package com.flipperhero.flipper

import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.system.DeviceInfoRequest
import com.flipperdevices.protobuf.system.PingRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ported from Swift `FramingTests`, plus JVM-grounded fragmentation/fuzz tests. */
class FramingTests {

    @Test
    fun varintRoundTrip() {
        for (value in listOf(0L, 1L, 127L, 128L, 300L, 16_383L, 16_384L, (1L shl 20))) {
            val bytes = Varint.encode(value)
            val (decoded, used) = Varint.decode(bytes, 0)!!
            assertEquals(value, decoded)
            assertEquals(bytes.size, used)
        }
    }

    @Test
    fun varintIncompleteReturnsNull() {
        assertNull(Varint.decode(byteArrayOf(0x80.toByte()), 0))
    }

    @Test
    fun decoderReassemblesByteByByte() {
        val a = Main.newBuilder().setCommandId(1).setSystemPingRequest(PingRequest.getDefaultInstance()).build()
        val b = Main.newBuilder().setCommandId(2).setSystemDeviceInfoRequest(DeviceInfoRequest.getDefaultInstance()).build()
        val stream = FrameCodec.encode(a) + FrameCodec.encode(b)

        val decoder = FrameDecoder()
        val out = ArrayList<Main>()
        for (byte in stream) out += decoder.append(byteArrayOf(byte))
        assertEquals(listOf(1, 2), out.map { it.commandId })
    }

    @Test
    fun decoderRejectsOversizedFrame() {
        val decoder = FrameDecoder()
        val huge = Varint.encode(FrameCodec.MAX_FRAME_SIZE.toLong() + 1)
        assertThrows(FlipperException.FrameTooLarge::class.java) { decoder.append(huge) }
    }

    @Test
    fun decoderRejectsGarbageBody() {
        val decoder = FrameDecoder()
        val frame = Varint.encode(3) + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertThrows(FlipperException::class.java) { decoder.append(frame) }
    }

    @Test
    fun pathNormalization() {
        assertEquals("/ext/nfc/a.nfc", FlipperPath.normalize("/ext//nfc/./a.nfc"))
        assertEquals("/ext", FlipperPath.normalize("/ext/"))
        assertThrows(FlipperException.InvalidPath::class.java) { FlipperPath.normalize("/ext/../int/x") }
        assertThrows(FlipperException.InvalidPath::class.java) { FlipperPath.normalize("relative/path") }
        assertThrows(FlipperException.InvalidPath::class.java) { FlipperPath.normalize("/etc/passwd") }
        assertThrows(FlipperException.InvalidPath::class.java) { FlipperPath.normalize("/ext/a\nb") }
        assertThrows(FlipperException.InvalidPath::class.java) { FlipperPath.normalize("") }
        assertThrows(FlipperException.InvalidPath::class.java) { FlipperPath.normalize("/ext/" + "a".repeat(300)) }
    }

    // ---- JVM-grounded: Android BLE delivers notifications in MTU chunks, so one RPC message
    // arrives split across callbacks. This has no iOS analog and must reassemble correctly. ----

    @Test
    fun reassemblesAtEveryByteBoundary() {
        val message = Main.newBuilder()
            .setCommandId(42)
            .setSystemPingRequest(PingRequest.newBuilder().setData(ByteArray(37) { it.toByte() }.toByteString()))
            .build()
        val frame = FrameCodec.encode(message)
        // Split the single frame at every possible cut point and assert it reassembles to one message.
        for (cut in 0..frame.size) {
            val decoder = FrameDecoder()
            val out = ArrayList<Main>()
            out += decoder.append(frame.copyOfRange(0, cut))
            out += decoder.append(frame.copyOfRange(cut, frame.size))
            assertEquals(1, out.size, "cut at $cut")
            assertEquals(42, out[0].commandId)
            assertEquals(message, out[0])
        }
    }

    @Test
    fun fuzzEncodeFragmentReassembleRoundTrips() {
        val rng = java.util.Random(0xF11EL)
        repeat(300) { iteration ->
            // Build a random stream of 1..5 messages with random ids and payloads.
            val messages = (0 until (1 + rng.nextInt(5))).map {
                val payload = ByteArray(rng.nextInt(400)).also { rng.nextBytes(it) }
                Main.newBuilder()
                    .setCommandId(1 + rng.nextInt(1_000_000))
                    .setSystemPingRequest(PingRequest.newBuilder().setData(payload.toByteString()))
                    .build()
            }
            var stream = ByteArray(0)
            for (m in messages) stream += FrameCodec.encode(m)

            // Fragment into random-sized chunks (1..13 bytes) and feed them in order.
            val decoder = FrameDecoder()
            val out = ArrayList<Main>()
            var offset = 0
            while (offset < stream.size) {
                val end = minOf(offset + 1 + rng.nextInt(13), stream.size)
                out += decoder.append(stream.copyOfRange(offset, end))
                offset = end
            }
            assertEquals(messages, out, "iteration $iteration")
        }
    }
}
