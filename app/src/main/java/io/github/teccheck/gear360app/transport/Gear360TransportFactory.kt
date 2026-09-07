package io.github.teccheck.gear360app.transport

import android.content.Context
import io.github.teccheck.gear360app.transport.nativegear360.NativeGear360Transport

object Gear360TransportFactory {
    enum class Mode {
        AUTO,
        NATIVE,
        LEGACY_DIAGNOSTIC
    }

    fun createDefault(
        context: Context,
        listener: Gear360ControlTransport.Listener,
        mode: Mode = Mode.AUTO
    ): Gear360ControlTransport {
        return when (mode) {
            Mode.AUTO -> {
                val samsungStatus = SamsungAccessoryFramework.inspect(context)
                listener.onSamsungFrameworkStatus(
                    samsungStatus.diagnosticStatus,
                    samsungStatus.version
                )
                if (samsungStatus.installed && !samsungStatus.broken) {
                    LegacySamsungAccessoryTransport(context, listener)
                } else {
                    NativeGear360Transport(context, listener)
                }
            }

            Mode.NATIVE -> NativeGear360Transport(context, listener)
            Mode.LEGACY_DIAGNOSTIC -> LegacySamsungAccessoryTransport(context, listener)
        }
    }

    fun createNativeDiagnostics(
        context: Context,
        listener: Gear360ControlTransport.Listener
    ): Gear360ControlTransport {
        return NativeGear360Transport(context, listener)
    }

    fun createLegacyDiagnostic(
        context: Context,
        listener: Gear360ControlTransport.Listener
    ): Gear360ControlTransport {
        return LegacySamsungAccessoryTransport(context, listener)
    }
}
