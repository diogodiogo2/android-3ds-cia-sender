#!/usr/bin/env bash
# Builds cia-sender.apk, signed with a throw-away debug key.
#
# Works with the official Android SDK (set ANDROID_HOME) and with the android-sdk
# packages of Debian/Ubuntu. You can also point directly at an android.jar:
#   ANDROID_JAR=/path/to/android.jar ./build.sh
#
# Debian/Ubuntu packages: android-sdk aapt zipalign apksigner dalvik-exchange
#                         default-jdk-headless zip
set -euo pipefail
cd "$(dirname "$0")"

# ---------- locate the SDK ----------
SDK=""
for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /usr/lib/android-sdk "$HOME/Android/Sdk" "$HOME/android-sdk"; do
  if [ -n "$c" ] && [ -d "$c" ]; then SDK="$c"; break; fi
done
echo "SDK: ${SDK:-<none; using PATH only>}"

# Finds a build tool: build-tools (newest first), then PATH.
find_tool() {
  local name="$1" d
  if [ -n "$SDK" ]; then
    for d in $(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -rV); do
      [ -x "$d/$name" ] && { echo "$d/$name"; return 0; }
    done
  fi
  command -v "$name" 2>/dev/null && return 0
  return 1
}

# ---------- locate android.jar (API >= 23, the lowest one available) ----------
JAR="${ANDROID_JAR:-}"
if [ -z "$JAR" ] && [ -n "$SDK" ]; then
  for api in $(ls -d "$SDK"/platforms/android-* 2>/dev/null | sed 's/.*android-//' | grep -E '^[0-9]+$' | awk '$1>=23' | sort -n); do
    [ -f "$SDK/platforms/android-$api/android.jar" ] && { JAR="$SDK/platforms/android-$api/android.jar"; break; }
  done
fi
if [ -z "$JAR" ]; then
  for f in $(ls /usr/share/java/android-*.jar /usr/lib/android-sdk/platforms/android-*/android.jar 2>/dev/null | sort -V); do
    JAR="$f"; break
  done
fi
[ -n "$JAR" ] || { echo "android.jar not found. Install a platform with the official sdkmanager (platforms;android-28) and set ANDROID_HOME, or run: ANDROID_JAR=/path/to/android.jar ./build.sh"; exit 1; }
[ -f "$JAR" ] || { echo "ANDROID_JAR does not exist: $JAR"; exit 1; }
echo "android.jar: $JAR"

# ---------- tools ----------
AAPT2="$(find_tool aapt2 || true)"
AAPT="$(find_tool aapt || true)"
D8="$(find_tool d8 || true)"
DX="$(find_tool dx || true)"
ZIPALIGN="$(find_tool zipalign || true)"
APKSIGNER="$(find_tool apksigner || true)"
[ -n "$AAPT2$AAPT" ] || { echo "Missing aapt/aapt2 (Debian: sudo apt install aapt)"; exit 1; }
[ -n "$D8$DX" ]      || { echo "Missing d8 or dx (Debian: sudo apt install dalvik-exchange)"; exit 1; }
[ -n "$ZIPALIGN" ]   || { echo "Missing zipalign (Debian: sudo apt install zipalign)"; exit 1; }
[ -n "$APKSIGNER" ]  || { echo "Missing apksigner (Debian: sudo apt install apksigner)"; exit 1; }
command -v javac >/dev/null || { echo "Missing JDK (Debian: sudo apt install default-jdk-headless)"; exit 1; }
command -v zip   >/dev/null || { echo "Missing zip (Debian: sudo apt install zip)"; exit 1; }
echo "aapt: ${AAPT2:-$AAPT} | dex: ${D8:-$DX} | zipalign: $ZIPALIGN | apksigner: $APKSIGNER"

rm -rf out && mkdir -p out/classes

# ---------- manifest ----------
if [ -n "$AAPT2" ]; then
  "$AAPT2" link --manifest AndroidManifest.xml -I "$JAR" \
    --min-sdk-version 9 --target-sdk-version 28 -o out/base.apk
else
  "$AAPT" package -f -M AndroidManifest.xml -I "$JAR" -F out/base.apk
fi

# ---------- compile Java ----------
# -parameters: javac from JDK 21 writes unnamed parameters into anonymous classes,
# which makes d8 8.2.x crash with a NullPointerException.
javac -encoding UTF-8 -parameters -source 8 -target 8 -Xlint:-options -bootclasspath "$JAR" -d out/classes \
  src/br/local/ciasender/*.java

# ---------- convert to dex ----------
if [ -n "$D8" ]; then
  "$D8" --release --min-api 9 --lib "$JAR" --output out $(find out/classes -name '*.class')
else
  "$DX" --dex --min-sdk-version=9 --output=out/classes.dex out/classes
fi
[ -f out/classes.dex ] || { echo "classes.dex was not generated"; exit 1; }

( cd out && zip -q -u base.apk classes.dex )
"$ZIPALIGN" -f 4 out/base.apk out/aligned.apk

# ---------- sign ----------
[ -f out/debug.keystore ] || keytool -genkeypair -keystore out/debug.keystore \
  -storepass android -keypass android -alias debug -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=CIA Sender" >/dev/null 2>&1

"$APKSIGNER" sign --ks out/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out cia-sender.apk out/aligned.apk
echo "Done: $(pwd)/cia-sender.apk"
