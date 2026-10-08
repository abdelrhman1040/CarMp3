package com.carplayer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlin.random.Random

data class Snap(
    val title: String,
    val playing: Boolean,
    val pos: Int,
    val dur: Int,
    val folderUri: String,
    val track: Int,
    val waiting: Boolean
)

class PlayerService : Service() {

    companion object {
        @Volatile
        var instance: PlayerService? = null

        const val A_BT_CONNECTED = "com.carplayer.app.BT_CONNECTED"
        const val A_PLAY_PAUSE = "com.carplayer.app.PLAY_PAUSE"
        const val A_NEXT = "com.carplayer.app.NEXT"
        const val A_PREV = "com.carplayer.app.PREV"
        const val A_NEXT_FOLDER = "com.carplayer.app.NEXT_FOLDER"
        const val A_PREV_FOLDER = "com.carplayer.app.PREV_FOLDER"
        const val A_SEEK = "com.carplayer.app.SEEK"
        const val A_PLAY_TRACK = "com.carplayer.app.PLAY_TRACK"
        const val A_STOP = "com.carplayer.app.STOP"

        private const val CH = "player"
        private const val NOTIF_ID = 1

        private val ACTIONS: Long = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO
    }

    private val h = Handler(Looper.getMainLooper())
    private var ready = false
    private var session: MediaSession? = null
    private var mp: MediaPlayer? = null
    private var prepared = false

    private var folders: List<Folder> = emptyList()
    private var folderIdx = 0
    private var tracks: List<Track> = emptyList()
    private var trackIdx = 0
    private val history = ArrayList<Int>()

    private var wantPlay = false
    private var carConnected = false
    private var connectedAt = 0L
    private var carPauseAt = 0L
    private var wantBeforeCarPause = false
    private var resumeOnGain = false
    private var errStreak = 0
    private var graceOver = true
    private var lastMeta = ""

    private val gbuf = ArrayList<Int>()
    private var focusReq: AudioFocusRequest? = null

    private fun log(m: String) = Logger.add(this, m)

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setup()
        val a = intent?.action
        when (a) {
            A_BT_CONNECTED -> onCarConnected()
            A_PLAY_PAUSE -> togglePlay()
            A_NEXT -> skip(1)
            A_PREV -> skip(-1)
            A_NEXT_FOLDER -> changeFolder(1)
            A_PREV_FOLDER -> changeFolder(-1)
            A_SEEK -> seekTo(intent?.getIntExtra("pos", 0) ?: 0)
            A_PLAY_TRACK -> playFromUi(intent?.getStringExtra("folder") ?: "", intent?.getIntExtra("index", 0) ?: 0)
            A_STOP -> {
                saveProgress()
                stopSelf()
            }
        }
        if (!carConnected && !wantPlay) scheduleIdle()
        return START_NOT_STICKY
    }

    private fun setup() {
        if (ready) return
        ready = true
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW))
        val s = MediaSession(this, "CarPlayer")
        s.setCallback(cb)
        s.isActive = true
        session = s
        startForeground(
            NOTIF_ID,
            buildNotification("Car Player", false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        registerReceiver(noisyRx, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(devCb, h)
        updateState()
        h.postDelayed(tickR, 5000)
        log("service up")
    }

    override fun onDestroy() {
        saveProgress()
        h.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(noisyRx)
        } catch (e: Exception) {
        }
        try {
            getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(devCb)
        } catch (e: Exception) {
        }
        releasePlayer()
        try {
            val r = focusReq
            if (r != null) getSystemService(AudioManager::class.java).abandonAudioFocusRequest(r)
        } catch (e: Exception) {
        }
        session?.isActive = false
        session?.release()
        session = null
        instance = null
        log("service down")
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Car connection
    // ------------------------------------------------------------------

    fun onCarConnected() {
        setup()
        val already = carConnected
        carConnected = true
        connectedAt = SystemClock.elapsedRealtime()
        carPauseAt = 0L
        cancelIdle()
        if (!already) {
            // Bluetooth audio (A2DP) connects a moment after the link itself.
            // Wait for it so the first seconds don't play on the phone speaker.
            graceOver = false
            h.removeCallbacks(graceR)
            h.postDelayed(graceR, 10000)
        }
        log("car connected")
        if (isPlayingNow()) return
        if (already && mp != null) return
        restoreLast(false)
    }

    fun onCarDisconnected() {
        if (!carConnected) return
        saveProgress()
        val now = SystemClock.elapsedRealtime()
        // Many head units send "pause" just before the Bluetooth link drops.
        // That must not count as "I was paused".
        if (carPauseAt != 0L && now - carPauseAt < 3000) {
            Store.setWasPlaying(this, wantBeforeCarPause)
        }
        carConnected = false
        log("car disconnected -> stopping")
        stopSelf()
    }

    private fun restoreLast(forcePlay: Boolean) {
        folders = Store.folders(this)
        if (folders.isEmpty()) {
            log("no folders added yet")
            return
        }
        val uri = Store.curFolder(this)
        var fi = folders.indexOfFirst { it.uri == uri }
        if (fi < 0) fi = 0
        if (!loadFolder(fi)) return
        val resume = Store.resumePos(this)
        val ti = if (resume) Store.curTrack(this) else 0
        val pos = if (resume) Store.curPos(this) else 0
        val play = if (forcePlay) true else when (Store.onConnect(this)) {
            "play" -> true
            "pause" -> false
            else -> Store.wasPlaying(this)
        }
        playIndex(ti, pos, play)
    }

    // ------------------------------------------------------------------
    // Media session (car buttons)
    // ------------------------------------------------------------------

    private val cb = object : MediaSession.Callback() {
        override fun onPlay() {
            log("car/session: play")
            userPlay()
        }

        override fun onPause() {
            val now = SystemClock.elapsedRealtime()
            if (carConnected && now - connectedAt < Store.ignorePauseMs(this@PlayerService)) {
                log("car/session: pause ignored (just connected)")
                return
            }
            if (carPauseAt == 0L || now - carPauseAt > 3000) wantBeforeCarPause = wantPlay
            carPauseAt = now
            log("car/session: pause")
            userPause()
        }

        override fun onStop() {
            userPause()
        }

        override fun onSkipToNext() {
            press(1)
        }

        override fun onSkipToPrevious() {
            press(-1)
        }

        override fun onSeekTo(pos: Long) {
            seekTo(pos.toInt())
        }
    }

    private val flushR = Runnable { flushGesture() }

    // Gesture: next,prev,next = next folder ; prev,next,prev = previous folder
    private fun press(dir: Int) {
        val gestureOn = Store.folderGesture(this) && Store.folders(this).size > 1
        if (!gestureOn) {
            skip(dir)
            return
        }
        h.removeCallbacks(flushR)
        gbuf.add(dir)
        if (gbuf == listOf(1, -1, 1)) {
            gbuf.clear()
            log("gesture: next folder")
            changeFolder(1)
            return
        }
        if (gbuf == listOf(-1, 1, -1)) {
            gbuf.clear()
            log("gesture: previous folder")
            changeFolder(-1)
            return
        }
        while (gbuf.isNotEmpty() && !isGesturePrefix(gbuf)) {
            skip(gbuf.removeAt(0))
        }
        if (gbuf.isNotEmpty()) h.postDelayed(flushR, Store.gestureMs(this))
    }

    private fun isGesturePrefix(b: List<Int>): Boolean {
        val a = listOf(1, -1, 1)
        val c = listOf(-1, 1, -1)
        return b.size <= 3 && (b == a.take(b.size) || b == c.take(b.size))
    }

    private fun flushGesture() {
        val l = ArrayList(gbuf)
        gbuf.clear()
        for (d in l) skip(d)
    }

    private val noisyRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            log("audio route lost -> pause")
            pausePlayer()
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    fun togglePlay() {
        if (isPlayingNow()) userPause() else userPlay()
    }

    private fun userPlay() {
        setWant(true)
        cancelIdle()
        if (mp == null) restoreLast(true) else startPlayer()
    }

    private fun userPause() {
        setWant(false)
        pausePlayer()
        scheduleIdle()
    }

    private fun setWant(v: Boolean) {
        wantPlay = v
        Store.setWasPlaying(this, v)
    }

    private fun skip(dir: Int) {
        if (tracks.isEmpty()) {
            restoreLast(true)
            return
        }
        playIndex(stepIndex(dir), 0, true)
    }

    private fun stepIndex(dir: Int): Int {
        val n = tracks.size
        if (n <= 1) return 0
        if (Store.shuffle(this)) {
            if (dir > 0) {
                history.add(trackIdx)
                if (history.size > 50) history.removeAt(0)
                var r = Random.nextInt(n)
                while (r == trackIdx) r = Random.nextInt(n)
                return r
            }
            if (history.isNotEmpty()) return history.removeAt(history.size - 1)
        }
        return (trackIdx + dir + n) % n
    }

    private fun changeFolder(dir: Int) {
        val fs = Store.folders(this)
        if (fs.size < 2) return
        saveProgress()
        val ni = (folderIdx + dir + fs.size) % fs.size
        folders = fs
        if (!loadFolder(ni)) return
        val f = folders[ni]
        if (Store.folderResume(this)) {
            playIndex(Store.folderTrack(this, f), Store.folderPos(this, f), true)
        } else {
            playIndex(0, 0, true)
        }
    }

    private fun playFromUi(folderUri: String, index: Int) {
        folders = Store.folders(this)
        val fi = folders.indexOfFirst { it.uri == folderUri }
        if (fi < 0) return
        if (!loadFolder(fi)) return
        playIndex(index, 0, true)
    }

    fun seekTo(ms: Int) {
        try {
            if (prepared) mp?.seekTo(ms)
        } catch (e: Exception) {
        }
        updateState()
    }

    fun snap(): Snap {
        val playing = isPlayingNow()
        val waiting = wantPlay && !playing && mp != null && !outputAllowed()
        return Snap(
            titleNow(),
            playing,
            posNow(),
            durNow(),
            if (folderIdx in folders.indices) folders[folderIdx].uri else "",
            trackIdx,
            waiting
        )
    }

    // ------------------------------------------------------------------
    // Playlist / player
    // ------------------------------------------------------------------

    private fun loadFolder(fi: Int): Boolean {
        folders = Store.folders(this)
        if (fi !in folders.indices) return false
        folderIdx = fi
        tracks = Store.listTracks(this, folders[fi])
        history.clear()
        if (tracks.isEmpty()) {
            log("empty folder: ${folders[fi].name}")
            return false
        }
        return true
    }

    private fun playIndex(ti: Int, pos: Int, play: Boolean) {
        if (tracks.isEmpty()) return
        trackIdx = ti.coerceIn(0, tracks.size - 1)
        setWant(play)
        prepare(pos)
    }

    private fun prepare(pos: Int) {
        releasePlayer()
        val t = tracks[trackIdx]
        val p = MediaPlayer()
        mp = p
        prepared = false
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(this, Uri.parse(t.uri))
        } catch (e: Exception) {
            log("cannot open ${t.name}: ${e.javaClass.simpleName}")
            mp = null
            p.release()
            onPlayerError()
            return
        }
        p.setOnPreparedListener {
            prepared = true
            errStreak = 0
            if (pos > 0 && pos < it.duration) it.seekTo(pos)
            if (wantPlay) startPlayer()
            updateAll()
        }
        p.setOnCompletionListener { onTrackEnd() }
        p.setOnErrorListener { _, what, extra ->
            log("player error $what/$extra")
            h.post { onPlayerError() }
            true
        }
        p.prepareAsync()
        updateAll()
    }

    private fun onPlayerError() {
        errStreak++
        if (errStreak <= tracks.size && tracks.size > 1) {
            playIndex(stepIndex(1), 0, wantPlay)
        } else {
            log("too many errors, stopping")
            setWant(false)
            releasePlayer()
            updateAll()
        }
    }

    private fun onTrackEnd() {
        val rep = Store.repeat(this)
        if (rep == "one") {
            try {
                mp?.seekTo(0)
            } catch (e: Exception) {
            }
            startPlayer()
            return
        }
        val atEnd = trackIdx == tracks.size - 1 && !Store.shuffle(this)
        if (atEnd) {
            when (rep) {
                "all" -> {
                    val fs = Store.folders(this)
                    if (fs.isNotEmpty() && loadFolder((folderIdx + 1) % fs.size)) playIndex(0, 0, true)
                }
                "folder" -> playIndex(0, 0, true)
                else -> playIndex(0, 0, false)
            }
            return
        }
        playIndex(stepIndex(1), 0, true)
    }

    private fun startPlayer() {
        val p = mp ?: return
        if (!prepared) return
        cancelIdle()
        if (!outputAllowed()) {
            log("waiting for the car's audio")
            updateAll()
            return
        }
        if (requestFocus()) {
            try {
                p.setVolume(1f, 1f)
                p.start()
            } catch (e: Exception) {
                log("start failed: ${e.javaClass.simpleName}")
            }
        } else {
            log("audio focus denied")
        }
        updateAll()
    }

    private fun pausePlayer() {
        try {
            if (prepared) mp?.pause()
        } catch (e: Exception) {
        }
        updateAll()
    }

    private fun releasePlayer() {
        val p = mp
        mp = null
        prepared = false
        if (p != null) {
            try {
                p.setOnPreparedListener(null)
                p.setOnCompletionListener(null)
                p.setOnErrorListener(null)
                p.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun isPlayingNow(): Boolean = try {
        prepared && mp?.isPlaying == true
    } catch (e: Exception) {
        false
    }

    private fun posNow(): Int = try {
        if (prepared) (mp?.currentPosition ?: 0) else 0
    } catch (e: Exception) {
        0
    }

    private fun durNow(): Int = try {
        if (prepared) (mp?.duration ?: 0) else 0
    } catch (e: Exception) {
        0
    }

    private fun saveProgress() {
        if (!prepared || tracks.isEmpty() || folderIdx !in folders.indices) return
        Store.saveProgress(this, folders[folderIdx], trackIdx, posNow())
    }

    // ------------------------------------------------------------------
    // Audio focus
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Car audio route
    // ------------------------------------------------------------------

    private fun carAudioPresent(): Boolean {
        val am = getSystemService(AudioManager::class.java)
        val devs = Store.devices(this)
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) continue
            val addr: String = d.address ?: ""
            if (Store.anyDevice(this) || addr.isBlank() || devs.any { it.mac.equals(addr, true) }) return true
        }
        return false
    }

    private fun outputAllowed(): Boolean {
        if (Store.carOnly(this)) return carAudioPresent()
        if (carConnected && !graceOver) return carAudioPresent()
        return true
    }

    private val devCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (wantPlay && prepared && !isPlayingNow()) startPlayer()
        }
    }

    private val graceR = Runnable {
        graceOver = true
        log("car audio wait over")
        if (wantPlay && prepared && !isPlayingNow()) startPlayer()
    }

    private val afl = AudioManager.OnAudioFocusChangeListener { change -> onFocus(change) }

    private fun requestFocus(): Boolean {
        val am = getSystemService(AudioManager::class.java)
        val r = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(afl, h)
            .build()
            .also { focusReq = it }
        return am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun onFocus(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                try {
                    mp?.setVolume(1f, 1f)
                } catch (e: Exception) {
                }
                if (resumeOnGain && wantPlay) {
                    resumeOnGain = false
                    startPlayer()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnGain = false
                setWant(false)
                pausePlayer()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnGain = isPlayingNow()
                pausePlayer()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    mp?.setVolume(0.2f, 0.2f)
                } catch (e: Exception) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Idle / tick
    // ------------------------------------------------------------------

    private val idleR = Runnable {
        if (!carConnected && !isPlayingNow()) {
            log("idle -> stopping")
            saveProgress()
            stopSelf()
        }
    }

    private fun scheduleIdle() {
        cancelIdle()
        val m = Store.idleMin(this)
        if (m > 0 && !carConnected) h.postDelayed(idleR, m * 60000L)
    }

    private fun cancelIdle() {
        h.removeCallbacks(idleR)
    }

    private val tickR = object : Runnable {
        override fun run() {
            if (isPlayingNow()) {
                saveProgress()
                updateState()
            }
            h.postDelayed(this, 5000)
        }
    }

    // ------------------------------------------------------------------
    // Metadata, state, notification
    // ------------------------------------------------------------------

    // Car screen shows: folder name + space + file name
    private fun titleNow(): String {
        if (folderIdx in folders.indices && trackIdx in tracks.indices) {
            return folders[folderIdx].name + " " + tracks[trackIdx].name
        }
        return "Car Player"
    }

    private fun updateAll() {
        val s = session ?: return
        val title = titleNow()
        val dur = durNow().toLong()
        val key = "$title|$dur"
        if (key != lastMeta) {
            lastMeta = key
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
                    .build()
            )
        }
        updateState()
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(title, isPlayingNow()))
        } catch (e: Exception) {
        }
    }

    private fun updateState() {
        val s = session ?: return
        val playing = isPlayingNow()
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    posNow().toLong(),
                    if (playing) 1f else 0f
                )
                .build()
        )
    }

    private fun act(icon: Int, label: String, action: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, req,
            Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun buildNotification(title: String, playing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { style.setMediaSession(it.sessionToken) }
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(act(android.R.drawable.ic_media_previous, "Previous", A_PREV, 1))
            .addAction(
                act(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play",
                    A_PLAY_PAUSE, 2
                )
            )
            .addAction(act(android.R.drawable.ic_media_next, "Next", A_NEXT, 3))
            .setStyle(style)
            .build()
    }
}
