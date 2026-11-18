#!/bin/sh
# Public CI checks both recipes with synthetic memory. Optionally read a private
# stock copy using the original shipped guard hashes; this never writes it.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO=$(CDPATH= cd -- "$HERE/../.." && pwd)
: "${HMGAPI_SDK:?set HMGAPI_SDK to the released HMG-API 1.1.0-beta.1 mod SDK}"
WIRELESS_MOD=${WIRELESS_MOD:-$HERE/fixtures/wireless-carplay-aa}
WIRELESS_RECIPE=${CEEV_WIRELESS_RECIPE:-$WIRELESS_MOD/recipes/aa-tbt-dbfe7bd2.recipe}
[ -f "$WIRELESS_RECIPE" ] || { echo 'The beta101 CEEV TBT recipe is required' >&2; exit 1; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PKG=com/google/android/projection/protocol
javac --release 8 -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" -d "$TMP" \
    "$REPO/gpm-ev/test/shim/android/util/Log.java" \
    "$REPO/hmgapi-mod/src/$PKG/NativeCheck.java" "$HERE/CeevNativeRecipeTest.java"
set -- "$REPO/hmgapi-mod/recipes/ev-sensor-dbfe7bd2.recipe" "$WIRELESS_RECIPE"
if [ -n "${CEEV_STOCK_LIBRARY:-}" ]; then
    [ -f "$CEEV_STOCK_LIBRARY" ] || { echo 'CEEV_STOCK_LIBRARY does not exist' >&2; exit 1; }
    set -- "$@" "$CEEV_STOCK_LIBRARY"
fi
java -cp "$TMP:$HMGAPI_SDK/lib/hmgapi-engine.jar" \
    com.google.android.projection.protocol.CeevNativeRecipeTest "$@"
