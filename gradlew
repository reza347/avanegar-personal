#!/usr/bin/env sh
# Lightweight bootstrap: fetch the *official* Gradle wrapper JAR if missing.
# Recommended on macOS/Linux; it is downloaded from Gradle's tagged GitHub source.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER="$ROOT/gradle/wrapper/gradle-wrapper.jar"
if [ ! -s "$WRAPPER" ]; then
  echo 'Downloading official Gradle wrapper (v8.11.1)...'
  URL='https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar'
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error "$URL" -o "$WRAPPER"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$URL" -O "$WRAPPER"
  else
    echo 'Need curl or wget to bootstrap Gradle.' >&2
    exit 1
  fi
fi
exec java -classpath "$WRAPPER" org.gradle.wrapper.GradleWrapperMain "$@"
