package com.hukua.fundradar.data

import android.content.Context

/**
 * One of the user's fund lists, persisted as an ordered, comma separated string
 * in SharedPreferences. The app never shows funds the user did not add.
 *
 * Two lists exist (see [FundScope]): the portfolio and the purchase-limit
 * watchlist. They share nothing but this class.
 */
class WatchlistStore(
    context: Context,
    scope: FundScope = FundScope.PORTFOLIO
) {

    private val prefs = context.applicationContext
        .getSharedPreferences("fund_radar", Context.MODE_PRIVATE)

    private val key = scope.prefsKey

    fun codes(): List<String> = prefs.getString(key, "").orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    fun contains(code: String): Boolean = codes().contains(code)

    /** Adds a code at the end; returns false when it was already present. */
    fun add(code: String): Boolean {
        val current = codes()
        if (current.contains(code)) return false
        prefs.edit().putString(key, (current + code).joinToString(",")).apply()
        return true
    }

    fun addAll(codes: List<String>) {
        val current = codes()
        val merged = (current + codes.map { it.trim() }.filter { it.isNotEmpty() })
            .distinct()
        prefs.edit().putString(key, merged.joinToString(",")).apply()
    }

    fun remove(code: String) {
        val next = codes().filterNot { it == code }
        prefs.edit().putString(key, next.joinToString(",")).apply()
    }
}
