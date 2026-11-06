package net.ankio.ledger.data

import android.graphics.BitmapFactory
import net.ankio.ledger.capture.ChannelSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Portable full backup. Large image bytes are streamed one at a time, never Base64 in JSON. */
object LedgerArchive {
    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 128 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_ZIP_BYTES = 544L * 1024 * 1024
    const val MAX_ATTACHMENTS = 100_000
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val attachmentPattern = Regex("images/([0-9a-f]{64})\\.bin")
    private val originalPattern = Regex("originals/([0-9a-f]{64})\\.bin")

    data class Summary(val records: Int, val attachments: Int, val attachmentBytes: Long)

    /** Call close after restore or cancellation. No database is changed during preparation. */
    class PreparedBackup internal constructor(
        internal val backup: ValidatedBackup,
        internal val directory: File?,
        internal val files: Map<String, File> = emptyMap(),
        internal val historyAssets: Map<String, Map<Int, String>> = emptyMap(),
        internal val iconAssets: Map<String, String> = emptyMap(),
        internal val originalFiles: Map<String, File> = emptyMap(),
        val legacy: Boolean = false,
    ) : Closeable {
        val recordCount get() = backup.records.size
        private var closed = false
        internal fun ensureOpen() = check(!closed) { "备份读取已结束，请重新选择文件" }
        override fun close() {
            if (closed) return
            closed = true
            // This directory contains only flat files created here, never archive-supplied paths.
            directory?.listFiles()?.forEach { it.delete() }
            directory?.delete()
        }
    }

    /** The caller owns output. A complete manifest is written only after all images succeed. */
    fun write(store: LedgerStore, channels: ChannelSettings, output: OutputStream): Summary = synchronized(store) {
        val records = store.all()
        val ledger = JSONObject(LedgerBackup.encode(records, channels, store.accounts(), store.customCategories(),store.defaultAccount()?.uid,store.channelDefaultAccounts(),AccountTransfers(store).list()))
        val reimbursements = ReimbursementStore(store.reimbursementContext, store)
        val reimbursement = reimbursements.backupMetadata()
        ledger.put("reimbursement", reimbursement)
        val histories = JSONObject()
        val icons = JSONObject()
        val stored = mutableSetOf<String>()
        var bytes = 0L
        ZipOutputStream(object : FilterOutputStream(output) {
            override fun close() { flush() }
        }).use { zip ->
            fun attachment(image: ByteArray): String {
                require(image.isNotEmpty() && image.size <= MAX_IMAGE_BYTES) { "账单图片大小无效" }
                val hash = hash(image)
                if (stored.add(hash)) {
                    require(stored.size <= MAX_ATTACHMENTS) { "备份图片数量过多" }
                    bytes += image.size
                    require(bytes <= MAX_TOTAL_BYTES) { "备份图片超过 512 MB" }
                    zip.putNextEntry(ZipEntry("images/$hash.bin"))
                    zip.write(image)
                    zip.closeEntry()
                }
                return hash
            }
            val grouped = store.captureHistories()
            for (record in records) {
                var history = grouped[record.id].orEmpty()
                // Older imports may have a primary screenshot without an evidence-history row.
                val legacyImage = if (history.isEmpty()) store.screenshot(record.id) else null
                if (legacyImage != null) {
                    val refund = record.bill.refund
                    history = listOf(RecordCaptureHistory(1, record.capture.receivedAt,
                        if (refund == null) "payment" else if (record.bill.amountCents == 0L) "full_refund" else "partial_refund",
                        record.bill.amountCents, refund?.refundedCents ?: 0L, record.capture.text, true,
                        "cumulative"))
                }
                val entries = JSONArray()
                for (entry in history) {
                    val image = if (legacyImage != null) legacyImage else store.historyScreenshot(record.id, entry.sequence)
                    entries.put(LedgerBackup.historyJson(entry).put("asset", image?.let(::attachment) ?: JSONObject.NULL))
                }
                if (entries.length() > 0) histories.put(record.uid, entries)
                store.merchantIcon(record.id)?.let { icons.put(record.uid, attachment(it)) }
            }
            ledger.put("histories", histories)
            val originals=reimbursement.getJSONArray("attachments")
            val originalHashes=mutableSetOf<String>()
            for(i in 0 until originals.length()) {
                val a=originals.getJSONObject(i);val hash=a.getString("sha256")
                require(originalHashes.add(hash) && stored.size+originalHashes.size<=MAX_ATTACHMENTS) { "报销原件重复或数量过多" }
                val file=reimbursements.attachmentFile(a.getString("uid"))
                bytes+=file.length();require(bytes<=MAX_TOTAL_BYTES) { "备份内容超过 512 MB" }
                zip.putNextEntry(ZipEntry("originals/$hash.bin"))
                file.inputStream().use { it.copyTo(zip,8192) };zip.closeEntry()
            }
            val manifest = JSONObject().put("format", "simple-ledger-archive").put("version", 1)
                .put("ledger", ledger).put("merchantIcons", icons).toString().toByteArray(Charsets.UTF_8)
            require(manifest.size <= MAX_MANIFEST_BYTES && bytes + manifest.size <= MAX_TOTAL_BYTES) { "备份内容超过容量限制" }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
        }
        Summary(records.size, stored.size + reimbursement.getJSONArray("attachments").length(), bytes)
    }

    /** ZIP is staged and checked before restore; old JSON backups keep their original validation. */
    fun read(input: InputStream, cacheDir: File): PreparedBackup {
        val stream = PushbackInputStream(BufferedInputStream(input), 4)
        val prefix = ByteArray(4)
        var count = 0
        while (count < prefix.size) {
            val n = stream.read(prefix, count, prefix.size - count)
            if (n == -1) break
            count += n
        }
        stream.unread(prefix, 0, count)
        if (count < 4 || !prefix.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
            return stream.use { PreparedBackup(LedgerBackup.decode(it), null, legacy = true) }
        }
        require(cacheDir.isDirectory || cacheDir.mkdirs()) { "无法创建备份缓存" }
        val directory = File.createTempFile("ledger-archive-", ".tmp", cacheDir)
        check(directory.delete() && directory.mkdir()) { "无法创建备份缓存" }
        val files = linkedMapOf<String, File>()
        val originals = linkedMapOf<String, File>()
        try {
            var total = 0L
            var manifest: JSONObject? = null
            val names = mutableSetOf<String>()
            // ZipFile requires a complete central directory; ZipInputStream alone accepts
            // truncation after the last local entry and cannot prove a complete backup.
            val archive = File(directory, "archive.zip")
            stream.use { source -> FileOutputStream(archive).use { destination ->
                val buffer = ByteArray(8192)
                var compressed = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n == -1) break
                    compressed += n
                    require(compressed <= MAX_ZIP_BYTES) { "备份文件超过容量限制" }
                    destination.write(buffer, 0, n)
                }
            } }
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(!entry.isDirectory && names.add(entry.name) && names.size <= MAX_ATTACHMENTS + 1) { "备份包含重复或无效文件" }
                    val match = attachmentPattern.matchEntire(entry.name)
                    val original = originalPattern.matchEntire(entry.name)
                    require(entry.name == "manifest.json" || match != null || original != null) { "备份包含不支持的文件" }
                    val limit = if(original!=null) ReimbursementStore.MAX_ATTACHMENT_BYTES else if (match == null) MAX_MANIFEST_BYTES.toLong() else MAX_IMAGE_BYTES.toLong()
                    require(entry.size < 0 || entry.size <= limit) { "备份文件超过容量限制" }
                    val target = File(directory, if(original!=null) "original-"+original.groupValues[1] else if (match == null) "manifest.json" else match.groupValues[1])
                    val digest = MessageDigest.getInstance("SHA-256")
                    val crc = CRC32()
                    var size = 0L
                    zip.getInputStream(entry).use { source -> FileOutputStream(target).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = source.read(buffer)
                            if (n == -1) break
                            size += n
                            total += n
                            require(size <= limit && total <= MAX_TOTAL_BYTES) { "备份解压内容超过容量限制" }
                            digest.update(buffer, 0, n)
                            crc.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    } }
                    require(size == entry.size && crc.value == entry.crc) { "备份文件已损坏" }
                    if(original!=null) {
                        val hash=original.groupValues[1]
                        require(size>0 && hex(digest.digest())==hash) { "报销原件已损坏" }
                        originals[hash]=target
                    }
                    else if (match == null) manifest = JSONObject(target.readText(Charsets.UTF_8))
                    else {
                        val hash = match.groupValues[1]
                        require(size > 0 && hex(digest.digest()) == hash) { "账单图片已损坏" }
                        validateImage(target)
                        files[hash] = target
                    }
                }
            }
            archive.delete()
            val root = requireNotNull(manifest) { "备份缺少账本清单" }
            require(root.get("format") == "simple-ledger-archive" && root.get("version") == 1) { "备份格式或版本不支持" }
            val ledger = root.getJSONObject("ledger")
            // ZIP metadata must not smuggle a second, unbounded Base64 image representation.
            val historyAssets = linkedMapOf<String, Map<Int, String>>()
            val histories = ledger.optJSONObject("histories") ?: JSONObject()
            for (uid in histories.keys()) {
                val entries = histories.getJSONArray(uid)
                val references = linkedMapOf<Int, String>()
                for (i in 0 until entries.length()) {
                    val e = entries.getJSONObject(i)
                    require(e.isNull("image")) { "备份截图格式无效" }
                    if (!e.isNull("asset")) references[e.getInt("sequence")] = checkedReference(e.get("asset"), files)
                }
                historyAssets[uid] = references
            }
            val backup = LedgerBackup.decodeMetadata(ledger)
            for (record in backup.records) backup.histories[record.uid]?.let { LedgerBackup.validateHistory(record, it) }
            val uids = backup.records.map { it.uid }.toSet()
            val iconAssets = linkedMapOf<String, String>()
            val icons = root.getJSONObject("merchantIcons")
            require(icons.length() <= uids.size) { "商户图标数量无效" }
            for (uid in icons.keys()) {
                require(uid in uids) { "商户图标没有关联账单" }
                iconAssets[uid] = checkedReference(icons.get(uid), files)
            }
            val references = historyAssets.values.flatMap { it.values }.toSet() + iconAssets.values
            require(references == files.keys) { "备份包含未关联的图片" }
            val attachments=backup.reimbursement?.getJSONArray("attachments") ?: JSONArray()
            val originalReferences=mutableSetOf<String>()
            for(i in 0 until attachments.length()) {
                val a=attachments.getJSONObject(i);val hash=checkedReference(a.get("sha256"),originals)
                require(originalReferences.add(hash) && originals.getValue(hash).length()==a.getLong("size")) { "原件大小无效或重复" }
            }
            require(originalReferences==originals.keys) { "备份包含未关联的报销原件" }
            return PreparedBackup(backup, directory, files, historyAssets, iconAssets, originals)
        } catch (error: Throwable) {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
            throw error
        }
    }

    /** Repeated imports are idempotent. Existing edits, evidence and capture permissions survive. */
    fun restore(store: LedgerStore, prepared: PreparedBackup): Int = synchronized(store) {
        prepared.ensureOpen()
        if (prepared.legacy) return@synchronized LedgerBackup.restore(store, prepared.backup)
        LedgerBackup.restore(store, prepared.backup, prepared.originalFiles) { record ->
            prepared.backup.histories[record.uid]?.let { entries ->
                store.restoreHistory(record.id, entries) { restored ->
                    prepared.historyAssets[record.uid]?.get(restored.entry.sequence)?.let { hash ->
                        attachmentBytes(prepared, hash)
                    }
                }
            }
            prepared.iconAssets[record.uid]?.let { store.restoreMerchantIcon(record.id, attachmentBytes(prepared, it)) }
        }
    }

    private fun attachmentBytes(prepared: PreparedBackup, assetHash: String): ByteArray {
        val file = prepared.files.getValue(assetHash)
        require(file.length() in 1..MAX_IMAGE_BYTES.toLong()) { "备份图片已损坏" }
        return file.readBytes().also { require(hash(it) == assetHash) { "备份图片已损坏" } }
    }

    private fun checkedReference(value: Any, files: Map<String, File>): String {
        val hash = value as? String ?: error("图片引用格式错误")
        require(hashPattern.matches(hash) && hash in files) { "备份缺少关联图片" }
        return hash
    }

    private fun validateImage(file: File) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 50_000_000L) { "账单图片无法读取" }
        var sample = 1
        while (options.outWidth / sample > 1024 || options.outHeight / sample > 1024) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        requireNotNull(decoded) { "账单图片无法读取" }.recycle()
    }

    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
