package com.xingshu.helper.data.repository

import android.content.Context
import com.xingshu.helper.data.account.BusinessAccount
import com.xingshu.helper.data.model.QAItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QACorpusLoader(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(account: BusinessAccount): List<Pair<QAItem, FloatArray>> = withContext(Dispatchers.IO) {
        val items = loadTexts(account)
        val vecs = loadEmbeddings(account)
        if (items.size != vecs.size) {
            throw IllegalStateException(
                "文本条数 ${items.size} 与向量条数 ${vecs.size} 不一致（账号 ${account.key}），请重新导出语料库"
            )
        }
        val store = LocalGoldStore(context)
        val demoted = store.loadDemoted(account)
        val localGold = store.load(account)
        // 本地金标里的 question 集合，用于覆盖（剔除）assets 中同 question 的条目，
        // 避免同一 question 同时出现 assets 原版 + 本地修订版（会导致 RAG 召回重复、UI 上⭐操作"同步翻转"）
        val localQuestions = localGold.mapNotNull { (item, _) -> item.questions.firstOrNull() }
            .filter { it.isNotBlank() }
            .toHashSet()
        // 只保留 assets 里的金标条目；非金标的 assets 条目（用户编辑也无法持久化）整体丢弃。
        // 用户主动降级（demoted）的金标也一并丢弃。
        val assetEntries = items.zip(vecs).mapNotNull { (item, vec) ->
            val q = item.questions.firstOrNull().orEmpty()
            if (q in localQuestions) return@mapNotNull null
            if (!item.isGold) return@mapNotNull null
            if (q in demoted) return@mapNotNull null
            item to vec
        }
        android.util.Log.d(
            "QACorpusLoader",
            "[${account.key}] 加载完成：assets 金标 ${assetEntries.size} 条 + 本地 ${localGold.size} 条" +
                "；丢弃 assets 非金标 ${items.size - assetEntries.size - localQuestions.size - demoted.size} 条" +
                "；本地覆盖 ${localQuestions.size} 条；降级 ${demoted.size} 条"
        )
        assetEntries + localGold
    }

    private fun loadTexts(account: BusinessAccount): List<QAItem> {
        val raw = context.assets.open("qa_${account.key}_texts.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = json.parseToJsonElement(raw) as JsonArray
        return arr.map { elem ->
            val obj = elem as JsonObject
            QAItem(
                scene = obj["scene"]?.jsonPrimitive?.content ?: "其他",
                questions = listOf(obj["question"]?.jsonPrimitive?.content ?: ""),
                answer = obj["answer"]?.jsonPrimitive?.content ?: "",
                riskNote = obj["risk_note"]?.jsonPrimitive?.content ?: "",
                isGold = obj["is_gold"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    private fun loadEmbeddings(account: BusinessAccount): List<FloatArray> {
        val bytes = context.assets.open("qa_${account.key}_embeddings.bin").use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = buf.int
        val d = buf.int
        return (0 until n).map {
            FloatArray(d) { buf.float }
        }
    }
}
