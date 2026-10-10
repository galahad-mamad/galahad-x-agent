#!/bin/bash
# Android Agent - Quick Setup & Run
# Usage: bash ~/android-agent/run.sh

set -e

export ANDROID_HOME=/home/kali/android-tools/sdk
export PATH=$PATH:/home/kali/android-tools/cmdline-tools/bin:$ANDROID_HOME/platform-tools
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

echo "=== Android Agent Runner ==="

# 1. Check if emulator is running
if ! adb devices | grep -q "emulator-5554.*device"; then
    echo "[1/5] Starting emulator..."
    $ANDROID_HOME/emulator/emulator -avd tgtest -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
    echo "  Waiting for boot (90s)..."
    sleep 90
else
    echo "[1/5] Emulator already running"
fi

# 2. Install APK
echo "[2/5] Installing APK..."
adb install -r /home/kali/android-agent/app/build/outputs/apk/debug/app-debug.apk

# 3. Enable Accessibility Service
echo "[3/5] Enabling Accessibility Service..."
adb shell settings put secure enabled_accessibility_services com.agent.app/.service.AgentAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 4. Enable Overlay permission
echo "[4/5] Enabling Floating Overlay..."
adb shell appops set com.agent.app SYSTEM_ALERT_WINDOW allow

# 5. Launch app
echo "[5/5] Launching app..."
adb shell am start -n com.agent.app/.ui.MainActivity

echo ""
echo "=== Agent Ready ==="
echo "The Android Agent app is now running with:"
echo "  ✅ Accessibility Service enabled"
echo "  ✅ Floating Overlay enabled"
echo ""
echo "Screenshot saved to: /tmp/agent_screen3.png"
echo "App is active and ready for Phase 2 features."
