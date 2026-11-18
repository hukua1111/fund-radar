package com.hukua.fundradar.data

import android.content.Context

/**
 * The app keeps two independent fund lists:
 *
 * - [PORTFOLIO] — what the user actually holds. No purchase-limit judging, the
 *   card is a plain overview plus an intraday estimate (see [Estimate]).
 * - [LIMIT] — a small, purpose-built list of funds whose 申购额度 matters.
 *
 * [PORTFOLIO] deliberately reuses the old `watchlist` preference key, so an
 * upgrade moves the existing list into the new overview page instead of
 * emptying it.
 */
enum class FundScope(internal val prefsKey: String) {
    PORTFOLIO("watchlist"),
    LIMIT("limit_watchlist");

    val isPortfolio: Boolean get() = this == PORTFOLIO
}
