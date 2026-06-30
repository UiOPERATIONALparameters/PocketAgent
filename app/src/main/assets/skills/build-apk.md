# Skill: Build an Android APK

## When to Use
The user wants to build an Android APK from source code.

## Prerequisites
- Linux environment installed (Settings → Linux Environment)
- JDK and Android SDK needed (install via pkg)

## Steps

### 1. Install Build Tools
```bash
pkg install openjdk-17 git
```

### 2. Clone or Create Project
```bash
cd ~/projects
git clone <repo-url> myapp
cd myapp
```

### 3. Build the APK
```bash
# Debug APK:
./gradlew assembleDebug
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

### 4. Copy to Downloads
```bash
cp app/build/outputs/apk/debug/app-debug.apk ~/downloads/
```

### 5. Install on Phone
Use the `install_apk` tool with path `downloads/app-debug.apk`.

## Tips
- Debug APKs don't need signing
- Use `--no-daemon` if memory is tight: `./gradlew assembleDebug --no-daemon`
