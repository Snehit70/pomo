#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="$PROJECT_DIR/android-sdk"
GRADLE_DIR="$PROJECT_DIR/gradle-dist"

export JAVA_HOME="/usr/lib/jvm/jdk-17.0.12-oracle-x64"
export ANDROID_HOME="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$GRADLE_DIR/gradle-9.2.1/bin:$PATH"

if [ -x "$PROJECT_DIR/gradlew" ]; then
    "$PROJECT_DIR/gradlew" testDebugUnitTest "$@"
else
    gradle testDebugUnitTest "$@"
fi
