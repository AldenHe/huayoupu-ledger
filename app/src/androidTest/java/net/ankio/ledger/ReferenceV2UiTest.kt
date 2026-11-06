package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.Source
import net.ankio.ledger.data.LedgerRecord
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** UI events must persist through the actual native bridge, rather than prototype-only arrays. */
@RunWith(AndroidJUnit4::class)
class ReferenceV2UiTest {
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun manualEntryCategoryDirectionNoteAndDraftSurviveRecreation() {
        val prefix = "V2-${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web = WebUiProbe(scenario)
                web.ready()
                web.run("document.querySelector('[aria-label=\"添加记账\"]').click()")
                web.await("manualOpen && !document.querySelector('#manual-panel').hidden")
                web.run("document.querySelector('#manual-type [data-type=\"expense\"]').click();document.querySelector('#category-grid [data-category=\"交通\"]').click();document.querySelector('#manual-amount').value='12.34';document.querySelector('#manual-note').value=${quote(prefix + " 支出")};document.querySelector('#manual-note').dispatchEvent(new Event('input',{bubbles:true}))")
                scenario.recreate()
                web.ready()
                web.await("manualOpen && !document.querySelector('#manual-panel').hidden && document.querySelector('#manual-amount').value==='12.34' && document.querySelector('#manual-note').value===${quote(prefix + " 支出")} && document.querySelector('#category-grid [data-category=\"交通\"]').classList.contains('active')")
                web.run("document.querySelector('#manual-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))")
                web.await("document.querySelector('#ledger').classList.contains('mobile-active') && JSON.parse(LedgerNative.snapshot()).records.some(r=>r.note===${quote(prefix + " 支出")}&&r.amountCents===1234&&r.direction==='EXPENSE'&&r.category==='交通'&&r.source==='MANUAL')")
                web.run("document.querySelector('[aria-label=\"添加记账\"]').click();document.querySelector('#manual-type [data-type=\"income\"]').click();document.querySelector('#manual-amount').value='56.78';document.querySelector('#manual-note').value=${quote(prefix + " 收入")};document.querySelector('#manual-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))")
                web.await("document.querySelector('#ledger').classList.contains('mobile-active') && JSON.parse(LedgerNative.snapshot()).records.some(r=>r.note===${quote(prefix + " 收入")}&&r.amountCents===5678&&r.direction==='INCOME')")
                scenario.recreate()
                web.ready()
                val saved = app.store.all().filter { it.note.startsWith(prefix) }
                assertEquals(2, saved.size)
                assertEquals(setOf(Direction.EXPENSE, Direction.INCOME), saved.map { it.bill.direction }.toSet())
                assertTrue(saved.all { it.capture.source == Source.MANUAL })
            }
        } finally { cleanupRecords(prefix) }
    }

    @Test fun statisticsUsesActualSelectedMonthRecordsForTotalsCountAndBothFilters() {
        val prefix = "V2-${UUID.randomUUID()}"
        val time = LocalDateTime.of(2097, 7, 15, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val created = listOf(
            seed(prefix + " 支出一", "餐饮", 1234, Direction.EXPENSE, time),
            seed(prefix + " 支出二", "交通", 250, Direction.EXPENSE, time + 60_000),
            seed(prefix + " 收入", "其他收入", 4567, Direction.INCOME, time + 120_000),
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web = WebUiProbe(scenario)
                web.ready()
                web.run("document.querySelector('#month').click();document.querySelector('#month-picker').value='2097-07';document.querySelector('#month-apply').click();document.querySelector('#ledger .bottom-nav button:nth-child(2)').click()")
                web.await("document.querySelector('#stats').classList.contains('mobile-active') && document.querySelector('#stats-month').textContent.includes('2097年7月')")
                // Older Android WebViews ignore CSS inset; the ring hole and center must still fill their boxes.
                web.await("(()=>{const donut=document.querySelector('#donut').getBoundingClientRect(),center=document.querySelector('.donut-center').getBoundingClientRect(),hole=getComputedStyle(document.querySelector('#donut'),':before');return Math.abs(center.width-donut.width)<1 && Math.abs(center.height-donut.height)<1 && parseFloat(hole.width)>100 && parseFloat(hole.height)>100})()")
                val current = app.store.all().filter { it.state.name == "POSTED" && it.bill.occurredAt.let { at -> java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).let { d -> d.year==2097 && d.monthValue==7 } } }
                for ((type, direction) in listOf("expense" to Direction.EXPENSE, "income" to Direction.INCOME)) {
                    val list = current.filter { it.bill.direction == direction }
                    val total = list.sumOf { it.bill.amountCents ?: 0 }
                    web.run("document.querySelector('#stats-type [data-type=\"$type\"]').click()")
                    web.await("document.querySelector('#stats-total').textContent.includes(${quote(money(total))}) && document.querySelector('#stats-count').textContent===${quote("共 ${list.size} 笔")}")
                    for ((category, group) in list.groupBy { it.bill.category }) {
                        web.await("Array.from(document.querySelectorAll('#ranking .ranking-row')).some(row=>row.textContent.includes(${quote(category)})&&row.textContent.includes(${quote(money(group.sumOf { it.bill.amountCents ?: 0 }))}))")
                    }
                    web.await("document.querySelector('#ranking-type [data-type=\"$type\"]').classList.contains('active')")
                }
                web.run("document.querySelector('#ranking-type [data-type=\"expense\"]').click()")
                web.await("document.querySelector('#ranking-type [data-type=\"expense\"]').classList.contains('active') && document.querySelector('#ranking').textContent.includes('餐饮')")
                scenario.recreate()
                web.ready()
                web.await("document.querySelector('#stats').classList.contains('mobile-active') && document.querySelector('#stats-month').textContent.includes('2097年7月')")
            }
        } finally { created.forEach { app.store.delete(it.id) }; app.changed() }
    }

    @Test fun customCategoryPersistsAndManualSaveUsesIt() {
        val prefix = "V2-${UUID.randomUUID()}"
        // Native length limit is deliberately respected despite UUID isolation.
        val category = "分类" + UUID.randomUUID().toString().take(8)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web = WebUiProbe(scenario)
            web.ready()
            try {
                web.run("go('manual');document.querySelector('#manual-type [data-type=\"expense\"]').click();document.querySelector('#manage-category').click()")
                web.await("document.querySelector('#new-category-name') && document.querySelector('#save-category')")
                web.run("document.querySelector('#new-category-name').value=${quote(category)};document.querySelector('#save-category').click()")
                web.await("JSON.parse(LedgerNative.snapshot()).customCategories.expense.includes(${quote(category)})")
                web.run("closeSheet()")
                scenario.recreate()
                web.ready()
                web.await("document.querySelector('#category-grid [data-category=\"$category\"]')")
                web.run("document.querySelector('#category-grid [data-category=\"$category\"]').click();document.querySelector('#manual-amount').value='3.21';document.querySelector('#manual-note').value=${quote(prefix)};document.querySelector('#manual-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))")
                web.await("JSON.parse(LedgerNative.snapshot()).records.some(r=>r.note===${quote(prefix)}&&r.category===${quote(category)}&&r.amountCents===321)")
            } finally {
                web.run("LedgerNative.deleteCategory(${quote(category)},'expense')")
                cleanupRecords(prefix)
            }
        }
    }

    @Test fun assetsAddEditLiabilityHideAndDeletePersistAcrossRecreation() {
        val prefix = "V2-${UUID.randomUUID()}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web = WebUiProbe(scenario)
            web.ready()
            try {
                web.run("go('assets');document.querySelector('#add-account').click()")
                web.await("document.querySelector('#account-name') && document.querySelector('#save-account')")
                fillAccount(web, prefix, "88.88", "ASSET")
                web.await("JSON.parse(LedgerNative.snapshot()).accounts.some(a=>a.name===${quote(prefix)}&&a.amountCents===8888&&a.type==='ASSET')")
                val account = snapshotItems(web, "accounts").single { it.getString("name") == prefix }
                val id = account.getLong("id")
                web.await("document.querySelector('[data-account=\"$id\"]').textContent.includes('88.88')")
                web.run("document.querySelector('[data-account=\"$id\"]').click();document.querySelector('#account-calibrate').click()")
                fillAccount(web, prefix, "11.11", "LIABILITY")
                web.await("JSON.parse(LedgerNative.snapshot()).accounts.some(a=>a.id===$id&&a.amountCents===1111&&a.type==='LIABILITY')")
                web.run("go('assets')")
                val all = snapshotItems(web, "accounts")
                val expected = all.sumOf { if(it.getString("type")=="LIABILITY") -it.getLong("amountCents") else it.getLong("amountCents") }
                web.await("document.querySelector('#net-amount').textContent.includes(${quote(money(expected))})")
                web.run("document.querySelector('#hide-assets').click()")
                web.await("document.querySelector('#net-amount').textContent.includes('****') && !document.querySelector('[data-account=\"$id\"]').textContent.includes('11.11')")
                scenario.recreate()
                web.ready()
                web.await("document.querySelector('#assets').classList.contains('mobile-active') && document.querySelector('#net-amount').textContent.includes('****') && document.querySelector('[data-account=\"$id\"]')")
                web.run("document.querySelector('#hide-assets').click();document.querySelector('[data-account=\"$id\"]').click();document.querySelector('#account-calibrate').click()")
                web.await("document.querySelector('#account-name').value===${quote(prefix)} && document.querySelector('#account-amount').value==='11.11' && document.querySelector('#account-type').value==='LIABILITY'")
                web.run("closeSheet(false);document.querySelector('#account-overflow').click();document.querySelector('#account-delete-action').click()")
                web.await("!document.querySelector('[data-account=\"$id\"]') || document.querySelector('#account-delete-confirm')")
                web.run("document.querySelector('#account-delete-confirm')?.click()")
                web.await("!JSON.parse(LedgerNative.snapshot()).accounts.some(a=>a.id===$id) && !document.querySelector('[data-account=\"$id\"]')")
                scenario.recreate()
                web.ready()
                web.await("!document.querySelector('[data-account=\"$id\"]')")
            } finally {
                snapshotItems(web, "accounts").filter { it.getString("name").startsWith(prefix) }.forEach { a -> web.run("LedgerNative.deleteAccount(${a.getLong("id")})") }
            }
        }
    }

    private fun fillAccount(web: WebUiProbe, name: String, amount: String, type: String) {
        web.run("document.querySelector('#account-name').value=${quote(name)};document.querySelector('#account-description').value='合成测试账户';document.querySelector('#account-amount').value=${quote(amount)};document.querySelector('#account-kind').value='bank';document.querySelector('#account-kind').dispatchEvent(new Event('change'));document.querySelector('#account-type').value=${quote(type)};document.querySelector('#save-account').click()")
    }

    private fun snapshotItems(web: WebUiProbe, key: String): List<JSONObject> {
        val array = JSONObject(web.text("LedgerNative.snapshot()")).getJSONArray(key)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun seed(name: String, category: String, cents: Long, direction: Direction, time: Long): LedgerRecord = app.store.saveEdited(
        LedgerRecord(capture=Capture(Source.MANUAL,"manual","",time),bill=ParsedBill(cents,direction,name,category,time,null,true,"",true),state=net.ankio.ledger.core.RecordState.POSTED)
    )

    private fun cleanupRecords(prefix: String) {
        app.store.all().filter { it.note.startsWith(prefix) || it.bill.merchant.startsWith(prefix) }.forEach { app.store.delete(it.id) }
        app.changed()
    }
    private fun quote(value: String) = JSONObject.quote(value)
    private fun money(cents: Long) = String.format(java.util.Locale.US, "%,.2f", cents / 100.0)
}
