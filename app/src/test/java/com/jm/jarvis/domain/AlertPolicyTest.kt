package com.jm.jarvis.domain

import com.jm.jarvis.data.AlertMode
import com.jm.jarvis.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class AlertPolicyTest {
    private val noon = LocalTime.NOON
    private val awake = Settings(quietEnabled = false)

    @Test
    fun `quiet window can cross midnight`() {
        val settings = Settings(quietEnabled = true, quietStart = LocalTime.of(22, 30), quietEnd = LocalTime.of(7, 0))
        assertTrue(settings.isQuietAt(LocalTime.of(22, 30)))
        assertTrue(settings.isQuietAt(LocalTime.of(23, 0)))
        assertTrue(settings.isQuietAt(LocalTime.of(6, 59)))
        assertFalse(settings.isQuietAt(LocalTime.of(7, 0)))
        assertFalse(settings.isQuietAt(noon))
    }

    @Test
    fun `quiet window within the same day`() {
        val settings = Settings(quietEnabled = true, quietStart = LocalTime.of(13, 0), quietEnd = LocalTime.of(14, 0))
        assertTrue(settings.isQuietAt(LocalTime.of(13, 30)))
        assertFalse(settings.isQuietAt(LocalTime.of(14, 0)))
    }

    @Test
    fun `disabled quiet window is never quiet`() = assertFalse(awake.isQuietAt(LocalTime.of(3, 0)))

    @Test
    fun `speak mode plays the sound and then talks`() =
        assertEquals(AlertDecision(AlertChannel.SOUND, speak = true), AlertPolicy.forHabit(AlertMode.SPEAK, awake, noon, deviceQuiet = false))

    @Test
    fun `phone on silent or do not disturb blocks speech`() =
        assertEquals(AlertDecision(AlertChannel.SOUND, speak = false), AlertPolicy.forHabit(AlertMode.SPEAK, awake, noon, deviceQuiet = true))

    @Test
    fun `muted JARVIS shows the reminder without sound`() = assertEquals(
        AlertDecision(AlertChannel.SILENT, speak = false),
        AlertPolicy.forHabit(AlertMode.SPEAK, awake.copy(muted = true), noon, deviceQuiet = false),
    )

    @Test
    fun `quiet hours silence even talking routines`() {
        val night = Settings(quietEnabled = true, quietStart = LocalTime.of(22, 30), quietEnd = LocalTime.of(7, 0))
        assertEquals(AlertDecision(AlertChannel.SILENT, speak = false), AlertPolicy.forHabit(AlertMode.SPEAK, night, LocalTime.of(23, 0), deviceQuiet = false))
    }

    @Test
    fun `vibrate and silent modes never talk`() {
        assertEquals(AlertDecision(AlertChannel.VIBRATE, speak = false), AlertPolicy.forHabit(AlertMode.VIBRATE, awake, noon, deviceQuiet = false))
        assertEquals(AlertDecision(AlertChannel.SILENT, speak = false), AlertPolicy.forHabit(AlertMode.SILENT, awake, noon, deviceQuiet = false))
    }

    @Test
    fun `calendar speech follows mute, quiet hours and the phone state`() {
        assertTrue(AlertPolicy.speakCalendar(awake, noon, deviceQuiet = false))
        assertFalse(AlertPolicy.speakCalendar(awake.copy(calendarSpeech = false), noon, deviceQuiet = false))
        assertFalse(AlertPolicy.speakCalendar(awake.copy(muted = true), noon, deviceQuiet = false))
        assertFalse(AlertPolicy.speakCalendar(awake, noon, deviceQuiet = true))
    }
}
