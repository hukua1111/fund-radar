package com.hukua.fundradar.data

import android.content.Context

/**
 * The user's own fund list, persisted as an ordered, comma separated string in
 * SharedPreferences. The app never shows funds the user did not add.
 */
class WatchlistStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("fund_radar", Context.MODE_PRIVATE)

    fun codes(): List<String> = prefs.getString(KEY_CODES, "").orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    fun contains(code: String): Boolean = codes().contains(code)

    /** Adds a code at the end; returns false when it was already present. */
    fun add(code: String): Boolean {
        val current = codes()
        if (current.contains(code)) return false
        prefs.edit().putString(KEY_CODES, (current + code).joinToString(",")).apply()
        return true
    }

    fun remove(code: String) {
        val next = codes().filterNot { it == code }
        prefs.edit().putString(KEY_CODES, next.joinToString(",")).apply()
    }

    private companion object {
        const val KEY_CODES = "watchlist"
    }
}
