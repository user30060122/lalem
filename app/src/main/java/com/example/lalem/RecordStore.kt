package com.example.lalem

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class PendingRecord(
    val entry: Entry,
    val remoteId: String?,
    val revision: Long,
    val deleted: Boolean
)

internal data class LocalRecords(val entries: List<Entry>, val pendingCount: Int)

internal class RecordStore(context: Context) : SQLiteOpenHelper(context, "lalem_records.db", null, 1) {
    private val changes = MutableStateFlow(0L)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE records (
                owner TEXT NOT NULL,
                id TEXT NOT NULL,
                remote_id TEXT,
                time INTEGER NOT NULL,
                type TEXT NOT NULL,
                weight REAL,
                revision INTEGER NOT NULL DEFAULT 0,
                dirty INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (owner, id)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun observe(owner: String) = changes.map {
        withContext(Dispatchers.IO) {
            readableDatabase.transaction {
                val entries = read("owner = ? AND deleted = 0", owner).map { it.entry }
                val pending = rawQuery("SELECT count(*) FROM records WHERE owner = ? AND dirty = 1", arrayOf(owner))
                    .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                LocalRecords(entries, pending)
            }
        }
    }

    suspend fun save(owner: String, entry: Entry) = mutate {
        if (entry.id.isBlank()) {
            insertOrThrow("records", null, values(entry).apply {
                put("id", UUID.randomUUID().toString())
                put("owner", owner)
                put("dirty", 1)
            })
        } else {
            val current = read("owner = ? AND id = ? AND deleted = 0", owner, entry.id).firstOrNull()
                ?: error("这条记录已不存在")
            update("records", values(entry).apply {
                put("revision", current.revision + 1)
                put("dirty", 1)
            }, "owner = ? AND id = ?", arrayOf(owner, entry.id))
        }
    }

    suspend fun delete(owner: String, id: String) = mutate {
        if (owner == GUEST) {
            delete("records", "owner = ? AND id = ?", arrayOf(owner, id))
        } else {
            execSQL(
                "UPDATE records SET deleted = 1, dirty = 1, revision = revision + 1 WHERE owner = ? AND id = ?",
                arrayOf(owner, id)
            )
        }
    }

    suspend fun claimGuestRecords(owner: String) = mutate {
        execSQL("UPDATE records SET owner = ? WHERE owner = ?", arrayOf(owner, GUEST))
    }

    suspend fun pending(owner: String): List<PendingRecord> = withContext(Dispatchers.IO) {
        readableDatabase.read("owner = ? AND dirty = 1", owner)
    }

    suspend fun entriesReadyToShare(owner: String): List<Entry>? = withContext(Dispatchers.IO) {
        readableDatabase.transaction {
            val hasPending = rawQuery("SELECT 1 FROM records WHERE owner = ? AND dirty = 1 LIMIT 1", arrayOf(owner))
                .use { it.moveToFirst() }
            if (hasPending) null else read("owner = ? AND deleted = 0", owner).map { it.entry }
        }
    }

    suspend fun acknowledge(owner: String, record: PendingRecord, remoteId: String?) = mutate {
        val target = arrayOf(owner, record.entry.id, record.revision.toString())
        if (record.deleted) {
            delete("records", "owner = ? AND id = ? AND revision = ?", target)
        } else {
            val matched = update("records", ContentValues().apply {
                put("dirty", 0)
                put("remote_id", remoteId)
            }, "owner = ? AND id = ? AND revision = ?", target)
            if (matched == 0) {
                // An edit or deletion made during upload still needs the server ID.
                update("records", ContentValues().apply { put("remote_id", remoteId) },
                    "owner = ? AND id = ?", arrayOf(owner, record.entry.id))
            }
        }
    }

    suspend fun merge(owner: String, cloud: List<CloudRecord>) = mutate {
        val local = read("owner = ?", owner)
        val byRemote = local.filter { it.remoteId != null }.associateBy { it.remoteId }
        val byId = local.associateBy { it.entry.id }
        val matchedLocal = mutableSetOf<String>()
        cloud.forEach { record ->
            val existing = byRemote[record.entry.id] ?: byId[record.clientId]
            val content = values(record.entry).apply { put("remote_id", record.entry.id) }
            if (existing == null) {
                insertOrThrow("records", null, content.apply {
                    put("owner", owner)
                    put("id", UUID.randomUUID().toString())
                })
            } else {
                matchedLocal += existing.entry.id
                if (existing.remoteId != record.entry.id) {
                    // A create may have reached Bmob before the app received its response.
                    update("records", ContentValues().apply { put("remote_id", record.entry.id) },
                        "owner = ? AND id = ?", arrayOf(owner, existing.entry.id))
                }
                update("records", content, "owner = ? AND id = ? AND dirty = 0",
                    arrayOf(owner, existing.entry.id))
            }
        }
        local.forEach { existing ->
            if (existing.remoteId != null && existing.entry.id !in matchedLocal) {
                delete("records", "owner = ? AND id = ? AND dirty = 0", arrayOf(owner, existing.entry.id))
            }
        }
    }

    private suspend fun mutate(action: SQLiteDatabase.() -> Unit) = withContext(Dispatchers.IO) {
        writableDatabase.transaction(body = action)
        changes.update { it + 1 }
    }

    private fun SQLiteDatabase.read(where: String, vararg args: String): List<PendingRecord> =
        rawQuery(
            "SELECT id, remote_id, time, type, weight, revision, deleted FROM records WHERE $where ORDER BY time DESC, id",
            args
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.record()) } }

    private fun Cursor.record() = PendingRecord(
        Entry(getString(0), getLong(2), RecordType.from(getString(3)), if (isNull(4)) null else getDouble(4)),
        getString(1), getLong(5), getInt(6) != 0
    )

    private fun values(entry: Entry) = ContentValues().apply {
        put("time", entry.time)
        put("type", entry.type.name)
        put("weight", entry.weight)
    }

    companion object {
        const val GUEST = "guest-local"
    }
}
