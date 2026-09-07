package io.github.teccheck.gear360app.bluetooth

import io.github.teccheck.gear360app.service.CameraMode
import io.github.teccheck.gear360app.service.CaptureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderTest {
    @Test
    fun photoCaptureUsesSapChannel204AndCaptureCommand() {
        val packets = mutableListOf<Pair<Int, String>>()
        val sender = MessageSender { channel, data ->
            packets += channel to data.decodeToString()
        }

        sender.sendCaptureRequest(CameraMode.PHOTO)

        assertEquals(1, packets.size)
        assertEquals(204, packets.single().first)
        assertTrue(packets.single().second.contains("\"msgId\":\"shot-req\""))
        assertTrue(packets.single().second.contains("\"description\":\"capture\""))
    }

    @Test
    fun videoCaptureUsesRecordCommand() {
        val packets = mutableListOf<Pair<Int, String>>()
        val sender = MessageSender { channel, data ->
            packets += channel to data.decodeToString()
        }

        sender.sendCaptureRequest(CameraMode.VIDEO)

        assertEquals(1, packets.size)
        assertEquals(204, packets.single().first)
        assertTrue(packets.single().second.contains("\"description\":\"record\""))
    }

    @Test
    fun stopRecordingUsesRecordStopCommand() {
        val packets = mutableListOf<Pair<Int, String>>()
        val sender = MessageSender { channel, data ->
            packets += channel to data.decodeToString()
        }

        sender.sendCaptureStopRequest(CameraMode.VIDEO, CaptureState.RECORDING)

        assertEquals(1, packets.size)
        assertEquals(204, packets.single().first)
        assertTrue(packets.single().second.contains("\"description\":\"record stop\""))
    }

    @Test
    fun stopWhenCaptureStateIsNoneDoesNotSendCommand() {
        val packets = mutableListOf<Pair<Int, String>>()
        val sender = MessageSender { channel, data ->
            packets += channel to data.decodeToString()
        }

        sender.sendCaptureStopRequest(CameraMode.VIDEO, CaptureState.NONE)

        assertTrue(packets.isEmpty())
    }
}
