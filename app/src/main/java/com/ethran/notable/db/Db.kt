package com.ethran.notable.db

import android.content.Context
import android.os.Environment
import androidx.room.*
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ethran.notable.utils.getDbDir
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Date


class Converters {
    @TypeConverter
    fun fromListString(value: List<String>) = Json.encodeToString(value)

    @TypeConverter
    fun toListString(value: String) = Json.decodeFromString<List<String>>(value)

    @TypeConverter
    fun fromListPoint(value: List<StrokePoint>) = Json.encodeToString(value)

    @TypeConverter
    fun toListPoint(value: String) = Json.decodeFromString<List<StrokePoint>>(value)

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}

// Manual migration from 30 to 32 (skipping 31 to avoid auto-migration issues)
val MIGRATION_30_32 = object : Migration(30, 32) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Step 1: Add the backgroundType column with default value
        database.execSQL("ALTER TABLE Page ADD COLUMN backgroundType TEXT NOT NULL DEFAULT 'native'")
        
        // Step 2: Create a new table with the correct schema
        database.execSQL("""
            CREATE TABLE Page_new (
                id TEXT PRIMARY KEY NOT NULL,
                scroll INTEGER NOT NULL,
                notebookId TEXT,
                background TEXT NOT NULL DEFAULT 'blank',
                backgroundType TEXT NOT NULL DEFAULT 'native',
                parentFolderId TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(parentFolderId) REFERENCES Folder(id) ON DELETE CASCADE,
                FOREIGN KEY(notebookId) REFERENCES Notebook(id) ON DELETE CASCADE
            )
        """.trimIndent())
        
        // Step 3: Copy data from old table to new table, renaming nativeTemplate to background
        database.execSQL("""
            INSERT INTO Page_new (id, scroll, notebookId, background, backgroundType, parentFolderId, createdAt, updatedAt)
            SELECT id, scroll, notebookId, 
                   CASE WHEN nativeTemplate IS NOT NULL THEN nativeTemplate ELSE 'blank' END as background,
                   'native' as backgroundType,
                   parentFolderId, createdAt, updatedAt
            FROM Page
        """.trimIndent())
        
        // Step 4: Drop old table
        database.execSQL("DROP TABLE Page")
        
        // Step 5: Rename new table to original name
        database.execSQL("ALTER TABLE Page_new RENAME TO Page")
        
        // Step 6: Recreate indices
        database.execSQL("CREATE INDEX index_Page_notebookId ON Page(notebookId)")
        database.execSQL("CREATE INDEX index_Page_parentFolderId ON Page(parentFolderId)")
        
        android.util.Log.d("Migration", "Manual migration 30->32 completed successfully")
    }
}

// Migration from 31 to 32 (for devices that already have v31)
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Check if we need to fix the schema (some v31 databases might have old schema)
        val cursor = database.query("PRAGMA table_info(Page)")
        val columnNames = mutableListOf<String>()
        
        while (cursor.moveToNext()) {
            val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            columnNames.add(columnName)
        }
        cursor.close()
        
        if (columnNames.contains("nativeTemplate")) {
            // This v31 database has the OLD schema, need to fix it
            android.util.Log.d("Migration", "v31 database has old schema, applying full migration")
            
            // Add the backgroundType column with default value
            database.execSQL("ALTER TABLE Page ADD COLUMN backgroundType TEXT NOT NULL DEFAULT 'native'")
            
            // Create a new table with the correct schema
            database.execSQL("""
                CREATE TABLE Page_new (
                    id TEXT PRIMARY KEY NOT NULL,
                    scroll INTEGER NOT NULL,
                    notebookId TEXT,
                    background TEXT NOT NULL DEFAULT 'blank',
                    backgroundType TEXT NOT NULL DEFAULT 'native',
                    parentFolderId TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(parentFolderId) REFERENCES Folder(id) ON DELETE CASCADE,
                    FOREIGN KEY(notebookId) REFERENCES Notebook(id) ON DELETE CASCADE
                )
            """.trimIndent())
            
            // Copy data from old table to new table, renaming nativeTemplate to background
            database.execSQL("""
                INSERT INTO Page_new (id, scroll, notebookId, background, backgroundType, parentFolderId, createdAt, updatedAt)
                SELECT id, scroll, notebookId, 
                       CASE WHEN nativeTemplate IS NOT NULL THEN nativeTemplate ELSE 'blank' END as background,
                       'native' as backgroundType,
                       parentFolderId, createdAt, updatedAt
                FROM Page
            """.trimIndent())
            
            // Drop old table
            database.execSQL("DROP TABLE Page")
            
            // Rename new table to original name
            database.execSQL("ALTER TABLE Page_new RENAME TO Page")
            
            // Recreate indices
            database.execSQL("CREATE INDEX index_Page_notebookId ON Page(notebookId)")
            database.execSQL("CREATE INDEX index_Page_parentFolderId ON Page(parentFolderId)")
            
            android.util.Log.d("Migration", "Manual migration 31->32 completed successfully (fixed schema)")
        } else {
            // This v31 database already has the correct schema
            android.util.Log.d("Migration", "Manual migration 31->32 completed successfully (no schema changes needed)")
        }
    }
}

// Downgrade migration from 33 to 32
val MIGRATION_33_32 = object : Migration(33, 32) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // For downgrade, we don't need to change the schema
        // Version 33 likely has the same schema as v32
        android.util.Log.d("Migration", "Downgrade migration 33->32 completed (no schema changes)")
    }
}

@Database(
    entities = [Folder::class, Notebook::class, Page::class, Stroke::class, Image::class, Kv::class],
    version = 32,
    autoMigrations = [
        AutoMigration(19, 20),
        AutoMigration(20, 21),
        AutoMigration(21, 22),
        AutoMigration(23, 24),
        AutoMigration(24, 25),
        AutoMigration(25, 26),
        AutoMigration(26, 27),
        AutoMigration(27, 28),
        AutoMigration(28, 29),
        AutoMigration(29, 30),
        // Comment out auto-migration 30->31 and 31->32 to use manual migrations
        // AutoMigration(30,  31, spec = AutoMigration30to31::class),
        // AutoMigration(31, 32, spec = AutoMigration31to32::class)

    ], exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun kvDao(): KvDao
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun ImageDao(): ImageDao

    companion object {
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            if (INSTANCE == null) {
                synchronized(this) {
                    val dbDir = getDbDir()
                    val dbFile = File(dbDir, "app_database")

                    // Use Room to build the database
                    INSTANCE =
                        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
                            // .allowMainThreadQueries() // REMOVED: This was causing ANRs and performance issues
                            .addMigrations(MIGRATION_16_17, MIGRATION_17_18, MIGRATION_22_23, MIGRATION_30_32, MIGRATION_31_32, MIGRATION_33_32)
                            .build()

                }
            }
            return INSTANCE!!
        }
    }
}