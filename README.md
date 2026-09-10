# opencompanion

A voice desk companion that runs on a spare Android phone. No root required.

It can:

- talk, with a wake word and a voice
- express emotions with a face
- look through the camera
- remember things
- search the web
- check the weather
- tell the time and set timers
- send notifications to your laptop

The default companion is named Soc.

## How it works

- opencompanion is a single Android app; the face fills the screen while a
  foreground service listens for the wake word
- Wake-word and voice-activity detection run on the phone
- Speech-to-text, the language model and text-to-speech use an
  OpenAI-compatible API
- Memory is markdown files in the app's private storage

## Requirements

- An Android phone on Android 8.0 or newer
- An OpenAI-compatible API with a chat model (tool calling, and image input
  for the camera), a transcription model and a speech model; an Exa key for
  web search is optional
- A wake-word model file for hands-free use (see Wake word below)

## Install

Build from source and install:

```
./gradlew :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. On first launch, grant the microphone permission, then open the settings with
a long press on the face and fill in the API section.

Android does not let the app start listening by itself after a reboot; open
the app once and everything resumes.

## Wake word

The app ships without a wake-word model. Train one with the openWakeWord
training notebook, then import the `.onnx` file from the settings. Until a
model is imported the companion stays idle; the settings stay reachable with a long press on the face.

## Configuration

- Everything is configured in the app's settings (long press on the face)
- The AI section takes a base URL, API key, auth header choice and three
  model names
- Chat, transcribe and speech can each use their own base URL, key and header
  instead of the defaults
- The wake-word model is imported from a file in the settings
- Quiet hours stop the companion on a daily schedule and start it again
  while the face is on screen
- The personality is edited in a full-screen editor with presets

## Laptop link

Install KDE Connect on the phone and pair it with your computer. Notifications
the companion posts are mirrored to the desktop. To let the companion read
your laptop's notifications, enable notification access for opencompanion in
the system settings.

## Development

```
./gradlew :core:test :app:testDebugUnitTest
./gradlew spotlessApply
./gradlew :app:assembleDebug
```

The `core` module is pure JVM and holds all the logic; the `app` module holds
the Android hardware adapters and UI.

## License

Copyright (C) 2026 Vivian Richard Demello (vynride)

opencompanion is free software under the GNU Affero General Public License,
version 3 or later. See [LICENSE](LICENSE).

Bundled models: Silero VAD (MIT) and the openWakeWord feature models
(Apache 2.0). See [models/README.md](models/README.md).
