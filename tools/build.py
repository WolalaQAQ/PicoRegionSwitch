"""Build and test the local worker; optionally assemble the complete offline Magisk ZIP."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import region_switch as core
from tools.toolchain import Toolchain

MODULE = '/data/adb/modules/pico-region-downgrade'
PLATFORM_SIGNER = 'dc30289e0a61447d03c95d15e5acf63e18d109d545a766b1208f6b53cbdcd482'


def host_tests(output, tools, library):
    classes = output / 'host-tests'
    classes.mkdir()
    java = ROOT / 'headset/src/com/pico4/regionswitch'
    sources = [java / name for name in ('Engine.java', 'SwitchTransaction.java', 'TarVerifier.java', 'FirstInstallTransaction.java', 'InstallPlan.java', 'FirstInstall.java')]
    sources += list((ROOT / 'headset/tests').rglob('*.java'))
    core.run([tools.java('javac'), '-encoding', 'UTF-8', '--release', '8', '-cp', library, '-d', classes, *sources])
    classpath = os.pathsep.join(map(str, [classes, library]))
    logs = []
    for name in ('SwitchTransactionTest', 'com.pico4.regionswitch.EngineHostTest', 'com.pico4.regionswitch.FirstInstallTest'):
        logs.append(core.run([tools.java('java'), '-cp', classpath, name, output / 'fixtures']))
    result = '\n'.join(logs)
    (output / 'host-tests.txt').write_text(result, encoding='utf-8')
    print(result)


def runtime(output, tools, library, keystore=None, alias='panel'):
    host_tests(output, tools, library)
    classes = output / 'classes'
    classes.mkdir()
    sources = list((ROOT / 'headset/src').rglob('*.java')) + [ROOT / 'PicoRegionServiceCall.java']
    core.run([tools.java('javac'), '-encoding', 'UTF-8', '--release', '8', '-cp', tools.android, '-d', classes, *sources])
    core.run([tools.java('java'), '-cp', tools.bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--lib', tools.android,
              '--min-api', '29', '--output', output, *classes.rglob('*.class')])
    unsigned, aligned, apk = [output / name for name in ('unsigned.apk', 'aligned.apk', 'PicoRegionSwitch.apk')]
    version = json.loads((ROOT / 'version.json').read_text(encoding='utf-8'))
    core.run([tools.binary('aapt2'), 'link', '-o', unsigned, '--manifest', ROOT / 'headset/AndroidManifest.xml', '-I', tools.android,
              '--version-code', str(version['version_code']), '--version-name', version['version']])
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(output / 'classes.dex', 'classes.dex')
    if keystore is None:
        keystore = output / 'ci-only.jks'
        os.environ['PANEL_STORE_PASSWORD'] = 'ci-only-not-for-release'
        core.run([tools.java('keytool'), '-genkeypair', '-keystore', keystore, '-storepass:env', 'PANEL_STORE_PASSWORD',
                  '-keypass:env', 'PANEL_STORE_PASSWORD', '-alias', alias, '-dname', 'CN=CI Test Only',
                  '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650'])
    core.run([tools.binary('zipalign'), '-f', '4', unsigned, aligned])
    signer = [tools.java('java'), '-jar', tools.bt / 'lib/apksigner.jar']
    core.run([*signer, 'sign', '--ks', keystore, '--ks-pass', 'env:PANEL_STORE_PASSWORD', '--key-pass', 'env:PANEL_STORE_PASSWORD',
              '--ks-key-alias', alias, '--out', apk, aligned])
    signature = core.run([*signer, 'verify', '--verbose', '--print-certs', apk])
    (output / 'panel-signature.txt').write_text(signature, encoding='utf-8')
    core.run([tools.binary('zipalign'), '-c', '4', apk])
    native = output / 'region-rename'
    core.run([tools.compiler(), '--target=aarch64-linux-android29', '-O2', '-Wall', '-Wextra', '-Werror',
              ROOT / 'native/region_rename.c', '-o', native])
    return apk, native, version


def profiles(inputs, tree, tools):
    bundled = {}
    initial = {role: [] for role in core.COMPONENTS}
    for region in ('cn', 'global', 'stock-cn'):
        apks = {}
        for role, package in core.COMPONENTS.items():
            item = core.apk_info(inputs / region / (role + '.apk'), tools)
            if item['package'] != package or item['signer_sha256'] != [PLATFORM_SIGNER]:
                raise ValueError('Package/platform signer differs: ' + region + '/' + role)
            if any(not core.regional_process(process) for process in item['processes']):
                raise ValueError('Uncovered process: ' + region + '/' + role)
            item['path'] = 'packages/' + region + '/' + role + '.apk'
            apks[role] = item
            if region != 'global':
                if item['sha256'] not in {old['sha256'] for old in initial[role]}:
                    initial[role].append({key: value for key, value in item.items() if key != 'path'})
            if region != 'stock-cn':
                target = tree / item['path']
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(inputs / region / (role + '.apk'), target)
        if region != 'stock-cn':
            bundled[region] = dict(schema=1, name=region, region_value='CN' if region == 'cn' else 'US', apks=apks)
    swift = bundled['global']['apks']['swift']['version_code']
    if any(item['version_code'] != swift for item in initial['swift']):
        raise ValueError('Swift is outside the seven-package downgrade exception; versionCodes must match')
    (tree / 'profiles').mkdir()
    core.write_json(tree / 'profiles/initial-cn.json', initial)
    for region, profile in bundled.items():
        core.write_json(tree / 'profiles' / (region + '.json'), profile)
        plan = core.atomic_install_script(profile['apks'], MODULE + '/packages/' + region)
        (tree / ('install-' + region + '.sh')).write_text(plan, encoding='utf-8', newline='\n')
        legacy = json.loads(json.dumps(profile))
        legacy['apks']['swift'] = bundled['cn']['apks']['swift']
        legacy['original_components'] = 7
        core.write_json(tree / 'profiles' / ('upgrade-source-' + region + '.json'), legacy)
        plan = core.atomic_install_script(legacy['apks'], MODULE + '/packages/' + region)
        if region == 'global':
            # The legacy global installation still has CN Swift; read its original package.
            plan = plan.replace(MODULE + '/packages/global/swift.apk', MODULE + '/packages/cn/swift.apk')
        (tree / ('install-upgrade-source-' + region + '.sh')).write_text(plan, encoding='utf-8', newline='\n')
    return bundled


def zip_tree(tree, destination):
    with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_STORED) as archive:
        for path in sorted(tree.rglob('*')):
            if not path.is_file():
                continue
            info = zipfile.ZipInfo(path.relative_to(tree).as_posix())
            info.external_attr = (0o100755 if path.suffix == '.sh' or path.name in ('update-binary', 'region-rename') else 0o100644) << 16
            archive.writestr(info, path.read_bytes())


def assemble(output, inputs, tools, apk, native, version, dexlib):
    from tools.inputs import validate
    validate(inputs)
    framework = output / 'framework'
    core.run([sys.executable, ROOT / 'framework/build.py', '--input', inputs / 'services-original.jar', '--dexlib', dexlib, '--output', framework], timeout=600)
    patched = framework / 'services-patched.jar'
    if core.file_digest(patched) != core.PATCHED_FRAMEWORK:
        raise ValueError('Rebuilt framework differs from the audited patch; inspect the semantic report')
    tree = output / 'module'
    shutil.copytree(ROOT / 'headset/module', tree)
    bundled = profiles(inputs, tree, tools)
    for name, source in {
        'system/app/PicoRegionSwitch/PicoRegionSwitch.apk': apk,
        'system/framework/services.jar': patched,
        'system/priv-app/pvrfit/pvrfit.apk': inputs / 'global/fitness.apk',
        'bin/region-rename': native,
    }.items():
        target = tree / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    (tree / 'module.prop').write_text('id=pico-region-downgrade\nname=PICO Region Switch\nversion=' + version['version'] +
        '\nversionCode=' + str(version['version_code']) + '\nauthor=WolalaQAQ\ndescription=Offline eight-app regional switch with separate data and first-install recovery.\n', encoding='utf-8', newline='\n')
    meta = tree / 'META-INF/com/google/android'
    meta.mkdir(parents=True)
    (meta / 'updater-script').write_text('#MAGISK\n', encoding='utf-8', newline='\n')
    (meta / 'update-binary').write_text('#!/sbin/sh\numask 022\nOUTFD=$2\nZIPFILE=$3\nmount /data 2>/dev/null\n'
        '[ -f /data/adb/magisk/util_functions.sh ] && . /data/adb/magisk/util_functions.sh || exit 1\ninstall_module\nexit 0\n', encoding='utf-8', newline='\n')
    for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md'):
        shutil.copyfile(ROOT / name, tree / name)
    manifest = {p.relative_to(tree).as_posix(): dict(size=p.stat().st_size, sha256=core.file_digest(p)) for p in sorted(tree.rglob('*')) if p.is_file()}
    core.write_json(output / 'module-manifest.json', manifest)
    destination = output / ('PicoRegionSwitch-' + version['version'] + '.zip')
    zip_tree(tree, destination)
    core.write_json(output / 'build-result.json', dict(version=version['version'], zip=destination.name,
        zip_sha256=core.file_digest(destination), framework_sha256=core.PATCHED_FRAMEWORK,
        components=8, data_roots=24, first_install='supported-cn-baseline; see CHANGELOG for device acceptance scope', os_upgrade=False))
    print(destination)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--inputs', type=Path)
    parser.add_argument('--deps', type=Path, default=ROOT / 'build/deps')
    parser.add_argument('--keystore', type=Path)
    parser.add_argument('--key-alias', default='panel')
    args = parser.parse_args()
    if args.inputs and not args.keystore:
        parser.error('Complete installable ZIPs require an explicit stable signing key (--keystore)')
    args.output.mkdir(parents=True, exist_ok=False)
    tools = Toolchain()
    apk, native, version = runtime(args.output.resolve(), tools, args.deps.resolve() / 'json-20240303.jar', args.keystore, args.key_alias)
    if args.inputs:
        assemble(args.output.resolve(), args.inputs.resolve(), tools, apk, native, version, args.deps.resolve() / 'jadx-1.5.1-all.jar')
