#!/data/data/com.termux/files/usr/bin/bash
#
# Prepares the compiler assets required by KotlinCompilerApp:
#   - kotlin-compiler-dex.jar  (kotlinc, dexed so it runs on ART)
#   - kotlin-stdlib.jar
#
# Does NOT fetch android.jar — that needs an Android SDK; see the
# printed note at the end.
#
# Usage:
#   bash setup-compiler-assets.sh [path to KotlinCompilerApp checkout]
#
# Default target: ~/KotlinCompilerApp

set -uo pipefail

KOTLIN_VERSION="1.9.24"
WORK_DIR="$HOME/kotlin-build"
APP_DIR="${1:-$HOME/KotlinCompilerApp}"
ASSETS_DIR="$APP_DIR/app/src/main/assets/tools"

COMPILER_JAR="kotlin-compiler-embeddable-${KOTLIN_VERSION}.jar"
STDLIB_JAR="kotlin-stdlib-${KOTLIN_VERSION}.jar"
COMPILER_URL="https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/${KOTLIN_VERSION}/${COMPILER_JAR}"
STDLIB_URL="https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/${KOTLIN_VERSION}/${STDLIB_JAR}"

log()  { echo -e "\033[1;34m[setup]\033[0m $*"; }
fail() { echo -e "\033[1;31m[error]\033[0m $*" >&2; exit 1; }

# --- 0. sanity checks -------------------------------------------------

[ -d "$APP_DIR" ] || fail "Project dir not found: $APP_DIR (pass it as an argument if it's elsewhere)"
mkdir -p "$WORK_DIR" "$ASSETS_DIR"
cd "$WORK_DIR" || fail "Could not cd into $WORK_DIR"

command -v curl >/dev/null 2>&1 || { log "curl not found, installing…"; pkg install curl -y || fail "pkg install curl failed"; }
command -v d8   >/dev/null 2>&1 || { log "d8 not found, installing android-tools…"; pkg install android-tools -y || fail "pkg install android-tools failed — d8 may not be packaged for this device, see README fallback"; }

# --- 1. download helper with verification ------------------------------

download() {
    local url="$1" dest="$2" tries=3
    if [ -s "$dest" ]; then
        log "Already have $dest, skipping download."
        return 0
    fi
    for ((i=1; i<=tries; i++)); do
        log "Downloading $dest (attempt $i/$tries)…"
        curl -L --fail --progress-bar -o "$dest" "$url" && [ -s "$dest" ] && return 0
        log "Attempt $i failed, retrying…"
        rm -f "$dest"
        sleep 2
    done
    fail "Could not download $dest after $tries attempts. Check network connectivity (try opening $url in a browser)."
}

download "$COMPILER_URL" "$COMPILER_JAR"
download "$STDLIB_URL" "$STDLIB_JAR"

# --- 2. dex the compiler -------------------------------------------------

log "Dexing $COMPILER_JAR with d8 (this can take a minute)…"
rm -rf out_dex
mkdir -p out_dex
d8 --min-api 26 --output out_dex/ "$COMPILER_JAR" || fail "d8 failed to dex the compiler jar"

[ -f out_dex/classes.dex ] || fail "d8 ran but out_dex/classes.dex was not produced"

# --- 3. copy into the app's assets folder --------------------------------

cp out_dex/classes.dex "$ASSETS_DIR/kotlin-compiler-dex.jar"
cp "$STDLIB_JAR" "$ASSETS_DIR/kotlin-stdlib.jar"

log "Done. Placed in $ASSETS_DIR:"
ls -la "$ASSETS_DIR"

echo
log "Still needed: android.jar (from an Android SDK's platforms/android-34/)."
log "This script can't fetch it — it isn't published as a plain download;"
log "it comes bundled with Android Studio's SDK Manager or the"
log "commandlinetools package. If you don't have an SDK anywhere, tell"
log "Claude and it'll walk you through pulling it via sdkmanager in Termux."
