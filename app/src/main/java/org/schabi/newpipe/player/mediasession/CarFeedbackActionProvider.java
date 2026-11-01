package org.schabi.newpipe.player.mediasession;

import android.content.Context;
import android.os.Bundle;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;

import org.schabi.newpipe.R;
import org.schabi.newpipe.player.Player;

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
    private final Context context;
    private final boolean positive;

    public CarFeedbackActionProvider(@NonNull final Player player,
                                     @NonNull final Context context,
                                     final boolean positive) {
        this.player = player;
        this.context = context.getApplicationContext();
        this.positive = positive;
    }

    @Nullable
    @Override
    public PlaybackStateCompat.CustomAction getCustomAction(
            @NonNull final com.google.android.exoplayer2.Player exoPlayer) {
        if (player.getCurrentStreamInfo().isEmpty()) {
            return null;
        }
        final String url = player.getCurrentStreamInfo().get().getUrl();
        final int selected = org.schabi.newpipe.util.FlowPlayRecommendations.feedbackFor(
                context, url);
        return new PlaybackStateCompat.CustomAction.Builder(
                positive ? ACTION_UP : ACTION_DOWN,
                positive
                        ? (selected == 1 ? "👍 Recommended" : "👍 Recommend more")
                        : (selected == -1 ? "👎 Not recommended" : "👎 Don't recommend"),
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
        // Delegate to Player, which updates the queue and skips the disliked
        // current item. Saving a preference without changing playback is not enough.
        player.applyFlowPlayFeedback(ACTION_UP.equals(action));
    }
}
