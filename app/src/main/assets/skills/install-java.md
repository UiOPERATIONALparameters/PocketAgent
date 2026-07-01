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
# Download the .deb file (don't install it)
apt download openjdk-17
# or
pkg download openjdk-17
```

### 2. Extract it manually
```bash
# Extract to a temporary directory
mkdir -p /tmp/jdk-extract
dpkg-deb -x openjdk-17_*.deb /tmp/jdk-extract
```

### 3. Copy to the right location
```bash
# Copy the JDK files to the usr directory
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

## Alternative: Use the wrapper
```bash
# If the above doesn't work, try:
apt download openjdk-17
dpkg-deb --unpack openjdk-17_*.deb
# Then manually run the postinst:
/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/bin/java -version
```

## Tips
- The JDK binaries themselves work fine — only the preinst script triggers seccomp
- After manual installation, `java` and `javac` should work normally
- For building APKs, also install gradle: `pkg install gradle`
- JDK 21 has the same issue — use the same workaround
