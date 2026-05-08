# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

AabResGuard is a resource obfuscation tool for Android App Bundle (`.aab`) files, built on top of Google's `bundletool` (pinned to **0.10.0**, see "Why bundletool stays at 0.10.0" below). It ships in two forms from this same repo:

- A standalone command-line jar (`core` module) — entry point `com.bytedance.android.aabresguard.AabResGuardMain`.
- A Gradle plugin (`plugin` module) for **AGP 9.1+** that hooks into AGP's bundle pipeline via the modern Variant API (`androidComponents.onVariants` + `Artifacts.use(...).wiredWithFiles(...).toTransform(SingleArtifact.BUNDLE)`).

`samples/` contains a real Android app (`:app`) plus two dynamic feature modules (`:df_module1`, `:df_module2`) used as the integration target for both the plugin and the test suite.

## Required environment

- **JDK 17** (build toolchain). Set `JAVA_HOME` to a JDK 17 install before invoking the wrapper.
- **Gradle 9.3.1** (provided by wrapper). AGP 9.1.x requires Gradle ≥ 9.3.1.
- **Kotlin 2.1.20** for the plugin module.
- **Android SDK** with `compileSdk = 35`, `minSdk = 24` for the sample app.

## Build & development commands

```bash
# Build the standalone CLI fat jar (output: core/build/libs/AabResGuard-<version>.jar)
./gradlew :core:shadowJar -PuseSource=true -x compileTestJava

# Build the plugin
./gradlew :plugin:assemble -PuseSource=true

# Publish core + plugin to ~/.m2/repository so the sample can consume them
./script/publish.sh l                     # publishToMavenLocal
./gradlew :core:publishToMavenLocal :plugin:publishToMavenLocal -PuseSource=true -x compileTestJava

# Build the sample bundle WITH the plugin (after publishing locally)
./gradlew clean :app:bundleDebug -DenableAabResGuardPlugin=true -DuseLocalMaven=true

# Build the sample bundle WITHOUT the plugin (vanilla AGP build)
./gradlew clean :app:bundleDebug
```

### System-property toggles that change the build graph

- `-DenableAabResGuardPlugin=true` — adds the plugin to the `buildscript` classpath (`build.gradle:23-26`) and applies `gradle/aabresguard.gradle` to the sample app. Default is `false` so the project can build the plugin from source without a chicken-and-egg classpath problem.
- `-DuseLocalMaven=true` — switches both `buildscript` and `dependencyResolutionManagement` repos to include `mavenLocal()`. Use this after `./script/publish.sh l` to test plugin changes against the sample app end-to-end.
- `-PuseSource=true` (Gradle property, NOT `-D`) — makes `:plugin` depend on `project(':core')` instead of the published `aabresguard-core` Maven coordinate. Required for first-time builds and dev iteration.
- `gradle.properties` is loaded into System properties at config time but only fills in **unset** keys (`gradle/config.gradle:loadSystemProperties`). Command-line `-D` flags always win.

## Architecture

### Core module (`core/`) — pure Java 8, shaded into a fat jar

The CLI dispatcher (`AabResGuardMain`) parses flags via bundletool's `FlagParser` and routes to one of four commands under `commands/`:

| Command | Class | What it does |
|---|---|---|
| `obfuscate-bundle` | `ObfuscateBundleCommand` | Top-level pipeline: optionally merges duplicates → obfuscates resource names/paths → optionally filters files/strings → repackages and signs. Composes the other executors. |
| `merge-duplicated-res` | `DuplicatedResourcesMergerCommand` | Detects byte-identical resource files (md5) and rewrites the resource table to point all references at a single retained copy. |
| `filter-file` | `FileFilterCommand` | Drops bundle entries matching regex/glob rules (only `META-INF/` and `lib/` paths are supported). |
| `filter-string` | `StringFilterCommand` | Removes string resource values by name from a line-delimited unused-strings file, and prunes translations outside a language whitelist. |

Each command uses `@AutoValue` builders, calls `fromFlags(ParsedFlags)` to construct itself, and delegates the actual work to a class under `executors/` (`ResourcesObfuscator`, `BundleFileFilter`, `BundleStringFilter`, `DuplicatedResourcesMerger`).

**Mutability gotcha**: several executors mutate the `Set<String>` collections passed into them (notably `BundleFileFilter:67` calls `filterRules.addAll(...)`). The Gradle plugin defends against this in `AabResGuardTask` by wrapping each `SetProperty.get()` call in `HashSet(...)` before forwarding to `ObfuscateBundleCommand.builder()`. Don't remove those copies.

The shadow jar (`core/build.gradle`) **relocates** `com.android.*` → `shadow.bytedance.com.android.*` and `com.google.*` → `shadow.bytedance.com.google.*`, and `minimize()`s unused classes. This avoids classpath clashes when consumers (the Gradle plugin, or other tools) embed the jar alongside AGP. Uses `com.gradleup.shadow` 9.0.0 (the maintained fork of the Engelman plugin); the publication is hooked via `from components.shadow` (changed from the old `project.shadow.component(publication)` API in `gradle/publish-shadow.gradle`).

### Plugin module (`plugin/`) — Kotlin 2.1, JVM 17

`AabResGuardPlugin` registers an `aabResGuard { ... }` extension (`AabResGuardExtension`), then iterates variants via `ApplicationAndroidComponentsExtension.onVariants { variant -> ... }`. For each variant it registers an `aabresguard<Variant>` task and wires it as an in-place transform of the `SingleArtifact.BUNDLE` artifact:

```kotlin
variant.artifacts
    .use(taskProvider)
    .wiredWithFiles(AabResGuardTask::inputBundle, AabResGuardTask::outputBundle)
    .toTransform(SingleArtifact.BUNDLE)
```

This means: AGP runs `signDebugBundle` (or release equivalent) → hands the **signed** AAB to the task as `inputBundle` → the task obfuscates and **re-signs** the output → AGP treats that output as the final BUNDLE artifact (so `bundleDebug` consumes it directly without extra wiring).

Signing config is resolved via the DSL: `androidExtension.buildTypes.findByName(variant.buildType)?.signingConfig` returns an `ApkSigningConfig` with `storeFile`/`storePassword`/`keyAlias`/`keyPassword`. The `Variant.signingConfig` property in the new API only exposes signing-version flags, not keystore details — that's why we go through the build type instead.

`AabResGuardTask` uses lazy properties (`RegularFileProperty`, `Property<>`, `SetProperty<>`) compatible with the configuration cache.

### Module layout

`settings.gradle` includes 5 projects: `:core`, `:plugin`, `:app`, `:df_module1`, `:df_module2`. The latter three live under `samples/` and exist purely as integration test targets. **The published artifacts are only `:core` (`aabresguard-core`) and `:plugin` (`aabresguard-plugin`)** — `script/publish.sh` only invokes those two.

## Versions and dependencies

Centralized in `gradle/versions.gradle` and `gradle/config.gradle`:

- `aabresguard` — artifact version (currently `0.2.0`)
- `agp` — Android Gradle Plugin (`9.1.0`)
- `kotlin` — `2.1.20`
- `bundletool` — `0.10.0` (force-pinned via `resolutionStrategy` in `core/build.gradle`; do not let transitive deps drift this — see next section)
- `java` — `17` (toolchain). `:core` still targets bytecode 8 (`sourceCompatibility = VERSION_1_8`); `:plugin` and the sample app target 17.
- `compileSdkVersion` — `35`, `minSdkVersion` — `24`
- `shadow` — `9.0.0` (`com.gradleup.shadow`)
- `protobuf` — `0.9.4` plugin, `protoc` 3.25.5

### Why bundletool stays at 0.10.0

The `core` executors are built directly against bundletool's internal model (`AppBundle`, `BundleModule`, `ModuleEntry`, `InMemoryModuleEntry`, `ZipPath`, `ResourceTableEntry`, `BundleMetadata`, `ResourcesUtils`, …). bundletool 1.x refactored most of those types (e.g. `ModuleEntry` from interface to `@AutoValue` class, removal of `InMemoryModuleEntry`, package moves under `model.utils.*`). Upgrading bundletool requires porting every executor. The current AGP 9.1 build path verifies that bundletool 0.10.0 can still parse and re-write AABs produced by AGP 9.1, so the upgrade has been deferred. If you ever hit a parse/serialize failure, that's the trigger to bite the bundletool 1.x port.

### Tests

Tests are not built by default in the AGP 9 setup (`testImplementation deps.gradle.agp` was removed because AGP 9.x requires JVM ≥ 11, conflicting with `:core`'s Java 8 target). The publish/build scripts skip `compileTestJava`. To run tests you'd need to either keep an older AGP coordinate just for tests or move the test toolchain to 17.

## Conventions

- Package root for core code: `com.bytedance.android.aabresguard.*`. Plugin code: `com.bytedance.android.plugin.*`.
- Commands are the public API of `core`. When adding a new CLI command: create a `*Command` class with `@AutoValue`, a `COMMAND_NAME` constant, `fromFlags`/`help`/`execute`, then register it in all four switch-cases in `AabResGuardMain` (dispatch + general help + per-command help).
- Plugin's task properties must remain lazy (`Property<>`, `SetProperty<>`, `RegularFileProperty`) — Gradle 9 + configuration cache demands it. Don't introduce eager fields on the task.
- The `obfuscatedBundleFileName` extension property is **deprecated and ignored** under AGP 9: the obfuscated AAB now replaces the BUNDLE artifact in place at the path AGP chose. Don't add code that reads it.
