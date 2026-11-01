package com.clipforge.app

import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var selectedUri: Uri? = null
    @Volatile private var busy = false

    private data class Word(val text: String, val start: Double, val end: Double)

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

        findViewById<Button>(R.id.btnPick).setOnClickListener { pickVideo.launch("video/*") }
        findViewById<Button>(R.id.btnCut).setOnClickListener { cutClip() }
        findViewById<Button>(R.id.btnCapEn).setOnClickListener {
            makeCaptions(
                "vosk-model-small-en-us-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                "en"
            )
        }
        findViewById<Button>(R.id.btnCapHi).setOnClickListener {
            makeCaptions(
                "vosk-model-small-hi-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
                "hi"
            )
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

    // ---------------- CUT CLIP ----------------
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
                    info.text = "Clip taiyar! " + saveToGallery(outFile)
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
            val target = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
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

    // ---------------- CAPTIONS ----------------
    private fun setCap(text: String) {
        runOnUiThread { findViewById<TextView>(R.id.tvCaptions).text = text }
    }

    private fun makeCaptions(modelName: String, modelUrl: String, lang: String) {
        val uri = selectedUri
        if (uri == null) {
            Toast.makeText(this, "Pehle video chuno", Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) {
            Toast.makeText(this, "Pehle wala kaam chal raha hai", Toast.LENGTH_SHORT).show()
            return
        }
        val startSec = findViewById<EditText>(R.id.etStart).text.toString().toLongOrNull() ?: 0L
        val endSec = findViewById<EditText>(R.id.etEnd).text.toString().toLongOrNull() ?: 0L
        if (endSec <= startSec) {
            Toast.makeText(this, "End time Start se bada hona chahiye", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        Thread {
            try {
                val modelDir = ensureModel(modelName, modelUrl)
                setCap("Model load ho raha hai...")
                val words = transcribe(uri, startSec, endSec, modelDir)
                if (words.isEmpty()) {
                    setCap("Koi bolne wali awaaz nahi mili. Dusri language try karo ya time range badlo.")
                } else {
                    val srt = buildSrt(words, startSec.toDouble())
                    val f = File(getExternalFilesDir(null), "captions_${lang}_${System.currentTimeMillis()}.srt")
                    f.writeText(srt)
                    setCap(srt + "\n\n[SRT file: " + f.absolutePath + "]")
                }
            } catch (e: Throwable) {
                setCap("Error: " + e.message)
            } finally {
                busy = false
            }
        }.start()
    }

    private fun ensureModel(name: String, url: String): File {
        val modelsRoot = File(filesDir, "models")
        modelsRoot.mkdirs()
        val modelDir = File(modelsRoot, name)
        if (File(modelDir, "am").exists()) return modelDir

        val zipFile = File(cacheDir, "$name.zip")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.connect()
        val total = conn.contentLength.toLong()
        var done = 0L
        var lastShown = -1
        conn.inputStream.use { input ->
            FileOutputStream(zipFile).use { out ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastShown) {
                            lastShown = pct
                            setCap("Model download ho raha hai (sirf pehli baar): $pct%")
                        }
                    }
                }
            }
        }

        setCap("Model unzip ho raha hai...")
        val rootCanon = modelsRoot.canonicalPath
        ZipInputStream(zipFile.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val outFile = File(modelsRoot, entry.name)
                if (!outFile.canonicalPath.startsWith(rootCanon)) continue
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zin.copyTo(it) }
                }
            }
        }
        zipFile.delete()
        return modelDir
    }

    private fun transcribe(uri: Uri, startSec: Long, endSec: Long, modelDir: File): List<Word> {
        val words = ArrayList<Word>()
        val model = Model(modelDir.absolutePath)
        val rec = Recognizer(model, 16000.0f)
        rec.setWords(true)

        fun collect(json: String) {
            val arr = JSONObject(json).optJSONArray("result") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                words.add(Word(o.getString("word"), o.getDouble("start"), o.getDouble("end")))
            }
        }

        val extractor = MediaExtractor()
        extractor.setDataSource(this, uri, null)
        var trackIdx = -1
        var fmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIdx = i
                fmt = f
                break
            }
        }
        if (trackIdx < 0 || fmt == null) throw Exception("Video mein awaaz nahi mili")

        extractor.selectTrack(trackIdx)
        extractor.seekTo(startSec * 1_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val endUs = endSec * 1_000_000L

        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var pos = 0.0
        var lastPct = -1

        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10000)
                if (ii >= 0) {
                    val ib = codec.getInputBuffer(ii)!!
                    val n = extractor.readSampleData(ib, 0)
                    val t = extractor.sampleTime
                    if (n < 0 || t > endUs) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, t, 0)
                        extractor.advance()
                        val pct = (((t / 1_000_000L) - startSec) * 100 / (endSec - startSec)).toInt()
                        if (pct != lastPct && pct in 0..100) {
                            lastPct = pct
                            setCap("Awaaz pehchani ja rahi hai: $pct%")
                        }
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val nf = codec.outputFormat
                inRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (oi >= 0) {
                val ob = codec.getOutputBuffer(oi)!!
                ob.position(info.offset)
                ob.limit(info.offset + info.size)
                val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frames = sb.remaining() / ch
                val ratio = inRate / 16000.0
                val outBuf = ByteBuffer.allocate(((frames / ratio).toInt() + 4) * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                val mono = ShortArray(frames)
                for (f in 0 until frames) {
                    var sum = 0
                    for (c in 0 until ch) sum += sb.get(f * ch + c).toInt()
                    mono[f] = (sum / ch).toShort()
                }
                while (pos < frames) {
                    outBuf.putShort(mono[pos.toInt()])
                    pos += ratio
                }
                pos -= frames
                if (outBuf.position() > 0) {
                    val bytes = outBuf.array()
                    val len = outBuf.position()
                    if (rec.acceptWaveForm(bytes, len)) collect(rec.result)
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }

        collect(rec.finalResult)
        codec.stop()
        codec.release()
        extractor.release()
        rec.close()
        model.close()
        return words
    }

    private fun buildSrt(words: List<Word>, offsetSec: Double): String {
        val sb = StringBuilder()
        var idx = 1
        var i = 0
        while (i < words.size) {
            var j = i
            while (j + 1 < words.size && (j - i) < 5 && words[j + 1].start - words[j].end < 0.8) j++
            val line = words.subList(i, j + 1).joinToString(" ") { it.text }
            sb.append(idx).append('\n')
            sb.append(fmtTime(words[i].start + offsetSec)).append(" --> ")
                .append(fmtTime(words[j].end + offsetSec)).append('\n')
            sb.append(line).append("\n\n")
            idx++
            i = j + 1
        }
        return sb.toString()
    }

    private fun fmtTime(t: Double): String {
        val ms = (t * 1000).toLong()
        return String.format(
            "%02d:%02d:%02d,%03d",
            ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000
        )
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
