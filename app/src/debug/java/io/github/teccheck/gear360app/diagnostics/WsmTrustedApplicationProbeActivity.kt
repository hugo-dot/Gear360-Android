package io.github.teccheck.gear360app.diagnostics

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.sun.jna.NativeLibrary
import com.sun.jna.Memory
import io.github.teccheck.gear360app.transport.nativegear360.sap.SamsungWsmClientAuthenticator

/**
 * Debug-only probe for the Samsung WSM Trusted Application.
 *
 * It runs in an isolated app process because vendor TrustZone libraries are not
 * part of Android's public ABI and may terminate the calling process.
 */
class WsmTrustedApplicationProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread({ runProbe() }, "G360-WSM-probe").start()
    }

    private fun runProbe() {
        val appId = Process.myPid()
        var prepared = false
        try {
            SamsungWsmClientAuthenticator("34:2D:0D:94:7F:29", "CC:E9:FA:67:51:1B").use { client ->
                val challenge = CAPTURED_CHALLENGE.split(" ").map { it.toInt(16).toByte() }.toByteArray()
                val response = client.answerChallenge(challenge)
                Log.i(TAG, "SOFTWARE WSM RESPONSE len=${response.size} header=${response.take(3).joinToString(" ") { "%02X".format(it) }}")
            }
            if (!intent.getBooleanExtra("test_trustzone", false)) return
            Log.i(TAG, "Loading libwsmd_functions appId=$appId")
            val library = NativeLibrary.getInstance("wsmd_functions")
            val prepare = library.getFunction("prepare_trusted_app")
            val result = prepare.invokeInt(arrayOf(appId))
            prepared = result == PREPARE_SUCCESS
            Log.i(TAG, "prepare_trusted_app result=$result prepared=$prepared")

            if (prepared) {
                val command = library.getFunction("provide_command_to_trusted_app")
                Memory(0x406c).use { buffer ->
                    buffer.clear()
                    buffer.setInt(4, 0x20)
                    buffer.setString(0x0c, "34:2D:0D:94:7F:29", "UTF-8")
                    buffer.setString(0x8c, "CC:E9:FA:67:51:1B", "UTF-8")
                    val dispatched = command.invokeInt(arrayOf(appId, buffer))
                    val status = buffer.getInt(0)
                    val operation = buffer.getInt(0x16c)
                    val handle = buffer.getInt(0x170)
                    Log.i(TAG, "clientInit dispatch=$dispatched status=$status operation=$operation handle=$handle")
                    if (status == 1 && operation == 0 && handle > 0) {
                        val challenge = CAPTURED_CHALLENGE.split(" ").map { it.toInt(16).toByte() }.toByteArray()
                        buffer.clear()
                        buffer.setInt(4, 0x50)
                        buffer.setInt(8, handle)
                        buffer.write(0x0c, challenge, 0, challenge.size)
                        val challengeDispatch = command.invokeInt(arrayOf(appId, buffer))
                        val response = buffer.getByteArray(0x52, 102)
                        Log.i(TAG, "challenge dispatch=$challengeDispatch status=${buffer.getInt(0)} operation=${buffer.getInt(0xb8)} responseHeader=${response.take(3).joinToString(" ") { "%02X".format(it) }} nonzero=${response.count { it != 0.toByte() }}")
                        buffer.clear()
                        buffer.setInt(4, 0x30)
                        buffer.setInt(8, handle)
                        command.invokeInt(arrayOf(appId, buffer))
                        Log.i(TAG, "clientDestroy status=${buffer.getInt(0)} operation=${buffer.getInt(0x0c)}")
                    }
                }
            }

            if (!prepared) {
                Log.e(
                    TAG,
                    "Samsung WSM Trusted Application is unavailable to this app; " +
                        "native Gear 360 authentication cannot continue on this device"
                )
            }

            if (prepared) {
                val finish = library.getFunction("finish_work_with_trusted_app")
                val finishResult = finish.invokeInt(arrayOf(appId))
                Log.i(TAG, "finish_work_with_trusted_app result=$finishResult")
                prepared = false
            }
        } catch (error: Throwable) {
            Log.e(TAG, "WSM Trusted Application probe failed", error)
        } finally {
            if (prepared) {
                Log.w(TAG, "Trusted Application remained prepared after probe failure")
            }
            runOnUiThread { finish() }
        }
    }

    private companion object {
        const val TAG = "G360-WSM-PROBE"
        const val PREPARE_SUCCESS = 1
        const val CAPTURED_CHALLENGE = "00 00 46 04 CF 95 AF 4F F1 25 68 23 78 72 C9 78 EB 5E B3 23 49 60 D6 F7 EB 5A 8B AE 3E 20 4C E1 FC DB 14 8B 39 0C 47 0C 88 DD 1B E0 80 1F A3 93 03 AD B4 48 77 F6 2A 38 66 D5 7B 18 ED 7B 68 1E 93 62 76 7F E4 FD"
    }
}
