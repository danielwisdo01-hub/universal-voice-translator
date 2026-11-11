package com.universalvoicetranslator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScreenTranslationScreen(
    recognizedText: String?,
    captureError: String?,
    serverUrl: String,
    targetLanguage: Language,
    onTargetLanguageChanged: (Language) -> Unit,
    onRequestCapture: () -> Unit,
) {
    val context = LocalContext.current
    var originalText by remember(recognizedText) { mutableStateOf(recognizedText.orEmpty()) }
    var translatedText by remember { mutableStateOf("") }
    var translationError by remember { mutableStateOf<String?>(null) }
    var isTranslating by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var speechEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var speechReady by remember { mutableStateOf(false) }
    var speechError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(originalText, targetLanguage, serverUrl) {
        if (originalText.isNotBlank()) {
            isTranslating = true
            translatedText = ""
            translationError = null
            try {
                translatedText = withContext(Dispatchers.IO) {
                    TranslationClient.translate(
                        serverUrl,
                        originalText,
                        Language("auto", "Auto detect"),
                        targetLanguage,
                    )
                }
            } catch (exception: Exception) {
                translationError = exception.message ?: "Could not translate the detected text."
            } finally {
                isTranslating = false
            }
        } else {
            translatedText = ""
            isTranslating = false
        }
    }

    DisposableEffect(targetLanguage) {
        val engineRef = arrayOfNulls<TextToSpeech>(1)
        val engine = TextToSpeech(context) { result ->
            Handler(Looper.getMainLooper()).post {
                val initializedEngine = engineRef[0]
                if (initializedEngine != null && speechEngine === initializedEngine) {
                    if (result != TextToSpeech.SUCCESS) {
                        speechReady = false
                        speechError = "Android text-to-speech could not be initialized."
                    } else {
                        val locale = Locale.forLanguageTag(screenTtsLocale(targetLanguage.code))
                        val languageStatus = initializedEngine.setLanguage(locale)
                        if (languageStatus == TextToSpeech.LANG_MISSING_DATA || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                            speechReady = false
                            speechError = "No installed ${targetLanguage.name} text-to-speech voice is available."
                        } else {
                            val offlineVoice = initializedEngine.voices
                                ?.firstOrNull { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
                            if (offlineVoice == null) {
                                speechReady = false
                                speechError = "Install a free offline ${targetLanguage.name} text-to-speech voice in Android settings."
                            } else {
                                initializedEngine.voice = offlineVoice
                                speechReady = true
                                speechError = null
                            }
                        }
                    }
                }
            }
        }
        engineRef[0] = engine
        speechEngine = engine
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
            }

            override fun onError(utteranceId: String?) {
                isSpeaking = false
                speechError = "Could not read the translation aloud."
            }
        })
        onDispose {
            engine.stop()
            engine.shutdown()
            speechEngine = null
            speechReady = false
            isSpeaking = false
        }
    }

    Surface(color = Color.Transparent) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Screen translation", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Each capture needs Android's confirmation. The screenshot is read on-device with Latin-script OCR; detected text is sent to your configured translation server.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 19.sp,
            )
            Button(
                onClick = onRequestCapture,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Capture another screen")
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Translate to", color = Ink, fontWeight = FontWeight.Medium)
                LanguagePicker(targetLanguage, Modifier.weight(1f), enabled = !isTranslating, onSelected = onTargetLanguageChanged)
            }

            captureError?.let {
                Text(it, color = Color(0xFFB3261E), fontSize = 13.sp)
            }
            if (originalText.isNotBlank()) {
                Text("DETECTED TEXT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                OutlinedTextField(
                    value = originalText,
                    onValueChange = { originalText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    shape = RoundedCornerShape(12.dp),
                )
                Text("TRANSLATION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    if (isTranslating) {
                        CircularProgressIndicator(modifier = Modifier.height(22.dp), strokeWidth = 2.dp, color = Teal)
                    } else {
                        Text(
                            translatedText.ifBlank { translationError ?: "Translation will appear here" },
                            color = if (translationError == null) Ink else Color(0xFFB3261E),
                            fontSize = 17.sp,
                            lineHeight = 24.sp,
                        )
                    }
                }
                if (translatedText.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                speechError = null
                                if (isSpeaking) {
                                    speechEngine?.stop()
                                    isSpeaking = false
                                } else if (speechReady) {
                                    speechEngine?.speak(translatedText, TextToSpeech.QUEUE_FLUSH, null, "screen-translation")
                                } else {
                                    speechError = "An offline voice for ${targetLanguage.name} is not available on this device."
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            androidx.compose.material3.Icon(Icons.Filled.VolumeUp, contentDescription = "Read translation aloud")
                            Text(if (isSpeaking) "Stop reading" else "Read aloud")
                        }
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Screen translation", translatedText))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            androidx.compose.material3.Icon(Icons.Filled.ContentCopy, contentDescription = "Copy translation")
                            Text("Copy")
                        }
                    }
                }
                speechError?.let { Text(it, color = Muted, fontSize = 12.sp) }
            } else if (captureError == null) {
                Text("Tap the floating button in another app to capture text, or use the button above.", color = Muted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun screenTtsLocale(code: String): String = when (code) {
    "ar" -> "ar-SA"
    "pt" -> "pt-BR"
    "zh" -> "zh-CN"
    else -> code
}
