package io.github.teccheck.gear360app.transport.nativegear360.sap

class SapChannelMux {
    private val channels = mutableMapOf<Int, SapChannel>()
    private val channelBySession = mutableMapOf<Int, Int>()

    fun markOpening(channel: Int) {
        val previous = channels[channel]
        channels[channel] = SapChannel(
            id = channel,
            state = SapChannelState.OPENING,
            sessionId = previous?.sessionId
        )
    }

    fun markOpen(channel: Int, sessionId: Int? = channels[channel]?.sessionId) {
        channels[channel] = SapChannel(
            id = channel,
            state = SapChannelState.OPEN,
            sessionId = sessionId
        )
        if (sessionId != null) {
            channelBySession[sessionId] = channel
        }
    }

    fun markClosed(channel: Int) {
        val previous = channels[channel]
        if (previous?.sessionId != null) {
            channelBySession.remove(previous.sessionId)
        }
        channels[channel] = SapChannel(
            id = channel,
            state = SapChannelState.CLOSED,
            sessionId = previous?.sessionId
        )
    }

    fun bind(request: ServiceConnectionRequest): List<SapChannel> {
        channels.clear()
        channelBySession.clear()
        request.channels.forEach { record ->
            markOpen(record.channelId, record.sessionId)
        }
        return snapshot()
    }

    fun isOpen(channel: Int): Boolean {
        return channels[channel]?.state == SapChannelState.OPEN
    }

    fun sessionForChannel(channel: Int): Int? {
        return channels[channel]?.takeIf { it.state == SapChannelState.OPEN }?.sessionId
    }

    fun channelForSession(sessionId: Int): Int? {
        return channelBySession[sessionId]
    }

    fun toPayload(frame: SapFrame): SapPayload? {
        val channel = channelForSession(frame.sessionId) ?: frame.channel
        if (!isOpen(channel)) return null
        return SapPayload(channel, frame.payload)
    }

    fun snapshot(): List<SapChannel> {
        return channels.values.sortedBy { it.id }
    }
}
