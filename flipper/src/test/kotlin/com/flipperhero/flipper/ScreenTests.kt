package com.flipperhero.flipper

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.screen.InputKey
import com.flipperdevices.protobuf.screen.InputType
import com.flipperdevices.protobuf.screen.ScreenFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** Ported from Swift `ScreenTests`. */
class ScreenTests {

    /** Builds a framebuffer with the given display pixels set, in u8g2 page layout. */
    private fun buffer(points: List<Pair<Int, Int>>): ByteArray {
        val bytes = ByteArray(1024)
        for ((x, y) in points) {
            val i = (y / 8) * 128 + x
            bytes[i] = (bytes[i].toInt() or (1 shl (y % 8))).toByte()
        }
        return bytes
    }

    @Test
    fun pageLayoutDecoding() {
        val frame = FlipperScreenFrame(buffer(listOf(0 to 0, 127 to 63, 5 to 9)))
        assertTrue(frame.pixel(0, 0))
        assertTrue(frame.pixel(127, 63))
        assertTrue(frame.pixel(5, 9))
        assertFalse(frame.pixel(5, 8))
        assertFalse(frame.pixel(6, 9))
        assertEquals(3, frame.pixels().count { it })
    }

    @Test
    fun flippedOrientationRotates180() {
        val frame = FlipperScreenFrame(buffer(listOf(0 to 0)), FlipperScreenFrame.Orientation.FLIPPED)
        assertTrue(frame.pixel(127, 63))
        assertFalse(frame.pixel(0, 0))
    }

    @Test
    fun verticalOrientationSwapsSize() {
        val frame = FlipperScreenFrame(buffer(emptyList()), FlipperScreenFrame.Orientation.VERTICAL)
        assertEquals(64, frame.displaySize.width)
        assertEquals(128, frame.displaySize.height)
    }

    @Test
    fun captureReceivesTheUnsolicitedFrame(): Unit = runBlocking {
        val transport = MockTransport()
        val pixelBuffer = buffer(listOf(10 to 10))
        transport.responder = { request ->
            when (request.contentCase) {
                Main.ContentCase.GUI_START_SCREEN_STREAM_REQUEST -> {
                    val unsolicited = Main.newBuilder().setCommandId(0)
                        .setGuiScreenFrame(ScreenFrame.newBuilder().setData(pixelBuffer.toByteString())).build()
                    listOf(reply(request), unsolicited)
                }
                else -> listOf(reply(request))
            }
        }
        val client = FlipperRpcClient(transport, 2.seconds)
        client.start()
        val frame = client.captureScreen(2.seconds)
        assertTrue(frame.pixel(10, 10))
        delay(100)
        val stops = transport.requests.count { it.contentCase == Main.ContentCase.GUI_STOP_SCREEN_STREAM_REQUEST }
        assertEquals(1, stops, "the stream is stopped again after a one-off capture")
    }

    @Test
    fun sharedStreamIsStartedOnceAndStoppedLast(): Unit = runBlocking {
        val transport = MockTransport()
        transport.responder = { listOf(reply(it)) }
        val client = FlipperRpcClient(transport, 2.seconds)
        client.start()
        client.acquireScreenStream()
        client.acquireScreenStream()
        client.releaseScreenStream()
        fun count(case: Main.ContentCase) = transport.requests.count { it.contentCase == case }
        assertEquals(1, count(Main.ContentCase.GUI_START_SCREEN_STREAM_REQUEST))
        assertEquals(0, count(Main.ContentCase.GUI_STOP_SCREEN_STREAM_REQUEST))
        client.releaseScreenStream()
        assertEquals(1, count(Main.ContentCase.GUI_STOP_SCREEN_STREAM_REQUEST))
    }

    @Test
    fun pressSendsPressShortRelease(): Unit = runBlocking {
        val transport = MockTransport()
        transport.responder = { listOf(reply(it)) }
        val client = FlipperRpcClient(transport, 2.seconds)
        client.start()
        client.press(FlipperKey.OK)
        client.press(FlipperKey.BACK, long = true)
        val events = transport.requests.mapNotNull {
            if (it.contentCase == Main.ContentCase.GUI_SEND_INPUT_EVENT_REQUEST) it.guiSendInputEventRequest else null
        }
        assertEquals(
            listOf(InputType.PRESS, InputType.SHORT, InputType.RELEASE, InputType.PRESS, InputType.LONG, InputType.RELEASE),
            events.map { it.type },
        )
        assertEquals(
            listOf(InputKey.OK, InputKey.OK, InputKey.OK, InputKey.BACK, InputKey.BACK, InputKey.BACK),
            events.map { it.key },
        )
    }

    @Test
    fun pressSendsAllEventsBeforeWaitingForAnswers(): Unit = runBlocking {
        val transport = MockTransport()
        val held = ArrayList<Main>()
        transport.responder = { request ->
            held.add(request)
            if (held.size < 3) {
                emptyList()
            } else {
                val answers = held.map { reply(it) }
                held.clear()
                answers
            }
        }
        val client = FlipperRpcClient(transport, 1.seconds)
        client.start()
        client.press(FlipperKey.DOWN)
        client.press(FlipperKey.UP, long = true)
        assertEquals(6, transport.requests.size)
    }

    @Test
    fun pressReportsARejectedEvent(): Unit = runBlocking {
        val transport = MockTransport()
        transport.responder = { request ->
            val isShort = request.contentCase == Main.ContentCase.GUI_SEND_INPUT_EVENT_REQUEST &&
                request.guiSendInputEventRequest.type == InputType.SHORT
            listOf(reply(request, status = if (isShort) CommandStatus.ERROR_INVALID_PARAMETERS else CommandStatus.OK))
        }
        val client = FlipperRpcClient(transport, 1.seconds)
        client.start()
        assertThrowsSuspend<FlipperException.Rpc> { client.press(FlipperKey.OK) }
        // Nothing left behind: the next press works normally.
        client.press(FlipperKey.OK, long = true)
    }
}
