# Klipa

A native Android video editor. Kotlin, Jetpack Compose, and AndroidX Media3
(ExoPlayer for preview, Transformer for export). The editing engine is ported
from [ReaCut](https://github.com/Xiangyu-CAS/rea-capcut) (MIT).

**Status:** the editing engine is ported and tested on the JVM (milestones 1–2:
data model, edit operations, project validation and JSON load/save). No UI,
preview or export yet.

## Roadmap

1. ~~Core model and edit operations, with JVM unit tests~~
2. ~~Project load/save (ReaCut JSON shape) and validation~~
3. Compose timeline (tracks, clips, playhead, zoom, selection) with undo and transactions
4. Preview: ExoPlayer for the base track, then GL compositing of layers
5. Export with Media3 Transformer: one track, then multi-track, effects and text
6. Custom GL shaders: chroma key, mask, blur, temperature

## Engine

What `:core:model` and `:core:edit` provide, ported from ReaCut's
`src/core/{types,model,timelineEdits}.ts`:

- **Model** — immutable `Project`/`Track`/`Clip`/`MediaAsset` data classes that
  serialize to the same JSON as ReaCut projects (`version: 1`).
- **Clip edits** — `splitClip`, `trimClip` (clamped to source handles),
  `changeClipSpeed`. Keyframe curves and fade envelopes survive cuts exactly:
  sliced `easeInOut` segments get a narrowed easing window, cut pieces keep a
  shifted fade window.
- **Timeline edits** — `placeAsset` (insert/ripple, overwrite, overlay),
  `moveLinked`, `trimLinked`, `splitLinked`, `slipLinked`, `speedLinked`,
  `detachLinkedAudio`, `unlinkClips`. Linked clips (a video and its detached
  audio) always edit together, and nothing on a locked track ever changes.
- **Evaluation** — `evaluateKeyframes` and `evaluateFade`, shared by preview
  and export.
- **Validation** — `ProjectJson.decode` checks untrusted files without
  coercion (no quoted numbers, NaN, nulls or defaulted fields), with ReaCut's
  bounds: fps ∈ {24, 25, 30, 50, 60}, 16–7680 px, speed 0.1–8, ≤400 keyframes
  per clip, ≤24 h timeline.

Every edit snaps to frame boundaries and returns a new `Project`, or the same
instance when it does not apply, so undo history can store projects directly.
ReaCut's test cases for these functions are ported to JUnit; its editor-store,
assistant and transition cases come with the milestones that add those parts.

## Modules

| Module | Type | Responsibility |
|---|---|---|
| `:app` | Android app | Activity, navigation, wiring |
| `:core:model` | Kotlin/JVM | Project/Track/Clip data model, project validation |
| `:core:edit` | Kotlin/JVM | Pure edit operations: split, trim, speed, keyframes, timeline placement |
| `:core:designsystem` | Android library | Theme and design tokens |
| `:feature:timeline` | Android library | Timeline UI, editor state, undo |
| `:feature:preview` | Android library | Real-time preview (ExoPlayer + GL compositing) |
| `:feature:export` | Android library | Export with Media3 Transformer |

`:core:model` and `:core:edit` have no Android dependency, so their tests run on
the JVM without an emulator.

Package root: `com.example.klipa` (placeholder — must change before a Play Store
release, which rejects `com.example`).

## Build

Requirements: JDK 17+, Android SDK with platform 37.

```bash
./gradlew assembleDebug                         # debug APK
./gradlew :core:model:test :core:edit:test      # JVM unit tests
```

Versions are pinned in `gradle/libs.versions.toml` (AGP 9.4.1, Kotlin 2.4.21,
Gradle 9.8.2, Compose BOM 2026.09.00, Media3 1.11.1). minSdk 26, targetSdk 37.

## License

Klipa includes code derived from ReaCut, used under the MIT License. See
[`NOTICE`](NOTICE).
