package com.browdika.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryItem(
    val id: Long,
    val url: String,
    val title: String,
    val visitedAt: Long,
    val visitCount: Int
)

class HistoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_URL TEXT NOT NULL UNIQUE,
                $COLUMN_TITLE TEXT NOT NULL DEFAULT '',
                $COLUMN_VISITED_AT INTEGER NOT NULL,
                $COLUMN_VISIT_COUNT INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_history_visited_at ON $TABLE_NAME ($COLUMN_VISITED_AT DESC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // First version of the history database.
    }

    fun recordVisit(url: String, title: String?) {
        if (url.isBlank()) return

        val now = System.currentTimeMillis()
        val displayTitle = title?.trim().orEmpty()
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_VISITED_AT, now)
            if (displayTitle.isNotEmpty()) {
                put(COLUMN_TITLE, displayTitle)
            }
        }

        val updated = db.update(TABLE_NAME, values, "$COLUMN_URL = ?", arrayOf(url))
        if (updated > 0) {
            db.execSQL(
                "UPDATE $TABLE_NAME SET $COLUMN_VISIT_COUNT = $COLUMN_VISIT_COUNT + 1 " +
                    "WHERE $COLUMN_URL = ?",
                arrayOf(url)
            )
        } else {
            values.put(COLUMN_URL, url)
            values.put(COLUMN_TITLE, displayTitle)
            values.put(COLUMN_VISIT_COUNT, 1)
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun getHistory(limit: Int = 500): List<HistoryItem> {
        val items = mutableListOf<HistoryItem>()
        readableDatabase.query(
            TABLE_NAME,
            arrayOf(COLUMN_ID, COLUMN_URL, COLUMN_TITLE, COLUMN_VISITED_AT, COLUMN_VISIT_COUNT),
            null,
            null,
            null,
            null,
            "$COLUMN_VISITED_AT DESC",
            limit.toString()
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val urlIndex = cursor.getColumnIndexOrThrow(COLUMN_URL)
            val titleIndex = cursor.getColumnIndexOrThrow(COLUMN_TITLE)
            val visitedIndex = cursor.getColumnIndexOrThrow(COLUMN_VISITED_AT)
            val countIndex = cursor.getColumnIndexOrThrow(COLUMN_VISIT_COUNT)

            while (cursor.moveToNext()) {
                items.add(
                    HistoryItem(
                        id = cursor.getLong(idIndex),
                        url = cursor.getString(urlIndex),
                        title = cursor.getString(titleIndex) ?: "",
                        visitedAt = cursor.getLong(visitedIndex),
                        visitCount = cursor.getInt(countIndex)
                    )
                )
            }
        }
        return items
    }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE_NAME, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    fun clear() {
        writableDatabase.delete(TABLE_NAME, null, null)
    }

    companion object {
        private const val DATABASE_NAME = "browdika_history.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "history"
        private const val COLUMN_ID = "id"
        private const val COLUMN_URL = "url"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_VISITED_AT = "visited_at"
        private const val COLUMN_VISIT_COUNT = "visit_count"
    }
}
