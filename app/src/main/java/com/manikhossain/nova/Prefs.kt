package com.manikhossain.nova

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Small settings + memory store (SharedPreferences). */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.getSharedPreferences("nova", Context.MODE_PRIVATE)
    }

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()
    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v.trim()).apply()
    var voice: String
        get() = sp.getString("voice", "Zephyr") ?: "Zephyr"
        set(v) = sp.edit().putString("voice", v).apply()
    var wakeWord: Boolean
        get() = sp.getBoolean("wake_word", true)
        set(v) = sp.edit().putBoolean("wake_word", v).apply()
    var gmailConnected: Boolean
        get() = sp.getBoolean("gmail", false)
        set(v) = sp.edit().putBoolean("gmail", v).apply()
    var model: String
        get() = sp.getString("model", "") ?: ""
        set(v) = sp.edit().putString("model", v).apply()

    // ---- long-term memory (facts the user taught Nova)
    fun memories(): JSONArray = JSONArray(sp.getString("memory", "[]"))

    fun remember(fact: String, category: String): JSONObject {
        val a = memories()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("fact").equals(fact, true)) return JSONObject().put("status", "already_known")
        a.put(JSONObject().put("fact", fact).put("category", category).put("t", System.currentTimeMillis()))
        while (a.length() > 150) a.remove(0)
        sp.edit().putString("memory", a.toString()).apply()
        return JSONObject().put("status", "remembered")
    }

    fun forget(text: String): Int {
        val a = memories()
        val b = JSONArray()
        var n = 0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("fact").contains(text, true)) n++ else b.put(o)
        }
        sp.edit().putString("memory", b.toString()).apply()
        return n
    }

    fun memoryPrompt(): String {
        val a = memories()
        if (a.length() == 0) return "(nothing yet)"
        val sb = StringBuilder()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            sb.append("- [").append(o.optString("category")).append("] ").append(o.optString("fact")).append('\n')
        }
        return sb.toString()
    }

    // ---- recent conversation, so a new session continues where we left off
    fun addHistory(role: String, text: String) {
        if (text.isBlank()) return
        val a = JSONArray(sp.getString("history", "[]"))
        a.put(JSONObject().put("r", role).put("x", text.take(400)).put("t", System.currentTimeMillis()))
        while (a.length() > 40) a.remove(0)
        sp.edit().putString("history", a.toString()).apply()
    }

    fun recentConversation(): String {
        val a = JSONArray(sp.getString("history", "[]"))
        val cut = System.currentTimeMillis() - 6 * 3600_000L
        val sb = StringBuilder()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optLong("t") < cut) continue
            val who = when (o.optString("r")) { "user" -> "User"; "nova" -> "Nova"; else -> "Action" }
            sb.append(who).append(": ").append(o.optString("x")).append('\n')
        }
        return sb.toString()
    }

    fun historyJson(): JSONArray = JSONArray(sp.getString("history", "[]"))
}
