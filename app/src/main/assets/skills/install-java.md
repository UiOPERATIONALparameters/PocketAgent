# Skill: Install Java/JDK (with seccomp workaround)

## When to Use
The user wants to install Java for building APKs or running Java programs.

## CRITICAL: JDK preinst script triggers seccomp (SIGSYS)

On Android 14+, the JDK pre-installation script uses a syscall blocked by
Android's seccomp filter (signal 31 / SIGSYS). `pkg install openjdk-17`
will FAIL with:
  "dpkg: error processing archive openjdk-17: pre-installation script
   terminated with signal 31"

## Workaround: Manual Extraction

### 1. Download the JDK package manually
```bash
apt download openjdk-17
```

### 2. Extract it manually
```bash
mkdir -p /tmp/jdk-extract
dpkg-deb -x openjdk-17_*.deb /tmp/jdk-extract
```

### 3. Copy to the right location
```bash
cp -r /tmp/jdk-extract/data/data/com.termux/files/usr/* /data/data/com.termux/files/usr/
```

### 4. Verify
```bash
java -version
javac -version
```

### 5. Clean up
```bash
rm -rf /tmp/jdk-extract openjdk-17_*.deb
```

## Tips
- The JDK binaries work fine — only the preinst script triggers seccomp
- After manual installation, java and javac should work normally
- For building APKs, also install gradle: pkg install gradle
