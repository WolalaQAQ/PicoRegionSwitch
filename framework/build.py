"""Offline framework candidate/control build. No device, network or module activation."""
import argparse
import ast
import os
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import time
import zipfile
import zlib

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from tools.toolchain import Toolchain
ORIGINAL = None
EXPECTED = 'bca28757d42e6198a332db77c024c77e2ec66cf1183fbba8a6c47d0e2d7b0125'
DEFAULT_OUTPUT = HERE.parent / 'build/framework'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def components():
    tree = ast.parse((HERE.parent / 'region_switch.py').read_text(encoding='utf-8'))
    node = next(n for n in tree.body if isinstance(n, ast.Assign)
                and any(isinstance(t, ast.Name) and t.id == 'COMPONENTS' for t in n.targets))
    packages = [package for role, package in ast.literal_eval(node.value).items() if role != 'swift']
    assert len(packages) == 7 and len(set(packages)) == 7
    source = (HERE / 'PicoRegionDowngradeGate.java').read_text(encoding='utf-8')
    assert re.findall(r'"([a-z0-9_.]+)"\.equals\(packageName\)', source) == packages
    return packages


def dex_header(data, strict=True):
    assert data[:4] == b'dex\n' and data[7] == 0
    assert struct.unpack_from('<I', data, 32)[0] == len(data)
    assert struct.unpack_from('<I', data, 36)[0] == 112
    assert struct.unpack_from('<I', data, 40)[0] == 0x12345678
    signature = hashlib.sha1(data[32:]).digest()
    checksum_valid = zlib.adler32(data[12:]) & 0xffffffff == struct.unpack_from('<I', data, 8)[0]
    signature_valid = signature == data[12:32]
    if strict:
        assert signature_valid and checksum_valid
    result = {'version': data[4:7].decode('ascii'), 'size': len(data), 'sha256': sha(data),
              'sha1_valid': signature_valid, 'adler32_valid': checksum_valid,
              'sha1_header': data[12:32].hex(), 'sha1_computed': signature.hex()}
    for name, offset in [('strings', 56), ('types', 64), ('protos', 72),
                         ('fields', 80), ('methods', 88), ('classes', 96)]:
        result[name] = struct.unpack_from('<I', data, offset)[0]
    return result


def zip_layout(path):
    data = path.read_bytes()
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        assert len({i.filename for i in infos}) == len(infos)
        central = archive.start_dir
        comment = archive.comment
    eocd = len(data) - 22 - len(comment)
    assert data[eocd:eocd + 4] == b'PK\x05\x06'
    end = struct.unpack_from('<4s4H2IH', data, eocd)
    assert end[1:3] == (0, 0) and end[3] == end[4] == len(infos)
    assert end[6] == central and end[5] == eocd - central  # no ZIP64/digital-signature trailer
    records = {}
    cursor = central
    for info in infos:
        assert data[cursor:cursor + 4] == b'PK\x01\x02'
        name_len, extra_len, comment_len = struct.unpack_from('<3H', data, cursor + 28)
        size = 46 + name_len + extra_len + comment_len
        records[info.filename] = data[cursor:cursor + size]
        cursor += size
    assert cursor == eocd
    segments = {}
    ordered = sorted(infos, key=lambda i: i.header_offset)
    for index, info in enumerate(ordered):
        stop = ordered[index + 1].header_offset if index + 1 < len(ordered) else central
        segments[info.filename] = data[info.header_offset:stop]
    return data, infos, records, segments, data[eocd:]


def raw_repack(source, destination, name, replacement):
    """Keep every other local record, compressed payload and ZIP metadata unchanged."""
    raw, infos, records, segments, end = zip_layout(source)
    out = bytearray(raw[:min(i.header_offset for i in infos)])
    offsets = {}
    replacement_crc = zlib.crc32(replacement) & 0xffffffff
    info = next(i for i in infos if i.filename == name)
    assert info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
    if info.compress_type == zipfile.ZIP_DEFLATED:
        compressor = zlib.compressobj(6, zlib.DEFLATED, -15)
        compressed = compressor.compress(replacement) + compressor.flush()
    else:
        compressed = replacement
    for item in sorted(infos, key=lambda i: i.header_offset):
        offsets[item.filename] = len(out)
        segment = segments[item.filename]
        if item.filename == name:
            assert segment[:4] == b'PK\x03\x04'
            name_len, extra_len = struct.unpack_from('<2H', segment, 26)
            header = bytearray(segment[:30 + name_len + extra_len])
            flags = struct.unpack_from('<H', header, 6)[0] & ~8
            assert not flags & 1  # not encrypted
            struct.pack_into('<H', header, 6, flags)
            struct.pack_into('<3I', header, 14, replacement_crc, len(compressed), len(replacement))
            segment = header + compressed
        out += segment
    central_offset = len(out)
    for item in infos:
        record = bytearray(records[item.filename])
        struct.pack_into('<I', record, 42, offsets[item.filename])
        if item.filename == name:
            flags = struct.unpack_from('<H', record, 8)[0] & ~8
            struct.pack_into('<H', record, 8, flags)
            struct.pack_into('<3I', record, 16, replacement_crc, len(compressed), len(replacement))
        out += record
    tail = bytearray(end)
    struct.pack_into('<2I', tail, 12, len(out) - central_offset, central_offset)
    destination.write_bytes(out + tail)


def zip_audit(original, candidate, changed):
    _, ai, ac, al, _ = zip_layout(original)
    _, bi, bc, bl, _ = zip_layout(candidate)
    assert [x.filename for x in ai] == [x.filename for x in bi]
    rows = []
    with zipfile.ZipFile(original) as a, zipfile.ZipFile(candidate) as b:
        assert a.comment == b.comment
        for i in ai:
            name = i.filename
            old, new = a.read(name), b.read(name)
            unchanged = old == new
            local_same = al[name] == bl[name]
            central_a, central_b = bytearray(ac[name]), bytearray(bc[name])
            central_a[42:46] = central_b[42:46] = b'\0' * 4
            central_same = central_a == central_b
            if name != changed:
                assert unchanged and local_same and central_same, name
            else:
                assert not unchanged, name
            rows.append({'entry': name, 'original_sha256': sha(old), 'candidate_sha256': sha(new),
                         'payload_equal': unchanged, 'local_record_equal': local_same,
                         'central_metadata_equal_except_offset': central_same})
    return rows


def write_report(output, manifest):
    control = json.loads((output / 'audit/original-v-control.json').read_text(encoding='utf-8'))
    patched = json.loads((output / 'audit/original-v-patched.json').read_text(encoding='utf-8'))
    cross = json.loads((output / 'audit/control-v-patched.json').read_text(encoding='utf-8'))
    storage = control['staticDefaultStorageChanges']
    storage_classes = {s['field'].split('->')[0] for s in storage}
    target_a, target_b = patched['target']['original'], patched['target']['candidate']
    unchanged = manifest['inventories']['classes2.dex']
    sections = [
        '# 七组件框架补丁离线审计报告',
        '',
        f'日期：{manifest["created"]}。状态：**离线审计通过的候选，尚未安装或在 ART 验收**。',
        '',
        '## 产物',
        '',
        '| 文件 | SHA-256 |',
        '|---|---|',
        f'| services-original.jar | `{manifest["input_sha256"]}` |',
        *[f'| {name} | `{digest}` |' for name, digest in manifest['jar_sha256'].items()],
        '',
        '`services-control.jar` 与 `services-patched.jar` 都从指定原件独立出发，只重写一次 `classes.dex`；没有串行二次重写，也没有整树 smali 重建。',
        '',
        '## 唯一原有方法修改点',
        '',
        '`com.android.server.pm.PackageManagerService.checkDowngrade(PackageParser.Package before, PackageInfoLite after)`，access flags 保持 `private static`／0xA。原有调用者只有 `access$2700`，其唯一调用者为 `InstallParams.installLocationPolicy`，DEX code-unit 地址 143／0x8f。两份 DEX 的引用扫描均已覆盖。',
        '',
        '方法入口新增以下 5 条指令，共 9 code units／18 字节。p0=v8、p1=v9，v0 是独立局部寄存器；返回原入口后第一条 invoke 的结果仍写入 v0:v1。',
        '',
        '```smali',
        'iget-object v0, p0, Landroid/content/pm/PackageParser$Package;->packageName:Ljava/lang/String;',
        'invoke-static {v0}, Lcom/android/server/pm/PicoRegionDowngradeGate;->allow(Ljava/lang/String;)Z',
        'move-result v0',
        'if-eqz v0, :original_entry',
        'return-void',
        ':original_entry',
        '# 原来的 89 条指令',
        '```',
        '',
        '| 目标方法 | 原件 | 补丁 |',
        '|---|---:|---:|',
        *[f'| {key} | {target_a[key]} | {target_b[key]} |'
          for key in ['registers', 'instructions', 'codeUnits', 'tryBlocks', 'debugItems']],
        '',
        '原后缀的全部 opcode、寄存器操作数、字面量、引用、分支目标及 debug 事件语义保持一致；原地址整体平移 9 code units。通过 `MutableMethodImplementation` 移动原分支和调试位置，没有手算修改原跳转。',
        '',
        '## 新增 helper 与集成接口',
        '',
        '- 类：`public final com.android.server.pm.PicoRegionDowngradeGate`；方法：`public static boolean allow(String packageName)`。另外只有私有构造方法。',
        '- 属性：`sys.pico_region_downgrade_until`，缺省 0；以 `SystemClock.elapsedRealtime()` 毫秒为时基。',
        '- 允许条件：精确命中七包，且 `deadline > now && deadline - now <= 120000`。0、到期及超过上限均返回 false。',
        '- 每次安装使用 90 秒窗口并在 finally 清 0；未清属性时仍由期限失效。helper 本身只检查包名和期限。',
        '- helper 只有一个类，编译用 Android stub 没有放入 DEX 或 JAR。',
        '',
        *[f'  - `{package}`' for package in manifest['packages']],
        '',
        '## 全量结构与语义检查（observed）',
        '',
        f'- `classes.dex`：比较 {control["classesCompared"]} 类、{control["methodsCompared"]} 方法、{control["instructionsCompared"]} 条指令、{control["tryBlocksCompared"]} 个 try block、{control["debugItemsCompared"]} 个 debug event。',
        '- 三组对比：original→control、original→patched、control→patched，均无意外语义或元数据差异。补丁比较时单独验证 5 条前缀，再逐条比较原后缀。',
        '- 全部原有类／方法 access flags、direct／virtual 归属、方法原型、参数名及注解、hidden-API 限制、寄存器数保持；类继承／接口、字段定义和实际初值保持。',
        f'- `classes2.dex`：{unchanged["classes"]} 类、{unchanged["methods"]} 方法，ZIP entry 内容和完整 local record 字节均与原件一致；因此指令及所有 metadata 均未改变。',
        '- ZIP 的 `META-INF/MANIFEST.MF` 也保留内容、压缩数据、local header／record 字节；central directory 只调整必要的 local offset。原件只有这三个 entry。',
        '- 新目标方法与 helper 的寄存器操作数均在范围内，所有分支落在指令边界且不落入 move-result，move-result 均紧邻生产指令。',
        '',
        '### 重写管线的非语义差异',
        '',
        f'- 零改写对照中 {len(storage_classes)} 类、{len(storage)} 个静态字段的末尾显式默认初值被 dexlib2 省略；这是 `false/0/null` 等值的 DEX 默认值表示变化。所有原始变化逐项列在 `audit/original-v-control.json` 的 `staticDefaultStorageChanges`，补丁与对照之间此项无差异。',
        f'- 原 `classes.dex` 字符串表 {manifest["original_dex_headers"]["classes.dex"]["strings"]} 项，对照 {manifest["control_dex_header"]["strings"]} 项；引用语义全量一致。DEX 的排序、offset、对齐及共享数据布局经写入器重排，不主张 DEX 字节不变。',
        f'- 原 `classes.dex` {manifest["original_dex_headers"]["classes.dex"]["size"]} 字节，对照 {manifest["control_dex_header"]["size"]} 字节，补丁 {manifest["patched_dex_header"]["size"]} 字节。',
        '- 指定输入的两份原始 DEX 均为 SHA-1 头部与内容不一致、Adler32 正确；输入 JAR SHA-256 与指定值相符。生成的 control／patched／helper DEX 两项完整性字段均验证正确。未改写的 classes2.dex 保留原状态。',
        '',
        '## 编译与主机测试（observed）',
        '',
        '- JDK 17 由 `JAVA_HOME` 指定。源码按 Java 8、UTF-8、`-Xlint:all -Werror` 编译；helper 经 D8 `--min-api 29` 转换。',
        '- JVM `-Xverify:all` 执行实际 helper 源码，125 项断言通过：七包、非名单、null／空字符串／大小写／前后缀，缺省／空／无效属性，0／负数／到期／恰等于现在，1ms／120000ms／120001ms 及 long 边界。Android 时间／属性由测试 stub 提供，不是设备属性读取测试。',
        '- 7 项 Python 回归通过：STORED／DEFLATED 各带或不带 data descriptor，其他 entry 原始记录保留，七包源匹配，生成 DEX 头部验证及坏 DEX 拒绝。',
        '- 每个原有方法的规范化前后摘要在 `audit/*.methods.tsv`，逐项覆盖 opcode／操作数、try/debug 与方法 metadata；完整命令、退出码、耗时及原始 stdout／stderr 在 `commands.json`、`logs/`。',
        '',
        '## 安装与运行验收边界',
        '',
        '版本、基包和拆分版本检查是唯一被短时门控跳过的逻辑；签名、权限、KeySet、包保护及 persistent 路径均属未改动方法。APK 原件没有读取后写回、改签或改版本。（inferred：这些原方法按原实现继续执行；运行结果仍须真机确认。）',
        '',
        '本报告记录离线构建与语义审计，不代替真机安装验收。当前版本的设备验证范围见 CHANGELOG。框架 helper 只检查包名和期限；事务互斥和恢复由切区引擎负责。',
        '',
        '## 离线重建',
        '',
        '```powershell',
        'python framework/build.py --input services.jar --dexlib build/deps/jadx-1.5.1-all.jar --output build/framework',
        '```',
        '',
        '输入由 --input 指定，SHA-256 约束已审计的固件基线；--output 指定输出。`build-manifest.json` 记录源码及产物身份。',
    ]
    assert control['result'] == patched['result'] == cross['result'] == 'success'
    assert not cross['staticDefaultStorageChanges']
    (output / 'REPORT.md').write_text('\n'.join(sections) + '\n', encoding='utf-8')


def verify_delivery(output):
    manifest = json.loads((output / 'build-manifest.json').read_text(encoding='utf-8'))
    assert sha(ORIGINAL.read_bytes()) == manifest['input_sha256'] == EXPECTED
    for name, expected in manifest['source_sha256'].items():
        assert sha((HERE / name).read_bytes()) == expected, name
    for name, expected in manifest['jar_sha256'].items():
        assert sha((output / name).read_bytes()) == expected, name
        with zipfile.ZipFile(output / name) as archive:
            assert archive.testzip() is None
    rows_verified = 0
    for label in ('original-v-control', 'original-v-patched', 'control-v-patched'):
        audit = json.loads((output / 'audit' / (label + '.json')).read_text(encoding='utf-8'))
        assert audit['result'] == 'success' and not audit['differences']
        lines = (output / 'audit' / (label + '.methods.tsv')).read_text(encoding='utf-8').splitlines()
        assert len(lines) - 1 == int(audit['classesCompared']) + int(audit['methodsCompared'])
        for line in lines[1:]:
            kind, identity, before, after, comparison = line.split('\t')
            assert before == after, (label, kind, identity, comparison)
            rows_verified += 1
    assert (output / 'REPORT.md').is_file() and (output / 'zip-audit.json').is_file()
    result = {'result': 'success', 'source_hashes_verified': len(manifest['source_sha256']),
              'jar_hashes_and_crc_verified': len(manifest['jar_sha256']),
              'semantic_digest_rows_verified': rows_verified, 'device_operations': 0}
    write_json(output / 'delivery-verification.json', result)
    print(json.dumps(result, indent=2))


def main():
    global ORIGINAL
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--dexlib', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument('--verify-only', action='store_true', help='Verify an existing offline delivery')
    args = parser.parse_args()
    ORIGINAL = args.input.resolve()
    JADX = args.dexlib.resolve()
    output = args.output.resolve()
    if args.verify_only:
        verify_delivery(output)
        print('RESULT=success')
        return
    output.mkdir(parents=True, exist_ok=True)
    logs = output / 'logs'
    logs.mkdir(exist_ok=True)
    commands = []

    def run(argv, timeout=120):
        index = len(list(logs.glob('*.json'))) + 1
        start = time.monotonic()
        result = subprocess.run([str(x) for x in argv], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout)
        (logs / f'{index:03d}.stdout.txt').write_bytes(result.stdout)
        (logs / f'{index:03d}.stderr.txt').write_bytes(result.stderr)
        entry = {'argv': [str(x) for x in argv], 'elapsed_seconds': time.monotonic() - start,
                 'returncode': result.returncode, 'stdout': f'logs/{index:03d}.stdout.txt',
                 'stderr': f'logs/{index:03d}.stderr.txt'}
        write_json(logs / f'{index:03d}.json', entry)
        commands.append(entry)
        write_json(output / 'commands.json', commands)
        print(f'[{index:03d}] rc={result.returncode} {entry["elapsed_seconds"]:.2f}s {argv[0]}', flush=True)
        if result.returncode:
            raise RuntimeError(result.stderr.decode('utf-8', 'replace') + result.stdout.decode('utf-8', 'replace'))
        return result.stdout.decode('utf-8', 'replace')

    original_bytes = ORIGINAL.read_bytes()
    assert sha(original_bytes) == EXPECTED
    (output / 'services-original.jar').write_bytes(original_bytes)
    packages = components()
    (output / 'packages.txt').write_text('\n'.join(packages) + '\n', encoding='utf-8')
    tools = Toolchain()
    jdk = tools.jdk
    javac, java = tools.java('javac'), tools.java('java')
    android = tools.android
    d8 = tools.bt / 'lib/d8.jar'
    for path in (javac, java, JADX, android, d8):
        assert path.is_file(), path
    with zipfile.ZipFile(JADX) as jar:
        for required in ['com/android/tools/smali/dexlib2/builder/MutableMethodImplementation.class',
                         'com/google/common/collect/ImmutableList.class']:
            assert required in jar.namelist(), required
    tool_classes = output / 'tool-classes'
    host_classes = output / 'host-classes'
    helper_dex = output / 'helper-dex'
    dex_dir = output / 'original-dex'
    audit_dir = output / 'audit'
    for directory in (tool_classes, host_classes, helper_dex, dex_dir, audit_dir):
        directory.mkdir(exist_ok=True)
    run([java, '-version'])
    run([javac, '--release', '8', '-encoding', 'UTF-8', '-Xlint:all', '-Werror',
         '-cp', JADX, '-d', tool_classes, HERE / 'FrameworkTool.java'])
    run([javac, '--release', '8', '-encoding', 'UTF-8', '-Xlint:all', '-Werror',
         '-d', host_classes, HERE / 'test-stubs/android/os/SystemClock.java',
         HERE / 'test-stubs/android/os/SystemProperties.java',
         HERE / 'PicoRegionDowngradeGate.java', HERE / 'GateSemanticsTest.java'])
    host_test = run([java, '-Xverify:all', '-cp', host_classes, 'GateSemanticsTest', output / 'packages.txt'])
    assert 'RESULT=success' in host_test
    runtime_class = host_classes / 'com/android/server/pm/PicoRegionDowngradeGate.class'
    run([java, '-cp', d8, 'com.android.tools.r8.D8', '--min-api', '29', '--lib', android,
         '--output', helper_dex, runtime_class])
    helper_file = helper_dex / 'classes.dex'
    cp = os.pathsep.join(map(str, [tool_classes, JADX]))

    def tool(*argv, timeout=180):
        return run([java, '-Xmx2g', '-cp', cp, 'FrameworkTool', *argv], timeout=timeout)

    inspections = {}
    headers = {}
    target_dex = None
    with zipfile.ZipFile(ORIGINAL) as archive:
        dex_names = [n for n in archive.namelist() if re.fullmatch(r'classes(?:\d+)?\.dex', n)]
        assert dex_names
        for name in dex_names:
            data = archive.read(name)
            headers[name] = dex_header(data, strict=False)
            path = dex_dir / name
            path.write_bytes(data)
            tool('inspect', path, audit_dir / ('inventory-' + name))
            inspections[name] = json.loads((audit_dir / ('inventory-' + name + '.json')).read_text(encoding='utf-8'))
            if 'target' in inspections[name]:
                assert target_dex is None
                target_dex = name
    assert target_dex
    source_dex = dex_dir / target_dex
    control_dex = output / 'control.dex'
    patched_dex = output / 'patched.dex'
    # Both variants start from the original and each serialize exactly once.
    tool('control', source_dex, helper_file, control_dex)
    tool('patch', source_dex, helper_file, patched_dex)
    tool('audit-control', source_dex, control_dex, audit_dir / 'original-v-control')
    tool('audit-patch', source_dex, patched_dex, audit_dir / 'original-v-patched')
    tool('audit-patch', control_dex, patched_dex, audit_dir / 'control-v-patched')
    control_jar = output / 'services-control.jar'
    patched_jar = output / 'services-patched.jar'
    raw_repack(ORIGINAL, control_jar, target_dex, control_dex.read_bytes())
    raw_repack(ORIGINAL, patched_jar, target_dex, patched_dex.read_bytes())
    zip_reports = {'original-v-control': zip_audit(ORIGINAL, control_jar, target_dex),
                   'original-v-patched': zip_audit(ORIGINAL, patched_jar, target_dex)}
    write_json(output / 'zip-audit.json', zip_reports)
    python_test = run([sys.executable, '-B', '-X', 'utf8', HERE / 'test_build.py', '--output', output])
    assert 'TESTS=7' in python_test and 'RESULT=success' in python_test
    assert ORIGINAL.read_bytes() == original_bytes
    manifest = {'schema': 1, 'created': datetime.now().astimezone().isoformat(),
                'status': 'offline-audited-candidate', 'device_operations': 0,
                'input': str(ORIGINAL), 'input_sha256': EXPECTED, 'packages': packages,
                'property': 'sys.pico_region_downgrade_until', 'maximum_window_ms': 120000,
                'timebase': 'android.os.SystemClock.elapsedRealtime()', 'target_dex': target_dex,
                'original_dex_headers': headers,
                'control_dex_header': dex_header(control_dex.read_bytes()),
                'patched_dex_header': dex_header(patched_dex.read_bytes()),
                'helper_dex_header': dex_header(helper_file.read_bytes()),
                'jar_sha256': {p.name: sha(p.read_bytes()) for p in (control_jar, patched_jar)},
                'host_tests': host_test.splitlines(), 'inventories': inspections,
                'python_tests': python_test.splitlines(),
                'jdk': str(jdk), 'dexlib2_classpath': str(JADX),
                'source_sha256': {str(p.relative_to(HERE)): sha(p.read_bytes())
                                  for p in sorted(HERE.rglob('*')) if p.suffix in ('.py', '.java')},
                'runtime_pending': ['ART class verification and boot', 'zero-change control installation',
                                    'closed/open/expired downgrade windows', 'unlisted package rejection',
                                    'signature rejection and seven-package atomic transaction',
                                    'module removal recovery', 'daily switching without reboot']}
    write_json(output / 'build-manifest.json', manifest)
    write_report(output, manifest)
    verify_delivery(output)
    print(json.dumps({'control': str(control_jar), 'patched': str(patched_jar),
                      'sha256': manifest['jar_sha256']}, indent=2))
    print('RESULT=success')


if __name__ == '__main__':
    main()
