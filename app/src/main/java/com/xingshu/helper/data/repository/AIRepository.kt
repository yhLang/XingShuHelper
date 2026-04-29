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

    /**
     * 给一条标准答案反推可能的客户问法（用于添加金标 QA 时 AI 自动生成 Q 变体）。
     * 返回 5-7 条不同表达的 Q（覆盖直接问/隐晦问/急切语气/客气语气）。
     * 失败返回空列表。
     */
    suspend fun generateQuestionVariants(
        scene: String,
        answer: String,
        apiKey: String,
        baseUrl: String,
    ): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (answer.isBlank()) return@withContext emptyList()

        val tool = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "submit_questions")
                put("description", "提交反推出的客户问法列表")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("required", buildJsonArray { add(JsonPrimitive("questions")) })
                    put("properties", buildJsonObject {
                        put("questions", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put("description", "5-7 条家长可能问出的问法，10-30 字")
                        })
                    })
                })
            })
        }

        val systemPrompt = """
你是培训机构话术整理助手。下方给你一条客服的标准回复（A），请反推 5-7 个家长可能问出的问题（Q），覆盖不同表达方式：直接问、隐晦问、急切语气、客气语气、专业术语、口语化等。

要求：
- 每个 Q 是真实家长会说出的中文短句，10-30 字
- 不同问法之间表达要有明显差异，不要换字不换意
- 不要带语气词冗余（如"嗯""啊"），保持自然
- 只返回 Q 列表，不要写解释或参考答案
        """.trimIndent()

        val userContent = buildString {
            append("场景：").appendLine(scene.ifBlank { "未指定" })
            appendLine()
            append("标准回复：").appendLine(answer)
        }

        val body = buildJsonObject {
            put("model", com.xingshu.helper.AppConfig.CHAT_MODEL)
            put("max_tokens", 512)
            put("temperature", 0.7)
            put("tool_choice", buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", "submit_questions") })
            })
            putJsonArray("tools") { add(tool) }
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

        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val resp = client.newCall(req).execute()
            val text = resp.body?.string() ?: return@withContext emptyList()
            if (!resp.isSuccessful) {
                android.util.Log.e("AIRepository", "generateQuestionVariants 失败: ${resp.code} $text")
                return@withContext emptyList()
            }
            val root = json.parseToJsonElement(text) as JsonObject
            val args = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.get("message")?.let { it as? JsonObject }
                ?.get("tool_calls")?.let { it as? JsonArray }
                ?.firstOrNull()?.let { it as? JsonObject }
                ?.get("function")?.let { it as? JsonObject }
                ?.get("arguments")?.jsonPrimitive?.content
                ?: return@withContext emptyList()
            val parsed = json.parseToJsonElement(args) as? JsonObject
            val list = parsed?.get("questions") as? JsonArray
            list?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("AIRepository", "generateQuestionVariants 异常: ${e.message}")
            emptyList()
        }
    }

    fun generate(
        messages: List<String>,
        apiKey: String,
        baseUrl: String,
        contextItems: List<QAItem> = emptyList()
    ): Flow<GenerateState> {
        val userContent = if (messages.size == 1) {
            "客户消息：${messages[0]}"
        } else {
            "客户连续发来的消息（按顺序）：\n" +
                    messages.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString("\n")
        }
        return runChatCompletion(userContent, apiKey, baseUrl, contextItems)
    }

    /**
     * 基于完整对话历史生成回复（OCR / 截屏识别后的主路径）。
     * AI 能同时看到客户的话和"我"的话，对当前对话状态有上下文判断。
     */
    fun generateFromDialog(
        dialog: List<com.xingshu.helper.data.model.DialogMessage>,
        apiKey: String,
        baseUrl: String,
        contextItems: List<QAItem> = emptyList()
    ): Flow<GenerateState> {
        // 渲染成清晰的、按时间顺序的两人对话，[客户] / [我] 双前缀让模型分清说话方
        val userContent = buildString {
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
        return runChatCompletion(userContent, apiKey, baseUrl, contextItems)
    }

    private fun runChatCompletion(
        userContent: String,
        apiKey: String,
        baseUrl: String,
        contextItems: List<QAItem>
    ): Flow<GenerateState> = flow {
        emit(GenerateState.Loading)

        if (apiKey.isBlank()) {
            emit(GenerateState.Error("请先在设置中填入 API Key"))
            return@flow
        }

        val systemPrompt = if (contextItems.isEmpty()) {
            QALibrary.systemPrompt
        } else {
            QALibrary.buildPrompt(contextItems)
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
