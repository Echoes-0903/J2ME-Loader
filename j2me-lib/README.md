# J2ME embedding library

This module packages the emulator runtime as an Android AAR. The host owns the
`Activity` and `SurfaceView`, receives software video frames through the external
output API, and forwards input and lifecycle events through `NativeLibrary`.

## Consume a release

Add the public Maven branch to `dependencyResolutionManagement.repositories`:

```groovy
maven {
    url = uri('https://raw.githubusercontent.com/Echoes-0903/J2ME-Loader/maven/')
    content {
        includeGroup 'ru.playsoftware.j2meloader'
    }
}
```

Then add the fixed release dependency:

```groovy
implementation 'ru.playsoftware.j2meloader:j2me-loader:1.8.2-external-output.3'
```

Tags named `j2me-lib-v<version>` build the release AAR, update the `maven`
branch, and attach the AAR and POM to a GitHub Release.

## Build locally

```text
gradlew.bat -Pj2meEmbeddingBuild -Pj2meNdkVersion=27.1.12297006 :j2me-lib:assembleRelease
```
