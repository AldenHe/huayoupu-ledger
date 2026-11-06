package net.ankio.ledger.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.Money
import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.RefundInfo
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic response fixtures for persistence regression tests; not shipped. */
object SyntheticRecognition {

        private val localTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
        private val localTimeMinutes = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

        /** Parsing never converts a malformed amount/date/direction into a posted transaction. */
        fun parseRecognition(text: String, receivedAt: Long = System.currentTimeMillis(), imageWidth:Int=0,imageHeight:Int=0,livePaymentAt:Long?=null,expenseOnly:Boolean=false): RecognitionResult {
            val json = objectFromText(text)
            val isBill = json.opt("is_bill") as? Boolean ?: throw RecognitionException("模型结果缺少账单判断，请重试或更换模型")
            val raw = (json.opt("raw_text") as? String).orEmpty().take(16_384)
            net.ankio.ledger.core.TransactionRejection.reason(raw,manualCapture=!expenseOnly)?.let { return RecognitionResult(raw,null,skipReason=it) }
            // Refund pages remain actionable review results even if an older model says NO_BILL.
            val isRefund = refundIntent(raw) ||
                (json.has("refund") && !json.isNull("refund"))
            if (!isBill && !isRefund) return RecognitionResult(raw, null)
            if (raw.isBlank()) throw RecognitionException("未识别到可核对的账单文字，请重新截图")
            if (isRefund) return parseRefundRecognition(json, raw, receivedAt, imageWidth, imageHeight, expenseOnly)
            val reasons = ArrayList<String>()
            val amountInput = when (val value = json.opt("amount")) {
                is String -> value
                is Number -> value.toString()
                else -> null
            }
            val amount = amountInput?.let { input ->
                if (Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(input)) Money.parseCents(input) else null
            }?.takeIf { it > 0 }
            if (amount == null) reasons += "金额不明确"
            val extractedMerchant = field(json, "merchant", 80)
            val merchant = extractedMerchant.ifBlank { if(json.opt("direction")=="income")"收入记账" else "支出记账" }
            val senderPaid=senderPaymentSucceeded(raw)
            val direction = if(namedRecipientReceipt(raw,merchant) || senderPaid)Direction.EXPENSE
                else when (json.opt("direction")) { "expense" -> Direction.EXPENSE; "income" -> Direction.INCOME; null,JSONObject.NULL,"" -> if(!expenseOnly)Direction.EXPENSE else null; else -> null }
            if(expenseOnly && direction==Direction.INCOME)return RecognitionResult(raw,null)
            if (direction == null) reasons += "收支方向不明确"
            if (extractedMerchant.isNotEmpty() && !compact(raw).contains(compact(extractedMerchant))) reasons += "交易对象需核对原文"
            val category = field(json, "category", 32).ifBlank { "其他" }
            val timeInput = json.opt("occurred_at") as? String
            val time = parseTime(timeInput, receivedAt)
            // An immediate result screen often omits the date. Its native verified acquisition
            // clock is an observed time, never an invented explicit transaction timestamp.
            val observedAt=livePaymentAt?.takeIf { timeInput.isNullOrBlank() && direction==Direction.EXPENSE &&
                Regex("支付成功|付款成功|转账成功").containsMatchIn(compact(raw)) && it in 946_656_000_000L..receivedAt }
            if (expenseOnly && time == null && observedAt==null) reasons += "交易时间需确认"
            else if (time != null && !timeVisible(raw, timeInput!!)) reasons += "交易时间需核对原文"
            // Validated fields determine posting; a low model confidence flag alone does not block it.
            if (json.opt("reliable") !is Boolean) reasons += "AI 结果格式需确认"
            if (amount != null && !amountVisible(raw, amount)) reasons += "金额需核对原文"
            val normalizedRaw = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            val directionEvidence=Regex("付款|支出|支付成功|扣款|消费|付给|转账成功|收入|收款|收钱|到账|收到|入账|[-+]\\s*[¥￥]?\\s*[0-9]").containsMatchIn(normalizedRaw)
            if (direction != null && (expenseOnly || directionEvidence) && !directionVisible(normalizedRaw, direction,merchant,amount)) reasons += "收支方向需核对原文"
            val statusText=normalizedRaw.filterNot { it.isWhitespace() }
            if (Regex("待付款|待支付|未支付|未付款|支付中|付款中|交易关闭|支付失败|付款失败|交易失败|已取消|转账中|处理中").containsMatchIn(statusText) ||
                !senderPaid && Regex("等待.*收款|待.*确认收款|待收款|待对方收款|待对方确认").containsMatchIn(statusText))
                reasons += "交易状态或性质需确认"
            if (expenseOnly && !Regex("支付成功|付款成功|转账成功|交易成功|已收款|已收钱|收款成功|收款到账|已到账|已支付|已付款|扣款成功|成功扣款").containsMatchIn(statusText))
                reasons += "交易完成状态不明确"
            val transactionId = field(json, "transaction_id", 160).ifBlank { null }?.takeIf {
                val visible = compact(raw).contains(compact(it))
                if (!visible) reasons += "交易号需核对原文"
                visible
            }
            val reliable = reasons.isEmpty()
            return withIcon(json,raw,ParsedBill(amount, direction, merchant, category, time ?: observedAt ?: receivedAt,
                transactionId, reliable, if (reliable) if(observedAt!=null)"已识别即时支付成功页；页面未显示交易时间，使用本机捕获时间" else if(time==null)"远端 AI 识别；页面未显示有效付款时间，使用识别时间" else "远端 AI 识别，已校验必填信息" else reasons.joinToString("；"), time != null),imageWidth,imageHeight)
        }

        private fun withIcon(json:JSONObject,raw:String,bill:ParsedBill,imageWidth:Int,imageHeight:Int):RecognitionResult {
            val iconValue=json.opt("merchant_icon")
            val region=MerchantIconRegion.parse(iconValue,imageWidth,imageHeight)
            val iconStatus=when {
                !json.has("merchant_icon") -> "missing_field"
                iconValue==null || iconValue===JSONObject.NULL -> "not_visible"
                region!=null -> "located"
                iconValue !is JSONObject -> "invalid_metadata"
                iconValue.opt("kind") !in setOf("merchant_logo","counterparty_avatar") -> "wrong_kind"
                (iconValue.opt("confidence") as? Number)?.toDouble()?.let { it.isFinite() && it in 0.0..0.899999 } == true -> "confidence_low"
                else -> "invalid_box_or_confidence"
            }
            return RecognitionResult(raw,bill,iconRegion=region,iconStatus=iconStatus)
        }

        private fun parseRefundRecognition(json:JSONObject,raw:String,receivedAt:Long,imageWidth:Int,imageHeight:Int,
            expenseOnly:Boolean):RecognitionResult {
            val reasons=ArrayList<String>()
            val evidenceErrors=ArrayList<String>()
            val text=compact(raw)
            val metadata=json.optJSONObject("refund")
            if(metadata==null)evidenceErrors+="退款信息不完整，需重新识别原付款及退款详情"
            val rawProviders=buildSet {
                if(Regex("微信|WeChat",RegexOption.IGNORE_CASE).containsMatchIn(text))add("wechat")
                if(Regex("支付宝|Alipay",RegexOption.IGNORE_CASE).containsMatchIn(text))add("alipay")
            }
            val declaredProvider=metadata?.let { field(it,"provider",20) }.orEmpty()
            val provider=when {
                declaredProvider in setOf("wechat","alipay") &&
                    (rawProviders.isEmpty() || rawProviders==setOf(declaredProvider)) -> declaredProvider
                declaredProvider.isEmpty() && rawProviders.size==1 -> rawProviders.single()
                else -> null
            }
            if(provider==null)evidenceErrors+="退款支付平台不明确或与原文冲突"
            val original=metadata?.let { cents(it.opt("original_amount")) }?.takeIf { it>0 }
            val refunded=metadata?.let { cents(it.opt("refunded_amount")) }?.takeIf { it>0 }
            if(original==null)evidenceErrors+="退款原付款金额不明确"
            else if(!originalAmountVisible(raw,original))evidenceErrors+="退款原付款金额需核对原文"
            if(refunded==null)evidenceErrors+="成功退款金额不明确"
            else if(!labeledAmountVisible(raw,refunded,"累计(?:成功)?退款(?:金额)?|退款总额|已(?:成功)?退款|已退金额|已退|退款金额|本次退款|部分退款|全额退款"))
                evidenceErrors+="成功退款金额需核对原文"
            if(original!=null && refunded!=null && refunded>original)evidenceErrors+="退款金额不能超过原付款金额"
            val mode=metadata?.let { field(it,"mode",20) }.orEmpty()
            if(mode !in setOf("cumulative","single"))evidenceErrors+="退款金额口径不明确，需区分累计和单次"
            else if(mode=="cumulative" && !Regex("累计(?:成功)?退款|退款总额|已(?:成功)?退款|已退|全额退款|全部退款").containsMatchIn(text))
                evidenceErrors+="累计退款金额口径需核对原文"
            else if(mode=="single" && !Regex("本次退款|退款金额|退款单号|退款订单号|退款交易号").containsMatchIn(text))
                evidenceErrors+="单次退款金额口径需核对原文"
            val refundId=metadata?.let { field(it,"refund_id",160) }?.ifBlank { null }
            if(mode=="single" && refundId==null)evidenceErrors+="单次退款缺少独立退款单号"
            if(refundId!=null && !labeledIdentifierVisible(raw,refundId,"退款(?:订单|交易)?(?:单号|号)"))
                evidenceErrors+="退款单号需核对原文"
            val pending=Regex("退款处理中|退款申请中|申请(?:全额|部分)?退款|退款失败|待退款|退款中|退款审核|退款受理中|退回中|退回失败").containsMatchIn(text)
            if(pending)evidenceErrors+="退款尚未成功完成，保留原账单金额"
            else if(!Regex("退款成功|已(?:成功)?退款|已部分退款|已全额退款|已全部退款|已退回|退款完成|退款(?:已)?到账|成功退款").containsMatchIn(text))
                evidenceErrors+="退款完成状态不明确，保留原账单金额"
            if(expenseOnly)evidenceErrors+="退款请通过摇一摇或截图导入确认原账单"
            val merchant=field(json,"merchant",80)
            if(merchant.isBlank())reasons+="交易对象不明确"
            else if(!text.contains(compact(merchant)))reasons+="交易对象需核对原文"
            val timeInput=json.opt("occurred_at") as? String
            val time=parseTime(timeInput,receivedAt)
            val originalTimeValid=time!=null && originalTimeVisible(raw,timeInput!!)
            if(!originalTimeValid)reasons+="退款原支付时间需确认，不能使用退款时间替代"
            val transactionInput=field(json,"transaction_id",160).ifBlank { null }
            val transactionId=transactionInput?.takeIf {
                val valid=it!=refundId && labeledIdentifierVisible(raw,it,
                    "(?<!退款)(?:原)?(?:(?:微信|支付宝)?(?:支付|付款|交易|订单))(?:单号|号)")
                if(!valid)reasons+="退款原交易单号需核对，不能使用退款单号"
                valid
            }
            if(transactionInput==null)reasons+="退款原交易单号缺失，需确认原账单"
            if(json.opt("reliable") !is Boolean)reasons+="AI 结果格式需确认"
            val refund=if(evidenceErrors.isEmpty())RefundInfo(provider!!,original!!,refunded!!,mode,refundId) else null
            reasons.addAll(0,evidenceErrors)
            val reliable=reasons.isEmpty()
            return withIcon(json,raw,ParsedBill(refund?.let { it.originalAmountCents-it.refundedCents },Direction.EXPENSE,
                merchant,field(json,"category",32).ifBlank { "其他" },if(originalTimeValid)time!! else receivedAt,
                transactionId,reliable,if(reliable)"已校验成功退款，按原付款减退款金额更新原账单" else reasons.distinct().joinToString("；"),
                originalTimeValid,refund),imageWidth,imageHeight)
        }

        private fun cents(value:Any?):Long? {
            val input=when(value) { is String -> value; is Number -> value.toString(); else -> return null }
            // Models sometimes retain the currency unit printed on the page. Money accepts
            // only well-formed nonnegative decimal currency; raw labeled evidence is still checked.
            return Money.parseCents(input)
        }

        /** A payment page can offer refund help/buttons without having a refund event. */
        private fun refundIntent(raw:String):Boolean = Regex(
            "已(?:成功|部分|全部|全额)?退款|退款(?:成功|完成|失败|中|处理中|申请中|审核中|受理中|金额|总额|状态|单号|订单号|交易号|已到账|到账)|"+
                "累计(?:成功)?退款|本次退款|已退(?:回|金额)|已退[：:(¥￥]?[0-9]|"+
                "当前状态[：:]?(?:申请|待)?退款|待退款|退回(?:成功|失败|中)"
        ).containsMatchIn(compact(raw))

        private fun labeledAmountVisible(raw:String,cents:Long,label:String):Boolean {
            val text=compact(raw)
            return Regex("(?:$label)[：:=()¥￥元\\s-]{0,12}([0-9]+(?:[,.][0-9]+)*)").findAll(text)
                .any { Money.parseCents(it.groupValues[1])==cents }
        }

        private fun originalAmountVisible(raw:String,cents:Long):Boolean =
            labeledAmountVisible(raw,cents,"原(?:付款|支付|交易|订单)(?:金额)?|付款金额|支付金额|交易金额|订单金额|原金额|原付") ||
                Regex("(?m)(?:^|\\s)[-−]\\s*[¥￥]?\\s*([0-9]+(?:[,.][0-9]+)*)(?=\\s|元|$)")
                    .findAll(Normalizer.normalize(raw,Normalizer.Form.NFKC)).any { Money.parseCents(it.groupValues[1])==cents }

        private fun labeledIdentifierVisible(raw:String,id:String,label:String):Boolean =
            Regex("(?:$label)[：:=()]{0,3}${Regex.escape(compact(id))}(?![A-Za-z0-9_-])").containsMatchIn(compact(raw))

        private fun originalTimeVisible(raw:String,time:String):Boolean {
            val normalized=Normalizer.normalize(raw,Normalizer.Form.NFKC)
            return Regex("(?:原)?(?:支付|付款|交易|转账)时间[\\s:：]*([0-9]{4}[-/.年][0-9]{1,2}[-/.月][0-9]{1,2}日?[\\sT]*[0-9]{1,2}[:时][0-9]{1,2}(?::[0-9]{2}|分(?:[0-9]{1,2}秒)?)?)")
                .findAll(normalized).any {
                timeVisible(it.groupValues[1],time)
            }
        }

        private fun amountVisible(raw: String, cents: Long): Boolean = Regex("[0-9]+(?:[,.][0-9]+)*")
            .findAll(Normalizer.normalize(raw, Normalizer.Form.NFKC)).any { Money.parseCents(it.value) == cents }

        private fun compact(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).filterNot { it.isWhitespace() }

        private fun timeVisible(raw: String, time: String): Boolean {
            val parts = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})[ T]([0-9]{2}):([0-9]{2})").find(time)?.groupValues ?: return false
            val text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            val date = Regex("(?<![0-9])${parts[1]}[-/.年]0?${parts[2].toInt()}[-/.月]0?${parts[3].toInt()}日?(?![0-9])")
            val clock = Regex("(?<![0-9])0?${parts[4].toInt()}[:时]0?${parts[5].toInt()}(?:分)?(?![0-9])")
            return date.containsMatchIn(text) && clock.containsMatchIn(text)
        }

        private fun namedRecipientReceipt(raw:String,merchant:String):Boolean {
            val name=compact(merchant)
            if(name.isBlank() || name in setOf("我","你","本人","自己"))return false
            // WeChat sender detail: “<recipient>已收款”. A standalone “已收款” is income.
            return raw.lineSequence().map(::compact).any { line ->
                listOf("${name}已收款","${name}已收钱","对方已收款","对方已收钱").any { status ->
                    line.startsWith(status) || line.contains("当前状态$status") || line.contains("当前状态:$status")
                }
            }
        }
        private fun directionVisible(raw: String, direction: Direction,merchant:String,amount:Long?): Boolean {
            if(namedRecipientReceipt(raw,merchant) || senderPaymentSucceeded(raw))return direction==Direction.EXPENSE
            // Counterparty labels occur on both receipt types and do not establish direction.
            val text = raw.replace(Regex("收款方备注|收款方|收款账户|收款人|付款方|付款账户|付款人|支付方式"), "")
            // Rewards such as “积分 +14” are not signed transaction amounts. Only
            // compare the sign of the extracted bill total, excluding point units.
            val signed=Regex("(?m)(?:^|\\s)([-+])\\s*[¥￥]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)(?=\\s|元|积分|分|点|$)")
                .findAll(text).firstOrNull { match ->
                    amount!=null && Money.parseCents(match.groupValues[2])==amount &&
                        !Regex("积分\\s*[:：]?\\s*$").containsMatchIn(text.take(match.range.first)) &&
                        !Regex("^\\s*(积分|分|点)").containsMatchIn(text.substring(match.range.last+1))
                }?.groupValues?.get(1)
            if(signed!=null)return (if(signed=="-")Direction.EXPENSE else Direction.INCOME)==direction
            val expense = Regex("付款|支出|支付成功|扣款|消费|付给|已支付|已付款|支付金额|转账成功").containsMatchIn(text)
            val income = Regex("收入|收款|收钱|到账|收到|已收款|已收钱|入账").containsMatchIn(text)
            return when (direction) { Direction.EXPENSE -> expense && !income; Direction.INCOME -> income && !expense }
        }

        private fun senderPaymentSucceeded(raw:String):Boolean {
            val text=compact(raw)
            return Regex("支付成功|付款成功|转账成功").containsMatchIn(text) &&
                Regex("待.{1,40}确认收款|待对方收款|等待对方收款").containsMatchIn(text) &&
                !Regex("支付失败|付款失败|转账失败|已取消").containsMatchIn(text) && !refundIntent(raw)
        }

        private fun parseTime(value: String?, now: Long): Long? {
            if (value.isNullOrBlank() || value.length > 40) return null
            val instant = try {
                if (value.endsWith("Z") || Regex(".*[+-][0-9]{2}:[0-9]{2}$").matches(value))
                    OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
                else {
                    val normalized = value.replace('T', ' ')
                    LocalDateTime.parse(normalized, if (normalized.length == 16) localTimeMinutes else localTime)
                        .atZone(ZoneId.systemDefault()).toInstant()
                }
            } catch (_: Exception) { return null }
            return instant.toEpochMilli().takeIf { it >= 946_656_000_000L && it <= now + 86_400_000L }
        }

        private fun field(json: JSONObject, name: String, limit: Int): String =
            (json.opt(name) as? String).orEmpty().filter { it.code >= 32 && it.code != 127 }.trim().take(limit)

        private fun objectFromText(input: String): JSONObject {
            val value = input.trim().let {
                if (it.startsWith("```json\n") && it.endsWith("```")) it.removePrefix("```json\n").removeSuffix("```").trim()
                else if (it.startsWith("```\n") && it.endsWith("```")) it.removePrefix("```\n").removeSuffix("```").trim()
                else it
            }
            if (value.length > 65_536 || !value.startsWith('{') || !value.endsWith('}'))
                throw RecognitionException("模型返回格式不正确，请重试或更换视觉模型")
            return try { JSONObject(value) } catch (_: Exception) { throw RecognitionException("模型返回格式不正确，请重试或更换视觉模型") }
        }

}
