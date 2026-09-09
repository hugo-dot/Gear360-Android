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
                // The native SAP session is still under hardware validation. When the
                // matching Samsung framework is available it is the reference backend,
                // including on Android 12+, with native transport kept as the fallback.
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
