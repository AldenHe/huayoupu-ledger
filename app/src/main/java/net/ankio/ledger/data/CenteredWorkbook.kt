package net.ankio.ledger.data

import java.io.FilterOutputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small native OOXML writer shared by report and reimbursement exports. No formulas from user text. */
internal object CenteredWorkbook {
    data class Cell(val text:String,val numeric:Boolean=false,val money:Boolean=false)
    data class Picture(val row:Int,val column:Int,val width:Int,val height:Int,val png:()->ByteArray?)
    data class Sheet(val name:String,val widths:List<Int>,val rows:List<List<Cell>>,val filter:Boolean=false,val pictures:List<Picture> = emptyList(),val identities:List<String> = emptyList())
    fun text(value:Any?)=Cell(value?.toString().orEmpty())
    fun money(cents:Long)=Cell(BigDecimal.valueOf(cents,2).toPlainString(),true,true)
    fun number(value:Int)=Cell(value.toString(),true)
    internal fun xml(value:String)=buildString {value.codePoints().forEach {c->when(c){38->append("&amp;");60->append("&lt;");62->append("&gt;");34->append("&quot;");else->if(c==9||c==10||c==13||c in 0x20..0xd7ff||c in 0xe000..0xfffd||c in 0x10000..0x10ffff)appendCodePoint(c)}}}
    private fun column(index:Int):String {var n=index+1;var name="";while(n>0){n--;name=('A'+n%26)+name;n/=26};return name}
    /** Split exact text slices, without inserting/removing source characters, below Excel's 409pt limit. */
    private fun chunks(value:String,width:Int):List<String> {
        val result=mutableListOf<String>();val part=StringBuilder();var lines=1;var units=0
        fun flush(){if(part.isNotEmpty()){result+=part.toString();part.clear()};lines=1;units=0}
        value.codePoints().forEach {code->
            if(code==10){
                if(lines>=21)flush()
                part.appendCodePoint(code);lines++;units=0
            }else{
                val size=if(code>255)2 else 1
                if(units+size>width&&units>0){lines++;units=0}
                if(lines>21)flush()
                part.appendCodePoint(code);units+=size
            }
        }
        flush();return result.ifEmpty {listOf("")}
    }
    /** Keep sortable records intact; long fields live in one appendix with sort-independent keys. */
    private fun withLongText(sheets:List<Sheet>):List<Sheet> {
        val appendix=mutableListOf(listOf("长文本编号","来源工作表","来源记录标识","字段","分段","完整内容").map(::text))
        var count=0
        val prepared=sheets.map {sheet->
            val rows=sheet.rows.mapIndexed {r,row->
                val identity=sheet.identities.getOrNull(r)?.takeIf {it.isNotBlank()}?:row.firstOrNull()?.text?.take(120).orEmpty().ifBlank {"未命名记录"}
                row.mapIndexed {c,cell->
                    if(cell.numeric||chunks(cell.text,sheet.widths.getOrElse(c){24}).size==1)cell else {
                        val key="L${(++count).toString().padStart(3,'0')}"
                        val field=sheet.rows.firstOrNull()?.getOrNull(c)?.text?.takeIf {r>0&&it.isNotBlank()}?.take(80)?:"${column(c)} 列"
                        val parts=chunks(cell.text,72)
                        parts.forEachIndexed {index,part->appendix+=listOf(text(key),text(sheet.name),text(identity),text(field),text("${index+1}/${parts.size}"),text(part))}
                        text("${field.take(24)}\n完整内容见「长文本」$key")
                    }
                }
            }
            sheet.copy(rows=rows)
        }
        return if(count==0)prepared else {
            require(sheets.none {it.name=="长文本"}){"长文本工作表名称已被占用"}
            prepared+Sheet("长文本",listOf(18,24,42,32,14,72),appendix,true)
        }
    }
    fun write(sourceSheets:List<Sheet>,output:OutputStream) {
        val sheets=withLongText(sourceSheets)
        require(sheets.isNotEmpty()&&sheets.size<=20)
        ZipOutputStream(object:FilterOutputStream(output){override fun close(){flush()}}).use {zip->
            fun entry(path:String,value:String){zip.putNextEntry(ZipEntry(path));zip.write(value.toByteArray(Charsets.UTF_8));zip.closeEntry()}
            val pictures=sheets.withIndex().flatMap {(index,sheet)->sheet.pictures.map {Triple(index+1,it,"s${index+1}r${it.row}c${it.column}")}}
            entry("[Content_Types].xml","""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.indices.joinToString(""){"<Override PartName=\"/xl/worksheets/sheet${it+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"}}${sheets.withIndex().filter {it.value.pictures.isNotEmpty()}.joinToString(""){"<Override PartName=\"/xl/drawings/drawing${it.index+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>"}}</Types>""")
            entry("_rels/.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry("xl/workbook.xml","""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView/></bookViews><sheets>${sheets.mapIndexed {i,s->"<sheet name=\"${xml(s.name)}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>"}.joinToString("")}</sheets></workbook>""")
            entry("xl/_rels/workbook.xml.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.indices.joinToString(""){"<Relationship Id=\"rId${it+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it+1}.xml\"/>"}}<Relationship Id="rId${sheets.size+1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            entry("xl/styles.xml","""<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Microsoft YaHei"/></font><font><b/><sz val="11"/><name val="Microsoft YaHei"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="3"><xf fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf fontId="1" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            sheets.forEachIndexed {index,sheet->
                val part=index+1
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet$part.xml"))
                fun put(s:String){zip.write(s.toByteArray(Charsets.UTF_8))}
                put("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheetViews><sheetView workbookViewId="0">${if(sheet.filter)"<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"else""}</sheetView></sheetViews><sheetFormatPr defaultRowHeight="28"/><cols>${sheet.widths.mapIndexed {i,w->"<col min=\"${i+1}\" max=\"${i+1}\" width=\"$w\" customWidth=\"1\"/>"}.joinToString("")}</cols><sheetData>""")
                sheet.rows.forEachIndexed {r,values->
                    val lines=values.mapIndexed {c,cell->cell.text.split('\n').sumOf {line->((line.codePoints().map {if(it>255)2 else 1}.sum()+sheet.widths.getOrElse(c){24}-1)/sheet.widths.getOrElse(c){24}).coerceAtLeast(1)}}.maxOrNull()?:1
                    val minHeight=sheet.pictures.filter {it.row==r}.maxOfOrNull {it.height*0.75+12}?:28.0
                    val height=maxOf(lines*17.0+10,minHeight).coerceAtMost(409.0)
                    put("<row r=\"${r+1}\" ht=\"$height\" customHeight=\"1\">")
                    values.forEachIndexed {c,cell->val style=if(cell.money)1 else if(r==0)2 else 0
                        val ref="${column(c)}${r+1}"
                        put(if(cell.numeric)"<c r=\"$ref\" s=\"$style\"><v>${cell.text}</v></c>" else "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(cell.text)}</t></is></c>")
                    };put("</row>")
                }
                put("</sheetData>")
                if(sheet.filter)put("<autoFilter ref=\"A1:${column(sheet.widths.lastIndex)}${sheet.rows.size}\"/>")
                put("<printOptions horizontalCentered=\"1\"/><pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/><pageSetup orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
                if(sheet.pictures.isNotEmpty())put("<drawing r:id=\"drawing\"/>")
                put("</worksheet>");zip.closeEntry()
                if(sheet.pictures.isNotEmpty()) {
                    entry("xl/worksheets/_rels/sheet$part.xml.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="drawing" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing$part.xml"/></Relationships>""")
                    val own=pictures.filter {it.first==part}
                    entry("xl/drawings/drawing$part.xml","""<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">${own.mapIndexed {i,(_,p,key)->val colPx=sheet.widths[p.column]*7+5;val left=((colPx-p.width)/2).coerceAtLeast(4);"""<xdr:oneCellAnchor><xdr:from><xdr:col>${p.column}</xdr:col><xdr:colOff>${left*9525}</xdr:colOff><xdr:row>${p.row}</xdr:row><xdr:rowOff>76200</xdr:rowOff></xdr:from><xdr:ext cx="${p.width*9525}" cy="${p.height*9525}"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${i+1}" name="支付截图-${xml(key)}"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:embed="img${i+1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${p.width*9525}" cy="${p.height*9525}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>"""}.joinToString("")}</xdr:wsDr>""")
                    entry("xl/drawings/_rels/drawing$part.xml.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${own.mapIndexed {i,(_,_,key)->"<Relationship Id=\"img${i+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/$key.png\"/>"}.joinToString("")}</Relationships>""")
                    own.forEach {(_,picture,key)->val bytes=requireNotNull(picture.png()){ "截图已失效，请重新生成材料包" };zip.putNextEntry(ZipEntry("xl/media/$key.png"));zip.write(bytes);zip.closeEntry()}
                }
            }
        }
    }
}
