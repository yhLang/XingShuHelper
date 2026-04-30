package com.xingshu.helper.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class EmbeddingRepository {

    private val client = sharedHttpClient

    private val json = sharedJson

    /**
     * 单条 query embedding 的 LRU 缓存。客服在一轮会话里反复点"生成"或"结合 AI"
     * 时 query 文本基本不变，命中可直接省掉一次 200-500ms 的网络往返。
     * 容量 32 条，accessOrder=true 让 LinkedHashMap 表现为 LRU。
     */
    private val cache = object : LinkedHashMap<String, FloatArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, FloatArray>): Boolean = size > 32
    }
    private val cacheLock = Any()

    suspend fun embed(text: String, apiKey: String, baseUrl: String): FloatArray? {
        synchronized(cacheLock) { cache[text]?.let { return it } }
        val result = embedBatch(listOf(text), apiKey, baseUrl)?.firstOrNull() ?: return null
        synchronized(cacheLock) { cache[text] = result }
        return result
    }

    suspend fun embedBatch(
        texts: List<String>,
        apiKey: String,
        baseUrl: String
    ): List<FloatArray>? = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("model", "text-embedding-v3")
                putJsonArray("input") { texts.forEach { add(JsonPrimitive(it)) } }
                put("encoding_format", "float")
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/embeddings")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val root = json.parseToJsonElement(response.body?.string() ?: "") as JsonObject
            val data = root["data"] as? JsonArray ?: return@withContext null

            data.map { element ->
                val obj = element as JsonObject
                val index = obj["index"]?.jsonPrimitive?.int ?: 0
                val vec = (obj["embedding"] as JsonArray)
                    .map { it.jsonPrimitive.float }
                    .toFloatArray()
                index to vec
            }.sortedBy { it.first }.map { it.second }
        } catch (e: Exception) {
            android.util.Log.e("EmbeddingRepo", "embedBatch 失败：${e.message}")
            null
        }
    }
}
