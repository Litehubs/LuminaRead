#!/usr/bin/env bash
# ============================================================
#  LuminaRead Launcher for Linux (Ubuntu / Debian / Fedora / Arch)
#  Requires Java 17 or newer (OpenJDK / Temurin)
# ============================================================
set -e

# Resolve absolute directory of this script
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

show_error_dialog() {
    local msg="$1"
    if command -v zenity >/dev/null 2>&1; then
        zenity --error --title="LuminaRead - Java Required" --text="$msg" 2>/dev/null || true
    elif command -v kdialog >/dev/null 2>&1; then
        kdialog --error "$msg" --title "LuminaRead - Java Required" 2>/dev/null || true
    fi
}

# 1. Check for Java presence
if ! command -v java >/dev/null 2>&1; then
    echo "============================================================"
    echo " [ERROR] Java was not found on your system."
    echo " LuminaRead requires Java 17 or newer (LTS)."
    echo ""
    echo " To install Java on your distribution:"
    echo "   - Ubuntu / Debian:   sudo apt update && sudo apt install -y openjdk-17-jre"
    echo "   - Fedora / RHEL:     sudo dnf install -y java-17-openjdk"
    echo "   - Arch Linux:        sudo pacman -S jre-openjdk"
    echo "   - Or download from:  https://adoptium.net/"
    echo "============================================================"
    show_error_dialog "Java 17 or newer is required to run LuminaRead.\nPlease install it (e.g. 'sudo apt install openjdk-17-jre') or visit https://adoptium.net/"
    exit 1
fi

# 2. Check Java version >= 17
JAVA_SPEC_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ -n "$JAVA_SPEC_VER" ] && [ "$JAVA_SPEC_VER" -lt 17 ] 2>/dev/null; then
    echo "[WARNING] Detected Java version: $JAVA_SPEC_VER. LuminaRead recommends Java 17 or newer."
fi

# 3. Check for LuminaRead.jar
JAR_PATH="$SCRIPT_DIR/LuminaRead.jar"
if [ ! -f "$JAR_PATH" ]; then
    echo "============================================================"
    echo " [ERROR] LuminaRead.jar was not found in: $SCRIPT_DIR"
    echo " Please make sure LuminaRead.jar is in the same directory as this script."
    echo "============================================================"
    show_error_dialog "LuminaRead.jar was not found in: $SCRIPT_DIR"
    exit 1
fi

# 4. Desktop environment & typography friendly JVM flags
JAVA_OPTS="-Dawt.useSystemAAFontSettings=on -Dswing.aatext=true -Dfile.encoding=UTF-8 -Dsun.java2d.opengl=false"

# 5. Launch application
exec java $JAVA_OPTS -jar "$JAR_PATH" "$@"
