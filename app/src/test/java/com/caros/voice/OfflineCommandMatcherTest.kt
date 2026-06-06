package com.caros.voice

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OfflineCommandMatcherTest {

    private lateinit var matcher: OfflineCommandMatcher

    @Before
    fun setUp() {
        matcher = OfflineCommandMatcher()
    }

    @Test
    fun `matches play command in Czech`() {
        val result = matcher.match("hraj muziku")
        assertTrue(result is VoiceCommand.Media)
        assertEquals("play", (result as VoiceCommand.Media).cmd)
    }

    @Test
    fun `matches pause command`() {
        val result = matcher.match("zastav přehrávání")
        assertTrue(result is VoiceCommand.Media)
        assertEquals("pause", (result as VoiceCommand.Media).cmd)
    }

    @Test
    fun `matches next track command`() {
        val result = matcher.match("další skladba")
        assertTrue(result is VoiceCommand.Media)
        assertEquals("next", (result as VoiceCommand.Media).cmd)
    }

    @Test
    fun `matches volume up`() {
        val result = matcher.match("hlasitěji prosím")
        assertTrue(result is VoiceCommand.Media)
        assertEquals("volume_up", (result as VoiceCommand.Media).cmd)
    }

    @Test
    fun `matches volume down`() {
        val result = matcher.match("ztiš to")
        assertTrue(result is VoiceCommand.Media)
        assertEquals("volume_down", (result as VoiceCommand.Media).cmd)
    }

    @Test
    fun `matches night audio profile`() {
        val result = matcher.match("noční profil")
        assertTrue(result is VoiceCommand.AudioProfile)
        assertEquals("night", (result as VoiceCommand.AudioProfile).profile)
    }

    @Test
    fun `matches sport audio profile`() {
        val result = matcher.match("sportovní mód")
        assertTrue(result is VoiceCommand.AudioProfile)
        assertEquals("sport", (result as VoiceCommand.AudioProfile).profile)
    }

    @Test
    fun `matches Spotify app launch`() {
        val result = matcher.match("spusť spotify")
        assertTrue(result is VoiceCommand.AppLaunch)
        assertEquals("spotify", (result as VoiceCommand.AppLaunch).app)
    }

    @Test
    fun `matches speed car info`() {
        val result = matcher.match("jaká je rychlost")
        assertTrue(result is VoiceCommand.CarInfo)
        assertEquals("speed", (result as VoiceCommand.CarInfo).query)
    }

    @Test
    fun `matches RPM car info`() {
        val result = matcher.match("kolik mám otáčky")
        assertTrue(result is VoiceCommand.CarInfo)
        assertEquals("rpm", (result as VoiceCommand.CarInfo).query)
    }

    @Test
    fun `matches cancel command`() {
        val result = matcher.match("zrušit")
        assertTrue(result is VoiceCommand.Cancel)
    }

    @Test
    fun `returns Unknown for unrecognized input`() {
        val result = matcher.match("co je osmdesát devět")
        assertTrue(result is VoiceCommand.Unknown)
    }

    @Test
    fun `matching is case insensitive`() {
        val result = matcher.match("HRAJ MUZIKU")
        assertTrue(result is VoiceCommand.Media)
    }

    @Test
    fun `matches wifi on system command`() {
        val result = matcher.match("zapni wifi")
        assertTrue(result is VoiceCommand.System)
        assertEquals("wifi_on", (result as VoiceCommand.System).cmd)
    }

    @Test
    fun `matches brightness down`() {
        val result = matcher.match("ztlum displej")
        assertTrue(result is VoiceCommand.System)
        assertEquals("brightness_down", (result as VoiceCommand.System).cmd)
    }

    @Test
    fun `matches DPF car info`() {
        val result = matcher.match("jaký je stav dpf")
        assertTrue(result is VoiceCommand.CarInfo)
        assertEquals("dpf", (result as VoiceCommand.CarInfo).query)
    }
}
