package org.schabi.newpipe.player.mediasession;

import android.content.Context;
import android.os.Bundle;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;

import org.schabi.newpipe.R;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.mediabrowser.CarPlaylistSaveHelper;

/**
 * Android Auto playback-screen action. Car head units may show this in
 * the transport controls or their overflow menu, depending on host limits.
 */
public final class CarSaveActionProvider implements MediaSessionConnector.CustomActionProvider {
    private static final String SAVE_ACTION = "org.schabi.newpipe.car.save-current";
    private final Player player;
    private final Context context;

    public CarSaveActionProvider(@NonNull final Player player, @NonNull final Context context) {
        this.player = player;
        this.context = context.getApplicationContext();
    }

    @Nullable
    @Override
    public PlaybackStateCompat.CustomAction getCustomAction(
            @NonNull final com.google.android.exoplayer2.Player exoPlayer) {
        if (player.getCurrentStreamInfo().isEmpty()) {
            return null;
        }
        return new PlaybackStateCompat.CustomAction.Builder(
                SAVE_ACTION,
                "Save to Car Saves",
                R.drawable.ic_playlist_add
        ).build();
    }

    @Override
    public void onCustomAction(
            @NonNull final com.google.android.exoplayer2.Player exoPlayer,
            @NonNull final String action,
            @Nullable final Bundle extras) {
        if (SAVE_ACTION.equals(action)) {
            player.getCurrentStreamInfo()
                    .ifPresent(info -> CarPlaylistSaveHelper.saveToCarSaves(context, info));
        }
    }
}
