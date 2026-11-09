"""Offline APK inspection and atomic install plans. No ADB/device commands."""
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess

COMPONENTS = {
    'matrix': 'com.bytedance.pico.matrix',
    'store': 'com.picovr.store',
    'user_center': 'com.picovr.vrusercenter',
    'fitness': 'com.pvr.pvrfit',
    'friends': 'com.picopui.im',
    'home': 'com.pvr.home',
    'avatar': 'com.pvr.avatareditor',
    'swift': 'com.pvr.swift',
}
PROCESS_ALIASES = ('com.pvr.swift.service', 'com.pvr.pvrfit.ui')
ORIGINAL_FRAMEWORK = 'bca28757d42e6198a332db77c024c77e2ec66cf1183fbba8a6c47d0e2d7b0125'
PATCHED_FRAMEWORK = '222d3f66a9556571b1a6b69f97171f93a090698e301d8e79f1a0aecd273a5102'
ORIGINAL_FITNESS = 'f26c3acf526410bc0a11076ddfa0f7bab1deb6fd7a5ec9512ecfe0b5aa008f7c'


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def text(data):
    return data.decode('utf-8-sig', 'replace').replace('\r', '').replace('\0', '\n')


def run(argv, timeout=120):
    result = subprocess.run(list(map(str, argv)), capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}): {argv}\n{text(result.stdout)}\n{text(result.stderr)}')
    return text(result.stdout)


def file_digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def manifest_attribute(source, name):
    match = re.search(r'(?:android:|http://schemas.android.com/apk/res/android:)' + re.escape(name) + r'\([^)]*\)=(.*)$', source, re.M)
    return match[1].strip() if match else None


def manifest_bool(source, name):
    value = manifest_attribute(source, name)
    if value is None or value == 'false':
        return False
    if value == 'true':
        return True
    raise ValueError(f'Unrecognized Manifest boolean {name}: {value}')


def manifest_services(source, package):
    rows, result = source.splitlines(), []
    for index, line in enumerate(rows):
        if not re.match(r'^\s*E: service ', line):
            continue
        indent, end = len(line) - len(line.lstrip()), index + 1
        while end < len(rows):
            if re.match(r'^\s*E:', rows[end]) and len(rows[end]) - len(rows[end].lstrip()) <= indent:
                break
            end += 1
        block = '\n'.join(rows[index:end])
        name = re.match(r'"([^"]+)"', manifest_attribute(block, 'name'))[1]
        if name.startswith('.'):
            name = package + name
        elif '.' not in name:
            name = package + '.' + name
        actions = re.findall(r'E: action[^\n]*\n\s*A: [^\n]*:name\([^)]*\)="([^"]+)"', block)
        result.append(dict(name=name, actions=actions))
    return result


def regional_process(name):
    return name in PROCESS_ALIASES or any(name == package or name.startswith(package + ':') for package in COMPONENTS.values())


def apk_info(path, tools):
    path = Path(path).resolve()
    badging = run([tools.binary('aapt2'), 'dump', 'badging', path])
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging, re.M)
    if not package:
        raise ValueError(f'aapt2 did not report a package identity: {path}')
    manifest = run([tools.binary('aapt2'), 'dump', 'xmltree', path, '--file', 'AndroidManifest.xml'])
    shared_value = manifest_attribute(manifest, 'sharedUserId')
    shared = re.match(r'"([^"]+)"', shared_value) if shared_value else None
    certificate = run([tools.java('java'), '-jar', tools.bt / 'lib/apksigner.jar', 'verify', '--print-certs', path])
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest: (\w+)$', certificate, re.M)
    if not signers:
        raise ValueError('No verified APK signer: ' + str(path))
    processes = {package[1]}
    for value in re.findall(r':process\([^)]*\)="([^"]+)"', manifest):
        processes.add(package[1] + value if value.startswith(':') else value)
    return dict(package=package[1], version_code=package[2], version_name=package[3],
                role={v: k for k, v in COMPONENTS.items()}.get(package[1]), path=str(path),
                size=path.stat().st_size, sha256=file_digest(path), signer_sha256=signers,
                shared_user_id=shared[1] if shared else None, services=manifest_services(manifest, package[1]),
                processes=sorted(processes), persistent=manifest_bool(manifest, 'persistent'), debuggable=manifest_bool(manifest, 'debuggable'))


def atomic_install_script(apks, remote_dir):
    lines = ['#!/system/bin/sh', 'set -eu', 'parent=', 'children=',
             'cleanup() {',
             '  [ -z "$parent" ] || pm install-abandon "$parent" >/dev/null 2>&1 || true',
             '  for child in $children; do pm install-abandon "$child" >/dev/null 2>&1 || true; done',
             '}', 'trap cleanup EXIT',
             'pm_ok() {', '  reply=$(pm "$@") || { echo "$reply"; return 1; }', '  echo "$reply"',
             '  if [ "$1" = "install-write" ]; then', '    [ "$reply" = "Success: streamed $3 bytes" ]',
             '  else', '    [ "$reply" = "Success" ]', '  fi', '}',
             'create() {', '  reply=$(pm install-create "$@") || { echo "$reply"; exit 1; }',
             '  echo "$reply" >&2',
             '  id=$(printf "%s\\n" "$reply" | sed -n "s/^Success: created install session \\[\\([0-9][0-9]*\\)\\].*/\\1/p")',
             '  case "$id" in ""|*[!0-9]*) exit 1;; esac', '  echo "$id"', '}',
             'parent=$(create --multi-package -r -d --user 0)']
    for role, apk in apks.items():
        if role not in COMPONENTS or apk['package'] != COMPONENTS[role]:
            raise ValueError('Unexpected regional component: ' + role)
        path, size = shlex.quote(remote_dir + '/' + role + '.apk'), int(apk['size'])
        lines += [f'child=$(create -r -d --user 0 -S {size})', 'children="$children $child"',
                  f'pm_ok install-write -S {size} "$child" base.apk {path}', 'pm_ok install-add-session "$parent" "$child"']
    if not apks:
        raise ValueError('No APKs selected')
    return '\n'.join(lines + ['pm_ok install-commit "$parent"', 'parent=', 'children=', 'echo REGION_ATOMIC_INSTALL_SUCCESS']) + '\n'
