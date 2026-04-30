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

        /**
         * 把回复文本填入当前微信前台的输入框。仅 SET_TEXT，不点发送。
         * @return true 表示找到了输入框并写入成功；false 表示当前不在微信、
         *         或者找不到输入框节点（可能客服没把微信放前台）。
         */
        fun fillReplyToWeChat(text: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != WECHAT_PKG) return false
            val input = root.findInputEditText() ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        /** 在 view tree 里找输入框：标准 EditText，且大小可接受。 */
        private fun AccessibilityNodeInfo.findInputEditText(): AccessibilityNodeInfo? {
            val cls = className?.toString().orEmpty()
            if (cls == "android.widget.EditText" && isEditable) return this
            for (i in 0 until childCount) {
                val child = getChild(i) ?: continue
                val hit = child.findInputEditText()
                if (hit != null) return hit
                child.recycle()
            }
            return null
        }
    }
}
