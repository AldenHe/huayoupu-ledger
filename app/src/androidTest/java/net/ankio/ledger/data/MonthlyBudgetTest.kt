package net.ankio.ledger.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.Money
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonthlyBudgetTest {
    @Test fun recurringLimitSurvivesOwnerRecreationAndRejectsInvalidCents() {
        // The QA app has an Application; the instrumentation-only package does not.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(context.packageName == "net.ankio.ledger.qa")
        val budget = MonthlyBudget(context)
        val original = budget.cents()
        try {
            budget.set(200_001L)
            assertEquals(200_001L, MonthlyBudget(context).cents())
            for (invalid in listOf(-1L, Money.MAX_CENTS + 1)) {
                try { budget.set(invalid); fail("Invalid budget was accepted") }
                catch (_: IllegalArgumentException) { }
                assertEquals(200_001L, budget.cents())
            }
            budget.set(0)
            assertEquals(0L, MonthlyBudget(context).cents())
        } finally { budget.set(original) }
    }
}
