package com.yuanshan.locbeacon

import android.content.Context

/** 服务器地址和 Token 存这里，App 重启后不用重填。 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("loc_beacon", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(value) {
            sp.edit().putString("url", value.trim()).apply()
        }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) {
            sp.edit().putString("token", value.trim()).apply()
        }
}
