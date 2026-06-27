package com.irozar.ipdfmaster.pdfeditorspike

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/** Extracts both line blocks (for Edit) and word blocks (for Move). */
class PdfTextExtractor(private val targetPageIndex: Int) : PDFTextStripper() {

    val lines = mutableListOf<TextBlock>()
    val words = mutableListOf<WordBlock>()

    override fun writeString(text: String, positions: List<TextPosition>) {
        if (positions.isEmpty() || text.isBlank()) return

        val first = positions.first()
        val last = positions.last()
        val tm = first.textMatrix
        val lineX = tm.translateX
        val baselineY = tm.translateY
        val lineWidth = (last.xDirAdj + last.widthDirAdj) - first.xDirAdj

        // ---- line block (Edit) ----
        lines += TextBlock(
            pageIndex = targetPageIndex,
            text = text,
            x = first.xDirAdj,
            yTop = first.yDirAdj - first.heightDir,
            width = lineWidth,
            height = positions.maxOf { it.heightDir },
            fontSize = first.fontSizeInPt,
            fontName = first.font?.name ?: "",
            baselineX = lineX,
            baselineY = baselineY
        )

        // ---- word blocks (Move) ----
        val n = positions.size
        var i = 0
        while (i < n) {
            if (isBlank(positions[i])) { i++; continue }
            val s = i
            while (i < n && !isBlank(positions[i])) i++
            val e = i - 1   // inclusive

            val wordText = (s..e).joinToString("") { positions[it].unicode ?: "" }
            if (wordText.isBlank()) continue
            val prefix = (0 until s).joinToString("") { positions[it].unicode ?: "" }
            val suffix = ((e + 1) until n).joinToString("") { positions[it].unicode ?: "" }

            words += WordBlock(
                pageIndex = targetPageIndex,
                word = wordText,
                wordX = positions[s].textMatrix.translateX,
                baselineY = positions[s].textMatrix.translateY,
                wordWidth = (positions[e].xDirAdj + positions[e].widthDirAdj) - positions[s].xDirAdj,
                fontSize = positions[s].fontSizeInPt,
                lineX = lineX,
                lineWidth = lineWidth,
                prefixText = prefix,
                suffixText = suffix,
                suffixX = if (e + 1 < n) positions[e + 1].textMatrix.translateX else Float.NaN
            )
        }
    }

    private fun isBlank(p: TextPosition): Boolean = (p.unicode ?: " ").isBlank()

    companion object {
        fun extractAll(doc: PDDocument, pageIndex: Int): Pair<List<TextBlock>, List<WordBlock>> {
            val stripper = PdfTextExtractor(pageIndex).apply {
                startPage = pageIndex + 1
                endPage = pageIndex + 1
                sortByPosition = true
            }
            stripper.getText(doc)
            return stripper.lines to stripper.words
        }
    }
}
