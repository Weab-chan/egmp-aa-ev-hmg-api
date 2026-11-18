# Source after setting REPO to this checkout. No HMG-API source checkout is used.
HMGAPI_SDK=${HMGAPI_SDK:?set HMGAPI_SDK to the released HMG-API 1.1.0-beta.1 mod SDK}
HMGAPI_SDK=$(cd "$HMGAPI_SDK" && pwd)
python3 "$REPO/tools/verify-sdk.py" "$HMGAPI_SDK"
[ "$(cat "$HMGAPI_SDK/VERSION")" = "1.1.0-beta.1" ] || { echo "HMG-API SDK 1.1.0-beta.1 required" >&2; exit 1; }
for sdk_jar in hmgapi-engine hmgapi-runtime hmgapi-runtime-stubs hmgapi-client; do
    [ -f "$HMGAPI_SDK/lib/$sdk_jar.jar" ] || { echo "SDK is missing $sdk_jar.jar" >&2; exit 1; }
done
. "$HMGAPI_SDK/bin/env.sh"
HMGAPI_ENGINE=$HMGAPI_SDK/lib/hmgapi-engine.jar
HMGAPI_RUNTIME=$HMGAPI_SDK/lib/hmgapi-runtime.jar
HMGAPI_STUBS=$HMGAPI_SDK/lib/hmgapi-runtime-stubs.jar
HMGAPI_CLIENT=$HMGAPI_SDK/lib/hmgapi-client.jar
