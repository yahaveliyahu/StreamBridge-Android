package dev.yahaveliyahu.streambridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ChatHistoryStore {
    private const val PREF = "chat_history"
    private const val KEY = "history"

    fun append(context: Context, obj: JSONObject) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(old)
        arr.put(obj)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
