package app.facet.browser

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Optional assistant. Off by default; nothing is sent anywhere until the user turns it on and asks. */
class AiConfig(private val prefs: Prefs) {
    var enabled: Boolean
        get() = prefs.getBool("ai_enabled", false)
        set(v) = prefs.putBool("ai_enabled", v)

    /** 0 = Anthropic, 1 = OpenAI-compatible */
    var provider: Int
        get() = prefs.getInt("ai_provider", 0)
        set(v) = prefs.putInt("ai_provider", v)

    var baseUrl: String
        get() = prefs.getStr("ai_base", if (provider == 0) "https://api.anthropic.com" else "https://api.openai.com/v1")
        set(v) = prefs.putStr("ai_base", v.trim().trimEnd('/'))

    var model: String
        get() = prefs.getStr("ai_model", if (provider == 0) "claude-sonnet-5-5" else "")
        set(v) = prefs.putStr("ai_model", v.trim())

    var apiKey: String
        get() = SecretBox.decrypt(prefs.getStr("ai_key", ""))
        set(v) = prefs.putStr("ai_key", SecretBox.encrypt(v.trim()))

    val ready: Boolean get() = enabled && apiKey.isNotEmpty() && model.isNotEmpty()

    /** Blocking network call. Run it off the main thread. */
    fun ask(system: String, user: String): String {
        val base = baseUrl
        val conn: HttpURLConnection
        val body = JSONObject()
        if (provider == 0) {
            conn = URL("$base/v1/messages").openConnection() as HttpURLConnection
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            body.put("model", model)
            body.put("max_tokens", 1500)
            body.put("system", system)
            body.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        } else {
            conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            body.put("model", model)
            body.put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        }
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 90000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
                throw Exception(if (!msg.isNullOrEmpty()) msg else "The provider answered with code $code")
            }
            val o = JSONObject(text)
            return if (provider == 0) {
                val parts = o.getJSONArray("content")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                sb.toString().trim()
            } else {
                o.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            }
        } finally {
            conn.disconnect()
        }
    }
}
