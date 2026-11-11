# Universal Voice Translator

A free Android translator, built with Kotlin and Jetpack Compose. Stages 1 and 2 provide text translation and voice-note translation. Text translation uses a LibreTranslate-compatible server; speech recognition and speech synthesis use on-device Android services. The app has no ads, subscriptions, or paid API dependency.

## Run the app

Open this repository in Android Studio with JDK 17 or 21, allow Gradle sync to finish, then run the `app` configuration on an Android emulator or device (Android 8.0/API 26 or newer). Install Android SDK Platform 35 when prompted.

The default server address, `http://10.0.2.2:5000`, is for the Android emulator to reach a LibreTranslate server running on the development machine. To start a free local server with Docker:

```sh
docker run --rm -p 5000:5000 libretranslate/libretranslate
```

The first launch may take a while while translation models are prepared. For a physical Android device, change the server address in the app to the development machine's reachable LAN address, for example `http://192.168.1.20:5000`. Keep the device and server on a trusted network when using unencrypted local HTTP; use HTTPS for a remotely hosted server.

## Stage 1: text translation

- Select a source and target language, enter text, and request a real translation.
- Change the LibreTranslate server URL. Network or server errors are shown in the app.
- Language options are a common-language subset; the selected server must have the corresponding translation models available.

## Stage 2: voice notes

- Record a voice note with microphone permission, including pause, resume, and stop controls.
- Import an audio file through Android's document picker, or share audio to the app from another app. No broad storage permission is requested.
- Decode supported audio formats on-device, transcribe using Android's on-device speech recognizer, then translate through the configured LibreTranslate server.
- Edit the recognized or translated text, create speech with an installed offline Android TTS voice, play/pause/stop it, and save or share the generated WAV file.

Voice-note transcription currently requires Android 13 (API 33) or later and an installed on-device speech recognition service that supports prerecorded audio. The service must have a model for the selected source language. Android's available audio decoders vary by device and file format. On devices without a compatible offline recognition service, the app reports the limitation and does not fall back to cloud speech recognition. Spoken output also requires an installed offline TTS voice for the selected target language. Android 8 through Android 12 can still use Stage 1 text translation.

## Test Stage 2

Use an Android 13+ emulator or device with an offline speech recognition model and offline TTS voices installed for the languages you plan to test. Speech and TTS model availability depends on the device; some emulators do not include either service by default.

1. Start the LibreTranslate server above and run the app.
2. Open **Voice note**, grant microphone access, record a sentence, pause/resume, then stop.
3. Select the matching source language, tap **Transcribe on device**, edit the transcript if needed, select a target language, then tap **Translate voice note**.
4. Create spoken translation, play it, pause/resume and stop. Try **Save audio** and **Share audio**.
5. Test **Import audio** with an audio file. Also try sharing an audio file from WhatsApp, Telegram, or a file manager. If another app does not expose audio sharing, use the import picker.
6. Turn off or uninstall the selected offline recognition model to verify that the app shows an error without uploading the voice note to a cloud recognizer.

## Build from a terminal

With Android SDK Platform 35 and Android Build Tools installed:

```sh
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
