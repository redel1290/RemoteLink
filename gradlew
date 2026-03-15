#!/bin/sh
APP_HOME="$(cd "$(dirname "$0")" && pwd -P)"
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# If wrapper jar exists, use it normally
if [ -f "$WRAPPER_JAR" ]; then
  if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
  else
    JAVACMD="java"
  fi
  exec "$JAVACMD" -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
fi

# Fallback: use gradle command directly (available via setup-gradle action)
if command -v gradle > /dev/null 2>&1; then
  exec gradle "$@"
fi

echo "ERROR: Neither gradle-wrapper.jar nor gradle command found." >&2
echo "Please run: gradle wrapper --gradle-version 8.6" >&2
exit 1
