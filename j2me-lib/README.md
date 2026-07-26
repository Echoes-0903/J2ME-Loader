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
implementation 'ru.playsoftware.j2meloader:j2me-loader:1.8.2-external-output.5'
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
