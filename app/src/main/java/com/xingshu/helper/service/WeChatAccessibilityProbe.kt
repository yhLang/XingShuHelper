package com.xingshu.helper.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Sprint 0 PoC：临时探测微信单聊对话窗口的 view tree 结构。
 *
 * 目标（验证后即删）：
 *   1. 单聊切换稳定触发 → 打印窗口标题（客户昵称）和气泡数量
 *   2. 客户/自己气泡区分准确率 → 用左右坐标启发式判断，打印对比
 *   3. 国产 ROM 不杀后台 → 跑两天观察日志连续性
 *
 * 不做：
 *   - 不写状态、不发事件、不持久化任何信息
 *   - 不动 ViewModel / UI / 业务流
 *   - 不点任何按钮、不填任何输入框
 *
 * 验证标准（在 logcat 用 `adb logcat -s WeChatProbe` 看）：
 *   - 切换对话时出现 [STATE] window=... title=...
 *   - 收到/发送消息时出现 [CONTENT] bubbles=N customer=A me=B
 *   - 标题里的 emoji / 备注名能正确取出
 *   - 客户气泡（左侧）和自己气泡（右侧）不串
 *
 * 用法：
 *   设置 → 无障碍 → 已下载的服务 → 行恕客服助手（调试） → 开启
 *   然后用微信单聊一会儿，跑 `adb logcat -s WeChatProbe > probe.log` 收集
 */
class WeChatAccessibilityProbe : AccessibilityService() {

    override fun onServiceConnected() {
        Log.i(TAG, "[INIT] probe connected, waiting for WeChat events…")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // 调试：所有事件先打一行心跳，确认服务真的在收事件
        Log.v(TAG, "[RAW] pkg=${event.packageName} type=${AccessibilityEvent.eventTypeToString(event.eventType)} cls=${event.className}")

        if (event.packageName != WECHAT_PKG) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleStateChanged(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContentChanged(event)
            else -> Log.d(TAG, "[OTHER] type=${AccessibilityEvent.eventTypeToString(event.eventType)}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "[INTERRUPT] service interrupted")
    }

    private fun handleStateChanged(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: ""
        // 微信单聊 Activity 类名（历史观察）：com.tencent.mm.ui.LauncherUI 或 ChattingUI
        // 不在 WHITELIST 里也打日志，便于发现新版本类名变化
        val title = rootInActiveWindow?.findTitle().orEmpty()
        Log.d(TAG, "[STATE] activity=$className title=\"$title\" inChat=${className in CHAT_ACTIVITY_HINTS}")
    }

    private fun handleContentChanged(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        // 节流：只在前 5 秒详细打印，之后每 50 个 content 事件打印一次摘要，避免 log 洪水
        contentEventCount++
        val verbose = contentEventCount <= 20 || contentEventCount % 50 == 0L
        if (!verbose) return

        val title = root.findTitle().orEmpty()
        val bubbles = root.collectBubbles()
        val customerCount = bubbles.count { it.role == Role.CUSTOMER }
        val meCount = bubbles.count { it.role == Role.ME }
        val unknownCount = bubbles.count { it.role == Role.UNKNOWN }

        Log.d(
            TAG,
            "[CONTENT] #$contentEventCount title=\"$title\" bubbles=${bubbles.size} " +
                "customer=$customerCount me=$meCount unknown=$unknownCount " +
                "screen=${root.windowWidth()}x${root.windowHeight()}"
        )

        // 详细模式：打印最后 3 条气泡的 role + 文本前 30 字 + 横坐标，方便判断左右识别准不准
        bubbles.takeLast(3).forEach { b ->
            Log.v(TAG, "  └─ ${b.role} x=${b.centerX} \"${b.text.take(30).replace('\n', ' ')}\"")
        }
    }

    /** 找窗口标题：微信单聊顶部 ActionBar 的 TextView，通常是客户昵称/备注。 */
    private fun AccessibilityNodeInfo.findTitle(): String? {
        // 启发式：找深度 ≤ 6 的第一个 TextView，且文本不为空、长度 ≤ 30
        // 这是 PoC 的粗略实现，正式版会改成按 view-id 查
        return findShallowTextView(maxDepth = 6, predicate = { txt ->
            txt.isNotBlank() && txt.length in 1..30 && !txt.contains('\n')
        })
    }

    private fun AccessibilityNodeInfo.findShallowTextView(
        maxDepth: Int,
        predicate: (String) -> Boolean,
        depth: Int = 0,
    ): String? {
        if (depth > maxDepth) return null
        val cls = className?.toString().orEmpty()
        if (cls.endsWith("TextView")) {
            val txt = text?.toString()
            if (txt != null && predicate(txt)) return txt
        }
        for (i in 0 until childCount) {
            val child = getChild(i) ?: continue
            try {
                child.findShallowTextView(maxDepth, predicate, depth + 1)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private data class Bubble(val role: Role, val text: String, val centerX: Int)
    private enum class Role { CUSTOMER, ME, UNKNOWN }

    /**
     * 遍历对话区域所有 TextView，按节点中心横坐标判断左右：
     *   - 屏幕中线偏左（< 45%）→ CUSTOMER
     *   - 屏幕中线偏右（> 55%）→ ME
     *   - 中间 → UNKNOWN（系统提示 / 时间戳 / 撤回提示）
     */
    private fun AccessibilityNodeInfo.collectBubbles(): List<Bubble> {
        val screenW = windowWidth().takeIf { it > 0 } ?: return emptyList()
        val out = mutableListOf<Bubble>()
        walk { node ->
            val cls = node.className?.toString().orEmpty()
            if (!cls.endsWith("TextView")) return@walk
            val txt = node.text?.toString().orEmpty()
            if (txt.isBlank() || txt.length > 500) return@walk
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            // 顶部 ActionBar 区域内的 TextView 不是气泡（标题/返回键/菜单）
            if (rect.top < 200) return@walk
            // 底部输入栏区域也排除（输入框里的草稿、发送按钮文字）
            if (rect.top > screenW * 2.3) return@walk  // 粗略：手机一般 height ≈ width * 2.0~2.3
            val cx = (rect.left + rect.right) / 2
            val role = when {
                cx < screenW * 0.45 -> Role.CUSTOMER
                cx > screenW * 0.55 -> Role.ME
                else -> Role.UNKNOWN
            }
            out += Bubble(role, txt, cx)
        }
        return out
    }

    private fun AccessibilityNodeInfo.walk(visit: (AccessibilityNodeInfo) -> Unit) {
        visit(this)
        for (i in 0 until childCount) {
            val child = getChild(i) ?: continue
            try {
                child.walk(visit)
            } finally {
                child.recycle()
            }
        }
    }

    private fun AccessibilityNodeInfo.windowWidth(): Int {
        val rect = android.graphics.Rect()
        getBoundsInScreen(rect)
        return rect.width()
    }

    private fun AccessibilityNodeInfo.windowHeight(): Int {
        val rect = android.graphics.Rect()
        getBoundsInScreen(rect)
        return rect.height()
    }

    private var contentEventCount = 0L

    companion object {
        private const val TAG = "WeChatProbe"
        private const val WECHAT_PKG = "com.tencent.mm"
        // 历史观察的微信聊天 Activity 类名，仅用于日志判断。新版本不在表内也照常采样
        private val CHAT_ACTIVITY_HINTS = setOf(
            "com.tencent.mm.ui.LauncherUI",
            "com.tencent.mm.ui.chatting.ChattingUI",
            "com.tencent.mm.plugin.chatroom.ui.ChatroomInfoUI"
        )
    }
}
