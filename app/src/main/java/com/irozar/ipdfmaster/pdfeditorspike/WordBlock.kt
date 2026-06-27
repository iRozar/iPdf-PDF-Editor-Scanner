package com.irozar.ipdfmaster.pdfeditorspike

/**
 * A single word, with everything needed to MOVE just that word while leaving the rest
 * of its line in place. All X/Y are PDF user space (bottom-left baseline).
 *
 * To move a word we remove the whole line's glyphs (reliable), then redraw:
 *   - prefixText at lineX           (text before the word, original spot)
 *   - suffixText at suffixX         (text after the word, original spot)
 *   - the word at (wordX+dx, y+dy)  (relocated)
 */
data class WordBlock(
    val pageIndex: Int,
    val word: String,
    val wordX: Float,
    val baselineY: Float,
    val wordWidth: Float,
    val fontSize: Float,
    val lineX: Float,
    val lineWidth: Float,
    val prefixText: String,
    val suffixText: String,
    val suffixX: Float   // Float.NaN if the word is last on the line
)
