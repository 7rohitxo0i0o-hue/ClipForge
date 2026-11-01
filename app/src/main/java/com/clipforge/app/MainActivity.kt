package com.clipforge.app

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var selectedUri: Uri? = null

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                selectedUri = uri
                playVideo(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickVideo.launch("video/*")
        }
        findViewById<Button>(R.id.btnCut).setOnClickListener {
            cutClip()
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
        findViewById<TextView>(R.id.videoInfo).text =
            "Video chuna gaya: " + (uri.lastPathSegment ?: "")
    }

    private fun cutClip() {
        val uri = selectedUri
        if (uri == null) {
            Toast.makeText(this, "Pehle video chuno", Toast.LENGTH_SHORT).show()
            return
        }
        val startSec = findViewById<EditText>(R.id.etStart).text.toString().toLongOrNull() ?: 0L
        val endSec = findViewById<EditText>(R.id.etEnd).text.toString().toLongOrNull() ?: 0L
        if (endSec <= startSec) {
            Toast.makeText(this, "End time Start se bada hona chahiye", Toast.LENGTH_SHORT).show()
            return
        }

        val info = findViewById<TextView>(R.id.videoInfo)
        info.text = "Clip ban rahi hai, ruko..."

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startSec * 1000)
            .setEndPositionMs(endSec * 1000)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipping)
            .build()
        val edited = EditedMediaItem.Builder(mediaItem).build()

        val outFile = File(cacheDir, "clip_${System.currentTimeMillis()}.mp4")

        val transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val savedAt = saveToGallery(outFile)
                    info.text = "Clip taiyar! " + savedAt
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    info.text = "Error: " + exportException.message
                }
            })
            .build()

        transformer.start(edited, outFile.absolutePath)
    }

    private fun saveToGallery(file: File): String {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipForge")
                }
            }
            val target = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            )
            if (target != null) {
                contentResolver.openOutputStream(target)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                "Gallery > Movies/ClipForge mein save hui"
            } else {
                "Save nahi ho paayi"
            }
        } catch (e: Exception) {
            "Save error: " + e.message
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
