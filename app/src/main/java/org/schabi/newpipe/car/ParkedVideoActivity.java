/*
 * Flow Play parked-video test entry point.
 * Only Android Auto hosts that support parked-video projection can launch this activity.
 * The platform is responsible for preventing its display while driving.
 * We additionally stop playback on pause/stop to avoid leaving video running
 * behind the system's driving-safety overlay.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.car;

import android.os.Bundle;
import androidx.annotation.NonNull;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.player.helper.PlayerHolder;

/**
 * Experimental Android Auto parked-video entry point.
 *
 * This deliberately is not exposed as an ordinary phone launcher entry.
 * Android Auto connects through android.intent.category.CAR_LAUNCHER on
 * platforms where parked-video activities are enabled.
 */
public class ParkedVideoActivity extends MainActivity {

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Reuse the full native video browser, playlists and player.
    }

    @Override
    protected void onPause() {
        // A car may cover this surface as soon as movement begins.
        // Stop playback, not merely video rendering, as a conservative
        // fallback. This class is only reachable via the car launcher.
        stopProjectedPlayback();
        super.onPause();
    }

    @Override
    protected void onStop() {
        stopProjectedPlayback();
        super.onStop();
    }

    private void stopProjectedPlayback() {
        final PlayerHolder holder = PlayerHolder.getInstance();
        if (holder.isPlayerOpen()) {
            holder.stopService();
        }
    }
}
