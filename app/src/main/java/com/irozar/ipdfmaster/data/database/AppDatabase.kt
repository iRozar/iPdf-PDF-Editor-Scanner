package com.irozar.ipdfmaster.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.irozar.ipdfmaster.data.dao.PdfDao
import com.irozar.ipdfmaster.data.entity.*

@Database(
    entities = [PdfFile::class, SavedSignature::class, AnnotationItem::class, PageSourceMapping::class, ChatMessage::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pdfDao(): PdfDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pdf_master_database"
                )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `page_source_mappings` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `targetFileName` TEXT NOT NULL,
                        `pageIndex` INTEGER NOT NULL,
                        `sourceFileName` TEXT NOT NULL,
                        `sourcePageNumber` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
