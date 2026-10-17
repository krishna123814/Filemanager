package com.krishna.myfiles

import android.content.ComponentName
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var audioIcon: TextView
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var isVideo = true
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5000L)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            titleView.text = mediaMetadata.title ?: ""
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@PlayerActivity, "Play nahi hua: ${error.errorCodeName}", Toast.LENGTH_LONG
            ).show()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // file poori chal gayi -> agli baar shuru se
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                val id = oldPosition.mediaItem?.mediaId
                if (id != null) Session.setPos(this@PlayerActivity, id, 0L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isVideo = intent.getBooleanExtra("video", true)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this)
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        root.addView(playerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        audioIcon = TextView(this)
        audioIcon.text = "🎵"
        audioIcon.textSize = 96f
        audioIcon.alpha = 0.35f
        audioIcon.gravity = Gravity.CENTER
        root.addView(audioIcon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        titleView = TextView(this)
        titleView.setTextColor(Color.WHITE)
        titleView.textSize = 15f
        titleView.maxLines = 2
        titleView.setBackgroundColor(0x88000000.toInt())
        titleView.setPadding(dp(14), dp(14), dp(14), dp(10))
        root.addView(titleView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { v -> titleView.visibility = v }
        )

        setContentView(root)
        applyMode()
    }

    private fun applyMode() {
        audioIcon.visibility = if (isVideo) View.GONE else View.VISIBLE
        if (isVideo) {
            playerView.controllerShowTimeoutMs = 3000
            playerView.controllerHideOnTouch = true
        } else {
            playerView.controllerShowTimeoutMs = 0
            playerView.controllerHideOnTouch = false
            playerView.showController()
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = try {
                f.get()
            } catch (e: Exception) {
                Toast.makeText(this, "Player connect nahi hua", Toast.LENGTH_LONG).show()
                return@addListener
            }
            controller = c
            playerView.player = c
            c.addListener(listener)
            applyPending(c)
            titleView.text = c.mediaMetadata.title ?: ""
            handler.postDelayed(ticker, 5000L)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyPending(c: MediaController) {
        if (!Queue.pending) {
            // notification se aaye ho: jo chal raha hai wahi dikhao
            val name = c.mediaMetadata.title?.toString() ?: ""
            if (name.isNotEmpty()) {
                isVideo = Kinds.of(name, false) == Kind.VIDEO
                applyMode()
            }
            return
        }
        Queue.pending = false
        val list = Queue.media
        if (list.isEmpty()) return
        val idx = Queue.mediaIndex.coerceIn(0, list.size - 1)
        isVideo = list[idx].kind == Kind.VIDEO
        applyMode()

        val items = list.map { e ->
            MediaItem.Builder()
                .setUri(e.url)
                .setMediaId(e.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(e.name).build())
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(e.url)).build()
                )
                .build()
        }
        val saved = Session.getPos(this, list[idx].url)
        c.setMediaItems(items, idx, if (saved > 10_000L) saved else 0L)
        c.prepare()
        c.play()
    }

    private fun savePosition() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val dur = c.duration
        val pos = c.currentPosition
        val save = if (dur > 0 && pos > dur - 15_000L) 0L else pos
        Session.setPos(this, item.mediaId, save)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        savePosition()
        val c = controller
        if (c != null) {
            // video background me pause; audio chalta rahe (notification se control)
            if (isVideo) c.pause()
            c.removeListener(listener)
        }
        playerView.player = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }
}
