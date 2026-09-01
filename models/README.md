# Models

- `melspectrogram.onnx`, `embedding_model.onnx`: openWakeWord feature extraction (Google speech
  embedding model), Apache-2.0, see `LICENSE-openwakeword-feature-models.txt`
- `silero_vad.onnx`: Silero VAD v5, MIT, see `LICENSE-silero-vad.txt`
- `wakeword/`: kept empty; keyword models are never committed here

The app build copies this directory into its assets.

No wake-word keyword model ships with the repo or the app: upstream openWakeWord keyword
models are CC BY-NC-SA and are never committed or distributed. Import one from Settings
instead; the app copies it into its private storage.
