package com.example.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

data class TrimmedAudioResult(
    val file: File,
    val extension: String,
    val mimeType: String,
    val durationSeconds: Long
)

object AudioTrimmer {
    private const val TAG = "AudioTrimmer"
    private const val DEFAULT_BUFFER_SIZE = 128 * 1024
    private const val TARGET_AAC_BITRATE = 128_000 // 128 kbps: High quality, compact size (~1MB / minute)
    private const val TARGET_SAMPLE_RATE = 44100
    private const val TIMEOUT_US = 10_000L

    fun trimAudio(
        context: Context,
        audioUri: Uri,
        startMs: Long,
        endMs: Long,
        totalDurationMs: Long
    ): TrimmedAudioResult {
        val shouldTrim = totalDurationMs > 0 && (startMs > 0 || (endMs in 1 until totalDurationMs))

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, audioUri)
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed to read URI: ${e.message}")
        }

        val originalDurationMs = try {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: totalDurationMs
        } catch (e: Exception) {
            totalDurationMs
        }

        val originalMime = try {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } catch (e: Exception) {
            null
        } ?: context.contentResolver.getType(audioUri) ?: "audio/mpeg"

        try {
            retriever.release()
        } catch (ignored: Exception) {}

        val effectiveDurationSec = if (shouldTrim && endMs > startMs) {
            ((endMs - startMs) / 1000L).coerceAtLeast(1L)
        } else {
            (originalDurationMs.coerceAtLeast(totalDurationMs) / 1000L).coerceAtLeast(1L)
        }

        val effectiveStartMs = if (shouldTrim) startMs.coerceAtLeast(0L) else 0L
        val effectiveEndMs = if (shouldTrim && endMs > startMs) endMs else originalDurationMs.coerceAtLeast(totalDurationMs)

        // Primary: Transcode and Compress to High-Quality AAC / M4A (128 kbps)
        try {
            val compressedResult = transcodeAndCompressAudio(
                context = context,
                audioUri = audioUri,
                startMs = effectiveStartMs,
                endMs = effectiveEndMs,
                effectiveDurationSec = effectiveDurationSec
            )
            if (compressedResult != null && compressedResult.file.length() > 1024) {
                Log.i(TAG, "Successfully compressed audio to high-quality AAC: ${compressedResult.file.length()} bytes")
                return compressedResult
            }
        } catch (e: Exception) {
            Log.w(TAG, "Transcoding to AAC failed: ${e.message}, trying fast muxer trimming", e)
        }

        // Secondary: Fast MediaMuxer trimming for AAC/M4A streams
        if (originalMime.contains("mp4", ignoreCase = true) || originalMime.contains("aac", ignoreCase = true)) {
            try {
                val muxedResult = trimViaMediaMuxer(context, audioUri, effectiveStartMs, effectiveEndMs, effectiveDurationSec)
                if (muxedResult != null && muxedResult.file.length() > 1024) {
                    return muxedResult
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaMuxer trimming failed: ${e.message}", e)
            }
        }

        // Tertiary: Frame-aligned MP3 trimming
        val ext = getExtensionFromMime(originalMime)
        val originalTempFile = File.createTempFile("audio_orig_", ".$ext", context.cacheDir)
        try {
            context.contentResolver.openInputStream(audioUri)?.use { input ->
                FileOutputStream(originalTempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("تعذر فتح ملف الصوت الأصلي")
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying original audio stream to temp file", e)
            throw IllegalStateException("تعذر قراءة ملف الصوت: ${e.localizedMessage}")
        }

        if (originalMime.contains("mpeg", ignoreCase = true) || originalMime.contains("mp3", ignoreCase = true) || ext == "mp3") {
            try {
                val mp3Result = trimMp3File(
                    sourceFile = originalTempFile,
                    startMs = effectiveStartMs,
                    endMs = effectiveEndMs,
                    totalDurationMs = originalDurationMs.coerceAtLeast(totalDurationMs),
                    effectiveDurationSec = effectiveDurationSec,
                    context = context
                )
                if (mp3Result != null && mp3Result.file.length() > 1024) {
                    originalTempFile.delete()
                    return mp3Result
                }
            } catch (e: Exception) {
                Log.w(TAG, "MP3 trimming failed: ${e.message}", e)
            }
        }

        // Safe Final Fallback
        Log.i(TAG, "Using original audio file as fallback: ${originalTempFile.length()} bytes")
        return TrimmedAudioResult(originalTempFile, ext, originalMime, effectiveDurationSec)
    }

    /**
     * Transcodes any audio track to High-Quality AAC/M4A (128 kbps, 44.1 kHz, Stereo)
     * within the specified [startMs, endMs] window.
     */
    private fun transcodeAndCompressAudio(
        context: Context,
        audioUri: Uri,
        startMs: Long,
        endMs: Long,
        effectiveDurationSec: Long
    ): TrimmedAudioResult? {
        val outputFile = File.createTempFile("compressed_audio_", ".m4a", context.cacheDir)
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, audioUri, null)

            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) return null

            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(audioTrackIndex)

            val startUs = (startMs * 1000L).coerceAtLeast(0L)
            val endUs = if (endMs > 0) endMs * 1000L else Long.MAX_VALUE
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            // 1. Setup Decoder
            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // 2. Setup Encoder (AAC LC, 128 kbps, 44.1 kHz, 2 channels)
            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            } else {
                2
            }
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(22050, 48000)
            } else {
                TARGET_SAMPLE_RATE
            }

            val outputFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, TARGET_AAC_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_BUFFER_SIZE)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIndex = -1
            var muxerStarted = false

            val decoderInputBufferInfo = MediaCodec.BufferInfo()
            val decoderOutputBufferInfo = MediaCodec.BufferInfo()
            val encoderOutputBufferInfo = MediaCodec.BufferInfo()

            var extractorDone = false
            var decoderDone = false
            var encoderDone = false

            var presentationTimeUs = 0L

            while (!encoderDone) {
                // Feed Extractor -> Decoder
                if (!extractorDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        val sampleTimeUs = extractor.sampleTime

                        if (sampleSize < 0 || (endUs in 1..sampleTimeUs)) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                // Feed Decoder -> Encoder
                if (!decoderDone) {
                    val outBufIndex = decoder.dequeueOutputBuffer(decoderOutputBufferInfo, TIMEOUT_US)
                    if (outBufIndex >= 0) {
                        val decodedBuf = decoder.getOutputBuffer(outBufIndex)
                        val isEOS = (decoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                        if (decodedBuf != null && decoderOutputBufferInfo.size > 0) {
                            val encInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encInputIndex >= 0) {
                                val encInputBuf = encoder.getInputBuffer(encInputIndex)
                                if (encInputBuf != null) {
                                    encInputBuf.clear()
                                    decodedBuf.position(decoderOutputBufferInfo.offset)
                                    decodedBuf.limit(decoderOutputBufferInfo.offset + decoderOutputBufferInfo.size)
                                    encInputBuf.put(decodedBuf)

                                    val flags = if (isEOS) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                    encoder.queueInputBuffer(encInputIndex, 0, decoderOutputBufferInfo.size, presentationTimeUs, flags)
                                    presentationTimeUs += (decoderOutputBufferInfo.size * 1_000_000L) / (sampleRate * channelCount * 2)
                                }
                            }
                        }

                        decoder.releaseOutputBuffer(outBufIndex, false)
                        if (isEOS) decoderDone = true
                    }
                }

                // Feed Encoder -> Muxer
                val encOutIndex = encoder.dequeueOutputBuffer(encoderOutputBufferInfo, TIMEOUT_US)
                if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                } else if (encOutIndex >= 0) {
                    val encodedBuf = encoder.getOutputBuffer(encOutIndex)
                    if (encodedBuf != null && encoderOutputBufferInfo.size > 0 && muxerStarted) {
                        if ((encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            muxer.writeSampleData(muxerTrackIndex, encodedBuf, encoderOutputBufferInfo)
                        }
                    }

                    if ((encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true
                    }
                    encoder.releaseOutputBuffer(encOutIndex, false)
                }
            }

            if (muxerStarted) {
                muxer.stop()
            }

            return if (outputFile.exists() && outputFile.length() > 512) {
                TrimmedAudioResult(outputFile, "m4a", "audio/mp4", effectiveDurationSec)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "transcodeAndCompressAudio exception: ${e.message}")
            if (outputFile.exists()) outputFile.delete()
            return null
        } finally {
            try { decoder?.stop() } catch (ignored: Exception) {}
            try { decoder?.release() } catch (ignored: Exception) {}
            try { encoder?.stop() } catch (ignored: Exception) {}
            try { encoder?.release() } catch (ignored: Exception) {}
            try { muxer?.release() } catch (ignored: Exception) {}
            try { extractor?.release() } catch (ignored: Exception) {}
        }
    }

    private fun trimViaMediaMuxer(
        context: Context,
        audioUri: Uri,
        startMs: Long,
        endMs: Long,
        effectiveDurationSec: Long
    ): TrimmedAudioResult? {
        val outputFile = File.createTempFile("trimmed_audio_", ".m4a", context.cacheDir)
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, audioUri, null)

            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || trackFormat == null) return null

            extractor.selectTrack(audioTrackIndex)

            val startUs = (startMs * 1000L).coerceAtLeast(0L)
            val endUs = if (endMs > 0) endMs * 1000L else Long.MAX_VALUE
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(trackFormat)
            muxer.start()

            val maxInputSize = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                DEFAULT_BUFFER_SIZE
            }
            val buffer = ByteBuffer.allocate(maxInputSize.coerceAtLeast(DEFAULT_BUFFER_SIZE))
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break

                bufferInfo.presentationTimeUs = (sampleTimeUs - startUs).coerceAtLeast(0L)
                val sampleFlags = extractor.sampleFlags
                bufferInfo.flags = if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            return TrimmedAudioResult(outputFile, "m4a", "audio/mp4", effectiveDurationSec)
        } catch (e: Exception) {
            if (outputFile.exists()) outputFile.delete()
            return null
        } finally {
            try { muxer?.release() } catch (ignored: Exception) {}
            try { extractor?.release() } catch (ignored: Exception) {}
        }
    }

    private fun trimMp3File(
        sourceFile: File,
        startMs: Long,
        endMs: Long,
        totalDurationMs: Long,
        effectiveDurationSec: Long,
        context: Context
    ): TrimmedAudioResult? {
        val totalLength = sourceFile.length()
        if (totalLength <= 0 || totalDurationMs <= 0) return null

        val startRatio = (startMs.toDouble() / totalDurationMs.toDouble()).coerceIn(0.0, 1.0)
        val endRatio = if (endMs > 0) (endMs.toDouble() / totalDurationMs.toDouble()).coerceIn(startRatio, 1.0) else 1.0

        var startByte = (startRatio * totalLength).toLong()
        var endByte = (endRatio * totalLength).toLong()

        val outputFile = File.createTempFile("trimmed_audio_", ".mp3", context.cacheDir)

        FileInputStream(sourceFile).use { fis ->
            val headerBytes = ByteArray(10)
            val readHeader = fis.read(headerBytes)
            var audioDataStartOffset = 0L

            if (readHeader == 10 && headerBytes[0] == 'I'.code.toByte() && headerBytes[1] == 'D'.code.toByte() && headerBytes[2] == '3'.code.toByte()) {
                val tagSize = ((headerBytes[6].toInt() and 0x7F) shl 21) or
                        ((headerBytes[7].toInt() and 0x7F) shl 14) or
                        ((headerBytes[8].toInt() and 0x7F) shl 7) or
                        (headerBytes[9].toInt() and 0x7F)
                audioDataStartOffset = 10L + tagSize
            }

            startByte = startByte.coerceAtLeast(audioDataStartOffset)
            endByte = endByte.coerceAtLeast(startByte + 4096).coerceAtMost(totalLength)

            FileInputStream(sourceFile).use { stream ->
                stream.skip(startByte)

                var syncFound = false
                var prevByte = -1
                var extraSkipped = 0L

                while (extraSkipped < 4096) {
                    val currentByte = stream.read()
                    if (currentByte == -1) break
                    extraSkipped++

                    if (prevByte == 0xFF && (currentByte and 0xE0) == 0xE0) {
                        syncFound = true
                        FileOutputStream(outputFile).use { fos ->
                            fos.write(0xFF)
                            fos.write(currentByte)

                            val remainingBytesToCopy = (endByte - (startByte + extraSkipped)).coerceAtLeast(0L)
                            var copied = 0L
                            val buffer = ByteArray(16384)
                            while (copied < remainingBytesToCopy) {
                                val toRead = minOf(buffer.size.toLong(), remainingBytesToCopy - copied).toInt()
                                val r = stream.read(buffer, 0, toRead)
                                if (r == -1) break
                                fos.write(buffer, 0, r)
                                copied += r
                            }
                        }
                        break
                    }
                    prevByte = currentByte
                }

                if (!syncFound) {
                    FileInputStream(sourceFile).use { fallbackStream ->
                        fallbackStream.skip(startByte)
                        FileOutputStream(outputFile).use { fos ->
                            var copied = 0L
                            val bytesToCopy = (endByte - startByte).coerceAtLeast(1024L)
                            val buffer = ByteArray(16384)
                            while (copied < bytesToCopy) {
                                val toRead = minOf(buffer.size.toLong(), bytesToCopy - copied).toInt()
                                val r = fallbackStream.read(buffer, 0, toRead)
                                if (r == -1) break
                                fos.write(buffer, 0, r)
                                copied += r
                            }
                        }
                    }
                }
            }
        }

        return if (outputFile.exists() && outputFile.length() > 512) {
            TrimmedAudioResult(outputFile, "mp3", "audio/mpeg", effectiveDurationSec)
        } else {
            if (outputFile.exists()) outputFile.delete()
            null
        }
    }

    private fun getExtensionFromMime(mime: String): String = when (mime.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav" -> "wav"
        else -> "mp3"
    }
}
