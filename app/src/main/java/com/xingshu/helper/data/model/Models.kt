package com.xingshu.helper.data.model

import java.util.concurrent.atomic.AtomicLong

private val nextId = AtomicLong(0)

data class BasketMessage(
    val id: Long = nextId.incrementAndGet(),
    val content: String
)

data class QAItem(
    val scene: String,
    val questions: List<String>,
    val answer: String,
    val riskNote: String = "",
)

sealed class GenerateState {
    data object Idle : GenerateState()
    data object Loading : GenerateState()

    /** 流式中：text 是已经累计到的回复正文。复制按钮在该状态下应禁用。 */
    data class Streaming(val text: String) : GenerateState()

    /** 流式结束：text 是最终回复正文。复制按钮启用。 */
    data class Success(val text: String) : GenerateState()

    data class Error(val message: String) : GenerateState()
}

enum class PanelScreen { MAIN, RESULT, SETTINGS, SNIPPETS }

/** 常用片段：客服可一键复制的标准措辞，不走 RAG，直接静态加载。 */
data class Snippet(
    val category: String,
    val title: String,
    val text: String,
)

enum class DialogRole { CUSTOMER, ME }

data class DialogMessage(
    val role: DialogRole,
    val text: String
)

sealed class VisionState {
    data object Idle : VisionState()
    data object Loading : VisionState()
    data class Success(
        val messages: List<DialogMessage>,
        val rawJson: String,
        val promptTokens: Int,
        val completionTokens: Int
    ) : VisionState()
    data class Error(val message: String) : VisionState()
}
