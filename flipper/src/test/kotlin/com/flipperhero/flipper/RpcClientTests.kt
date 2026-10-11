package com.flipperhero.flipper

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.storage.File
import com.flipperdevices.protobuf.storage.ListResponse
import com.flipperdevices.protobuf.storage.ReadResponse
import com.flipperdevices.protobuf.storage.StatResponse
import com.flipperdevices.protobuf.system.DeviceInfoResponse
import com.flipperdevices.protobuf.system.PingResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Ported from Swift `RPCClientTests`. */
class RpcClientTests {

    private fun makeClient(
        timeout: kotlin.time.Duration = 5.seconds,
        responder: (Main) -> List<Main>,
    ): Pair<FlipperRpcClient, MockTransport> {
        val transport = MockTransport()
        transport.responder = responder
        val client = FlipperRpcClient(transport, timeout)
        client.start()
        return client to transport
    }

    @Test
    fun ping(): Unit = runBlocking {
        val (client, _) = makeClient { request ->
            if (request.contentCase != Main.ContentCase.SYSTEM_PING_REQUEST) return@makeClient emptyList()
            listOf(reply(request) { setSystemPingResponse(PingResponse.newBuilder().setData(request.systemPingRequest.data)) })
        }
        client.ping(byteArrayOf(9, 8, 7))
    }

    @Test
    fun deviceInfoAggregatesParts(): Unit = runBlocking {
        val (client, _) = makeClient { request ->
            fun part(k: String, v: String, next: Boolean) =
                reply(request, hasNext = next) {
                    setSystemDeviceInfoResponse(DeviceInfoResponse.newBuilder().setKey(k).setValue(v))
                }
            listOf(
                part("hardware_name", "Laisear", true),
                part("firmware_commit", "d3f89dfe", true),
                part("firmware_version", "mntm-dev", false),
            )
        }
        val info = client.deviceInfo()
        assertEquals("Laisear", info["hardware_name"])
        assertEquals("d3f89dfe", info["firmware_commit"])
        assertEquals(3, info.size)
    }

    @Test
    fun listSortsDirectoriesFirst(): Unit = runBlocking {
        val (client, _) = makeClient { request ->
            fun f(n: String, dir: Boolean, size: Int = 0) =
                File.newBuilder().setName(n).setType(if (dir) File.FileType.DIR else File.FileType.FILE).setSize(size).build()
            val r = ListResponse.newBuilder()
                .addFile(f("b.sub", false, 10)).addFile(f("nfc", true)).addFile(f("a.sub", false, 5))
            listOf(reply(request) { setStorageListResponse(r) })
        }
        val entries = client.list("/ext")
        assertEquals(listOf("nfc", "a.sub", "b.sub"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
    }

    @Test
    fun readChecksSizeBeforeTransfer(): Unit = runBlocking {
        val (client, transport) = makeClient { request ->
            when (request.contentCase) {
                Main.ContentCase.STORAGE_STAT_REQUEST -> listOf(reply(request) {
                    setStorageStatResponse(StatResponse.newBuilder()
                        .setFile(File.newBuilder().setSize(10_000_000).setType(File.FileType.FILE)))
                })
                else -> emptyList()
            }
        }
        assertThrowsSuspend<FlipperException.Rpc> { client.read("/ext/big.bin", maxBytes = 1024) }
        val startedRead = transport.requests.any { it.contentCase == Main.ContentCase.STORAGE_READ_REQUEST }
        assertFalse(startedRead, "must not start a transfer for oversized files")
    }

    @Test
    fun readConcatenatesParts(): Unit = runBlocking {
        val (client, _) = makeClient { request ->
            when (request.contentCase) {
                Main.ContentCase.STORAGE_STAT_REQUEST -> listOf(reply(request) {
                    setStorageStatResponse(StatResponse.newBuilder().setFile(File.newBuilder().setSize(6).setType(File.FileType.FILE)))
                })
                Main.ContentCase.STORAGE_READ_REQUEST -> {
                    fun chunk(s: String, next: Boolean) = reply(request, hasNext = next) {
                        setStorageReadResponse(ReadResponse.newBuilder().setFile(File.newBuilder().setData(s.toByteArray().toByteString())))
                    }
                    listOf(chunk("abc", true), chunk("def", false))
                }
                else -> emptyList()
            }
        }
        val data = client.read("/ext/a.txt")
        assertEquals("abcdef", String(data, Charsets.UTF_8))
    }

    @Test
    fun rpcErrorStatusBecomesError(): Unit = runBlocking {
        val (client, _) = makeClient { request -> listOf(reply(request, status = CommandStatus.ERROR_STORAGE_NOT_EXIST)) }
        val e = assertThrowsSuspend<FlipperException.Rpc> { client.list("/ext/missing") }
        assertTrue(e.status.replace("_", "").lowercase().contains("notexist"), e.status)
    }

    @Test
    fun timeout(): Unit = runBlocking {
        val (client, _) = makeClient(timeout = 150.milliseconds) { emptyList() }
        assertThrowsSuspend<FlipperException.Timeout> { client.ping() }
    }

    @Test
    fun writeChunksShareCommandIdAndSetHasNext(): Unit = runBlocking {
        val (client, transport) = makeClient { request ->
            // Only answer once the final chunk (has_next == false) arrives.
            if (request.hasNext) emptyList() else listOf(reply(request))
        }
        val payload = ByteArray(FlipperLimits.WRITE_CHUNK_SIZE * 2 + 100) { 0x41 }
        client.write("/ext/test.txt", payload)
        val writes = transport.requests.filter { it.contentCase == Main.ContentCase.STORAGE_WRITE_REQUEST }
        assertEquals(3, writes.size)
        assertEquals(1, writes.map { it.commandId }.toSet().size)
        assertEquals(listOf(true, true, false), writes.map { it.hasNext })
        val total = writes.sumOf { it.storageWriteRequest.file.data.size() }
        assertEquals(payload.size, total)
    }

    @Test
    fun invalidPathNeverReachesTransport(): Unit = runBlocking {
        val (client, transport) = makeClient { emptyList() }
        assertThrowsSuspend<FlipperException.InvalidPath> { client.delete("/ext/../int/secret", recursive = true) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun callsAreSerialized(): Unit = runBlocking {
        val (client, _) = makeClient { request ->
            if (request.contentCase != Main.ContentCase.SYSTEM_PING_REQUEST) return@makeClient emptyList()
            listOf(reply(request) { setSystemPingResponse(PingResponse.newBuilder().setData(request.systemPingRequest.data)) })
        }
        (0 until 10).map { i -> async { client.ping(byteArrayOf(i.toByte())) } }.awaitAll()
    }
}
