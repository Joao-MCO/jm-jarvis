package com.jm.jarvis.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lembrete avulso: "me lembra de ligar para o dentista amanhã às 15h". */
@Entity(tableName = "reminders", indices = [Index("at")])
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** Quando avisar (ms, relógio de parede). */
    val at: Long,
    val done: Boolean = false,
    val createdAt: Long,
)

/** Algo que o usuário pediu para o JARVIS lembrar sobre ele. */
@Entity(tableName = "memory_facts")
data class MemoryFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
)
