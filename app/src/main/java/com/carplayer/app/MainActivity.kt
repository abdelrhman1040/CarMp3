package com.carplayer.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val ACTION_AUTOPLAY = "com.carplayer.app.AUTOPLAY"
        private const val REQ_TREE = 10
    }

    private val h = Handler(Looper.getMainLooper())

    private lateinit var spinner: Spinner
    private lateinit var songsHeader: Button
    private lateinit var spacer: View
    private lateinit var listView: ListView
    private lateinit var listAdapter: ArrayAdapter<String>
    private lateinit var nowText: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnShuffle: Button
    private lateinit var btnRepeat: Button

    private var folders: List<Folder> = emptyList()
    private var tracks: List<Track> = emptyList()
    private var shownFolder = -1
    private var dragging = false
    private var lastMark = ""
    private var songsOpen = false
    private var lastPlayingFolder = ""

    private val tick = object : Runnable {
        override fun run() {
            refreshPlayer()
            h.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return r
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun send(action: String, extra: (Intent) -> Unit = {}) {
        val i = Intent(this, PlayerService::class.java).setAction(action)
        extra(i)
        startForegroundService(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(10), dp(6), dp(10), dp(6))

        root.addView(
            row(
                btn("Devices") { showDevices() },
                btn("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
                btn("Log") { showLog() }
            )
        )

        spinner = Spinner(this)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showFolder(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(row(spinner, btn("+ Folder") { addFolder() }))
        root.addView(
            row(
                btn("Remove") { removeFolder() },
                btn("Move up") { moveFolder(-1) },
                btn("Move down") { moveFolder(1) }
            )
        )

        songsHeader = btn("") { toggleSongs() }
        root.addView(
            songsHeader,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        listView = ListView(this)
        listView.adapter = listAdapter
        listView.setOnItemClickListener { _, _, pos, _ -> playTrack(pos) }
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        spacer = View(this)
        root.addView(spacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        applySongsOpen()

        nowText = TextView(this)
        nowText.textSize = 16f
        nowText.setPadding(0, dp(8), 0, 0)
        root.addView(nowText)

        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeText.text = fmt(progress) + " / " + fmt(sb?.max ?: 0)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
                if (PlayerService.instance != null) {
                    val p = sb?.progress ?: 0
                    send(PlayerService.A_SEEK) { it.putExtra("pos", p) }
                }
            }
        })
        root.addView(seek)

        timeText = TextView(this)
        root.addView(timeText)

        btnPlay = btn("\u25B6") { send(PlayerService.A_PLAY_PAUSE) }
        btnPlay.textSize = 22f
        root.addView(
            row(
                btn("Folder \u25C0") { folderStep(PlayerService.A_PREV_FOLDER) },
                btn("\u23EE") { trackStep(PlayerService.A_PREV) },
                btnPlay,
                btn("\u23ED") { trackStep(PlayerService.A_NEXT) },
                btn("\u25B6 Folder") { folderStep(PlayerService.A_NEXT_FOLDER) }
            )
        )

        btnShuffle = btn("") {
            Store.setShuffle(this, !Store.shuffle(this))
            refreshPlayer()
        }
        btnRepeat = btn("") {
            val order = listOf("off", "one", "folder", "all")
            val next = order[(order.indexOf(Store.repeat(this)) + 1) % order.size]
            Store.setRepeat(this, next)
            refreshPlayer()
        }
        root.addView(row(btnShuffle, btnRepeat))

        setContentView(root)

        requestPermissions(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS),
            1
        )

        if (intent?.action == ACTION_AUTOPLAY) send(PlayerService.A_BT_CONNECTED)
    }

    override fun onResume() {
        super.onResume()
        reloadFolders(-1)
        h.removeCallbacks(tick)
        h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }

    // ---------------- player controls ----------------

    private fun trackStep(action: String) {
        send(action)
    }

    private fun folderStep(action: String) {
        send(action)
    }

    private fun toggleSongs() {
        songsOpen = !songsOpen
        applySongsOpen()
    }

    private fun applySongsOpen() {
        listView.visibility = if (songsOpen) View.VISIBLE else View.GONE
        spacer.visibility = if (songsOpen) View.GONE else View.VISIBLE
        updateSongsHeader()
    }

    private fun updateSongsHeader() {
        val arrow = if (songsOpen) "\u25B4" else "\u25BE"
        val name = if (shownFolder in folders.indices) folders[shownFolder].name else "-"
        songsHeader.text = "$arrow  Songs in $name (${tracks.size})"
    }

    private fun playTrack(pos: Int) {
        if (shownFolder !in folders.indices) return
        val uri = folders[shownFolder].uri
        songsOpen = false
        applySongsOpen()
        send(PlayerService.A_PLAY_TRACK) {
            it.putExtra("folder", uri)
            it.putExtra("index", pos)
        }
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
    }

    private fun refreshPlayer() {
        btnShuffle.text = "Shuffle: " + (if (Store.shuffle(this)) "on" else "off")
        btnRepeat.text = "Repeat: " + Store.repeat(this)

        val s = PlayerService.instance?.snap()
        if (s != null && s.folderUri.isNotEmpty() && s.folderUri != lastPlayingFolder) {
            // The playing folder changed (for example from the steering wheel): follow it.
            lastPlayingFolder = s.folderUri
            val fi = folders.indexOfFirst { it.uri == s.folderUri }
            if (fi >= 0 && fi != shownFolder) {
                spinner.setSelection(fi)
                showFolder(fi)
                return
            }
        }
        if (s == null) {
            lastPlayingFolder = ""
            nowText.text = "Not playing"
            btnPlay.text = "\u25B6"
            if (!dragging) {
                seek.max = 1
                seek.progress = 0
            }
            timeText.text = ""
            markList(-1)
            return
        }
        nowText.text = if (s.waiting) "Waiting for the car's audio...\n" + s.title else s.title
        btnPlay.text = if (s.playing) "\u23F8" else "\u25B6"
        if (!dragging) {
            seek.max = if (s.dur > 0) s.dur else 1
            seek.progress = s.pos
            timeText.text = fmt(s.pos) + " / " + fmt(s.dur)
        }
        val idx = if (shownFolder in folders.indices && folders[shownFolder].uri == s.folderUri) s.track else -1
        markList(idx)
    }

    private fun markList(playingIdx: Int) {
        val mark = "$shownFolder:$playingIdx:${tracks.size}"
        if (mark == lastMark) return
        lastMark = mark
        listAdapter.clear()
        for ((i, t) in tracks.withIndex()) {
            listAdapter.add((if (i == playingIdx) "\u25B6  " else "") + t.name)
        }
        listAdapter.notifyDataSetChanged()
    }

    // ---------------- folders ----------------

    private fun reloadFolders(select: Int) {
        folders = Store.folders(this)
        val names = folders.map { it.name }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        if (folders.isEmpty()) {
            showFolder(-1)
            return
        }
        val saved = Store.curFolder(this)
        val sel = when {
            select in folders.indices -> select
            shownFolder in folders.indices -> shownFolder
            else -> {
                val k = folders.indexOfFirst { it.uri == saved }
                if (k >= 0) k else 0
            }
        }
        spinner.setSelection(sel)
        showFolder(sel)
    }

    private fun showFolder(i: Int) {
        shownFolder = i
        tracks = if (i in folders.indices) Store.listTracks(this, folders[i]) else emptyList()
        lastMark = ""
        updateSongsHeader()
        refreshPlayer()
    }

    private fun addFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, REQ_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Logger.add(this, "persist permission failed: ${e.javaClass.simpleName}")
            }
            val added = Store.addFolder(this, Folder(uri.toString(), Store.treeName(this, uri)))
            if (!added) toast("Folder already in the list")
            reloadFolders(Store.folders(this).size - 1)
        }
    }

    private fun removeFolder() {
        if (shownFolder !in folders.indices) return
        val f = folders[shownFolder]
        AlertDialog.Builder(this)
            .setTitle("Remove \"${f.name}\" from the app?")
            .setMessage("The files stay on your phone.")
            .setPositiveButton("Remove") { _, _ ->
                try {
                    contentResolver.releasePersistableUriPermission(
                        Uri.parse(f.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                }
                Store.removeFolder(this, shownFolder)
                reloadFolders(0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveFolder(delta: Int) {
        if (shownFolder !in folders.indices) return
        val ni = Store.moveFolder(this, shownFolder, delta)
        reloadFolders(ni)
    }

    // ---------------- devices ----------------

    private fun showDevices() {
        val devs = Store.devices(this)
        val labels = devs.map { it.name + "  [" + it.mac + "]" }.toTypedArray()
        val b = AlertDialog.Builder(this)
            .setTitle(if (devs.isEmpty()) "No devices yet" else "Tap a device to remove it")
            .setPositiveButton("Add paired") { _, _ -> pickPaired() }
            .setNeutralButton("Add by connecting") { _, _ -> armLearn() }
            .setNegativeButton("Close", null)
        if (devs.isNotEmpty()) {
            b.setItems(labels) { _, which -> confirmRemoveDevice(devs[which]) }
        }
        b.show()
    }

    private fun confirmRemoveDevice(d: Device) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${d.name}?")
            .setPositiveButton("Remove") { _, _ -> Store.removeDevice(this, d.mac) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun armLearn() {
        Store.setLearnUntil(this, System.currentTimeMillis() + 120000L)
        Toast.makeText(
            this,
            "Now connect (or reconnect) the device within 2 minutes",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun pickPaired() {
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            val paired: List<BluetoothDevice> = (bm.adapter?.bondedDevices ?: emptySet<BluetoothDevice>()).toList()
            if (paired.isEmpty()) {
                toast("No paired devices (or Bluetooth permission not granted)")
                return
            }
            val labels = paired.map { (it.name ?: "?") + "  [" + it.address + "]" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Add a paired device")
                .setItems(labels) { _, which ->
                    val d = paired[which]
                    Store.addDevice(this, Device(d.address, d.name ?: d.address))
                    toast("Added")
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: SecurityException) {
            toast("Allow the Bluetooth permission first")
        }
    }

    // ---------------- log ----------------

    private fun showLog() {
        val tv = TextView(this)
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 11f
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(12), dp(12), dp(12), dp(12))
        tv.text = Logger.read(this).takeLast(15000)
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Log")
            .setView(sv)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("log", Logger.read(this)))
                toast("Log copied")
            }
            .setNeutralButton("Clear") { _, _ -> Logger.clear(this) }
            .setNegativeButton("Close", null)
            .show()
    }
}
