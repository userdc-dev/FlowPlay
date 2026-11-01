package org.schabi.newpipe.player.mediasession;

import android.content.Context;
import android.os.Bundle;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;

import org.schabi.newpipe.R;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.util.FlowPlayRecommendations;

/**
 * Opt-in local feedback on the current video or audio item, exposed to Android
 * Auto through MediaSession. Host vehicles control which actions are visible;
 * some show them only in playback overflow.
 */
public final class CarFeedbackActionProvider
        implements MediaSessionConnector.CustomActionProvider {
    private static final String ACTION_UP = "org.schabi.newpipe.car.recommend-more";
    private static final String ACTION_DOWN = "org.schabi.newpipe.car.do-not-recommend";

    private final Player player;
    private final Context appContext;
    private final boolean positive;

    public CarFeedbackActionProvider(@NonNull final Player player,
                                     @NonNull final Context context,
                                     final boolean positive) {
        this.player = player;
        this.appContext = context.getApplicationContext();
        this.positive = positive;
    }

    @Nullable
    @Override
    public PlaybackStateCompat.CustomAction getCustomAction(
            @NonNull final com.google.android.exoplayer2.Player exoPlayer) {
        if (player.getCurrentStreamInfo().isEmpty()) {
            return null;
        }
        return new PlaybackStateCompat.CustomAction.Builder(
                positive ? ACTION_UP : ACTION_DOWN,
                positive ? "Like 👍" : "Dislike 👎",
                positive ? R.drawable.ic_thumb_up : R.drawable.ic_thumb_down
        ).build();
    }

    @Override
    public void onCustomAction(
            @NonNull final com.google.android.exoplayer2.Player exoPlayer,
            @NonNull final String action,
            @Nullable final Bundle extras) {
        if (!ACTION_UP.equals(action) && !ACTION_DOWN.equals(action)) {
            return;
        }
        player.getCurrentStreamInfo().ifPresent(info -> {
            if (ACTION_UP.equals(action)) {
                FlowPlayRecommendations.recommendMore(appContext, info.getUrl(),
                        info.getUploaderName() == null ? "" : info.getUploaderName());
            } else {
                FlowPlayRecommendations.avoid(appContext, info.getUrl());
            }
        });
    }
}
