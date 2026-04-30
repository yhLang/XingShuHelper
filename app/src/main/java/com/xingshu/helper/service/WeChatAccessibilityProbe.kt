package com.xingshu.helper.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 微信输入框注入器（无障碍服务）。
 *
 * 历史背景：
 * 这个服务原本是 Sprint 0 PoC，用来验证"无障碍读单聊对话"的可行性。
 * 跑下来发现微信对单聊 RecyclerView 做了反屏蔽（rootInActiveWindow 整树
 * 只有 1 个根节点，子节点全被 importantForAccessibility=NO_HIDE_DESCENDANTS
 * 或自绘屏蔽掉），交叉验证短信/QQ 等正常 App 都能 dump 出对话文字 → 100%
 * 是微信主动反制，不是代码或设备问题。
 *
 * 因此"读对话"的方向放弃，但服务本体保留：
 * - 微信对话区域不可见 ≠ 微信输入框不可见。输入框是标准 EditText，
 *   微信不能屏蔽（屏蔽了用户自己也输不了字）。
 * - 这个能力可以让结果页"复制"按钮升级成"填入微信"按钮，省掉客服
 *   切回微信粘贴这一步。
 *
 * 当前职责（极小）：
 * - 监听微信窗口事件，缓存最近一次 rootInActiveWindow 引用
 * - 提供 fillReplyToWeChat(text) 静态 API，找到微信底部输入框并 SET_TEXT
 * - 不读对话、不点发送、不存储任何数据
 *
 * 当前并未在产品中启用。后续 Sprint 接入"填入微信"按钮时再串起来。
 */
class WeChatAccessibilityProbe : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 当前不处理任何事件 —— 仅靠 rootInActiveWindow 在调用 fillReplyToWeChat
        // 时实时拉取一次。后续若需要"切对话时刷新缓存的客户档案"，再在这里加。
    }

    override fun onInterrupt() {
        Log.w(TAG, "service interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WeChatInjector"
        private const val WECHAT_PKG = "com.tencent.mm"

        @Volatile
        private var instance: WeChatAccessibilityProbe? = null

        /** 服务是否已被系统绑定（用户已开无障碍权限）。 */
        fun isReady(): Boolean = instance != null

        /** 填入结果，让 UI 给客服明确的提示。 */
        sealed class FillResult {
            object Success : FillResult()
            object ServiceNotEnabled : FillResult()      // 用户没开无障碍权限
            object NotInWeChat : FillResult()            // 当前前台不是微信
            object InputBoxNotFound : FillResult()       // 在微信但找不到输入框（不在对话页）
            object SetTextFailed : FillResult()          // 找到输入框但 SET_TEXT 调用失败
        }

        /**
         * 把回复文本填入当前微信前台的输入框。仅 SET_TEXT，不点发送。
         * 调用前必须保证微信已经在前台、且打开了某个具体的对话页（不是聊天列表）。
         */
        fun fillReplyToWeChat(text: String): FillResult {
            val svc = instance ?: return FillResult.ServiceNotEnabled
            val root = svc.rootInActiveWindow ?: return FillResult.NotInWeChat
            if (root.packageName?.toString() != WECHAT_PKG) return FillResult.NotInWeChat
            val input = root.findInputEditText()
            if (input == null) {
                // 调试：找不到输入框时 dump 所有 editable 节点，看微信用什么自定义类
                Log.w(TAG, "input not found, dumping all editable / EditText-like nodes:")
                root.walkDump()
                return FillResult.InputBoxNotFound
            }
            Log.i(TAG, "input found: cls=${input.className} id=${input.viewIdResourceName}")
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            return if (ok) FillResult.Success else FillResult.SetTextFailed
        }

        /** 找输入框：放宽到任何 isEditable=true 的节点。 */
        private fun AccessibilityNodeInfo.findInputEditText(): AccessibilityNodeInfo? {
            if (isEditable) return this
            val cls = className?.toString().orEmpty()
            if (cls.endsWith("EditText")) return this
            for (i in 0 until childCount) {
                val child = getChild(i) ?: continue
                val hit = child.findInputEditText()
                if (hit != null) return hit
                child.recycle()
            }
            return null
        }

        /** 调试：遍历节点，把可能是输入框的全打出来。 */
        private fun AccessibilityNodeInfo.walkDump() {
            val cls = className?.toString().orEmpty()
            val interesting = isEditable ||
                cls.contains("Edit", ignoreCase = true) ||
                cls.contains("Input", ignoreCase = true) ||
                cls.endsWith("TextView")  // 微信可能用 TextView 当输入框
            if (interesting) {
                val rect = android.graphics.Rect()
                getBoundsInScreen(rect)
                Log.w(
                    TAG,
                    "  cls=$cls id=${viewIdResourceName ?: "-"} editable=$isEditable " +
                        "focused=$isFocused clickable=$isClickable " +
                        "x=${rect.left}-${rect.right} y=${rect.top}-${rect.bottom} " +
                        "text=\"${text?.toString()?.take(30)?.replace('\n', ' ')}\""
                )
            }
            for (i in 0 until childCount) {
                val child = getChild(i) ?: continue
                child.walkDump()
                child.recycle()
            }
        }
    }
}
