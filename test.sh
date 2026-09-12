#!/usr/bin/env bash
# FR3K HUD test script — runs JVM unit tests in every Gradle module.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon -q test
echo "[test] OK"
