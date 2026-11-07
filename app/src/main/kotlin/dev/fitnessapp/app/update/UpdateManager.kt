package dev.fitnessapp.app.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import dev.fitnessapp.app.BuildConfig
import dev.fitnessapp.app.LocalSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What the update check and download are doing (D98). The prompt is open for [Available] up to [DownloadFailed]. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object CheckFailed : UpdateState
    data class Available(val release: Release) : UpdateState
    /** [progress] in 0..1, null while the size is unknown. */
    data class Downloading(val release: Release, val progress: Float?) : UpdateState
    data class ReadyToInstall(val release: Release, val file: File) : UpdateState
    data class DownloadFailed(val release: Release) : UpdateState
    /** "Later": the prompt is closed until a check finds the release again. */
    data class Later(val release: Release) : UpdateState
}

/**
 * The in-app update check of D98: reads the repository's latest GitHub release, offers it when its version code is
 * above the installed one, downloads the APK into the cache (SHA-256 checked against GitHub's digest) and hands it to
 * Android's package installer, which only accepts an APK signed with the installed app's key.
 */
class UpdateManager(
    private val context: Context,
    private val settings: LocalSettings,
    private val scope: CoroutineScope,
    private val repo: String = BuildConfig.UPDATE_REPO,
    private val installedCode: Int = BuildConfig.VERSION_CODE,
    /** Automatic checks (on open and in the background) run in the release build only (D98 (2)). */
    val automatic: Boolean = BuildConfig.UPDATE_CHECK_ENABLED,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private val dir: File get() = File(context.cacheDir, "updates")

    /** On open: checks when automatic checks are on and the last check is at least [CHECK_INTERVAL_MS] old. */
    fun checkIfDue() {
        if (!automatic) return
        val last = settings.lastUpdateCheckMs
        if (last != 0L && now() - last in 0 until CHECK_INTERVAL_MS) return
        check(manual = false)
    }

    /** "Check for updates" (D98 (5)) and the N-UPDATE tap: checks at once and reports the result. */
    fun checkNow() = check(manual = true)

    private fun check(manual: Boolean) {
        if (job?.isActive == true) return
        val cur = _state.value
        if (cur is UpdateState.ReadyToInstall && cur.file.exists()) return
        job = scope.launch(Dispatchers.IO) {
            if (manual) _state.value = UpdateState.Checking
            val result = runCatching { fetchLatest() }
            settings.lastUpdateCheckMs = now()
            result.onSuccess { r ->
                cleanCache(keepCode = r?.takeIf { Releases.isNewer(it, installedCode) }?.versionCode)
                _state.value = when {
                    r != null && Releases.isNewer(r, installedCode) -> {
                        // Shown in the app: the background job does not notify this version again.
                        if (settings.lastNotifiedUpdateCode < r.versionCode) settings.lastNotifiedUpdateCode = r.versionCode
                        UpdateState.Available(r)
                    }
                    manual -> UpdateState.UpToDate
                    else -> UpdateState.Idle
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.i(TAG, "update check failed: ${e::class.simpleName}")
                _state.value = if (manual) UpdateState.CheckFailed else UpdateState.Idle
            }
        }
    }

    /** "Later" (D98 (3)). */
    fun later() {
        val r = releaseOf(_state.value) ?: return
        if (_state.value is UpdateState.Downloading) job?.cancel()
        _state.value = UpdateState.Later(r)
    }

    /** "Update" / "Try again": downloads the APK, then opens the installer. */
    fun download() {
        val r = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.DownloadFailed -> s.release
            is UpdateState.Later -> s.release
            else -> return
        }
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = UpdateState.Downloading(r, null)
            dir.mkdirs()
            val file = File(dir, "FitnessApp-${r.versionCode}.apk")
            val part = File(dir, file.name + ".part")
            try {
                fetchApk(r, part)
                if (file.exists()) file.delete()
                if (!part.renameTo(file)) throw IOException("rename failed")
                _state.value = UpdateState.ReadyToInstall(r, file)
                withContext(Dispatchers.Main) { install(file) }
            } catch (e: CancellationException) {
                part.delete()
                if (_state.value is UpdateState.Downloading) _state.value = UpdateState.Available(r)
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "update download failed: ${e::class.simpleName}")
                part.delete()
                _state.value = UpdateState.DownloadFailed(r)
            }
        }
    }

    /** Cancel during the download: back to the prompt. */
    fun cancelDownload() {
        job?.cancel()
    }

    /** "Install" after the installer was left without installing (D98 (4)). */
    fun installAgain() {
        val s = _state.value as? UpdateState.ReadyToInstall ?: return
        if (s.file.exists()) install(s.file) else _state.value = UpdateState.Available(s.release)
    }

    /** The background job's question (D98 (2)): the latest release when it is newer, else null; throws on network errors. */
    suspend fun newerRelease(): Release? = withContext(Dispatchers.IO) { fetchLatest()?.takeIf { Releases.isNewer(it, installedCode) } }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            Log.w(TAG, "installer could not be opened", it)
            _state.value = UpdateState.DownloadFailed((_state.value as? UpdateState.ReadyToInstall)?.release ?: return)
        }
    }

    private fun fetchLatest(): Release? {
        val c = open("https://api.github.com/repos/$repo/releases/latest").apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            return when (val code = c.responseCode) {
                HttpURLConnection.HTTP_OK -> Releases.parseLatest(c.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND -> null // no release published yet
                else -> throw IOException("GitHub answered $code")
            }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun fetchApk(r: Release, out: File) {
        val c = open(r.apkUrl).apply { readTimeout = 30_000 }
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("download answered ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize.takeIf { it > 0 }
            val sha = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var shown = -1
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        read += n
                        if (read > MAX_APK_BYTES) throw IOException("APK too large")
                        val pct = total?.let { (read * 100 / it).toInt().coerceIn(0, 100) }
                        if (pct != null && pct != shown) {
                            shown = pct
                            _state.value = UpdateState.Downloading(r, pct / 100f)
                        }
                    }
                }
            }
            if (total != null && read != total) throw IOException("incomplete download")
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (r.sha256 != null && hex != r.sha256) throw IOException("SHA-256 mismatch")
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        instanceFollowRedirects = true // release assets redirect to GitHub's download host (HTTPS to HTTPS)
        setRequestProperty("User-Agent", "FitnessApp-Android/${BuildConfig.VERSION_NAME}")
    }

    /** Deletes downloaded APKs except the one of [keepCode] (D98 (4)). */
    private fun cleanCache(keepCode: Int?) {
        dir.listFiles()?.forEach { f ->
            val code = Releases.cachedCode(f.name)
            if (code == null || code != keepCode || f.name.endsWith(".part")) f.delete()
        }
    }

    private fun releaseOf(s: UpdateState): Release? = when (s) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.ReadyToInstall -> s.release
        is UpdateState.DownloadFailed -> s.release
        is UpdateState.Later -> s.release
        else -> null
    }

    companion object {
        private const val TAG = "UpdateCheck"
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
    }
}
