# J2ME embedding library

This module packages the emulator runtime as an Android AAR. The host owns the
`Activity` and `SurfaceView`, receives software video frames through the external
output API, and forwards input and lifecycle events through `NativeLibrary`.
The embedding API does not accept or attach a host `ViewGroup` or `OverlayView`.

Create the facade with only the host activity and callbacks, set an
`ExternalVideoOutput`, then start the game with host-provided paths:

```java
NativeLibrary runtime = new NativeLibrary(activity, callbacks);
runtime.setExternalOutput(videoOutput);
runtime.setAudioEnabled(true);
runtime.startGame(midletJarPath, conversionDirectoryPath);
```

Canvas content is emitted exclusively through `ExternalVideoOutput`. Non-Canvas
LCDUI screens such as `Form`, `List`, `TextBox`, and `Alert` are delivered as
immutable semantic `LcdUiState` snapshots through
`Callbacks.onLcdUiStateChanged`. The host renders them with Compose, Views, or
another UI toolkit and returns interactions through
`NativeLibrary.dispatchUiAction(LcdUiAction)`. The embedding API never creates or
returns an Android `View` or `Dialog`.

```java
callbacks.onLcdUiStateChanged(state);
runtime.dispatchUiAction(LcdUiAction.command(state.getScreenId(), commandId));
runtime.dispatchUiAction(LcdUiAction.setText(state.getScreenId(), itemId, text));
```

## Host pause and resume (since 1.8.2-external-output.7)

`NativeLibrary.pause()` / `resume()` remain asynchronous and safe to call from an
Activity lifecycle callback. `Callbacks.onStateChanged(PAUSED/RUNNING)` now reports
completion of the MIDlet lifecycle and external Canvas visibility callbacks, not
just submission to a Handler. Callbacks run on the Android main thread. Never block
that thread waiting for them. A rejected `startApp()` reports `PAUSED`; failed or
timed-out callbacks report an error. Superseded requests cannot reopen output.

Pause releases tracked keys/pointers, sends `hideNotify`, invokes `pauseApp`, and
holds external frame delivery. Resume invokes `startApp`, sends `showNotify` to
the current Canvas, and reopens output. A Canvas selected while paused stays
hidden. The host keeps its Surface and the last displayed frame. Hosts should
only block new gameplay input after releasing previously held inputs. Game-owned
LCDUI is still part of the game, not a reason to pause it by itself.

Host audio pause is separate from the player's requested state and user mute:
normal media playback is paused at the backend; new starts are deferred; players
stopped/closed by the game are not restarted. Raw MIDI/Tone synthesizer output is
muted while paused: that backend has no playback-cursor pause API, and an already
submitted tone is not replayed on resume.

This is cooperative MIDP pause, **not a VM freeze**. Arbitrary game Threads,
Timers and wall-clock time still advance unless the game suspends them. A game
may show its own pause menu on `hideNotify` and require its own Continue command.

## Host vibration output (since 1.8.2-external-output.7)

`J2meSession.Callbacks.onVibrationRequested(int durationMillis)` forwards game
vibration requests on the Android main thread. A positive duration (milliseconds)
replaces the current effect; `0` cancels it. The default callback is intentionally
empty: embedded sessions never fall back to the Android vibrator, even when the
host ignores a request. The game-facing return value indicates host ownership,
not the presence of a phone/controller motor. The standalone vibration preference
does not suppress this output; the host owns device selection and haptic settings.

Only a visible, running session forwards positive requests. Pause, failure and
shutdown send cancellation; obsolete queued requests are discarded and effects
are not replayed on resume. Cancellation can repeat and must be idempotent. A final
cancellation precedes `onSessionFinished`, with no vibration callbacks afterwards.

The existing MIDP/Nokia/Siemens/Samsung/Vodafone paths share this bridge. Existing
vendor duration conversion is unchanged; strength/frequency is not exposed. This
does not implement previously stubbed vendor APIs or virtual-keyboard feedback.

## Consume a release

Add GitHub Packages to `dependencyResolutionManagement.repositories`:

```groovy
maven {
    url = uri('https://maven.pkg.github.com/echoes-0903/J2ME-Loader')
    credentials {
        username = providers.gradleProperty('gpr.user').orNull
                ?: System.getenv('GITHUB_ACTOR')
        password = providers.gradleProperty('gpr.key').orNull
                ?: System.getenv('GITHUB_TOKEN')
    }
    content {
        includeGroup 'ru.playsoftware.j2meloader'
    }
}
```

Then add the fixed release dependency:

```groovy
implementation 'ru.playsoftware.j2meloader:j2me-loader:1.8.2-external-output.7'
```

Use a classic personal access token with `read:packages` in the user-level
`~/.gradle/gradle.properties` file:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_CLASSIC_PAT
```

For private repositories, the token also needs `repo`. Never commit this file.

Tags named `j2me-lib-v<version>` build the release AAR, publish it to GitHub
Packages, and attach the AAR and POM to a GitHub Release. The workflow can also
be run manually with an explicit Maven version.

## Build locally

```text
gradlew.bat -Pj2meEmbeddingBuild -Pj2meNdkVersion=27.1.12297006 :j2me-lib:assembleRelease
```
