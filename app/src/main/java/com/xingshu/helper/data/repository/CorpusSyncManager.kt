package com.xingshu.helper.data.repository

import android.content.Context
import com.xingshu.helper.BuildConfig
import com.xingshu.helper.data.account.BusinessAccount
import com.xingshu.helper.update.GitHubMirrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * 金标语料云同步：从独立 GitHub 仓库（CORPUS_REPO）拉 manifest，按需下载新版 JSON+bin
 * 到 filesDir/corpus/<account>/，QACorpusLoader 优先读这里。
 *
 * 仓库布局（dist/ 目录由 CI 产出）：
 *   dist/<account>.manifest.json  →  { version, count, files: { texts:{path,sha256,size}, embeddings:{...} } }
 *   dist/<account>/qa_<account>_texts.json
 *   dist/<account>/qa_<account>_embeddings.bin
 *
 * 大陆访问全程走 GitHubMirrors 反代，不直连 raw.githubusercontent.com。
 */
class CorpusSyncManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Manifest(
        @SerialName("version") val version: Int,
        @SerialName("count") val count: Int = 0,
        @SerialName("updated_at") val updatedAt: String = "",
        @SerialName("files") val files: Files,
    )

    @Serializable
    data class Files(
        @SerialName("texts") val texts: FileEntry,
        @SerialName("embeddings") val embeddings: FileEntry,
    )

    @Serializable
    data class FileEntry(
        @SerialName("path") val path: String,
        @SerialName("sha256") val sha256: String,
        @SerialName("size") val size: Long = 0,
    )

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class UpToDate(val version: Int) : State()
        data class Downloading(val progress: Float) : State()
        data class Updated(val version: Int, val count: Int) : State()
        data class Error(val message: String) : State()
    }

    fun isConfigured(): Boolean = BuildConfig.CORPUS_REPO.isNotBlank()

    fun corpusDir(account: BusinessAccount): File =
        File(context.filesDir, "corpus/${account.key}").apply { mkdirs() }

    fun localTextsFile(account: BusinessAccount): File =
        File(corpusDir(account), "qa_${account.key}_texts.json")

    fun localEmbeddingsFile(account: BusinessAccount): File =
        File(corpusDir(account), "qa_${account.key}_embeddings.bin")

    private fun localManifestFile(account: BusinessAccount): File =
        File(corpusDir(account), "manifest.json")

    /** 已下载的版本号；无本地数据返回 0。 */
    fun localVersion(account: BusinessAccount): Int {
        val f = localManifestFile(account)
        if (!f.exists()) return 0
        return runCatching { json.decodeFromString<Manifest>(f.readText()).version }.getOrDefault(0)
    }

    /**
     * 同步入口。listener 回调通知 UI 状态变化。
     * 返回 true 表示同步成功（或已经是最新），false 表示失败。
     */
    suspend fun sync(
        account: BusinessAccount,
        listener: (State) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            listener(State.Error("未配置 CORPUS_REPO"))
            return@withContext false
        }
        listener(State.Checking)

        val manifestUrl = rawUrl("dist/${account.key}.manifest.json")
        val manifest = fetchManifest(manifestUrl) ?: run {
            listener(State.Error("拉取 manifest 失败（所有镜像）"))
            return@withContext false
        }

        if (manifest.version <= localVersion(account) &&
            localTextsFile(account).exists() &&
            localEmbeddingsFile(account).exists()
        ) {
            listener(State.UpToDate(manifest.version))
            return@withContext true
        }

        listener(State.Downloading(0f))

        val textsTmp = File(corpusDir(account), "texts.tmp")
        val embTmp = File(corpusDir(account), "embeddings.tmp")
        val ok = downloadTo(rawUrl(manifest.files.texts.path), textsTmp,
            manifest.files.texts.size, manifest.files.texts.sha256
        ) { listener(State.Downloading(it * 0.5f)) } &&
            downloadTo(rawUrl(manifest.files.embeddings.path), embTmp,
                manifest.files.embeddings.size, manifest.files.embeddings.sha256
            ) { listener(State.Downloading(0.5f + it * 0.5f)) }

        if (!ok) {
            textsTmp.delete()
            embTmp.delete()
            listener(State.Error("下载或校验失败"))
            return@withContext false
        }

        // 原子替换：先写 tmp + sha256 校验通过 → rename 到目标位置 → 最后写 manifest
        val textsOk = textsTmp.renameTo(localTextsFile(account))
        val embOk = embTmp.renameTo(localEmbeddingsFile(account))
        if (!textsOk || !embOk) {
            textsTmp.delete()
            embTmp.delete()
            listener(State.Error("文件替换失败（磁盘空间不足或跨分区）"))
            return@withContext false
        }
        localManifestFile(account).writeText(json.encodeToString(Manifest.serializer(), manifest))

        listener(State.Updated(manifest.version, manifest.count))
        true
    }

    private fun rawUrl(path: String): String {
        val repo = BuildConfig.CORPUS_REPO
        val branch = BuildConfig.CORPUS_BRANCH.ifBlank { "main" }
        return "https://raw.githubusercontent.com/$repo/$branch/$path"
    }

    private fun fetchManifest(url: String): Manifest? {
        for (wrap in GitHubMirrors.wrappers) {
            val full = wrap(url)
            try {
                val req = Request.Builder().url(full).build()
                GitHubMirrors.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    return runCatching { json.decodeFromString<Manifest>(body) }.getOrNull()
                        ?: return@use
                }
            } catch (e: Exception) {
                android.util.Log.w("CorpusSync", "manifest 镜像 $full 失败：${e.message}")
            }
        }
        return null
    }

    private fun downloadTo(
        url: String,
        dst: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (Float) -> Unit,
    ): Boolean {
        for ((idx, wrap) in GitHubMirrors.wrappers.withIndex()) {
            val full = wrap(url)
            android.util.Log.d("CorpusSync", "下载 [${idx + 1}/${GitHubMirrors.wrappers.size}] $full")
            val ok = try {
                val req = Request.Builder().url(full).build()
                GitHubMirrors.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val src = resp.body?.byteStream() ?: return@use false
                    val md = MessageDigest.getInstance("SHA-256")
                    val total = expectedSize.takeIf { it > 0 } ?: -1L
                    dst.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var bytes = 0L
                        while (src.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            md.update(buf, 0, read)
                            bytes += read
                            if (total > 0) onProgress((bytes.toDouble() / total).toFloat())
                        }
                    }
                    val actual = md.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        android.util.Log.w(
                            "CorpusSync",
                            "sha256 不匹配：expected=$expectedSha256 actual=$actual"
                        )
                        return@use false
                    }
                    true
                }
            } catch (e: Exception) {
                android.util.Log.w("CorpusSync", "下载镜像 $full 失败：${e.message}")
                false
            }
            if (ok) return true
            dst.delete()
        }
        return false
    }
}
