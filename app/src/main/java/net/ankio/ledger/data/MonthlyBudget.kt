package net.ankio.ledger.data

import android.content.Context
import net.ankio.ledger.core.Money

/** A recurring monthly expense limit. Zero means no limit has been set. */
class MonthlyBudget(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("monthly_budget", Context.MODE_PRIVATE)
    fun cents(): Long = preferences.getLong("expense_limit_cents", 0L)
    fun set(cents: Long) {
        require(cents in 0..Money.MAX_CENTS) { "月限额无效" }
        check(preferences.edit().putLong("expense_limit_cents", cents).commit()) { "月限额保存失败，请重试" }
    }
}
