package io.github.teccheck.gear360app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTest {
    @Test
    fun connectingStatesAreRecognized() {
        assertTrue(ConnectionState.BT_CONNECTING.isConnecting())
        assertTrue(ConnectionState.ACCESSORY_CONNECTED.isConnecting())
        assertTrue(ConnectionState.SAP_DISCOVERING.isConnecting())
        assertTrue(ConnectionState.PROTOCOL_SYNCING.isConnecting())
        assertTrue(ConnectionState.WIFI_CONNECTING.isConnecting())
        assertTrue(ConnectionState.PAIRING.isConnecting())
        assertFalse(ConnectionState.READY.isConnecting())
    }

    @Test
    fun controlChannelStatesAreRecognized() {
        assertFalse(ConnectionState.BT_CONNECTED.hasControlChannel())
        assertTrue(ConnectionState.SAP_SOCKET_CONNECTED.hasControlChannel())
        assertTrue(ConnectionState.PROTOCOL_SYNCING.hasControlChannel())
        assertTrue(ConnectionState.READY.hasControlChannel())
        assertTrue(ConnectionState.RECORDING.hasControlChannel())
        assertFalse(ConnectionState.WIFI_CONNECTING.hasControlChannel())
        assertFalse(ConnectionState.DISCONNECTED.hasControlChannel())
    }

    @Test
    fun readyForCaptureAllowsConnectedReadyWifiAndRecordingStates() {
        assertTrue(ConnectionState.READY.isReadyForCapture())
        assertTrue(ConnectionState.RECORDING.isReadyForCapture())
        assertFalse(ConnectionState.WIFI_AVAILABLE.isReadyForCapture())
        assertFalse(ConnectionState.PROTOCOL_SYNCING.isReadyForCapture())
        assertFalse(ConnectionState.CAPTURING.isReadyForCapture())
        assertFalse(ConnectionState.ERROR.isReadyForCapture())
    }
}
