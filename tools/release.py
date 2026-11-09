"""Check tag/version alignment and stage only intended release attachments."""
import argparse
import json
from pathlib import Path
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from region_switch import file_digest


def section(path, version):
    text = path.read_text(encoding='utf-8')
    match = re.search(r'^## \[' + re.escape(version) + r'\][^\n]*\n(.*?)(?=^## \[|\Z)', text, re.M | re.S)
    if match is None or not match[1].strip():
        raise ValueError(f'{path.name} has no [{version}] release notes')
    return match[1].strip()


def check_tag(tag):
    version = json.loads((ROOT / 'version.json').read_text(encoding='utf-8'))['version']
    if not re.fullmatch(r'v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', tag) or tag != 'v' + version:
        raise ValueError('Tag must match version.json: v' + version)
    return version


def stage(build, output, tag):
    version = check_tag(tag)
    body = '## English\n\n' + section(ROOT / 'CHANGELOG.md', version) + '\n\n## 中文\n\n' + section(ROOT / 'CHANGELOG_zh.md', version) + '\n'
    output.mkdir(parents=True, exist_ok=False)
    names = ['PicoRegionSwitch-' + version + '.zip', 'module-manifest.json', 'build-result.json', 'LICENSE', 'THIRD_PARTY_NOTICES.md']
    for name in names:
        shutil.copyfile((ROOT if name in ('LICENSE', 'THIRD_PARTY_NOTICES.md') else build) / name, output / name)
    result = json.loads((output / 'build-result.json').read_text(encoding='utf-8'))
    if result['version'] != version or file_digest(output / names[0]) != result['zip_sha256']:
        raise ValueError('Build identity differs from the requested release')
    (output / 'SHA256SUMS.txt').write_text(''.join(file_digest(output / name) + '  ' + name + '\n' for name in names), encoding='utf-8', newline='\n')
    # Notes are used as the body, not uploaded as another asset.
    (build / 'release-notes.md').write_text(body, encoding='utf-8', newline='\n')
    return body


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.build is None:
        check_tag(args.tag)
    else:
        if args.output is None:
            parser.error('--build requires --output')
        stage(args.build, args.output, args.tag)
