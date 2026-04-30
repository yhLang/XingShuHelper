package com.xingshu.helper.data.repository

import android.content.Context
import android.util.Log
import com.xingshu.helper.data.account.BusinessAccount

class StructuredKnowledgeBase(private val context: Context) {

    private val sync = CorpusSyncManager(context)

    fun load(account: BusinessAccount): String {
        val local = sync.localStructuredFile(account)
        if (local.exists() && local.length() > 0) {
            return local.readText(Charsets.UTF_8)
        }
        return runCatching {
            context.assets.open("structured_${account.key}.txt")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrElse {
            Log.w("StructuredKB", "结构化知识库未找到 [${account.key}]")
            ""
        }
    }
}
