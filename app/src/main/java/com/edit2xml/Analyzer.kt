package com.edit2xml

import android.content.Context
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.*

class Analysis(
    val durMs: Long, val w: Int, val h: Int, val fps: Int,
    val cuts: Int, val bpm: Double, val onsets: Int,
    val hasAudio: Boolean, val xml: File
)

object Analyzer {
    fun run(ctx: Context, uri: Uri, id: String, onP: (Float, String) -> Unit): Analysis {
        val dir = File(ctx.filesDir, "projects").apply { mkdirs() }
        val r = MediaMetadataRetriever()
        r.setDataSource(ctx, uri)
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1080
        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1920
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        var fps = 30
        runCatching {
            val e = MediaExtractor(); e.setDataSource(ctx, uri, null)
            for (i in 0 until e.trackCount) {
                val f = e.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)!!.startsWith("video") && f.containsKey(MediaFormat.KEY_FRAME_RATE))
                    fps = f.getInteger(MediaFormat.KEY_FRAME_RATE)
            }
            e.release()
        }

        // 1) Cut detection: compare 32x32 luminance of frames sampled every step ms
        val step = if (dur > 90_000) 200L else 100L
        val n = (dur / step).toInt().coerceAtLeast(1)
        val diffs = DoubleArray(n)
        val cols = IntArray(n)
        var prev: IntArray? = null
        for (i in 0 until n) {
            val bmp = r.getScaledFrameAtTime(i * step * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 32, 32)
            if (bmp != null) {
                val cnt = bmp.width * bmp.height
                val p = IntArray(cnt)
                bmp.getPixels(p, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val lum = IntArray(cnt)
                var rs = 0L; var gs = 0L; var bs = 0L
                for (k in 0 until cnt) {
                    val c = p[k]
                    val rr = (c shr 16) and 255; val gg = (c shr 8) and 255; val bb = c and 255
                    lum[k] = (rr * 299 + gg * 587 + bb * 114) / 1000
                    rs += rr; gs += gg; bs += bb
                }
                cols[i] = Color.rgb((rs / cnt).toInt(), (gs / cnt).toInt(), (bs / cnt).toInt())
                val pv = prev
                if (pv != null && pv.size == cnt) {
                    var s = 0L
                    for (k in 0 until cnt) s += abs(lum[k] - pv[k])
                    diffs[i] = s.toDouble() / cnt
                }
                prev = lum
                bmp.recycle()
            } else if (i > 0) cols[i] = cols[i - 1]
            onP(0.6f * (i + 1) / n, "s1")
        }
        r.release()
        val mean = diffs.average()
        val sd = sqrt(diffs.map { (it - mean).pow(2) }.average())
        val thr = max(22.0, mean + 3 * sd)
        val cutIdx = ArrayList<Int>()
        for (i in 1 until n) {
            if (diffs[i] > thr && diffs[i] >= diffs[i - 1] && (i + 1 >= n || diffs[i] >= diffs[i + 1]) &&
                (cutIdx.isEmpty() || i - cutIdx.last() >= 2)
            ) cutIdx.add(i)
        }

        // 2) Extract the song (audio track, no re-encode)
        onP(0.62f, "s2")
        val audioFile = File(dir, "$id.m4a")
        val hasAudio = extractAudio(ctx, uri, audioFile)
        if (!hasAudio) audioFile.delete()

        // 3) Beat / onset detection + BPM
        var onsets: List<Long> = emptyList()
        var bpm = 0.0
        if (hasAudio) {
            runCatching { beats(ctx, uri, dur) { onP(0.65f + 0.3f * it, "s3") } }
                .onSuccess { onsets = it.first; bpm = it.second }
        }

        // 4) Write XML
        onP(0.97f, "s4")
        val idx = listOf(0) + cutIdx + listOf(n)
        val sb = StringBuilder()
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<alightmotion version=\"1\" generator=\"Edit2XML\">\n")
        sb.append("  <project name=\"${esc(id)}\" width=\"$w\" height=\"$h\" fps=\"$fps\" durationMs=\"$dur\">\n")
        if (hasAudio) sb.append(String.format(Locale.US, "    <audio file=\"%s\" bpm=\"%.2f\" />\n", esc(audioFile.name), bpm))
        sb.append("    <markers>\n")
        onsets.take(400).forEach { sb.append("      <marker timeMs=\"$it\" />\n") }
        sb.append("    </markers>\n    <timeline>\n")
        for (s in 0 until idx.size - 1) {
            val st = if (s == 0) 0L else idx[s] * step
            val en = if (s == idx.size - 2) dur else idx[s + 1] * step
            val mid = ((idx[s] + idx[s + 1]) / 2).coerceIn(0, n - 1)
            val off = onsets.minByOrNull { abs(it - st) }?.let { it - st }
            sb.append("      <clip index=\"${s + 1}\" startMs=\"$st\" endMs=\"$en\" durationMs=\"${en - st}\" ")
            sb.append(String.format("color=\"#%06X\"", cols[mid] and 0xFFFFFF))
            if (s > 0) sb.append(" transition=\"cut\"")
            if (off != null && s > 0) sb.append(" nearestBeatOffsetMs=\"$off\"")
            sb.append(" />\n")
        }
        sb.append("    </timeline>\n  </project>\n</alightmotion>\n")
        val xml = File(dir, "$id.xml")
        xml.writeText(sb.toString())
        onP(1f, "s4")
        return Analysis(dur, w, h, fps, cutIdx.size, bpm, onsets.size, hasAudio, xml)
    }

    private fun extractAudio(ctx: Context, uri: Uri, out: File): Boolean = runCatching {
        val e = MediaExtractor(); e.setDataSource(ctx, uri, null)
        val ti = (0 until e.trackCount).first { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        e.selectTrack(ti)
        val mx = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val tr = mx.addTrack(e.getTrackFormat(ti))
        mx.start()
        val buf = ByteBuffer.allocate(1 shl 20)
        val bi = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val n = e.readSampleData(buf, 0)
            if (n < 0) break
            val key = if ((e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            bi.set(0, n, e.sampleTime, key)
            mx.writeSampleData(tr, buf, bi)
            e.advance()
        }
        mx.stop(); mx.release(); e.release()
        true
    }.getOrDefault(false)

    private fun beats(ctx: Context, uri: Uri, durMs: Long, prog: (Float) -> Unit): Pair<List<Long>, Double> {
        val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
        val ti = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return Pair(emptyList(), 0.0)
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val hop = 1024
        val en = ArrayList<Float>()
        var acc = 0.0; var cnt = 0
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false; var tick = 0
        try {
            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10000)
                    if (i >= 0) {
                        val b = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            if (tick++ % 40 == 0 && durMs > 0) prog((ex.sampleTime / 1000f / durMs).coerceIn(0f, 1f))
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10000)
                if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val n = sb.remaining()
                    var k = 0
                    while (k < n) {
                        val v = sb.get(k) / 32768.0
                        acc += v * v; cnt++
                        if (cnt == hop) { en.add(sqrt(acc / hop).toFloat()); acc = 0.0; cnt = 0 }
                        k += ch
                    }
                    codec.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
        }
        val m = en.size
        if (m < 20) return Pair(emptyList(), 0.0)
        val fl = FloatArray(m) { if (it == 0) 0f else max(0f, en[it] - en[it - 1]) }
        val hs = hop.toDouble() / sr
        val gap = ceil(0.25 / hs).toInt()
        val on = ArrayList<Long>()
        var last = -gap
        for (i in 1 until m - 1) {
            val lo = max(0, i - 8); val hi = min(m - 1, i + 8)
            var s = 0f
            for (j in lo..hi) s += fl[j]
            val mean = s / (hi - lo + 1)
            if (fl[i] > mean * 1.8f + 0.004f && fl[i] >= fl[i - 1] && fl[i] >= fl[i + 1] && i - last >= gap) {
                on.add((i * hs * 1000).toLong()); last = i
            }
        }
        val lagLo = round(60.0 / 180 / hs).toInt().coerceAtLeast(1)
        val lagHi = round(60.0 / 60 / hs).toInt().coerceAtMost(m - 2)
        var best = 0.0; var bl = 0
        for (l in lagLo..lagHi) {
            var s = 0.0
            for (i in 0 until m - l) s += fl[i] * fl[i + l]
            s /= (m - l)
            if (s > best) { best = s; bl = l }
        }
        return Pair(on, if (bl > 0) 60.0 / (bl * hs) else 0.0)
    }
}
