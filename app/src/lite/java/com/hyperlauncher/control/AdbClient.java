package com.hyperlauncher.control;

import android.content.Context;

import java.io.IOException;

/**
 * Lite edition: there is no ADB client in this build. The Control Center calls the same API as in the full edition, so
 * every call fails the way it does when ADB is off, and the screens fall back to Android's own dialogs and settings.
 */
public final class AdbClient {
    private static final AdbClient INSTANCE = new AdbClient();

    private AdbClient() {}

    public static AdbClient get(Context context) {
        return INSTANCE;
    }

    public void setTarget(String host, int port) {}

    public boolean isConnected() {
        return false;
    }

    public void connect() throws IOException {
        throw new IOException("not part of the Lite edition");
    }

    public void close() {}

    public String shell(String command) throws IOException {
        throw new IOException("not part of the Lite edition");
    }
}
