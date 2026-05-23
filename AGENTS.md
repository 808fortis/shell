# Shell — Agent Guide

## Build & verify

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # release APK (CI runs this)
```

No tests, no lint, no formatter — `assemble` is the only verification step.

## Project structure

- **App:** `app/` — Jetpack Compose, single-module Android project
- **Entry:** `app/src/main/java/com/shizush/MainActivity.kt` → `ui/MainScreen.kt`
- **Package:** `com.shizush`
- **MinSdk 28, targetSdk 34, Java 17**
- **AGP 8.2.2, Kotlin 1.9.22, Compose Compiler 1.5.8, Compose BOM 2024.01.00**

## Architecture quirks

- **No ViewModel** — state lives in composables (`remember`/`mutableStateOf`) and in `ShizukuManager` (StateFlow)
- **Dark-only theme** — Catppuccin Mocha palette, with dynamic color on Android 12+
- **Three shell providers**, auto-detected by `ShizukuManager.initialize()`:
  1. Shizuku (primary, via Shizuku API)
  2. Wireless Debugging (reads ADB WiFi settings)
  3. Direct Shell (`Runtime.exec`)
- **Foreground service** (`ShellService`) with notification channel `"shell_terminal"`
- **ProGuard** keeps `com.shizush.shizuku.**`

## Dependency gotchas

- Shizuku artifacts use **`dev.rikka.shizuku`** groupId (not `rikka.shizuku`)
- Extra Maven repo: `https://maven.aliucord.com/snapshots` (defined in `settings.gradle.kts`)
- `local.properties` is gitignored — must exist locally with `sdk.dir` for builds
- **Release signing** uses `app/keystore.jks` (gitignored); CI builds without it (unsigned). Generate with:
  ```bash
  keytool -genkey -v -keystore app/keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias shell -storepass shell123 -keypass shell123 -dname "CN=Shell, OU=Dev, O=Shell, L=Unknown, ST=Unknown, C=US"
  ```

## CI

- `.github/workflows/build.yml` — builds release APK on push/PR to `master`, uploads artifact
