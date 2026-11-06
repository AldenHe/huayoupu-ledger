package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class LedgerIdentity056Test {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="identity056-${UUID.randomUUID()}.db"
    private val store=LedgerStore(context,name)
    private val now=System.currentTimeMillis()
    private fun capture(text:String,origin:String="com.taobao.taobao")=Capture(Source.OCR,origin,text,now)
    private fun bill(id:String?)=ParsedBill(2250,Direction.EXPENSE,"测试店铺","购物",now,id,true,"",true)
    @After fun cleanup() { store.all().forEach { store.delete(it.id) };store.close();context.deleteDatabase(name) }

    @Test fun cooccurringScopedIdsLinkTaobaoAndAlipayAndPersistAcrossReopen() {
        val first=(store.ingest(capture("订单编号 TBORDER0001"),bill("TBORDER0001"),screenshot=byteArrayOf(1)) as IngestResult.Saved).record
        val bridge=store.ingest(capture("淘宝订单号 TBORDER0001\n支付宝交易号 APTRADE0001","com.eg.android.AlipayGphone"),bill("APTRADE0001"),screenshot=byteArrayOf(2)) as IngestResult.Saved
        assertEquals(first.id,bridge.record.id)
        assertEquals(setOf(TransactionIdentity("app:com.taobao.taobao","TBORDER0001"),TransactionIdentity("alipay","APTRADE0001")),store.get(first.id)!!.identities.toSet())
        assertEquals(2,store.captureHistory(first.id).size)
        store.close()
        LedgerStore(context,name).use { reopened->
            assertEquals(IngestResult.DuplicateSkipped,reopened.ingest(capture("支付宝交易号 APTRADE0001","com.eg.android.AlipayGphone"),bill("APTRADE0001")))
            assertEquals(first.id,reopened.matchingIdentity(capture("支付宝交易号 APTRADE0001","com.eg.android.AlipayGphone"),bill("APTRADE0001"))!!.id);assertEquals(1,reopened.all().size)
        }
    }

    @Test fun conflictingScopedIdsNeverMergeEvenWithSharedAlias() {
        store.ingest(capture("淘宝订单号 TBORDER0001\n支付宝交易号 APTRADE0001","com.eg.android.AlipayGphone"),bill("APTRADE0001"))
        store.ingest(capture("淘宝订单号 TBORDER0002\n支付宝交易号 APTRADE0001","com.eg.android.AlipayGphone"),bill("APTRADE0001"))
        assertEquals(2,store.all().size)
        assertTrue(store.all().none { it.identities.any { id->id.value=="TBORDER0001" } && it.identities.any { id->id.value=="TBORDER0002" } })
        assertNull(store.matchingIdentity(capture("支付宝交易号 APTRADE0001","com.eg.android.AlipayGphone"),bill("APTRADE0001")))
        val ambiguous=store.ingest(capture("支付宝交易号 APTRADE0001","com.eg.android.AlipayGphone"),bill("APTRADE0001")) as IngestResult.Saved
        assertEquals(RecordState.REVIEW,ambiguous.record.state)
    }

    @Test fun separateEqualPriceOrdersStaySeparate() {
        store.ingest(capture("订单编号 TBORDER0001"),bill("TBORDER0001"))
        store.ingest(capture("订单编号 TBORDER0002"),bill("TBORDER0002"))
        assertEquals(2,store.all().size);assertTrue(store.all().all { it.state==RecordState.POSTED })
    }

    @Test fun amountAndMerchantWithoutIdentityAreNotEnoughToMerge() {
        store.ingest(capture("商品甲 已付款22.50"),bill(null))
        store.ingest(capture("商品乙 已付款22.50"),bill(null))
        assertEquals(2,store.all().size)
    }

    @Test fun archiveRoundtripRetainsMultipleIdentities() {
        store.ingest(capture("淘宝订单号 TBORDER0001\n支付宝交易号 APTRADE0001","com.eg.android.AlipayGphone"),bill("APTRADE0001"))
        val bytes=ByteArrayOutputStream();LedgerArchive.write(store,ChannelSettings(context),bytes)
        val otherName="identity-dest-${UUID.randomUUID()}.db"
        LedgerStore(context,otherName).use { other->
            LedgerArchive.read(ByteArrayInputStream(bytes.toByteArray()),context.cacheDir).use { backup->LedgerArchive.restore(other,backup) }
            assertEquals(store.all().single().identities,other.all().single().identities)
            assertEquals(IngestResult.DuplicateSkipped,other.ingest(capture("订单编号 TBORDER0001"),bill("TBORDER0001")))
            assertEquals(1,other.all().size);assertEquals(other.all().single().id,other.matchingIdentity(capture("订单编号 TBORDER0001"),bill("TBORDER0001"))!!.id)
        };context.deleteDatabase(otherName)
    }

    @Test fun versionSevenMigrationRecoversScopedAliasesFromStoredCaptureEvidence() {
        val original=(store.ingest(capture("淘宝订单号 TBORDER0001\n支付宝交易号 APTRADE0001","com.eg.android.AlipayGphone"),bill("APTRADE0001")) as IngestResult.Saved).record
        val db=store.writableDatabase
        // Reconstruct v7's actual column set, without the v8 alias and undo tables.
        db.execSQL("CREATE TABLE records_v7 AS SELECT id,uid,source,origin,raw_text,received_at,amount,direction,merchant,category,occurred_at,transaction_id,reliable,time_explicit,state,reason,duplicate_id,note,refund_provider,refund_original,refund_amount,refund_mode,refund_id FROM records")
        db.execSQL("DROP TABLE records");db.execSQL("ALTER TABLE records_v7 RENAME TO records")
        db.execSQL("DROP TABLE record_identities");db.execSQL("DROP TABLE record_deletions");db.version=7;store.close()
        LedgerStore(context,name).use { upgraded->
            assertEquals(14,upgraded.readableDatabase.version)
            assertEquals(original.uid,upgraded.get(original.id)!!.uid)
            assertEquals(2,upgraded.get(original.id)!!.identities.size)
            assertEquals(original.id,upgraded.matchingIdentity(capture("订单编号 TBORDER0001"),bill("TBORDER0001"))!!.id)
        }
    }

    @Test fun partialRefundUsesVerifiedPaymentAliasOfShoppingOrder() {
        val original=(store.ingest(capture("订单编号 TBORDER0001"),bill("TBORDER0001")) as IngestResult.Saved).record
        store.ingest(capture("淘宝订单号 TBORDER0001\n支付宝交易号 APTRADE0001","com.eg.android.AlipayGphone"),bill("APTRADE0001"))
        val refund=bill("APTRADE0001").copy(amountCents=1750,refund=RefundInfo("alipay",2250,500))
        val updated=store.ingest(capture("支付宝交易号 APTRADE0001\n原付22.50 累计退款5.00","com.eg.android.AlipayGphone"),refund) as IngestResult.Saved
        assertTrue(updated.updated);assertEquals(original.id,updated.record.id)
        assertEquals(1750L,store.all().single().bill.amountCents)
        assertEquals(2,store.all().single().identities.size)
        assertEquals(IngestResult.DuplicateSkipped,store.ingest(capture("支付宝交易号 APTRADE0001","com.eg.android.AlipayGphone"),bill("APTRADE0001")))
        assertEquals(1750L,store.all().single().bill.amountCents)
    }

    @Test fun boundedMediaMetadataIncludesOnlyRequestedBills() {
        val first=(store.ingest(capture("订单编号 TBORDER0001"),bill("TBORDER0001"),screenshot=byteArrayOf(1),merchantIcon=byteArrayOf(9)) as IngestResult.Saved).record
        val second=(store.ingest(capture("订单编号 TBORDER0002"),bill("TBORDER0002").copy(merchant="另一家店"),screenshot=byteArrayOf(2),merchantIcon=byteArrayOf(8)) as IngestResult.Saved).record
        store.ingest(capture("订单编号 TBORDER0001 详情"),bill("TBORDER0001"),screenshot=byteArrayOf(3))
        val requested=setOf(first.id);val owners=store.availableScreenshotOwners(requested)
        assertEquals(2,owners.size)
        assertEquals(requested,store.historyRevisions(owners,requested).keys)
        assertEquals(requested,store.screenshotIds(owners,requested))
        assertEquals(setOf(MerchantNames.key(first.bill.merchant)),store.merchantIconRevisions(requested).keys)
        assertFalse(store.screenshotIds(owners,requested).contains(second.id))
        assertTrue(store.availableScreenshotOwners(emptySet()).isEmpty())
        assertTrue(store.historyRevisions(emptySet(),emptySet()).isEmpty())
    }

    @Test fun explicitAlipayIdInWechatCaptureCannotInventWechatAlias() {
        val id="2026101900001234"
        for(label in listOf("支付宝交易号：","支付宝交易单号：","支付宝 交易号：")) {
            assertEquals(listOf(TransactionIdentity("alipay",id)),TransactionIdentities.from(capture(label+id,"com.tencent.mm"),bill(id)))
        }
        store.ingest(capture("微信交易单号：$id","com.tencent.mm"),bill(id))
        val foreign=store.ingest(capture("支付宝交易号：$id","com.tencent.mm"),bill(id)) as IngestResult.Saved
        assertEquals(2,store.all().size);assertEquals(listOf(TransactionIdentity("alipay",id)),foreign.record.identities)
    }

    @Test fun explicitWechatIdInAlipayCaptureCannotInventAlipayAlias() {
        val id="2026101900001234"
        for(label in listOf("微信交易号：","微信交易单号：","微信支付单号：","微信 交易单号：")) {
            assertEquals(listOf(TransactionIdentity("wechat",id)),TransactionIdentities.from(capture(label+id,"com.eg.android.AlipayGphone"),bill(id)))
        }
        store.ingest(capture("支付宝交易号：$id","com.eg.android.AlipayGphone"),bill(id))
        val foreign=store.ingest(capture("微信交易单号：$id","com.eg.android.AlipayGphone"),bill(id)) as IngestResult.Saved
        assertEquals(2,store.all().size);assertEquals(listOf(TransactionIdentity("wechat",id)),foreign.record.identities)
    }

    @Test fun merchantAndOriginalLabelsCannotBecomeProviderTransactionAliases() {
        val id="2026101900001234"
        for(origin in listOf("com.tencent.mm","com.eg.android.AlipayGphone")) {
            for(label in listOf("商户交易号：","商户交易单号：","商户 交易单号：","原交易号：","原交易单号：","原 交易单号："))
                assertTrue("$origin $label",TransactionIdentities.from(capture(label+id,origin),bill(id)).isEmpty())
            val scope=if(origin=="com.tencent.mm")"wechat" else "alipay"
            assertEquals(listOf(TransactionIdentity(scope,id)),TransactionIdentities.from(capture("交易单号：$id",origin),bill(id)))
        }
        store.ingest(capture("交易单号：$id","com.tencent.mm"),bill(id))
        val merchant=store.ingest(capture("商户交易单号：$id","com.tencent.mm"),bill(id)) as IngestResult.Saved
        assertEquals(2,store.all().size);assertTrue(merchant.record.identities.isEmpty())
    }
}
