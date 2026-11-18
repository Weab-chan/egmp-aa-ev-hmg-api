#!/bin/sh
# Released SDK Engine plan/apply for both Canadian CEEV/CVEV 260206 maps and
# Wireless beta101 declarations. Only temporary host files are changed. See
# fixtures/README.md: stand-ins are not executable firmware or hook payloads.
set -e
cd "$(dirname "$0")/../.."
REPO=$PWD
. "$REPO/hmgapi-mod/tools/sdk-env.sh"
MOD=$REPO/build/mod/egmp-aa-ev
FIX=$REPO/hmgapi-mod/test/fixtures
[ -f "$MOD/hooks.jar" ] || { echo "build the mod first: sh hmgapi-mod/build.sh"; exit 1; }
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail=0
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in:"; echo "$3"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: unexpected [$2] in:"; echo "$3"; fail=1 ;; *) echo "ok    $1" ;; esac; }
check_rc() { [ "$rc" = "$2" ] && echo "ok    $1" || { echo "FAIL  $1: exit $rc (wanted $2): $out"; fail=1; }; }
eng() { set +e; out=$(HMGAPI_ROOT="$R" HMGAPI_HOST=1 HMGAPI_PRODUCT="$P" HMGAPI_ONEBIN=fixture java -cp "$HMGAPI_ENGINE" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
for MODEL in CEEV CVEV; do
case "$MODEL" in
    CEEV) MAP=hyundai/ceev/can/260206; FIREWALL=true ;;
    CVEV) MAP=kia/ev6/can/260206; FIREWALL=false ;;
esac
R=$T/$MODEL/unit
CASE_DIR=$T/$MODEL
P=$MODEL.CAN.0000.V015.011.260206
echo "Testing $P (wireless firewall $FIREWALL)"
mkdir -p "$R/data/hmgapi/mods" "$R/data/hmgapi/settings" "$R/data/hmgapi/public" \
    "$R/system/onebin/fixture" "$R/system/etc/permissions"
printf 'ro.build.product=%s\nro.build.version.release=4.4.2\n' "$P" > "$R/system/build.prop"
touch "$R/data/hmgapi/public/wifi-chip"
printf '%s\n' '-/system/etc/permissions/com.hkmc.software.wifihotspot.xml' \
    '-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml' \
    '-/system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml' \
    > "$R/system/onebin/fixture/featurexml.cfg"
cp -R "$FIX/maps" "$R/data/hmgapi/maps"
cp "$HMGAPI_SDK/points.txt" "$R/data/hmgapi/maps/points.txt"
cp -R "$MOD" "$R/data/hmgapi/mods/egmp-aa-ev"
cp -R "$FIX/wireless-carplay-aa" "$R/data/hmgapi/mods/wireless-carplay-aa"
printf 'HOST FIXTURE ONLY — NOT EXECUTABLE\n' > "$R/data/hmgapi/mods/wireless-carplay-aa/hooks.jar"
printf '{"aa_firewall":%s,"tbt":true,"tbt_standard":false}\n' "$FIREWALL" > "$R/data/hmgapi/settings/wireless-carplay-aa.json"
cp "$R/data/hmgapi/settings/wireless-carplay-aa.json" "$CASE_DIR/wireless-settings.json"
python3 - "$R" "$MOD" "$MAP" "$P" <<'PY'
import hashlib, json, pathlib, sys
root=pathlib.Path(sys.argv[1]); mod=json.loads((pathlib.Path(sys.argv[2])/'mod.json').read_text())
carfile=root/'data/hmgapi/maps'/sys.argv[3]/'car.json'
car=json.loads(carfile.read_text())
for name in car['files']:
    target=root/name.lstrip('/'); target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text('HOST STAND-IN '+name+'\n')
    car['files'][name]=hashlib.md5(target.read_bytes()).hexdigest()
carfile.write_text(json.dumps(car,indent=2)+'\n')
# The published EV6 map has not been stamped globally verified. Model the
# owner's per-unit map-check receipt with this temporary root's stand-in hashes.
# This is fabricated HOST TEST STATE, never a real verification or shipped file.
state=root/'data/hmgapi/state'; state.mkdir(parents=True,exist_ok=True)
(state/'map-check.json').write_text(json.dumps({
 'ok':True,'folder':sys.argv[3],'files':car['files'],'host_fixture_only':True})+'\n')
have={}
for path in carfile.parent.glob('*.json'):
    if path.name!='car.json': have.update(json.loads(path.read_text()))
want=list(mod['hooks']['points'])+[
 'framework.car_services.hev','framework.car_services.bcm','framework.car_services.datc',
 'framework.hscan_adapter.service_name','framework.hev_service_stub.as_interface',
 'framework.hscan_service_stub.as_interface','framework.datc_service_stub.as_interface',
 'framework.bcm_service_stub.as_interface','framework.hev_service.hev_status','framework.hev_flag',
 'framework.hev_flag.battery_conditioning','framework.hev_flag.ev_ready',
 'framework.hscan_service.car_speed','framework.hscan_service.hv_health',
 'framework.bcm_service.register_listener','framework.bcm_listener','framework.bcm_listener_stub',
 'framework.bcm_listener_stub.as_interface','framework.runtime.load_library','framework.runtime.load']
missing=[p for p in want if p not in have or p in car.get('missing',{})]
assert not missing, missing
print('ok    %s: all %d EV hook and indirect point declarations mapped'%(sys.argv[4],len(want)))
PY
eng plan
check_rc 'both mods plan with released SDK' 0
has 'shared next-turn callback is planned' 'gpm.navigation_status.next_turn:' "$out"
has 'shared navigation status callback is planned' 'gpm.navigation_status.status:' "$out"
has 'EV relay service is planned' 'relay' "$out"
has 'EV offload service is planned' 'offload' "$out"
has 'conditioning command is declared' 'conditioning' "$out"
# Plan describes conditional declarations even when the setting is false.
# Inspect published net.rules after Apply to test which rules actually settle.
has 'wireless firewall declaration remains conditional' '(when settings.aa_firewall)' "$out"
hasnt 'no hook refusal' "won't load" "$out"
printf '%s\n' "$out" > "$CASE_DIR/plan.txt"
eng apply
check_rc 'both mods apply in host fixture' 0
has 'both mods remain enabled' 'state published: 2 of 2 mods on' "$out"
cmp "$CASE_DIR/wireless-settings.json" "$R/data/hmgapi/settings/wireless-carplay-aa.json"
python3 - "$R" "$FIREWALL" <<'PYTEST'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1]);state=json.loads((root/'data/hmgapi/public/state.json').read_text())
mods=state['mods']
assert mods['egmp-aa-ev']['enabled'] is True
assert mods['wireless-carplay-aa']['enabled'] is True
assert mods['egmp-aa-ev']['settings']['preconditioning'] is False
assert mods['wireless-carplay-aa']['settings']['aa_firewall'] is (sys.argv[2]=='true')
assert mods['wireless-carplay-aa']['settings']['tbt'] is True
assert mods['wireless-carplay-aa']['settings']['tbt_standard'] is False
print('ok    published state keeps Wireless settings and EV preconditioning off')
files=json.loads((root/'data/hmgapi/state/files.json').read_text())
assert len(files['files']) == 3, files
assert all(entry.get('mods') == ['wireless-carplay-aa'] for entry in files['files'].values()), files
print('ok    EV add-on owns no system file changes')
rules=root/'data/hmgapi/state/net.rules'
network=rules.read_text() if rules.exists() else ''
assert ('40515' in network)==(sys.argv[2]=='true'), network
print('ok    published network rules preserve this car\'s firewall baseline')
PYTEST
# Empty patches.json opts the hook-only EV add-on into the released engine's
# dependency/version checks. It declares no filesystem target. Without it, the
# SDK silently skips the EV mod's version range in plan/apply.
# The dependency must remain enabled and be at least the working beta101.
touch "$R/data/hmgapi/mods/wireless-carplay-aa/.disabled"
eng plan
check_rc 'disabled Wireless refuses plan' 3
has 'disabled dependency explanation' "isn't installed and enabled" "$out"
rm "$R/data/hmgapi/mods/wireless-carplay-aa/.disabled"
cp "$R/data/hmgapi/mods/wireless-carplay-aa/mod.json" "$CASE_DIR/wireless.json"
python3 - "$R/data/hmgapi/mods/wireless-carplay-aa/mod.json" <<'PY'
import json,sys
p=sys.argv[1];m=json.load(open(p));m['version']='2.2.0-beta.1';open(p,'w').write(json.dumps(m))
PY
eng plan
check_rc 'old Wireless refuses plan' 3
has 'old dependency explanation' 'but wireless-carplay-aa 2.2.0-beta.1 is installed' "$out"
cp "$CASE_DIR/wireless.json" "$R/data/hmgapi/mods/wireless-carplay-aa/mod.json"
mv "$R/data/hmgapi/mods/wireless-carplay-aa" "$CASE_DIR/wireless-mod"
eng plan
check_rc 'missing Wireless refuses plan' 3
has 'missing dependency explanation' "isn't installed and enabled" "$out"
mv "$CASE_DIR/wireless-mod" "$R/data/hmgapi/mods/wireless-carplay-aa"
# Disabling EV leaves the known-working Wireless feature flags and firewall.
touch "$R/data/hmgapi/mods/egmp-aa-ev/.disabled"
eng apply
check_rc 'EV can be disabled without removing Wireless' 0
has 'Wireless remains enabled' 'state published: 1 of 2 mods on' "$out"
cmp "$CASE_DIR/wireless-settings.json" "$R/data/hmgapi/settings/wireless-carplay-aa.json"
python3 - "$R" "$FIREWALL" <<'PYDISABLED'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1]);state=json.loads((root/'data/hmgapi/public/state.json').read_text())
assert state['mods']['egmp-aa-ev']['enabled'] is False
wireless=state['mods']['wireless-carplay-aa']
assert wireless['enabled'] is True
assert wireless['settings']['aa_firewall'] is (sys.argv[2]=='true')
assert wireless['settings']['tbt'] is True
assert wireless['settings']['tbt_standard'] is False
files=json.loads((root/'data/hmgapi/state/files.json').read_text())
assert len(files['files'])==3
assert all(entry.get('mods')==['wireless-carplay-aa'] for entry in files['files'].values())
rules=root/'data/hmgapi/state/net.rules'
network=rules.read_text() if rules.exists() else ''
assert ('40515' in network)==(sys.argv[2]=='true'), network
print('ok    disabling EV preserves Wireless settings and feature files')
PYDISABLED
eng plan
check_rc 'Wireless-only plan remains valid' 0
has 'Wireless firewall declaration still conditional' '(when settings.aa_firewall)' "$out"
hasnt 'EV relay no longer planned' 'egmp-aa-ev/relay' "$out"
# Without this car's separately installed core map, hooks must fail closed.
rm "$R/data/hmgapi/mods/egmp-aa-ev/.disabled"
mv "$R/data/hmgapi/maps/$MAP" "$CASE_DIR/absent-map"
eng plan
has 'missing core map keeps EV hooks off' "egmp-aa-ev won't load" "$out"
done
exit "$fail"
