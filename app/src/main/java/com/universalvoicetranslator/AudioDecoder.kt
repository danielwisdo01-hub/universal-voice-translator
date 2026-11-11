package com.universalvoicetranslator

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedAudio(val pcmFile: File, val sampleRate: Int)

object AudioDecoder {
    fun decodeToPcm16(context: Context, uri: Uri, outputFile: File): DecodedAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            if (uri.scheme == "content") {
                extractor.setDataSource(context, uri, null)
            } else {
                extractor.setDataSource(uri.path ?: throw IllegalArgumentException("Invalid audio file."))
            }

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("This file does not contain an audio track.")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Unsupported audio format.")
            extractor.selectTrack(trackIndex)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val decodedFile = File(outputFile.parentFile, "${outputFile.name}.decoded")
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var outputEncoding = AudioFormat.ENCODING_PCM_16BIT

            FileOutputStream(decodedFile).use { decodedOutput ->
                while (!outputEnded) {
                    if (!inputEnded) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = decoder.outputFormat
                            outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            outputEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            if (bufferInfo.size > 0) {
                                val outputBuffer = decoder.getOutputBuffer(outputIndex)!!
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val chunk = ByteArray(bufferInfo.size)
                                outputBuffer.get(chunk)
                                decodedOutput.write(chunk)
                            }
                            outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            require(outputEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                "This device decoded the file to an unsupported audio format. Try another audio file."
            }
            require(outputSampleRate > 0 && outputChannels > 0) { "Could not read the audio format." }
            writeMonoPcm(decodedFile, outputChannels, outputFile)
            decodedFile.delete()
            require(outputFile.length() > 0L) { "The audio file is empty." }
            return DecodedAudio(outputFile, outputSampleRate)
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    private fun writeMonoPcm(inputFile: File, channels: Int, outputFile: File) {
        val frameSize = channels * 2
        BufferedInputStream(inputFile.inputStream()).use { input ->
            FileOutputStream(outputFile).use { output ->
                var carry = ByteArray(0)
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    val data = carry + chunk.copyOf(read)
                    val usableSize = data.size - data.size % frameSize
                    if (channels == 1) {
                        output.write(data, 0, usableSize)
                    } else {
                        val frame = ByteBuffer.wrap(data, 0, usableSize).order(ByteOrder.LITTLE_ENDIAN)
                        while (frame.remaining() >= frameSize) {
                            var sum = 0
                            repeat(channels) { sum += frame.short.toInt() }
                            val mono = (sum / channels).toShort()
                            output.write(mono.toInt() and 0xFF)
                            output.write((mono.toInt() shr 8) and 0xFF)
                        }
                    }
                    carry = data.copyOfRange(usableSize, data.size)
                }
            }
        }
    }
}