package com.xingshu.helper.data.repository

import com.xingshu.helper.data.model.GeneratedResult
import com.xingshu.helper.data.model.GenerateState
import com.xingshu.helper.data.model.QAItem
import com.xingshu.helper.data.qa.QALibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
     * AI 能同时看到客户的话和"我"的话，对当前对话状态有上下文判断。
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

    /** 结构化查询路径：直接用结构化 KB 生成，不走 RAG，不附话术库兜底。 */
    fun generateStructured(
        messages: List<String>,
        apiKey: String,
        baseUrl: String,
        structuredContext: String
    ): Flow<GenerateState> {
        val userContent = if (messages.size == 1) {
            "客户消息：${messages[0]}"
        } else {
            "客户连续发来的消息（按顺序）：\n" +
                    messages.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString("\n")
        }
        return runChatCompletion(userContent, apiKey, baseUrl, emptyList(), structuredContext, useQaFallback = false)
    }

    /** 结构化查询路径（对话版）：直接用结构化 KB 生成，不走 RAG，不附话术库兜底。 */
    fun generateStructuredFromDialog(
        dialog: List<com.xingshu.helper.data.model.DialogMessage>,
        apiKey: String,
        baseUrl: String,
        structuredContext: String
    ): Flow<GenerateState> {
        val userContent = buildDialogContent(dialog)
        return runChatCompletion(userContent, apiKey, baseUrl, emptyList(), structuredContext, useQaFallback = false)
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
        useQaFallback: Boolean = true
    ): Flow<GenerateState> = flow {
        emit(GenerateState.Loading)

        if (apiKey.isBlank()) {
            emit(GenerateState.Error("请先在设置中填入 API Key"))
            return@flow
        }

        val systemPrompt = if (!useQaFallback && structuredContext.isNotBlank()) {
            // 结构化路径：专用 prompt，直接引用结构化数据，不附话术库
            QALibrary.buildStructuredPrompt(structuredContext)
        } else {
            val effectiveItems = contextItems.ifEmpty { QALibrary.items }
            QALibrary.buildPrompt(effectiveItems, structuredContext)
        }

        val body = buildJsonObject {
            put("model", com.xingshu.helper.AppConfig.CHAT_MODEL)
            put("max_tokens", 1024)
            put("temperature", 0.3)
            put("tool_choice", buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", "submit_reply") })
            })
            putJsonArray("tools") { add(replyTool()) }
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
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                emit(GenerateState.Error(extractApiError(responseBody) ?: "请求失败（${response.code}）"))
                return@flow
            }

            logCacheUsage(responseBody)

            val result = parseResponse(responseBody)
            if (result != null && (result.shortVersion.isNotBlank() || result.naturalVersion.isNotBlank() || result.inviteVersion.isNotBlank())) {
                emit(GenerateState.Success(result))
            } else {
                val rawHint = extractDebugContent(responseBody)
                emit(GenerateState.Error("解析失败，模型返回：\n${rawHint.take(400)}"))
            }
        } catch (e: Exception) {
            emit(GenerateState.Error("网络异常：${e.message ?: "未知错误"}"))
        }
    }.flowOn(Dispatchers.IO)

    // 工具定义：8 个必需字段，类型严格。这是 OpenAI 兼容工具调用规范。
    private fun replyTool(): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", "submit_reply")
            put("description", "提交客服回复草稿，必须填写全部 8 个字段")
            put("parameters", buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray {
                    add(JsonPrimitive("is_sensitive"))
                    add(JsonPrimitive("sensitive_note"))
                    add(JsonPrimitive("short"))
                    add(JsonPrimitive("natural"))
                    add(JsonPrimitive("invite"))
                    add(JsonPrimitive("intent"))
                    add(JsonPrimitive("next_step"))
                    add(JsonPrimitive("human_confirm"))
                })
                put("properties", buildJsonObject {
                    put("is_sensitive", buildJsonObject {
                        put("type", "boolean")
                        put("description", "是否敏感场景（退费/投诉/价格/效果保证等）")
                    })
                    put("sensitive_note", buildJsonObject {
                        put("type", "string")
                        put("description", "敏感原因说明，不敏感时为空字符串")
                    })
                    put("short", buildJsonObject {
                        put("type", "string")
                        put("description", "简短版回复，2-3 句")
                    })
                    put("natural", buildJsonObject {
                        put("type", "string")
                        put("description", "自然版回复，3-5 句完整回复")
                    })
                    put("invite", buildJsonObject {
                        put("type", "string")
                        put("description", "邀约版回复，3-5 句，引导试听预约")
                    })
                    put("intent", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("高"))
                            add(JsonPrimitive("中"))
                            add(JsonPrimitive("低"))
                        })
                        put("description", "客户意向等级")
                    })
                    put("next_step", buildJsonObject {
                        put("type", "string")
                        put("description", "建议下一步操作")
                    })
                    put("human_confirm", buildJsonObject {
                        put("type", "string")
                        put("description", "需要人工确认的内容，无则为空字符串")
                    })
                })
            })
        })
    }

    private fun parseResponse(responseBody: String): GeneratedResult? {
        return try {
            val root = json.parseToJsonElement(responseBody) as JsonObject
            val firstChoice = root["choices"]
                ?.let { it as? JsonArray }
                ?.firstOrNull()
                ?.let { it as? JsonObject }
            val message = firstChoice?.get("message") as? JsonObject

            // 优先从 tool_calls 解析（强约束路径）
            val toolArgs = (message?.get("tool_calls") as? JsonArray)
                ?.firstOrNull()
                ?.let { it as? JsonObject }
                ?.get("function")
                ?.let { it as? JsonObject }
                ?.get("arguments")
                ?.jsonPrimitive?.content

            val parsed: JsonObject? = if (toolArgs != null) {
                android.util.Log.d("AIRepository", "tool_args: $toolArgs")
                runCatching { json.parseToJsonElement(toolArgs) as? JsonObject }.getOrNull()
            } else {
                val text = message?.get("content")?.jsonPrimitive?.content ?: return null
                android.util.Log.d("AIRepository", "fallback content: $text")
                extractJson(text)
            }
            val obj = parsed ?: return null

            GeneratedResult(
                isSensitive = obj["is_sensitive"]?.jsonPrimitive?.boolean ?: false,
                sensitiveNote = obj["sensitive_note"]?.jsonPrimitive?.content ?: "",
                shortVersion = obj["short"]?.jsonPrimitive?.content ?: "",
                naturalVersion = obj["natural"]?.jsonPrimitive?.content ?: "",
                inviteVersion = obj["invite"]?.jsonPrimitive?.content ?: "",
                intent = obj["intent"]?.jsonPrimitive?.content ?: "",
                nextStep = obj["next_step"]?.jsonPrimitive?.content ?: "",
                humanConfirm = obj["human_confirm"]?.jsonPrimitive?.content ?: ""
            )
        } catch (e: Exception) {
            android.util.Log.e("AIRepository", "parseResponse exception: ${e.message}")
            null
        }
    }

    private fun extractJson(text: String): JsonObject? {
        runCatching { json.parseToJsonElement(text.trim()) as? JsonObject }
            .getOrNull()?.let { return it }
        val stripped = text
            .replace(Regex("^```(?:json)?\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("```\\s*$", RegexOption.MULTILINE), "")
            .trim()
        runCatching { json.parseToJsonElement(stripped) as? JsonObject }
            .getOrNull()?.let { return it }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end > start) {
            runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }
                .getOrNull()?.let { return it }
        }
        android.util.Log.w("AIRepository", "extractJson 全部 fallback 失败，原文：${text.take(200)}")
        return null
    }

    private fun extractDebugContent(responseBody: String): String = try {
        val message = ((json.parseToJsonElement(responseBody) as? JsonObject)
            ?.get("choices") as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("message") as? JsonObject

        val toolArgs = (message?.get("tool_calls") as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("function")
            ?.let { it as? JsonObject }
            ?.get("arguments")
            ?.jsonPrimitive?.content

        toolArgs ?: message?.get("content")?.jsonPrimitive?.content ?: responseBody
    } catch (_: Exception) {
        responseBody
    }

    private fun logCacheUsage(responseBody: String) {
        try {
            val root = json.parseToJsonElement(responseBody) as? JsonObject ?: return
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
