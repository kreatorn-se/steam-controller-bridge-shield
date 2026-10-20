package com.steamcontroller.android.hub

import android.content.Context
import android.os.Build

/**
 * Link state for Gamehub. Kept in its own preferences file on purpose: the device token must
 * never travel inside a settings backup (BackupManager exports the main prefs).
 */
object GamehubStore {
    const val DEFAULT_URL = "https://gamehub.kreatorn.se"

    private const val FILE = "gamehub"
    private const val K_URL = "url"
    private const val K_DEVICE_ID = "device_id"
    private const val K_TOKEN = "token"
    private const val K_NAME = "name"
    private const val K_REV = "config_rev"
    private const val K_HASH = "config_hash"
    private const val K_LAST_OK = "last_ok_at"
    private const val K_LAST_ERROR = "last_error"
    private const val K_AUTO_SHIZUKU = "auto_start_shizuku"

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun url(c: Context): String = p(c).getString(K_URL, DEFAULT_URL) ?: DEFAULT_URL
    fun token(c: Context): String? = p(c).getString(K_TOKEN, null)
    fun deviceName(c: Context): String = p(c).getString(K_NAME, null) ?: defaultName()
    fun isLinked(c: Context): Boolean = token(c) != null

    fun defaultName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Shield"

    fun saveLink(c: Context, url: String, deviceId: String, token: String, name: String) {
        p(c).edit()
            .putString(K_URL, url).putString(K_DEVICE_ID, deviceId).putString(K_TOKEN, token)
            .putString(K_NAME, name).putInt(K_REV, 0).remove(K_HASH).remove(K_LAST_ERROR)
            .apply()
    }

    fun clearLink(c: Context) {
        p(c).edit().remove(K_DEVICE_ID).remove(K_TOKEN).remove(K_REV).remove(K_HASH)
            .remove(K_LAST_OK).remove(K_LAST_ERROR).apply()
    }

    fun configRev(c: Context): Int = p(c).getInt(K_REV, 0)
    fun configHash(c: Context): String? = p(c).getString(K_HASH, null)
    fun setConfigState(c: Context, rev: Int, hash: String) {
        p(c).edit().putInt(K_REV, rev).putString(K_HASH, hash).apply()
    }

    fun lastOkAt(c: Context): Long = p(c).getLong(K_LAST_OK, 0L)
    fun lastError(c: Context): String? = p(c).getString(K_LAST_ERROR, null)
    fun markOk(c: Context) { p(c).edit().putLong(K_LAST_OK, System.currentTimeMillis()).remove(K_LAST_ERROR).apply() }
    fun markError(c: Context, msg: String) { p(c).edit().putString(K_LAST_ERROR, msg.take(200)).apply() }

    fun autoStartShizuku(c: Context): Boolean = p(c).getBoolean(K_AUTO_SHIZUKU, true)
    fun setAutoStartShizuku(c: Context, on: Boolean) { p(c).edit().putBoolean(K_AUTO_SHIZUKU, on).apply() }
}
