package net.ankio.ledger.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyDisplayTest {
    @Test fun readingKeepsEveryCentWithoutPaddingAndExportsRetainFixedScale() {
        for((cents,text) in listOf(0L to "0",5400L to "54",5410L to "54.1",5401L to "54.01",-350050L to "-3500.5",Money.MAX_CENTS to "999999999.99")) {
            assertEquals(text,Money.display(cents))
        }
        assertEquals("54.00",Money.format(5400))
        assertEquals(5410L,Money.parseCents("54.10"))
    }
}
