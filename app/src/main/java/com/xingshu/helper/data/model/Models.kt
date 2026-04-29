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
    /** 金标话术：人工挑选的高质量回复，prompt 中会作为标准回复优先采用。 */
    val isGold: Boolean = false,
)

data class RagMatch(
    val scene: String,
    val answer: String,
    val score: Float
)

data class GeneratedResult(
    val isSensitive: Boolean = false,
    val sensitiveNote: String = "",
    val shortVersion: String = "",
    val naturalVersion: String = "",
    val inviteVersion: String = "",
    val intent: String = "",
    val nextStep: String = "",
    val humanConfirm: String = "",
    val isDirectMatch: Boolean = false,
    val ragMatches: List<RagMatch> = emptyList()
)

sealed class GenerateState {
    data object Idle : GenerateState()
    data object Loading : GenerateState()
    data class Success(val result: GeneratedResult) : GenerateState()
    data class Error(val message: String) : GenerateState()
}

enum class PanelScreen { MAIN, RESULT, SETTINGS, SNIPPETS, ADD_GOLD }

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
