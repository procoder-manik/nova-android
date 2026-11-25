package com.manikhossain.nova

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Reads / adds events in the phone's calendars (Google, Samsung...) directly - no sign-in needed. */
object CalendarTool {
    private val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH)

    private fun noPerm(c: Context, p: String) = c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED

    fun events(c: Context, daysAhead: Int, daysBack: Int, query: String): JSONObject {
        if (noPerm(c, Manifest.permission.READ_CALENDAR)) return JSONObject().put("status", "no_permission")
            .put("note", "Ask the user to allow Calendar for Nova (Nova → Settings → Permissions).")
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysBack.coerceIn(0, 60))
            if (daysBack > 0) { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }
        }.timeInMillis
        val end = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysAhead.coerceIn(0, 90)); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
        }.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end)
        }.build()
        val out = JSONArray()
        c.contentResolver.query(uri, arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        ), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
            while (cur.moveToNext() && out.length() < 40) {
                val title = cur.getString(0) ?: "(no title)"
                val desc = cur.getString(5) ?: ""
                if (query.isNotBlank() && !title.contains(query, true) && !desc.contains(query, true)) continue
                val allDay = cur.getInt(3) == 1
                out.put(JSONObject().put("title", title)
                    .put("start", if (allDay) "all day " + SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(cur.getLong(1)) else fmt.format(cur.getLong(1)))
                    .put("end", if (allDay) "" else fmt.format(cur.getLong(2)))
                    .put("location", cur.getString(4) ?: "").put("notes", desc.take(200)).put("calendar", cur.getString(6) ?: ""))
            }
        }
        return JSONObject().put("events", out).put("count", out.length())
            .put("now", SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(System.currentTimeMillis()))
    }

    fun add(c: Context, title: String, start: String, minutes: Int, location: String, notes: String): JSONObject {
        if (noPerm(c, Manifest.permission.WRITE_CALENDAR)) return JSONObject().put("status", "no_permission")
            .put("note", "Ask the user to allow Calendar for Nova (Nova → Settings → Permissions).")
        val begin = try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(start)!!.time }
            catch (_: Exception) { return JSONObject().put("status", "error").put("error", "start must look like 2026-11-25 15:30") }
        var calId = -1L
        c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.ACCOUNT_TYPE), CalendarContract.Calendars.VISIBLE + "=1", null, null)?.use { cur ->
            var best = -1
            while (cur.moveToNext()) {
                if (cur.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                val sc = (if (cur.getInt(1) == 1) 2 else 0) + (if (cur.getString(3) == "com.google") 1 else 0)
                if (sc > best) { best = sc; calId = cur.getLong(0) }
            }
        }
        if (calId < 0) return JSONObject().put("status", "no_calendar").put("note", "No writable calendar on this phone.")
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, begin + minutes.coerceIn(5, 24 * 60) * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (notes.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, notes)
        }
        val uri = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
        return if (uri != null) JSONObject().put("status", "added").put("title", title).put("start", fmt.format(begin))
        else JSONObject().put("status", "failed")
    }
}
