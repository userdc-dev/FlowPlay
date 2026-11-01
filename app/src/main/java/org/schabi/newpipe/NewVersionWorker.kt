package org.schabi.newpipe

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import java.io.IOException
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.util.ReleaseVersionUtil

class NewVersionWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    /**
     * Method to compare the current and latest available app version.
     * If a newer version is available, we show the update notification.
     *
     * @param versionName    Name of new version
     * @param apkLocationUrl Url with the new apk
     * @param versionCode    Code of new version
     */
    private fun compareAppVersionAndShowNotification(
        versionName: String,
        apkLocationUrl: String?,
        versionCode: Int
    ) {
        if (BuildConfig.VERSION_CODE >= versionCode) {
            if (inputData.getBoolean(IS_MANUAL, false)) {
                // Show toast stating that the app is up-to-date if the update check was manual.
                ContextCompat.getMainExecutor(applicationContext).execute {
                    Toast.makeText(
                        applicationContext,
                        R.string.app_update_unavailable_toast,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return
        }

        // A pending intent to open the apk location url in the browser.
        val intent = Intent(Intent.ACTION_VIEW, apkLocationUrl?.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntentCompat.getActivity(
            applicationContext,
            0,
            intent,
            0,
            false
        )
        val channelId = applicationContext.getString(R.string.app_update_notification_channel_id)
        val notificationBuilder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_newpipe_update)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setContentTitle(
                applicationContext.getString(R.string.app_update_available_notification_title)
            )
            .setContentText(
                applicationContext.getString(
                    R.string.app_update_available_notification_text,
                    versionName
                )
            )

        val notificationManager = NotificationManagerCompat.from(applicationContext)
        if (notificationManager.areNotificationsEnabled()) {
            notificationManager.notify(2000, notificationBuilder.build())
        }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    private fun checkNewVersion() {
        if (!inputData.getBoolean(IS_MANUAL, false)) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            // Check if the last request has happened a certain time ago
            // to reduce the number of API requests.
            val expiry = prefs.getLong(applicationContext.getString(R.string.update_expiry_key), 0)
            if (!ReleaseVersionUtil.isLastUpdateCheckExpired(expiry)) {
                return
            }
        }

        // Fetch only FlowPlay APKs, never upstream NewPipe release binaries.
        val response = DownloaderImpl.getInstance().get(FLOWPLAY_RELEASES_URL)
        handleResponse(response)
    }

    private fun handleResponse(response: Response) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        try {
            // Store a timestamp which needs to be exceeded,
            // before a new request to the API is made.
            val newExpiry = ReleaseVersionUtil.coerceUpdateCheckExpiry(response.getHeader("expires"))
            prefs.edit {
                putLong(applicationContext.getString(R.string.update_expiry_key), newExpiry)
            }
        } catch (e: Exception) {
            if (DEBUG) {
                Log.w(TAG, "Could not extract and save new expiry date", e)
            }
        }

        // GitHub's /releases/latest can point to an older build when CI jobs
        // complete out of order. Choose the HIGHEST numbered FlowPlay release.
        try {
            val releases = JsonParser.array().from(response.responseBody())
            var newestVersion = -1
            var newestApkUrl: String? = null
            for (item in releases) {
                val releaseInfo = item as? JsonObject ?: continue
                val tag = releaseInfo.getString("tag_name") ?: continue
                if (!tag.startsWith("flow-play-v2-")) continue
                val version = tag.removePrefix("flow-play-v2-").toIntOrNull() ?: continue
                val apk = releaseInfo.getArray("assets")
                    ?.mapNotNull { (it as? JsonObject)?.getString("browser_download_url") }
                    ?.firstOrNull { it.endsWith(".apk", ignoreCase = true) }
                if (apk != null && version > newestVersion) {
                    newestVersion = version
                    newestApkUrl = apk
                }
            }
            if (newestVersion >= 0 && newestApkUrl != null) {
                compareAppVersionAndShowNotification(
                    "FlowPlay v2 (build $newestVersion)", newestApkUrl,
                    20000000 + newestVersion
                )
            } else if (inputData.getBoolean(IS_MANUAL, false)) {
                ContextCompat.getMainExecutor(applicationContext).execute {
                    Toast.makeText(applicationContext,
                        R.string.flowplay_update_check_failed, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: JsonParserException) {
            Log.w(TAG, "Invalid FlowPlay release metadata", e)
            if (inputData.getBoolean(IS_MANUAL, false)) {
                ContextCompat.getMainExecutor(applicationContext).execute {
                    Toast.makeText(applicationContext,
                        R.string.flowplay_update_check_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun doWork(): Result {
        return try {
            checkNewVersion()
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Could not fetch FlowPlay releases: probably a network problem", e)
            Result.failure()
        } catch (e: ReCaptchaException) {
            Log.e(TAG, "ReCaptchaException should never happen here.", e)
            Result.failure()
        }
    }

    companion object {
        private val DEBUG = MainActivity.DEBUG
        private val TAG = NewVersionWorker::class.java.simpleName
        private const val FLOWPLAY_RELEASES_URL =
            "https://api.github.com/repos/userdc-dev/FlowPlay/releases?per_page=30"
        private const val IS_MANUAL = "isManual"

        /**
         * Check the user's own FlowPlay GitHub releases, not upstream NewPipe.
         * The check respects automatic-update consent and manual invocations.
         * Installation is always explicitly initiated by the user.
         */
        @JvmStatic
        fun enqueueNewVersionCheckingWork(context: Context, isManual: Boolean) {
            val workRequest = OneTimeWorkRequestBuilder<NewVersionWorker>()
                .setInputData(workDataOf(IS_MANUAL to isManual))
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
