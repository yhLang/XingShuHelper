package com.xingshu.helper.data.repository

import com.xingshu.helper.data.model.GenerateState
import com.xingshu.helper.data.model.QAItem
import com.xingshu.helper.data.qa.QALibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 客服回复生成（路线 A：纯文本 + SSE 流式）。
 *
 * 历史包袱：早期版本走 OpenAI tool_calls 让模型填 6 个字段（敏感判断、意向、
 * 简短/自然/邀约三版回复等）。实测下来：
 *   1. 三版生成把 completion tokens 拉到 ~3x，是延迟主因；
 *   2. 客服 90% 场景只复制其中一版，metadata 卡片基本不看；
 *   3. 下游是人不是程序，没必要 JSON 强约束。
 *
 * 新方案：直接让模型输出回复正文，开 stream，UI 一边收 token 一边打字，
 * 首字到达时间从 5-8s 砍到 < 1s。
 */
class AIRepository {

    private val client = sharedHttpClient
    private val json = sharedJson

    fun generate(
        messages: List<String>,
        apiKey: String,
        baseUrl: String,
        contextItems: List<QAItem> = emptyList(),
        structuredContext: String = ""
    ): Flow<GenerateState> {
        val userContent = if (messages.size == 1) {
            "客户消息：${messages[0]}"
        } else {
            "客户连续发来的消息（按顺序）：\n" +
                    messages.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString("\n")
        }
        return runChatCompletion(userContent, apiKey, baseUrl, contextItems, structuredContext)
    }

    /**
     * 基于完整对话历史生成回复（OCR / 截屏识别后的主路径）。
     */
    fun generateFromDialog(
        dialog: List<com.xingshu.helper.data.model.DialogMessage>,
        apiKey: String,
        baseUrl: String,
        contextItems: List<QAItem> = emptyList(),
        structuredContext: String = ""
    ): Flow<GenerateState> {
        val userContent = buildDialogContent(dialog)
        return runChatCompletion(userContent, apiKey, baseUrl, contextItems, structuredContext)
    }

    private fun buildDialogContent(dialog: List<com.xingshu.helper.data.model.DialogMessage>): String = buildString {
        appendLine("以下是与客户的最近微信对话（按时间从早到晚）：")
        appendLine()
        dialog.forEach { msg ->
            val tag = when (msg.role) {
                com.xingshu.helper.data.model.DialogRole.CUSTOMER -> "[客户]"
                com.xingshu.helper.data.model.DialogRole.ME -> "[我]"
            }
            appendLine("$tag ${msg.text}")
        }
        appendLine()
        appendLine("请基于完整对话上下文，为客户最后一句（或最近未回复的消息）生成回复草稿。")
        appendLine("注意：参考[我]已经说过的话，不要重复或自相矛盾。")
    }

    private fun runChatCompletion(
        userContent: String,
        apiKey: String,
        baseUrl: String,
        contextItems: List<QAItem>,
        structuredContext: String = "",
    ): Flow<GenerateState> = flow {
        emit(GenerateState.Loading)

        if (apiKey.isBlank()) {
            emit(GenerateState.Error("请先在设置中填入 API Key"))
            return@flow
        }

        val systemPrompt = QALibrary.buildPrompt(contextItems, structuredContext)

        val body = buildJsonObject {
            put("model", com.xingshu.helper.AppConfig.CHAT_MODEL)
            put("max_tokens", 600)
            put("temperature", 0.3)
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userContent)
                })
            }
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty()
                response.close()
                emit(GenerateState.Error(extractApiError(errBody) ?: "请求失败（${response.code}）"))
                return@flow
            }

            val source = response.body?.source()
            if (source == null) {
                response.close()
                emit(GenerateState.Error("空响应"))
                return@flow
            }

            val sb = StringBuilder()
            try {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue

                    val payload = line.substring("data:".length).trim()
                    if (payload == "[DONE]") break

                    parseDeltaContent(payload)?.takeIf { it.isNotEmpty() }?.let { delta ->
                        sb.append(delta)
                        emit(GenerateState.Streaming(sb.toString()))
                    }
                    logUsageIfPresent(payload)
                }
            } finally {
                response.close()
            }

            if (sb.isEmpty()) {
                emit(GenerateState.Error("模型返回空回复"))
            } else {
                emit(GenerateState.Success(sb.toString()))
            }
        } catch (e: Exception) {
            emit(GenerateState.Error("网络异常：${e.message ?: "未知错误"}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun parseDeltaContent(payload: String): String? {
        return try {
            val obj = json.parseToJsonElement(payload) as? JsonObject ?: return null
            val choices = obj["choices"] as? JsonArray
            val first = choices?.firstOrNull() as? JsonObject ?: return null
            val delta = first["delta"] as? JsonObject ?: return null
            // 注意：deepseek 第一个 chunk 通常是 {"role":"assistant","content":null}，content 是
            // JsonNull。直接 .jsonPrimitive.content 会返回字符串 "null" 把它当文本累积进去。
            // 必须用 contentOrNull，它对 JsonNull 返回 Kotlin null。
            (delta["content"] as? JsonPrimitive)?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    private fun logUsageIfPresent(payload: String) {
        try {
            val root = json.parseToJsonElement(payload) as? JsonObject ?: return
            val usage = root["usage"] as? JsonObject ?: return
            val promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val cachedTokens = (usage["prompt_tokens_details"] as? JsonObject)
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val hitRate = if (promptTokens > 0) cachedTokens * 100 / promptTokens else 0
            android.util.Log.d(
                "AIRepository",
                "tokens: prompt=$promptTokens (cached=$cachedTokens, $hitRate%) completion=$completionTokens"
            )
        } catch (_: Exception) {
        }
    }
}
