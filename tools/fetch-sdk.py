#!/usr/bin/env python3
"""Download and verify the released SDK pinned by hmgapi-sdk.json."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        parser.error('output directory already exists; choose a new directory')
    pin = json.loads((ROOT / 'hmgapi-sdk.json').read_text())
    version = pin['version']
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?', version):
        parser.error('invalid SDK version')
    name = 'hmgapi-mod-sdk-' + version + '.zip'
    url = 'https://github.com/Open-HMG/HMG-API/releases/download/v' + version + '/' + name
    with urllib.request.urlopen(url, timeout=120) as response:
        archive = response.read()
    if hashlib.sha256(archive).hexdigest() != pin['sha256']:
        parser.error('download does not match the pinned SHA-256')
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        if z.read('VERSION').decode().strip() != version:
            parser.error('SDK VERSION does not match the pin')
        for item in z.infolist():
            path = PurePosixPath(item.filename)
            if path.is_absolute() or '..' in path.parts or stat.S_ISLNK(item.external_attr >> 16):
                parser.error('unsafe SDK archive entry')
        args.out.mkdir(parents=True)
        z.extractall(args.out)
        for item in z.infolist():
            mode = (item.external_attr >> 16) & 0o777
            if mode:
                (args.out / item.filename).chmod(mode)
    print('Verified SDK', version, pin['sha256'])


if __name__ == '__main__':
    main()
