package com.universalvoicetranslator

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val VoiceRed = Color(0xFFB63F36)

@Composable
fun VoiceNoteScreen(
    serverUrl: String,
    incomingAudioUri: Uri?,
    onIncomingAudioConsumed: () -> Unit,
    onCaptureStateChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val canTranscribeAudio = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    val voiceDir = remember { File(context.cacheDir, "voice-notes").apply { mkdirs() } }

    var source by remember { mutableStateOf(Languages.first { it.code == "en" }) }
    var target by remember { mutableStateOf(Languages.first { it.code == "es" }) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioLabel by remember { mutableStateOf<String?>(null) }
    var recognizedText by remember { mutableStateOf("") }
    var translatedText by remember { mutableStateOf("") }
    var generatedAudio by remember { mutableStateOf<File?>(null) }
    var synthesisOutput by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("Import a voice note or record one here.") }
    var error by remember { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var audioDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var speechEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var speechReady by remember { mutableStateOf(false) }
    var fileToSave by remember { mutableStateOf<File?>(null) }

    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audioUri = uri
            audioLabel = displayName(context, uri)
            recognizedText = ""
            translatedText = ""
            generatedAudio = null
            error = null
            status = "Voice note ready to transcribe."
        }
    }
    val saveAudio = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val sourceFile = fileToSave
        if (uri != null && sourceFile != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            sourceFile.inputStream().use { input -> input.copyTo(output) }
                        } ?: throw java.io.IOException("Could not open the selected destination.")
                    }
                    status = "Translated audio saved."
                } catch (exception: Exception) {
                    error = exception.message ?: "Could not save the audio file."
                }
            }
        }
        fileToSave = null
    }

    fun startRecording() {
        error = null
        val file = File(voiceDir, "recording-${System.currentTimeMillis()}.m4a")
        var createdRecorder: MediaRecorder? = null
        try {
            val activeRecorder = MediaRecorder(context)
            createdRecorder = activeRecorder
            activeRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = activeRecorder
            onCaptureStateChanged(true)
            audioUri = Uri.fromFile(file)
            audioLabel = "Recorded voice note"
            recognizedText = ""
            translatedText = ""
            generatedAudio = null
            isRecording = true
            isPaused = false
            status = "Recording"
        } catch (exception: Exception) {
            runCatching { createdRecorder?.release() }
            recorder = null
            file.delete()
            error = exception.message ?: "Could not start recording. Check microphone access."
            status = "Recording stopped"
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else error = "Microphone permission is required to record a voice note."
    }
    val speechLanguage = source

    fun stopRecording() {
        val activeRecorder = recorder
        try {
            activeRecorder?.stop()
            status = "Recording ready to transcribe."
        } catch (_: RuntimeException) {
            audioUri?.path?.let(::File)?.delete()
            audioUri = null
            audioLabel = null
            error = "The recording was too short or could not be saved. Try recording again."
            status = "Recording stopped"
        } finally {
            runCatching { activeRecorder?.reset() }
            activeRecorder?.release()
            recorder = null
            isRecording = false
            isPaused = false
            onCaptureStateChanged(false)
        }
    }

    val hostActivity = context.findComponentActivity()
    DisposableEffect(hostActivity, isRecording, isPaused) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (isRecording || isPaused)) stopRecording()
        }
        hostActivity?.let { it.lifecycle.addObserver(observer) }
        onDispose { hostActivity?.lifecycle?.removeObserver(observer) }
    }

    fun transcribeAudio(uri: Uri) {
        error = null
        recognizedText = ""
        translatedText = ""
        generatedAudio = null
        if (Build.VERSION.SDK_INT < 33) {
            error = "Imported voice-note transcription requires Android 13 or later for on-device audio-file recognition."
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            error = "This device does not have an on-device speech recognition service installed. No audio was sent to a cloud recognizer."
            return
        }

        isProcessing = true
        status = "Preparing voice note"
        scope.launch {
            try {
                val decoded = withContext(Dispatchers.IO) {
                    AudioDecoder.decodeToPcm16(context, uri, File(voiceDir, "recognition-input.pcm"))
                }
                val descriptor = ParcelFileDescriptor.open(decoded.pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
                audioDescriptor = descriptor
                val service = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer = service
                service.setRecognitionListener(object : RecognitionListener {
                    private fun closeSession() {
                        runCatching { descriptor.close() }
                        if (audioDescriptor === descriptor) audioDescriptor = null
                        if (recognizer === service) {
                            service.destroy()
                            recognizer = null
                        }
                        isProcessing = false
                    }

                    override fun onReadyForSpeech(params: Bundle?) {
                        status = "Transcribing on this device"
                    }

                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit

                    override fun onError(errorCode: Int) {
                        closeSession()
                        status = "Transcription failed"
                        error = speechErrorMessage(errorCode)
                    }

                    override fun onResults(results: Bundle?) {
                        val transcript = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        closeSession()
                        if (transcript.isBlank()) {
                            error = "No speech was recognized. Try a clearer recording or another source language."
                            status = "Transcription finished"
                        } else {
                            recognizedText = transcript
                            status = "Transcription ready to translate"
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag(languageLocaleTag(speechLanguage.code)).toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, decoded.sampleRate)
                }
                service.startListening(recognitionIntent)
            } catch (exception: Exception) {
                runCatching { audioDescriptor?.close() }
                audioDescriptor = null
                recognizer?.destroy()
                recognizer = null
                isProcessing = false
                status = "Transcription failed"
                error = exception.message ?: "Could not transcribe this audio file."
            }
        }
    }

    fun translateTranscript() {
        error = null
        isProcessing = true
        status = "Translating"
        scope.launch {
            try {
                translatedText = withContext(Dispatchers.IO) {
                    TranslationClient.translate(serverUrl, recognizedText, source, target)
                }
                status = "Translation ready"
            } catch (exception: Exception) {
                status = "Translation failed"
                error = exception.message ?: "Could not translate this voice note."
            } finally {
                isProcessing = false
            }
        }
    }

    fun synthesizeTranslation() {
        val engine = speechEngine
        if (!speechReady || engine == null) {
            error = "Android text-to-speech is not ready on this device."
            return
        }
        error = null
        val locale = Locale.forLanguageTag(languageLocaleTag(target.code))
        val languageStatus = engine.setLanguage(locale)
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            error = "The installed text-to-speech engine does not have a ${target.name} voice. Install a free voice in Android system settings."
            return
        }
        val offlineVoice = engine.voices
            ?.firstOrNull { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
        if (offlineVoice == null) {
            error = "No installed offline ${target.name} text-to-speech voice is available. Install a free offline voice in Android system settings."
            return
        }
        engine.voice = offlineVoice

        val outputFile = File(voiceDir, "translated-${System.currentTimeMillis()}.wav")
        synthesisOutput = outputFile
        generatedAudio = null
        error = null
        isProcessing = true
        status = "Creating translated audio"
        val result = engine.synthesizeToFile(translatedText, Bundle(), outputFile, "translated-note")
        if (result == TextToSpeech.ERROR) {
            isProcessing = false
            status = "Audio synthesis failed"
            error = "Android text-to-speech could not create the audio file."
        }
    }

    fun playGeneratedAudio() {
        val audioFile = generatedAudio ?: return
        scope.launch {
            try {
                val newPlayer = withContext(Dispatchers.IO) {
                    MediaPlayer().apply {
                        setDataSource(audioFile.absolutePath)
                        prepare()
                    }
                }
                newPlayer.setOnCompletionListener {
                    isPlaying = false
                    player = null
                    it.release()
                }
                player?.release()
                player = newPlayer
                newPlayer.start()
                isPlaying = true
                isPaused = false
                status = "Playing translated audio"
            } catch (exception: Exception) {
                error = exception.message ?: "Could not play the translated audio."
            }
        }
    }

    LaunchedEffect(incomingAudioUri) {
        if (incomingAudioUri != null) {
            if (isRecording || isPaused) {
                error = "Stop the current recording before opening another voice note."
            } else {
                audioUri = incomingAudioUri
                audioLabel = displayName(context, incomingAudioUri)
                recognizedText = ""
                translatedText = ""
                generatedAudio = null
                error = null
                status = "Shared voice note ready to transcribe."
            }
            onIncomingAudioConsumed()
        }
    }

    DisposableEffect(context) {
        val engine = TextToSpeech(context) { result ->
            speechReady = result == TextToSpeech.SUCCESS
            if (result != TextToSpeech.SUCCESS) error = "Android text-to-speech could not be initialized."
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                val output = synthesisOutput?.takeIf(File::exists)
                generatedAudio = output
                isProcessing = false
                status = if (output != null) "Translated audio ready" else "Audio synthesis failed"
                if (output == null) error = "The text-to-speech engine did not create an audio file."
            }

            override fun onError(utteranceId: String?) {
                isProcessing = false
                status = "Audio synthesis failed"
                error = "Android text-to-speech could not create the audio file."
            }
        })
        speechEngine = engine
        onDispose {
            runCatching { recorder?.stop() }
            recorder?.release()
            recognizer?.destroy()
            runCatching { audioDescriptor?.close() }
            player?.release()
            onCaptureStateChanged(false)
            engine.stop()
            engine.shutdown()
        }
    }

    Surface(color = Color.Transparent) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Voice note", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            Text("Import audio or record a note, then translate its speech.", color = Muted, fontSize = 14.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguagePicker(source, Modifier.weight(1f), enabled = !isRecording && !isPaused && !isProcessing) { source = it }
                LanguagePicker(target, Modifier.weight(1f), enabled = !isRecording && !isPaused && !isProcessing) { target = it }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { importAudio.launch(arrayOf("audio/*")) },
                    enabled = canTranscribeAudio && !isRecording && !isPaused && !isProcessing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Import audio")
                }
                Button(
                    onClick = {
                        if (!isRecording && !isPaused) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startRecording()
                            } else {
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else if (isPaused) {
                            runCatching { recorder?.resume() }
                                .onSuccess {
                                    isPaused = false
                                    isRecording = true
                                    status = "Recording"
                                }
                                .onFailure { error = "Could not resume recording: ${it.message}" }
                        } else {
                            runCatching { recorder?.pause() }
                                .onSuccess {
                                    isRecording = false
                                    isPaused = true
                                    status = "Recording paused"
                                }
                                .onFailure { error = "Could not pause recording: ${it.message}" }
                        }
                    },
                    enabled = canTranscribeAudio && !isProcessing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) VoiceRed else Teal),
                ) {
                    Text(when {
                        isRecording -> "Pause"
                        isPaused -> "Resume"
                        else -> "Record"
                    })
                }
                if (isRecording || isPaused) {
                    Button(
                        onClick = ::stopRecording,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink),
                    ) {
                        androidx.compose.material3.Icon(Icons.Filled.Stop, contentDescription = "Stop recording")
                    }
                }
            }

            audioLabel?.let { label ->
                Text("Audio: $label", color = Muted, fontSize = 13.sp)
            }

            Button(
                onClick = { audioUri?.let(::transcribeAudio) },
                enabled = audioUri != null && !isRecording && !isPaused && !isProcessing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isProcessing) status else "Transcribe on device")
            }

            VoiceTextField(
                title = "RECOGNIZED SPEECH",
                value = recognizedText,
                placeholder = "The recognized transcript will appear here",
                onValueChange = { recognizedText = it },
            )

            Button(
                onClick = ::translateTranscript,
                enabled = recognizedText.isNotBlank() && !isProcessing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Translate voice note")
            }

            VoiceTextField(
                title = "TRANSLATED TEXT",
                value = translatedText,
                placeholder = "The translated speech will appear here",
                onValueChange = { translatedText = it },
            )

            if (translatedText.isNotBlank()) {
                Button(
                    onClick = ::synthesizeTranslation,
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Create spoken translation")
                }
            }

            generatedAudio?.let { audioFile ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (isPlaying) {
                                player?.pause()
                                isPlaying = false
                                isPaused = true
                            } else if (isPaused && player != null) {
                                player?.start()
                                isPlaying = true
                                isPaused = false
                            } else {
                                playGeneratedAudio()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        androidx.compose.material3.Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause translated audio" else "Play translated audio",
                        )
                        Text(if (isPlaying) "Pause" else if (isPaused) "Resume" else "Play")
                    }
                    Button(
                        onClick = {
                            runCatching { player?.stop() }
                            player?.release()
                            player = null
                            isPlaying = false
                            isPaused = false
                            status = "Playback stopped"
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        androidx.compose.material3.Icon(Icons.Filled.Stop, contentDescription = "Stop playback")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            fileToSave = audioFile
                            saveAudio.launch("translated-note.wav")
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Save audio") }
                    Button(
                        onClick = { shareAudio(context, audioFile) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Share audio") }
                }
            }

            Text(status, color = Muted, fontSize = 13.sp)
            error?.let { Text(it, color = Color(0xFFB3261E), fontSize = 13.sp) }
            Text(
                when {
                    Build.VERSION.SDK_INT < 33 -> "Voice-note transcription requires Android 13 or later. Text translation remains available on this device."
                    !canTranscribeAudio -> "No on-device speech recognition service is installed. Install a compatible offline service to use voice notes."
                    else -> "Speech models and offline TTS voices vary by device. Voice transcription is not sent to a cloud recognizer."
                },
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun VoiceTextField(
    title: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Text(title, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        placeholder = { Text(placeholder, color = Muted) },
        shape = RoundedCornerShape(14.dp),
    )
}

private fun displayName(context: Context, uri: Uri): String {
    if (uri.scheme == "file") return File(uri.path.orEmpty()).name
    return runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: "Shared audio"
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

private fun languageLocaleTag(code: String): String = when (code) {
    "ar" -> "ar-SA"
    "pt" -> "pt-BR"
    "zh" -> "zh-CN"
    else -> code
}

private fun speechErrorMessage(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "The recognition service could not read this audio."
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The on-device service does not have a speech model for the selected source language."
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The on-device recognition service failed. Check that the selected offline model is installed."
    SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized. Try a clearer recording or another source language."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected in this audio."
    else -> "On-device transcription failed (error $code)."
}

private fun shareAudio(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = android.content.ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share translated audio"))
}
