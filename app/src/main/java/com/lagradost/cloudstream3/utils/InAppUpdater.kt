package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.PackageInstallerService
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

object InAppUpdater {
    private const val ANDROID_UPDATER_URL = "https://version.jehadjoy69.workers.dev/android"
    private const val NOTICE_URL = "https://version.jehadjoy69.workers.dev/notice"

    private const val PRERELEASE_PACKAGE_NAME = "com.jahidhub.app.prerelease"
    private const val LOG_TAG = "InAppUpdater"

    fun isVersionNewer(remoteVersion: String?, currentVersion: String?): Boolean {
        if (remoteVersion.isNullOrBlank()) return false
        if (currentVersion.isNullOrBlank()) return true

        fun cleanVersionString(v: String): String {
            return v.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore("-")
                .substringBefore("+")
                .substringBefore(" ")
                .trim()
        }

        val cleanRemote = cleanVersionString(remoteVersion)
        val cleanCurrent = cleanVersionString(currentVersion)

        // Same version string means remote is NOT newer
        if (cleanRemote.equals(cleanCurrent, ignoreCase = true)) {
            return false
        }

        val remoteParts = cleanRemote.split(".").mapNotNull { part ->
            part.trim().takeWhile { it.isDigit() }.takeIf { it.isNotEmpty() }?.toLongOrNull()
        }
        val currentParts = cleanCurrent.split(".").mapNotNull { part ->
            part.trim().takeWhile { it.isDigit() }.takeIf { it.isNotEmpty() }?.toLongOrNull()
        }

        if (remoteParts.isNotEmpty() && currentParts.isNotEmpty()) {
            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0L }
                val c = currentParts.getOrElse(i) { 0L }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }

        val rNum = Regex("""\d+""").findAll(cleanRemote).mapNotNull { it.value.toLongOrNull() }.toList()
        val cNum = Regex("""\d+""").findAll(cleanCurrent).mapNotNull { it.value.toLongOrNull() }.toList()
        if (rNum.isNotEmpty() && cNum.isNotEmpty()) {
            val maxLen = maxOf(rNum.size, cNum.size)
            for (i in 0 until maxLen) {
                val r = rNum.getOrElse(i) { 0L }
                val c = cNum.getOrElse(i) { 0L }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }

        return false
    }

    @Serializable
    private data class GithubAsset(
        @JsonProperty("name") @SerialName("name") val name: String = "",
        @JsonProperty("size") @SerialName("size") val size: Int = 0, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String = "application/vnd.android.package-archive", // application/vnd.android.package-archive
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String = "", // Version code
        @JsonProperty("name") @SerialName("name") val name: String? = null,
        @JsonProperty("body") @SerialName("body") val body: String? = "", // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset> = emptyList(),
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String? = null, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean = false,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String? = null,
    )

    @Serializable
    data class PopupNotice(
        @JsonProperty("enabled") @SerialName("enabled") val enabled: Boolean = false,
        @JsonProperty("version") @SerialName("version") val version: String? = null,
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("message") @SerialName("message") val message: String? = null,
        @JsonProperty("features") @SerialName("features") val features: List<String> = emptyList(),
        @JsonProperty("buttonText") @SerialName("buttonText") val buttonText: String? = null,
        @JsonProperty("downloadUrl") @SerialName("downloadUrl") val downloadUrl: String? = null,
        @JsonProperty("remindLaterHours") @SerialName("remindLaterHours") val remindLaterHours: Long = 24L,
    )

    @Serializable
    private data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String = "", // SHA-256 hash
        @JsonProperty("type") @SerialName("type") val type: String = "",
        @JsonProperty("url") @SerialName("url") val url: String = "",
    )

    @Serializable
    private data class GithubTag(
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject = GithubObject(),
    )

    @Serializable
    private data class Update(
        @JsonProperty("shouldUpdate") @SerialName("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") @SerialName("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") @SerialName("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") @SerialName("changelog") val changelog: String?,
        @JsonProperty("updateNodeId") @SerialName("updateNodeId") val updateNodeId: String?,
    )

    private suspend fun Activity.getAppUpdate(installPrerelease: Boolean): Update {
        return try {
            if (installPrerelease) getPreReleaseUpdate() else getReleaseUpdate()
        } catch (e: Exception) {
            Log.e(LOG_TAG, Log.getStackTraceString(e))
            Update(false, null, null, null, null)
        }
    }

    private suspend fun Activity.getReleaseUpdate(): Update {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Android; Mobile; JoyFlix)",
            "Accept" to "application/json"
        )
        val responseText = try {
            app.get(ANDROID_UPDATER_URL, headers = headers).text
        } catch (_: Exception) {
            return Update(false, null, null, null, null)
        }
        val response = try {
            tryParseJson<GithubRelease>(responseText)?.let { listOf(it) }
                ?: parseJson<Array<GithubRelease>>(responseText).toList()
        } catch (_: Exception) {
            return Update(false, null, null, null, null)
        }

        fun parseVersion(versionStr: String?): Long {
            if (versionStr.isNullOrBlank()) return 0L
            val clean = versionStr.trim().removePrefix("v").removePrefix("V")
            val match = Regex("""(\d+)(?:\.(\d+))?(?:\.(\d+))?""").find(clean) ?: return 0L
            val major = match.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
            val minor = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
            val patch = match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
            return major * 100_000_000L + minor * 10_000L + patch
        }

        val foundList = response.filter { rel ->
            !rel.prerelease
        }.sortedWith(compareBy { release ->
            val apk = release.assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) || it.contentType == "application/vnd.android.package-archive"
            }
            maxOf(parseVersion(release.tagName), parseVersion(apk?.name))
        })

        val found = foundList.lastOrNull() ?: return Update(false, null, null, null, null)
        val foundAsset = found.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) || it.contentType == "application/vnd.android.package-archive"
        } ?: return Update(false, null, null, null, null)

        val currentVersionName = packageName?.let {
            try { packageManager.getPackageInfo(it, 0)?.versionName } catch (_: Exception) { null }
        } ?: BuildConfig.VERSION_NAME

        val isNewer = isVersionNewer(found.tagName, currentVersionName) ||
            isVersionNewer(foundAsset.name, currentVersionName)

        val shouldUpdate = isNewer && foundAsset.browserDownloadUrl.isNotBlank()
        val displayVersion = found.tagName.ifBlank { foundAsset.name }

        return Update(
            shouldUpdate,
            foundAsset.browserDownloadUrl,
            displayVersion,
            found.body,
            found.nodeId ?: displayVersion
        )
    }

    private suspend fun Activity.getPreReleaseUpdate(): Update {
        return getReleaseUpdate()
    }

    private val updateLock = Mutex()

    private suspend fun Activity.downloadUpdate(url: String): Boolean {
        try {
            Log.d(LOG_TAG, "Downloading update: $url")
            val appUpdateName = "JoyFlix"
            val appUpdateSuffix = "apk"

            // Delete all old updates
            this.cacheDir.listFiles()?.filter {
                it.name.startsWith(appUpdateName) && it.extension == appUpdateSuffix
            }?.forEach { deleteFileOnExit(it) }

            val downloadedFile = File.createTempFile(appUpdateName, ".$appUpdateSuffix")
            val sink: BufferedSink = downloadedFile.sink().buffer()

            updateLock.withLock {
                sink.writeAll(app.get(url).body.source())
                sink.close()
                openApk(this, Uri.fromFile(downloadedFile))
            }

            return true
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }

    private fun openApk(context: Context, uri: Uri) = safe {
        val path = uri.path ?: return@safe
        val contentUri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".provider", File(path)
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            data = contentUri
        }
        context.startActivity(installIntent)
    }

    fun Activity.installPreReleaseIfNeeded() = ioSafe {
        val isInstalled = try {
            packageManager.getPackageInfo(PRERELEASE_PACKAGE_NAME, 0)
            true
        } catch (_: NameNotFoundException) {
            false
        }

        if (isInstalled) {
            showToast(R.string.prerelease_already_installed)
        } else if (!runAutoUpdate(checkAutoUpdate = false, installPrerelease = true)) {
            showToast(R.string.prerelease_install_failed)
        }
    }


    /**
     * @param checkAutoUpdate if the update check was launched automatically
     * @param installPrerelease if we want to install the pre-release version
     */
    suspend fun Activity.runAutoUpdate(
        checkAutoUpdate: Boolean = true, installPrerelease: Boolean = false
    ): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val autoUpdateEnabled =
            settingsManager.getBoolean(getString(R.string.auto_update_key), true)
        if (checkAutoUpdate && !autoUpdateEnabled) {
            return false
        }

        val update = getAppUpdate(installPrerelease)
        if (!update.shouldUpdate || update.updateURL == null) {
            return false
        }

        // Check if update should be skipped
        val updateNodeId = settingsManager.getString(
            getString(R.string.skip_update_key), ""
        )

        // Skips the update if its an automatic update and the update is skipped
        // This allows updating manually
        if (update.updateNodeId.equals(updateNodeId) && checkAutoUpdate) {
            return false
        }

        runOnUiThread {
            safe {
                val currentVersion = packageName?.let {
                    packageManager.getPackageInfo(it, 0)
                }

                val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                builder.setTitle(
                    getString(R.string.new_update_format).format(
                        currentVersion?.versionName, update.updateVersion
                    )
                )

                val logRegex = Regex("\\[(.*?)]\\((.*?)\\)")
                val sanitizedChangelog = update.changelog?.replace(logRegex) { matchResult ->
                    matchResult.groupValues[1]
                } // Sanitized because it looks cluttered

                builder.setMessage(sanitizedChangelog)
                builder.apply {
                    setPositiveButton(R.string.update) { _, _ ->
                        startUpdateDownload(update.updateURL)
                    }

                    setNegativeButton(R.string.cancel) { _, _ -> }

                    if (checkAutoUpdate) {
                        setNeutralButton(R.string.skip_update) { _, _ ->
                            settingsManager.edit {
                                putString(
                                    getString(R.string.skip_update_key), update.updateNodeId ?: ""
                                )
                            }
                        }
                    }
                }
                builder.show().setDefaultFocus()
            }
        }
        return true
    }

    private fun Activity.startUpdateDownload(downloadUrl: String) {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        // Forcefully start any delayed installations
        if (ApkInstaller.delayedInstaller?.startInstallation() == true) return

        showToast(R.string.download_started, Toast.LENGTH_LONG)

        // Check if the setting hasn't been changed
        if (settingsManager.getInt(
                getString(R.string.apk_installer_key), -1
            ) == -1
        ) {
            // Set to legacy installer if using MIUI
            if (isMiUi()) {
                settingsManager.edit {
                    putInt(getString(R.string.apk_installer_key), 1)
                }
            }
        }

        val currentInstaller = settingsManager.getInt(
            getString(R.string.apk_installer_key), 1
        )

        when (currentInstaller) {
            // New method
            0 -> {
                val intent = PackageInstallerService.Companion.getIntent(
                    this, downloadUrl
                )
                ContextCompat.startForegroundService(
                    this, intent
                )
            }
            // Legacy
            1 -> {
                ioSafe {
                    if (!downloadUpdate(downloadUrl)) {
                        runOnUiThread {
                            showToast(
                                R.string.download_failed, Toast.LENGTH_LONG
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun Activity.checkPopupNotice(): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Android; Mobile; JoyFlix)",
            "Accept" to "application/json"
        )
        val notice = try {
            val responseText = app.get(NOTICE_URL, headers = headers).text
            tryParseJson<PopupNotice>(responseText)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to fetch popup notice: ${e.message}")
            null
        } ?: return false

        if (!notice.enabled) return false

        val currentVersionName = packageName?.let {
            try { packageManager.getPackageInfo(it, 0)?.versionName } catch (_: Exception) { null }
        } ?: BuildConfig.VERSION_NAME

        // Only show notice if notice version is STRICTLY NEWER than installed app version!
        if (!notice.version.isNullOrBlank()) {
            if (!isVersionNewer(notice.version, currentVersionName)) {
                return false
            }
        }

        // Check if user permanently skipped this notice version
        val skippedVersion = settingsManager.getString("popup_notice_skipped_version", null)
        if (!notice.version.isNullOrBlank() && skippedVersion == notice.version) {
            return false
        }

        // Remind later cooldown
        val lastNoticeTime = settingsManager.getLong("popup_notice_last_time", 0L)
        val remindHours = if (notice.remindLaterHours > 0) notice.remindLaterHours else 24L
        val cooldownMillis = remindHours * 3600 * 1000L
        if (System.currentTimeMillis() - lastNoticeTime < cooldownMillis) {
            return false
        }

        val title = notice.title?.takeIf { it.isNotBlank() } ?: getString(R.string.update)
        val messageText = buildString {
            if (!notice.message.isNullOrBlank()) {
                append(notice.message)
            }
            if (notice.features.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(notice.features.joinToString("\n"))
            }
        }.trim()

        val downloadUrl = notice.downloadUrl?.takeIf { it.isNotBlank() }
        val positiveText = notice.buttonText?.takeIf { it.isNotBlank() } ?: getString(R.string.update)

        runOnUiThread {
            safe {
                val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                builder.setTitle(title)
                builder.setMessage(messageText)

                builder.setPositiveButton(positiveText) { _, _ ->
                    if (!downloadUrl.isNullOrBlank()) {
                        settingsManager.edit {
                            putString("popup_notice_skipped_version", notice.version ?: "")
                        }
                        startUpdateDownload(downloadUrl)
                    }
                }

                builder.setNegativeButton(R.string.cancel) { _, _ ->
                    settingsManager.edit {
                        putLong("popup_notice_last_time", System.currentTimeMillis())
                    }
                }

                if (!notice.version.isNullOrBlank()) {
                    builder.setNeutralButton(R.string.skip_update) { _, _ ->
                        settingsManager.edit {
                            putString("popup_notice_skipped_version", notice.version)
                        }
                    }
                }

                builder.show().setDefaultFocus()
            }
        }
        return true
    }

    private fun isMiUi(): Boolean = !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    private fun getSystemProperty(propName: String): String? = try {
        val p = Runtime.getRuntime().exec("getprop $propName")
        BufferedReader(InputStreamReader(p.inputStream), 1024).use {
            it.readLine()
        }
    } catch (_: IOException) {
        null
    }
}
