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
    val riskNote: String = ""
)

enum class GenerateMode { RAG_ONLY, RAG_PLUS_AI }

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

enum class PanelScreen { MAIN, RESULT, SETTINGS }

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
