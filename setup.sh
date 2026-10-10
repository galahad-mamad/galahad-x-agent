#!/bin/bash
# Android Agent - Full Auto Setup
# Usage: bash ~/android-agent/setup.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "=== Android Agent Setup ==="

# 1. Install Java (already done, verify)
if ! command -v java &> /dev/null; then
    echo "[1/6] Installing Java 17..."
    sudo apt-get update -qq
    sudo apt-get install -y -qq openjdk-17-jdk-headless unzip wget git
else
    echo "[1/6] Java already installed"
fi

# 2. Create Android SDK directory
echo "[2/6] Setting up Android SDK..."
mkdir -p ~/Android/Sdk/cmdline-tools
cd ~/Android/Sdk

# 3. Try to download SDK (may fail due to network restrictions)
if [ ! -f cmdline-tools/latest/bin/sdkmanager ]; then
    echo "[3/6] Downloading Android SDK (this may fail in some regions)..."
    # Try Google first
    if ! curl -L -o cmdline-tools.zip --max-time 60 -H "User-Agent: Mozilla/5.0" \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip 2>/dev/null; then
        echo "  Google download failed, trying mirror..."
        curl -L -o cmdline-tools.zip --max-time 60 \
            https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip 2>/dev/null || true
    fi
    
    if [ -f cmdline-tools.zip ] && file cmdline-tools.zip | grep -q "Zip"; then
        unzip -q -o cmdline-tools.zip -d cmdline-tools/
        mv cmdline-tools/cmdline-tools cmdline-tools/latest 2>/dev/null || true
        rm -f cmdline-tools.zip
        echo "  SDK downloaded successfully"
    else
        echo "  ⚠️  SDK download failed (network restriction?)"
        echo "  The project code is still ready - you can build it later"
        echo "  when you have access to Android SDK"
    fi
else
    echo "[3/6] Android SDK already present"
fi

# 4. Set up Gradle wrapper
echo "[4/6] Setting up Gradle..."
cd "$SCRIPT_DIR"
if [ ! -f gradlew ]; then
    curl -L -o gradle-8.2-bin.zip --max-time 60 \
        https://services.gradle.org/distributions/gradle-8.2-bin.zip 2>/dev/null || true
    if [ -f gradle-8.2-bin.zip ]; then
        unzip -q -o gradle-8.2-bin.zip -d /tmp/
        cp -r /tmp/gradle-8.2/* ~/android-agent/gradle/wrapper/ 2>/dev/null || true
        chmod +x gradlew 2>/dev/null || true
    fi
    rm -f gradle-8.2-bin.zip
fi

# 5. Create gradlew script
if [ ! -f "$SCRIPT_DIR/gradlew" ]; then
    cat > "$SCRIPT_DIR/gradlew" << 'GRADLEW'
#!/bin/sh
# Gradle wrapper script
exec java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
GRADLEW
    chmod +x "$SCRIPT_DIR/gradlew"
fi

# 6. Try to build
echo "[5/6] Attempting build..."
export ANDROID_HOME=~/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

if [ -f ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager ]; then
    # Accept licenses
    yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses 2>/dev/null || true
    # Install required components
    ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>/dev/null || true
    
    # Try building
    if ./gradlew assembleDebug --no-daemon 2>&1 | tail -20; then
        echo "[6/6] ✅ Build successful!"
        echo ""
        echo "APK location: app/build/outputs/apk/debug/app-debug.apk"
    else
        echo "[6/6] ⚠️  Build failed - check errors above"
    fi
else
    echo "[5/6] Skipping build (SDK not available)"
    echo "[6/6] ⚠️  Project structure is ready at: $SCRIPT_DIR"
    echo ""
    echo "To build later:"
    echo "  1. Install Android SDK"
    echo "  2. cd $SCRIPT_DIR"
    echo "  3. ./gradlew assembleDebug"
fi

echo ""
echo "=== Setup Complete ==="
echo "Project location: $SCRIPT_DIR"
echo ""
echo "Next steps:"
echo "  1. Open project in Android Studio"
echo "  2. Or build from CLI: cd $SCRIPT_DIR && ./gradlew assembleDebug"
echo "  3. Install APK on device: adb install app/build/outputs/apk/debug/app-debug.apk"
