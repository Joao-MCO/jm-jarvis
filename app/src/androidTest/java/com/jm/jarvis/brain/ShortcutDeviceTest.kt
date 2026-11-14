package com.jm.jarvis.brain

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jm.jarvis.container
import com.jm.jarvis.domain.HabitMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Confere no aparelho que um atalho de voz de fato grava no banco, e não só responde "Registrado". */
@RunWith(AndroidJUnit4::class)
class ShortcutDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.container

    @Test
    fun waterShortcutWritesTheLog() = runBlocking {
        val habits = app.habits.habits()
        val water = checkNotNull(HabitMatcher.find("água", habits))
        suspend fun total() = app.habits.todayState().statuses.first { it.habit.id == water.id }.doneToday
        val before = total()
        val shortcut = checkNotNull(Shortcut.match("Bebi um litro de água", habits)) { "o atalho não reconheceu a frase" }
        val reply = shortcut.run(app.newTools())
        val after = total()
        Log.i("JarvisShortcut", "antes=$before depois=$after resposta=$reply")
        assertEquals(before + 1000.0, after, 0.01)
    }
}
