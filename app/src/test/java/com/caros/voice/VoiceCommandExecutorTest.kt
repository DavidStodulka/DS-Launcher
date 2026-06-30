package com.caros.voice

import android.content.Context
import android.content.pm.PackageManager
import com.caros.audio.AdaptiveEQEngine
import com.caros.audio.AudioEngineManager
import com.caros.audio.AudioProfile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VoiceCommandExecutorTest {

    private lateinit var context: Context
    private lateinit var audioEngine: AudioEngineManager
    private lateinit var adaptiveEQ: AdaptiveEQEngine
    private lateinit var executor: VoiceCommandExecutor

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        audioEngine = mockk(relaxed = true)
        adaptiveEQ = mockk(relaxed = true)
        executor = VoiceCommandExecutor(context, audioEngine, adaptiveEQ)
    }

    @Test
    fun `media play command returns Czech confirmation`() {
        val result = executor.execute(VoiceCommand.Media("play"))
        assertEquals("Přehrávám", result)
    }

    @Test
    fun `media pause command returns Czech confirmation`() {
        val result = executor.execute(VoiceCommand.Media("pause"))
        assertEquals("Pauza", result)
    }

    @Test
    fun `media next command returns confirmation`() {
        val result = executor.execute(VoiceCommand.Media("next"))
        assertEquals("Další", result)
    }

    @Test
    fun `audio profile night sets profile on engine`() {
        executor.execute(VoiceCommand.AudioProfile("night"))
        verify { audioEngine.setProfile(AudioProfile.NIGHT) }
    }

    @Test
    fun `audio profile sport sets profile on engine`() {
        executor.execute(VoiceCommand.AudioProfile("sport"))
        verify { audioEngine.setProfile(AudioProfile.SPORT) }
    }

    @Test
    fun `unknown audio profile returns error message`() {
        val result = executor.execute(VoiceCommand.AudioProfile("invalid"))
        assertTrue(result.contains("invalid"))
    }

    @Test
    fun `auto eq enable calls setEnabled true`() {
        executor.execute(VoiceCommand.AutoEQ(true))
        verify { adaptiveEQ.setEnabled(true) }
    }

    @Test
    fun `auto eq disable calls setEnabled false`() {
        executor.execute(VoiceCommand.AutoEQ(false))
        verify { adaptiveEQ.setEnabled(false) }
    }

    @Test
    fun `cancel command returns Czech string`() {
        val result = executor.execute(VoiceCommand.Cancel)
        assertEquals("Zrušeno", result)
    }

    @Test
    fun `unknown command returns message with raw text`() {
        val result = executor.execute(VoiceCommand.Unknown("bla bla"))
        assertTrue(result.contains("bla bla"))
    }

    @Test
    fun `navigate command returns destination in result`() {
        every { context.startActivity(any()) } returns Unit
        val result = executor.execute(VoiceCommand.Navigate("Praha"))
        assertTrue(result.contains("Praha"))
    }

    @Test
    fun `car info without CAN frame returns unavailable message`() {
        executor.canFrameFlow = null
        val result = executor.execute(VoiceCommand.CarInfo("speed"))
        assertFalse(result.contains("km/h"))
    }
}
