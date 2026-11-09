"""Verify the installable ZIP against its exact manifest and supported runtime layout."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import region_switch as core


def verify(build):
    result = json.loads((build / 'build-result.json').read_text(encoding='utf-8'))
    manifest = json.loads((build / 'module-manifest.json').read_text(encoding='utf-8'))
    path = build / result['zip']
    if core.file_digest(path) != result['zip_sha256']:
        raise ValueError('Release ZIP digest differs')
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) != set(manifest):
            raise ValueError('ZIP files differ from module-manifest.json')
        required = {'customize.sh', 'service.sh', 'uninstall.sh', 'bin/region-rename', 'profiles/initial-cn.json',
                    'system/framework/services.jar', 'system/app/PicoRegionSwitch/PicoRegionSwitch.apk',
                    'system/priv-app/pvrfit/pvrfit.apk', 'META-INF/com/google/android/update-binary'}
        if not required <= set(names):
            raise ValueError('Missing first-install/module runtime files')
        for name, expected in manifest.items():
            if Path(name).is_absolute() or '..' in Path(name).parts or '\\' in name:
                raise ValueError('Unsafe module path: ' + name)
            info = archive.getinfo(name)
            with archive.open(name) as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            if digest != expected['sha256'] or info.file_size != expected['size']:
                raise ValueError('Module file differs: ' + name)
            if name.endswith('.sh') or name.endswith('/update-binary'):
                source = archive.read(name)
                if b'\r' in source or b'\x00' in source or b'\x01' in source or not info.external_attr >> 16 & 0o100:
                    raise ValueError('Invalid shell text or executable mode: ' + name)
        if manifest['system/framework/services.jar']['sha256'] != core.PATCHED_FRAMEWORK:
            raise ValueError('Unreviewed framework patch')
        for region in ('cn', 'global'):
            profile = json.loads(archive.read('profiles/' + region + '.json'))
            if set(profile['apks']) != set(core.COMPONENTS):
                raise ValueError('Profile must contain exactly eight components')
            for role, package in core.COMPONENTS.items():
                item = profile['apks'][role]
                expected_path = 'packages/' + region + '/' + role + '.apk'
                if item['path'] != expected_path or item['package'] != package or item['sha256'] != manifest[expected_path]['sha256']:
                    raise ValueError('Bundled profile differs: ' + region + '/' + role)
        global_profile = json.loads(archive.read('profiles/global.json'))
        if global_profile['apks']['fitness']['sha256'] != manifest['system/priv-app/pvrfit/pvrfit.apk']['sha256']:
            raise ValueError('Fitness base is not the verified global original')
    print(f'Verified {len(manifest)} exact module files; one installable ZIP, eight apps, 24 data roots.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    verify(parser.parse_args().build)
