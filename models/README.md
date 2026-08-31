# Models

- `melspectrogram.onnx`, `embedding_model.onnx`: openWakeWord feature extraction (Google speech
  embedding model), Apache-2.0, see `LICENSE-openwakeword-feature-models.txt`
- `silero_vad.onnx`: Silero VAD v5, MIT, see `LICENSE-silero-vad.txt`
- `wakeword/<name>.onnx`: the companion's wake-word classifier, trained with the openWakeWord
  training notebook; licensed with the app

The app build copies this directory into its assets.
