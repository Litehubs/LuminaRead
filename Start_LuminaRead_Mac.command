#!/bin/bash
cd "$(dirname "$0")" || exit 1

if ! command -v java >/dev/null 2>&1; then
    echo "Java 17 or newer is required to run LuminaRead."
    exit 1
fi

JAR_PATH="target/LuminaRead-2.0.0.jar"
if [ ! -f "$JAR_PATH" ]; then JAR_PATH="LuminaRead.jar"; fi
if [ ! -f "$JAR_PATH" ]; then
    echo "LuminaRead was not found. Build the project or place LuminaRead.jar next to this launcher."
    exit 1
fi

exec java -Xmx2g -Dfile.encoding=UTF-8 -jar "$JAR_PATH" "$@"
