package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.util.ArrayList;
import java.util.List;

/**
 * Restorable, local-only playlist snapshot. Limits size and writes at most every 15s.
 * Does not start playback without a user's tap.
 */
public final class FlowPlaySessionStore {
    private static final String KEY_QUEUE = "flowplay_v2_last_queue";
    private static final int MAX_ITEMS = 50;
    private static final long SAVE_INTERVAL = 15000L;
    private static long lastSaveElapsed = 0;

    private FlowPlaySessionStore() { }

    public static void save(final Context context, @Nullable final PlayQueue queue,
                            final long positionMs, final boolean force) {
        if (queue == null || queue.isEmpty()) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (!force && now - lastSaveElapsed < SAVE_INTERVAL && lastSaveElapsed != 0) {
            return;
        }
        lastSaveElapsed = now;
        try {
            final JSONObject snapshot = new JSONObject();
            final JSONArray streams = new JSONArray();
            final int from = Math.max(0, queue.getIndex() - 3);
            final int to = Math.min(queue.size(), from + MAX_ITEMS);
            for (int i = from; i < to; i++) {
                final PlayQueueItem item = queue.getItem(i);
                if (item == null || item.getUrl().isEmpty()) {
                    continue;
                }
                final JSONObject entry = new JSONObject();
                entry.put("url", item.getUrl());
                entry.put("title", item.getTitle());
                entry.put("service", item.getServiceId());
                entry.put("type", item.getStreamType().name());
                entry.put("auto", item.isAutoQueued());
                streams.put(entry);
            }
            snapshot.put("items", streams);
            snapshot.put("index", Math.min(streams.length() - 1,
                    Math.max(0, queue.getIndex() - from)));
            snapshot.put("position", Math.max(positionMs, 0));
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().putString(KEY_QUEUE, snapshot.toString()).apply();
        } catch (final Exception ignored) {
            // A corrupt item must never interrupt playback.
        }
    }

    @Nullable
    public static PlayQueue restore(final Context context) {
        try {
            final String raw = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(KEY_QUEUE, null);
            if (raw == null) {
                return null;
            }
            final JSONObject snapshot = new JSONObject(raw);
            final JSONArray entries = snapshot.getJSONArray("items");
            final List<StreamInfoItem> streams = new ArrayList<>();
            for (int i = 0; i < entries.length() && i < MAX_ITEMS; i++) {
                final JSONObject entry = entries.getJSONObject(i);
                final String url = entry.getString("url");
                if (url.isEmpty()) {
                    continue;
                }
                final StreamType type = StreamType.valueOf(
                        entry.optString("type", "VIDEO_STREAM"));
                if (StreamTypeUtil.isLiveStream(type)) {
                    continue;
                }
                streams.add(new StreamInfoItem(entry.getInt("service"), url,
                        entry.optString("title", "Saved track"), type));
            }
            if (streams.isEmpty()) {
                return null;
            }
            final int index = Math.min(streams.size() - 1,
                    Math.max(0, snapshot.optInt("index", 0)));
            final SinglePlayQueue queue = new SinglePlayQueue(streams, index);
            queue.setRecovery(index, snapshot.optLong("position", 0));
            return queue;
        } catch (final Exception ignored) {
            return null;
        }
    }

    public static boolean hasSession(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .contains(KEY_QUEUE);
    }
}
