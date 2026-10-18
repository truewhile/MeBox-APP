# Audiobook Startup Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Execute inline in the current session; the user has approved the scope.

**Goal:** Shorten audiobook startup and provide continuous, truthful feedback from the play click through audio preparation and buffering.

**Architecture:** Introduce a small pure Kotlin playback-request state machine used by the existing ReaderAudioEngine. Reuse the application HTTP connection pool in ReaderAudioService and retain the existing Compose player layout.

**Tech Stack:** Kotlin, coroutines, Media3 1.7.1, Jetpack Compose, OkHttp 4.12.0, JUnit 4.

## Global Constraints

- Preserve chapter-end guards, background playback, timers, speed, and credit skipping.
- Do not change server APIs or the video player.
- Never log signed media URLs.
- First-play buffer: 750ms; rebuffer: 2000ms; background buffer window: 50 seconds.
- Report actual verification separately from unverified real-device performance.

---

### Task 1: Playback request lifecycle

**Files:**
- Create `app/src/main/java/app/mebox/android/core/reader/ReaderAudioPlayback.kt`.
- Create `app/src/test/java/app/mebox/android/core/reader/ReaderAudioPlaybackTest.kt`.
- Modify `app/src/main/java/app/mebox/android/core/reader/ReaderAudioEngine.kt`.

**Interfaces:** `ReaderAudioPlayback.begin(autoPlay: Boolean): Long`, `isCurrent(request: Long): Boolean`, `mediaInstalled(request: Long): Boolean`, `setPlayWhenReady(value: Boolean)`, `playerChanged(buffering: Boolean, playing: Boolean, playWhenReady: Boolean)`, `fail(request: Long): Boolean`, and `reset()`.

- [x] Write regression tests asserting loading survives old idle events, media installation stays loading until ready, and intent never implies actual playing.
- [x] Write tests for play/pause changes during preparation, failures, newer same-chapter requests and stop invalidation.
- [x] Run `./gradlew.bat :app:testDebugUnitTest --tests '*ReaderAudioPlaybackTest'` and confirm missing behavior fails.
- [x] Implement the pure state machine, use it in the engine, and guard asynchronous media installation with request identity.
- [x] Preserve latest intent on install; stop old playback while resolving; restart fresh chapter loading on failure; clear connection waiters on connection failure.
- [x] Run targeted playback and chapter advance tests.

### Task 2: Network and buffering

**Files:**
- Create `app/src/main/java/app/mebox/android/core/reader/ReaderAudioNetwork.kt`.
- Create `app/src/test/java/app/mebox/android/core/reader/ReaderAudioNetworkTest.kt`.
- Modify `app/src/main/java/app/mebox/android/service/ReaderAudioService.kt`.

**Interface:** `createReaderAudioHttpClient(shared: OkHttpClient): OkHttpClient` returns a client sharing connections/DNS and with disk caching disabled.

- [x] Write MockWebServer tests: warm shared client connection then audio request must use the same connection; two cacheable audio requests must both reach the server.
- [x] Run targeted tests and confirm the missing implementation fails.
- [x] Implement the client factory using `shared.newBuilder().cache(null).build()` with bounded connect/read timeouts.
- [x] Install `DefaultDataSource.Factory` + `OkHttpDataSource.Factory` in the service; configure `DefaultLoadControl.Builder().setBufferDurationsMs(50_000, 50_000, 750, 2_000)`.
- [x] Run network tests.

### Task 3: Visible feedback and verification

**Files:**
- Modify `app/src/main/java/app/mebox/android/feature/reader/ReaderAudioPlayerScreen.kt`.
- Modify `app/src/main/java/app/mebox/android/feature/reader/ReaderViewModel.kt`.
- Extend state/UI regression tests where derived state is introduced.

- [x] Show preparation/buffering labels and a center spinner, retaining the latest play/pause action and accessible descriptions.
- [x] Include chapter-loading state, prevent repeated play clicks from canceling/restarting an in-flight chapter content request, and show a retry action after failure.
- [x] Remove unconditional playing/background labels when audio is paused or loading.
- [x] Add timing diagnostics at preparation, media installation, and first actual playback.
- [x] Execute `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- [x] Review the full diff for chapter guard, background control, timer, and retry regressions. Report build/test results and any real-device verification gap.

## Verification result (2026-10-18)

- `:app:testDebugUnitTest`: 312 tests, 0 failures, 0 ignored; 22 new tests cover audio lifecycle, feedback and real HTTP connection/cache behavior.
- `:app:lintDebug`: passed after explicitly opting in to Media3's unstable data-source/load-control APIs. Existing project warnings remain.
- `:app:assembleDebug`: passed; universal and per-ABI debug APKs generated.
- `git diff --check`: passed.
- `adb devices`: no device connected, so real-source startup latency, rendered animation and lock-screen/headset behavior have not been measured on a device.
- Self-review also covered explicit pause overriding inherited autoplay, suppression of old errors during retry, stopped sessions ignoring late events, and retaining the audio player while chapter content is absent.
