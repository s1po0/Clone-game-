#!/bin/sh
# Minimal Gradle wrapper launcher for MangaScribe.
# Uses JAVA_HOME when it is set, otherwise java from PATH.
set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd -P)

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=java
fi

if ! command -v "$JAVACMD" >/dev/null 2>&1; then
  echo "gradlew: no java found. Install JDK 17 or set JAVA_HOME." >&2
  exit 1
fi

exec "$JAVACMD" \
  -Xmx64m -Xms64m \
  -Dorg.gradle.appname=gradlew \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
