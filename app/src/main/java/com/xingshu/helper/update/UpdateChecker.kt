package com.xingshu.helper.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.xingshu.helper.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 检查 GitHub Releases 上的最新版本，比当前版本新则提示更新。
 *
 * 配置：在 local.properties 写入
 *   GITHUB_OWNER=yourname
 *   GITHUB_REPO=XingShuHelper
 * 公开仓库无需 token；私有仓库需要在 BuildConfig 注入 PAT（这里未实现）。
 */
object UpdateChecker {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class UpToDate(val current: String) : State()
        data class Available(
            val latestVersion: String,
            val downloadUrl: String,
            val sizeBytes: Long,
            val notes: String
        ) : State()
        data class Downloading(val progress: Float) : State()
        data class ReadyToInstall(val apk: File) : State()
        data class Error(val message: String) : State()
    }

    fun isConfigured(): Boolean =
        BuildConfig.GITHUB_OWNER.isNotBlank() && BuildConfig.GITHUB_REPO.isNotBlank()

    fun check(): Flow<State> = flow {
        if (!isConfigured()) {
            emit(State.Error("未配置 GITHUB_OWNER / GITHUB_REPO"))
            return@flow
        }
        emit(State.Checking)
        val url = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                emit(State.Error("GitHub 接口 ${resp.code}"))
                return@flow
            }
            resp.body?.string().orEmpty()
        }
        val release = runCatching { json.decodeFromString<GhRelease>(body) }
            .getOrElse {
                emit(State.Error("响应解析失败：${it.message}"))
                return@flow
            }
        val latestVer = release.tagName.removePrefix("v")
        val current = BuildConfig.VERSION_NAME
        if (compareVersions(latestVer, current) <= 0) {
            emit(State.UpToDate(current))
            return@flow
        }
        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
            ?: run {
                emit(State.Error("最新 release 没有 APK 资源"))
                return@flow
            }
        emit(
            State.Available(
                latestVersion = latestVer,
                downloadUrl = apkAsset.downloadUrl,
                sizeBytes = apkAsset.size,
                notes = release.body.orEmpty()
            )
        )
    }.flowOn(Dispatchers.IO)

    fun download(context: Context, available: State.Available): Flow<State> = flow {
        emit(State.Downloading(0f))
        val req = Request.Builder().url(available.downloadUrl).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                emit(State.Error("下载失败 ${resp.code}"))
                return@use
            }
            val source = resp.body?.byteStream() ?: run {
                emit(State.Error("下载流为空"))
                return@use
            }
            val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            // 清理旧文件
            dir.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }
            val file = File(dir, "XingShuHelper-${available.latestVersion}.apk")
            val total = available.sizeBytes.takeIf { it > 0 } ?: -1L
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var bytes = 0L
                while (source.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    bytes += read
                    if (total > 0) emit(State.Downloading((bytes.toDouble() / total).toFloat()))
                }
            }
            emit(State.ReadyToInstall(file))
        }
    }.flowOn(Dispatchers.IO)

    fun launchInstall(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** 简单比较 1.0.10 vs 1.0.2 类型的版本号；返回 a vs b 的符号。 */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.', '-').mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("body") val body: String? = null,
        @SerialName("assets") val assets: List<GhAsset> = emptyList()
    )

    @Serializable
    private data class GhAsset(
        @SerialName("name") val name: String,
        @SerialName("size") val size: Long,
        @SerialName("browser_download_url") val downloadUrl: String
    )
}
