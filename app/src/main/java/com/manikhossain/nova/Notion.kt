package com.manikhossain.nova

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Notion through its official API (the user's own integration secret). Writes notes, appends, reads, searches. */
object Notion {
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val b = Request.Builder().url("https://api.notion.com/v1/$path")
            .header("Authorization", "Bearer ${Prefs.notionToken}").header("Notion-Version", "2022-06-28")
        if (body != null) b.method(method, body.toString().toRequestBody(JSON)) else b.method(method, null)
        http.newCall(b.build()).execute().use {
            val txt = it.body?.string() ?: "{}"
            if (!it.isSuccessful) throw RuntimeException("Notion ${it.code}: ${txt.take(200)}")
            return JSONObject(txt)
        }
    }

    private fun notConnected() = JSONObject().put("status", "not_connected")
        .put("note", "Notion isn't connected. Tell the user: Nova → Settings → Notion → paste the integration secret, and share pages with it.")

    private fun title(o: JSONObject): String {
        if (o.optString("object") == "database") return o.optJSONArray("title")?.let { a -> (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("plain_text") } } ?: ""
        val props = o.optJSONObject("properties") ?: return ""
        for (k in props.keys()) {
            val p = props.getJSONObject(k)
            if (p.optString("type") == "title") {
                val a = p.optJSONArray("title") ?: continue
                return (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("plain_text") }
            }
        }
        return ""
    }

    fun search(q: String): JSONObject {
        if (Prefs.notionToken.isBlank()) return notConnected()
        val r = call("POST", "search", JSONObject().put("query", q).put("page_size", 10))
        val out = JSONArray()
        val res = r.optJSONArray("results") ?: JSONArray()
        for (i in 0 until res.length()) {
            val o = res.getJSONObject(i)
            out.put(JSONObject().put("id", o.optString("id")).put("type", o.optString("object")).put("title", title(o)).put("url", o.optString("url")))
        }
        return JSONObject().put("results", out)
    }

    private fun findPage(nameOrId: String): String? {
        if (nameOrId.replace("-", "").length == 32 && nameOrId.replace("-", "").all { it.isLetterOrDigit() }) return nameOrId
        val res = search(nameOrId).optJSONArray("results") ?: return null
        var fallback: String? = null
        for (i in 0 until res.length()) {
            val o = res.getJSONObject(i)
            if (o.optString("type") != "page") continue
            if (o.optString("title").equals(nameOrId, true)) return o.optString("id")
            if (fallback == null) fallback = o.optString("id")
        }
        return fallback
    }

    private fun rich(t: String) = JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", t.take(1900))))

    private fun blocks(md: String): JSONArray {
        val a = JSONArray()
        for (raw in md.lines()) {
            val l = raw.trimEnd()
            if (l.isBlank()) continue
            val (type, text) = when {
                l.startsWith("### ") -> "heading_3" to l.drop(4)
                l.startsWith("## ") -> "heading_2" to l.drop(3)
                l.startsWith("# ") -> "heading_1" to l.drop(2)
                l.startsWith("- [ ] ") -> "to_do" to l.drop(6)
                l.startsWith("- ") || l.startsWith("* ") || l.startsWith("• ") -> "bulleted_list_item" to l.drop(2)
                Regex("^\\d+[.)] ").containsMatchIn(l) -> "numbered_list_item" to l.substringAfter(' ')
                else -> "paragraph" to l
            }
            a.put(JSONObject().put("object", "block").put("type", type).put(type, JSONObject().put("rich_text", rich(text))))
            if (a.length() >= 95) break
        }
        return a
    }

    fun createPage(titleText: String, content: String, parent: String): JSONObject {
        if (Prefs.notionToken.isBlank()) return notConnected()
        val parentId = if (parent.isNotBlank()) findPage(parent) else null
        val pid = parentId ?: run {
            val res = call("POST", "search", JSONObject().put("filter", JSONObject().put("property", "object").put("value", "page")).put("page_size", 1))
            res.optJSONArray("results")?.optJSONObject(0)?.optString("id")
        } ?: return JSONObject().put("status", "no_parent").put("note", "Nova can't see any Notion page. Share a page with the integration first.")
        val body = JSONObject().put("parent", JSONObject().put("page_id", pid))
            .put("properties", JSONObject().put("title", JSONObject().put("title", rich(titleText))))
            .put("children", blocks(content))
        val r = call("POST", "pages", body)
        return JSONObject().put("status", "created").put("id", r.optString("id")).put("url", r.optString("url"))
    }

    fun append(page: String, content: String): JSONObject {
        if (Prefs.notionToken.isBlank()) return notConnected()
        val id = findPage(page) ?: return JSONObject().put("status", "not_found").put("note", "No page '$page' visible to Nova.")
        call("PATCH", "blocks/$id/children", JSONObject().put("children", blocks(content)))
        return JSONObject().put("status", "appended").put("page_id", id)
    }

    fun read(page: String): JSONObject {
        if (Prefs.notionToken.isBlank()) return notConnected()
        val id = findPage(page) ?: return JSONObject().put("status", "not_found")
        val r = call("GET", "blocks/$id/children?page_size=100")
        val sb = StringBuilder()
        val res = r.optJSONArray("results") ?: JSONArray()
        for (i in 0 until res.length()) {
            val b = res.getJSONObject(i)
            val t = b.optJSONObject(b.optString("type"))?.optJSONArray("rich_text") ?: continue
            sb.append((0 until t.length()).joinToString("") { t.getJSONObject(it).optString("plain_text") }).append('\n')
        }
        return JSONObject().put("page_id", id).put("text", sb.toString().take(6000))
    }
}
