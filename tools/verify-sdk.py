#!/usr/bin/env python3
"""Verify extracted SDK files against the hashes of the pinned release archive."""
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
pin = json.loads((ROOT / 'hmgapi-sdk.json').read_text())
if len(sys.argv) != 2:
    sys.exit('usage: verify-sdk.py <extracted SDK>')
sdk = Path(sys.argv[1])
bad = [name for name, digest in pin['files'].items()
       if not (sdk / name).is_file() or hashlib.sha256((sdk / name).read_bytes()).hexdigest() != digest]
if bad:
    sys.exit('SDK differs from the pinned release: ' + ', '.join(bad))
print('Verified extracted SDK', pin['version'], '(' + str(len(pin['files'])) + ' files)')
