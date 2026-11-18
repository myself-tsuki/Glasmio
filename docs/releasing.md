# Releases

All six artifacts share `GROUP` and `VERSION_NAME` in `gradle.properties`. A release tag is `v` followed by that version. The Android, Kotlin, Compose, and NDK versions are pinned in the checked-in Gradle configuration.

Every latest GitHub release must be published as a non-prerelease and include the signed Release APK named `Glasmio-demo.apk`; the README download link resolves this asset. Build the public APK with `:sample:assembleRelease`, `-Pglasmio.requireReleaseFonts=true`, and both `glasmio.symbolFont` and `glasmio.italicFont` set to the host font paths. Sign the APK with the release key before uploading it.

## Local staging

```sh
./gradlew testDebugUnitTest lintDebug :sample:assembleDebug \
  publishAllPublicationsToStagingRepository
python3 scripts/verify_consumer.py
python3 scripts/release_bundle.py --allow-unsigned
```

The staging repository is `build/maven`. It includes AARs, sources, Javadoc, POMs, and Gradle module metadata. The consumer check builds a separate application from the Maven coordinates without an included source build. The bundle check validates the six modules, required POM fields, metadata versions, and required artifacts before generating `build/glasmio-central.zip` with checksums.

`--allow-unsigned` is for local inspection. Uploads require signatures.

## Maven Central

Configure these GitHub repository or `maven-central` environment secrets:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Publisher Portal user-token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Publisher Portal user-token password |
| `MAVEN_SIGNING_KEY` | ASCII-armored private PGP signing key |
| `MAVEN_SIGNING_PASSWORD` | Signing key password, if configured |

The `io.github.neoruaa.glasmio` namespace must be verified in the publishing account. The signing public key must be available to Maven Central's signature verification. Refer to the [Central token documentation](https://central.sonatype.org/publish/generate-portal-token/) and [Publisher API](https://central.sonatype.org/publish/publish-portal-api/).

The Release workflow runs tests, lint, release assembly, staging, and a fresh consumer build before uploading an automatically published deployment. It checks the tag against `VERSION_NAME` and exits if credentials are absent. It reports success only after Central returns `PUBLISHED`.

For a local signed publication, provide the same names as environment variables, stage artifacts with Gradle, and run:

```sh
python3 scripts/release_bundle.py --upload
```

After publication, run `python3 scripts/verify_consumer.py --central` to build a clean consumer without the staging repository. Update the release status in README and CHANGELOG, and update MeiloX with the fixed version. CI should use published coordinates. Developers can explicitly opt into the local composite build for ongoing work.

## Using a fork without Maven Central

No Maven account is needed. `publishToMavenLocal` builds all six libraries (including the native code) and works without signing keys.

**JitPack (any app, any machine).** Create a GitHub release with tag `v0.2.0`, then open `https://jitpack.io/#myself-tsuki/Glasmio` and press Get it once to build it. `jitpack.yml` installs SDK 37, NDK 27.1 and CMake. In your app:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
// app/build.gradle.kts
implementation("com.github.myself-tsuki.Glasmio:glasmio-ui:v0.2.0")
implementation("com.github.myself-tsuki.Glasmio:glasmio-scaffold:v0.2.0")
```

**Local only.** `./gradlew publishToMavenLocal`, add `mavenLocal()` to your repositories and use `io.github.neoruaa.glasmio:glasmio-ui:0.2.0`.

**Source include.** Add this repo as a git submodule and `includeBuild("Glasmio")` in your `settings.gradle.kts`.

## GitHub release without credentials

`scripts/github-release.workflow.yml` is a ready workflow; copy it to `.github/workflows/github-release.yml` (pushing workflow files needs a GitHub connection with the `workflow` permission). It runs the unit tests, stages all six libraries and creates (or updates) a GitHub release for a tag, attaching each AAR and `glasmio-maven-repository.zip`. Trigger it from Actions → GitHub Release → Run workflow and enter the tag, or push a `v*` tag. It uses only the built-in `GITHUB_TOKEN`. If the tag does not exist the release is created from the commit the workflow ran on.
