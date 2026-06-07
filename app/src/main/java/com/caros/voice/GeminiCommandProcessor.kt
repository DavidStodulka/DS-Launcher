package com.caros.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiCommandProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offlineMatcher: OfflineCommandMatcher
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences("caros_voice_prefs", Context.MODE_PRIVATE)

    private var __apiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(v) { prefs.edit().putString("gemini_api_key", v).apply() }

    private val SYSTEM_PROMPT = """
        Jsi hlasový asistent automobilu CarOS. Uživatel mluví česky nebo anglicky.
        Odpovídej POUZE validním JSON. Nikdy nepřidávej vysvětlení ani text mimo JSON.

        Dostupné akce:
        Navigace: {"action":"navigate","destination":"text"}
        Media: {"action":"media","cmd":"play|pause|next|prev|volume_up|volume_down"}
        Audio profil: {"action":"audio_profile","profile":"flat|bass|vocal|stage|night|sport"}
        EQ: {"action":"eq_adjust","param":"bass|treble|surround","value":0-100}
        Auto EQ: {"action":"auto_eq","enabled":true/false}
        Spuštění app: {"action":"app_launch","app":"spotify|youtube|waze"}
        Info o autě: {"action":"car_info","query":"speed|rpm|temp|dpf|oil_life|voltage"}
        Telefon: {"action":"phone","cmd":"call","contact":"jméno"}
        Systém: {"action":"system","cmd":"brightness_up|brightness_down|wifi_on|wifi_off"}
        Zrušit: {"action":"cancel"}

        Příklady:
        "Hraj muziku" -> {"action":"media","cmd":"play"}
        "Zvyš basy" -> {"action":"eq_adjust","param":"bass","value":80}
        "Spusť Waze" -> {"action":"app_launch","app":"waze"}
        "Jaká je rychlost" -> {"action":"car_info","query":"speed"}
        "Zavolej Petrovi" -> {"action":"phone","cmd":"call","contact":"Petr"}
        "Nočný profil" -> {"action":"audio_profile","profile":"night"}
        "Naviguj do Prahy" -> {"action":"navigate","destination":"Praha"}
    """.trimIndent()

    fun setApiKey(key: String) { _apiKey = key }
    fun hasApiKey(): Boolean = _apiKey.isNotBlank()

    suspend fun processCommand(spokenText: String): VoiceCommand = withContext(Dispatchers.IO) {
        if (!hasApiKey()) return@withContext offlineMatcher.match(spokenText)
        runCatching {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$_apiKey"
            val body = buildRequestBody(spokenText)
            val request = Request.Builder().url(url).post(body).build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Timber.w("Gemini HTTP ${response.code}: $responseBody")
                return@withContext VoiceCommand.Unknown("http_${response.code}")
            }
            if (responseBody.isBlank()) return@withContext VoiceCommand.Unknown("empty response")
            parseGeminiResponse(responseBody)
        }.getOrElse { e ->
            Timber.e(e, "Gemini API error — falling back to offline matcher")
            offlineMatcher.match(spokenText)
        }
    }

    private fun buildRequestBody(text: String): RequestBody {
        val json = """
            {
              "system_instruction": {"parts": [{"text": ${JSONObject.quote(SYSTEM_PROMPT)}}]},
              "contents": [{"parts": [{"text": ${JSONObject.quote(text)}}]}],
              "generationConfig": {"temperature": 0.1, "maxOutputTokens": 200}
            }
        """.trimIndent()
        return json.toRequestBody("application/json".toMediaType())
    }

    private fun parseGeminiResponse(responseJson: String): VoiceCommand {
        return runCatching {
            val root = JSONObject(responseJson)
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                Timber.w("Gemini: no candidates in response")
                return VoiceCommand.Unknown("no candidates")
            }
            val text = candidates
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
            Timber.d("Gemini response: $text")
            parseCommandJson(text)
        }.getOrElse { e ->
            Timber.e(e, "Gemini parse error: $responseJson")
            VoiceCommand.Unknown("parse_error")
        }
    }

    private fun parseCommandJson(json: String): VoiceCommand {
        val clean = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching {
            val obj = JSONObject(clean)
            when (val action = obj.getString("action")) {
                "navigate"      -> VoiceCommand.Navigate(obj.getString("destination"))
                "media"         -> VoiceCommand.Media(obj.getString("cmd"))
                "audio_profile" -> VoiceCommand.AudioProfile(obj.getString("profile"))
                "eq_adjust"     -> VoiceCommand.EQAdjust(obj.getString("param"), obj.getInt("value"))
                "auto_eq"       -> VoiceCommand.AutoEQ(obj.getBoolean("enabled"))
                "app_launch"    -> VoiceCommand.AppLaunch(obj.getString("app"))
                "car_info"      -> VoiceCommand.CarInfo(obj.getString("query"))
                "phone"         -> VoiceCommand.Phone(obj.getString("cmd"), obj.optString("contact"))
                "system"        -> VoiceCommand.System(obj.getString("cmd"))
                "cancel"        -> VoiceCommand.Cancel
                else            -> VoiceCommand.Unknown(action)
            }
        }.getOrElse { VoiceCommand.Unknown(clean) }
    }

    suspend fun testApiKey(key: String): Boolean = withContext(Dispatchers.IO) {
        val oldKey = _apiKey
        _apiKey = key
        val result = runCatching { processCommand("test") !is VoiceCommand.Unknown }.getOrDefault(false)
        _apiKey = oldKey
        result
    }
}
