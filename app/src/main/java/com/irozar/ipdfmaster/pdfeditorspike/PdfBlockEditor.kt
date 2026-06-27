package com.irozar.ipdfmaster.pdfeditorspike

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/**
 * Block editing engine. Pipeline for one edit:
 *  1. WALK the content stream, replaying CTM + text matrices (validated convention).
 *  2. DELETE the show operators whose text-matrix origin matches the target BASELINE.
 *  3. REUSE the original simple/embedded font if it can encode the new text, else Noto.
 *  4. REFLOW the new text within the block width, drawn from the baseline downward.
 *
 * Matching is anchored on the baseline (PDF user space) -- the same space the walk
 * computes -- which avoids top/bottom origin conversion bugs. If NOTHING matches, we
 * abort with NO_MATCH instead of drawing floating duplicate text.
 *
 * Known limits: Form XObjects, rotated/sheared text, and Tz scaling aren't handled.
 */
object PdfBlockEditor {

    private const val TAG = "PdfBlockEditor"
    private const val X_PAD = 4f
    private val SHOW_OPS = setOf("Tj", "TJ", "'", "\"")

    enum class Result { EDITED, NO_MATCH, FONT_UNSUPPORTED, ERROR }

    fun editBlock(
        context: Context,
        pdf: File,
        block: TextBlock,
        newText: String,
        sizePt: Float = block.fontSize,
        r: Int = 0,
        g: Int = 0,
        b: Int = 0,
        fontName: String = "Original",
        bold: Boolean = false,
        italic: Boolean = false,
        matchOriginalColor: Boolean = false
    ): Result {
        return try {
            PDDocument.load(pdf).use { doc ->
                val page = doc.getPage(block.pageIndex)
                val res = page.resources

                val walk = walkAndStrip(page, res, block)
                Log.d(TAG, "target baseline=(${block.baselineX}, ${block.baselineY}) " +
                        "width=${block.width} removed=${walk.removed}")

                if (walk.removed == 0) return Result.NO_MATCH

                // Default to the original text's fill colour (so an edit keeps the exact
                // shade) unless the caller picked an explicit colour.
                val oc = if (matchOriginalColor) walk.capturedColor else null
                val rr = oc?.getOrNull(0) ?: r
                val gg = oc?.getOrNull(1) ?: g
                val bb = oc?.getOrNull(2) ?: b

                // Pick a NON-bold font; bold is synthesized via stroke in drawTextAt so it
                // renders the same in the live editor preview and the exported PDF.
                val drawFont = chooseFont(doc, walk.capturedFont, newText, context, fontName, false, italic)
                    ?: return Result.FONT_UNSUPPORTED

                val newStream = PDStream(doc)
                newStream.createOutputStream(COSName.FLATE_DECODE).use { os ->
                    ContentStreamWriter(os).writeTokens(walk.tokens)
                }
                page.setContents(newStream)

                // Use page width (not the original box) so a wider fallback font doesn't wrap.
                val noWrapWidth = page.mediaBox.width - block.baselineX - 8f
                drawTextAt(doc, page, drawFont, block.baselineX, block.baselineY,
                    noWrapWidth, sizePt, newText, rr, gg, bb, bold && !isBoldFont(drawFont))

                val tmp = File(pdf.parentFile, "tmp_${pdf.name}")
                doc.save(tmp)
                tmp.copyTo(pdf, overwrite = true)
                tmp.delete()
            }
            Result.EDITED
        } catch (e: Exception) {
            Log.e(TAG, "edit failed", e)
            Result.ERROR
        }
    }

    /** Feature #1 (Edit tool): move existing text. Removes original glyphs, redraws the
     *  same text shifted by (dxPts, dyPts) in PDF points (+dy moves UP, user space).
     *  Formatting (size/colour/font/bold/italic) can be overridden; by default it keeps
     *  the original size and colour (matchOriginalColor) so a plain move looks unchanged. */
    fun moveBlock(
        context: Context, pdf: File, block: TextBlock, dxPts: Float, dyPts: Float,
        sizePt: Float = block.fontSize,
        r: Int = 0, g: Int = 0, b: Int = 0,
        fontName: String = "Original",
        bold: Boolean = false,
        italic: Boolean = false,
        matchOriginalColor: Boolean = true
    ): Result {
        return try {
            PDDocument.load(pdf).use { doc ->
                val page = doc.getPage(block.pageIndex)
                val walk = walkAndStrip(page, page.resources, block)
                Log.d(TAG, "move removed=${walk.removed} delta=($dxPts,$dyPts)")
                if (walk.removed == 0) return Result.NO_MATCH
                val drawFont = chooseFont(doc, walk.capturedFont, block.text, context, fontName, false, italic)
                    ?: return Result.FONT_UNSUPPORTED
                val ns = PDStream(doc)
                ns.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(walk.tokens) }
                page.setContents(ns)
                // Keep the original colour unless the caller picked one.
                val oc = if (matchOriginalColor) walk.capturedColor else null
                val rr = oc?.getOrNull(0) ?: r
                val gg = oc?.getOrNull(1) ?: g
                val bb = oc?.getOrNull(2) ?: b
                drawTextAt(doc, page, drawFont, block.baselineX + dxPts, block.baselineY + dyPts,
                    page.mediaBox.width, sizePt, block.text, rr, gg, bb,
                    bold && !isBoldFont(drawFont), wrapText = false)
                saveOver(pdf, doc)
            }
            Result.EDITED
        } catch (e: Exception) { Log.e(TAG, "move failed", e); Result.ERROR }
    }

    /** Feature #2 (Type Text): add brand-new text at a baseline point (PDF user space). */
    fun addText(context: Context, pdf: File, pageIndex: Int, baselineX: Float, baselineY: Float,
                text: String, sizePt: Float, r: Int, g: Int, b: Int,
                snapBaselines: List<Float> = emptyList(),
                fontName: String = "Poppins",
                bold: Boolean = false,
                italic: Boolean = false): Result {
        return try {
            PDDocument.load(pdf).use { doc ->
                val page = doc.getPage(pageIndex)
                val font = chooseFont(doc, null, text, context, fontName, false, italic)
                    ?: return Result.FONT_UNSUPPORTED
                var by = baselineY
                val snap = snapBaselines.minByOrNull { kotlin.math.abs(it - by) }
                if (snap != null && kotlin.math.abs(snap - by) <= sizePt * 0.7f) by = snap
                val maxW = page.mediaBox.width - baselineX - 36f
                drawTextAt(doc, page, font, baselineX, by, maxW, sizePt, text, r, g, b, bold && !isBoldFont(font))
                saveOver(pdf, doc)
            }
            Result.EDITED
        } catch (e: Exception) { Log.e(TAG, "addText failed", e); Result.ERROR }
    }

    /** Feature: place an image on the page. (left, bottom) is the lower-left corner in PDF
     *  user space. [behind] = draw it BEHIND the page text (PREPEND), else over it (APPEND). */
    fun placeImage(pdf: File, pageIndex: Int, bitmap: Bitmap,
                   left: Float, bottom: Float, w: Float, h: Float, behind: Boolean): Result {
        return try {
            PDDocument.load(pdf).use { doc ->
                val page = doc.getPage(pageIndex)
                val image = LosslessFactory.createFromImage(doc, bitmap)
                val mode = if (behind) AppendMode.PREPEND else AppendMode.APPEND
                PDPageContentStream(doc, page, mode, true, true).use { cs ->
                    cs.drawImage(image, left, bottom, w, h)
                }
                saveOver(pdf, doc)
            }
            Result.EDITED
        } catch (e: Exception) { Log.e(TAG, "placeImage failed", e); Result.ERROR }
    }

    /** Feature #3 (Text Box): bordered box with text, baked into the page.
     *  rect in PDF user space: (left, bottom) is the lower-left corner. */
    fun addTextBox(context: Context, pdf: File, pageIndex: Int,
                   left: Float, bottom: Float, w: Float, h: Float, text: String, sizePt: Float): Result {
        return try {
            PDDocument.load(pdf).use { doc ->
                val page = doc.getPage(pageIndex)
                val font = chooseFont(doc, null, text, context) ?: return Result.FONT_UNSUPPORTED
                PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { cs ->
                    cs.setStrokingColor(220, 38, 38)
                    cs.setLineWidth(1.2f)
                    cs.addRect(left, bottom, w, h)
                    cs.stroke()
                }
                val pad = 6f
                drawTextAt(doc, page, font, left + pad, bottom + h - sizePt - pad,
                    w - 2 * pad, sizePt, text, 0, 0, 0)
                saveOver(pdf, doc)
            }
            Result.EDITED
        } catch (e: Exception) { Log.e(TAG, "addTextBox failed", e); Result.ERROR }
    }

    /** Feature #2b: move a SINGLE word. Removes the whole line, redraws the rest of the
     *  line in place, and draws just the word shifted by (dxPts, dyPts). */
    fun moveWord(context: Context, pdf: File, wb: WordBlock, dxPts: Float, dyPts: Float,
                 snapBaselines: List<Float> = emptyList()): Result {
        return try {
            PDDocument.load(pdf).use { doc ->
                val page = doc.getPage(wb.pageIndex)
                // Build a line-shaped target so walkAndStrip removes the whole line.
                val lineTarget = TextBlock(
                    pageIndex = wb.pageIndex, text = "", x = 0f, yTop = 0f,
                    width = wb.lineWidth, height = wb.fontSize, fontSize = wb.fontSize,
                    fontName = "", baselineX = wb.lineX, baselineY = wb.baselineY
                )
                val walk = walkAndStrip(page, page.resources, lineTarget)
                Log.d(TAG, "moveWord '${wb.word}' removed=${walk.removed} delta=($dxPts,$dyPts)")
                if (walk.removed == 0) return Result.NO_MATCH

                val font = chooseFont(doc, walk.capturedFont, wb.prefixText + wb.word + wb.suffixText, context)
                    ?: return Result.FONT_UNSUPPORTED

                val ns = PDStream(doc)
                ns.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(walk.tokens) }
                page.setContents(ns)

                val wide = page.mediaBox.width
                // Auto-adjust the source line: redraw the remaining words as ONE continuous
                // run from the line start, so no gap is left where the word used to be.
                val remaining = joinRemaining(wb.prefixText, wb.suffixText)
                if (remaining.isNotBlank())
                    drawTextAt(doc, page, font, wb.lineX, wb.baselineY, wide, wb.fontSize, remaining, 0, 0, 0)

                // Keep the moved word in line: snap its baseline to the nearest existing one.
                var newY = wb.baselineY + dyPts
                val snap = snapBaselines.minByOrNull { kotlin.math.abs(it - newY) }
                if (snap != null && kotlin.math.abs(snap - newY) <= wb.fontSize * 0.7f) newY = snap
                drawTextAt(doc, page, font, wb.wordX + dxPts, newY, wide, wb.fontSize, wb.word, 0, 0, 0)

                saveOver(pdf, doc)
            }
            Result.EDITED
        } catch (e: Exception) { Log.e(TAG, "moveWord failed", e); Result.ERROR }
    }

    private fun joinRemaining(prefix: String, suffix: String): String {
        val l = prefix.trimEnd(); val r = suffix.trimStart()
        return when { l.isEmpty() -> r; r.isEmpty() -> l; else -> "$l $r" }
    }

    private fun saveOver(pdf: File, doc: PDDocument) {
        val tmp = File(pdf.parentFile, "tmp_${pdf.name}")
        doc.save(tmp)
        tmp.copyTo(pdf, overwrite = true)
        tmp.delete()
    }

    // ---- steps 1 & 2 ----

    private class WalkResult(val tokens: MutableList<Any>, val capturedFont: PDFont?, val removed: Int,
                             val capturedColor: IntArray?)

    private fun walkAndStrip(page: PDPage, res: PDResources, block: TextBlock): WalkResult {
        val parser = PDFStreamParser(page)
        val raw = ArrayList<Any>()
        var t = parser.parseNextToken()
        while (t != null) { raw.add(t); t = parser.parseNextToken() }

        val out = ArrayList<Any>()
        val operands = ArrayList<COSBase>()

        var ctm = Matrix()
        val ctmStack = ArrayDeque<Matrix>()
        var tm = Matrix()
        var tlm = Matrix()
        var leading = 0f
        var curFont: PDFont? = null
        var capturedFont: PDFont? = null
        var curColor: IntArray? = null
        var capturedColor: IntArray? = null
        var removed = 0

        val tolY = (0.6f * block.fontSize).coerceAtLeast(2f)
        val xMin = block.baselineX - X_PAD
        val xMax = block.baselineX + block.width + X_PAD

        for (tok in raw) {
            if (tok !is Operator) { operands.add(tok as COSBase); continue }

            when (tok.name) {
                "q" -> ctmStack.addLast(ctm.clone())
                "Q" -> if (ctmStack.isNotEmpty()) ctm = ctmStack.removeLast()
                "cm" -> matrixFrom(operands)?.let { ctm = it.multiply(ctm) }
                "BT" -> { tm = Matrix(); tlm = Matrix() }
                "Tf" -> (operands.getOrNull(0) as? COSName)?.let { n ->
                    runCatching { res.getFont(n) }.getOrNull()?.let { curFont = it }
                }
                "TL" -> leading = num(operands.getOrNull(0))
                "Td" -> { tlm = Matrix.getTranslateInstance(num(operands.getOrNull(0)), num(operands.getOrNull(1))).multiply(tlm); tm = tlm.clone() }
                "TD" -> { val ty = num(operands.getOrNull(1)); leading = -ty; tlm = Matrix.getTranslateInstance(num(operands.getOrNull(0)), ty).multiply(tlm); tm = tlm.clone() }
                "Tm" -> matrixFrom(operands)?.let { tm = it; tlm = it.clone() }
                "T*" -> { tlm = Matrix.getTranslateInstance(0f, -leading).multiply(tlm); tm = tlm.clone() }
                // Track the current non-stroking (fill) colour so edits can match it.
                "g" -> { val gr = (num(operands.getOrNull(0)) * 255).toInt().coerceIn(0, 255); curColor = intArrayOf(gr, gr, gr) }
                "rg" -> curColor = intArrayOf(
                    (num(operands.getOrNull(0)) * 255).toInt().coerceIn(0, 255),
                    (num(operands.getOrNull(1)) * 255).toInt().coerceIn(0, 255),
                    (num(operands.getOrNull(2)) * 255).toInt().coerceIn(0, 255))
                "k" -> {
                    val c = num(operands.getOrNull(0)); val mm = num(operands.getOrNull(1))
                    val y = num(operands.getOrNull(2)); val kk = num(operands.getOrNull(3))
                    curColor = intArrayOf(
                        (255 * (1 - c) * (1 - kk)).toInt().coerceIn(0, 255),
                        (255 * (1 - mm) * (1 - kk)).toInt().coerceIn(0, 255),
                        (255 * (1 - y) * (1 - kk)).toInt().coerceIn(0, 255))
                }
            }

            if (tok.name in SHOW_OPS) {
                if (tok.name == "'" || tok.name == "\"") {
                    tlm = Matrix.getTranslateInstance(0f, -leading).multiply(tlm); tm = tlm.clone()
                }
                val render = tm.multiply(ctm)
                val px = render.translateX
                val py = render.translateY
                val inside = px in xMin..xMax && kotlin.math.abs(py - block.baselineY) <= tolY
                if (inside) {
                    if (capturedFont == null) capturedFont = curFont
                    if (capturedColor == null) capturedColor = curColor
                    emitEmptiedShow(out, tok, operands)
                    removed++
                } else {
                    out.addAll(operands); out.add(tok)
                }
            } else {
                out.addAll(operands); out.add(tok)
            }
            operands.clear()
        }
        return WalkResult(out, capturedFont, removed, capturedColor)
    }

    private fun emitEmptiedShow(out: MutableList<Any>, op: Operator, operands: List<COSBase>) {
        when (op.name) {
            "Tj", "'" -> { out.add(COSString("")); out.add(op) }
            "TJ" -> { out.add(COSArray()); out.add(op) }
            "\"" -> {
                out.add(operands.getOrElse(0) { COSNumber.get("0") })
                out.add(operands.getOrElse(1) { COSNumber.get("0") })
                out.add(COSString("")); out.add(op)
            }
            else -> { out.addAll(operands); out.add(op) }
        }
    }

    private fun matrixFrom(o: List<COSBase>): Matrix? {
        if (o.size < 6) return null
        return Matrix(num(o[0]), num(o[1]), num(o[2]), num(o[3]), num(o[4]), num(o[5]))
    }

    private fun num(b: COSBase?): Float = (b as? COSNumber)?.floatValue() ?: 0f

    // ---- step 3 ----

    private fun chooseFont(
        doc: PDDocument,
        original: PDFont?,
        text: String,
        ctx: Context,
        fontName: String = "Original",
        bold: Boolean = false,
        italic: Boolean = false
    ): PDFont? {
        val wantsOriginal = fontName.equals("Original", ignoreCase = true) && !bold && !italic

        // 1. Reuse the original simple/embedded font only when explicitly requested.
        if (wantsOriginal && original != null && (original is PDType1Font || original is PDTrueTypeFont) && canRender(original, text)) {
            Log.d(TAG, "reusing original font: ${original.name}")
            return original
        }

        // 2. Bundled Poppins if the app provides it in assets/fonts.
        if (fontName.equals("Poppins", ignoreCase = true)) {
            poppinsAssetName(bold, italic)?.let { asset ->
                val poppins = runCatching {
                    ctx.assets.open("fonts/$asset").use { PDType0Font.load(doc, it) }
                }.getOrNull()
                if (poppins != null && canRender(poppins, text)) return poppins
            }
        }

        // 3. Always-available standard font (Latin), including bold/italic variants.
        val std = standardFont(fontName, bold, italic)
        if (canRender(std, text)) {
            Log.d(TAG, "using built-in ${std.name} fallback")
            return std
        }

        // 4. Bundled Noto (best for CJK / RTL). Skip gracefully if the asset isn't present.
        // Noto-Regular is intentionally after standard fonts so Latin bold/italic requests
        // do not get flattened into a regular-weight Unicode fallback.
        val noto = runCatching {
            ctx.assets.open("fonts/NotoSans-Regular.ttf").use { PDType0Font.load(doc, it) }
        }.getOrNull()
        if (noto != null && canRender(noto, text)) return noto
        return null
    }

    private fun poppinsAssetName(bold: Boolean, italic: Boolean): String =
        when {
            bold && italic -> "Poppins-BoldItalic.ttf"
            bold -> "Poppins-Bold.ttf"
            italic -> "Poppins-Italic.ttf"
            else -> "Poppins-Regular.ttf"
        }

    private fun standardFont(fontName: String, bold: Boolean, italic: Boolean): PDFont {
        val useTimes = fontName.equals("Times", ignoreCase = true)
        val useCourier = fontName.equals("Courier", ignoreCase = true)
        return when {
            useCourier && bold && italic -> PDType1Font.COURIER_BOLD_OBLIQUE
            useCourier && bold -> PDType1Font.COURIER_BOLD
            useCourier && italic -> PDType1Font.COURIER_OBLIQUE
            useCourier -> PDType1Font.COURIER
            useTimes && bold && italic -> PDType1Font.TIMES_BOLD_ITALIC
            useTimes && bold -> PDType1Font.TIMES_BOLD
            useTimes && italic -> PDType1Font.TIMES_ITALIC
            useTimes -> PDType1Font.TIMES_ROMAN
            bold && italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
            bold -> PDType1Font.HELVETICA_BOLD
            italic -> PDType1Font.HELVETICA_OBLIQUE
            else -> PDType1Font.HELVETICA
        }
    }

    private fun canRender(font: PDFont, text: String): Boolean =
        runCatching { font.getStringWidth(text.replace("\n", " ")) }.isSuccess

    /** True if the font already has a real bold weight, so we must NOT also stroke it
     *  (stroking a bold font produces a far-too-heavy "double bold"). */
    private fun isBoldFont(font: PDFont?): Boolean =
        font === PDType1Font.HELVETICA_BOLD ||
        font === PDType1Font.HELVETICA_BOLD_OBLIQUE ||
        font === PDType1Font.TIMES_BOLD ||
        font === PDType1Font.TIMES_BOLD_ITALIC ||
        (font?.name?.contains("Bold", ignoreCase = true) == true)

    // ---- step 4 ----

    private fun drawTextAt(
        doc: PDDocument, page: PDPage, font: PDFont,
        x: Float, y: Float, maxWidth: Float, sizeIn: Float, text: String,
        r: Int, g: Int, b: Int, bold: Boolean = false, wrapText: Boolean = true
    ) {
        val size = sizeIn.coerceAtLeast(6f)
        val leading = size * 1.2f
        // When moving an existing line, keep it as a single line. Re-wrapping it to the
        // original width can split it in two (if the substitute font is wider), which then
        // overlaps the next line. Free-text additions still wrap normally.
        val lines = if (wrapText) wrap(font, size, text, maxWidth.coerceAtLeast(10f))
                    else listOf(text.replace("\n", " "))
        PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { cs ->
            cs.setNonStrokingColor(r, g, b)
            // Synthesize bold by outlining each glyph (fill + stroke) in the same colour.
            // Guarantees a heavier weight even when the chosen font has no real bold cut
            // (e.g. a reused embedded font or the regular-weight Unicode fallback).
            if (bold) {
                cs.setStrokingColor(r, g, b)
                cs.setLineWidth(size * 0.035f)
                cs.setRenderingMode(RenderingMode.FILL_STROKE)
            }
            var yy = y
            for (line in lines) {
                cs.beginText()
                cs.setFont(font, size)
                cs.newLineAtOffset(x, yy)
                cs.showText(line)
                cs.endText()
                yy -= leading
            }
        }
    }

    private fun wrap(font: PDFont, size: Float, text: String, maxWidth: Float): List<String> {
        val words = text.replace("\n", " ").split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            val width = runCatching { font.getStringWidth(trial) / 1000f * size }.getOrDefault(0f)
            if (cur.isEmpty() || width <= maxWidth) cur = StringBuilder(trial)
            else { lines.add(cur.toString()); cur = StringBuilder(w) }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines
    }
}
