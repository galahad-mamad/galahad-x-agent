#!/bin/bash
# Android Agent - Termux Integration Script
# Extracts Termux and builds the app with embedded shell

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERMUX_APK="/tmp/termux.apk"
TERMUX_DIR="$SCRIPT_DIR/termux"

echo "=== Android Agent - Termux Integration ==="

# 1. Extract Termux
if [ ! -d "$TERMUX_DIR" ]; then
    echo "[1/4] Extracting Termux..."
    mkdir -p "$TERMUX_DIR"
    cd "$TERMUX_DIR"
    unzip -q -o "$TERMUX_APK"
    echo "  Termux extracted"
else
    echo "[1/4] Termux already extracted"
fi

# 2. Copy Termux bootstrap to app assets
echo "[2/4] Copying Termux to app assets..."
mkdir -p "$SCRIPT_DIR/app/src/main/assets/termux"
cp -r "$TERMUX_DIR/lib" "$SCRIPT_DIR/app/src/main/assets/termux/" 2>/dev/null || true
cp -r "$TERMUX_DIR/bin" "$SCRIPT_DIR/app/src/main/assets/termux/" 2>/dev/null || true
echo "  Termux copied to assets"

# 3. Build APK
echo "[3/4] Building APK..."
export ANDROID_HOME=/home/kali/android-tools/sdk
export PATH=$PATH:/home/kali/android-tools/cmdline-tools/bin:$ANDROID_HOME/platform-tools
cd "$SCRIPT_DIR"
/home/kali/.gradle-dist/gradle-8.2/bin/gradle assembleDebug --no-daemon 2>&1 | tail -10

# 4. Install on emulator
echo "[4/4] Installing on emulator..."
adb install -r "$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk" 2>&1

echo ""
echo "=== Build Complete ==="
echo "APK: $SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "To run:"
echo "  adb shell am start -n com.agent.app/.ui.MainActivity"
