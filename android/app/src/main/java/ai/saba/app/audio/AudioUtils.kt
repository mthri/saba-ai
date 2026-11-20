package ai.saba.app.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object AudioUtils {
    const val ROBOT_SAMPLE_RATE = 16000
    const val BYTES_PER_SECOND = ROBOT_SAMPLE_RATE * 2 // 32,000 B/s

    fun pcm16LeBytesToFloatArray(pcmBytes: ByteArray): FloatArray {
        val sampleCount = pcmBytes.size / 2
        val out = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            out[i] = sample / 32768.0f
        }
        return out
    }

    fun shortArrayToPcm16LeBytes(shorts: ShortArray, count: Int = shorts.size): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = shorts[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        return out
    }

    fun floatArrayToPcm16LeBytes(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            val s = (clamped * 32767.0f).roundToInt().coerceIn(-32768, 32767)
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        return out
    }

    fun computeRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) {
            sumSq += (s * s).toDouble()
        }
        return sqrt(sumSq / samples.size).toFloat().coerceIn(0f, 1f)
    }

    /** Wrap raw 16-bit signed LE mono PCM bytes in a standard 44-byte RIFF WAV header. */
    fun pcm16ToWavBytes(pcmData: ByteArray, sampleRate: Int = ROBOT_SAMPLE_RATE): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalDataLen = pcmData.size + 36

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // PCM format = 1
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val result = ByteArray(44 + pcmData.size)
        System.arraycopy(header.array(), 0, result, 0, 44)
        System.arraycopy(pcmData, 0, result, 44, pcmData.size)
        return result
    }

    /** Linear interpolation resampler for mono float audio. */
    fun resampleMono(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (input.isEmpty() || srcRate <= 0 || srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = maxOf(1, (input.size / ratio).roundToInt())
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx0 = floor(srcPos).toInt().coerceIn(0, input.size - 1)
            val idx1 = (idx0 + 1).coerceIn(0, input.size - 1)
            val frac = (srcPos - idx0).toFloat()
            out[i] = input[idx0] * (1f - frac) + input[idx1] * frac
        }
        return out
    }

    fun downmixToMono(interleavedPcm16: ByteArray, channels: Int): FloatArray {
        val safeChannels = maxOf(1, channels)
        val totalSamples = interleavedPcm16.size / 2
        val frames = totalSamples / safeChannels
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until safeChannels) {
                val byteIdx = (f * safeChannels + c) * 2
                val lo = interleavedPcm16[byteIdx].toInt() and 0xFF
                val hi = interleavedPcm16[byteIdx + 1].toInt()
                val s = ((hi shl 8) or lo).toShort()
                sum += s / 32768.0f
            }
            out[f] = sum / safeChannels
        }
        return out
    }

    /**
     * Converts TTS audio output (WAV, raw 24kHz PCM, or MP3/AAC/OGG) into
     * 16,000 Hz 16-bit Signed LE Mono PCM required by the ESP32 robot speaker.
     */
    fun decodeAudioToPcm16kMono(
        context: Context,
        rawAudio: ByteArray,
        formatHint: String,
    ): ByteArray {
        if (rawAudio.isEmpty()) return ByteArray(0)

        val fmt = formatHint.trim().lowercase()
        // Detect RIFF/WAVE header automatically even if formatHint differs
        val looksLikeWav = rawAudio.size >= 44 &&
            rawAudio[0] == 'R'.code.toByte() &&
            rawAudio[1] == 'I'.code.toByte() &&
            rawAudio[2] == 'F'.code.toByte() &&
            rawAudio[3] == 'F'.code.toByte()

        if (looksLikeWav || fmt == "wav") {
            parseWavToPcm16kMono(rawAudio)?.let { return it }
        }

        if (fmt == "pcm") {
            // OpenAI /audio/speech with response_format="pcm" returns 24 kHz 16-bit signed LE mono
            val floats24k = pcm16LeBytesToFloatArray(rawAudio)
            val floats16k = resampleMono(floats24k, 24000, ROBOT_SAMPLE_RATE)
            return floatArrayToPcm16LeBytes(floats16k)
        }

        // Fallback: MP3 / AAC / OGG / Opus / FLAC via Android MediaExtractor + MediaCodec
        return decodeCompressedWithMediaCodec(context, rawAudio, fmt.ifEmpty { "mp3" })
    }

    private fun parseWavToPcm16kMono(wav: ByteArray): ByteArray? {
        if (wav.size < 44) return null
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        if (riff != "RIFF") return null
        buf.int // file size
        val wave = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        if (wave != "WAVE") return null

        var channels = 1
        var sampleRate = ROBOT_SAMPLE_RATE
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (buf.remaining() >= 8) {
            val chunkId = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val chunkSize = buf.int.coerceAtLeast(0)
            val chunkStart = buf.position()
            if (chunkId == "fmt " && chunkSize >= 16 && buf.remaining() >= 16) {
                buf.short // audioFormat
                channels = buf.short.toInt() and 0xFFFF
                sampleRate = buf.int
                buf.int // byteRate
                buf.short // blockAlign
                bitsPerSample = buf.short.toInt() and 0xFFFF
            } else if (chunkId == "data") {
                dataOffset = chunkStart
                dataSize = minOf(chunkSize, wav.size - chunkStart)
                break
            }
            val nextPos = chunkStart + chunkSize + (chunkSize and 1)
            if (nextPos > wav.size) break
            buf.position(nextPos)
        }

        if (dataOffset < 0 || dataSize <= 0 || bitsPerSample != 16) return null
        val pcmRaw = wav.copyOfRange(dataOffset, dataOffset + dataSize)
        if (channels == 1 && sampleRate == ROBOT_SAMPLE_RATE) {
            return pcmRaw
        }
        val mono = downmixToMono(pcmRaw, channels)
        val resampled = resampleMono(mono, sampleRate, ROBOT_SAMPLE_RATE)
        return floatArrayToPcm16LeBytes(resampled)
    }

    private fun decodeCompressedWithMediaCodec(
        context: Context,
        encodedBytes: ByteArray,
        extension: String,
    ): ByteArray {
        val tempFile = File.createTempFile("saba_tts_", ".$extension", context.cacheDir)
        try {
            tempFile.writeBytes(encodedBytes)
            val extractor = MediaExtractor()
            extractor.setDataSource(tempFile.absolutePath)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) {
                extractor.release()
                throw IllegalStateException("No audio track found in TTS response ($extension)")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val readSize = extractor.readSampleData(inBuf, 0)
                            if (readSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, readSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            if (bufferInfo.size > 0) {
                                val chunk = ByteArray(bufferInfo.size)
                                outBuf.position(bufferInfo.offset)
                                outBuf.get(chunk)
                                pcmOut.write(chunk)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFmt = codec.outputFormat
                            if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
                extractor.release()
            }

            val raw = pcmOut.toByteArray()
            if (channels == 1 && sampleRate == ROBOT_SAMPLE_RATE) return raw
            val mono = downmixToMono(raw, channels)
            val resampled = resampleMono(mono, sampleRate, ROBOT_SAMPLE_RATE)
            return floatArrayToPcm16LeBytes(resampled)
        } finally {
            tempFile.delete()
        }
    }

    /** Synthesizes the same 16kHz test melody as Python server's play_synth. */
    fun generateMelodyPcm16k(): ByteArray {
        val notes = listOf(
            523.25 to 0.18,
            659.25 to 0.18,
            783.99 to 0.18,
            1046.50 to 0.35,
        )
        val out = ByteArrayOutputStream()
        for ((freq, duration) in notes) {
            val totalFrames = (ROBOT_SAMPLE_RATE * duration).toInt()
            val fadeFrames = (ROBOT_SAMPLE_RATE * 0.01).toInt()
            for (i in 0 until totalFrames) {
                val env = when {
                    i < fadeFrames -> i.toDouble() / fadeFrames
                    totalFrames - i < fadeFrames -> (totalFrames - i).toDouble() / fadeFrames
                    else -> 1.0
                }
                val v = (sin(2.0 * PI * freq * (i.toDouble() / ROBOT_SAMPLE_RATE)) * 12000.0 * env)
                    .toInt().coerceIn(-32768, 32767)
                out.write(v and 0xFF)
                out.write((v ushr 8) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** Play 16kHz 16-bit mono PCM directly on the phone speaker (for robot-free testing). */
    suspend fun playPcmOnPhoneSpeaker(pcm16k: ByteArray) {
        if (pcm16k.isEmpty()) return
        val minBuf = AudioTrack.getMinBufferSize(
            ROBOT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ROBOT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.play()
            var offset = 0
            val chunkSize = 2048
            while (offset < pcm16k.size) {
                currentCoroutineContext().ensureActive()
                val len = minOf(chunkSize, pcm16k.size - offset)
                val written = track.write(pcm16k, offset, len)
                if (written <= 0) break
                offset += written
            }
        } catch (e: CancellationException) {
            runCatching { track.pause(); track.flush() }
            throw e
        } finally {
            runCatching {
                track.stop()
                track.release()
            }
        }
    }

    private val persianArabicRegex = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")

    fun isPersianText(text: String): Boolean = persianArabicRegex.containsMatchIn(text)

    /**
     * Renders Persian/Arabic or bilingual text onto a 128x64 monochrome bitmap packed
     * MSB-first (1024 bytes), directly compatible with ESP32 Adafruit_GFX display.show_bitmap.
     */
    fun renderTextToOledBitmap128x64(title: String, body: String): ByteArray {
        val width = 128
        val height = 64
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)

        var topY = 1f
        if (title.isNotBlank()) {
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
            }
            val titleAlign = if (isPersianText(title)) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            val titleLayout = StaticLayout.Builder
                .obtain(title.trim(), 0, title.trim().length, titlePaint, width - 4)
                .setAlignment(titleAlign)
                .setMaxLines(1)
                .build()
            canvas.save()
            canvas.translate(2f, topY)
            titleLayout.draw(canvas)
            canvas.restore()

            topY += titleLayout.height + 2f
            val linePaint = Paint().apply { color = Color.WHITE; strokeWidth = 1f }
            canvas.drawLine(0f, topY, width.toFloat(), topY, linePaint)
            topY += 3f
        }

        val bodyText = body.trim()
        if (bodyText.isNotEmpty()) {
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 12f
                typeface = Typeface.DEFAULT
            }
            val bodyAlign = if (isPersianText(bodyText)) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            val bodyLayout = StaticLayout.Builder
                .obtain(bodyText, 0, bodyText.length, bodyPaint, width - 4)
                .setAlignment(bodyAlign)
                .setLineSpacing(1f, 1.0f)
                .build()
            canvas.save()
            canvas.translate(2f, topY)
            bodyLayout.draw(canvas)
            canvas.restore()
        }

        // Pack 128x64 into 1024 bytes MSB-first
        val packed = ByteArray(width * height / 8)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bmp.getPixel(x, y)
                val lum = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                if (lum > 90) {
                    val byteIndex = (y * width + x) / 8
                    val bitMask = 1 shl (7 - (x % 8))
                    packed[byteIndex] = (packed[byteIndex].toInt() or bitMask).toByte()
                }
            }
        }
        bmp.recycle()
        return packed
    }
}
