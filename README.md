# Klipa

A native Android video editor. Kotlin, Jetpack Compose, and AndroidX Media3
(ExoPlayer for preview, Transformer for export). The editing engine is ported
from [ReaCut](https://github.com/Xiangyu-CAS/rea-capcut) (MIT).

**Status:** project scaffold. Modules build, no features yet.

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
