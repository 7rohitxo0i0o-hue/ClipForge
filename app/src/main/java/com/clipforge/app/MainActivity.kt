package com.clipforge.app

import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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
import androidx.media3.common.Effect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var selectedUri: Uri? = null
    @Volatile private var busy = false

    private data class Word(val text: String, val start: Double, val end: Double)
    private data class Pick(val start: Long, val end: Long, val score: Double)

    private val HI_NAME = "vosk-model-small-hi-0.22"
    private val HI_URL = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"
    private val EN_NAME = "vosk-model-small-en-us-0.15"
    private val EN_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    private val keywords = setOf(
        "क्या", "क्यों", "कैसे", "देखो", "सुनो", "वाओ", "सीक्रेट", "राज", "सच",
        "पैसे", "पैसा", "ज़रूर", "जरूर", "कभी", "सबसे", "बहुत", "गलती", "फ्री", "असली",
        "wow", "secret", "never", "free", "money", "mistake", "truth", "amazing",
        "why", "how", "best", "crazy", "stop", "look"
    )

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
        findViewById<Button>(R.id.btnAuto).setOnClickListener { autoClips() }
        findViewById<Button>(R.id.btnCapEn).setOnClickListener { makeCaptions(EN_NAME, EN_URL, "en") }
        findViewById<Button>(R.id.btnCapHi).setOnClickListener { makeCaptions(HI_NAME, HI_URL, "hi") }
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

    private fun cutRange(uri: Uri, startSec: Long, endSec: Long, done: (String) -> Unit) {
    Thread {
        val items = ArrayList<EditedMediaItem>()
        var s = startSec
        while (s < endSec) {
            val e = minOf(s + 5, endSec)
            val cx = findFaceX(uri, s, e)
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(s * 1000)
                .setEndPositionMs(e * 1000)
                .build()
            val mi = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping).build()
            val fx = androidx.media3.transformer.Effects(
                emptyList(),
                listOf<Effect>(androidx.media3.effect.Crop(cx - 0.4f, cx + 0.4f, -1f, 1f))
            )
            items.add(EditedMediaItem.Builder(mi).setEffects(fx).build())
            s = e
        }
        val outFile = File(cacheDir, "clip_${System.currentTimeMillis()}.mp4")
        val comp = Composition.Builder(
            listOf(androidx.media3.transformer.EditedMediaItemSequence(items))
        ).build()
        runOnUiThread {
            val transformer = Transformer.Builder(this)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done(saveToGallery(outFile))
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        done("Error: " + exportException.message)
                    }
                })
                .build()
            transformer.start(comp, outFile.absolutePath)
        }
    }.start()
    }

private fun cutRangeCrop(uri: Uri, startSec: Long, endSec: Long, cx: Float, done: (String) -> Unit) {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startSec * 1000)
            .setEndPositionMs(endSec * 1000)
            .build()
        val mediaItem = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping).build()
        val effects = androidx.media3.transformer.Effects(
    emptyList(),
    listOf<Effect>(
        androidx.media3.effect.Crop(cx - 0.4f, cx + 0.4f, -1f, 1f)
    )
)
val edited = EditedMediaItem.Builder(mediaItem).setEffects(effects).build()
        val outFile = File(cacheDir, "clip_${System.currentTimeMillis()}.mp4")
        val transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done(saveToGallery(outFile))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    done("Error: " + exportException.message)
                }
            })
            .build()
        transformer.start(edited, outFile.absolutePath)
    }

    private fun cutClip() {
        val uri = selectedUri
        if (uri == null) {
            Toast.makeText(this, "Pehle video chuno", Toast.LENGTH_SHORT).show()
            return
        }
        val s = findViewById<EditText>(R.id.etStart).text.toString().toLongOrNull() ?: 0L
        val e = findViewById<EditText>(R.id.etEnd).text.toString().toLongOrNull() ?: 0L
        if (e <= s) {
            Toast.makeText(this, "End time Start se bada hona chahiye", Toast.LENGTH_SHORT).show()
            return
        }
        val info = findViewById<TextView>(R.id.videoInfo)
        info.text = "Clip ban rahi hai, ruko..."
        cutRange(uri, s, e) { info.text = "Clip taiyar! $it" }
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
        } catch (ex: Exception) {
            "Save error: " + ex.message
        }
    }

    private fun autoClips() {
        val uri = selectedUri
        if (uri == null) {
            Toast.makeText(this, "Pehle video chuno", Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) {
            Toast.makeText(this, "Pehle wala kaam chal raha hai", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        Thread {
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(this, uri)
                val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                mmr.release()
                val totalSec = min(durMs / 1000, 600L)
                if (totalSec < 5) throw Exception("Video bahut chhoti hai")

                val modelDir = ensureModel(HI_NAME, HI_URL)
                setCap("Model load ho raha hai...")
                val energy = DoubleArray((totalSec + 2).toInt())
                val counts = LongArray((totalSec + 2).toInt())
                val words = transcribe(uri, 0, totalSec, modelDir, energy, counts)

                val picks = pickBest(words, energy, counts, totalSec)
                val sb = StringBuilder("Top hisse mile:\n")
                picks.forEachIndexed { i, p ->
                    sb.append("${i + 1}) ${p.start}s - ${p.end}s (score ${"%.2f".format(p.score)})\n")
                }
                setCap(sb.toString())
                runOnUiThread { cutQueue(uri, picks, 0) }
            } catch (ex: Throwable) {
                setCap("Error: " + ex.message)
            } finally {
                busy = false
            }
        }.start()
    }

    private fun cutQueue(uri: Uri, picks: List<Pick>, i: Int) {
        val info = findViewById<TextView>(R.id.videoInfo)
        if (i >= picks.size) {
            info.text = "Auto Clips poori hui! ${picks.size} Shorts Gallery > Movies/ClipForge mein hain"
            return
        }
        info.text = "Clip ${i + 1}/${picks.size} ban rahi hai..."
        cutRange(uri, picks[i].start, picks[i].end) { cutQueue(uri, picks, i + 1) }
    }

    private fun pickBest(
        words: List<Word>, energy: DoubleArray, counts: LongArray, totalSec: Long
    ): List<Pick> {
        val len = if (totalSec >= 40) 30L else totalSec
        if (len == totalSec) return listOf(Pick(0, totalSec, 1.0))

        val rms = DoubleArray(energy.size) { if (counts[it] > 0) sqrt(energy[it] / counts[it]) else 0.0 }
        var maxE = 1e-9
        var maxW = 1e-9
        val eList = ArrayList<Double>()
        val wList = ArrayList<Double>()
        val kList = ArrayList<Double>()
        val starts = ArrayList<Long>()

        var s = 0L
        while (s + len <= totalSec) {
            var e = 0.0
            for (t in s until s + len) e += rms[t.toInt()]
            e /= len
            val inWin = words.filter { it.start >= s && it.start < s + len }
            val w = inWin.size / len.toDouble()
            val k = inWin.count { keywords.contains(it.text.lowercase()) }.toDouble()
            maxE = max(maxE, e)
            maxW = max(maxW, w)
            eList.add(e)
            wList.add(w)
            kList.add(min(k, 3.0) / 3.0)
            starts.add(s)
            s += 5
        }
        val cands = ArrayList<Pick>()
        for (i in starts.indices) {
            val score = 0.4 * (eList[i] / maxE) + 0.3 * (wList[i] / maxW) + 0.3 * kList[i]
            cands.add(Pick(starts[i], starts[i] + len, score))
        }
        cands.sortByDescending { it.score }

        val chosen = ArrayList<Pick>()
        for (c in cands) {
            if (chosen.size >= 3) break
            if (chosen.none { c.start < it.end && c.end > it.start }) chosen.add(c)
        }
        return chosen.sortedBy { it.start }
    }

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
                val words = transcribe(uri, startSec, endSec, modelDir, null, null)
                if (words.isEmpty()) {
                    setCap("Koi bolne wali awaaz nahi mili.")
                } else {
                    val srt = buildSrt(words, startSec.toDouble())
                    val f = File(getExternalFilesDir(null), "captions_${lang}_${System.currentTimeMillis()}.srt")
                    f.writeText(srt)
                    setCap(srt + "\n\n[SRT file: " + f.absolutePath + "]")
                }
            } catch (ex: Throwable) {
                setCap("Error: " + ex.message)
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

    private fun transcribe(
        uri: Uri, startSec: Long, endSec: Long, modelDir: File,
        energy: DoubleArray?, counts: LongArray?
    ): List<Word> {
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
                var sumSq = 0.0
                for (f in 0 until frames) {
                    var sum = 0
                    for (c in 0 until ch) sum += sb.get(f * ch + c).toInt()
                    val v = sum / ch
                    mono[f] = v.toShort()
                    sumSq += v.toDouble() * v.toDouble()
                }
                if (energy != null && counts != null && frames > 0) {
                    val sec = (info.presentationTimeUs / 1_000_000L).toInt()
                    if (sec in energy.indices) {
                        energy[sec] += sumSq
                        counts[sec] += frames.toLong()
                    }
                }
                while (pos < frames) {
                    outBuf.putShort(mono[pos.toInt()])
                    pos += ratio
                }
                pos -= frames
                if (outBuf.position() > 0) {
                    if (rec.acceptWaveForm(outBuf.array(), outBuf.position())) collect(rec.result)
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
            val line = words.subList(i, j + 1).joinToString(" ") { toRoman(it.text) }
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

        private fun findFaceX(uri: Uri, startSec: Long, endSec: Long): Float {
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(this, uri)
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .build()
            )
            var sum = 0f
            var n = 0
            var t = startSec
            while (t < endSec) {
                val bmp = mmr.getFrameAtTime(
                    t * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (bmp != null) {
                    val faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
                    val big = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    if (big != null) {
                        sum += big.boundingBox.centerX().toFloat() / bmp.width
                        n++
                    }
                }
                t += 2
            }
            mmr.release()
            detector.close()
            if (n == 0) return 0f
            val avg = sum / n
            return (avg * 2f - 1f).coerceIn(-0.684f, 0.684f)
        } catch (e: Throwable) {
            return 0f
        }
        }
            private fun toRoman(s: String): String {
        val vowels = mapOf(
            'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
            'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऋ' to "ri"
        )
        val matras = mapOf(
            'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo",
            'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ृ' to "ri"
        )
        val cons = mapOf(
            'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
            'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
            'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
            'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
            'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m",
            'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
            'ष' to "sh", 'स' to "s", 'ह' to "h"
        )
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = if (i + 1 < s.length) s[i + 1] else ' '
            when {
                cons.containsKey(c) -> {
                    sb.append(cons[c])
                    when {
                        matras.containsKey(next) -> { sb.append(matras[next]); i++ }
                        next == '्' -> { i++ }
                        else -> {
                            val atEnd = i + 1 >= s.length || s[i + 1] == ' '
                            if (!atEnd) sb.append("a")
                        }
                    }
                }
                vowels.containsKey(c) -> sb.append(vowels[c])
                c == 'ं' || c == 'ँ' -> sb.append("n")
                c == 'ः' -> sb.append("h")
                c == '़' || c == '्' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
            }
    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
// END OF FILE
