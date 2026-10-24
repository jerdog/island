# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Island is an Android Device Policy Controller (DPC) app: it acts as profile owner (work profile, "Island") or device owner, and lets users clone/freeze/suspend apps across users. It also exposes its DPC capabilities to third-party apps through an open API.

## Build setup

- **Required sibling checkout:** the [`deagle`](https://github.com/oasisfeng/deagle) library must be cloned next to this repo (`../deagle`). `settings.gradle` maps `:deagle` to `../deagle/library`; nothing builds without it.
- Toolchain is old and pinned: Gradle 6.5 wrapper, Android Gradle Plugin 4.1.3, Kotlin 1.4.32, Java 8 source/target, `compileSdk 30`, `minSdk 24`. The `assembly` app uses `targetSdk 29` deliberately because `open` and `fileprovider` access private APIs.
- Repositories still include `jcenter()`.
- An optional, git-ignored `local.gradle` in the root is applied when present.
- The `assembly` module applies Google Services/Crashlytics, so it needs a `google-services.json` that is not in the repo.

## Commands

`assembly` is the application module and the build entry point. Its product flavors (dimension `packaging`) choose which modules are packaged:

| Flavor | Application ID | Contents |
|---|---|---|
| `complete` | `com.oasisfeng.island` | all modules |
| `engine` | `com.oasisfeng.island` (same ID, so profile/device ownership is kept) | `engine` only |
| `mobile` | `com.oasisfeng.island.mobile` | `mobile` (UI) only |
| `fileprovider` | `com.oasisfeng.island.fileprovider` | `fileprovider` only |

```sh
./gradlew :assembly:assembleCompleteDebug      # full app
./gradlew :assembly:installEngineDebug         # engine only, installs over the complete build
./gradlew :assembly:installMobileDebug         # UI module, installed side-by-side with engine
./gradlew :assembly:lintCompleteDebug          # lint (only checks NewApi; aborts on error)
./gradlew :engine:connectedAndroidTest         # instrumented tests (needs a device)
./gradlew :engine:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.oasisfeng.island.api.ApiActivityTest
```

The only tests in the repo are the instrumented tests in `engine/src/androidTest`; there are no JVM unit tests. In `shared`, every `assemble` depends on the lint task, so a lint error in `shared` fails the build.

## Architecture

All APKs share `android:sharedUserId="com.oasisfeng.island"`. That is why the separately installed flavor builds (`mobile`, `fileprovider`) run with the engine's UID and can use its DPC privileges. `Modules` (`shared/.../util/Modules.java`) finds sibling components by querying packages that share this UID.

Modules (all Android libraries except `assembly`):
- **shared**: code used by every module. It holds the API protocol (`api/Api.java`, versioned as nested `v1`/`v2`/`latest` interfaces; this is the public contract for 3rd-party apps, so keep it backward compatible), device-policy helpers (`util/DevicePolicies`), user/profile helpers (`util/Users`), analytics/Firebase wrappers, and the cross-profile **shuttle** framework.
- **engine**: the privileged core. It contains `IslandDeviceAdminReceiver`, provisioning (`provisioning/`, including `IslandProvisioning` and the cross-profile intent filters), and the API endpoints (`api/ApiActivity`, `ApiReceiver`, `ApiDispatcher`) that check callers and run freeze/launch/suspend requests.
- **mobile**: the user-facing UI (data binding, MVVM-ish `model/` + `controller/`, settings, setup wizard). It depends on `mobile/libs/setup-wizard-lib-platform-release.aar`.
- **open**: `SystemServiceBridge` (runs in a separate `:api` process) and delegated-scope authorization for 3rd-party apps.
- **fileprovider**: cross-profile file access through a DocumentsProvider and a shuttle provider, each running in its own process.
- **installer**: an app installer that runs inside Island and parses APKs with `apk-parser`.
- **watcher**: `IslandAppWatcher`, which tracks app state for notifications.

### Cross-user execution ("shuttle")

Island code constantly runs in two users: the owner (main) user and the managed profile. Calls that cross the boundary go through `shared/.../shuttle/`:
- `Shuttle(context, to = userHandle).invoke { ... }` / `.launch { ... }` runs a `Context.() -> R` lambda in the target user. It runs the lambda directly if the target is the current user. Otherwise it serializes the lambda as a `Closure` and sends it through `ShuttleProvider` with `ContentResolver.call` on a cross-profile URI.
- The shuttle only works after URI permission has been granted between the users (`ShuttleProvider.isReady`). Otherwise the call throws "Shuttle not ready".
- Lambdas sent this way must only capture serializable/parcelable values.
- Older mechanisms (`MethodShuttle`, `ServiceShuttle`, `ActivityShuttle`) are still in use.

The annotations `@OwnerUser` / `@ProfileUser` (source-retention, in `shared/.../util/`) document which user a class or method is expected to run in. Keep them accurate when you move code across that boundary.

`Users` is declared as a (pseudo) content provider so it initializes early. Its static state is only valid in the default process, and it fails early if used from another process. Watch the `android:process` attributes in manifests (`:api`, `:file`, `:file_shuttle`) when adding code that touches `Users`.

### Hidden API stubs

`open/src/main/java/android/...` and `fileprovider/src/main/java/android/content/...` contain stub classes inside framework packages, used to compile against hidden or derived platform classes. `fileprovider/build.gradle` removes the compiled `android/` stub classes from the output so they are not packaged. Reflection-based access to private APIs lives in `shared/.../util/Hacks.java`.

## Conventions

- The codebase mixes Java and Kotlin. Indent with **tabs**. Keep the existing compact style: long single-line statements and `final` locals/params in Java.
- Lint is configured to check only `NewApi`, so API-level guards (`minSdk 24`) matter more than other lint rules.
- Contribution policy (from README): bug fixes, small improvements and translations can be sent as PRs directly. Open an issue first to discuss feature work or new APIs.
