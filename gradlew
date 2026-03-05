#!/bin/sh
#
# Copyright © 2015-2021 the original authors.
# Licensed under the Apache License, Version 2.0
#

##############################################################################
# Shell script for running Gradle wrapper bootstrap
##############################################################################

# Attempt to set APP_HOME
APP_HOME="${APP_HOME:-$(cd "$(dirname "$0")" && pwd)}"

# Gradle wrapper jar location
GRADLE_WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# If the wrapper jar doesn't exist, download it
if [ ! -f "$GRADLE_WRAPPER_JAR" ]; then
    echo "Downloading Gradle wrapper jar..."
    mkdir -p "$APP_HOME/gradle/wrapper"
    curl -fsSL "https://github.com/gradle/gradle/raw/v8.6.0/gradle/wrapper/gradle-wrapper.jar" \
         -o "$GRADLE_WRAPPER_JAR" 2>/dev/null || \
    wget -q "https://github.com/gradle/gradle/raw/v8.6.0/gradle/wrapper/gradle-wrapper.jar" \
         -O "$GRADLE_WRAPPER_JAR" 2>/dev/null || \
    { echo "ERROR: Could not download gradle-wrapper.jar. Run: gradle wrapper --gradle-version 8.6"; exit 1; }
fi

exec java -jar "$GRADLE_WRAPPER_JAR" "$@"
