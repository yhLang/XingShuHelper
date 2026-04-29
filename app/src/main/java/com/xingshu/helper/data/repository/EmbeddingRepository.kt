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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class EmbeddingRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun embed(text: String, apiKey: String, baseUrl: String): FloatArray? =
        embedBatch(listOf(text), apiKey, baseUrl)?.firstOrNull()

    suspend fun embedBatch(
        texts: List<String>,
        apiKey: String,
        baseUrl: String
    ): List<FloatArray>? = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("model", "text-embedding-v4")
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
            null
        }
    }
}
