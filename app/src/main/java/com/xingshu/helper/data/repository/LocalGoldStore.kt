package com.xingshu.helper.data.repository

import android.content.Context
import com.xingshu.helper.data.account.BusinessAccount
import com.xingshu.helper.data.model.QAItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * 本地金标话术持久化（不进 APK 资产，App 内运行时添加）。
 *
 * 文件位置：filesDir/local_gold/<account>.jsonl
 * 每行一条 QA，JSON 字段：scene, question, answer, risk_note, embedding (List<Float>)
 *
 * 加载到 VectorStore 的策略：APK assets 是初始库；本 store 追加在末尾。
 */
class LocalGoldStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(account: BusinessAccount): File {
        val dir = File(context.filesDir, "local_gold").apply { mkdirs() }
        return File(dir, "${account.key}.jsonl")
    }

    suspend fun load(account: BusinessAccount): List<Pair<QAItem, FloatArray>> = withContext(Dispatchers.IO) {
        val f = file(account)
        if (!f.exists()) return@withContext emptyList()
        f.useLines { lines ->
            lines.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                runCatching {
                    val obj = json.parseToJsonElement(trimmed) as JsonObject
                    val item = QAItem(
                        scene = obj["scene"]?.jsonPrimitive?.content ?: "其他",
                        questions = listOf(obj["question"]?.jsonPrimitive?.content ?: ""),
                        answer = obj["answer"]?.jsonPrimitive?.content ?: "",
                        riskNote = obj["risk_note"]?.jsonPrimitive?.content ?: "",
                        isGold = true,
                    )
                    val vec = (obj["embedding"] as JsonArray).map { it.jsonPrimitive.float }.toFloatArray()
                    item to vec
                }.getOrNull()
            }.toList()
        }
    }

    /** 追加多条到 JSONL。每条一行，便于 grep/手工编辑。 */
    suspend fun append(account: BusinessAccount, entries: List<Pair<QAItem, FloatArray>>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        val f = file(account)
        f.appendText(
            entries.joinToString("\n", postfix = "\n") { (item, vec) ->
                buildJsonObject {
                    put("scene", item.scene)
                    put("question", item.questions.firstOrNull().orEmpty())
                    put("answer", item.answer)
                    put("risk_note", item.riskNote)
                    putJsonArray("embedding") {
                        vec.forEach { add(JsonPrimitive(it)) }
                    }
                }.toString()
            }
        )
    }

    suspend fun count(account: BusinessAccount): Int = withContext(Dispatchers.IO) {
        val f = file(account)
        if (!f.exists()) 0 else f.useLines { it.count { line -> line.isNotBlank() } }
    }
}
