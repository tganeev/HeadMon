package com.headmon.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "session_logs")
data class SessionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, // <-- Изменили PrimaryKey
    val patientName: String,                           // <-- Добавили имя
    val timestamp: Long,
    val maxDeviation: Float,
    val isDeviated: Boolean
)

@Dao
interface SessionLogDao {
    @Insert
    suspend fun insert(log: SessionLog)

    // Получить все логи (для общего списка)
    @Query("SELECT * FROM session_logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<SessionLog>

    // Получить уникальные имена пациентов для фильтра
    @Query("SELECT DISTINCT patientName FROM session_logs")
    suspend fun getAllPatientNames(): List<String>

    // Получить логи по конкретному пациенту
    @Query("SELECT * FROM session_logs WHERE patientName = :name ORDER BY timestamp ASC")
    suspend fun getLogsForPatient(name: String): List<SessionLog>

    // Для агрегации раз в минуту мы ищем последнюю запись пациента
    @Query("SELECT * FROM session_logs WHERE patientName = :name ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastLogForPatient(name: String): SessionLog?

    @Query("DELETE FROM session_logs WHERE patientName = :name")
    suspend fun clearPatientLogs(name: String)
}

@Database(entities = [SessionLog::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionLogDao(): SessionLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "headmon_database"
                )
                    .fallbackToDestructiveMigration() // <-- Сносим старую БД при обновлении
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}