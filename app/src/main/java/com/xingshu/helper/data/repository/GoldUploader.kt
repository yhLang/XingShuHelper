package com.xingshu.helper.data.repository

import com.xingshu.helper.BuildConfig
import com.xingshu.helper.data.account.BusinessAccount
import com.xingshu.helper.update.GitHubMirrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 把单组金标 QA 上传到阿里云 FC 中转，由 FC 追加到 GitHub gold/<account>.jsonl 触发 CI 重建。
 *
 * 走中转的原因：app 用户在大陆直连 api.github.com 不稳定。
 * FC 默认域名（*.fcapp.run）国内可达，无需备案。
 */
object GoldUploader {

    private val json = sharedJson
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(): Boolean = BuildConfig.CORPUS_UPLOAD_URL.isNotBlank()

    sealed class Result {
        data class Success(val commit: String?) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * @param account 账号
     * @param scene 场景
     * @param questions Q 变体（已 trim 过滤）
     * @param answer 标准 A
     * @param riskNote 风险提示，可为空
     */
    suspend fun upload(
        account: BusinessAccount,
        scene: String,
        questions: List<String>,
        answer: String,
        riskNote: String,
    ): Result = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.Failure("未配置 CORPUS_UPLOAD_URL")

        val body = buildJsonObject {
            put("secret", BuildConfig.CORPUS_UPLOAD_SECRET)
            put("account", account.key)
            putJsonObject("qa") {
                put("scene", scene.ifBlank { "其他" })
                put("questions", buildJsonArray { questions.forEach { add(JsonPrimitive(it)) } })
                put("answer", answer)
                put("risk_note", riskNote)
            }
        }.toString()

        val req = Request.Builder()
            .url(BuildConfig.CORPUS_UPLOAD_URL)
            .post(body.toRequestBody(jsonMedia))
            .build()

        try {
            GitHubMirrors.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.Failure("HTTP ${resp.code}: ${text.take(200)}")
                }
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                val ok = obj?.get("ok")?.jsonPrimitive?.booleanOrNull == true
                if (!ok) {
                    val err = obj?.get("error")?.jsonPrimitive?.content ?: text.take(200)
                    return@withContext Result.Failure(err)
                }
                val commit = obj?.get("commit")?.jsonPrimitive?.content
                Result.Success(commit)
            }
        } catch (e: Exception) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
}
