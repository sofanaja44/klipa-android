# Klipa

A native Android video editor. Kotlin, Jetpack Compose, and AndroidX Media3
(ExoPlayer for preview, Transformer for export). The editing engine is ported
from [ReaCut](https://github.com/Xiangyu-CAS/rea-capcut) (MIT).

**Status:** milestones 1–6 plus monitor editing, projects and background
export (version 0.9.0).
Keep any number of projects, each in its own format (9:16, 16:9, 1:1, 4:5).
Import media, cut on a multi-track timeline with thumbnails and waveforms,
arrange titles and picture-in-picture overlays right on the monitor, grade clips
with filters, chroma key and masks, play the edit back in real time with sound,
and export it to an MP4 in the phone's gallery. Projects autosave.

## What works

- **Projects**: the home screen lists every project, most recently edited
  first, with a cover from its first picture, its format, length and when it
  was last edited. Start one in 9:16 (TikTok, Reels, Shorts), 16:9, 1:1 or 4:5;
  rename, duplicate or delete it from its card. The editor's back arrow (or
  the system back gesture) returns to the list, and ⋮ → Format changes a
  project's shape later. The single project of Klipa 0.7 and older joins the
  list on first launch.
- **Import** video, stills and audio (photo picker / document picker). Media is
  inserted at the playhead and ripples later clips. **Overlay** adds photos and
  videos as picture-in-picture instead: on a video track above the main one
  (a new one when none is free), at half size, without moving other clips.
- **Arrange on the monitor**: tap a clip or title to select it (tap a selected
  title again to edit its words, tap empty frame to deselect), drag the
  selected clip to move it, pinch with two fingers to resize and rotate it. It
  snaps to the frame's center lines, to 100% and to right angles; animated
  properties move their whole animation; each gesture is one undo step. The
  preview follows without reloading.
- **Timeline** with a fixed center playhead: drag to scrub (with fling), pinch
  to zoom, tap to select, drag a selected clip's edge to trim, long-press to
  move a clip in time or onto another track. Trims and moves snap to the
  playhead and clip edges; each drag is one undo step.
- **Clip tools**: split at the playhead, speed (0.25×–4×), volume and mute,
  extract audio into a linked clip, unlink, duplicate, delete; add text and edit
  its words, size, color and weight. Each sheet session is one undo step.
- **Looks** for video and photo clips: *Adjust* offers ReaCut's eight filter
  presets plus brightness, contrast, saturation, color temperature and blur;
  *Chroma key* removes a key color with tolerance and edge softness; *Mask*
  keeps a centered rectangle or ellipse, with feather and invert. A custom GL
  shader ports ReaCut's 8-bit pixel math (checked in WebGL against ReaCut's own
  functions, `tools/shader-check`), so the preview and the export match. Look
  and volume sliders apply to the playing (or paused) preview without
  reloading the player.
- **Tracks**: mute, hide, lock, reorder, delete from the track header menu.
- **Real-time preview with sound** through Media3's `CompositionPlayer`, set
  up as in Media3's composition demo. With one video track it uses the
  single-input path Media3 officially supports; clips are placed, scaled,
  rotated and faded by per-clip effects. Several video tracks are composited
  (a Media3 path still in development); if that fails on a device the preview
  falls back to the bottom track, and then to decoded still frames. Crop,
  looks, speed, volume and fades apply; scrubbing seeks the player.
- **Export to MP4** (H.264 video, AAC audio) through Media3 `Transformer`, from
  the same composition the preview plays, with every video layer composited and
  titles drawn on top. Pick 480p, 720p or 1080p (up to the project's own size;
  the sheet estimates the file size), follow progress, cancel. The video goes to
  Movies/Klipa and can be shared or played from the sheet. Exports run in a
  foreground service: switch apps freely, follow the progress in a
  notification, and tap the "Video saved" notification to play the result. HDR footage is
  tone-mapped to SDR; media shot at a higher frame rate is thinned to the
  project's.
- **Titles** are rendered by one piece of code for the monitor and the export
  (ported from ReaCut's `renderText`), so they look the same in both.
- **Playback diagnostics** (⋮ menu): player mode, state, composition layout,
  recent errors and the device, ready to copy into a bug report.
- **Timeline thumbnails and waveforms**, decoded in the background.
- **Undo/redo** (150 steps) and **autosave** to app storage, one ReaCut JSON
  file per project.
- Portrait and landscape layouts; English and Indonesian.

Not yet: a crop tool (crops in imported ReaCut projects are rendered); editing
keyframes; still frames (shown before playback starts, or if the preview falls
back) show color adjustments but not chroma key, masks or blur; text is drawn
above all media regardless of track order; the project background color is not
rendered behind media (gaps are black); a project needs a video or photo to
export.

## Install on a phone

Download the release APK (from a build, `app/build/outputs/apk/release/app-release.apk`),
open it on the phone and allow installing from that app when Android asks.
Android 8.0 or newer.

Exports need no permission on Android 10 and newer. On Android 8 and 9, Klipa
asks for storage access at the first export, to save into Movies/Klipa; without
it the video stays in the app and can still be shared.

APKs are signed with a development key committed at `app/klipa-dev.keystore`, so
any build installs over the previous one. That key is not secret: a Play Store
release needs its own key, kept out of the repository.

## Roadmap

1. ~~Core model and edit operations, with JVM unit tests~~
2. ~~Project load/save (ReaCut JSON shape) and validation~~
3. ~~Compose timeline (tracks, clips, playhead, zoom, selection) with undo and transactions~~
4. ~~Preview: real-time playback of all layers with sound~~
5. ~~Export with Media3 Transformer: one track, then multi-track, effects and text~~
6. ~~Custom GL shaders: chroma key, mask, blur, temperature~~

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
| `:core:projects` | Kotlin/JVM | The project library: one file per project, a summary index for the list, create, rename, duplicate, delete |
| `:core:designsystem` | Android library | Theme and design tokens (palette, type, spacing, shapes) |
| `:feature:timeline` | Android library | `EditorViewModel`, the Canvas timeline and its gestures |
| `:core:media` | Android library | Media access (probing, frames, waveforms) and the project → Media3 `Composition` mapping shared by preview and export |
| `:feature:preview` | Android library | Program monitor and the real-time `PreviewPlayer` |
| `:feature:export` | Android library | Export: Transformer engine, saving to the gallery, the export sheet |

`:core:model`, `:core:edit` and `:core:projects` have no Android dependency, so
their tests run on the JVM without an emulator.

Package root: `com.example.klipa` (placeholder — must change before a Play Store
release, which rejects `com.example`).

## Build

Requirements: JDK 17+, Android SDK with platform 37.

```bash
./gradlew assembleRelease                        # installable APK (R8, dev-signed)
./gradlew assembleDebug                          # debug APK
./gradlew :core:model:test :core:edit:test :core:projects:test   # engine and library tests (plain JVM)
./gradlew testDebugUnitTest                      # ViewModel, timeline, composition, export, app smoke test
./gradlew :app:recordRoborazziDebug              # editor screenshots -> app/build/outputs/roborazzi
./gradlew lintDebug
node tools/shader-check/check.js core/media/src/main/res/raw   # GLSL vs ReaCut's pixel math (needs Playwright)
```

Screenshots are rendered by Robolectric on the JVM, so the UI can be reviewed
without a device.

Preview and export use Media3 APIs marked unstable or experimental
(`Composition`, `CompositionPlayer`, `Transformer` compositions, overlays);
upgrading Media3 needs a check that they still behave the same.

Versions are pinned in `gradle/libs.versions.toml` (AGP 9.4.1, Kotlin 2.4.21,
Gradle 9.8.2, Compose BOM 2026.09.00, Media3 1.11.1). minSdk 26, targetSdk 37.

## License

Klipa includes code derived from ReaCut, used under the MIT License. See
[`NOTICE`](NOTICE).
