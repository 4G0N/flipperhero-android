package com.flipperhero.flipper

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.screen.StartScreenStreamRequest
import com.flipperdevices.protobuf.screen.StopScreenStreamRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Sends [Main] requests over a [Transport] and matches responses by command id. Calls are
 * serialized: the Flipper RPC session is single-threaded. Mirrors the Swift `FlipperRPCClient`
 * actor, using a [Mutex] for the "one call at a time" invariant and a second mutex for state.
 */
class FlipperRpcClient(
    private val transport: Transport,
    private val defaultTimeout: Duration = 20.seconds,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private class Pending {
        val parts = ArrayList<Main>()
        val deferred = CompletableDeferred<List<Main>>()
    }

    private val callMutex = Mutex()
    private val stateMutex = Mutex()
    private var nextId = 1
    private val pending = HashMap<Int, Pending>()
    private var decoder = FrameDecoder()
    private var readerJob: Job? = null
    private var screenStreamUsers = 0

    /** Subscribers for messages the Flipper sends on its own (screen frames use command id 0). */
    private val unsolicited = ConcurrentHashMap<UUID, SendChannel<Main>>()

    private val closedSignal = CompletableDeferred<Unit>()

    /** Completes once the transport's byte stream ends (link lost or closed). */
    val closed: Deferred<Unit> get() = closedSignal

    fun start() {
        if (readerJob != null) return
        readerJob = scope.launch {
            try {
                transport.incoming.collect { handle(it) }
            } finally {
                failAll(FlipperException.NotConnected)
                if (!closedSignal.isCompleted) closedSignal.complete(Unit)
            }
        }
    }

    suspend fun stop() {
        readerJob?.cancel()
        readerJob = null
        failAll(FlipperException.NotConnected)
        transport.close()
    }

    // MARK: - Core request/response

    /**
     * Sends one request made of one or more frames that share a command id and await a single
     * multi-part answer. [progress] is called after each frame goes out, with (sent, total).
     */
    suspend fun call(
        contents: List<RpcContent>,
        timeout: Duration? = null,
        progress: ((Int, Int) -> Unit)? = null,
    ): List<Main> {
        require(contents.isNotEmpty()) { "at least one request message is required" }
        return callMutex.withLock {
            val (id, p) = stateMutex.withLock {
                val id = allocateIdLocked()
                val p = Pending()
                pending[id] = p
                id to p
            }
            val frames = contents.mapIndexed { index, setter ->
                val builder = Main.newBuilder().setCommandId(id).setHasNext(index < contents.size - 1)
                builder.setter()
                FrameCodec.encode(builder.build())
            }
            val limit = timeout ?: defaultTimeout
            val total = frames.size
            try {
                withTimeoutOrNull(limit) {
                    for ((index, frame) in frames.withIndex()) {
                        transport.send(frame)
                        progress?.invoke(index + 1, total)
                    }
                    p.deferred.await()
                } ?: run {
                    finish(id, Result.failure(FlipperException.Timeout))
                    throw FlipperException.Timeout
                }
            } finally {
                stateMutex.withLock { pending.remove(id) }
            }
        }
    }

    /** Single-message convenience; supports a trailing lambda: `call { setSystemPingRequest(...) }`. */
    suspend fun call(timeout: Duration? = null, content: RpcContent): List<Main> =
        call(listOf(content), timeout, null)

    /**
     * Sends independent single-message requests back to back and waits for all answers. The Flipper
     * handles them in order, so a short sequence such as a button press costs one round trip.
     */
    suspend fun callPipelined(contents: List<RpcContent>, timeout: Duration? = null) {
        require(contents.isNotEmpty())
        callMutex.withLock {
            val limit = timeout ?: defaultTimeout
            val entries = ArrayList<Pair<Int, Pending>>()
            val buffer = ByteArrayOutputStream()
            stateMutex.withLock {
                for (setter in contents) {
                    val id = allocateIdLocked()
                    val builder = Main.newBuilder().setCommandId(id)
                    builder.setter()
                    buffer.write(FrameCodec.encode(builder.build()))
                    val p = Pending()
                    pending[id] = p
                    entries.add(id to p)
                }
            }
            try {
                transport.send(buffer.toByteArray())
                for ((_, p) in entries) {
                    withTimeoutOrNull(limit) { p.deferred.await() } ?: throw FlipperException.Timeout
                }
            } finally {
                stateMutex.withLock { for ((id, _) in entries) pending.remove(id) }
            }
        }
    }

    // MARK: - Internals

    private fun allocateIdLocked(): Int {
        val id = nextId
        nextId = if (nextId == Int.MAX_VALUE) 1 else nextId + 1
        return id
    }

    private suspend fun handle(chunk: ByteArray) {
        val messages = try {
            stateMutex.withLock { decoder.append(chunk) }
        } catch (e: FlipperException) {
            stateMutex.withLock { decoder = FrameDecoder() }
            failAll(e)
            return
        }
        for (m in messages) dispatch(m)
    }

    private suspend fun dispatch(message: Main) {
        val id = message.commandId
        stateMutex.withLock {
            val entry = pending[id]
            if (entry == null) {
                for (ch in unsolicited.values) ch.trySend(message)
                return@withLock
            }
            if (message.commandStatus != CommandStatus.OK) {
                finishLocked(id, Result.failure(FlipperException.Rpc(message.commandStatus.name)))
                return@withLock
            }
            entry.parts.add(message)
            if (!message.hasNext) finishLocked(id, Result.success(entry.parts.toList()))
        }
    }

    private fun finishLocked(id: Int, result: Result<List<Main>>) {
        val entry = pending.remove(id) ?: return
        if (!entry.deferred.isCompleted) {
            result.fold(
                onSuccess = { entry.deferred.complete(it) },
                onFailure = { entry.deferred.completeExceptionally(it) },
            )
        }
    }

    private suspend fun finish(id: Int, result: Result<List<Main>>) =
        stateMutex.withLock { finishLocked(id, result) }

    private suspend fun failAll(error: Throwable) = stateMutex.withLock {
        for (id in pending.keys.toList()) finishLocked(id, Result.failure(error))
        if (error is FlipperException.NotConnected) {
            for (ch in unsolicited.values) ch.close()
            unsolicited.clear()
        }
    }

    // MARK: - Unsolicited / screen stream

    /** Messages that do not answer a pending request. Each call returns an independent stream. */
    fun unsolicitedMessages(): Flow<Main> = callbackFlow {
        val id = UUID.randomUUID()
        unsolicited[id] = channel
        awaitClose { unsolicited.remove(id) }
    }

    /** Starts the firmware's screen stream for the first user; later users share it. */
    suspend fun acquireScreenStream() {
        val shouldStart = stateMutex.withLock {
            screenStreamUsers++
            screenStreamUsers == 1
        }
        if (!shouldStart) return
        try {
            call { setGuiStartScreenStreamRequest(StartScreenStreamRequest.getDefaultInstance()) }
        } catch (e: FlipperException.Rpc) {
            if (!e.status.contains("AlreadyStarted", ignoreCase = true)) {
                stateMutex.withLock { screenStreamUsers-- }
                throw e
            }
        } catch (e: Throwable) {
            stateMutex.withLock { screenStreamUsers-- }
            throw e
        }
    }

    /** Stops the stream when the last user is done. */
    suspend fun releaseScreenStream() {
        val shouldStop = stateMutex.withLock {
            if (screenStreamUsers > 0) {
                screenStreamUsers--
                screenStreamUsers == 0
            } else {
                false
            }
        }
        if (shouldStop) runCatching { call { setGuiStopScreenStreamRequest(StopScreenStreamRequest.getDefaultInstance()) } }
    }

    /** Live frames. The caller must hold the stream via [acquireScreenStream]. */
    fun screenFrames(): Flow<FlipperScreenFrame> = callbackFlow {
        val id = UUID.randomUUID()
        val relay = Channel<Main>(Channel.BUFFERED)
        unsolicited[id] = relay
        val pump = scope.launch {
            for (m in relay) {
                if (m.contentCase == Main.ContentCase.GUI_SCREEN_FRAME) {
                    FlipperScreenFrame.from(m.guiScreenFrame)?.let { trySend(it) }
                }
            }
        }
        awaitClose {
            unsolicited.remove(id)
            relay.close()
            pump.cancel()
        }
    }

    /**
     * One current frame. Starting the stream makes the firmware redraw, so a frame arrives even on
     * a static screen. The subscriber is registered synchronously before the stream starts, so the
     * redraw frame is not missed.
     */
    suspend fun captureScreen(timeout: Duration = 5.seconds): FlipperScreenFrame {
        val id = UUID.randomUUID()
        val channel = Channel<Main>(Channel.UNLIMITED)
        unsolicited[id] = channel
        try {
            acquireScreenStream()
            return withTimeoutOrNull(timeout) {
                for (m in channel) {
                    if (m.contentCase == Main.ContentCase.GUI_SCREEN_FRAME) {
                        FlipperScreenFrame.from(m.guiScreenFrame)?.let { return@withTimeoutOrNull it }
                    }
                }
                null
            } ?: throw FlipperException.Timeout
        } finally {
            unsolicited.remove(id)
            channel.close()
            scope.launch { releaseScreenStream() }
        }
    }
}
