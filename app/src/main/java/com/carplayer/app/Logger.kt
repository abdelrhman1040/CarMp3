package com.carplayer.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val main = Handler(Looper.getMainLooper())

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "log.txt")

    @Synchronized
    fun add(ctx: Context, msg: String) {
        try {
            val f = file(ctx)
            if (f.exists() && f.length() > 100000) {
                f.writeText(f.readText().takeLast(40000))
            }
            f.appendText(fmt.format(Date()) + "  " + msg + "\n")
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun read(ctx: Context): String {
        return try {
            val f = file(ctx)
            if (f.exists()) f.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (e: Exception) {
        }
    }
}
