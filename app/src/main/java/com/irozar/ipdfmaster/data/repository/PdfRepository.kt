package com.irozar.ipdfmaster.data.repository

import com.irozar.ipdfmaster.data.dao.PdfDao
import com.irozar.ipdfmaster.data.entity.*
import kotlinx.coroutines.flow.Flow
import java.io.File

class PdfRepository(private val pdfDao: PdfDao) {

    val allFiles: Flow<List<PdfFile>> = pdfDao.getAllPdfFiles()
    val favoriteFiles: Flow<List<PdfFile>> = pdfDao.getFavoritePdfFiles()
    val privateFiles: Flow<List<PdfFile>> = pdfDao.getPrivatePdfFiles()
    val savedSignatures: Flow<List<SavedSignature>> = pdfDao.getAllSavedSignatures()
    val pageSourceMappings: Flow<List<PageSourceMapping>> = pdfDao.getAllPageSourceMappings()

    fun getFilesByCategory(category: String): Flow<List<PdfFile>> {
        return if (category == "All") allFiles else pdfDao.getPdfFilesByCategory(category)
    }

    fun searchFiles(query: String): Flow<List<PdfFile>> = pdfDao.searchPdfFiles(query)

    suspend fun getFileById(id: Int): PdfFile? = pdfDao.getPdfFileById(id)

    suspend fun insertFile(file: PdfFile): Long = pdfDao.insertPdfFile(file)

    suspend fun updateFile(file: PdfFile) = pdfDao.updatePdfFile(file)

    suspend fun deleteFileById(id: Int) {
        pdfDao.deletePdfFileById(id)
        pdfDao.deleteAnnotationsByFileId(id)
        pdfDao.deleteChatMessagesByFileId(id)
    }

    suspend fun toggleFavorite(id: Int, isFavorite: Boolean) {
        pdfDao.updateFavoriteStatus(id, if (isFavorite) 1 else 0)
    }

    suspend fun togglePrivate(id: Int, isPrivate: Boolean) {
        pdfDao.updatePrivacyStatus(id, if (isPrivate) 1 else 0)
    }

    // --- Annotation Actions ---
    fun getAnnotationsForFile(fileId: Int): Flow<List<AnnotationItem>> = pdfDao.getAnnotationsForFile(fileId)

    suspend fun addAnnotation(annotation: AnnotationItem): Long = pdfDao.insertAnnotation(annotation)

    suspend fun updateAnnotation(annotation: AnnotationItem) = pdfDao.updateAnnotation(annotation)

    suspend fun removeAnnotation(id: Int) = pdfDao.deleteAnnotationById(id)

    suspend fun clearAnnotations(fileId: Int) = pdfDao.deleteAnnotationsByFileId(fileId)

    // --- Page Source Mapping Actions ---
    suspend fun replacePageSourceMappings(targetFileName: String, sourcePages: List<Pair<String, Int>>) {
        val cleanName = if (targetFileName.endsWith(".pdf")) targetFileName else "$targetFileName.pdf"
        val rows = sourcePages.mapIndexed { index, source ->
            PageSourceMapping(
                targetFileName = cleanName,
                pageIndex = index,
                sourceFileName = if (source.first.endsWith(".pdf")) source.first else "${source.first}.pdf",
                sourcePageNumber = source.second
            )
        }
        pdfDao.replacePageSourceMappings(cleanName, rows)
    }

    // --- Signatures ---
    suspend fun saveSignature(pointsJson: String): Long = pdfDao.insertSignature(SavedSignature(pointsJson = pointsJson))

    suspend fun deleteSignature(id: Int) = pdfDao.deleteSignatureById(id)

    // --- Chat Messages ---
    fun getChatMessages(fileId: Int): Flow<List<ChatMessage>> = pdfDao.getChatMessagesForFile(fileId)

    suspend fun addChatMessage(fileId: Int, sender: String, content: String): Long {
        return pdfDao.insertChatMessage(ChatMessage(fileId = fileId, sender = sender, content = content))
    }

    suspend fun clearChatMessages(fileId: Int) = pdfDao.deleteChatMessagesByFileId(fileId)

    // --- Pre-populate DB with premium mock contents ---
    suspend fun populateDefaultDocsIfEmpty() {
        // We will run this check. If empty, create the precise items matching the mockups!
        // These will reference simulated PDF documents that are completely interactable in our rich viewer.
        val defaultFiles = listOf(
            PdfFile(
                name = "Project_Proposal.pdf",
                filePath = "assets/Project_Proposal.pdf",
                sizeInBytes = 2516582, // 2.4 MB
                pageCount = 14,
                category = "Work",
                isFavorite = true
            ),
            PdfFile(
                name = "Math_Notes.pdf",
                filePath = "assets/Math_Notes.pdf",
                sizeInBytes = 1153434, // 1.1 MB
                pageCount = 6,
                category = "Study",
                isFavorite = false
            ),
            PdfFile(
                name = "Contract.pdf",
                filePath = "assets/Contract.pdf",
                sizeInBytes = 1887436, // 1.8 MB
                pageCount = 8,
                category = "Work",
                isFavorite = true
            ),
            PdfFile(
                name = "ID_Card.pdf",
                filePath = "assets/ID_Card.pdf",
                sizeInBytes = 786432, // 768 KB
                pageCount = 2,
                category = "Scanner",
                isFavorite = false
            ),
            PdfFile(
                name = "Book_Sample.pdf",
                filePath = "assets/Book_Sample.pdf",
                sizeInBytes = 5872025, // 5.6 MB
                pageCount = 32,
                category = "Study",
                isFavorite = false
            ),
            PdfFile(
                name = "Notes.pdf",
                filePath = "assets/Notes.pdf",
                sizeInBytes = 1258291, // 1.2 MB
                pageCount = 4,
                category = "Study",
                isFavorite = false
            )
        )

        // Count existing files in db (via repository setup)
        // Check if DB lacks files to inject them
    }
}
