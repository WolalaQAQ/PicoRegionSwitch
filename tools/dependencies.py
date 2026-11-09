"""Fetch build-time libraries; hashes pin the bytes used by the audited pipeline."""
import argparse
from pathlib import Path
import sys
import urllib.request
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from region_switch import file_digest

DEPENDENCIES = {
    'json-20240303.jar': ('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',
                          '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed', None),
    'jadx-1.5.1-all.jar': ('https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip',
                          'dc717e51c08d31a66357933c8d4896755d7c4939745e03aefd02427041f9aa53', 'lib/jadx-1.5.1-all.jar'),
}


def fetch(output, framework=False):
    output.mkdir(parents=True, exist_ok=True)
    for name, (url, digest, member) in DEPENDENCIES.items():
        if member and not framework:
            continue
        target = output / name
        if not target.exists():
            temporary = output / (name + '.download')
            with urllib.request.urlopen(url, timeout=120) as response, temporary.open('wb') as stream:
                import shutil
                shutil.copyfileobj(response, stream)
            if member:
                with zipfile.ZipFile(temporary) as archive:
                    target.write_bytes(archive.read(member))
                temporary.unlink()
            else:
                temporary.rename(target)
        if file_digest(target) != digest:
            raise ValueError('Build dependency differs from its pinned SHA-256: ' + name)
        print('Verified ' + name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('build/deps'))
    parser.add_argument('--framework', action='store_true')
    args = parser.parse_args()
    fetch(args.output, args.framework)
