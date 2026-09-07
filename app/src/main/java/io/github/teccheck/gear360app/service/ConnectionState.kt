package io.github.teccheck.gear360app.service

enum class ConnectionState {
    INVALID,
    DISCONNECTED,
    DISCOVERING,
    PAIRING,
    BT_BONDED,
    ACCESSORY_CONNECTING,
    ACCESSORY_CONNECTED,
    SAP_DISCOVERING,
    SAP_PEER_FOUND,
    SAP_CONNECTING,
    SAP_SOCKET_CONNECTED,
    PROTOCOL_SYNCING,
    BT_CONNECTING,
    CONNECTING,
    BT_CONNECTED,
    GETTING_CAMERA_INFO,
    WIFI_AVAILABLE,
    WIFI_CONNECTING,
    READY,
    CAPTURING,
    RECORDING,
    DOWNLOADING,
    ERROR,
    CONNECTED,
    ;

    fun isConnecting(): Boolean {
        return this == CONNECTING ||
            this == BT_CONNECTING ||
            this == ACCESSORY_CONNECTING ||
            this == ACCESSORY_CONNECTED ||
            this == SAP_DISCOVERING ||
            this == SAP_PEER_FOUND ||
            this == SAP_CONNECTING ||
            this == SAP_SOCKET_CONNECTED ||
            this == PROTOCOL_SYNCING ||
            this == GETTING_CAMERA_INFO ||
            this == WIFI_CONNECTING ||
            this == PAIRING
    }

    fun hasControlChannel(): Boolean {
        return this == SAP_SOCKET_CONNECTED ||
            this == PROTOCOL_SYNCING ||
            this == CONNECTED ||
            this == READY ||
            this == CAPTURING ||
            this == RECORDING ||
            this == DOWNLOADING
    }

    fun isReadyForCapture(): Boolean {
        return this == CONNECTED ||
            this == READY ||
            this == RECORDING
    }
}
