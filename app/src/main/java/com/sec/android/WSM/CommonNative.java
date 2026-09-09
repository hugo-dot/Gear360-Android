package com.sec.android.WSM;

/** Minimal JNI ABI used to select the response size expected by WSM. */
public final class CommonNative {
    static {
        System.loadLibrary("wsm2_jni");
    }

    public native int getCurrentProtocolVersion();

    public native boolean isDaemonReachable();

    public native int setProtocolVersion(int version);
}
