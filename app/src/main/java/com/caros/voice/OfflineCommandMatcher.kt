package com.caros.voice

import javax.inject.Inject
import javax.inject.Singleton

/**
 * OfflineCommandMatcher — keyword-based voice command parser used when the
 * Gemini API is unavailable (no internet, missing API key, network timeout).
 *
 * Pattern matching is intentionally simple: each rule is a pair of
 * (keyword list, VoiceCommand factory). The first rule whose ANY keyword
 * appears in the lowercased input wins.  Priority order matters — more
 * specific rules must come first.
 */
@Singleton
class OfflineCommandMatcher @Inject constructor() {

    private data class Rule(val keywords: List<String>, val builder: () -> VoiceCommand)

    private val rules: List<Rule> = listOf(
        // ── App launch (before media — "spusť spotify" contains "pusť") ──────
        Rule(listOf("spotify")) {
            VoiceCommand.AppLaunch("spotify")
        },
        Rule(listOf("youtube")) {
            VoiceCommand.AppLaunch("youtube")
        },
        Rule(listOf("waze", "naviguj", "navigate")) {
            VoiceCommand.AppLaunch("waze")
        },

        // ── Media controls ────────────────────────────────────────────────────
        Rule(listOf("zastav", "stop", "pause", "pauza")) {
            VoiceCommand.Media("pause")
        },
        Rule(listOf("hraj", "play", "přehraj", "spusť hudbu", "pusť")) {
            VoiceCommand.Media("play")
        },
        Rule(listOf("další", "next", "přeskočit", "přeskoč")) {
            VoiceCommand.Media("next")
        },
        Rule(listOf("předchozí", "previous", "zpět k")) {
            VoiceCommand.Media("prev")
        },
        Rule(listOf("hlasitěji", "zesil", "zvyš hlasitost", "volume up", "víc")) {
            VoiceCommand.Media("volume_up")
        },
        Rule(listOf("ztiš", "zeslabi", "volume down", "méně hlasitě")) {
            VoiceCommand.Media("volume_down")
        },

        // ── Audio profiles ────────────────────────────────────────────────────
        Rule(listOf("noční", "night")) {
            VoiceCommand.AudioProfile("night")
        },
        Rule(listOf("sport", "sportovní")) {
            VoiceCommand.AudioProfile("sport")
        },
        Rule(listOf("basy", "bass", "víc basů")) {
            VoiceCommand.AudioProfile("bass")
        },
        Rule(listOf("flat", "plochý", "neutrální")) {
            VoiceCommand.AudioProfile("flat")
        },
        Rule(listOf("vokál", "vocal", "hlasy")) {
            VoiceCommand.AudioProfile("vocal")
        },

        // ── EQ tweaks ─────────────────────────────────────────────────────────
        Rule(listOf("zvyš basy", "přidej bas")) {
            VoiceCommand.EQAdjust("bass", 80)
        },
        Rule(listOf("sniz basy", "ubrat bas")) {
            VoiceCommand.EQAdjust("bass", 30)
        },
        Rule(listOf("zvyš výšky", "přidej výšky", "treble")) {
            VoiceCommand.EQAdjust("treble", 80)
        },
        Rule(listOf("auto eq", "automatické eq", "adaptivní")) {
            VoiceCommand.AutoEQ(true)
        },

        // ── Car info ──────────────────────────────────────────────────────────
        Rule(listOf("rychlost", "speed")) {
            VoiceCommand.CarInfo("speed")
        },
        Rule(listOf("otáčky", "rpm")) {
            VoiceCommand.CarInfo("rpm")
        },
        Rule(listOf("teplota", "temperature", "chladivo")) {
            VoiceCommand.CarInfo("temp")
        },
        Rule(listOf("napětí", "voltage", "baterie")) {
            VoiceCommand.CarInfo("voltage")
        },
        Rule(listOf("dpf", "filtr pevných", "saze")) {
            VoiceCommand.CarInfo("dpf")
        },

        // ── System ───────────────────────────────────────────────────────────
        Rule(listOf("ztlum displej", "sniz jas", "brightness down")) {
            VoiceCommand.System("brightness_down")
        },
        Rule(listOf("rozsvit displej", "zvyš jas", "brightness up")) {
            VoiceCommand.System("brightness_up")
        },
        Rule(listOf("zapni wifi", "wifi on")) {
            VoiceCommand.System("wifi_on")
        },
        Rule(listOf("vypni wifi", "wifi off")) {
            VoiceCommand.System("wifi_off")
        },

        // ── Cancel ───────────────────────────────────────────────────────────
        Rule(listOf("zrušit", "cancel", "stop listening", "ne")) {
            VoiceCommand.Cancel
        }
    )

    /**
     * Returns the best-matching [VoiceCommand] for [input], or
     * [VoiceCommand.Unknown] if no keyword matches.
     */
    fun match(input: String): VoiceCommand {
        val lower = input.lowercase().trim()
        for (rule in rules) {
            if (rule.keywords.any { keyword -> lower.contains(keyword) }) {
                return rule.builder()
            }
        }
        return VoiceCommand.Unknown("no_match")
    }
}
