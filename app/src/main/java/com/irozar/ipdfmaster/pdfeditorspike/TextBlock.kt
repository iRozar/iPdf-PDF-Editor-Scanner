package com.irozar.ipdfmaster.pdfeditorspike

/**
 * One editable run of text (a line, in this spike).
 *
 * Two coordinate sets, on purpose:
 *  - baselineX / baselineY: the text-matrix origin in PDF USER space (bottom-left).
 *    This is the reliable anchor for finding + removing the text and for redrawing,
 *    because it's the same space the content-stream replay computes in.
 *  - x / yTop / width / height: a TOP-LEFT visual box (PDF points) used only to place
 *    the on-screen edit overlay and to bound reflow width.
 */
data class TextBlock(
    val pageIndex: Int,
    val text: String,
    val x: Float,
    val yTop: Float,
    val width: Float,
    val height: Float,
    val fontSize: Float,
    val fontName: String,
    val baselineX: Float,
    val baselineY: Float
) {
    fun containsTopLeft(px: Float, py: Float, pad: Float = 2f): Boolean =
        px >= x - pad && px <= x + width + pad &&
        py >= yTop - pad && py <= yTop + height + pad
}
