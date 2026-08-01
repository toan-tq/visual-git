#!/bin/bash
#
# build-app.sh — Compile visual-git and package standalone apps
#   macOS:   .app bundles (GitLog.app, FolderCompare.app)
#   Windows: jpackage app-images with per-app .exe (dist/GitLog, dist/FolderCompare)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src/main/java/com/visualgit"
LIB="$ROOT/lib"
OUT="$ROOT/out"
DIST="$ROOT/dist"

# Prefer the bundled JDK when present (Windows checkout ships jdk-25.0.2/)
JAVAC=javac
JAR=jar
JPACKAGE=jpackage
if [ -x "$ROOT/jdk-25.0.2/bin/javac" ] || [ -x "$ROOT/jdk-25.0.2/bin/javac.exe" ]; then
    JAVAC="$ROOT/jdk-25.0.2/bin/javac"
    JAR="$ROOT/jdk-25.0.2/bin/jar"
    JPACKAGE="$ROOT/jdk-25.0.2/bin/jpackage"
fi

# ── 1. Compile ───────────────────────────────────────────────────────────────

echo "Compiling Java sources..."
rm -rf "$OUT"
mkdir -p "$OUT"
"$JAVAC" -cp "$LIB/swt.jar" -d "$OUT" \
    "$SRC/AppTheme.java" \
    "$SRC/SyntaxHighlighter.java" \
    "$SRC/FileCompare.java" \
    "$SRC/GitLog.java" \
    "$SRC/FolderCompare.java" \
    "$SRC/FolderDiff.java"
echo "  Compiled $(find "$OUT" -name '*.class' | wc -l | tr -d ' ') classes"

# ── Helper: build one macOS .app bundle ──────────────────────────────────────

build_app() {
    local APP_NAME="$1"       # e.g. FileExplorer
    local MAIN_CLASS="$2"     # e.g. com.visualgit.FileExplorer
    local ICON_SRC="$3"       # e.g. resources/app-icon.png
    local BUNDLE_ID="$4"      # e.g. com.visualgit.FileExplorer

    local APP="$DIST/$APP_NAME.app"
    local CONTENTS="$APP/Contents"

    echo ""
    echo "Building $APP_NAME.app..."

    # Convert PNG → ICNS
    local ICONSET="$DIST/${APP_NAME}.iconset"
    rm -rf "$ICONSET"
    mkdir -p "$ICONSET"
    for size in 16 32 128 256 512; do
        sips -z $size $size "$ICON_SRC" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null 2>&1
        local double=$((size * 2))
        sips -z $double $double "$ICON_SRC" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null 2>&1
    done
    local ICNS="$DIST/${APP_NAME}.icns"
    iconutil -c icns "$ICONSET" -o "$ICNS"
    rm -rf "$ICONSET"

    # Create .app bundle
    rm -rf "$APP"
    mkdir -p "$CONTENTS/MacOS"
    mkdir -p "$CONTENTS/Resources/Java/out"
    mkdir -p "$CONTENTS/Resources/Java/lib"

    # Info.plist
    cat > "$CONTENTS/Info.plist" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key>
    <string>$APP_NAME</string>
    <key>CFBundleDisplayName</key>
    <string>$APP_NAME</string>
    <key>CFBundleIdentifier</key>
    <string>$BUNDLE_ID</string>
    <key>CFBundleVersion</key>
    <string>1.0</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleExecutable</key>
    <string>$APP_NAME</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>LSMultipleInstancesProhibited</key>
    <false/>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSSupportsAutomaticGraphicsSwitching</key>
    <true/>
</dict>
</plist>
PLIST

    # Native launcher
    clang -O2 -DMAIN_CLASS="\"$MAIN_CLASS\"" \
        -o "$CONTENTS/MacOS/$APP_NAME" "$ROOT/resources/launcher.c" \
        -framework CoreFoundation

    # Copy resources
    cp "$ICNS" "$CONTENTS/Resources/AppIcon.icns"
    cp -R "$OUT/com" "$CONTENTS/Resources/Java/out/com"
    cp "$LIB/swt.jar" "$CONTENTS/Resources/Java/lib/swt.jar"
    rm -f "$ICNS"

    local APP_SIZE
    APP_SIZE=$(du -sh "$APP" | cut -f1)
    echo "  Created $APP ($APP_SIZE)"
}

# ── Helper: build one Windows app-image (.exe) via jpackage ──────────────────

build_exe() {
    local APP_NAME="$1"       # e.g. GitLog
    local MAIN_CLASS="$2"     # e.g. com.visualgit.GitLog

    echo ""
    echo "Building $APP_NAME.exe (jpackage app-image)..."
    if ! rm -rf "$DIST/$APP_NAME" 2>/dev/null; then
        echo "  ERROR: cannot remove $DIST/$APP_NAME — files are locked." >&2
        echo "  Is $APP_NAME.exe still running? Close it and re-run this script." >&2
        exit 1
    fi

    local ICON_ARGS=()
    if [ -f "$ROOT/gitlog-proper.ico" ]; then
        ICON_ARGS=(--icon "$ROOT/gitlog-proper.ico")
    fi

    "$JPACKAGE" --type app-image \
        --name "$APP_NAME" \
        --input "$DIST/input" \
        --main-jar visual-git.jar \
        --main-class "$MAIN_CLASS" \
        --java-options "--enable-native-access=ALL-UNNAMED" \
        --dest "$DIST" \
        "${ICON_ARGS[@]}"

    local APP_SIZE
    APP_SIZE=$(du -sh "$DIST/$APP_NAME" | cut -f1)
    echo "  Created $DIST/$APP_NAME ($APP_SIZE) — run $DIST/$APP_NAME/$APP_NAME.exe"
}

# ── 2. Build apps ────────────────────────────────────────────────────────────

case "$(uname -s)" in
    Darwin)
        build_app "GitLog" "com.visualgit.GitLog" \
            "$ROOT/resources/gitlog-icon.png" "com.visualgit.GitLog"
        build_app "FolderCompare" "com.visualgit.FolderCompare" \
            "$ROOT/resources/gitlog-icon.png" "com.visualgit.FolderCompare"

        echo ""
        echo "Done!"
        echo ""
        echo "To install:"
        echo "  cp -R dist/GitLog.app dist/FolderCompare.app /Applications/"
        echo ""
        echo "To test:"
        echo "  open dist/GitLog.app"
        ;;

    MINGW*|MSYS*|CYGWIN*)
        # Shared jpackage input: application jar + SWT + runtime resources
        echo ""
        echo "Preparing jpackage input..."
        rm -rf "$DIST/input"
        mkdir -p "$DIST/input/resources"
        "$JAR" cf "$DIST/input/visual-git.jar" -C "$OUT" .
        cp "$LIB/swt.jar" "$DIST/input/"
        cp "$ROOT/resources/gitlog-icon.png" "$DIST/input/resources/"

        build_exe "GitLog" "com.visualgit.GitLog"
        build_exe "FolderCompare" "com.visualgit.FolderCompare"

        echo ""
        echo "Done!"
        echo ""
        echo "To test:"
        echo "  ./dist/GitLog/GitLog.exe"
        echo "  ./dist/FolderCompare/FolderCompare.exe"
        ;;

    *)
        echo "Unsupported platform: $(uname -s)" >&2
        exit 1
        ;;
esac
