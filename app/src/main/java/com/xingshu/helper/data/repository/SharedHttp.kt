package com.xingshu.helper.data.repository

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
