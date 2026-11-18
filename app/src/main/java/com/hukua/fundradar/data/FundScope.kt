package com.hukua.fundradar.data

/**
 * The app keeps three independent fund lists:
 *
 * - [PORTFOLIO] — what the user actually holds. No purchase-limit judging, the
 *   card is a plain overview plus an intraday estimate (see [Estimate]).
 * - [LIMIT] — a small, purpose-built list of funds whose 申购额度 matters.
 * - [DCA] — funds bought on a schedule. Its own list on purpose: a fund can be
 *   worth a monthly plan before there is a position, and a position does not
 *   have to be on a plan.
 *
 * [PORTFOLIO] deliberately reuses the old `watchlist` preference key, so an
 * upgrade moves the existing list into the new overview page instead of
 * emptying it.
 */
enum class FundScope(internal val prefsKey: String) {
    PORTFOLIO("watchlist"),
    LIMIT("limit_watchlist"),
    DCA("dca_watchlist");

    val isPortfolio: Boolean get() = this == PORTFOLIO

    /**
     * 需要盘中估算的页：持仓页和定投页都按跟踪指数估涨跌；限购页只看额度，
     * 不必为估算多打两轮行情。
     */
    val wantsEstimate: Boolean get() = this != LIMIT
}
