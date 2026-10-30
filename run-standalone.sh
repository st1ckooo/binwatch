#!/bin/sh
# BIN Watch standalone (no Minecraft). Builds the jar on first use, then runs it. Options are passed through,
# e.g.  ./run-standalone.sh --dry-run
set -e
cd "$(dirname "$0")"
JAR=build/libs/binwatch-standalone-1.0.0.jar
[ -f "$JAR" ] || ./gradlew standaloneJar
exec java -jar "$JAR" "$@"
