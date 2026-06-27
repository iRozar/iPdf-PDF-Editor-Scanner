package com.irozar.ipdfmaster.utils

data class TextBlock(
    val id: String,
    var text: String,
    var x: Float, // percentage 0f - 100f
    var y: Float, // percentage 0f - 100f
    var fontSize: Float = 14f,
    var colorHex: String = "#333333",
    var fontStyle: String = "Normal", // "Bold", "Italic", "Underline"
    var width: Float = 18f,
    var height: Float = 4f,
    var originalText: String = text,
    var fontFamily: String = "Helvetica",
    var pageIndex: Int = 0,
    var source: String = "generated" // "generated", "ocr", "user"
)

data class DocumentPage(
    val pageNumber: Int,
    var rotation: Int = 0, // 0, 90, 180, 270
    val textBlocks: MutableList<TextBlock> = mutableListOf(),
    val baseImageResId: String? = null // For pages that are scanned images
)

object DocumentEngine {

    // In-memory mapping of extracted/copied file name to a list of (sourceFileName, sourcePageNumber)
    private val extractedPagesMap = java.util.concurrent.ConcurrentHashMap<String, List<Pair<String, Int>>>()

    private fun cleanPdfName(fileName: String): String {
        return if (fileName.endsWith(".pdf")) fileName else "$fileName.pdf"
    }

    fun registerMergedFile(newFileName: String, sourceFiles: List<Pair<String, Int>>) {
        val cleanName = cleanPdfName(newFileName)
        val sourcePages = sourceFiles.flatMap { source ->
            getOrCreatePageMapping(source.first, source.second)
        }
        extractedPagesMap[cleanName] = sourcePages
    }

    fun registerExtractedFile(newFileName: String, sourcePages: List<Pair<String, Int>>) {
        val cleanName = cleanPdfName(newFileName)
        extractedPagesMap[cleanName] = sourcePages.map { cleanPdfName(it.first) to it.second }
    }

    fun getOrCreatePageMapping(fileName: String, pageCount: Int): List<Pair<String, Int>> {
        val cleanName = cleanPdfName(fileName)
        val existing = extractedPagesMap[cleanName]
        if (existing != null) {
            return existing
        }
        return (1..pageCount).map { cleanName to it }
    }

    fun getSourcePage(fileName: String, pageNumber: Int, pageCount: Int): Pair<String, Int> {
        return getOrCreatePageMapping(fileName, pageCount).getOrNull(pageNumber - 1)
            ?: (fileName to pageNumber)
    }

    /**
     * Dynamically generates the actual textual content of default mock files
     * so that the user and the Gemini AI assistant can actually read and summarize them!
     */
    fun getPageContentsForFile(fileName: String, pageNumber: Int): List<TextBlock> {
        val cleanName = cleanPdfName(fileName)
        
        // First check extracted pages map to see if we point back to a specific page of an original doc
        val extractedSources = extractedPagesMap[cleanName]
        if (extractedSources != null) {
            if (pageNumber in 1..extractedSources.size) {
                val source = extractedSources[pageNumber - 1]
                if (source.first == cleanName) {
                    return getBasePageContentsForFile(source.first, source.second)
                }
                return getPageContentsForFile(source.first, source.second)
            }
        }

        return getBasePageContentsForFile(cleanName, pageNumber)
    }

    private fun getBasePageContentsForFile(fileName: String, pageNumber: Int): List<TextBlock> {
        return when (fileName) {
            "Project_Proposal.pdf" -> {
                when (pageNumber) {
                    1 -> listOf(
                        TextBlock("p1_t1", "PROJECT PROPOSAL", 10f, 15f, 26f, "#D32F2F", "Bold"),
                        TextBlock("p1_t2", "A Mobile PDF Application Solution", 10f, 22f, 16f, "#555555"),
                        TextBlock("p1_t3", "Prepared by: DevTeam Alpha", 10f, 75f, 12f, "#111111"),
                        TextBlock("p1_t4", "Date: June 2026 | Confidentiality: High", 10f, 80f, 11f, "#777777")
                    )
                    2 -> listOf(
                        TextBlock("p2_t1", "Executive Summary", 10f, 10f, 20f, "#222222", "Bold"),
                        TextBlock("p2_t2", "This project aims to develop an absolute, offline-first mobile application that helps users manage and secure their PDF documents easily on Android devices.", 10f, 17f, 14f, "#111111"),
                        TextBlock("p2_t3", "Our primary focus is strict privacy - scanning, drawing, and AI tasks process locally without cloud servers.", 10f, 32f, 14f, "#333333"),
                        TextBlock("p2_t4", "Key features include drawing signatures, highlighting text, page rotation, on-device OCR scanners, and interactive intelligence.", 10f, 48f, 13f, "#444444")
                    )
                    3 -> listOf(
                        TextBlock("p3_t1", "Technical Architecture", 10f, 10f, 20f, "#222222", "Bold"),
                        TextBlock("p3_t2", "The application runs on a clean architecture stack utilizing Kotlin, Jetpack Compose, and Room Database SQLite persistence.", 10f, 18f, 13f, "#111111"),
                        TextBlock("p3_t3", "PDF generation leverages light standard drawing contexts with high fidelity layouts, ensuring minimal memory consumptions.", 10f, 30f, 13f, "#111111")
                    )
                    else -> listOf(
                        TextBlock("px_t1", "Project Proposal - Page $pageNumber", 10f, 10f, 18f, "#222222"),
                        TextBlock("px_t2", "Supplementary information and schedules for project completion. Milestone details, timeline forecasts, and allocation of key technical developers.", 10f, 18f, 13f, "#555555")
                    )
                }
            }
            "Math_Notes.pdf" -> {
                when (pageNumber) {
                    1 -> listOf(
                        TextBlock("m1_t1", "ADVANCED CALCULUS NOTES", 14f, 15f, 22f, "#1E88E5", "Bold"),
                        TextBlock("m1_t2", "Topic 1: Derivative Limits & Continuity", 14f, 22f, 15f, "#333333"),
                        TextBlock("m1_t3", "Theorem of Limits: If f(x) satisfies continuous bounds...", 14f, 35f, 13f, "#111111")
                    )
                    2 -> listOf(
                        TextBlock("m2_t1", "Integration Sequences", 14f, 10f, 20f, "#1E88E5", "Bold"),
                        TextBlock("m2_t2", "Section 2.1: Riemann Sum Integrals", 14f, 18f, 14f, "#333333"),
                        TextBlock("m2_t3", "Evaluating the area under f(x) over [a,b] is computed by finding limits of summation. Area = lim [Σ f(x_i) Δx].", 14f, 26f, 13f, "#111111")
                    )
                    else -> listOf(
                        TextBlock("mx_t1", "Mathematics Study - Page $pageNumber", 14f, 10f, 18f, "#1E88E5"),
                        TextBlock("mx_t2", "Additional mathematical proof procedures and derivation equations for student review and homework preparation.", 14f, 18f, 13f, "#444444")
                    )
                }
            }
            "ID_Card.pdf" -> {
                listOf(
                    TextBlock("id_t1", "REPUBLIC DIGITAL IDENTIFICATION", 10f, 12f, 16f, "#1A237E", "Bold"),
                    TextBlock("id_t2", "SURNAME: SMITH", 40f, 30f, 13f, "#222222", "Bold"),
                    TextBlock("id_t3", "GIVEN NAME: JONATHAN", 40f, 38f, 13f, "#222222", "Bold"),
                    TextBlock("id_t4", "ID NUMBER: 884-2951-XG", 40f, 46f, 13f, "#D32F2F", "Bold"),
                    TextBlock("id_t5", "NATIONALITY: CITIZEN", 40f, 54f, 11f, "#444444"),
                    TextBlock("id_t6", "ISSUING AUTH: DEPT. OF INTERNAL RECORD", 10f, 85f, 10f, "#777777")
                )
            }
            else -> {
                emptyList()
            }
        }
    }

    /**
     * Combines multiple files into a new single merged simulated PDF file.
     */
    fun performSimulatedMerge(newFileName: String, filesToMerge: List<Pair<String, Int>>): Int {
        // Returns the final accumulated total page count.
        var totalPages = 0
        for (file in filesToMerge) {
            totalPages += file.second
        }
        return totalPages
    }

    /**
     * Extracts full textual transcripts from a simulated file so that on-device AI
     * can answer question tokens with exact prompt context matches!
     */
    fun getFullDocumentTranscript(fileName: String, pageCount: Int): String {
        val transcriptBuilder = StringBuilder()
        for (p in 1..pageCount) {
            transcriptBuilder.append("--- PAGE $p ---\n")
            val blocks = getPageContentsForFile(fileName, p)
            for (block in blocks) {
                transcriptBuilder.append(block.text).append(" ")
            }
            transcriptBuilder.append("\n\n")
        }
        return transcriptBuilder.toString()
    }
}
