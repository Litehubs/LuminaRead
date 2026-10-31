#!/bin/bash
cd "$(dirname "$0")"
mkdir -p bin
for jar in lib/*.jar; do
    [ -f "$jar" ] && unzip -o -q "$jar" -d bin 2>/dev/null
done
find src/main/java -name "*.java" > sources.txt
javac -encoding UTF-8 -cp "bin:lib/*" -d bin @sources.txt
jar cfe LuminaRead.jar com.luminaread.LuminaReadApp -C bin .
java -Xmx2g -Dfile.encoding=UTF-8 -cp "bin:lib/*" com.luminaread.LuminaReadApp
