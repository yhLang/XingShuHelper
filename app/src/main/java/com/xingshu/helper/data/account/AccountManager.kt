package com.xingshu.helper.data.account

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 业务账号 = 微信号 = 一套独立的话术库。
 * 两台手机各自固定一个账号，运行期不切换；但保留切换能力以备调试。
 */
enum class BusinessAccount(val key: String, val displayName: String, val brandName: String) {
    KIRIN("kirin", "麒麟斋", "行恕书院（麒麟斋）"),
    XINGSHU("xingshu", "行恕书院", "行恕书院（万科校区）");

    companion object {
        fun fromKey(key: String?): BusinessAccount =
            values().firstOrNull { it.key == key } ?: KIRIN
    }
}

class AccountManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _current = MutableStateFlow(load())
    val current: StateFlow<BusinessAccount> = _current

    fun set(account: BusinessAccount) {
        prefs.edit().putString(KEY_ACCOUNT, account.key).apply()
        _current.value = account
    }

    private fun load(): BusinessAccount =
        BusinessAccount.fromKey(prefs.getString(KEY_ACCOUNT, null))

    companion object {
        private const val PREF_NAME = "xingshu_helper_prefs"
        private const val KEY_ACCOUNT = "business_account"
    }
}
