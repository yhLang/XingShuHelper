package com.xingshu.helper

internal object AppConfig {
    // 从 BuildConfig 读取（值在 build.gradle.kts 里来自 local.properties，不进 git）
    // 注意：BuildConfig 字段非编译期常量，因此这里用 val 而不是 const val
    val API_KEY: String = BuildConfig.DASHSCOPE_API_KEY
    const val API_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    const val CHAT_MODEL = "deepseek-v4-flash"
    const val VISION_MODEL = "qwen-vl-max"
}
