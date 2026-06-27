package com.irozar.ipdfmaster.pdfeditorspike

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class FileCategory(val label: String) {
    WORK("Work"),
    STUDY("Study"),
    SCANNER("Scanner"),
    PERSONAL("Personal"),
    PRIVATE_SAFE("Private Safe");

    companion object {
        fun fromLabel(label: String) = entries.firstOrNull { it.label == label } ?: PERSONAL
    }
}

data class DocumentRecord(
    val id: String,
    val name: String,
    val path: String,
    val category: FileCategory,
    val favorite: Boolean,
    val lastOpened: Long
) {
    val isPrivate get() = category == FileCategory.PRIVATE_SAFE
}

class DocumentStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("pdfly_documents", Context.MODE_PRIVATE)
    private val docsDir = File(context.filesDir, "documents").apply { mkdirs() }

    var hideRecent: Boolean
        get() = prefs.getBoolean("hide_recent", false)
        set(value) = prefs.edit().putBoolean("hide_recent", value).apply()

    var privatePin: String
        get() = prefs.getString("private_pin", "1234") ?: "1234"
        set(value) = prefs.edit().putString("private_pin", value).apply()

    fun fileForNewImport(name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document.pdf" }
        return File(docsDir, "${System.currentTimeMillis()}_$safe")
    }

    fun upsert(file: File, name: String, category: FileCategory = FileCategory.PERSONAL): DocumentRecord {
        val current = records().firstOrNull { it.path == file.absolutePath }
        val record = DocumentRecord(
            id = current?.id ?: System.currentTimeMillis().toString(),
            name = name,
            path = file.absolutePath,
            category = category,
            favorite = current?.favorite ?: false,
            lastOpened = System.currentTimeMillis()
        )
        save(records().filterNot { it.path == file.absolutePath || it.id == record.id } + record)
        return record
    }

    fun records(): List<DocumentRecord> {
        val json = JSONArray(prefs.getString("records", "[]"))
        return buildList {
            for (i in 0 until json.length()) {
                val o = json.optJSONObject(i) ?: continue
                val path = o.optString("path")
                if (!File(path).exists()) continue
                add(
                    DocumentRecord(
                        id = o.optString("id"),
                        name = o.optString("name", File(path).name),
                        path = path,
                        category = FileCategory.fromLabel(o.optString("category", FileCategory.PERSONAL.label)),
                        favorite = o.optBoolean("favorite", false),
                        lastOpened = o.optLong("lastOpened", 0L)
                    )
                )
            }
        }.sortedByDescending { it.lastOpened }
    }

    fun touch(record: DocumentRecord) {
        save(records().map {
            if (it.id == record.id) it.copy(lastOpened = System.currentTimeMillis()) else it
        })
    }

    fun setFavorite(record: DocumentRecord, favorite: Boolean) {
        save(records().map { if (it.id == record.id) it.copy(favorite = favorite) else it })
    }

    fun setCategory(record: DocumentRecord, category: FileCategory) {
        save(records().map { if (it.id == record.id) it.copy(category = category) else it })
    }

    fun remove(record: DocumentRecord) {
        save(records().filterNot { it.id == record.id })
    }

    private fun save(records: List<DocumentRecord>) {
        val json = JSONArray()
        records.take(50).forEach {
            json.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("path", it.path)
                put("category", it.category.label)
                put("favorite", it.favorite)
                put("lastOpened", it.lastOpened)
            })
        }
        prefs.edit().putString("records", json.toString()).apply()
    }
}
