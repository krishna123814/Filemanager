package com.krishna.myfiles

import android.content.Context
import okhttp3.Credentials

/** Server URL / login + chhoti settings. Password app ke private storage me rehta hai. */
object Session {
    const val DEFAULT_URL = "https://u8cyy.aiccloud.online/dav/"

    @Volatile var baseUrl: String = DEFAULT_URL
    @Volatile var user: String = ""
    @Volatile var pass: String = ""

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("myfiles", Context.MODE_PRIVATE)

    private fun posPrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("positions", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = sp(ctx)
        baseUrl = p.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        user = p.getString("user", "") ?: ""
        pass = p.getString("pass", "") ?: ""
    }

    fun set(url: String, u: String, p: String) {
        baseUrl = url
        user = u
        pass = p
    }

    fun persist(ctx: Context) {
        sp(ctx).edit()
            .putString("url", baseUrl)
            .putString("user", user)
            .putString("pass", pass)
            .apply()
    }

    fun clearPass(ctx: Context) {
        pass = ""
        sp(ctx).edit().remove("pass").apply()
    }

    fun hasCreds(): Boolean = user.isNotEmpty() && pass.isNotEmpty()

    fun auth(): String = Credentials.basic(user, pass)

    fun bool(ctx: Context, key: String, def: Boolean): Boolean = sp(ctx).getBoolean(key, def)
    fun setBool(ctx: Context, key: String, v: Boolean) = sp(ctx).edit().putBoolean(key, v).apply()
    fun int(ctx: Context, key: String, def: Int): Int = sp(ctx).getInt(key, def)
    fun setInt(ctx: Context, key: String, v: Int) = sp(ctx).edit().putInt(key, v).apply()

    // Video/audio resume position (ms), key = file URL
    fun getPos(ctx: Context, key: String): Long = posPrefs(ctx).getLong(key, 0L)
    fun setPos(ctx: Context, key: String, ms: Long) = posPrefs(ctx).edit().putLong(key, ms).apply()
}
