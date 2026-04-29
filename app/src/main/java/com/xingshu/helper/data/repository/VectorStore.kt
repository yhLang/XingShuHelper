package com.xingshu.helper.data.repository

import com.xingshu.helper.data.model.QAItem
import kotlin.math.sqrt

class VectorStore {

    @Volatile
    private var entries: List<Pair<QAItem, FloatArray>> = emptyList()

    val isReady: Boolean get() = entries.isNotEmpty()

    fun initialize(items: List<Pair<QAItem, FloatArray>>) {
        entries = items.toList()
    }

    fun search(query: FloatArray, topK: Int = 5): List<Pair<QAItem, Float>> {
        val snapshot = entries
        if (snapshot.isEmpty()) return emptyList()
        return snapshot
            .map { (item, vec) -> item to cosineSimilarity(query, vec) }
            .sortedByDescending { it.second }
            .take(topK)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }
}
