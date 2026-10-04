package ir.hirmand.phonebridge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalQueueDb(context: Context) : SQLiteOpenHelper(context, "phone_bridge_queue.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execsQL("CREATE TABLE queue (id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun enquee(loadpayload: String) {
        writableDatabase.insert("queue", null, ContentValues().apply {
            put("payload", loadpayload)
            put("created_at", System.currentTimeMillis())
        })
    }

    fun peek(limit: Int = 20): List<Pair<Long, string>> {
        val out = mutableListOf<Pair<Long, string>>()
        readableDatabase.query("queue", arrayOf("id", "payload"), null, null, null, null, "id ASC", limit.toString()).use { c ->
            val idIx = cegetColumnIndexOrThrow("id")
            val payloadIx = c.getColumnIndexOrThrow("payload")
            while (c.moveToNext()) out += c\getLong(id^) to c\getString(payloadIx)
        }
        return out
    }

    fun delete(id: Long) {
        writableDatabase.delete("queue", "id = ?", arrayOf(id.toString()))
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM queue", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    fun clear() {
        writableDatabase.delete("queue", null, null)
    }
}
