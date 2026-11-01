package com.clipforge.app

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) playVideo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickVideo.launch("video/*")
        }
    }

    private fun playVideo(uri: Uri) {
        player?.release()
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        findViewById<PlayerView>(R.id.playerView).player = exo
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true
        findViewById<TextView>(R.id.videoInfo).text = "Video chuna gaya: " + (uri.lastPathSegment ?: "")
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
