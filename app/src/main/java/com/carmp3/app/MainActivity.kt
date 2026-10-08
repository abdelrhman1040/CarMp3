package com.carmp3.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

private val C_BG = Color.parseColor("#0E1014")
private val C_CARD = Color.parseColor("#1A1D24")
private val C_ITEM = Color.parseColor("#232834")
private val C_TXT = Color.parseColor("#ECEFF4")
private val C_SUB = Color.parseColor("#98A2B3")
private val C_ACC = Color.parseColor("#4DA3FF")
private val C_OK = Color.parseColor("#4ADE80")
private val C_BAD = Color.parseColor("#FF6B6B")

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var dynamic: LinearLayout
    private lateinit var tvNow: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvTime: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: Button

    private var folders: List<Folder> = emptyList()
    private var scanning = false
    private var userSeeking = false

    // ------------------------------------------------------------------ دورة الحياة

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        folders = Library.load(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(C_BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(24), dp(14), dp(32))
        }
        scroll.addView(root, MATCH, WRAP)
        setContentView(scroll)

        root.addView(tv("🚗 مشغل السيارة", 24f, C_TXT, true))
        root.addView(buildPlayerCard())
        dynamic = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(dynamic, MATCH, WRAP)

        requestMissingPermissions()
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                toast("تعذّر حفظ صلاحية المجلد")
                return
            }
            prefs.addTree(uri.toString())
            rescan()
        }
    }

    // ------------------------------------------------------------------ مساعدات الواجهة

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun bg(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, top: Int = 0, start: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply {
            topMargin = dp(top)
            marginStart = dp(start)
        }

    private fun tv(text: String, size: Float = 15f, color: Int = C_TXT, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(label: String, filled: Boolean = true, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            stateListAnimator = null
            setTextColor(if (filled) Color.BLACK else C_ACC)
            background = bg(if (filled) C_ACC else C_ITEM, 12)
            minHeight = 0
            minimumHeight = dp(44)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { onClick() }
        }

    private fun card(title: String?, build: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(C_CARD, 18)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            layoutParams = lp(top = 12)
            if (title != null) addView(tv(title, 17f, C_TXT, true))
            build()
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ------------------------------------------------------------------ بطاقة المشغّل

    private fun buildPlayerCard(): View = card(null) {
        tvNow = tv("لا يوجد مقطع", 17f, C_TXT, true)
        tvSub = tv("", 13f, C_SUB)
        addView(tvNow)
        addView(tvSub.apply { layoutParams = lp(top = 2) })

        seek = SeekBar(this@MainActivity).apply {
            progressTintList = ColorStateList.valueOf(C_ACC)
            thumbTintList = ColorStateList.valueOf(C_ACC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) tvTime.text = fmt(p) + " / " + fmt(sb?.max ?: 0)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userSeeking = false
                    PlaybackService.instance?.seekFromUi(sb?.progress ?: 0)
                }
            })
        }
        addView(seek, lp(top = 10))
        tvTime = tv("0:00 / 0:00", 12f, C_SUB)
        addView(tvTime)

        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = lp(top = 8)
        }
        row.addView(button("⏮", false) { cmd(PlaybackService.ACTION_PREV) }, lp(0, WRAP, 1f))
        btnPlay = button("▶") { cmd(PlaybackService.ACTION_TOGGLE) }
        row.addView(btnPlay, lp(0, WRAP, 1.4f, start = 8))
        row.addView(button("⏭", false) { cmd(PlaybackService.ACTION_NEXT) }, lp(0, WRAP, 1f, start = 8))
        addView(row)

        val row2 = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(top = 8)
        }
        row2.addView(button("المجلد السابق", false) { cmd(PlaybackService.ACTION_PREV_FOLDER) }, lp(0, WRAP, 1f))
        row2.addView(button("المجلد التالي", false) { cmd(PlaybackService.ACTION_NEXT_FOLDER) }, lp(0, WRAP, 1f, start = 8))
        addView(row2)
    }

    private fun cmd(action: String, extra: (Intent.() -> Unit)? = null) {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        extra?.invoke(i)
        try {
            startForegroundService(i)
        } catch (e: Exception) {
            toast("تعذّر تشغيل الخدمة")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            refreshPlayer()
            ui.postDelayed(this, 500)
        }
    }

    private fun refreshPlayer() {
        val s = PlaybackService.instance?.snapshot()
        if (s != null && s.track.isNotEmpty()) {
            tvNow.text = "${s.folder} - ${s.track}"
            tvSub.text = if (s.btSession) "متصل بالبلوتوث" else "تشغيل من الهاتف"
            if (!userSeeking) {
                seek.max = if (s.durMs > 0) s.durMs else 1
                seek.progress = s.posMs.coerceAtLeast(0)
                tvTime.text = fmt(s.posMs) + " / " + fmt(s.durMs)
            }
            btnPlay.text = if (s.playing) "⏸" else "▶"
            return
        }
        // الخدمة متوقفة: نعرض آخر مقطع محفوظ
        val f = folders.firstOrNull { it.key == prefs.lastFolderKey }
        val t = f?.tracks?.firstOrNull { it.uri == prefs.lastTrackUri }
        tvNow.text = if (f != null && t != null) "${f.name} - ${t.name}" else "لا يوجد مقطع"
        tvSub.text = "المشغّل متوقف"
        btnPlay.text = "▶"
        if (!userSeeking) {
            seek.max = 1
            seek.progress = 0
            tvTime.text = "0:00 / 0:00"
        }
    }

    // ------------------------------------------------------------------ الأقسام

    private fun render() {
        if (!::dynamic.isInitialized) return
        dynamic.removeAllViews()
        dynamic.addView(statusCard())
        dynamic.addView(devicesCard())
        dynamic.addView(modeCard())
        dynamic.addView(locationsCard())
        dynamic.addView(libraryCard())
        dynamic.addView(tipsCard())
        refreshPlayer()
    }

    // ----- الأذونات والحالة

    private fun hasBtPerm(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifPerm(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun batteryOk(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestMissingPermissions() {
        val need = ArrayList<String>()
        if (!hasBtPerm()) need.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (!hasNotifPerm()) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun statusCard(): View = card("الحالة") {
        fun line(ok: Boolean, text: String) =
            addView(tv((if (ok) "✅ " else "❌ ") + text, 14f, if (ok) C_OK else C_BAD).apply { layoutParams = lp(top = 6) })

        val bt = hasBtPerm()
        val nt = hasNotifPerm()
        val bat = batteryOk()
        line(bt, "إذن الأجهزة القريبة (البلوتوث)")
        line(nt, "إذن الإشعارات")
        line(bat, "استثناء البطارية (ضروري لهونر)")

        if (!bt || !nt) {
            addView(button("منح الأذونات") { requestMissingPermissions() }.apply { layoutParams = lp(top = 10) })
        }
        if (!bat) {
            addView(button("إلغاء تقييد البطارية") {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (e: ActivityNotFoundException) {
                    toast("افتح إعدادات البطارية يدوياً")
                }
            }.apply { layoutParams = lp(top = 8) })
        }
        addView(button("إعدادات التطبيق (التشغيل التلقائي)", false) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }.apply { layoutParams = lp(top = 8) })
    }

    // ----- أجهزة البلوتوث

    private fun devicesCard(): View = card("أجهزة التشغيل المعتمدة") {
        val devs = prefs.devices()
        if (devs.isEmpty()) {
            addView(
                tv("لا أجهزة مضافة. أي جهاز اسمه يحتوي Captiva أو Chevrolet يُعتمد تلقائياً.", 13f, C_SUB)
                    .apply { layoutParams = lp(top = 6) }
            )
        }
        devs.forEach { d ->
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp(top = 8)
            }
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(d.name.ifBlank { d.address }, 15f, C_TXT, true))
            col.addView(tv(d.address, 11f, C_SUB))
            row.addView(col, lp(0, WRAP, 1f))
            row.addView(button("حذف", false) {
                prefs.removeDevice(d.address)
                render()
            })
            addView(row)
        }

        addView(button("➕ إضافة جهاز بلوتوث") { addDeviceDialog() }.apply { layoutParams = lp(top = 12) })

        val sw = Switch(this@MainActivity).apply {
            text = "العمل مع أي جهاز بلوتوث (قد يشمل الساعة والسماعات)"
            setTextColor(C_TXT)
            isChecked = prefs.anyDevice
            setOnCheckedChangeListener { _, checked -> prefs.anyDevice = checked }
        }
        addView(sw, lp(top = 12))
    }

    private fun connectedAddresses(): Set<String> {
        val am = getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            .map { it.address.uppercase() }
            .toSet()
    }

    private fun addDeviceDialog() {
        if (!hasBtPerm()) {
            requestMissingPermissions()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("شغّل البلوتوث أولاً")
            return
        }
        val connected = connectedAddresses()
        val list: List<BluetoothDevice> = try {
            (adapter.bondedDevices?.toList() ?: emptyList()).sortedBy {
                if (connected.contains(it.address.uppercase())) 0 else 1
            }
        } catch (e: SecurityException) {
            emptyList()
        }
        if (list.isEmpty()) {
            toast("لا توجد أجهزة مقترنة. اقترن بالسيارة من إعدادات البلوتوث أولاً")
            return
        }
        val labels = list.map {
            val on = connected.contains(it.address.uppercase())
            (if (on) "🟢 متصل الآن:  " else "") + (it.name ?: "؟") + "\n" + it.address
        }.toTypedArray<CharSequence>()

        AlertDialog.Builder(this)
            .setTitle("اختر الجهاز لإضافته")
            .setItems(labels) { _, i ->
                val d = list[i]
                prefs.addDevice(d.name ?: d.address, d.address)
                toast("تمت الإضافة")
                render()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ----- سلوك البدء

    private fun modeCard(): View = card("عند اتصال البلوتوث") {
        val rg = RadioGroup(this@MainActivity)
        val labels = listOf("تشغيل دائماً", "إيقاف دائماً", "استكمال الحالة السابقة")
        labels.forEachIndexed { i, label ->
            rg.addView(RadioButton(this@MainActivity).apply {
                id = 100 + i
                text = label
                setTextColor(C_TXT)
                buttonTintList = ColorStateList.valueOf(C_ACC)
                isChecked = prefs.startMode == i
            })
        }
        rg.setOnCheckedChangeListener { _, id -> prefs.startMode = id - 100 }
        addView(rg, lp(top = 6))
    }

    // ----- المسارات

    private fun treeLabel(t: String): String =
        try {
            DocumentsContract.getTreeDocumentId(Uri.parse(t)).replace("primary:", "الذاكرة الداخلية/")
        } catch (e: Exception) {
            t
        }

    private fun locationsCard(): View = card("مسارات الموسيقى") {
        val trees = prefs.trees()
        if (trees.isEmpty()) {
            addView(tv("أضف المجلد الذي يحتوي ملفات الـ MP3 (يُفحص ما بداخله من مجلدات فرعية تلقائياً).", 13f, C_SUB)
                .apply { layoutParams = lp(top = 6) })
        }
        trees.forEach { t ->
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp(top = 8)
            }
            row.addView(tv("📂 " + treeLabel(t), 14f), lp(0, WRAP, 1f))
            row.addView(button("حذف", false) {
                try {
                    contentResolver.releasePersistableUriPermission(Uri.parse(t), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                prefs.removeTree(t)
                rescan()
            })
            addView(row)
        }
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(top = 12)
        }
        row.addView(button("➕ إضافة مسار") { pickFolder() }, lp(0, WRAP, 1f))
        row.addView(button(if (scanning) "جارٍ الفحص..." else "🔄 إعادة فحص", false) { rescan() }, lp(0, WRAP, 1f, start = 8))
        addView(row)
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        try {
            startActivityForResult(i, REQ_FOLDER)
        } catch (e: ActivityNotFoundException) {
            toast("لا يوجد منتقي ملفات في الجهاز")
        }
    }

    private fun rescan() {
        if (scanning) return
        scanning = true
        render()
        val trees = prefs.trees()
        Thread {
            val result: List<Folder>? = try {
                Library.scan(this, trees)
            } catch (e: Exception) {
                null
            }
            if (result != null) Library.save(this, result)
            runOnUiThread {
                scanning = false
                if (result != null) {
                    folders = result
                    toast("تم العثور على ${result.size} مجلد")
                } else {
                    toast("فشل الفحص")
                }
                render()
            }
        }.start()
    }

    // ----- المكتبة

    private fun libraryCard(): View = card("المجلدات (${folders.size})") {
        if (folders.isEmpty()) {
            addView(tv("لا توجد مجلدات. أضف مساراً ثم اضغط إعادة فحص.", 13f, C_SUB).apply { layoutParams = lp(top = 6) })
        }
        folders.forEach { f ->
            addView(
                tv("📁  ${f.name}   (${f.tracks.size})", 15f).apply {
                    background = bg(C_ITEM, 12)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = lp(top = 8)
                    setOnClickListener { showTracks(f) }
                }
            )
        }
    }

    private fun showTracks(f: Folder) {
        val names = f.tracks.mapIndexed { i, t -> "${i + 1}.  ${t.name}" }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(names) { _, i ->
                cmd(PlaybackService.ACTION_PLAY_TRACK) {
                    putExtra(PlaybackService.EXTRA_FOLDER_KEY, f.key)
                    putExtra(PlaybackService.EXTRA_TRACK_INDEX, i)
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    // ----- نصائح

    private fun tipsCard(): View = card("لتعمل تلقائياً على هونر 90") {
        addView(
            tv(
                "1) افتح التطبيق مرة واحدة على الأقل بعد التثبيت.\n" +
                    "2) امنح إذن «الأجهزة القريبة» و«الإشعارات».\n" +
                    "3) اضغط «إلغاء تقييد البطارية» أعلاه.\n" +
                    "4) الإعدادات ← البطارية ← تشغيل التطبيقات (App launch) ← مشغل السيارة ← إدارة يدوية، وفعّل الثلاثة: " +
                    "التشغيل التلقائي، التشغيل الثانوي، التشغيل في الخلفية.\n" +
                    "5) لا تغلق التطبيق بـ «Force stop» ولا تمسحه من مدير المهام بالقوة.\n\n" +
                    "أزرار السيارة:  >> << >>  = المجلد التالي ،  << >> <<  = المجلد السابق (كل ضغطة خلال ثانيتين من التي قبلها).",
                13f, C_SUB
            ).apply { layoutParams = lp(top = 8) }
        )
    }

    companion object {
        private const val REQ_FOLDER = 2
    }
}
