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

    /** 运行时追加条目，例如用户在 App 里添加新金标 QA 后立即生效。 */
    fun appendEntries(more: List<Pair<QAItem, FloatArray>>) {
        if (more.isEmpty()) return
        entries = entries + more
    }

    /**
     * 按 question 替换/追加为新本地条目。
     * 同 question 的 assets 原版会被剔除——避免 RAG 召回同 question 的多份重复条目，
     * 也确保按 question 维度的金标⭐操作不会"同步翻转"两条。
     */
    fun upsertByQuestion(question: String, newItem: QAItem, newVec: FloatArray) {
        val filtered = entries.filterNot { (item, _) ->
            item.questions.firstOrNull() == question
        }
        entries = filtered + (newItem to newVec)
    }

    /** 找回 question 对应的向量（编辑 answer 时复用原向量，不需重新调 embedding API）。 */
    fun vectorForQuestion(question: String): FloatArray? =
        entries.firstOrNull { (item, _) -> item.questions.firstOrNull() == question }?.second

    /** 找回 question 对应的 QAItem。用于升级金标时拿到原条目（assets 来源不可写但可读）。 */
    fun itemForQuestion(question: String): QAItem? =
        entries.firstOrNull { (item, _) -> item.questions.firstOrNull() == question }?.first

    /** 内存里翻转 question 对应条目的 isGold 标志，立刻影响后续 search 排序，无需重 load corpus。 */
    fun setGoldByQuestion(question: String, isGold: Boolean) {
        val updated = entries.map { (item, vec) ->
            if (item.questions.firstOrNull() == question && item.isGold != isGold) {
                item.copy(isGold = isGold) to vec
            } else item to vec
        }
        entries = updated
    }

    fun search(query: FloatArray, topK: Int = 5): List<Pair<QAItem, Float>> {
        val snapshot = entries
        if (snapshot.isEmpty()) return emptyList()
        return snapshot
            .map { (item, vec) ->
                val raw = cosineSimilarity(query, vec)
                // 金标话术加分（boost），让人工挑选的高质量回复更容易进 top-K
                val boosted = if (item.isGold) raw + GOLD_BOOST else raw
                Triple(item, raw, boosted)
            }
            .sortedByDescending { it.third }
            .take(topK)
            // 排序用 boosted，但展示给 UI 的还是原始相似度，避免误导
            .map { (item, raw, _) -> item to raw }
    }

    companion object {
        /** 金标条目相似度加分。0.05 ≈ 在 0.5-0.8 区间内提升约 2-3 名。 */
        private const val GOLD_BOOST = 0.05f
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
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
