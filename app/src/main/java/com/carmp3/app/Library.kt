package com.carmp3.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Track(val name: String, val uri: String)

/** المجلد = أي مجلد يحتوي ملفات صوتية مباشرة. */
data class Folder(val key: String, val name: String, val tracks: List<Track>)

/** فحص المجلدات (عبر Storage Access Framework) وحفظ النتيجة في ملف سريع القراءة. */
object Library {

    private const val FILE = "library.json"
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac")

    // ---------- قراءة / حفظ ----------

    fun load(ctx: Context): List<Folder> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ta = o.getJSONArray("t")
                Folder(
                    o.getString("k"),
                    o.getString("n"),
                    (0 until ta.length()).map { j ->
                        val t = ta.getJSONObject(j)
                        Track(t.getString("n"), t.getString("u"))
                    }
                )
            }
        } catch (e: Exception) {
            Log.w("Library", "load failed", e)
            emptyList()
        }
    }

    fun save(ctx: Context, folders: List<Folder>) {
        val arr = JSONArray()
        folders.forEach { f ->
            val ta = JSONArray()
            f.tracks.forEach { ta.put(JSONObject().put("n", it.name).put("u", it.uri)) }
            arr.put(JSONObject().put("k", f.key).put("n", f.name).put("t", ta))
        }
        File(ctx.filesDir, FILE).writeText(arr.toString())
    }

    // ---------- الفحص ----------

    fun scan(ctx: Context, trees: List<String>): List<Folder> {
        val out = ArrayList<Folder>()
        for (t in trees) {
            try {
                val tree = Uri.parse(t)
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val rootName = displayName(ctx, tree, rootId)
                    ?: rootId.substringAfterLast('/').substringAfterLast(':')
                walk(ctx, tree, rootId, rootName, out, 0)
            } catch (e: Exception) {
                Log.w("Library", "scan failed for $t", e)
            }
        }
        return out.distinctBy { it.key }
    }

    private class Child(val id: String, val name: String, val mime: String) {
        val isDir: Boolean get() = mime == Document.MIME_TYPE_DIR
    }

    private fun walk(ctx: Context, tree: Uri, docId: String, name: String, out: MutableList<Folder>, depth: Int) {
        val kids = children(ctx, tree, docId).filter { !it.name.startsWith(".") }

        val audio = kids
            .filter { !it.isDir && isAudio(it) }
            .sortedWith { a, b -> naturalCompare(a.name.lowercase(), b.name.lowercase()) }

        if (audio.isNotEmpty()) {
            out.add(
                Folder(
                    key = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
                    name = name,
                    tracks = audio.map {
                        Track(
                            it.name.substringBeforeLast('.', it.name),
                            DocumentsContract.buildDocumentUriUsingTree(tree, it.id).toString()
                        )
                    }
                )
            )
        }

        if (depth < 8) {
            kids.filter { it.isDir }
                .sortedWith { a, b -> naturalCompare(a.name.lowercase(), b.name.lowercase()) }
                .forEach { walk(ctx, tree, it.id, it.name, out, depth + 1) }
        }
    }

    private fun isAudio(c: Child): Boolean {
        val ext = c.name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXT || c.mime.startsWith("audio/")
    }

    private fun children(ctx: Context, tree: Uri, parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val res = ArrayList<Child>()
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                res.add(Child(c.getString(0) ?: continue, c.getString(1) ?: "", c.getString(2) ?: ""))
            }
        }
        return res
    }

    private fun displayName(ctx: Context, tree: Uri, docId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        return try {
            ctx.contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** ترتيب طبيعي: 2 قبل 10. */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isDigit()) ie++
                var je = j
                while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0')
                val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                if (a[i] != b[j]) return a[i].compareTo(b[j])
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
