---
description: Watch CaraML run a local Qwen2.5 chat on a real Pixel 9. Includes an accessible transcript, capture details, and a reproducible demo-rendering workflow.
---

# CaraML demo

<video controls playsinline preload="none" poster="../assets/demo-poster.jpg" aria-label="CaraML local AI product walkthrough; text transcript follows">
  <source src="../assets/caraml-demo.mp4" type="video/mp4">
  <track kind="captions" src="../assets/caraml-demo.vtt" srclang="en" label="English" default>
  Your browser does not support inline video. Use the download link below.
</video>

[Download the MP4](assets/caraml-demo.mp4) · [Poster](assets/demo-poster.jpg)

## Transcript

| Time | Visual and message |
| --- | --- |
| 0–4 s | “Your models. Your device.” CaraML's real Android start screen introduces local AI with Kotlin Multiplatform. |
| 4–14 s | “An idea in. An answer out.” A real Pixel 9 recording shows Qwen2.5 3B responding to “Give me three short names for a robot gardener.” |
| 14–19 s | “Pick your little brain.” The installed model library shows GGUF and supported ONNX text models. |
| 19–24 s | “Make it local.” The completed conversation and repository URL invite developers to build CaraML. Commercial use requires permission. |

The generated answer was **BotTend**, **GreenBot**, and **SoilMate**. No voiceover or music is included; all explanatory copy is on screen and in this transcript.

## Capture details

- Captured **7 November 2026** from the installed CaraML Android app on a physical **Google Pixel 9, Android 17**.
- Selected model: **Qwen/Qwen2.5-3B-Instruct-GGUF**, `qwen2.5-3b-instruct-q4_k_m.gguf`, already downloaded.
- The conversation and model-library screens are real app captures. Only the public sample prompt above is used.
- This is an **edited product walkthrough, not a benchmark**. The recording is trimmed, held frames are used for readability, and title cards/screenshots are composited around it. Neither the runtime backend nor the installed APK's exact commit was independently recorded for this demo.
- Use the [benchmark reports](benchmarks.md) for measured performance and complete workload conditions.

## iPhone capture

![CaraML on a physical iPhone 13](assets/screenshots/ios.webp){ width="320" }

Captured on 7 November 2026 after a successful Xcode Debug build and installation on a physical iPhone 13. This is the initial chat screen, not an iOS inference run; earlier measured iPhone workloads are in the [ONNX/GGUF report](https://github.com/Debanshu777/CaraML/blob/main/benchmarks/onnx/README.md).

## Recreate the video

The short story structure is inspired by [Brag](https://github.com/latent-spaces/brag). This native-app demo is composed locally with FFmpeg using actual device footage; it does not require Brag, Hyperframes, a hosted video service, or an API key.

```bash
python -m pip install -r tools/docs/media-requirements.txt
python tools/docs/render_demo.py --chat-video build/caraml-android-raw.mp4
```

Run after [setting up the docs environment](maintaining-docs.md). Supply your own raw Android recording, with the sample prompt sent around five seconds into the clip. Update the PNG screenshots in `docs/assets/screenshots/`, then review the video before publishing. The renderer uses the app's bundled fonts and writes the MP4, poster, and social image to `docs/assets/`. It also creates lossless WebP copies of the screenshots for faster page loading. Raw footage and intermediate clips stay in ignored `build/`.

For a fresh capture, open CaraML, enter a public sample prompt, and record with Android's screen recorder or `adb shell screenrecord`. Keep only app content; do not include notifications, access tokens, or personal conversations. Recheck model labels and the transcript after changing footage.
