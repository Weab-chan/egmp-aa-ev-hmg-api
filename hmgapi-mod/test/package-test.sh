#!/bin/sh
# The source manifest through HMG-API's actual signed-package install gate and
# NEMESIS packager. Only manifest/settings metadata is packaged with a disposable
# test key: this tests admission/update behavior, not executable car payloads.
# Requires a JDK, python3, zip/unzip, and mknemesismod.sh's GNU touch (as in CI).
# No Android SDK or built mod is needed.
#   HMGAPI_SDK=<released SDK> sh hmgapi-mod/test/package-test.sh
set -e
cd "$(dirname "$0")/../.."
REPO=$PWD
. "$REPO/hmgapi-mod/tools/sdk-env.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/mod"
cp hmgapi-mod/mod.json "$T/mod/mod.json"
cp hmgapi-mod/settings.json "$T/mod/settings.json"
java -cp "$HMGAPI_ENGINE" hmgapi.engine.Signing keygen "$T/test.key" >/dev/null
java -cp "$HMGAPI_ENGINE" hmgapi.engine.Signing sign "$T/mod" "$T/test.key" >/dev/null
python3 - "$T" <<'PY'
from pathlib import Path
import sys, zipfile
root = Path(sys.argv[1])
with zipfile.ZipFile(root / 'mod.zip', 'w') as z:
    for f in sorted((root / 'mod').rglob('*')):
        if f.is_file():
            z.write(f, f.relative_to(root / 'mod'))
PY

# Exercise install (plan alone never checks the package's system requirements).
# Dependencies are not required at install time, so no Wireless payload is needed.
check() { # product, expected exit, expected explanation (when refused)
    product=$1 want=$2 reason=${3:-}
    root=$T/units/$product
    mkdir -p "$root/data/hmgapi"
    rc=0
    HMGAPI_ROOT="$root" HMGAPI_HOST=1 HMGAPI_PRODUCT="$product" \
        java -cp "$HMGAPI_ENGINE" hmgapi.engine.Engine install "$T/mod.zip" --from-nemesis >"$T/result" 2>&1 || rc=$?
    if [ "$rc" != "$want" ]; then
        echo "FAIL  $product: install exit $rc, expected $want"
        cat "$T/result"
        exit 1
    fi
    if [ "$want" = 0 ]; then
        cmp "$T/mod/mod.json" "$root/data/hmgapi/mods/egmp-aa-ev/mod.json"
    else
        [ ! -d "$root/data/hmgapi/mods/egmp-aa-ev" ]
        grep -F "$reason" "$T/result" >/dev/null || { cat "$T/result"; exit 1; }
    fi
    echo "ok    $product: install exit $rc"
}
# Admit only the two examined Canadian 260206 models.
check CVEV.CAN.0000.V015.011.260206 0
check CVEV.CAN.0000.V014.005.250101 3 'needs firmware 260206'
check CVEV.CAN.0000.V016.010.260903 3 'needs firmware 260206'
check CVEV.USA.0000.V015.012.260507 3 'needs region CAN'
check CVEV.USA.0000.V015.011.260206 3 'needs region CAN'
check CVEV.EUR.0000.V015.011.260206 3 'needs region CAN'
check NEEV.CAN.0000.V015.010.260206 3 'needs model'
check NEEV.USA.0000.V015.012.260507 3 'needs model'
check NEEV23.USA.0000.V015.012.260507 3 'needs model'
check NEEV23SE.USA.0000.V015.012.260507 3 'needs model'
check NEEV23.USA.0000.V016.010.260903 3 'needs model'
check NEEV23.USA.0000.V017.001.261201 3 'needs model'
check NEEV.USA.0000.V014.005.250101 3 'needs model'
check CEEV.USA.0000.V016.010.260903 3 'needs region CAN'
check CEEV.USA.0000.V017.001.261201 3 'needs region CAN'
check CEEV.CAN.0000.V014.010.250818 3 'needs firmware 260206'
check CEEV.CAN.0000.V015.011.260206 0
check CEEV.CAN.0000.V016.010.260903 3 'needs firmware 260206'
check CEEV.USA.0000.V014.010.250818 3 'needs region CAN'
check CEEV.USA.0000.V015.011.260206 3 'needs region CAN'
check CEEV.EUR.0000.V015.011.260206 3 'needs region CAN'

# Use the pinned released SDK packager. NEMESIS must explicitly advertise
# exactly the two Canadian model patterns and preserve the signed inner bytes.
sh "$HMGAPI_SDK/bin/mknemesismod.sh" "$T/mod.zip" "$T/dist" >"$T/packager.log"
python3 - "$T" <<'PY'
import configparser, fnmatch, json, pathlib, sys, zipfile
root = pathlib.Path(sys.argv[1])
version = json.loads((root / 'mod/mod.json').read_text())['version']
package = root / ('dist/egmp-aa-ev-' + version + '-nemesis-package.zip')
with zipfile.ZipFile(package) as z:
    assert z.read('mod/package.zip') == (root / 'mod.zip').read_bytes(), 'signed payload changed'
    descriptor = configparser.ConfigParser()
    descriptor.read_string(z.read('NEMESIS.data').decode())
    patterns = descriptor['nemesis']['os'].strip('"').split(',')
    assert sorted(patterns) == ['CEEV.CAN.*.260206', 'CVEV.CAN.*.260206'], patterns
    def advertised(product):
        return any(fnmatch.fnmatchcase(product, p) for p in patterns)
    assert advertised('CEEV.CAN.0000.V015.011.260206'), patterns
    assert advertised('CVEV.CAN.0000.V015.011.260206'), patterns
    for product in ['CEEV.CAN.0000.V014.010.250818',
                    'CVEV.CAN.0000.V014.005.250101', 'CVEV.CAN.0000.V016.010.260903',
                    'CVEV.USA.0000.V015.012.260507', 'NEEV.CAN.0000.V015.010.260206',
                    'CEEV.CAN.0000.V016.010.260903',
                    'CEEV.USA.0000.V014.010.250818',
                    'CVEV.USA.0000.V015.011.260206', 'CVEV.EUR.0000.V015.011.260206',
                    'CEEV.USA.0000.V015.011.260206', 'CEEV.EUR.0000.V015.011.260206']:
        assert not advertised(product), (product, patterns)
with zipfile.ZipFile(root / ('dist/egmp-aa-ev-' + version + '-nemesis.zip')) as z:
    assert z.read('packages/hmgapi.egmp-aa-ev.zip') == package.read_bytes(), 'USB package changed'
print('ok    NEMESIS compatibility is narrow; signed package and USB inner bytes are preserved')
PY

# Same-author upgrades from the prior Ioniq 6 package must preserve the owner's
# saved settings and enabled/disabled state. The old EV6 install used the real
# Engine's --any-car path in this host-only fixture; the new package needs none.
python3 - "$T" "$HMGAPI_ENGINE" <<'PYUPGRADE'
import itertools,json,os,pathlib,shutil,subprocess,sys,zipfile
root=pathlib.Path(sys.argv[1]); engine=sys.argv[2]
prior=root/'prior';prior.mkdir()
metadata=json.loads((root/'mod/mod.json').read_text())
assert metadata['version']=='0.5.1-beta.102', metadata['version']
metadata['version']='0.5.1-beta.101'
metadata['system']=[{'model':['CEEV'],'region':'CAN','firmware':['260206']}]
(prior/'mod.json').write_text(json.dumps(metadata)+'\n')
shutil.copy2(root/'mod/settings.json',prior/'settings.json')
subprocess.run(['java','-cp',engine,'hmgapi.engine.Signing','sign',str(prior),str(root/'test.key')],check=True,capture_output=True,text=True)
with zipfile.ZipFile(root/'prior.zip','w') as z:
    for path in sorted(prior.rglob('*')):
        if path.is_file(): z.write(path,path.relative_to(prior))
uuid=json.loads((prior/'META-HMGAPI/manifest.json').read_text())['uuid']
for model,preconditioning,enabled in itertools.product(('CEEV','CVEV'),(False,True),(False,True)):
    unit=root/'upgrades'/('%s-%s-%s'%(model,preconditioning,enabled))
    data=unit/'data/hmgapi';data.mkdir(parents=True)
    # Metadata-only enabled companion: LiveState otherwise correctly marks
    # EV disabled because its dependency is absent, irrespective of its switch.
    wireless=data/'mods/wireless-carplay-aa';wireless.mkdir(parents=True)
    shutil.copy2('hmgapi-mod/test/fixtures/wireless-carplay-aa/mod.json',wireless/'mod.json')
    env=dict(os.environ,HMGAPI_ROOT=str(unit),HMGAPI_HOST='1',
             HMGAPI_PRODUCT=model+'.CAN.0000.V015.011.260206')
    def install(package,*flags):
        result=subprocess.run(['java','-cp',engine,'hmgapi.engine.Engine','install',str(package),
                               '--from-nemesis',*flags],env=env,capture_output=True,text=True)
        assert result.returncode==0, result.stdout+result.stderr
        return result.stdout
    old=install(root/'prior.zip',*(['--any-car'] if model=='CVEV' else []))
    installed=data/'mods/egmp-aa-ev'
    assert json.loads((installed/'mod.json').read_text())['version']=='0.5.1-beta.101'
    if model=='CVEV': assert 'installing it anyway' in old, old
    settings=data/'settings/egmp-aa-ev.json';settings.parent.mkdir(parents=True,exist_ok=True)
    saved=json.dumps({'preconditioning':preconditioning,'precond_window_min':17,
                      'live_data':False,'energy_model':True},sort_keys=True)+'\n'
    settings.write_text(saved)
    if not enabled: (installed/'.disabled').touch()
    updated=install(root/'mod.zip')
    assert 'installing it anyway' not in updated, updated
    assert json.loads((installed/'mod.json').read_text())['version']=='0.5.1-beta.102'
    assert json.loads((installed/'mod.json').read_text())['id']=='egmp-aa-ev'
    assert json.loads((installed/'META-HMGAPI/manifest.json').read_text())['uuid']==uuid
    assert settings.read_text()==saved
    assert (installed/'.disabled').exists() is (not enabled)
    state=json.loads((data/'public/state.json').read_text())['mods']['egmp-aa-ev']
    assert state['enabled'] is enabled
    assert state['settings']['preconditioning'] is preconditioning
    assert state['settings']['precond_window_min']==17
    assert state['settings']['live_data'] is False
    print('ok    %s same-author beta101 -> beta102: preconditioning=%s, enabled=%s preserved'%
          (model,str(preconditioning).lower(),str(enabled).lower()))
PYUPGRADE
