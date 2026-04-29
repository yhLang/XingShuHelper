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
        // assets 是只读的，因此「降级」只能在加载阶段把 isGold 强制翻 false
        val assetEntries = items.zip(vecs).map { (item, vec) ->
            val q = item.questions.firstOrNull().orEmpty()
            if (item.isGold && q in demoted) item.copy(isGold = false) to vec else item to vec
        }
        // 合并用户在 App 内添加的本地金标
        val localGold = store.load(account)
        if (localGold.isNotEmpty()) {
            android.util.Log.d("QACorpusLoader", "合并本地金标 [${account.key}]: ${localGold.size} 条")
        }
        if (demoted.isNotEmpty()) {
            android.util.Log.d("QACorpusLoader", "应用金标降级 [${account.key}]: ${demoted.size} 条")
        }
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
