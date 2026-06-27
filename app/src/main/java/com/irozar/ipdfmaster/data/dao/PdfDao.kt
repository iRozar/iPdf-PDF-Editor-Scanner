package com.irozar.ipdfmaster.data.dao

import androidx.room.*
import com.irozar.ipdfmaster.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfDao {

    // --- PDF Files ---
    @Query("SELECT * FROM pdf_files ORDER BY createdAt DESC")
    fun getAllPdfFiles(): Flow<List<PdfFile>>

    @Query("SELECT * FROM pdf_files WHERE isFavorite = 1 ORDER BY createdAt DESC")
    fun getFavoritePdfFiles(): Flow<List<PdfFile>>

    @Query("SELECT * FROM pdf_files WHERE isPrivate = 1 ORDER BY createdAt DESC")
    fun getPrivatePdfFiles(): Flow<List<PdfFile>>

    @Query("SELECT * FROM pdf_files WHERE category = :category ORDER BY createdAt DESC")
    fun getPdfFilesByCategory(category: String): Flow<List<PdfFile>>

    @Query("SELECT * FROM pdf_files WHERE name LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchPdfFiles(query: String): Flow<List<PdfFile>>

    @Query("SELECT * FROM pdf_files WHERE id = :id LIMIT 1")
    suspend fun getPdfFileById(id: Int): PdfFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPdfFile(pdfFile: PdfFile): Long

    @Update
    suspend fun updatePdfFile(pdfFile: PdfFile)

    @Query("DELETE FROM pdf_files WHERE id = :id")
    suspend fun deletePdfFileById(id: Int)

    /** Removes the built-in demo/sample files (their paths start with "assets/"). */
    @Query("DELETE FROM pdf_files WHERE filePath LIKE 'assets/%'")
    suspend fun deleteSeededSampleFiles()

    @Query("UPDATE pdf_files SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavoriteStatus(id: Int, isFavorite: Int)

    @Query("UPDATE pdf_files SET isPrivate = :isPrivate WHERE id = :id")
    suspend fun updatePrivacyStatus(id: Int, isPrivate: Int)

    // --- Page Source Mappings ---
    @Query("SELECT * FROM page_source_mappings ORDER BY targetFileName ASC, pageIndex ASC")
    fun getAllPageSourceMappings(): Flow<List<PageSourceMapping>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPageSourceMappings(mappings: List<PageSourceMapping>)

    @Query("DELETE FROM page_source_mappings WHERE targetFileName = :targetFileName")
    suspend fun deletePageSourceMappings(targetFileName: String)

    @Transaction
    suspend fun replacePageSourceMappings(targetFileName: String, mappings: List<PageSourceMapping>) {
        deletePageSourceMappings(targetFileName)
        if (mappings.isNotEmpty()) {
            insertPageSourceMappings(mappings)
        }
    }

    // --- Saved Signatures ---
    @Query("SELECT * FROM saved_signatures ORDER BY createdAt DESC")
    fun getAllSavedSignatures(): Flow<List<SavedSignature>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignature(signature: SavedSignature): Long

    @Query("DELETE FROM saved_signatures WHERE id = :id")
    suspend fun deleteSignatureById(id: Int)


    // --- Annotation Items ---
    @Query("SELECT * FROM annotation_items WHERE fileId = :fileId ORDER BY id ASC")
    fun getAnnotationsForFile(fileId: Int): Flow<List<AnnotationItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnotation(annotation: AnnotationItem): Long

    @Update
    suspend fun updateAnnotation(annotation: AnnotationItem)

    @Query("DELETE FROM annotation_items WHERE id = :id")
    suspend fun deleteAnnotationById(id: Int)

    @Query("DELETE FROM annotation_items WHERE fileId = :fileId")
    suspend fun deleteAnnotationsByFileId(fileId: Int)


    // --- Chat Messages ---
    @Query("SELECT * FROM chat_messages WHERE fileId = :fileId ORDER BY timestamp ASC")
    fun getChatMessagesForFile(fileId: Int): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(message: ChatMessage): Long

    @Query("DELETE FROM chat_messages WHERE fileId = :fileId")
    suspend fun deleteChatMessagesByFileId(fileId: Int)
}
