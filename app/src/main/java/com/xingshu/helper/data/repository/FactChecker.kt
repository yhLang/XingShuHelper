package com.xingshu.helper.data.repository

/**
 * 防幻觉硬约束：检查 LLM 生成的回复里出现的"硬事实"（价格、时段）
 * 是否都在结构化知识库里。不在 KB 里的标记为可疑，UI 给客服明显的警告。
 *
 * 这个检查只针对"绝不能编"的字段，不去管自然描述里的数字（"3 个孩子"、
 * "1 节课"等）。范围越窄误报越少，客服越愿意看警告。
 */
object FactChecker {

    /**
     * @return 可疑片段列表（带原文上下文），空列表表示全部通过。
     */
    fun check(reply: String, structuredKb: String): List<String> {
        if (reply.isBlank() || structuredKb.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        out += checkPrices(reply, structuredKb)
        out += checkTimes(reply, structuredKb)
        return out.distinct()
    }

    /**
     * 价格匹配：
     * - "¥1234" / "￥1234" / "1234元" / "1234块" / "1234 元"
     * - 提取数字部分到 KB 里查（KB 里"¥1550"、"1550元"、"50元/节"任意一种都算命中）
     */
    private fun checkPrices(reply: String, kb: String): List<String> {
        val regex = Regex("[¥￥]\\s*(\\d{2,5})|(\\d{2,5})\\s*[元块￥]")
        return regex.findAll(reply).mapNotNull { m ->
            val number = m.groupValues[1].ifEmpty { m.groupValues[2] }
            // 数字本身在 KB 里出现就算合法（前后字符不强制要求是货币符号，
            // 因为 KB 排版灵活：可能是"¥1550"也可能是"16次¥1550"）
            if (!kb.contains(number)) m.value.trim() else null
        }.toList()
    }

    /**
     * 时段匹配：HH:MM 格式（半小时半小时这种自然语言不查，会误报太多）。
     */
    private fun checkTimes(reply: String, kb: String): List<String> {
        val regex = Regex("(\\d{1,2}):(\\d{2})")
        return regex.findAll(reply).mapNotNull { m ->
            val time = m.value
            // KB 包含这个时间点字符串就算合法
            if (!kb.contains(time)) time else null
        }.toList()
    }
}
