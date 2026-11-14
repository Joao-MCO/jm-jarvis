package com.jm.jarvis.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalTime

@Database(entities = [Habit::class, HabitLog::class, Reminder::class, MemoryFact::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao
    abstract fun reminderDao(): ReminderDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        /** v2: lembretes avulsos e memória. Só cria tabelas novas; nada existente é tocado. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT NOT NULL, `at` INTEGER NOT NULL, `done` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_at` ON `reminders` (`at`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_facts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "jarvis.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE done = 0 ORDER BY at")
    fun observePending(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE done = 0 ORDER BY at")
    suspend fun pending(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun reminder(id: Long): Reminder?

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Query("UPDATE reminders SET done = 1 WHERE id = :id")
    suspend fun markDone(id: Long)

    @Query("UPDATE reminders SET at = :at WHERE id = :id")
    suspend fun moveTo(id: Long, at: Long)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_facts ORDER BY createdAt DESC")
    fun observeFacts(): Flow<List<MemoryFact>>

    @Query("SELECT * FROM memory_facts ORDER BY createdAt DESC")
    suspend fun facts(): List<MemoryFact>

    @Insert
    suspend fun insert(fact: MemoryFact): Long

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY id")
    fun observeHabits(): Flow<List<Habit>>

    @Query("SELECT * FROM habits ORDER BY id")
    suspend fun habits(): List<Habit>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun habit(id: Long): Habit?

    @Insert
    suspend fun insert(habit: Habit): Long

    @Insert
    suspend fun insertAll(habits: List<Habit>)

    @Update
    suspend fun update(habit: Habit)

    @Delete
    suspend fun delete(habit: Habit)

    @Query("UPDATE habits SET lastRemindedAt = :at, snoozedUntil = NULL WHERE id = :id")
    suspend fun markReminded(id: Long, at: Long)

    @Query("UPDATE habits SET snoozedUntil = :until WHERE id = :id")
    suspend fun snooze(id: Long, until: Long)

    @Insert
    suspend fun insertLog(log: HabitLog): Long

    @Query("DELETE FROM habit_logs WHERE id = :id")
    suspend fun deleteLog(id: Long)

    @Query("SELECT * FROM habit_logs WHERE at >= :since ORDER BY at")
    fun observeLogsSince(since: Long): Flow<List<HabitLog>>

    @Query("SELECT * FROM habit_logs WHERE at >= :since ORDER BY at")
    suspend fun logsSince(since: Long): List<HabitLog>
}

class Converters {
    @TypeConverter
    fun timesToText(times: List<LocalTime>): String = times.joinToString(",")

    @TypeConverter
    fun textToTimes(text: String): List<LocalTime> =
        if (text.isBlank()) emptyList() else text.split(',').map(LocalTime::parse)

    @TypeConverter
    fun timeToText(time: LocalTime?): String? = time?.toString()

    @TypeConverter
    fun textToTime(text: String?): LocalTime? = text?.let(LocalTime::parse)

    @TypeConverter
    fun daysToText(days: Set<DayOfWeek>): String = days.sorted().joinToString(",") { it.value.toString() }

    @TypeConverter
    fun textToDays(text: String): Set<DayOfWeek> =
        if (text.isBlank()) emptySet() else text.split(',').map { DayOfWeek.of(it.toInt()) }.toSet()
}
