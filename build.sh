#!/data/data/com.termux/files/usr/bin/bash
set -e
ROOT=$(cd "$(dirname "$0")" && pwd)
MOD=$ROOT/module
OUT=$ROOT/out
TOOLS=$ROOT/tools

mkdir -p "$TOOLS"

# libxposed API 102 is compile-only; fetch its classes.jar if not present.
if [ ! -f "$TOOLS/api-102.jar" ]; then
  echo "Fetching libxposed API 102..."
  curl -fsSL "https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar" \
    -o "$TOOLS/api-102.aar"
  unzip -p "$TOOLS/api-102.aar" classes.jar > "$TOOLS/api-102.jar"
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex" "$OUT/apk"

# 1. resources + manifest
aapt2 compile --dir "$MOD/res" -o "$OUT/compiled_res.zip"
aapt2 link \
  -o "$OUT/apk/base.apk" \
  -I "$TOOLS/android-4.1.1.4.jar" \
  --manifest "$MOD/AndroidManifest.xml" \
  -R "$OUT/compiled_res.zip" \
  --auto-add-overlay \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 37

# 2. java
javac -source 8 -target 8 -encoding UTF-8 \
  -cp "$TOOLS/api-102.jar:$TOOLS/android-4.1.1.4.jar" \
  -d "$OUT/obj" \
  $(find "$MOD/src" "$OUT/gen" -name '*.java')

# 3. dex
java -cp "$TOOLS/r8.jar" com.android.tools.r8.D8 \
  --min-api 26 \
  --lib "$TOOLS/android-4.1.1.4.jar" \
  --output "$OUT/dex" \
  $(find "$OUT/obj" -name '*.class')

# 4. pack
python3 - "$OUT" "$MOD" <<'PY'
import sys, zipfile, os
out, mod = sys.argv[1], sys.argv[2]
src = os.path.join(out, 'apk', 'base.apk')
dst = os.path.join(out, 'unsigned.apk')
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(os.path.join(out, 'dex', 'classes.dex'), 'classes.dex')
    # Modern libxposed API 102 entry files.
    meta = os.path.join(mod, 'meta-inf', 'META-INF', 'xposed')
    for name in ('java_init.list', 'module.prop', 'scope.list'):
        zout.write(os.path.join(meta, name), 'META-INF/xposed/' + name)
PY

zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# 5. sign
if [ ! -f "$TOOLS/debug.jks" ]; then
  keytool -genkeypair -v -keystore "$TOOLS/debug.jks" \
    -alias pswitch -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=PSwitch, OU=Modding, O=PSwitch, L=NA, S=NA, C=NA" >/dev/null 2>&1
fi
cp "$OUT/aligned.apk" "$ROOT/PSwitch.apk"
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore "$TOOLS/debug.jks" -storepass android -keypass android \
  "$ROOT/PSwitch.apk" pswitch >/dev/null 2>&1

ls -lh "$ROOT/PSwitch.apk"
echo "BUILD OK"
