// SensorBufferStore.kt
package com.example.watchstreamer.sensor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable, disk-backed FIFO queue of sensor readings.
 *
 * Every reading is written here first. A separate sender drains the queue in
 * timestamp order and only removes a row once it has been sent successfully.
 * If sending fails (radio asleep, no route to host, etc.) rows just
 * accumulate and get flushed once connectivity returns — nothing is lost,
 * and gaps get backfilled automatically.
 */
class SensorBufferStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TS INTEGER NOT NULL,
                $COL_ACCEL_X REAL NOT NULL,
                $COL_ACCEL_Y REAL NOT NULL,
                $COL_ACCEL_Z REAL NOT NULL,
                $COL_GYRO_X REAL NOT NULL,
                $COL_GYRO_Y REAL NOT NULL,
                $COL_GYRO_Z REAL NOT NULL,
                $COL_HR REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_id ON $TABLE($COL_ID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /** Appends a reading to the queue. Safe to call from any thread. */
    fun enqueue(data: SensorData) {
        synchronized(this) {
            val values = ContentValues().apply {
                put(COL_TS, data.timestamp)
                put(COL_ACCEL_X, data.accelX)
                put(COL_ACCEL_Y, data.accelY)
                put(COL_ACCEL_Z, data.accelZ)
                put(COL_GYRO_X, data.gyroX)
                put(COL_GYRO_Y, data.gyroY)
                put(COL_GYRO_Z, data.gyroZ)
                put(COL_HR, data.heartRate)
            }
            writableDatabase.insert(TABLE, null, values)
        }
    }

    /** Oldest-first batch of up to [limit] unsent readings, paired with their row id. */
    fun peekBatch(limit: Int): List<Pair<Long, SensorData>> {
        synchronized(this) {
            val out = mutableListOf<Pair<Long, SensorData>>()
            readableDatabase.query(
                TABLE, null, null, null, null, null,
                "$COL_ID ASC", limit.toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID))
                    val data = SensorData(
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TS)),
                        accelX = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_ACCEL_X)),
                        accelY = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_ACCEL_Y)),
                        accelZ = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_ACCEL_Z)),
                        gyroX = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_GYRO_X)),
                        gyroY = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_GYRO_Y)),
                        gyroZ = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_GYRO_Z)),
                        heartRate = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_HR))
                    )
                    out.add(id to data)
                }
            }
            return out
        }
    }

    /** Removes every row up to and including [id] — used after a batch sends successfully. */
    fun removeUpTo(id: Long) {
        synchronized(this) {
            writableDatabase.delete(TABLE, "$COL_ID <= ?", arrayOf(id.toString()))
        }
    }

    /** Number of readings currently waiting to be sent. */
    fun pendingCount(): Long {
        synchronized(this) {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
                it.moveToFirst()
                return it.getLong(0)
            }
        }
    }

    /** Drops everything in the queue (e.g. a manual reset). */
    fun clear() {
        synchronized(this) { writableDatabase.delete(TABLE, null, null) }
    }

    companion object {
        private const val DB_NAME = "sensor_buffer.db"
        private const val DB_VERSION = 1
        private const val TABLE = "readings"
        private const val COL_ID = "id"
        private const val COL_TS = "ts"
        private const val COL_ACCEL_X = "accel_x"
        private const val COL_ACCEL_Y = "accel_y"
        private const val COL_ACCEL_Z = "accel_z"
        private const val COL_GYRO_X = "gyro_x"
        private const val COL_GYRO_Y = "gyro_y"
        private const val COL_GYRO_Z = "gyro_z"
        private const val COL_HR = "heart_rate"

        @Volatile private var instance: SensorBufferStore? = null

        fun get(context: Context): SensorBufferStore =
            instance ?: synchronized(this) {
                instance ?: SensorBufferStore(context).also { instance = it }
            }
    }
}