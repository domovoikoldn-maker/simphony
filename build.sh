#!/usr/bin/env bash
# =============================================================================
#  build.sh — сборка APK «Симфонии» без Gradle и БЕЗ рекламы (только Android SDK).
#  Версия с рекламой Яндекса собирается Gradle'ом (Android Studio), см. README.md.
#  Разработчик и владелец: domovoi4ik
#
#  Пути — из переменных окружения или ANDROID_HOME:
#     ANDROID_JAR, AAPT2, ZIPALIGN, D8 (или D8_JAR), APKSIGNER (или APKSIGNER_JAR)
#  Ключ подписи: signing/symphony-release.jks (пароль — signing/README.txt или KS_PASS)
#  Результат: dist/Simfoniya-<версия>.apk;  DEMO_ADS=1 ./build.sh — с демо-рекламой (заглушки)
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
VERSION_NAME="${VERSION_NAME:-2.0.0}"
VERSION_CODE="${VERSION_CODE:-2}"
MIN_SDK=24
TARGET_SDK=36

if [[ -n "${ANDROID_HOME:-}" ]]; then
    BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
    : "${ANDROID_JAR:=$ANDROID_HOME/platforms/android-$TARGET_SDK/android.jar}"
    : "${AAPT2:=$BT/aapt2}"; : "${ZIPALIGN:=$BT/zipalign}"; : "${D8:=$BT/d8}"; : "${APKSIGNER:=$BT/apksigner}"
fi
: "${ANDROID_JAR:?укажите ANDROID_JAR или ANDROID_HOME}"
run_d8() { if [[ -n "${D8_JAR:-}" ]]; then java -cp "$D8_JAR" com.android.tools.r8.D8 "$@"; else "$D8" "$@"; fi; }
run_apksigner() { if [[ -n "${APKSIGNER_JAR:-}" ]]; then java -jar "$APKSIGNER_JAR" "$@"; else "$APKSIGNER" "$@"; fi; }
KEYSTORE="$ROOT/signing/symphony-release.jks"
KS_PASS="${KS_PASS:-$(sed -n 's/^storepass=//p' "$ROOT/signing/README.txt" 2>/dev/null || true)}"

B="$ROOT/build/apk"
rm -rf "$B"; mkdir -p "$B"/{gen,classes,dex} "$ROOT/dist"

echo "== 1. Ресурсы (aapt2) → R.java"
"$AAPT2" compile --dir "$ROOT/android/res" -o "$B/res.zip"
"$AAPT2" link -o "$B/base.apk" -I "$ANDROID_JAR" --manifest "$ROOT/android/AndroidManifest.xml" \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$B/gen" "$B/res.zip"

echo "== 2. Java → байткод (без папки ads/: SDK Яндекса только в Gradle-сборке)"
SRC_DIRS=("$ROOT/src" "$B/gen")
if [[ "${DEMO_ADS:-0}" == "1" ]]; then SRC_DIRS+=("$ROOT/demo/src"); echo "   + демо-реклама (заглушки вместо SDK Яндекса)"; fi
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$B/classes" \
    $(find "${SRC_DIRS[@]}" -name '*.java') 2>&1 | grep -v -E "^warning|bootstrap|^1 warning|options|^Note" || true
test -f "$B/classes/com/domovoi4ik/symphony/MainActivity.class" || { echo "ошибка компиляции" >&2; exit 1; }

echo "== 3. DEX"
run_d8 --release --min-api $MIN_SDK --output "$B/dex" $(find "$B/classes" -name '*.class')

echo "== 4. Упаковка и подпись"
cp "$B/base.apk" "$B/unaligned.apk"
(cd "$B/dex" && zip -q -X "$B/unaligned.apk" classes.dex)
"$ZIPALIGN" -p -f 4 "$B/unaligned.apk" "$B/aligned.apk"
SUFFIX=""; if [[ "${DEMO_ADS:-0}" == "1" ]]; then SUFFIX="-demo-ads"; fi
OUT="$ROOT/dist/Simfoniya-$VERSION_NAME$SUFFIX.apk"
run_apksigner sign --ks "$KEYSTORE" --ks-key-alias symphony --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --min-sdk-version $MIN_SDK --v2-signing-enabled true --v3-signing-enabled true --out "$OUT" "$B/aligned.apk"
run_apksigner verify --min-sdk-version $MIN_SDK "$OUT"
echo "Готово: $OUT ($(du -h "$OUT" | cut -f1))"
