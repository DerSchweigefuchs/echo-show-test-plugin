#!/bin/sh
# Builds dist/echo-show-test-<version>.zip with the SDK's tools/build.py. Toolchain is user-local, only for this call.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/root/tools/android-sdk}"
python3 tools/build.py --android-platform 35 "$@"
