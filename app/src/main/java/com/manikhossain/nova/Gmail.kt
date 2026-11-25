package com.manikhossain.nova

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Read-only Gmail access with the user's own Google sign-in (OAuth). No password is ever seen by Nova. */
object Gmail {
    const val REQ = 4711
    private const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    private var token: String? = null
    private var tokenTime = 0L
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Called from the Settings button: shows Google's consent screen if needed. */
    fun connect(a: Activity, done: (Boolean, String) -> Unit) {
        Identity.getAuthorizationClient(a).authorize(request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    try {
                        a.startIntentSenderForResult(r.pendingIntent!!.intentSender, REQ, null, 0, 0, 0)
                    } catch (e: Exception) { done(false, e.message ?: "error") }
                } else {
                    token = r.accessToken; tokenTime = System.currentTimeMillis()
                    Prefs.gmailConnected = true
                    done(true, "connected")
                }
            }
            .addOnFailureListener { e -> done(false, e.message ?: "error") }
    }

    fun onResult(a: Activity, data: Intent?): Boolean = try {
        val r = Identity.getAuthorizationClient(a).getAuthorizationResultFromIntent(data)
        token = r.accessToken; tokenTime = System.currentTimeMillis()
        Prefs.gmailConnected = token != null
        token != null
    } catch (_: Exception) { false }

    private fun freshToken(c: Context): String? {
        if (token != null && System.currentTimeMillis() - tokenTime < 45 * 60_000) return token
        return try {
            val r = Tasks.await(Identity.getAuthorizationClient(c).authorize(request()), 15, TimeUnit.SECONDS)
            if (r.hasResolution()) null else r.accessToken.also { token = it; tokenTime = System.currentTimeMillis() }
        } catch (_: Exception) { null }
    }

    private fun get(url: String, tok: String): JSONObject {
        val res = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $tok").build()).execute()
        res.use {
            val body = it.body?.string() ?: "{}"
            if (!it.isSuccessful) throw RuntimeException("Gmail ${it.code}: ${body.take(200)}")
            return JSONObject(body)
        }
    }

    fun check(c: Context, query: String): JSONObject {
        val tok = freshToken(c)
        if (tok == null) {
            val n = NotifListener.recent("Gmail", c)
            return JSONObject().put("status", "gmail_not_connected")
                .put("note", "Gmail isn't connected. Tell the user: Nova → Settings → Connect Gmail. Meanwhile, recent Gmail notifications are below.")
                .put("gmail_notifications", n.optJSONArray("notifications") ?: JSONArray())
        }
        val base = "https://gmail.googleapis.com/gmail/v1/users/me/messages"
        val list = get("$base?maxResults=8&q=" + URLEncoder.encode(query, "UTF-8"), tok).optJSONArray("messages") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val id = list.getJSONObject(i).optString("id")
            val m = get("$base/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date", tok)
            val h = JSONObject()
            val hs = m.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
            for (k in 0 until hs.length()) h.put(hs.getJSONObject(k).optString("name"), hs.getJSONObject(k).optString("value"))
            out.put(JSONObject().put("from", h.optString("From")).put("subject", h.optString("Subject"))
                .put("date", h.optString("Date")).put("snippet", m.optString("snippet").take(220)))
        }
        return JSONObject().put("query", query).put("emails", out).put("count", out.length())
    }
}
