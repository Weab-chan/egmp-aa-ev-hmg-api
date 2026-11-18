#!/usr/bin/env python3
"""Build and verify private test artifacts using an existing retained author key."""
import argparse
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CANDIDATE_VERSION = '0.5.1-beta.102'
AUTHOR_UUID = 'c2318db4-4435-830e-9ef0-297ce6a863fc'
PRODUCTS = ('CEEV.CAN.0000.V015.011.260206', 'CVEV.CAN.0000.V015.011.260206')
PATTERNS = ('CEEV.CAN.*.260206', 'CVEV.CAN.*.260206')


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], cwd=ROOT, check=True, **kwargs)


def validate_contract(metadata, defaults):
    """Keep this reviewed candidate limited to its two Canadian targets."""
    if metadata.get('id') != 'egmp-aa-ev' or metadata.get('version') != CANDIDATE_VERSION:
        raise ValueError('review builder provenance before changing the module ID or candidate version')
    if metadata.get('hmgapi') != '>=1.1.0-beta.1':
        raise ValueError('the reviewed core dependency is >=1.1.0-beta.1')
    if metadata.get('requires') != ['wireless-carplay-aa >=2.2.0-beta.101']:
        raise ValueError('review builder provenance before changing the working wireless dependency')
    expected = [{'model': ['CEEV', 'CVEV'], 'region': 'CAN', 'firmware': ['260206']}]
    if metadata.get('system') != expected:
        raise ValueError('candidate must admit exactly CEEV and CVEV / CAN / 260206')
    if defaults.get('preconditioning') is not False:
        raise ValueError('private first test requires preconditioning off by default')


def validate_patterns(patterns):
    if len(patterns) != len(PATTERNS) or set(patterns) != set(PATTERNS):
        raise ValueError('NEMESIS must contain exactly the two reviewed Canadian firmware patterns')
    if not all(any(fnmatch.fnmatchcase(product, p) for p in patterns) for product in PRODUCTS):
        raise ValueError('NEMESIS does not recognize both target firmware strings')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--key', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    key, out = args.key.resolve(), args.out.resolve()
    if ROOT == key or ROOT in key.parents:
        parser.error('keep the private author key outside this repository')
    if not key.is_file() or not Path(str(key) + '.pub').is_file():
        parser.error('an existing private key and its .pub file are required')
    if key.stat().st_mode & 0o077:
        parser.error('private key must have mode 600')
    if out.exists():
        parser.error('output already exists; never overwrite a test artifact')
    if run(['git', 'status', '--porcelain'], capture_output=True, text=True).stdout:
        parser.error('commit the reviewed source before making a distributable test')
    sdk = Path(os.environ.get('HMGAPI_SDK', '')).resolve()
    if not (sdk / 'lib/hmgapi-engine.jar').is_file():
        parser.error('set HMGAPI_SDK to the pinned released SDK')
    run(['python3', ROOT / 'tools/verify-sdk.py', sdk])
    source = run(['git', 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
    metadata = json.loads((ROOT / 'hmgapi-mod/mod.json').read_text())
    settings = json.loads((ROOT / 'hmgapi-mod/settings.json').read_text())
    defaults = {s['key']: s['default'] for s in settings['settings']}
    try:
        validate_contract(metadata, defaults)
    except ValueError as e:
        parser.error(str(e))
    version = metadata['version']
    signing = ['java', '-cp', sdk / 'lib/hmgapi-engine.jar', 'hmgapi.engine.Signing']
    author = run(signing + ['uuid', str(key) + '.pub'], capture_output=True, text=True).stdout.strip()
    if author != AUTHOR_UUID:
        parser.error('use the retained private author identity; do not replace it with a new key')
    run(['sh', 'hmgapi-mod/build.sh', key])
    package = ROOT / 'dist' / ('egmp-aa-ev-' + version + '.zip')
    out.mkdir(parents=True)
    with tempfile.TemporaryDirectory(dir=out) as temporary:
        extracted = Path(temporary)
        with zipfile.ZipFile(package) as z:
            z.extractall(extracted)
        run(signing + ['verify', extracted, author])
        if json.loads((extracted / 'mod.json').read_text()) != metadata:
            parser.error('signed manifest does not match committed source')
    run(['sh', sdk / 'bin/mknemesismod.sh', package, out])
    wrapped = out / ('egmp-aa-ev-' + version + '-nemesis-package.zip')
    with zipfile.ZipFile(wrapped) as z:
        if z.read('mod/package.zip') != package.read_bytes():
            parser.error('NEMESIS wrapper changed the signed package')
        descriptor = z.read('NEMESIS.data').decode()
        fields = dict(line.split('=', 1) for line in descriptor.splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
        patterns = fields['os'].strip().strip('"').split(',')
        try:
            validate_patterns(patterns)
        except ValueError as e:
            parser.error(str(e))
    with zipfile.ZipFile(out / ('egmp-aa-ev-' + version + '-nemesis.zip')) as z:
        if z.read('packages/hmgapi.egmp-aa-ev.zip') != wrapped.read_bytes():
            parser.error('USB wrapper changed the NEMESIS package')
    shutil.copy2(package, out / package.name)
    artifacts = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.glob('*.zip'))}
    (out / 'SHA256SUMS').write_text(''.join(digest + '  ' + name + '\n' for name, digest in artifacts.items()))
    (out / 'BUILD.json').write_text(json.dumps({
        'source_commit': source, 'version': version, 'author_uuid': author,
        'sdk': {k: v for k, v in json.loads((ROOT / 'hmgapi-sdk.json').read_text()).items() if k != 'files'},
        'targets': list(PRODUCTS), 'nemesis_patterns': list(PATTERNS),
        'core_dependency': '>=1.1.0-beta.1', 'wireless_dependency': '>=2.2.0-beta.101',
        'validation_scope': 'This beta.102 artifact; earlier owner reports do not validate this build.',
        'ev_routing_tested_on_car': False,
        'on_car_validation': {product: False for product in PRODUCTS},
        'prior_owner_report': {
            'version': '0.5.1-beta.101', 'target': PRODUCTS[0],
            'confirmed': ['wireless Android Auto', 'Google Maps dash directions', 'EV add-on working'],
            'preconditioning_confirmed': False,
        },
        'preconditioning_default': False,
        'existing_settings_preserved': True,
        'before_install': 'Turn preconditioning OFF in the existing mod; the EV6 saved ON setting survives upgrades.',
        'artifacts': artifacts,
    }, indent=2) + '\n')
    print('Verified private test artifacts:', out)


if __name__ == '__main__':
    main()
