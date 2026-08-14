package com.example.encoder

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class ReleaseInfo(
    val version: String,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val notes: String
)

/**
 * Проверка и загрузка обновлений из GitHub Releases публичного репозитория.
 * Токен не нужен — API и файлы релизов доступны без авторизации.
 */
class UpdateChecker(
    private val context: Context,
    private val owner: String = BuildConfig.GITHUB_OWNER,
    private val repo: String = BuildConfig.GITHUB_REPO
) {
    private val main = Handler(Looper.getMainLooper())

    fun checkForUpdate(
        currentVersion: String,
        onResult: (ReleaseInfo?) -> Unit,
        onError: (String) -> Unit
    ) {
        thread {
            try {
                val url = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }

                when (conn.responseCode) {
                    200 -> { /* продолжаем */ }
                    404 -> { main.post { onError("Релизов пока нет") }; return@thread }
                    403 -> { main.post { onError("Превышен лимит запросов GitHub") }; return@thread }
                    else -> { main.post { onError("GitHub ответил: ${conn.responseCode}") }; return@thread }
                }

                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()

                val latest = json.getString("tag_name").removePrefix("v")
                val notes = json.optString("body", "")

                val assets = json.getJSONArray("assets")
                var apkAsset: JSONObject? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                        apkAsset = asset
                        break
                    }
                }

                if (apkAsset == null) {
                    main.post { onError("В релизе $latest нет APK-файла") }
                    return@thread
                }

                val info = ReleaseInfo(
                    version = latest,
                    downloadUrl = apkAsset.getString("browser_download_url"),
                    fileName = apkAsset.getString("name"),
                    sizeBytes = apkAsset.getLong("size"),
                    notes = notes
                )

                main.post {
                    onResult(if (isNewer(latest, currentVersion)) info else null)
                }
            } catch (e: Exception) {
                main.post { onError("Ошибка проверки: ${e.message}") }
            }
        }
    }

    /** Скачивает APK в кэш и открывает системный установщик. */
    fun downloadAndInstall(
        release: ReleaseInfo,
        onProgress: (percent: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        thread {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                // Чистим старые загрузки, чтобы кэш не рос
                dir.listFiles()?.forEach { it.delete() }
                val outFile = File(dir, release.fileName)

                val conn = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 60_000
                }

                if (conn.responseCode !in 200..299) {
                    main.post { onError("Загрузка не удалась: ${conn.responseCode}") }
                    return@thread
                }

                val total = if (release.sizeBytes > 0) release.sizeBytes else conn.contentLengthLong
                var downloaded = 0L
                var lastPercent = -1

                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    main.post { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
                conn.disconnect()

                main.post { installApk(outFile) }
            } catch (e: Exception) {
                main.post { onError("Ошибка загрузки: ${e.message}") }
            }
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Сравнивает версии вида "1.2.10" покомпонентно, а не как строки. */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val l = local.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}