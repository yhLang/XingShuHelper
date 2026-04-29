package com.xingshu.helper.data.repository

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.xingshu.helper.AppConfig
import com.xingshu.helper.data.model.DialogMessage
import com.xingshu.helper.data.model.DialogRole
import com.xingshu.helper.data.model.VisionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class VisionRepository {

    private val client = sharedHttpClient

    private val json = Json { ignoreUnknownKeys = true }

    fun extractDialog(
        bitmap: Bitmap,
        apiKey: String = AppConfig.API_KEY,
        baseUrl: String = AppConfig.API_BASE_URL
    ): Flow<VisionState> = flow {
        emit(VisionState.Loading)

        if (apiKey.isBlank()) {
            emit(VisionState.Error("请先在设置中填入 API Key"))
            return@flow
        }

        val resized = downscale(bitmap, MAX_LONG_EDGE)
        val dataUrl = "data:image/jpeg;base64,${encodeJpegBase64(resized, JPEG_QUALITY)}"
        if (resized !== bitmap) resized.recycle()

        val body = buildJsonObject {
            put("model", AppConfig.VISION_MODEL)
            put("max_tokens", 2048)
            // temperature=0 + top_p=0.1：让模型尽量"逐字抄写"看到的内容，
            // 而不是发挥编出经典客服对话
            put("temperature", 0)
            put("top_p", 0.1)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", dataUrl) }
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", EXTRACT_PROMPT)
                        })
                    }
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

            // 把请求归属信息打出来，方便核对用量面板
            val requestId = response.header("x-request-id") ?: response.header("X-Request-Id")
            Log.d(TAG, "http=${response.code} request_id=$requestId server=${response.header("server")}")

            if (!response.isSuccessful) {
                emit(VisionState.Error(extractApiError(responseBody) ?: "请求失败（${response.code}）"))
                return@flow
            }

            val parsed = parseDialog(responseBody)
            if (parsed == null) {
                emit(VisionState.Error("视觉解析失败，请重试"))
            } else {
                Log.d(TAG, "extracted ${parsed.messages.size} messages, prompt=${parsed.promptTokens} completion=${parsed.completionTokens}")
                emit(parsed)
            }
        } catch (e: Exception) {
            emit(VisionState.Error("网络异常：${e.message ?: "未知错误"}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun parseDialog(responseBody: String): VisionState.Success? {
        return try {
            val root = json.parseToJsonElement(responseBody) as JsonObject
            val text = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
                ?: return null

            val cleaned = text
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val arr = json.parseToJsonElement(cleaned) as? JsonArray ?: return null
            val messages = arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val role = obj["role"]?.jsonPrimitive?.content?.lowercase() ?: return@mapNotNull null
                val msgText = obj["text"]?.jsonPrimitive?.content?.trim() ?: return@mapNotNull null
                if (msgText.isEmpty()) return@mapNotNull null
                val parsedRole = when (role) {
                    "customer", "user", "客户", "对方" -> DialogRole.CUSTOMER
                    "me", "self", "assistant", "我", "自己" -> DialogRole.ME
                    else -> return@mapNotNull null
                }
                DialogMessage(parsedRole, msgText)
            }

            val usage = root["usage"] as? JsonObject
            VisionState.Success(
                messages = messages,
                rawJson = cleaned,
                promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseDialog failed", e)
            null
        }
    }

    private fun extractApiError(body: String): String? {
        return try {
            val obj = json.parseToJsonElement(body) as? JsonObject
            obj?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
    }

    private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longEdge = maxOf(w, h)
        if (longEdge <= maxEdge) return src
        val scale = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    private fun encodeJpegBase64(bitmap: Bitmap, quality: Int): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "VisionRepository"
        private const val MAX_LONG_EDGE = 960
        private const val JPEG_QUALITY = 85

        private val EXTRACT_PROMPT = """
你的任务：从这张图片中**逐字抄写**所有微信聊天气泡里的文字。

⚠️ 极其重要的反幻觉约束：
1. 只输出图片中**真实存在、清晰可见、可以确认**的文字
2. 严禁编造、推测、补全、"修复"看不清的字
3. 严禁生成"示例"客服对话（如"你好我想问一下"、"好的谢谢"、"保修多久"等）
4. 如果图片不是微信聊天界面、或者你无法清晰辨识任何聊天文字 → 必须返回空数组 []
5. 如果只有一两条能看清，就只输出那一两条；不要凑数

判断角色：
- 左侧白/灰色气泡 = "customer"（对方）
- 右侧绿色气泡 = "me"（我自己）

忽略：时间戳、"以上是历史消息"、撤回提示、系统消息、纯表情包/贴纸（除非里面有文字）

输出格式：严格的 JSON 数组，不要任何额外说明，不要 markdown 代码块：
[{"role": "customer", "text": "原图中实际出现的文字"}, ...]

如果完全识别不出 → 返回 []，不要为了响应而编造。
        """.trimIndent()
    }
}
