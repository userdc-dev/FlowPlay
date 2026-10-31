package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/** A light-weight, conservative indicator for unavailable entries in playlists. */
public final class FlowPlayUnavailableStreams {
    private static final String PREF_URLS = "flowplay_confirmed_unavailable_urls_v1";
    private static final int MAX_REMEMBERED = 200;

    private FlowPlayUnavailableStreams() { }

    public static boolean isUnavailable(final Context context,
                                        @Nullable final String title,
                                        @Nullable final String url) {
        if (isUnavailableTitle(title)) {
            return true;
        }
        return url != null && !url.isEmpty()
                && PreferenceManager.getDefaultSharedPreferences(context)
                        .getStringSet(PREF_URLS, Collections.emptySet()).contains(url);
    }

    public static boolean isUnavailableTitle(@Nullable final String title) {
        if (title == null) {
            return false;
        }
        String normalized = title.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        switch (normalized) {
            case "deleted video":
            case "private video":
            case "video unavailable":
            case "unavailable video":
            case "this video is unavailable":
            case "removed video":
            case "video has been removed":
                return true;
            default:
                return false;
        }
    }

    /** Never remember network timeouts or errors that aren't confirmed unavailable. */
    public static void rememberIfConfirmedUnavailable(final Context context,
                                                      @Nullable final String url,
                                                      @Nullable final Throwable error) {
        if (url == null || url.isEmpty()) {
            return;
        }
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++) {
            if (cause instanceof ContentNotAvailableException) {
                final SharedPreferences prefs =
                        PreferenceManager.getDefaultSharedPreferences(context);
                final Set<String> urls = new HashSet<>(
                        prefs.getStringSet(PREF_URLS, Collections.emptySet()));
                if (urls.size() >= MAX_REMEMBERED) {
                    final Iterator<String> iterator = urls.iterator();
                    if (iterator.hasNext()) {
                        iterator.next();
                        iterator.remove();
                    }
                }
                urls.add(url);
                prefs.edit().putStringSet(PREF_URLS, urls).apply();
                return;
            }
            cause = cause.getCause();
        }
    }
}
