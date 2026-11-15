# Winnow for Android

A gallery declutterer. Two judges, a photographer and a poster, go through look-alike photos
together; a frame is proposed for deletion only when neither finds a reason to keep it, and nothing
moves until the session is approved. Approved items go to Android's own 30-day trash.

Everything runs on the phone. The package declares no internet permission.

## Build

Needs JDK 17 or newer and the Android SDK (platform 35, build-tools 35.0.0).

    echo "sdk.dir=/path/to/android-sdk" > local.properties
    gradle :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
    gradle :app:testDebugUnitTest    # engine tests, plus renders of every screen in app/build/shots

Versions: Android Gradle Plugin 8.11.1, Kotlin 2.1.21, Compose BOM 2025.06.00, Gradle 8.14.3.

## Where things are

- `engine/Pixels.kt`   image measures in plain Kotlin: focus, exposure, colour, layout signature, backdrop
- `engine/Judges.kt`   look-alike grouping, the eight "best" categories, the two judges, tuning, learning
- `engine/Analyzer.kt` reads a photo and runs the bundled face and person models
- `data/`              gallery access, thumbnails, on-phone cache and settings
- `WinnowViewModel.kt` scanning, sessions, trash and restore
- `ui/`                Compose screens

## Not built yet

Reel maker, space-saver (compressed) trash, background scanning while the app is closed,
people recognition across the library, pet and object subjects for the backdrop judge.
