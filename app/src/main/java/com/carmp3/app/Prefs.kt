package com.carmp3.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** كل الإعدادات والحالة المحفوظة في مكان واحد. */
class Prefs(ctx: Context) {

    companion object {
        const val MODE_ALWAYS_PLAY = 0
        const val MODE_ALWAYS_PAUSE = 1
        const val MODE_RESUME = 2

        /** أي جهاز بلوتوث اسمه يحتوي إحدى هذه الكلمات يُعتمد تلقائياً. */
        val DEFAULT_KEYWORDS = listOf("captiva", "chevrolet")
    }

    data class Dev(val name: String, val address: String)

    private val sp = ctx.applicationContext.getSharedPreferences("car_mp3", Context.MODE_PRIVATE)

    // ---------- أجهزة البلوتوث ----------

    var anyDevice: Boolean
        get() = sp.getBoolean("any_device", false)
        set(v) { sp.edit().putBoolean("any_device", v).apply() }

    fun devices(): List<Dev> {
        val raw = sp.getString("devices", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Dev(o.optString("n"), o.getString("a"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveDevices(list: List<Dev>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("a", it.address)) }
        sp.edit().putString("devices", arr.toString()).apply()
    }

    fun addDevice(name: String, address: String) {
        val list = devices().filter { !it.address.equals(address, true) }.toMutableList()
        list.add(Dev(name, address))
        saveDevices(list)
    }

    fun removeDevice(address: String) {
        saveDevices(devices().filter { !it.address.equals(address, true) })
    }

    /** هل الجهاز موجود في القائمة المعتمدة (أو اسمه يحتوي كلمة افتراضية)؟ */
    fun matchesList(address: String?, name: String?): Boolean {
        if (address != null && devices().any { it.address.equals(address, true) }) return true
        val n = name?.lowercase() ?: return false
        return DEFAULT_KEYWORDS.any { n.contains(it) }
    }

    // ---------- سلوك البدء ----------

    var startMode: Int
        get() = sp.getInt("start_mode", MODE_RESUME)
        set(v) { sp.edit().putInt("start_mode", v).apply() }

    // ---------- مسارات الموسيقى ----------

    fun trees(): List<String> {
        val raw = sp.getString("trees", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveTrees(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp.edit().putString("trees", arr.toString()).apply()
    }

    fun addTree(uri: String) {
        if (!trees().contains(uri)) saveTrees(trees() + uri)
    }

    fun removeTree(uri: String) {
        saveTrees(trees().filter { it != uri })
    }

    // ---------- الحالة المحفوظة (المجلد / الملف / الموضع / تشغيل أو إيقاف) ----------

    val lastFolderKey: String? get() = sp.getString("last_folder", null)
    val lastTrackUri: String? get() = sp.getString("last_track", null)
    val lastPos: Int get() = sp.getInt("last_pos", 0)
    val lastPlaying: Boolean get() = sp.getBoolean("last_playing", false)

    fun saveState(folderKey: String, trackUri: String, posMs: Int, playing: Boolean) {
        sp.edit()
            .putString("last_folder", folderKey)
            .putString("last_track", trackUri)
            .putInt("last_pos", posMs)
            .putBoolean("last_playing", playing)
            .apply()
    }
}
