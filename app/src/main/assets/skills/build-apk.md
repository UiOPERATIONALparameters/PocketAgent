# Skill: Build an Android APK

## When to Use
The user wants to build an Android APK from source code. This includes:
- Building an existing Android project
- Creating a new app from scratch
- Modifying and rebuilding an APK

## Prerequisites
- Linux environment installed (Settings → Linux Environment)
- If not installed: tell user to install it first
- JDK 17+ and Android SDK needed (install via apk)

## Steps

### 1. Install Build Tools
```bash
apk add openjdk17-jdk git
# Gradle will download itself via the wrapper
```

### 2. Set Up Android SDK
The Android SDK is large (~1GB). For simple apps, use the Gradle wrapper which handles SDK download.
If the project has a `gradlew` file, use it directly.

### 3. Clone or Create Project
```bash
# Clone existing:
cd ~/projects
git clone <repo-url> myapp
cd myapp

# Or create new:
mkdir -p ~/projects/myapp && cd ~/projects/myapp
```

### 4. Build the APK
```bash
# Debug APK:
./gradlew assembleDebug

# Release APK (needs signing):
./gradlew assembleRelease
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`
or: `app/build/outputs/apk/release/app-release-unsigned.apk`

### 5. Copy to Downloads
```bash
cp app/build/outputs/apk/debug/app-debug.apk ~/downloads/
```

### 6. Install on Phone
Use the `install_apk` tool with path `downloads/app-debug.apk`.
The user will see the system install dialog.

## Tips
- Debug APKs don't need signing — use debug builds for testing
- Release APKs need a keystore: `keytool -genkey -v -keystore release.keystore -alias mykey -keyalg RSA -keysize 2048 -validity 10000`
- Build can take 5-10 minutes for the first build (downloads dependencies)
- Use `--no-daemon` if memory is tight: `./gradlew assembleDebug --no-daemon`
