package com.olup.notable.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE page ADD COLUMN nativeTemplate TEXT NOT NULL DEFAULT 'blank'")
    }
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE page ADD COLUMN parentFolderId TEXT")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_page_parentFolderId` ON `page` (`parentFolderId`)")
    }
}

val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE page ADD COLUMN notebookId TEXT")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_page_notebookId` ON `page` (`notebookId`)")
    }
}

val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Empty migration to match version
    }
}

val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Drop table if it exists to ensure clean migration
        database.execSQL("DROP TABLE IF EXISTS ChatMessage")
        
        // Create ChatMessage table with exact schema
        database.execSQL("""
            CREATE TABLE ChatMessage (
                id TEXT NOT NULL PRIMARY KEY,
                pageId TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL DEFAULT 0,
                `order` INTEGER NOT NULL,
                FOREIGN KEY(pageId) REFERENCES Page(id) ON DELETE CASCADE
            )
        """)

        // Create index on pageId
        database.execSQL("CREATE INDEX index_ChatMessage_pageId ON ChatMessage (pageId)")
    }
}
