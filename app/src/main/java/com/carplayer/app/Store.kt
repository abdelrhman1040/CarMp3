package com.carplayer.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.preference.PreferenceManager
import android.provider.DocumentsContract

data class Folder(val uri: String, val name: String)
data class Track(val name: String, val uri: String)
data class Device(val mac: String, val name: String)

object Store {

    private fun p(ctx: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    private fun str(ctx: Context, key: String, def: String): String =
        p(ctx).getString(key, def) ?: def

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ')

    // ---------------- folders ----------------

    fun folders(ctx: Context): List<Folder> {
        val out = ArrayList<Folder>()
        for (line in str(ctx, "folders", "").split("\n")) {
            val a = line.split("\t")
            if (a.size >= 2 && a[0].isNotBlank()) out.add(Folder(a[0], a[1]))
        }
        return out
    }

    private fun saveFolders(ctx: Context, l: List<Folder>) {
        p(ctx).edit().putString("folders", l.joinToString("\n") { it.uri + "\t" + clean(it.name) }).apply()
    }

    fun addFolder(ctx: Context, f: Folder): Boolean {
        val l = folders(ctx)
        if (l.any { it.uri == f.uri }) return false
        saveFolders(ctx, l + f)
        return true
    }

    fun removeFolder(ctx: Context, idx: Int) {
        val l = folders(ctx).toMutableList()
        if (idx in l.indices) {
            l.removeAt(idx)
            saveFolders(ctx, l)
        }
    }

    fun moveFolder(ctx: Context, idx: Int, delta: Int): Int {
        val l = folders(ctx).toMutableList()
        val j = idx + delta
        if (idx !in l.indices || j !in l.indices) return idx
        val t = l[idx]
        l[idx] = l[j]
        l[j] = t
        saveFolders(ctx, l)
        return j
    }

    fun treeName(ctx: Context, tree: Uri): String {
        try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            ctx.contentResolver.query(doc, cols, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (e: Exception) {
        }
        return tree.lastPathSegment ?: "Folder"
    }

    private val digits = Regex("\\d+")
    private fun natural(s: String): String = digits.replace(s.lowercase()) { it.value.padStart(12, '0') }

    private fun stripExt(n: String): String {
        val i = n.lastIndexOf('.')
        return if (i > 0 && n.length - i <= 6) n.substring(0, i) else n
    }

    private val audioExt = listOf(".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac", ".opus", ".wma")

    fun listTracks(ctx: Context, f: Folder): List<Track> {
        val out = ArrayList<Track>()
        try {
            val tree = Uri.parse(f.uri)
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            ctx.contentResolver.query(kids, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val low = name.lowercase()
                    if (mime.startsWith("audio/") || audioExt.any { low.endsWith(it) }) {
                        val u = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        out.add(Track(stripExt(name), u.toString()))
                    }
                }
            }
        } catch (e: Exception) {
            Logger.add(ctx, "list failed for ${f.name}: ${e.javaClass.simpleName}")
        }
        return out.sortedBy { natural(it.name) }
    }

    // ---------------- trusted devices ----------------

    fun devices(ctx: Context): List<Device> {
        val out = ArrayList<Device>()
        for (line in str(ctx, "devices", "").split("\n")) {
            val a = line.split("\t")
            if (a.size >= 2 && a[0].isNotBlank()) out.add(Device(a[0], a[1]))
        }
        return out
    }

    private fun saveDevices(ctx: Context, l: List<Device>) {
        p(ctx).edit().putString("devices", l.joinToString("\n") { it.mac + "\t" + clean(it.name) }).apply()
    }

    fun addDevice(ctx: Context, d: Device) {
        val l = devices(ctx)
        if (l.any { it.mac.equals(d.mac, true) }) return
        saveDevices(ctx, l + d)
    }

    fun removeDevice(ctx: Context, mac: String) {
        saveDevices(ctx, devices(ctx).filter { !it.mac.equals(mac, true) })
    }

    fun isTrusted(ctx: Context, mac: String): Boolean =
        anyDevice(ctx) || devices(ctx).any { it.mac.equals(mac, true) }

    fun learnUntil(ctx: Context): Long = p(ctx).getLong("learn_until", 0L)
    fun setLearnUntil(ctx: Context, t: Long) {
        p(ctx).edit().putLong("learn_until", t).apply()
    }

    // ---------------- saved playback state ----------------

    fun curFolder(ctx: Context): String = str(ctx, "cur_folder", "")
    fun curTrack(ctx: Context): Int = p(ctx).getInt("cur_track", 0)
    fun curPos(ctx: Context): Int = p(ctx).getInt("cur_pos", 0)
    fun wasPlaying(ctx: Context): Boolean = p(ctx).getBoolean("was_playing", true)
    fun setWasPlaying(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("was_playing", v).apply()
    }

    private fun fk(f: Folder): String = Integer.toHexString(f.uri.hashCode())

    fun saveProgress(ctx: Context, f: Folder, track: Int, pos: Int) {
        p(ctx).edit()
            .putString("cur_folder", f.uri)
            .putInt("cur_track", track)
            .putInt("cur_pos", pos)
            .putInt("ft_" + fk(f), track)
            .putInt("fp_" + fk(f), pos)
            .apply()
    }

    fun folderTrack(ctx: Context, f: Folder): Int = p(ctx).getInt("ft_" + fk(f), 0)
    fun folderPos(ctx: Context, f: Folder): Int = p(ctx).getInt("fp_" + fk(f), 0)

    // ---------------- settings (keys match res/xml/prefs.xml) ----------------

    fun anyDevice(ctx: Context): Boolean = p(ctx).getBoolean("any_device", false)
    fun onConnect(ctx: Context): String = str(ctx, "on_connect", "last")
    fun resumePos(ctx: Context): Boolean = p(ctx).getBoolean("resume_pos", true)
    fun ignorePauseMs(ctx: Context): Long = (str(ctx, "ignore_pause", "3").toLongOrNull() ?: 3L) * 1000L
    fun folderResume(ctx: Context): Boolean = p(ctx).getBoolean("folder_resume", true)
    fun folderGesture(ctx: Context): Boolean = p(ctx).getBoolean("folder_gesture", true)
    fun gestureMs(ctx: Context): Long = str(ctx, "gesture_ms", "700").toLongOrNull() ?: 700L
    fun carOnly(ctx: Context): Boolean = p(ctx).getBoolean("car_only", false)
    fun idleMin(ctx: Context): Int = str(ctx, "idle_min", "10").toIntOrNull() ?: 10

    fun shuffle(ctx: Context): Boolean = p(ctx).getBoolean("shuffle", false)
    fun setShuffle(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("shuffle", v).apply()
    }

    fun repeat(ctx: Context): String = str(ctx, "repeat", "folder")
    fun setRepeat(ctx: Context, v: String) {
        p(ctx).edit().putString("repeat", v).apply()
    }
}
