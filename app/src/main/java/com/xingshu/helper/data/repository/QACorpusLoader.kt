package com.xingshu.helper.data.repository

import android.content.Context
import com.xingshu.helper.data.model.QAItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QACorpusLoader(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): List<Pair<QAItem, FloatArray>> = withContext(Dispatchers.IO) {
        val items = loadTexts()
        val vecs = loadEmbeddings()
        if (items.size != vecs.size) {
            throw IllegalStateException(
                "文本条数 ${items.size} 与向量条数 ${vecs.size} 不一致，请重新导出语料库"
            )
        }
        items.zip(vecs)
    }

    private fun loadTexts(): List<QAItem> {
        val raw = context.assets.open("qa_kirin_texts.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = json.parseToJsonElement(raw) as JsonArray
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

    private fun loadEmbeddings(): List<FloatArray> {
        val bytes = context.assets.open("qa_kirin_embeddings.bin").use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = buf.int
        val d = buf.int
        return (0 until n).map {
            FloatArray(d) { buf.float }
        }
    }
}
