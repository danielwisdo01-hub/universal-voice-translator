package com.universalvoicetranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF173B3A)
private val Teal = Color(0xFF087F74)
private val Mist = Color(0xFFF2F7F5)
private val Muted = Color(0xFF637876)
internal val Languages = listOf(
    Language("ar", "Arabic"),
    Language("zh", "Chinese"),
    Language("nl", "Dutch"),
    Language("en", "English"),
    Language("fr", "French"),
    Language("de", "German"),
    Language("hi", "Hindi"),
    Language("it", "Italian"),
    Language("ja", "Japanese"),
    Language("ko", "Korean"),
    Language("pt", "Portuguese"),
    Language("ru", "Russian"),
    Language("es", "Spanish"),
    Language("tr", "Turkish"),
)

class MainActivity : ComponentActivity() {
    private var incomingAudioUri by mutableStateOf<Uri?>(null)
    private var floatingButtonEnabled by mutableStateOf(false)
    private var screenCaptureRequested by mutableStateOf(false)
    private var screenRecognizedText by mutableStateOf<String?>(null)
    private var screenCaptureError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyIncomingIntent(intent)
        floatingButtonEnabled = FloatingTranslatorService.isEnabled(this)
        setContent {
            TranslatorApp(
                incomingAudioUri = incomingAudioUri,
                floatingButtonEnabled = floatingButtonEnabled,
                screenCaptureRequested = screenCaptureRequested,
                screenRecognizedText = screenRecognizedText,
                screenCaptureError = screenCaptureError,
                onIncomingAudioConsumed = { incomingAudioUri = null },
                onFloatingButtonChanged = { floatingButtonEnabled = it },
                onScreenCaptureRequestConsumed = { screenCaptureRequested = false },
                onScreenCaptureError = { screenCaptureError = it },
                onClearScreenResult = { screenRecognizedText = null },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val overlayAllowed = Settings.canDrawOverlays(this)
        if (!overlayAllowed && FloatingTranslatorService.isEnabled(this)) {
            FloatingTranslatorService.hide(this)
        }
        floatingButtonEnabled = overlayAllowed && FloatingTranslatorService.isEnabled(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIncomingIntent(intent)
    }

    private fun applyIncomingIntent(intent: Intent) {
        when (intent.action) {
            ScreenTranslationService.ACTION_REQUEST_CAPTURE -> {
                incomingAudioUri = null
                screenRecognizedText = null
                screenCaptureError = null
                screenCaptureRequested = true
            }
            ScreenTranslationService.ACTION_SHOW_RESULT -> {
                incomingAudioUri = null
                screenCaptureRequested = false
                screenRecognizedText = intent.getStringExtra(ScreenTranslationService.EXTRA_OCR_TEXT)
                screenCaptureError = intent.getStringExtra(ScreenTranslationService.EXTRA_OCR_ERROR)
            }
            else -> {
                screenCaptureRequested = false
                incomingAudioUri = sharedAudioFrom(intent)
            }
        }
    }
}

@Composable
private fun TranslatorApp(
    incomingAudioUri: Uri?,
    floatingButtonEnabled: Boolean,
    screenCaptureRequested: Boolean,
    screenRecognizedText: String?,
    screenCaptureError: String?,
    onIncomingAudioConsumed: () -> Unit,
    onFloatingButtonChanged: (Boolean) -> Unit,
    onScreenCaptureRequestConsumed: () -> Unit,
    onScreenCaptureError: (String?) -> Unit,
    onClearScreenResult: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember(context) {
        context.getSharedPreferences(ScreenTranslationService.SETTINGS, Context.MODE_PRIVATE)
    }
    var serverUrl by remember {
        mutableStateOf(settings.getString(ScreenTranslationService.KEY_SERVER_URL, "http://10.0.2.2:5000") ?: "http://10.0.2.2:5000")
    }
    var source by remember { mutableStateOf(Languages.first { it.code == "en" }) }
    var target by remember {
        mutableStateOf(
            Languages.firstOrNull { it.code == settings.getString(ScreenTranslationService.KEY_TARGET_LANGUAGE, "es") }
                ?: Languages.first { it.code == "es" },
        )
    }
    var input by remember { mutableStateOf("") }
    var translatedText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isTranslating by remember { mutableStateOf(false) }
    var selectedTab by remember {
        mutableStateOf(if (screenRecognizedText != null || screenCaptureError != null) 2 else 0)
    }
    var voiceCaptureActive by remember { mutableStateOf(false) }
    var floatingPermissionMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(serverUrl, target.code) {
        settings.edit()
            .putString(ScreenTranslationService.KEY_SERVER_URL, serverUrl)
            .putString(ScreenTranslationService.KEY_TARGET_LANGUAGE, target.code)
            .apply()
    }

    fun startFloatingButton() {
        FloatingTranslatorService.show(context)
        floatingPermissionMessage = null
        onFloatingButtonChanged(true)
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            floatingPermissionMessage = "Notification access was declined. The overlay can still run; stop it here or from Android's Active apps controls."
        }
        startFloatingButton()
    }

    fun requestNotificationOrStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startFloatingButton()
        }
    }

    val overlaySettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) {
            requestNotificationOrStart()
        } else {
            floatingPermissionMessage = "Allow display over other apps in Android Settings to enable the floating button."
        }
    }

    val captureConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val resultData = result.data
        if (result.resultCode == Activity.RESULT_OK && resultData != null) {
            try {
                val serviceIntent = Intent(context, ScreenTranslationService::class.java)
                    .putExtra(ScreenTranslationService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenTranslationService.EXTRA_RESULT_DATA, resultData)
                ContextCompat.startForegroundService(context, serviceIntent)
                onScreenCaptureRequestConsumed()
                (context.findActivity() as? Activity)?.finish()
            } catch (exception: Exception) {
                onScreenCaptureRequestConsumed()
                onScreenCaptureError(exception.message ?: "Could not start the one-time screen capture.")
                restoreFloatingButtonIfEnabled(context)
            }
        } else {
            onScreenCaptureRequestConsumed()
            onScreenCaptureError("Screen capture was canceled. No screenshot was taken.")
            restoreFloatingButtonIfEnabled(context)
        }
    }

    val captureNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureConsent.launch(manager.createScreenCaptureIntent())
        } else {
            onScreenCaptureRequestConsumed()
            onScreenCaptureError("Allow notifications to receive and open the screen translation result, then try again.")
            restoreFloatingButtonIfEnabled(context)
        }
    }

    fun requestScreenCapture() {
        selectedTab = 2
        onClearScreenResult()
        onScreenCaptureError(null)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            captureNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            onScreenCaptureError("Enable notifications for Universal Translator in Android Settings to receive screen-capture results.")
            restoreFloatingButtonIfEnabled(context)
        } else {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureConsent.launch(manager.createScreenCaptureIntent())
        }
    }

    androidx.compose.runtime.LaunchedEffect(screenCaptureRequested) {
        if (screenCaptureRequested) requestScreenCapture()
    }

    androidx.compose.runtime.LaunchedEffect(screenRecognizedText, screenCaptureError) {
        if (screenRecognizedText != null || screenCaptureError != null) selectedTab = 2
    }

    androidx.compose.runtime.LaunchedEffect(incomingAudioUri) {
        if (incomingAudioUri != null) selectedTab = 1
    }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Teal,
            onPrimary = Color.White,
            background = Mist,
            surface = Color.White,
            onSurface = Ink,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Mist) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp),
            ) {
                Spacer(Modifier.height(32.dp))
                Text("UNIVERSAL", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Text("Translator", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Free text, voice note and screen translation", color = Muted, fontSize = 15.sp)
                Spacer(Modifier.height(26.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Floating button", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Show above other apps", color = Muted, fontSize = 13.sp)
                    }
                    Switch(
                        checked = floatingButtonEnabled,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                FloatingTranslatorService.hide(context)
                                onFloatingButtonChanged(false)
                                floatingPermissionMessage = null
                            } else if (Settings.canDrawOverlays(context)) {
                                requestNotificationOrStart()
                            } else {
                                floatingPermissionMessage = "Android requires your approval before the button can appear over other apps."
                                overlaySettings.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        },
                    )
                }
                Text(
                    "When enabled, tap the draggable button in another app to request a one-time screen capture. Android asks before every capture; the notification lets you hide the button.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                floatingPermissionMessage?.let { message ->
                    Text(message, color = if (floatingButtonEnabled) Muted else Color(0xFFB3261E), fontSize = 12.sp)
                }
                Spacer(Modifier.height(18.dp))

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        enabled = !(selectedTab == 1 && voiceCaptureActive),
                        onClick = { selectedTab = 0 },
                        text = { Text("Text") },
                    )
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Voice note") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Screen") })
                }
                Spacer(Modifier.height(18.dp))

                if (selectedTab == 1) {
                    VoiceNoteScreen(serverUrl, incomingAudioUri, onIncomingAudioConsumed) { active ->
                        voiceCaptureActive = active
                    }
                } else if (selectedTab == 2) {
                    ScreenTranslationScreen(
                        recognizedText = screenRecognizedText,
                        captureError = screenCaptureError,
                        serverUrl = serverUrl,
                        targetLanguage = target,
                        onTargetLanguageChanged = { target = it },
                        onRequestCapture = ::requestScreenCapture,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LanguagePicker(source, Modifier.weight(1f)) { source = it }
                        TextButton(
                            onClick = {
                                val previousSource = source
                                source = target
                                target = previousSource
                            },
                        ) {
                            androidx.compose.material3.Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap languages")
                        }
                        LanguagePicker(target, Modifier.weight(1f)) { target = it }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text("YOUR TEXT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        placeholder = { Text("Type something to translate", color = Muted) },
                        shape = RoundedCornerShape(14.dp),
                    )

                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                isTranslating = true
                                error = null
                                translatedText = ""
                                try {
                                    translatedText = withContext(Dispatchers.IO) {
                                        TranslationClient.translate(serverUrl, input, source, target)
                                    }
                                } catch (exception: Exception) {
                                    error = exception.message ?: "Translation failed. Check your server and try again."
                                } finally {
                                    isTranslating = false
                                }
                            }
                        },
                        enabled = input.isNotBlank() && !isTranslating,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                    ) {
                        if (isTranslating) {
                            CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(10.dp))
                            Text("Translating")
                        } else {
                            Text("Translate", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Text("TRANSLATION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(14.dp))
                            .padding(18.dp),
                    ) {
                        Text(
                            text = translatedText.ifBlank { "Your translation will appear here" },
                            color = if (translatedText.isBlank()) Muted else Ink,
                            fontSize = 17.sp,
                            lineHeight = 25.sp,
                        )
                    }
                    if (error != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(error.orEmpty(), color = Color(0xFFB3261E), fontSize = 14.sp)
                    }

                    Spacer(Modifier.height(24.dp))
                    Text("TRANSLATION SERVER", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://your-libretranslate-server") },
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Free translation via LibreTranslate. Configure your own server address.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
internal fun LanguagePicker(
    selected: Language,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelected: (Language) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Button(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        ) {
            Text(selected.name, maxLines = 1, fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.name) },
                    onClick = {
                        onSelected(language)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun sharedAudioFrom(intent: Intent?): Uri? {
    if (intent == null) return null
    return when (intent.action) {
        Intent.ACTION_SEND -> intent.audioStream()
        Intent.ACTION_SEND_MULTIPLE -> intent.audioStreams().firstOrNull()
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun restoreFloatingButtonIfEnabled(context: Context) {
    if (Settings.canDrawOverlays(context) && FloatingTranslatorService.isEnabled(context)) {
        FloatingTranslatorService.restoreAfterCapture(context)
    }
}

@Suppress("DEPRECATION")
private fun Intent.audioStream(): Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
    getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
} else {
    getParcelableExtra(Intent.EXTRA_STREAM)
} ?: data

@Suppress("DEPRECATION")
private fun Intent.audioStreams(): List<Uri> = if (android.os.Build.VERSION.SDK_INT >= 33) {
    getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
} else {
    getParcelableArrayListExtra(Intent.EXTRA_STREAM).orEmpty()
}