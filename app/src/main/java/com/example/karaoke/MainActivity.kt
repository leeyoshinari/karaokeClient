package com.example.karaoke

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.gson.Gson
import okhttp3.*
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private lateinit var videoPlayer: ExoPlayer
    private lateinit var vocalsPlayer: ExoPlayer
    private lateinit var accPlayer: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var tvPlayingText: TextView

    private lateinit var soundPool: SoundPool
    private val soundEffectMap = mutableMapOf<String, Int>()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val gson = Gson()
    private var sseEventSource: EventSource? = null

    private var serverIp = ""
    private var vocalsVolume = 1.0f
    private var accVolume = 1.0f

    // 对应 JS 的 singsList = [];
    private var singsList = mutableListOf<Song>()

    // 对应 JS 的 videoReady 和 audioReady 状态
    private var isVideoReady = false
    private var isVocalsReady = false
    private var isAccReady = false
    private var isPlayIntent = false

    // A/V 同步线程
    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            if (videoPlayer.isPlaying && vocalsPlayer.isPlaying) {
                val videoPos = videoPlayer.currentPosition
                val audioPos = vocalsPlayer.currentPosition
                val diff = videoPos - audioPos

                when {
                    diff > 80 -> {
                        vocalsPlayer.setPlaybackSpeed(1.03f)
                        accPlayer.setPlaybackSpeed(1.03f)
                    }
                    diff < -80 -> {
                        vocalsPlayer.setPlaybackSpeed(0.97f)
                        accPlayer.setPlaybackSpeed(0.97f)
                    }
                    else -> {
                        if (abs(diff) < 30) {
                            vocalsPlayer.setPlaybackSpeed(1.0f)
                            accPlayer.setPlaybackSpeed(1.0f)
                        }
                    }
                }

                if (abs(diff) > 1000) {
                    vocalsPlayer.seekTo(videoPos)
                    accPlayer.seekTo(videoPos)
                }
            }
            syncHandler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player_view)
        tvPlayingText = findViewById(R.id.tv_playing_text)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initSoundEffects()

        val prefs = getSharedPreferences("KTV_PREFS", Context.MODE_PRIVATE)
        serverIp = prefs.getString("server", "http://192.168.1.20:15200") ?: "http://192.168.1.20:15200"
        vocalsVolume = prefs.getFloat("vocalsVolume", 1.0f)
        accVolume = prefs.getFloat("accompanimentVolume", 1.0f)

        showIpConfigDialog()
    }

    private fun initSoundEffects() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(audioAttributes)
            .build()
        try {
            soundEffectMap["daxiao"] = soundPool.load(this, R.raw.daxiao, 1)
            soundEffectMap["guzhang"] = soundPool.load(this, R.raw.guzhang, 1)
            soundEffectMap["huanhu"] = soundPool.load(this, R.raw.huanhu, 1)
            soundEffectMap["xixu"] = soundPool.load(this, R.raw.xixu, 1)
        } catch (e: Exception) {
            Log.e("KTV_DEBUG", "加载音效失败: ${e.message}")
        }
    }

    private fun playUserInterruption(flag: String) {
        soundEffectMap[flag]?.let { soundId ->
            soundPool.play(soundId, 0.9f, 0.9f, 1, 0, 1.0f)
        }
    }

    private fun showIpConfigDialog() {
        val editText = EditText(this).apply {
            setText(serverIp)
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }

        AlertDialog.Builder(this)
            .setTitle("设置服务器 IP")
            .setView(editText)
            .setCancelable(false)
            .setPositiveButton("保存并进入") { _, _ ->
                serverIp = editText.text.toString().trim()
                if (serverIp.endsWith("/")) {
                    serverIp = serverIp.substring(0, serverIp.length - 1)
                }
                getSharedPreferences("KTV_PREFS", Context.MODE_PRIVATE)
                    .edit { putString("server", serverIp) }

                initPlayers()
                startSSE()

                // 对应 window.onload = function() { loadSing(); ... }
                Thread { loadSingPure(flag = false) }.start()
            }.show()
    }

    // ================== 🛠️ 像素级同步复制 JS 核心逻辑 🛠️ ==================

    // 对应 JS: getSingList = (flag = false) => { ... async: flag ... }
    // 强制同步阻塞死等，直观顺次执行
    private fun getSingListPure() {
        val request = Request.Builder().url("$serverIp/song/singHistory/pendingAll").build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val res = gson.fromJson(bodyStr, ApiResponse::class.java)
                    if (res.code == 0) {
                        singsList.clear()
                        singsList.addAll(res.data)
                    } else {
                        showToast(res.msg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("KTV_DEBUG", "getSingListPure 出错", e)
        }
    }

    // 对应 JS: showTips = () => { ... }
    private fun showTipsPure() {
        runOnUiThread {
            var playinText = "暂未开始播放"
            if (singsList.isNotEmpty()) {
                if (singsList[0].is_sing == -1) {
                    playinText = "当前播放：" + singsList[0].name
                    playinText += if (singsList.size > 1) "，下一首：" + singsList[1].name else "，暂无下一首歌曲"
                } else {
                    playinText += "，下一首：" + singsList[0].name
                }
            } else {
                playinText += "，暂无下一首歌曲"
            }
            tvPlayingText.text = playinText
        }
    }

    // 对应 JS: setSinged = (flag = false) => { ... }
    private fun setSingedPure() {
        if (singsList.isEmpty()) return
        val request = Request.Builder().url("$serverIp/song/setSinged/${singsList[0].id}").build()
        try { client.newCall(request).execute().close() } catch (e: Exception) { e.printStackTrace() }
    }

    // 对应 JS: setSinging = (flag = false) => { ... }
    private fun setSingingPure() {
        if (singsList.isEmpty()) return
        val request = Request.Builder().url("$serverIp/song/setSinging/${singsList[0].id}").build()
        try { client.newCall(request).execute().close() } catch (e: Exception) { e.printStackTrace() }
    }

    // 对应 JS: send_message = (code, data) => { ... }
    private fun sendMessagePure(code: Int, data: String) {
        val request = Request.Builder().url("$serverIp/song/send/event?code=$code&data=$data").build()
        try { client.newCall(request).execute().close() } catch (e: Exception) { e.printStackTrace() }
    }

    // 对应 JS: loadSing = async (flag=false) => { ... }
    private fun loadSingPure(flag: Boolean) {
        getSingListPure()
        if (singsList.isEmpty()) return

        runOnUiThread {
            isVideoReady = false
            isVocalsReady = false
            isAccReady = false
            isPlayIntent = flag // 传递自动播放意图

            val fileName = singsList[0].name
            videoPlayer.stop(); vocalsPlayer.stop(); accPlayer.stop()

            videoPlayer.setMediaItem(MediaItem.fromUri("$serverIp/download/$fileName.mp4"))
            vocalsPlayer.setMediaItem(MediaItem.fromUri("$serverIp/download/${fileName}_vocals.mp3"))
            accPlayer.setMediaItem(MediaItem.fromUri("$serverIp/download/${fileName}_accompaniment.mp3"))

            videoPlayer.prepare()
            vocalsPlayer.prepare()
            accPlayer.prepare()
        }
        // 对应 JS 末尾的 showTips()
        showTipsPure()
    }

    // 对应 JS: tryPlay = () => { ... }
    private fun tryPlayPure() {
        if (isVideoReady && isVocalsReady && isAccReady) {
            isPlayIntent = false
            val pos = videoPlayer.currentPosition
            vocalsPlayer.seekTo(pos)
            accPlayer.seekTo(pos)

            videoPlayer.play()
            vocalsPlayer.play()
            accPlayer.play()
        }
    }

    // 对应 JS: first_play = () => { ... }
    private fun firstPlayPure() {
        if (singsList.isNotEmpty()) {
            if (!isVideoReady || !isVocalsReady || !isAccReady) {
                loadSingPure(flag = true)
            } else {
                runOnUiThread { tryPlayPure() }
            }
        }
    }

    // 对应 JS: nextSong = () => { ... }
    private fun nextSongPure() {
        if (singsList.isNotEmpty()) {
            setSingedPure()
        }
        getSingListPure()
        if (singsList.isEmpty()) {
            runOnUiThread {
                tvPlayingText.text = "当前没有待播放的歌曲，快去点歌吧 ~"
                videoPlayer.stop(); vocalsPlayer.stop(); accPlayer.stop()
            }
            return
        }
        singsList.removeAt(0) // 对应 singsList.shift()
        loadSingPure(flag = true)
    }

    // 对应 JS: reSing = () => { ... }
    private fun reSingPure() {
        runOnUiThread {
            videoPlayer.seekTo(0); vocalsPlayer.seekTo(0); accPlayer.seekTo(0)
            videoPlayer.play(); vocalsPlayer.play(); accPlayer.play()
        }
    }

    // ================== 🎬 播放器状态监听器（完美还原 JS 事件） 🎬 ==================
    @OptIn(UnstableApi::class)
    private fun initPlayers() {
        val tvLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                10000, // minBufferMs：最小缓冲 15 秒（默认 50 秒太大）
                30000, // maxBufferMs：最大缓冲 30 秒
                1500,  // bufferForPlaybackMs：弹窗后多少毫秒开始播放
                2000   // bufferForPlaybackAfterRebufferMs
            )
            .build()
        videoPlayer = ExoPlayer.Builder(this).setLoadControl(tvLoadControl).build().apply { volume = 0f }
        vocalsPlayer = ExoPlayer.Builder(this).build().apply { volume = vocalsVolume }
        accPlayer = ExoPlayer.Builder(this).build().apply { volume = accVolume }

        playerView.player = videoPlayer

        videoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    isVideoReady = true
                    if (isPlayIntent) tryPlayPure()
                } else if (state == Player.STATE_ENDED) {
                    // 对应 JS: video.addEventListener('ended', () => { ... nextSong(); })
                    isVideoReady = false; isVocalsReady = false; isAccReady = false
                    Thread { nextSongPure() }.start()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    if (!vocalsPlayer.isPlaying) vocalsPlayer.play()
                    if (!accPlayer.isPlaying) accPlayer.play()

                    // 【核心修复】对应 JS: video.addEventListener('play', () => { ... })
                    Thread {
                        setSingingPure()         // 1. 同步上报正在唱
                        sendMessagePure(1, "3") // 2. 同步发送开播事件
                        getSingListPure()        // 3. 同步拉取带有 is_sing=-1 的新列表
                        showTipsPure()           // 4. 彻底刷新当前播放文案
                    }.start()
                } else {
                    // 对应 JS: video.addEventListener('pause', () => { ... })
                    if (videoPlayer.playbackState != Player.STATE_ENDED) {
                        if (vocalsPlayer.isPlaying) vocalsPlayer.pause()
                        if (accPlayer.isPlaying) accPlayer.pause()
                        Thread { sendMessagePure(1, "4") }.start()
                    }
                }
            }
        })

        vocalsPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    isVocalsReady = true
                    if (isPlayIntent) tryPlayPure()
                }
            }
        })

        accPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    isAccReady = true
                    if (isPlayIntent) tryPlayPure()
                }
            }
        })

        syncHandler.post(syncRunnable)
    }

    // ================== 📡 SSE 远程长连接事件同步 📡 ==================

    private fun startSSE() {
        val request = Request.Builder().url("$serverIp/song/events").build()
        sseEventSource = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val msg = gson.fromJson(data, SseMessage::class.java)
                    handleSseMessage(msg)
                } catch (e: Exception) { Log.e("SSE", "JSON 解析失败", e) }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.e("SSE", "SSE 连接断开", t)
            }
        })
    }

    private fun handleSseMessage(msg: SseMessage) {
        when (msg.code) {
            1 -> {
                if (msg.data == "0") runOnUiThread {
                    if (videoPlayer.isPlaying) {
                        videoPlayer.pause()
                    } else {
                        Thread { sendMessagePure(1, "4") }.start()
                    }
                }
                if (msg.data == "1") {
                    runOnUiThread {
                        isPlayIntent = true
                        tryPlayPure()
                    }
                }
                if (msg.data == "5") {
                    Thread { firstPlayPure() }.start()
                }
            }
            2 -> reSingPure()
            3 -> Thread { nextSongPure() }.start()
            4 -> {
                runOnUiThread {
                    if (msg.data == "0") vocalsPlayer.volume = vocalsVolume
                    if (msg.data == "1") vocalsPlayer.volume = 0f
                }
            }
            5 -> {
                vocalsVolume = msg.data.toFloat()
                runOnUiThread { vocalsPlayer.volume = vocalsVolume }
                getSharedPreferences("KTV_PREFS", Context.MODE_PRIVATE).edit {
                    putFloat("vocalsVolume", vocalsVolume)
                }
            }
            6 -> {
                accVolume = msg.data.toFloat()
                runOnUiThread { accPlayer.volume = accVolume }
                getSharedPreferences("KTV_PREFS", Context.MODE_PRIVATE).edit {
                    putFloat("accompanimentVolume", accVolume)
                }
            }
            7 -> runOnUiThread { playUserInterruption(msg.data) }
            8 -> {
                Thread {
                    getSingListPure()
                    showTipsPure()
                }.start()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (videoPlayer.isPlaying) {
                    videoPlayer.pause()
                } else {
                    isPlayIntent = true
                    tryPlayPure()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                Thread { nextSongPure() }.start()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        sseEventSource?.cancel()
        syncHandler.removeCallbacks(syncRunnable)
        videoPlayer.release()
        vocalsPlayer.release()
        accPlayer.release()
        soundPool.release()
    }
}

// ========== 数据实体类 ==========
data class ApiResponse(val code: Int, val msg: String, val data: List<Song>)
data class Song(val id: Int, val name: String, val is_sing: Int)
data class SseMessage(val code: Int, val data: String)