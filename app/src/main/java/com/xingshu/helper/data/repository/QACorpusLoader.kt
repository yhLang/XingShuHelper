package com.xingshu.helper.data.repository

import android.content.Context
import com.xingshu.helper.data.account.BusinessAccount
import com.xingshu.helper.data.model.QAItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QACorpusLoader(private val context: Context) {

    private val sync = CorpusSyncManager(context)

    /** 优先用 filesDir 里的同步版（CorpusSyncManager 写入），其次回退到 APK assets。 */
    private fun openTexts(account: BusinessAccount): InputStream {
        val local = sync.localTextsFile(account)
        return if (local.exists() && local.length() > 0) local.inputStream()
        else context.assets.open("qa_${account.key}_texts.json")
    }

    private fun openEmbeddings(account: BusinessAccount): InputStream {
        val local = sync.localEmbeddingsFile(account)
        return if (local.exists() && local.length() > 0) local.inputStream()
        else context.assets.open("qa_${account.key}_embeddings.bin")
    }

    suspend fun load(account: BusinessAccount): List<Pair<QAItem, FloatArray>> = withContext(Dispatchers.IO) {
        val items = loadTexts(account)
        val vecs = loadEmbeddings(account)
        if (items.size != vecs.size) {
            throw IllegalStateException(
                "文本条数 ${items.size} 与向量条数 ${vecs.size} 不一致（账号 ${account.key}），请重新导出语料库"
            )
        }
        items.zip(vecs)
    }

    private fun loadTexts(account: BusinessAccount): List<QAItem> {
        val raw = openTexts(account).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = sharedJson.parseToJsonElement(raw) as JsonArray
        return arr.map { elem ->
            val obj = elem as JsonObject
            QAItem(
                scene = obj["scene"]?.jsonPrimitive?.content ?: "其他",
                questions = listOf(obj["question"]?.jsonPrimitive?.content ?: ""),
                answer = obj["answer"]?.jsonPrimitive?.content ?: "",
                riskNote = obj["risk_note"]?.jsonPrimitive?.content ?: "",
            )
        }
    }

    private fun loadEmbeddings(account: BusinessAccount): List<FloatArray> {
        val bytes = openEmbeddings(account).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = buf.int
        val d = buf.int
        return (0 until n).map {
            FloatArray(d) { buf.float }
        }
    }
}
