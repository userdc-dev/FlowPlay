package org.schabi.newpipe.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

/**
 * First-launch, optional, two-step guide for Flow Play floating playback.
 * Normal background audio itself does not need the SYSTEM_ALERT_WINDOW permission.
 */
public final class FlowPlayOverlayOnboarding {
    private static final String PREF_STEP = "flowplay_overlay_onboarding_step_v1";
    private static final int INTRO = 0;
    private static final int RETURNING_FROM_APP_INFO = 1;
    private static final int RETURNING_FROM_OVERLAY = 2;
    private static final int COMPLETE = 3;

    private final Activity activity;
    private final SharedPreferences prefs;
    private boolean dialogShowing;

    public FlowPlayOverlayOnboarding(final Activity activity) {
        this.activity = activity;
        prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    public void maybeShow() {
        if (dialogShowing || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(activity)) {
            prefs.edit().putInt(PREF_STEP, COMPLETE).apply();
            return;
        }
        switch (prefs.getInt(PREF_STEP, INTRO)) {
            case INTRO:
                showFirstStep();
                break;
            case RETURNING_FROM_APP_INFO:
                showSecondStep();
                break;
            case RETURNING_FROM_OVERLAY:
                showPermissionResult();
                break;
            default:
                break;
        }
    }

    private void showFirstStep() {
        show(new AlertDialog.Builder(activity)
                .setTitle(R.string.flowplay_overlay_intro_title)
                .setMessage(R.string.flowplay_overlay_intro_message)
                .setPositiveButton(R.string.flowplay_overlay_open_app_info, (d, w) -> {
                    prefs.edit().putInt(PREF_STEP, RETURNING_FROM_APP_INFO).apply();
                    openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.getPackageName())));
                })
                .setNeutralButton(R.string.flowplay_overlay_skip_restricted, (d, w) -> {
                    prefs.edit().putInt(PREF_STEP, RETURNING_FROM_APP_INFO).apply();
                    activity.getWindow().getDecorView().post(this::maybeShow);
                })
                .setNegativeButton(R.string.flowplay_overlay_later, (d, w) ->
                        prefs.edit().putInt(PREF_STEP, COMPLETE).apply()));
    }

    private void showSecondStep() {
        show(new AlertDialog.Builder(activity)
                .setTitle(R.string.flowplay_overlay_step_two_title)
                .setMessage(R.string.flowplay_overlay_step_two_message)
                .setPositiveButton(R.string.flowplay_overlay_open_permission, (d, w) -> {
                    prefs.edit().putInt(PREF_STEP, RETURNING_FROM_OVERLAY).apply();
                    final Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    // Android 11+ ignores direct package targeting for overlay settings.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        intent.setData(Uri.parse("package:" + activity.getPackageName()));
                    }
                    openSettings(intent);
                })
                .setNegativeButton(R.string.flowplay_overlay_later, (d, w) ->
                        prefs.edit().putInt(PREF_STEP, COMPLETE).apply()));
    }

    private void showPermissionResult() {
        show(new AlertDialog.Builder(activity)
                .setTitle(R.string.flowplay_overlay_retry_title)
                .setMessage(R.string.flowplay_overlay_retry_message)
                .setPositiveButton(R.string.flowplay_overlay_retry, (d, w) -> {
                    prefs.edit().putInt(PREF_STEP, RETURNING_FROM_APP_INFO).apply();
                    activity.getWindow().getDecorView().post(this::maybeShow);
                })
                .setNegativeButton(R.string.flowplay_overlay_later, (d, w) ->
                        prefs.edit().putInt(PREF_STEP, COMPLETE).apply()));
    }

    private void openSettings(final Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            prefs.edit().putInt(PREF_STEP, COMPLETE).apply();
            Toast.makeText(activity, R.string.flowplay_overlay_settings_unavailable,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void show(final AlertDialog.Builder builder) {
        dialogShowing = true;
        final AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> dialogShowing = false);
        dialog.show();
    }
}
