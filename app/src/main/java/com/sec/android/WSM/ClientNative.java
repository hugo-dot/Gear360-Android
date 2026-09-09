package com.sec.android.WSM;

/** Minimal JNI ABI required by the Samsung WSM interoperability backend. */
public final class ClientNative {
    static {
        System.loadLibrary("wsm2_jni");
    }

    public native long init(String serverId, String clientId);

    public native int checkAndGenerateServerChallenge(
            long clientHandle,
            byte[] clientChallenge,
            byte[] serverChallenge
    );

    public native int checkClientResponse(long clientHandle, byte[] clientResponse);

    public native int destroy(long clientHandle);
}
