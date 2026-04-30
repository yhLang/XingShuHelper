package com.xingshu.helper.data.repository

import com.xingshu.helper.data.model.QAItem
import kotlin.math.sqrt

class VectorStore {

    @Volatile
    private var entries: List<Pair<QAItem, FloatArray>> = emptyList()

    val isReady: Boolean get() = entries.isNotEmpty()

    fun initialize(items: List<Pair<QAItem, FloatArray>>) {
        entries = items.map { (item, vec) -> item to normalize(vec) }
    }

    fun upsertByQuestion(question: String, newItem: QAItem, newVec: FloatArray) {
        val filtered = entries.filterNot { (item, _) ->
            item.questions.firstOrNull() == question
        }
        entries = filtered + (newItem to normalize(newVec))
    }

    /** 找回 question 对应的向量（编辑 answer 时复用原向量，不需重新调 embedding API）。 */
    fun vectorForQuestion(question: String): FloatArray? =
        entries.firstOrNull { (item, _) -> item.questions.firstOrNull() == question }?.second

    /** 找回 question 对应的 QAItem。用于升级金标时拿到原条目（assets 来源不可写但可读）。 */
    fun itemForQuestion(question: String): QAItem? =
        entries.firstOrNull { (item, _) -> item.questions.firstOrNull() == question }?.first

    fun search(query: FloatArray, topK: Int = 5): List<Pair<QAItem, Float>> {
        val snapshot = entries
        if (snapshot.isEmpty()) return emptyList()
        val queryNorm = normalize(query)
        return snapshot
            .map { (item, vec) ->
                val raw = dotProduct(queryNorm, vec)
                item to raw
            }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .distinctBy { it.first.answer }
            .take(topK)
    }

    companion object {
        /** 最低相似度阈值。低于此视为"语料库未覆盖"，不返回噪声结果。
         *  0.6 是宽松档：放过更多语义近的（如「写字班多少钱」匹配到「学费多少」），
         *  弱相关由后续 AI fallback 兜底，比硬 miss 体验好。 */
        private const val MIN_SCORE = 0.6f
    }

    /**
     * 加载时归一化到单位向量，搜索时退化为纯点积，省去每次计算 normB。
     * API 返回的向量通常已归一化，此处做一次防御性处理。
     */
    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        return if (norm < 1e-10f) v.copyOf() else FloatArray(v.size) { v[it] / norm }
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
