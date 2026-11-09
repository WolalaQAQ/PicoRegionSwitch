"""Validate, pack or fetch exactly the vendor inputs pinned in config/inputs.lock.json."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from region_switch import file_digest


def lock():
    return json.loads((ROOT / 'config/inputs.lock.json').read_text(encoding='utf-8'))['files']


def validate(directory, expected=None):
    expected = lock() if expected is None else expected
    for name, identity in expected.items():
        path = directory / name
        if path.stat().st_size != identity['size'] or file_digest(path) != identity['sha256']:
            raise ValueError('Vendor input differs from the supported baseline: ' + name)


def unpack(archive, directory, expected=None):
    expected = lock() if expected is None else expected
    with zipfile.ZipFile(archive) as bundle:
        names = bundle.namelist()
        if len(names) != len(set(names)) or set(names) != set(expected):
            raise ValueError('Input archive must contain exactly the locked relative file names')
        directory.mkdir(parents=True, exist_ok=False)
        for name in names:
            if bundle.getinfo(name).file_size != expected[name]['size']:
                raise ValueError('Input ZIP entry size differs: ' + name)
            target = directory / name
            target.parent.mkdir(parents=True, exist_ok=True)
            with bundle.open(name) as source, target.open('xb') as output:
                shutil.copyfileobj(source, output)
    validate(directory, expected)


def pack(directory, archive):
    validate(directory)
    archive.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_STORED) as bundle:
        for name in sorted(lock()):
            bundle.write(directory / name, name)


def fetch(directory):
    url = os.environ['REGION_INPUTS_URL']
    if not url.startswith('https://'):
        raise ValueError('REGION_INPUTS_URL must be HTTPS')
    archive = directory.with_suffix('.download.zip')
    archive.parent.mkdir(parents=True, exist_ok=True)
    # Avoid printing a private or signed URL, including exception messages containing it.
    try:
        with urllib.request.urlopen(url, timeout=120) as source, archive.open('xb') as output:
            shutil.copyfileobj(source, output)
    except Exception:
        raise RuntimeError('Unable to download vendor inputs; check the configured URL') from None
    unpack(archive, directory)
    archive.unlink()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['check', 'pack', 'fetch'])
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--archive', type=Path)
    args = parser.parse_args()
    if args.action == 'check':
        validate(args.directory)
    elif args.action == 'pack':
        if args.archive is None:
            parser.error('pack requires --archive')
        pack(args.directory, args.archive)
    else:
        fetch(args.directory)
    print('Vendor inputs match the pinned baseline.')
