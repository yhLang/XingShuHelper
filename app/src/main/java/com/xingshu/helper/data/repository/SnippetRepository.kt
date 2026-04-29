package com.xingshu.helper.data.repository

import android.content.Context
import com.xingshu.helper.data.account.BusinessAccount
import com.xingshu.helper.data.model.Snippet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SnippetRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(account: BusinessAccount): List<Snippet> = withContext(Dispatchers.IO) {
        val name = "snippets_${account.key}.json"
        val raw = try {
            context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            android.util.Log.w("SnippetRepository", "找不到片段文件 $name: ${e.message}")
            return@withContext emptyList()
        }
        val arr = json.parseToJsonElement(raw) as JsonArray
        arr.map { elem ->
            val obj = elem as JsonObject
            Snippet(
                category = obj["category"]?.jsonPrimitive?.content ?: "其他",
                title = obj["title"]?.jsonPrimitive?.content ?: "",
                text = obj["text"]?.jsonPrimitive?.content ?: "",
            )
        }
    }
}
