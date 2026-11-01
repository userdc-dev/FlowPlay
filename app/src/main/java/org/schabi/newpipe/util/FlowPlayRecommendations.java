package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small, on-device, no-account suggestion ranking. */
public final class FlowPlayRecommendations {
    public static final String AVOID_RECENT = "flowplay_v2_avoid_recent";
    public static final String MORE_ARTISTS = "flowplay_v2_mix_artists";
    private static final String RECENT_KEY = "flowplay_v2_recent_streams";
    private static final String SKIPPED_KEY = "flowplay_v2_skipped_streams";
    private static final String LIKED_KEY = "flowplay_v2_liked_streams";
    private static final String FAVOURITE_UPLOADERS_KEY = "flowplay_v2_liked_uploaders";
    private static final int HISTORY_SIZE = 35;

    private FlowPlayRecommendations() { }

    public static void markPlayed(@NonNull final Context context,
                                  @NonNull final String url) {
        if (url.isEmpty()) {
            return;
        }
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final LinkedHashSet<String> history = new LinkedHashSet<>();
        history.add(url);
        final String previous = prefs.getString(RECENT_KEY, "");
        for (final String old : previous.split("\n")) {
            if (!old.isEmpty() && history.size() < HISTORY_SIZE) {
                history.add(old);
            }
        }
        prefs.edit().putString(RECENT_KEY, String.join("\n", history)).apply();
    }

    /** Local-only feedback. It does not upload likes to YouTube or modify playlists. */
    public static void recommendMore(@NonNull final Context context,
                                     @NonNull final String url,
                                     @NonNull final String uploader) {
        if (url.isEmpty()) {
            return;
        }
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final Set<String> liked = new HashSet<>(
                prefs.getStringSet(LIKED_KEY, java.util.Collections.emptySet()));
        final Set<String> avoided = new HashSet<>(
                prefs.getStringSet(SKIPPED_KEY, java.util.Collections.emptySet()));
        if (liked.size() > 200) {
            liked.clear();
        }
        liked.add(url);
        avoided.remove(url);
        final Set<String> uploaders = new HashSet<>(
                prefs.getStringSet(FAVOURITE_UPLOADERS_KEY, java.util.Collections.emptySet()));
        if (!uploader.isBlank()) {
            if (uploaders.size() > 100) {
                uploaders.clear();
            }
            uploaders.add(uploader.trim().toLowerCase(java.util.Locale.ROOT));
        }
        prefs.edit().putStringSet(LIKED_KEY, liked)
                .putStringSet(SKIPPED_KEY, avoided)
                .putStringSet(FAVOURITE_UPLOADERS_KEY, uploaders).apply();
    }

    /** Locally hide this exact video/music URL from future automatic suggestions. */
    public static void avoid(@NonNull final Context context, @NonNull final String url) {
        if (url.isEmpty()) {
            return;
        }
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final Set<String> skipped = new HashSet<>(
                prefs.getStringSet(SKIPPED_KEY, java.util.Collections.emptySet()));
        final Set<String> liked = new HashSet<>(
                prefs.getStringSet(LIKED_KEY, java.util.Collections.emptySet()));
        if (skipped.size() > 150) {
            skipped.clear();
        }
        skipped.add(url);
        liked.remove(url);
        prefs.edit().putStringSet(SKIPPED_KEY, skipped)
                .putStringSet(LIKED_KEY, liked).apply();
    }

    /** -1 = thumbs down, +1 = thumbs up, 0 = no feedback. */
    public static int feedbackFor(@NonNull final Context context,
                                  @NonNull final String url) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getStringSet(SKIPPED_KEY, java.util.Collections.emptySet()).contains(url)) {
            return -1;
        }
        return prefs.getStringSet(LIKED_KEY, java.util.Collections.emptySet())
                .contains(url) ? 1 : 0;
    }

    public static List<StreamInfoItem> pick(@NonNull final Context context,
                                             @NonNull final List<InfoItem> related,
                                             @NonNull final List<PlayQueueItem> queue,
                                             final int target) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final Set<String> inQueue = new HashSet<>();
        for (final PlayQueueItem item : queue) {
            inQueue.add(item.getUrl());
        }
        final Set<String> excluded = prefs.getStringSet(SKIPPED_KEY,
                java.util.Collections.emptySet());
        final Set<String> recentlyPlayed = new HashSet<>();
        if (prefs.getBoolean(AVOID_RECENT, true)) {
            for (final String url : prefs.getString(RECENT_KEY, "").split("\n")) {
                if (!url.isEmpty()) recentlyPlayed.add(url);
            }
        }
        final boolean preferVariety = prefs.getBoolean(MORE_ARTISTS, true);
        final Set<String> favourites = prefs.getStringSet(LIKED_KEY,
                java.util.Collections.emptySet());
        final Set<String> favouriteUploaders = prefs.getStringSet(FAVOURITE_UPLOADERS_KEY,
                java.util.Collections.emptySet());
        final List<StreamInfoItem> likedMatches = new ArrayList<>();
        final Map<String, Integer> artists = new HashMap<>();
        final List<StreamInfoItem> primary = new ArrayList<>();
        final List<StreamInfoItem> fallback = new ArrayList<>();
        for (final InfoItem candidate : related) {
            if (!(candidate instanceof StreamInfoItem)) continue;
            final StreamInfoItem stream = (StreamInfoItem) candidate;
            final String url = stream.getUrl();
            if (url == null || url.isBlank() || !inQueue.add(url)
                    || excluded.contains(url)
                    || FlowPlayUnavailableStreams.isUnavailable(context,
                            stream.getName(), url)) {
                continue;
            }
            final String artist = stream.getUploaderName() == null
                    ? "" : stream.getUploaderName().trim().toLowerCase(java.util.Locale.ROOT);
            final boolean recentlyUsed = recentlyPlayed.contains(url);
            final boolean sameArtist = preferVariety
                    && !artist.isEmpty() && artists.getOrDefault(artist, 0) >= 2;
            if (recentlyUsed || sameArtist) {
                fallback.add(stream);
            } else if (favourites.contains(url) || favouriteUploaders.contains(artist)) {
                likedMatches.add(stream);
                artists.put(artist, artists.getOrDefault(artist, 0) + 1);
            } else {
                primary.add(stream);
                artists.put(artist, artists.getOrDefault(artist, 0) + 1);
            }
        }
        // Never leave the queue empty just because all matches were heard recently.
        likedMatches.addAll(primary);
        likedMatches.addAll(fallback);
        return likedMatches.subList(0, Math.min(target, likedMatches.size()));
    }
}
