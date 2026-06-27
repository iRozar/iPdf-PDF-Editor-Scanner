package com.irozar.ipdfmaster.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pdf_files")
data class PdfFile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val filePath: String,
    val sizeInBytes: Long,
    val pageCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isPrivate: Boolean = false,
    val thumbnailPath: String? = null,
    val category: String = "All" // "Work", "Study", "Finance", "Scanner" etc.
)

@Entity(tableName = "saved_signatures")
data class SavedSignature(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pointsJson: String, // SVG-like raw float points formatted as JSON string
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "annotation_items")
data class AnnotationItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileId: Int,
    val pageIndex: Int,
    val type: String, // "draw", "highlight", "underline", "strike", "text", "signature", "shape"
    val color: Int, // Hex ARGB format
    val thickness: Float = 4f,
    val pointsJson: String? = null, // Path coordinates Json for draw/highlight
    val textValue: String? = null, // For text boxes
    val paramX: Float = 0f, // Normalized x-coordinate (0f to 1f relative to page width)
    val paramY: Float = 0f, // Normalized y-coordinate (0f to 1f relative to page height)
    val paramWidth: Float = 0f,
    val paramHeight: Float = 0f,
    val shapeType: String? = null // "rectangle", "circle", "arrow", "line"
)

@Entity(tableName = "page_source_mappings")
data class PageSourceMapping(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val targetFileName: String,
    val pageIndex: Int,
    val sourceFileName: String,
    val sourcePageNumber: Int
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileId: Int,
    val sender: String, // "user", "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
