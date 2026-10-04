package ir.hirmand.phonebridge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalQueueDb(context: Context) : SQLiteOpenHelper(context, "phone_bridge_queue.db", null, 2) {
    data class QueueItem(
        val id: Long,
        val payload: String,
        val attempts: Int,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE queue (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "payload TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "attempts INTEGER NOT NULL DEFAULT 0, " +
                "next_attempt_at INTEGER NOT NULL DEFAULT 0, " +
                "last_error TEXT" +
                ")"
        )
        createIndexes(db)
        createDeadLetterTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE queue ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE queue ADD COLUMN next_attempt_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE queue ADD COLUMN last_error TEXT")
            createIndexes(db)
            createDeadLetterTable(db)
        }
    }

    fun enqueue(payload: String): Long =
        writableDatabase.insert(
            "queue",
            null,
            ContentValues().apply {
                put("payload", payload)
                put("created_at", System.currentTimeMillis())
                put("attempts", 0)
                put("next_attempt_at", 0)
            }
        )

    fun peek(limit: Int = 20, now: Long = System.currentTimeMillis()): List<QueueItem> {
        val out = mutableListOf<QueueItem>()
        readableDatabase.query(
            "queue",
            arrayOf("id", "payload", "attempts"),
            "next_attempt_at <= ?",
            arrayOf(now.toString()),
            null,
            null,
            "id ASC",
            limit.coerceIn(1, 100).toString(),
        ).use { c ->
            val idIx = c.getColumnIndexOrThrow("id")
            val payloadIx = c.getColumnIndexOrThrow("payload")
            val attemptsIx = c.getColumnIndexOrThrow("attempts")
            while (c.moveToNext()) {
                out += QueueItem(
                    id = c.getLong(idIx),
                    payload = c.getString(payloadIx),
                    attempts = c.getInt(attemptsIx),
                )
            }
        }
        return out
    }

    fun delete(id: Long) {
        writableDatabase.delete("queue", "id = ?", arrayOf(id.toString()))
    }

    /**
     * Records a transient failure. Returns true when the item has been moved
     * to the dead-letter table after reaching maxAttempts.
     */
    fun markFailure(id: Long, error: String, maxAttempts: Int = 5): Boolean {
        val db = writableDatabase
        var deadLettered = false
        db.beginTransaction()
        try {
            val currentAttempts = db.query(
                "queue",
                arrayOf("attempts", "payload", "created_at"),
                "id = ?",
                arrayOf(id.toString()),
                null,
                null,
                null,
                "1",
            ).use { c ->
                if (!c.moveToFirst()) return false
                Triple(c.getInt(0), c.getString(1), c.getLong(2))
            }

            val nextAttempts = currentAttempts.first + 1
            if (nextAttempts >= maxAttempts.coerceAtLeast(1)) {
                val now = System.currentTimeMillis()
                db.insert(
                    "dead_letters",
                    null,
                    ContentValues().apply {
                        put("original_queue_id", id)
                        put("payload", currentAttempts.second)
                        put("created_at", currentAttempts.third)
                        put("failed_at", now)
                        put("attempts", nextAttempts)
                        put("last_error", error.take(500))
                    }
                )
                db.delete("queue", "id = ?", arrayOf(id.toString()))
                deadLettered = true
            } else {
                val delay = backoffMillis(nextAttempts)
                db.update(
                    "queue",
                    ContentValues().apply {
                        put("attempts", nextAttempts)
                        put("next_attempt_at", System.currentTimeMillis() + delay)
                        put("last_error", error.take(500))
                    },
                    "id = ?",
                    arrayOf(id.toString()),
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return deadLettered
    }

    fun retryDeadLetters(limit: Int = 100): Int {
        val db = writableDatabase
        var restored = 0
        db.beginTransaction()
        try {
            val ids = mutableListOf<Long>()
            db.query(
                "dead_letters",
                arrayOf("id"),
                null,
                null,
                null,
                null,
                "failed_at ASC",
                limit.coerceIn(1, 500).toString(),
            ).use { c ->
                while (c.moveToNext()) ids += c.getLong(0)
            }

            for (id in ids) {
                db.query(
                    "dead_letters",
                    arrayOf("payload", "created_at"),
                    "id = ?",
                    arrayOf(id.toString()),
                    null,
                    null,
                    null,
                    "1",
                ).use { c ->
                    if (!c.moveToFirst()) return@use
                    val queued = db.insert(
                        "queue",
                        null,
                        ContentValues().apply {
                            put("payload", c.getString(0))
                            put("created_at", c.getLong(1))
                            put("attempts", 0)
                            put("next_attempt_at", 0)
                        }
                    )
                    if (queued != -1L) {
                        db.delete("dead_letters", "id = ?", arrayOf(id.toString()))
                        restored++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return restored
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM queue", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun countDeadLetters(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM dead_letters", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun hasDelayedItems(now: Long = System.currentTimeMillis()): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM queue WHERE next_attempt_at > ? LIMIT 1",
            arrayOf(now.toString()),
        ).use { it.moveToFirst() }

    fun clear() {
        writableDatabase.delete("queue", null, null)
    }

    fun clearDeadLetters() {
        writableDatabase.delete("dead_letters", null, null)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS queue_ready_idx ON queue(next_attempt_at,id)")
    }

    private fun createDeadLetterTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS dead_letters (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "original_queue_id INTEGER NOT NULL, " +
                "payload TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "failed_at INTEGER NOT NULL, " +
                "attempts INTEGER NOT NULL, " +
                "last_error TEXT" +
                ")"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS dead_letters_failed_idx ON dead_letters(failed_at,id)")
    }

    private fun backoffMillis(attempt: Int): Long =
        (30_000L * (1L shl (attempt - 1).coerceIn(0, 4))).coerceAtMost(15 * 60_000L)
}
