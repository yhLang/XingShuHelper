package com.xingshu.helper.data.repository

import android.content.Context
import com.xingshu.helper.data.account.BusinessAccount
import com.xingshu.helper.data.model.QAItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
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

    private val json = sharedJson

    private fun file(account: BusinessAccount): File {
        val dir = File(context.filesDir, "local_gold").apply { mkdirs() }
        return File(dir, "${account.key}.jsonl")
    }

    /** 用户取消金标的 question 列表（一行一个，纯文本，UTF-8）。 */
    private fun demotedFile(account: BusinessAccount): File {
        val dir = File(context.filesDir, "local_gold").apply { mkdirs() }
        return File(dir, "${account.key}.demoted.txt")
    }

    /** 解析一行 JSONL 为 (QAItem, embedding)，失败返回 null。 */
    private fun parseLine(line: String): Pair<QAItem, FloatArray>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val obj = json.parseToJsonElement(trimmed) as JsonObject
            val item = QAItem(
                scene = obj["scene"]?.jsonPrimitive?.content ?: "其他",
                questions = listOf(obj["question"]?.jsonPrimitive?.content ?: ""),
                answer = obj["answer"]?.jsonPrimitive?.content ?: "",
                riskNote = obj["risk_note"]?.jsonPrimitive?.content ?: "",
                isGold = true,
                isLocal = true,
            )
            val vec = (obj["embedding"] as JsonArray).map { it.jsonPrimitive.float }.toFloatArray()
            item to vec
        }.getOrNull()
    }

    /** 只解析 question 字段（用于按 question 去重 / 查找时无需读 embedding 浪费）。 */
    private fun parseQuestion(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            ((json.parseToJsonElement(trimmed) as? JsonObject)
                ?.get("question") as? JsonPrimitive)?.content
        }.getOrNull()
    }

    /** 把一条 (QAItem, embedding) 序列化为 JSONL 一行（不带换行）。 */
    private fun buildLine(
        scene: String,
        question: String,
        answer: String,
        riskNote: String,
        embedding: FloatArray,
    ): String = buildJsonObject {
        put("scene", scene)
        put("question", question)
        put("answer", answer)
        put("risk_note", riskNote)
        putJsonArray("embedding") { embedding.forEach { add(JsonPrimitive(it)) } }
    }.toString()

    suspend fun load(account: BusinessAccount): List<Pair<QAItem, FloatArray>> = withContext(Dispatchers.IO) {
        val f = file(account)
        if (!f.exists()) return@withContext emptyList()
        f.useLines { lines -> lines.mapNotNull { parseLine(it) }.toList() }
    }

    /** 追加多条到 JSONL。每条一行，便于 grep/手工编辑。 */
    suspend fun append(account: BusinessAccount, entries: List<Pair<QAItem, FloatArray>>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        val f = file(account)
        f.appendText(
            entries.joinToString("\n", postfix = "\n") { (item, vec) ->
                buildLine(
                    scene = item.scene,
                    question = item.questions.firstOrNull().orEmpty(),
                    answer = item.answer,
                    riskNote = item.riskNote,
                    embedding = vec,
                )
            }
        )
    }

    suspend fun count(account: BusinessAccount): Int = withContext(Dispatchers.IO) {
        val f = file(account)
        if (!f.exists()) 0 else f.useLines { it.count { line -> line.isNotBlank() } }
    }

    /**
     * 按 question 替换或追加。用于用户编辑某条 QA 的 answer：
     * - 如果本地已有同 question 的条目（之前修订过），直接覆盖
     * - 如果没有（首次修订 assets 来源的条目），追加新条目
     *
     * 注：assets 来源的原条目不动（不可写），但本地修订版有金标 boost 会优先返回。
     */
    suspend fun upsert(
        account: BusinessAccount,
        question: String,
        scene: String,
        answer: String,
        riskNote: String,
        embedding: FloatArray,
    ) = withContext(Dispatchers.IO) {
        val f = file(account)
        val targetQ = question.trim()
        if (targetQ.isEmpty()) return@withContext

        // 读现有所有行，过滤掉同 question 的（去重）
        val keep: List<String> = if (f.exists()) {
            f.useLines { lines ->
                lines.mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) null
                    else if (parseQuestion(trimmed) == targetQ) null
                    else trimmed
                }.toList()
            }
        } else emptyList()

        val newLine = buildLine(scene, targetQ, answer, riskNote, embedding)
        f.writeText((keep + newLine).joinToString("\n", postfix = "\n"))
    }

    /**
     * 把一条非金标 QA 升级为金标：复制原 item（answer/risk_note 不变）写入本地金标 jsonl。
     * 同 question 已存在则跳过（避免重复）。同时清掉 demoted 中的同 question 记录。
     */
    suspend fun promote(
        account: BusinessAccount,
        question: String,
        scene: String,
        answer: String,
        riskNote: String,
        embedding: FloatArray,
    ) = withContext(Dispatchers.IO) {
        val targetQ = question.trim()
        if (targetQ.isEmpty()) return@withContext
        removeDemoted(account, targetQ)

        val f = file(account)
        val exists = f.exists() && f.useLines { lines ->
            lines.any { parseQuestion(it) == targetQ }
        }
        if (exists) return@withContext

        f.appendText(buildLine(scene, targetQ, answer, riskNote, embedding) + "\n")
    }

    /** 读取被用户降级（取消金标）的 question 集合。 */
    suspend fun loadDemoted(account: BusinessAccount): Set<String> = withContext(Dispatchers.IO) {
        val f = demotedFile(account)
        if (!f.exists()) emptySet()
        else f.useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }

    /**
     * 把某个 question 标记为「已取消金标」。
     * 副作用：如果本地金标 jsonl 里有同 question 的条目，一并删除（保持 store 干净）。
     */
    suspend fun addDemoted(account: BusinessAccount, question: String) = withContext(Dispatchers.IO) {
        val q = question.trim()
        if (q.isEmpty()) return@withContext

        val df = demotedFile(account)
        val existing = if (df.exists()) df.useLines { it.map(String::trim).filter(String::isNotEmpty).toSet() } else emptySet()
        if (q !in existing) df.appendText(q + "\n")

        val f = file(account)
        if (f.exists()) {
            val keep = f.useLines { lines ->
                lines.mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) null
                    else if (parseQuestion(trimmed) == q) null
                    else trimmed
                }.toList()
            }
            f.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n"))
        }
    }

    /** 移除某个 question 的降级标记（重新可被金标 boost）。 */
    suspend fun removeDemoted(account: BusinessAccount, question: String) = withContext(Dispatchers.IO) {
        val q = question.trim()
        if (q.isEmpty()) return@withContext
        val df = demotedFile(account)
        if (!df.exists()) return@withContext
        val keep = df.useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && it != q }.toList()
        }
        df.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n"))
    }
}
