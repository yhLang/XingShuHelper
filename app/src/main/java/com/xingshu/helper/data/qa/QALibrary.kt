package com.xingshu.helper.data.qa

import com.xingshu.helper.data.model.QAItem

/**
 * Prompt 构建器：把 ROLE_INSTRUCTION + 结构化 KB + RAG 召回的金标话术拼成 system prompt。
 *
 * 历史包袱：早期版本这里有 20 条硬编码兜底话术（QALibrary.items / systemPrompt），
 * 在金标语料还没建立时用作 RAG fallback。现在云端金标已经覆盖（141 条 xingshu /
 * 73 条 kirin），且 RAG 几乎不会召回为空（查询任意文本都能找到 top-5），硬编码
 * 已废弃删除。仅保留 buildPrompt 函数和 ROLE_INSTRUCTION。
 */
object QALibrary {

    // 输出 schema 已由 AIRepository 的 tool calling 强制约束，prompt 里不再重复说明。
    fun buildPrompt(contextItems: List<QAItem>, structuredContext: String = ""): String = buildString {
        appendLine(ROLE_INSTRUCTION)
        appendLine()
        if (structuredContext.isNotBlank()) {
            appendLine("## 当期课程结构化数据（价格、时段以此为准，回答时直接引用）")
            appendLine()
            appendLine(structuredContext.trimEnd())
            appendLine()
        }
        if (contextItems.isNotEmpty()) {
            appendLine("## 标准话术（请优先采用其措辞、长度和语气）")
            appendLine()
            contextItems.forEach { qa ->
                appendLine("【${qa.scene}】")
                appendLine("客户问法：${qa.questions.joinToString("、")}")
                appendLine("推荐回复：${qa.answer}")
                if (qa.riskNote.isNotBlank()) appendLine("注意：${qa.riskNote}")
                appendLine()
            }
        }
    }

    private val ROLE_INSTRUCTION = """
你是行恕书画艺术培训中心的 AI 客服助手，专门帮助前台客服人员快速生成微信回复草稿。

## 回复原则
- 语气自然亲切，像微信真人客服，不要像官方模板
- 下方"结构化数据"里有准确的价格、时段、地址、老师排班、优惠政策，**请直接引用，无需引导来咨询**
- 结构化数据没有的事实（老师资质、教学承诺、退费规则等），**不要编造**，标记为需要人工确认
- 不承诺效果、不保证考级通过、不保证获奖
- 不贬低同行
- 回答完事实后，可顺势引导预约试听

## 敏感场景（必须标记 is_sensitive: true）
退费、投诉、老师更换、价格优惠、付款问题、效果保证、考级/比赛保证、孩子心理/健康问题、负面评价或纠纷
    """.trimIndent()

}
