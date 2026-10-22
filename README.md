# Klipa

A native Android video editor. Kotlin, Jetpack Compose, and AndroidX Media3
(ExoPlayer for preview, Transformer for export). The editing engine is ported
from [ReaCut](https://github.com/Xiangyu-CAS/rea-capcut) (MIT).

**Status:** milestones 1–3. The editing engine, undo history and a working
editor screen are in: import media, cut on a multi-track timeline, undo, and
scrub a program monitor. The project autosaves. Real-time playback with sound
and export are next.

## What works

- **Import** video, stills and audio (photo picker / document picker). Media is
  inserted at the playhead and ripples later clips.
- **Timeline** with a fixed center playhead: drag to scrub (with fling), pinch
  to zoom, tap to select, drag a selected clip's edge to trim, long-press to
  move a clip in time or onto another track. Trims and moves snap to the
  playhead and clip edges; each drag is one undo step.
- **Clip tools**: split at the playhead, speed (0.25×–4×), extract audio into a
  linked clip, unlink, duplicate, delete, add text.
- **Tracks**: mute, hide, lock, reorder, delete from the track header menu.
- **Program monitor**: shows the frame at the playhead with each layer's
  transform, keyframes and fades, including text layers.
- **Undo/redo** (150 steps) and **autosave** to app storage in ReaCut's JSON
  format.
- Portrait and landscape layouts; English and Indonesian.

Not yet: playback is a frame-by-frame preview without sound (ExoPlayer comes in
milestone 4); no export; crop, chroma key, masks and color adjustments are
stored and edited by the engine but not previewed; no clip thumbnails or
waveform extraction; one working project (no project list).

## Roadmap

1. ~~Core model and edit operations, with JVM unit tests~~
2. ~~Project load/save (ReaCut JSON shape) and validation~~
3. ~~Compose timeline (tracks, clips, playhead, zoom, selection) with undo and transactions~~
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

- **Editor state** — `EditorState` (port of ReaCut's editor store) is an
  immutable value with selection, playhead, clipboard and undo/redo history.
  Actions such as `split()`, `deleteSelected()` (with magnetic gap closing),
  `paste()` and `updateClip { … }` return the next state; transactions group
  a drag into one undo entry.

ReaCut's test cases for these functions and for its editor store are ported to
JUnit; the assistant and transition cases come with those features.

## Modules

| Module | Type | Responsibility |
|---|---|---|
| `:app` | Android app | Activity, navigation, wiring |
| `:core:model` | Kotlin/JVM | Project/Track/Clip data model, project validation |
| `:core:edit` | Kotlin/JVM | Pure edit operations and `EditorState` (selection, clipboard, undo) |
| `:core:designsystem` | Android library | Theme and design tokens (palette, type, spacing, shapes) |
| `:feature:timeline` | Android library | `EditorViewModel`, the Canvas timeline and its gestures |
| `:feature:preview` | Android library | Program monitor; ExoPlayer + GL compositing later |
| `:feature:export` | Android library | Export with Media3 Transformer |

`:core:model` and `:core:edit` have no Android dependency, so their tests run on
the JVM without an emulator.

Package root: `com.example.klipa` (placeholder — must change before a Play Store
release, which rejects `com.example`).

## Build

Requirements: JDK 17+, Android SDK with platform 37.

```bash
./gradlew assembleDebug                          # debug APK
./gradlew :core:model:test :core:edit:test       # engine tests (plain JVM)
./gradlew testDebugUnitTest                      # ViewModel, timeline geometry
./gradlew :app:recordRoborazziDebug              # editor screenshots -> app/build/outputs/roborazzi
./gradlew lintDebug
```

Screenshots are rendered by Robolectric on the JVM, so the UI can be reviewed
without a device.

Versions are pinned in `gradle/libs.versions.toml` (AGP 9.4.1, Kotlin 2.4.21,
Gradle 9.8.2, Compose BOM 2026.09.00, Media3 1.11.1). minSdk 26, targetSdk 37.

## License

Klipa includes code derived from ReaCut, used under the MIT License. See
[`NOTICE`](NOTICE).
