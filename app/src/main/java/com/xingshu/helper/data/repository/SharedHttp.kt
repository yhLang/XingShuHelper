package com.xingshu.helper.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 进程内共享的 OkHttpClient：所有调外部 API 的 Repository 复用同一份连接池 / 线程池。
 * 避免每个 Repository 各自 build 一份导致连接和线程数翻倍。
 */
internal val sharedHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

/** 全局共享的 Json 配置。所有 Repository 都用 ignoreUnknownKeys=true，统一一份。 */
internal val sharedJson: Json = Json { ignoreUnknownKeys = true }

/**
 * 从 OpenAI 兼容协议的错误 body 里提取 `error.message`。失败返回 null。
 * 抽出来给 AIRepository / VisionRepository 共用，避免两份完全相同的实现。
 */
internal fun extractApiError(body: String): String? {
    return try {
        val obj = sharedJson.parseToJsonElement(body) as? JsonObject
        obj?.get("error")
            ?.let { it as? JsonObject }
            ?.get("message")
            ?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
}
