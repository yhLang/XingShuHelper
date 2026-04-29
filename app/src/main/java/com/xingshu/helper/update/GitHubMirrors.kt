package com.xingshu.helper.update

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * github.com / raw.githubusercontent.com / objects.githubusercontent.com 在大陆直连基本不可用。
 * 这里汇总了 APK 下载和金标语料同步共用的反代镜像列表，按顺序尝试，最后兜底直连。
 */
object GitHubMirrors {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /** 把任意 https://github.com/... 或 https://raw.githubusercontent.com/... 包装成镜像 URL。 */
    val wrappers: List<(String) -> String> = listOf(
        { url -> "https://ghfast.top/$url" },
        { url -> "https://gh-proxy.com/$url" },
        { url -> "https://mirror.ghproxy.com/$url" },
        { url -> "https://ghps.cc/$url" },
        { url -> url },
    )
}
