package org.schabi.newpipe.util;

import android.os.SystemClock;

import androidx.annotation.NonNull;

/**
 * Best-effort Android Auto connection observation from the validated media browser.
 *
 * Wireless Android Auto uses a local Wi-Fi projection link but media can still
 * consume the phone's cellular data. This signal is an additional conservative
 * data-saver hint, not a claim that the app can control car connectivity.
 */
public final class CarConnectionTracker {
    private static final long MAX_AGE_MILLIS = 90L * 60L * 1000L;
    private static volatile long lastCarBrowserAccess = -1L;

    private CarConnectionTracker() { }

    public static void noteMediaBrowserClient(@NonNull final String callerPackage) {
        if ("com.google.android.projection.gearhead".equals(callerPackage)
                || "com.google.android.apps.auto".equals(callerPackage)
                || callerPackage.contains("android.projection.gearhead")) {
            lastCarBrowserAccess = SystemClock.elapsedRealtime();
        }
    }

    public static boolean isRecentlyConnected() {
        final long lastAccess = lastCarBrowserAccess;
        final long now = SystemClock.elapsedRealtime();
        return lastAccess >= 0L && now >= lastAccess
                && now - lastAccess < MAX_AGE_MILLIS;
    }
}
