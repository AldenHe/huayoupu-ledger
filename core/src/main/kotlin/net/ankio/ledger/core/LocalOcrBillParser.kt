package net.ankio.ledger.core

import java.time.LocalDateTime
import java.time.ZoneId

/** Coordinates refer to the exact bitmap passed to OCR. Reading order from OCR is not trusted. */
data class LocalOcrLine(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

/** Conservative, entirely offline extraction of a single completed transaction. */
object LocalOcrBillParser {
    private val number = Regex("(?<![0-9.])([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(\\.[0-9]{1,2})?(?![0-9.])")
    private val paid = Regex("支付成功|付款成功|转账成功|交易成功|扣款成功|成功扣款|已支付|已付款")
    private val received = Regex("收款成功|收款到账|已收款|已收钱|已到账|收入")
    private val pending = Regex("请输入.*密码|输入支付密码|指纹验证|面容验证|待付款|待支付|未支付|未付款|支付中|付款中|交易关闭|支付失败|付款失败|交易失败|已取消|转账中")
    private val amountLabel = Regex("^(?:实付款|实付金额|实付(?!价)|实际支付|付款金额|支付金额|转账金额|交易金额|支出金额|收款金额|到账金额|收入金额|金额)[：:]?")
    private val orderPaidTotal = Regex("^(?:实付款|实付金额|实际支付)[：:]?")
    private val manualTotal = Regex("^(?:实付款|实付金额|实际支付|实付(?!价)|合计|成交价(?:[（(]已到达卖家账[户戶][）)])?)[：:]?")
    private const val amountWord = "金[额額]{1,2}"
    private val originalLabel = Regex("^(?:原(?:付款|支付|交易|订单)(?:$amountWord)?|原$amountWord|原付|付款$amountWord|支付$amountWord|交易$amountWord|订单$amountWord)[：:]?")
    private val refundLabel = Regex("^(?:累计(?:成功)?退款(?:$amountWord)?|退款总额|已(?:成功)?退款(?:$amountWord)?|已退$amountWord|已退)[：:]?")
    private val singleRefundLabel = Regex("^(?:本次退款(?:$amountWord)?|退款$amountWord)[：:]?")
    private val refundIntent = Regex("累计(?:成功)?退款|退款总额|已(?:成功|部分|全部|全额)?退款|已退金额|退款(?:成功|完成|处理中|申请中|审核中|失败|金额|单号)|本次退款")
    private val merchantLabel = Regex("^(?:商[户戶](?:全称|名称|金称|各称)|商家名称|店铺名称|收款方(?:全称|名称)?(?!备注|服务)|付款方(?:全称|名称)?(?!备注|服务)|交易对方|对方账户|对方账号)[：:]?")
    private val paymentTimeLabel = Regex("^(?:原(?:支付|付款|交易|转账)时间|支付时间|付款时间|交易时间|转账时间)[：:]?")
    private val idLabel = Regex("^(?:(?:原)?(?:微信|支付宝)?(?:支付|付款|交易|转账|订单))(?:编号|单号|号)[：:]?")
    private val datePattern = Regex("(20[0-9]{2})[年/.-]([0-9]{1,2})[月/.-]([0-9]{1,2})日?\\s*([0-9]{1,2})[:：]([0-9]{2})(?:[:：]([0-9]{2}))?")
    private val uiText = Regex("支付成功|付款成功|转账成功|交易成功|账单详情|交易详情|转账详情|全部账单|返回商家|完成|当前状态|账单服务|收款方服务|支付方式|付款方式|零钱|余额|银行卡|对订单有疑惑|电子凭证|退款|返回|服务由|¥|^[+-]?[0-9,.]+$")
    private val ignoredMoney = Regex("优惠|红包|立减|折扣|优惠券|积分|余额|手续费|服务费|奖励|最多|最高|原价|订单金额|退款|已退")
    private val storeStatistics = Regex("好评率\\s*[0-9]|客服.*[0-9]\\s*秒.*回复|[0-9]\\s*秒.*回复|(?:已售|粉丝)\\s*[0-9]|[0-9][0-9.,万亿wWkK+＋]*人(?:喜欢|收藏|关注)")
    private val merchantNavigation = Regex("^(?:附近门店|前往小程序|商家小程序|小程序|门店|服务|交易服务|喜欢|收藏)\\s*[>〉›→]?$" )

    private val zeroAmountGlyph = Regex("(?<=[¥￥])[OoＯ](?=[>〉\\s]*$)")
    private val priceDetails = Regex("价格明细[>〉]?")
    private val itemPriceLabel = Regex("^实付价[：:]?")
    private val fulfilledOrder = Regex("已发货|待收货|确认收货|已收货|交易完成|交易成功")
    private val payLaterPrefix = Regex("^先用后付\\s*")
    private val shippingHelpGlyph = Regex("(?<=[0-9]\\.[0-9]{2})\\s+[Oo]\\s*(?=\\(免运费\\)\\s*$)")
    private val unpaidOrder = Regex("先用后付|确认收货后再付款|尚未扣款|未扣款")
    private val incomeType = Regex("^(?:交易类型[：:]?)?收入$")
    private val nonPaymentTimeLabel = Regex("^(?:创建|下单|发货|收货|退款)时间[：:]?")
    private val refundIdLabel = Regex("^退款(?:订单|交易)?(?:单号|号)[：:]?")
    private val refundIdentifier = Regex("[A-Za-z0-9_-]{10,160}")
    private val wechatProvider = Regex("微信|财付通|WeChat", RegexOption.IGNORE_CASE)
    private val alipayProvider = Regex("支付宝|Alipay", RegexOption.IGNORE_CASE)
    private val refundSuccess = Regex("退款成功|已(?:成功|部分|全额|全部)?退款|已退金额|退款完成|退款(?:已)?到账|成功退款")
    private val refundInProgress = Regex("退款处理中|退款申请中|申请退款|退款失败|待退款|退款中|退款审核|退款受理中")
    private val transactionIdentifier = Regex("(?<![A-Za-z0-9])([A-Za-z0-9_-]{10,160})(?![A-Za-z0-9])")
    private val orderInfoLabel = Regex("^订单信息(?:共[0-9]+[项页])?[>〉⌄∨vV√]?$" )
    private val amountDecorations = Regex("[（(]免运费[）)]|价格明细[>〉]?|[?？]")
    private val trailingFractionZeros = Regex("(\\.[0-9]{2})0+(?![0-9])")
    private val currencyDecoration = Regex("^[+−-]|[¥元]")
    private val shopEntry = Regex("^进店(?:逛逛|看看)?[>〉]?$")
    private val shoppingPage = Regex("订单编号|订单信息|实付款|实付价|先用后付")
    private val storeNamePrefix = Regex("^(.{2,60}?(?:旗舰店|专卖店|专营店|官方店))")
    private val scanPaymentRecipient = Regex("^(?:扫二维码付款|二维码付款|扫码付款)[-—:：]?给?(.+)$")
    private val scanPaymentPrefix = Regex("^(?:扫二维码付款|二维码付款|扫码付款)[-—:：]?")
    private val disclosureSuffix = Regex("[>〉]$")
    private val numericMerchant = Regex("[0-9]{8,}|[:：][0-9]{2}")
    private val genericMerchantLabel = Regex("^(?:商品|收单机构|付款方备注|收款方备注|转账单号|商户单号|商戶单号|对方|去看看|推荐|广告)$")
    private val digitWhitespace = Regex("(?<=[0-9])\\s+(?=[0-9])")
    private val productLabel = Regex("^(?:商品|商品说明|商品名称|交易说明)[：:]?")
    private val transportCategory = Regex("ETC|高速|停车|地铁|公交|公共交通|出租|打车|滴滴|加油", RegexOption.IGNORE_CASE)
    private val diningCategory = Regex("餐厅|餐饮|食堂|饭|咖啡|奶茶|外卖|美食|早餐|火锅")
    private val householdCategory = Regex("超市|便利店|百货|日用")
    private val shoppingCategory = Regex("淘宝|京东|拼多多|天猫|购物")
    private val medicalCategory = Regex("医院|药房|药店|医疗")

    fun parse(
        lines: List<LocalOcrLine>,
        width: Int,
        height: Int,
        capturedAt: Long,
        livePaymentAt: Long? = null,
        expenseOnly: Boolean = false,
        origin: String = "",
    ): ParsedBill? {
        val clean = lines.map { it.copy(text = normalize(it.text).trim().trim('|', '丨').trim()) }
            .filter { it.text.isNotBlank() }
        if (clean.isEmpty()) return null
        val text = clean.joinToString("\n") { it.text }
        if(TransactionRejection.reason(text,manualCapture=!expenseOnly && livePaymentAt==null)!=null)return null
        val compact = text.filterNot(Char::isWhitespace)
        val isRefund = refundIntent.containsMatchIn(compact)
        val paidTimes = labeledValues(clean, paymentTimeLabel)
            .mapNotNull { parseDate(it,capturedAt) }.distinct()
        val header = headerAmount(clean, width, height)
        val hasOrderTotal=clean.any { orderPaidTotal.containsMatchIn(it.text.replace(" ","")) }
        val total=if(hasOrderTotal)uniqueLabeledAmount(clean.map { it.copy(text=it.text.replace(zeroAmountGlyph,"0")) },orderPaidTotal) else null
        val itemPrice=uniqueLabeledAmount(clean.map { it.copy(text=it.text.replace(priceDetails,"")) },itemPriceLabel)
        val payLaterPrice=if(!expenseOnly && origin.substringAfter(':')==PaymentApps.TAOBAO && total==0L &&
            fulfilledOrder.containsMatchIn(compact))itemPrice?.takeIf { it>0 } else null
        // Keep the whitespace evidence: only an isolated glyph after two decimal
        // digits, immediately before free-shipping text, can be a help icon.
        // Never turn letters inside the numeric token into guessed digits.
        val manualLines=clean.map { it.copy(text=it.text.replace(payLaterPrefix,"")
            .replace(shippingHelpGlyph,"")) }
        val hasManualTotal=manualLines.any { manualTotal.containsMatchIn(it.text.replace(" ","")) }
        val manualAmount=payLaterPrice ?: if(hasManualTotal)uniqueLabeledAmount(manualLines,manualTotal) else agreedAmount(clean,amountLabel,header?.second)
        val manualExpense = !expenseOnly && !isRefund && manualAmount?.let { it>0 }==true &&
            (hasManualTotal || payLaterPrice!=null || !unpaidOrder.containsMatchIn(compact))
        // Some scan-payment result screens expose only the merchant, amount and “完成”.
        // livePaymentAt comes from the native success-frame gate, never OCR/model fields.
        val verifiedLiveExpense = expenseOnly && origin in setOf(PaymentApps.WECHAT,PaymentApps.ALIPAY) &&
            livePaymentAt?.let { it in 946_656_000_000L..capturedAt }==true && header?.second?.let { it>0 }==true
        // A success banner must never override a password/pending payment on the same image.
        if (!isRefund && pending.containsMatchIn(compact)) return null
        if (!isRefund && !manualExpense && !verifiedLiveExpense && !paid.containsMatchIn(compact) && !received.containsMatchIn(compact)) return null
        val direction = when {
            isRefund -> Direction.EXPENSE
            clean.any { incomeType.matches(it.text.replace(" ", "")) } -> Direction.INCOME
            header?.first?.text?.trim()?.startsWith('+') == true && received.containsMatchIn(compact) -> Direction.INCOME
            paid.containsMatchIn(compact) -> Direction.EXPENSE
            received.containsMatchIn(compact) -> Direction.INCOME
            manualExpense || verifiedLiveExpense -> Direction.EXPENSE
            else -> return null
        }
        if (expenseOnly && (direction == Direction.INCOME || isRefund)) return null
        val reasons = mutableListOf<String>()
        val merchant = merchant(clean, header, width, height).ifBlank {
            when(origin.substringAfter(':')) {
                PaymentApps.TAOBAO -> "淘宝支出"
                PaymentApps.WECHAT -> if(direction==Direction.INCOME)"微信收入" else "微信支出"
                PaymentApps.ALIPAY -> if(direction==Direction.INCOME)"支付宝收入" else "支付宝支出"
                else -> if(direction==Direction.INCOME)"收入记账" else "支出记账"
            }
        }
        val otherTimes=labeledValues(clean,nonPaymentTimeLabel)
            .mapNotNull { parseDate(it,capturedAt) }.toSet()
        val visibleTimes = clean.mapNotNull { parseDate(it.text, capturedAt) }.filterNot { it in otherTimes }.distinct()
        val explicitTime = if (paidTimes.size==1) paidTimes.single() else if (paidTimes.size>1) null
            else if (!isRefund && visibleTimes.size == 1) visibleTimes.single() else null
        val observedAt = livePaymentAt?.takeIf { !isRefund && direction == Direction.EXPENSE &&
            (paid.containsMatchIn(compact) || verifiedLiveExpense) && it in 946_656_000_000L..capturedAt }
        if (isRefund && explicitTime == null && observedAt == null) reasons += "退款原付款时间不明确"
        val transactionId = transactionId(clean)
        val amount: Long?
        var refund: RefundInfo? = null
        if (isRefund) {
            val original = agreedAmount(clean, originalLabel, header?.second)
            val cumulative = uniqueLabeledAmount(clean, refundLabel)
            val single = uniqueLabeledAmount(clean, singleRefundLabel)
            val refundId = labeledValues(clean, refundIdLabel)
                .mapNotNull { refundIdentifier.find(it)?.value }.distinct().singleOrNull()
            val refundProvider = when {
                provider(origin) in setOf("wechat", "alipay") -> provider(origin)
                wechatProvider.containsMatchIn(compact) -> "wechat"
                alipayProvider.containsMatchIn(compact) -> "alipay"
                else -> null
            }
            val successful = refundSuccess.containsMatchIn(compact)
            val refundPending = refundInProgress.containsMatchIn(compact)
            val refundAmount = cumulative ?: single
            val mode = if (cumulative != null) "cumulative" else "single"
            if (!successful || refundPending) reasons += "退款尚未成功完成"
            if (original == null || original <= 0) reasons += "原付款金额不明确"
            if (refundAmount == null || refundAmount <= 0) reasons += "已成功退款金额不明确"
            if (refundAmount != null && original != null && refundAmount > original) reasons += "退款金额超过原付款金额"
            if (mode == "single" && refundId == null) reasons += "单次退款缺少退款单号"
            if (refundProvider == null) reasons += "退款支付平台不明确"
            if (transactionId == null) reasons += "退款原交易单号缺失"
            if (successful && !refundPending && original != null && original > 0 && refundAmount != null && refundAmount in 1..original &&
                refundProvider != null && (mode == "cumulative" || refundId != null)) {
                refund = RefundInfo(refundProvider, original, refundAmount, mode, refundId)
            }
            amount = refund?.let { it.originalAmountCents - it.refundedCents }
        } else {
            // Shopping pages contain item prices and order totals at different sizes. Only
            // the explicitly paid order total represents this completed expense.
            amount = if(!expenseOnly)manualAmount else agreedAmount(clean, amountLabel, header?.second)
            if (amount == null || amount <= 0) reasons += "付款金额不明确"
        }
        return ParsedBill(amount, direction, merchant, category(clean, merchant), explicitTime ?: observedAt ?: capturedAt,
            transactionId, reasons.isEmpty(), if (reasons.isEmpty()) {
                if (explicitTime == null && observedAt != null) "已识别即时支付成功页；页面未显示交易时间，使用本机捕获时间"
                else if(explicitTime==null) "已识别；页面未显示有效付款时间，使用识别时间"
                else "已识别，已校验交易信息"
            } else reasons.distinct().joinToString("；"),
            explicitTime != null, refund)
    }

    private fun uniqueLabeledAmount(lines: List<LocalOcrLine>, label: Regex): Long? =
        labeledValues(lines, label).mapNotNull(::readMoney).distinct().singleOrNull()

    /** Can enrich an earlier record's identity without changing its amount, date or category. */
    fun transactionId(lines:List<LocalOcrLine>):String? {
        val fields=lines.map { it.copy(text=normalize(it.text).replace("订单編号","订单编号").trim()) }
        return (labeledValues(fields,idLabel)+orderInfoValues(fields)).mapNotNull { value ->
            transactionIdentifier.find(value)?.groupValues?.get(1)
        }.distinct().singleOrNull()
    }

    /** Shopping order summaries put the number beside “订单信息 共5项”, not a conventional field. */
    private fun orderInfoValues(lines:List<LocalOcrLine>):List<String> = lines.flatMapIndexed { index,line ->
        if(!orderInfoLabel.matches(line.text.replace(" ","")))return@flatMapIndexed emptyList()
        if(line.bottom>line.top) {
            val h=line.bottom-line.top
            lines.filter { it!==line && it.left>=line.right-h &&
                kotlin.math.abs((it.top+it.bottom-line.top-line.bottom)/2.0)<=maxOf(h,it.bottom-it.top)*.65 }.map { it.text }
        } else lines.drop(index+1).take(2).map { it.text }
    }

    /** A differently valued prominent amount is conflicting evidence, never a reason to guess. */
    private fun agreedAmount(lines: List<LocalOcrLine>, label: Regex, header: Long?): Long? {
        val labeled = labeledValues(lines, label).mapNotNull(::readMoney).distinct()
        if (labeled.size > 1) return null
        val value = labeled.singleOrNull()
        return if (value != null && header != null && value != header) null else value ?: header
    }

    /** Labels and values are frequently emitted as separate OCR blocks in arbitrary order. */
    private fun labeledValues(lines: List<LocalOcrLine>, label: Regex): List<String> = lines.flatMapIndexed { index, line ->
        val compactText = line.text.replace(" ", "")
        val match = label.find(compactText) ?: return@flatMapIndexed emptyList()
        val remainder = compactText.substring(match.range.last + 1).trim(' ', ':', '：')
        if (remainder.isNotEmpty()) return@flatMapIndexed listOf(remainder)
        val geometric = line.bottom > line.top && line.right > line.left
        if (geometric) {
            val cy = (line.top + line.bottom) / 2.0
            val h = line.bottom - line.top
            val sameRow = lines.filter { it !== line && it.left >= line.right - h &&
                kotlin.math.abs((it.top + it.bottom) / 2.0 - cy) <= maxOf(h, it.bottom - it.top) * .65 }
                .sortedBy { it.left }.joinToString(" ") { it.text }
            if (sameRow.isNotBlank()) return@flatMapIndexed listOf(sameRow)
            // A stacked field is accepted only when it is the closest short gap below the label.
            val below = lines.filter { it.top >= line.bottom && it.top - line.bottom <= h * 1.8 &&
                kotlin.math.abs(it.left - line.left) <= h * 2 }.minByOrNull { it.top }
            if (below != null && !fieldLabel(below.text)) return@flatMapIndexed listOf(below.text)
        } else {
            lines.getOrNull(index + 1)?.takeIf { !fieldLabel(it.text) }?.let { return@flatMapIndexed listOf(it.text) }
        }
        emptyList()
    }

    private fun fieldLabel(text: String): Boolean = listOf(amountLabel, originalLabel, refundLabel, merchantLabel, paymentTimeLabel, idLabel)
        .any { it.matches(text.replace(" ", "")) }

    private fun readMoney(value: String): Long? {
        val input = normalize(value).replace('−', '-').trim().replace("人民币", "").replace("RMB", "").replace("CNY", "").trim()
            .replace(amountDecorations,"")
            .replace(trailingFractionZeros,"$1")
            .trim().trimEnd('>', '〉', '⌃', '∧', 'へ', '~', '^', '⌄', '∨', 'v', 'V').trim()
        val matches = number.findAll(input).toList()
        if (matches.size != 1) return null
        val m = matches.single()
        if (input.substring(0, m.range.first).any { !it.isWhitespace() && it !in "¥+-:：(" } ||
            input.substring(m.range.last + 1).any { !it.isWhitespace() && it !in "元)" }) return null
        return Money.parseCents(m.value)
    }

    private fun headerAmount(lines: List<LocalOcrLine>, width: Int, height: Int): Pair<LocalOcrLine, Long>? {
        val candidates = lines.mapIndexedNotNull { index, line ->
            if (ignoredMoney.containsMatchIn(line.text)) return@mapIndexedNotNull null
            if (line.bottom > line.top) {
                val cy = (line.top + line.bottom) / 2.0
                val excludedLabel = lines.any { other -> other !== line && other.right <= line.left + 12 &&
                    ignoredMoney.containsMatchIn(other.text) &&
                    kotlin.math.abs((other.top + other.bottom) / 2.0 - cy) <= maxOf(line.bottom - line.top, other.bottom - other.top) * .65 }
                if (excludedLabel) return@mapIndexedNotNull null
            } else if (index > 0 && ignoredMoney.containsMatchIn(lines[index - 1].text) && readMoney(lines[index - 1].text) == null) {
                return@mapIndexedNotNull null
            }
            val cents = readMoney(line.text)?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
            val decorated = currencyDecoration.containsMatchIn(line.text)
            val size = line.bottom - line.top
            val inHeader = height > 0 && line.top in (height * .08).toInt()..(height * .6).toInt()
            val centered = width <= 0 || (line.left + line.right) / 2.0 in width * .15..width * .85
            if (!decorated && !(inHeader && centered && size >= width * .04 && line.text.contains('.'))) return@mapIndexedNotNull null
            Triple(line, cents, size)
        }
        if (candidates.isEmpty()) return null
        val maxHeight = candidates.maxOf { it.third }
        val prominent = if (maxHeight > 0) candidates.filter { it.third >= maxHeight * .8 } else candidates
        if (prominent.map { it.second }.distinct().size != 1) return null
        return prominent.maxBy { it.third }.let { it.first to it.second }
    }

    private fun merchant(lines: List<LocalOcrLine>, header: Pair<LocalOcrLine, Long>?, width: Int, height: Int): String {
        // A store row in an order is not centered above the amount like a wallet bill.
        lines.firstOrNull { shopEntry.matches(it.text.replace(" ","")) }?.let { entry ->
            lines.filter { it.right<=entry.left && it.bottom>it.top && plausibleMerchant(it.text) &&
                kotlin.math.abs((it.top+it.bottom)/2.0-(entry.top+entry.bottom)/2.0)<=maxOf(it.bottom-it.top,entry.bottom-entry.top)*.7 }
                .maxByOrNull { it.right }?.let { return it.text.take(80) }
        }
        // Order receipts may merge the logo lettering, store name and badges into
        // one OCR line. A unique named store above the total is stronger evidence
        // than nearby action labels such as Share product.
        if(height>0 && lines.any { shoppingPage.containsMatchIn(it.text) }) {
            val stores=lines.filter { it.top in (height*.08).toInt()..(height*.50).toInt() }
                .mapNotNull { line -> storeNamePrefix.find(line.text)?.groupValues?.get(1)?.trim() }
                .filter(::plausibleMerchant).distinct()
            stores.singleOrNull()?.let { return it }
        }
        goodsSectionMerchant(lines)?.let { return it }
        lines.firstOrNull { scanPaymentRecipient.matches(it.text) }?.let {
            return it.text.replace(scanPaymentPrefix, "").removePrefix("给").take(80)
        }
        val headerLine = header?.first
        if (width > 0 && headerLine != null && headerLine.bottom > headerLine.top) {
            // Merchant cards repeat the trading name immediately below the amount.
            // The upper card also contains likes, navigation and customer avatars.
            val below=lines.filter { line ->
                line.top>=headerLine.bottom && line.top-headerLine.bottom<width*.14 && line.bottom>line.top &&
                    kotlin.math.abs((line.left+line.right-headerLine.left-headerLine.right)/2.0)<width*.16 && plausibleMerchant(line.text)
            }.groupBy { it.text }.values.singleOrNull()?.minByOrNull { it.top }
            // Themed payment cards can sit near the bottom of the screenshot.
            // Bind their title to the amount, not a fixed band of the whole page;
            // distant decorative headings cannot supply the merchant by themselves.
            val above = lines.filter { line ->
                line.bottom > line.top && line.bottom <= headerLine.top && headerLine.top-line.bottom < width*.30 &&
                    kotlin.math.abs((line.left+line.right-headerLine.left-headerLine.right)/2.0)<width*.20 &&
                    (line.left+line.right)/2.0 in width*.18..width*.82 && plausibleMerchant(line.text)
            }.maxByOrNull { it.bottom }
            // Prefer the title touching the amount. A payment-method row below it
            // must not displace a closer title above; wallet cards with their name
            // directly below the amount keep that name (including ties).
            if (below!=null && (above==null || below.top-headerLine.bottom<=headerLine.top-above.bottom))
                return below.text.take(80)
            above?.let { return it.text.take(80) }
        }
        // Wallets show the trading name above the amount; the legal full name below
        // can be a different person or company and must not break the icon anchor.
        labeledValues(lines, merchantLabel).firstOrNull(::plausibleMerchant)?.let { return it.take(80) }
        // In text-only documents accept a clear named transaction header, never an arbitrary label.
        return lines.firstOrNull { it.text.startsWith("转账给") && it.text.length > 3 }?.text?.removePrefix("转账给")?.take(80).orEmpty()
    }

    /** A goods-cost section may be far below the wallet-style amount header. Only
     * accept a unique navigable store row beside its favorite action, before the total. */
    private fun goodsSectionMerchant(lines: List<LocalOcrLine>): String? {
        val stores = lines.filter { it.text == "商品费用" && it.bottom > it.top }.flatMap { section ->
            val totalTop = lines.filter { it.top > section.bottom && manualTotal.containsMatchIn(it.text) }
                .minOfOrNull { it.top } ?: return@flatMap emptyList()
            lines.filter { row ->
                row.top >= section.bottom && row.bottom < totalTop && row.bottom > row.top &&
                    kotlin.math.abs(row.left - section.left) <= section.bottom - section.top &&
                    disclosureSuffix.containsMatchIn(row.text) &&
                    plausibleMerchant(row.text.trimEnd('>', '〉').trim()) &&
                    lines.any { action ->
                        action.text.endsWith("收藏") && action.text.length <= 4 && action.left >= row.right &&
                            action.bottom > action.top &&
                            kotlin.math.abs((row.top + row.bottom - action.top - action.bottom) / 2.0) <=
                            maxOf(row.bottom - row.top, action.bottom - action.top) * .7
                    }
            }.map { it.text.trimEnd('>', '〉').trim() }
        }.distinct()
        return stores.singleOrNull()
    }

    fun isMerchantInterfaceText(value:String):Boolean {
        val compact=value.filterNot(Char::isWhitespace)
        return compact in setOf("全称","名称") || storeStatistics.containsMatchIn(compact) || merchantNavigation.matches(compact)
    }

    private fun plausibleMerchant(value: String): Boolean = value.length in 2..80 &&
        !uiText.containsMatchIn(value) && !isMerchantInterfaceText(value) && !fieldLabel(value) && !datePattern.containsMatchIn(value) &&
        !numericMerchant.containsMatchIn(value) &&
        !genericMerchantLabel.matches(value)

    private fun parseDate(value: String, capturedAt: Long): Long? {
        // OCR may split one number into two tokens (for example month "1 0"). This repairs
        // spacing only; numeric/date validity and the future-time guard still apply.
        val m = datePattern.find(value.replace(digitWhitespace, "")) ?: return null
        return runCatching {
            val g = m.groupValues
            LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                .takeIf { it in 946_656_000_000L..(capturedAt + 5 * 60_000L) }
        }.getOrNull()
    }

    private fun category(lines: List<LocalOcrLine>, merchant: String): String {
        val content = merchant + " " + labeledValues(lines, productLabel).joinToString(" ")
        return when {
            transportCategory.containsMatchIn(content) -> "交通出行"
            diningCategory.containsMatchIn(content) -> "餐饮美食"
            householdCategory.containsMatchIn(content) -> "日用购物"
            shoppingCategory.containsMatchIn(content) -> "购物消费"
            medicalCategory.containsMatchIn(content) -> "医疗健康"
            else -> "其他"
        }
    }
}
