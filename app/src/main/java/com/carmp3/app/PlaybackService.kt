package com.carmp3.app

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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * خدمة التشغيل. تعمل فقط أثناء اتصال البلوتوث (أو تشغيل يدوي من الهاتف) ثم تُغلق نفسها.
 *
 * كل شيء يجري على الخيط الرئيسي (callbacks الخاصة بـ MediaSession و MediaPlayer كلها على الـ main looper)
 * لذلك لا حاجة لأي قفل أو مزامنة.
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_BT_CONNECTED = "com.carmp3.app.BT_CONNECTED"
        const val ACTION_PLAY_TRACK = "com.carmp3.app.PLAY_TRACK"
        const val ACTION_TOGGLE = "com.carmp3.app.TOGGLE"
        const val ACTION_NEXT = "com.carmp3.app.NEXT"
        const val ACTION_PREV = "com.carmp3.app.PREV"
        const val ACTION_NEXT_FOLDER = "com.carmp3.app.NEXT_FOLDER"
        const val ACTION_PREV_FOLDER = "com.carmp3.app.PREV_FOLDER"
        const val ACTION_STOP = "com.carmp3.app.STOP"

        const val EXTRA_ADDRESS = "address"
        const val EXTRA_EXPLICIT = "explicit"
        const val EXTRA_FOLDER_KEY = "folder_key"
        const val EXTRA_TRACK_INDEX = "track_index"

        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 17

        /** أقصى فاصل بين ضغطتين متتاليتين لاعتبارهما نمطاً واحداً. */
        private const val PATTERN_GAP_MS = 2000L

        /** أقصى انتظار لاتصال صوت البلوتوث (A2DP) بعد اتصال الجهاز. */
        private const val ROUTE_TIMEOUT_MS = 15000L

        /** مهلة صغيرة بعد ظهور مسار الصوت حتى لا يضيع أول الصوت. */
        private const val ROUTE_SETTLE_MS = 1500L

        /** في التشغيل اليدوي: إغلاق الخدمة بعد هذه المدة من الإيقاف. */
        private const val IDLE_STOP_MS = 5 * 60 * 1000L

        private const val SAVE_EVERY_MS = 5000L

        @Volatile
        var instance: PlaybackService? = null
    }

    data class Snapshot(
        val folder: String,
        val track: String,
        val playing: Boolean,
        val posMs: Int,
        val durMs: Int,
        val btSession: Boolean
    )

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private lateinit var session: MediaSession

    private var folders: List<Folder> = emptyList()
    private var folderIdx = 0
    private var trackIdx = 0

    private var player: MediaPlayer? = null
    private var prepared = false
    private var pendingSeek = 0

    /** هل المستخدم/النظام يريد التشغيل (حتى لو لم يبدأ الصوت فعلياً بعد). */
    private var playIntent = false
    private var autoStartPending = false
    private var routeReady = true
    private var settlePosted = false
    private var waitingRoute = false

    /** عنوان جهاز البلوتوث الحالي؛ null يعني تشغيلاً يدوياً من الهاتف. */
    private var btAddress: String? = null

    /** بعد انقطاع صوت البلوتوث: نجمّد الحالة المحفوظة حتى لا تُكتب فوقها حالة "متوقف". */
    private var frozen = false

    private var resumeOnFocusGain = false
    private var focusRequest: AudioFocusRequest? = null
    private var errorStreak = 0

    private val presses = ArrayList<Pair<Char, Long>>()

    // ------------------------------------------------------------------ دورة حياة الخدمة

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()

        session = MediaSession(this, "CarMp3Session").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = pause()
                override fun onSkipToNext() = onSkipPress('N')
                override fun onSkipToPrevious() = onSkipPress('P')
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
            })
            isActive = true
        }

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
        updateSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // يجب إظهار الإشعار فوراً بعد startForegroundService
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_BT_CONNECTED -> startBtSession(
                intent.getStringExtra(EXTRA_ADDRESS),
                intent.getBooleanExtra(EXTRA_EXPLICIT, true)
            )
            ACTION_PLAY_TRACK -> playFromUi(
                intent.getStringExtra(EXTRA_FOLDER_KEY),
                intent.getIntExtra(EXTRA_TRACK_INDEX, 0)
            )
            ACTION_TOGGLE -> if (isPlayingNow()) pause() else play()
            ACTION_NEXT -> step(+1)
            ACTION_PREV -> step(-1)
            ACTION_NEXT_FOLDER -> jumpFolder(+1)
            ACTION_PREV_FOLDER -> jumpFolder(-1)
            ACTION_STOP -> {
                saveState()
                stopAll()
            }
            else -> if (player == null && btAddress == null) stopAll()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        try { audio.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        releasePlayer()
        abandonFocus()
        try { session.isActive = false; session.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------------ جلسة البلوتوث

    private fun startBtSession(address: String?, explicit: Boolean) {
        // ACL_CONNECTED قد يصل أكثر من مرة لنفس الجهاز
        if (btAddress != null && btAddress.equals(address, true) && player != null) return

        btAddress = address ?: "unknown"
        frozen = false
        handler.removeCallbacks(idleStop)
        folders = Library.load(this)
        if (folders.isEmpty()) {
            Log.w(TAG, "library empty")
            updateNotification()
            handler.postDelayed({ stopAll() }, 4000)
            return
        }

        restoreLastPosition()
        autoStartPending = when (prefs.startMode) {
            Prefs.MODE_ALWAYS_PLAY -> true
            Prefs.MODE_ALWAYS_PAUSE -> false
            else -> prefs.lastPlaying
        }
        playIntent = autoStartPending

        // نجهّز المقطع الآن (بدون تشغيل)، وننتظر حتى يتصل صوت السيارة قبل البدء
        routeReady = false
        loadTrack(pendingSeek)
        waitForRoute(explicit)
    }

    fun onBtDisconnected(address: String?) {
        val mine = btAddress ?: return
        if (address != null && !mine.equals(address, true)) return
        saveState()
        stopAll()
    }

    private fun btAudioPresent(): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= 31 &&
                    (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!routeReady && !settlePosted && btAudioPresent()) {
                settlePosted = true
                handler.postDelayed({ onRouteReady() }, ROUTE_SETTLE_MS)
            }
        }
    }

    private fun waitForRoute(explicit: Boolean) {
        if (btAudioPresent()) {
            onRouteReady()
            return
        }
        waitingRoute = true
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        handler.postDelayed({
            if (!routeReady) {
                // جهاز معتمد بالاسم لكن لا يظهر مسار صوت: نكمل على أي حال
                // أما في وضع "أي جهاز" فهو غالباً ساعة/جهاز بلا صوت → نغلق
                if (explicit) onRouteReady() else stopAll()
            }
        }, ROUTE_TIMEOUT_MS)
    }

    private fun onRouteReady() {
        if (waitingRoute) {
            waitingRoute = false
            try { audio.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        }
        routeReady = true
        maybeAutoStart()
    }

    private fun maybeAutoStart() {
        if (autoStartPending && prepared && routeReady) {
            autoStartPending = false
            play()
        }
    }

    /** الصوت سيخرج من سماعة الهاتف فجأة (انقطاع البلوتوث) → نوقف ونحفظ الحالة كما كانت. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (btAddress == null) {
                pause()
                return
            }
            saveState()
            frozen = true
            try { if (prepared) player?.pause() } catch (_: Exception) {}
            stopTicker()
            updateSession()
            updateNotification()
        }
    }

    // ------------------------------------------------------------------ التحميل والتشغيل

    private fun currentFolder(): Folder? = folders.getOrNull(folderIdx)
    private fun currentTrack(): Track? = currentFolder()?.tracks?.getOrNull(trackIdx)

    private fun ensureLoaded(): Boolean {
        if (folders.isEmpty()) {
            folders = Library.load(this)
            if (folders.isNotEmpty()) restoreLastPosition()
        }
        if (folders.isEmpty() && btAddress == null) stopAll()
        return folders.isNotEmpty()
    }

    private fun restoreLastPosition() {
        var fi = folders.indexOfFirst { it.key == prefs.lastFolderKey }
        if (fi < 0) fi = 0
        var ti = folders[fi].tracks.indexOfFirst { it.uri == prefs.lastTrackUri }
        val seek = if (ti >= 0) prefs.lastPos else 0
        if (ti < 0) ti = 0
        folderIdx = fi
        trackIdx = ti
        pendingSeek = seek
    }

    private fun loadTrack(startMs: Int) {
        releasePlayer()
        val tr = currentTrack() ?: return
        pendingSeek = startMs
        prepared = false
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(applicationContext, Uri.parse(tr.uri))
            mp.setOnPreparedListener { p ->
                if (player !== p) return@setOnPreparedListener
                prepared = true
                errorStreak = 0
                if (startMs > 0) p.seekTo(startMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                updateSession()
                updateNotification()
                maybeAutoStart()
            }
            mp.setOnCompletionListener { p ->
                if (player === p) step(+1)
            }
            mp.setOnErrorListener { p, what, extra ->
                Log.w(TAG, "player error $what/$extra")
                if (player === p) onPlayerError()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "loadTrack failed", e)
            onPlayerError()
            return
        }
        updateSession()
        updateNotification()
    }

    private fun releasePlayer() {
        val mp = player ?: return
        player = null
        prepared = false
        try {
            mp.setOnPreparedListener(null)
            mp.setOnCompletionListener(null)
            mp.setOnErrorListener(null)
            mp.release()
        } catch (_: Exception) {
        }
    }

    private fun onPlayerError() {
        errorStreak++
        val n = currentFolder()?.tracks?.size ?: 0
        if (n == 0 || errorStreak > n) {
            errorStreak = 0
            playIntent = false
            autoStartPending = false
            releasePlayer()
            updateSession()
            updateNotification()
            return
        }
        step(+1)
    }

    private fun isPlayingNow(): Boolean {
        val mp = player ?: return false
        return try { prepared && mp.isPlaying } catch (e: Exception) { false }
    }

    private fun play() {
        frozen = false
        handler.removeCallbacks(idleStop)

        if (player == null) {
            if (!ensureLoaded()) return
            autoStartPending = true
            routeReady = true
            playIntent = true
            loadTrack(pendingSeek)
            return
        }
        if (!prepared) {
            autoStartPending = true
            routeReady = true
            playIntent = true
            return
        }
        if (!requestFocus()) return
        try {
            player?.start()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "start failed", e)
            return
        }
        playIntent = true
        startTicker()
        updateSession()
        updateNotification()
    }

    private fun pause() {
        resumeOnFocusGain = false
        try { if (isPlayingNow()) player?.pause() } catch (_: Exception) {}
        playIntent = false
        autoStartPending = false
        saveState()
        stopTicker()
        updateSession()
        updateNotification()
        if (btAddress == null) {
            handler.removeCallbacks(idleStop)
            handler.postDelayed(idleStop, IDLE_STOP_MS)
        }
    }

    private fun seekTo(ms: Int) {
        if (!prepared) return
        try { player?.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) {}
        updateSession()
    }

    // ------------------------------------------------------------------ التنقل

    private fun startFresh() {
        frozen = false
        handler.removeCallbacks(idleStop)
        autoStartPending = true
        routeReady = true
        playIntent = true
        loadTrack(0)
        saveState()
    }

    /** الانتقال للمقطع التالي/السابق داخل المجلد الحالي (يلتف عند الأطراف). */
    private fun step(delta: Int) {
        if (!ensureLoaded()) return
        val n = currentFolder()?.tracks?.size ?: return
        if (n == 0) return
        trackIdx = ((trackIdx + delta) % n + n) % n
        startFresh()
    }

    private fun jumpFolder(delta: Int) {
        if (!ensureLoaded()) return
        val n = folders.size
        folderIdx = ((folderIdx + delta) % n + n) % n
        trackIdx = 0
        startFresh()
    }

    private fun playFromUi(key: String?, index: Int) {
        folders = Library.load(this)
        val fi = folders.indexOfFirst { it.key == key }
        if (fi < 0) {
            if (player == null && btAddress == null) stopAll()
            return
        }
        folderIdx = fi
        trackIdx = index.coerceIn(0, folders[fi].tracks.size - 1)
        startFresh()
    }

    /**
     * ضغطة زر من السيارة. تُنفَّذ فوراً بدون حساب مدة الضغط.
     * الأنماط: >> << >>  = المجلد التالي ،  << >> <<  = المجلد السابق (كل ضغطة خلال PATTERN_GAP_MS من التي قبلها).
     */
    private fun onSkipPress(dir: Char) {
        val now = SystemClock.elapsedRealtime()
        if (presses.isNotEmpty() && now - presses.last().second > PATTERN_GAP_MS) presses.clear()
        presses.add(dir to now)
        while (presses.size > 3) presses.removeAt(0)

        if (presses.size == 3) {
            val s = presses.map { it.first }.joinToString("")
            if (s == "NPN") {
                presses.clear()
                jumpFolder(+1)
                return
            }
            if (s == "PNP") {
                presses.clear()
                jumpFolder(-1)
                return
            }
        }
        step(if (dir == 'N') +1 else -1)
    }

    // ------------------------------------------------------------------ تركيز الصوت

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlayingNow()) {
                    resumeOnFocusGain = true
                    try { player?.pause() } catch (_: Exception) {}
                    stopTicker()
                    updateSession()
                    updateNotification()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try { player?.setVolume(0.25f, 0.25f) } catch (_: Exception) {}
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try { player?.setVolume(1f, 1f) } catch (_: Exception) {}
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
            .also { focusRequest = it }
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
    }

    // ------------------------------------------------------------------ حفظ الحالة

    private val ticker = object : Runnable {
        override fun run() {
            saveState()
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, SAVE_EVERY_MS)
    }

    private fun stopTicker() = handler.removeCallbacks(ticker)

    private fun saveState() {
        if (frozen) return
        val f = currentFolder() ?: return
        val t = currentTrack() ?: return
        val pos = if (prepared) {
            try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }
        } else pendingSeek
        prefs.saveState(f.key, t.uri, pos, playIntent)
    }

    private val idleStop = Runnable { if (!isPlayingNow()) stopAll() }

    private fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ واجهة للشاشة

    fun snapshot(): Snapshot {
        val f = currentFolder()
        val t = currentTrack()
        var pos = pendingSeek
        var dur = 0
        if (prepared) {
            try {
                pos = player?.currentPosition ?: pos
                dur = player?.duration ?: 0
            } catch (_: Exception) {
            }
        }
        return Snapshot(f?.name ?: "", t?.name ?: "", isPlayingNow(), pos, dur, btAddress != null)
    }

    fun seekFromUi(ms: Int) = seekTo(ms)

    // ------------------------------------------------------------------ الجلسة والإشعار

    private fun titleText(): String {
        val f = currentFolder()
        val t = currentTrack()
        return if (f != null && t != null) "${f.name} - ${t.name}" else "مشغل السيارة"
    }

    /** يُرسل لشاشة السيارة: «اسم المجلد - اسم الملف». */
    private fun updateSession() {
        val f = currentFolder()
        var dur = -1L
        var pos = 0L
        if (prepared) {
            try {
                dur = (player?.duration ?: -1).toLong()
                pos = (player?.currentPosition ?: 0).toLong()
            } catch (_: Exception) {
            }
        }
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, titleText())
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, titleText())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, f?.name ?: "")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, f?.name ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
            .build()
        session.setMetadata(meta)

        val playing = isPlayingNow()
        val state = when {
            playing -> PlaybackState.STATE_PLAYING
            playIntent && !prepared && player != null -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, pos, if (playing) 1f else 0f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "التشغيل", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playing = isPlayingNow()
        val sub = when {
            folders.isEmpty() -> "لا توجد ملفات: أضف مسارات من التطبيق"
            btAddress != null -> "متصل بالبلوتوث"
            else -> "تشغيل من الهاتف"
        }
        fun act(icon: Int, label: String, action: String) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), label, actionIntent(action)).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titleText())
            .setContentText(sub)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(android.R.drawable.ic_media_previous, "السابق", ACTION_PREV))
            .addAction(
                act(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "إيقاف" else "تشغيل",
                    ACTION_TOGGLE
                )
            )
            .addAction(act(android.R.drawable.ic_media_next, "التالي", ACTION_NEXT))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
    }
}
