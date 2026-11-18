#!/bin/sh
# Exercise EV6 EV/TBT recipes using synthetic memory, and optionally a private
# exact-stock library with shipped guard hashes. The stock file is read only.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO=$(CDPATH= cd -- "$HERE/../.." && pwd)
: "${HMGAPI_SDK:?set HMGAPI_SDK to the released HMG-API 1.1.0-beta.1 mod SDK}"
python3 "$REPO/tools/verify-sdk.py" "$HMGAPI_SDK"
WIRELESS_MOD=${WIRELESS_MOD:-$HERE/fixtures/wireless-carplay-aa}
WIRELESS_RECIPE=${EV6_WIRELESS_RECIPE:-$WIRELESS_MOD/recipes/aa-tbt-48ee793a.recipe}
[ -f "$WIRELESS_RECIPE" ] || { echo 'The official EV6 48ee793a TBT recipe is required' >&2; exit 1; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PKG=com/google/android/projection/protocol
javac --release 8 -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" -d "$TMP" \
    "$REPO/gpm-ev/test/shim/android/util/Log.java" \
    "$REPO/hmgapi-mod/src/$PKG/NativeCheck.java" "$HERE/Ev6NativeRecipeTest.java"
set -- "$REPO/hmgapi-mod/recipes/ev-sensor-48ee793a.recipe" "$WIRELESS_RECIPE"
if [ -n "${EV6_STOCK_LIBRARY:-}" ]; then
    [ -f "$EV6_STOCK_LIBRARY" ] || { echo 'EV6_STOCK_LIBRARY does not exist' >&2; exit 1; }
    set -- "$@" "$EV6_STOCK_LIBRARY"
fi
java -cp "$TMP:$HMGAPI_SDK/lib/hmgapi-engine.jar" \
    com.google.android.projection.protocol.Ev6NativeRecipeTest "$@"
