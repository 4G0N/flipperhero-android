package com.flipperhero.flipper

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Empty
import com.flipperdevices.protobuf.Main
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Simulated Flipper transport: decodes request frames and lets a responder script the reply.
 * Mirrors Swift `MockTransport`. Replies are delivered in 7-byte chunks to exercise reassembly.
 */
class MockTransport : Transport {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

    private val lock = Any()
    private val decoder = FrameDecoder()
    private val _requests = ArrayList<Main>()

    var responder: (Main) -> List<Main> = { emptyList() }

    val requests: List<Main> get() = synchronized(lock) { _requests.toList() }

    override suspend fun send(data: ByteArray) {
        val messages = synchronized(lock) { decoder.append(data) }
        for (message in messages) {
            synchronized(lock) { _requests.add(message) }
            for (replyMessage in responder(message)) {
                val frame = FrameCodec.encode(replyMessage)
                var offset = 0
                while (offset < frame.size) {
                    val end = minOf(offset + 7, frame.size)
                    channel.trySend(frame.copyOfRange(offset, end))
                    offset = end
                }
            }
        }
    }

    override suspend fun close() {
        channel.close()
    }

    fun inject(message: Main) {
        channel.trySend(FrameCodec.encode(message))
    }
}

/** Builds a reply to [request]; content defaults to Empty, matching Swift `reply(to:)`. */
fun reply(
    request: Main,
    hasNext: Boolean = false,
    status: CommandStatus = CommandStatus.OK,
    content: (Main.Builder.() -> Unit)? = null,
): Main {
    val builder = Main.newBuilder()
        .setCommandId(request.commandId)
        .setHasNext(hasNext)
        .setCommandStatus(status)
    if (content != null) builder.content() else builder.setEmpty(Empty.getDefaultInstance())
    return builder.build()
}
