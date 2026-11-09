import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import region_switch as core
from tools import build, inputs, release

ROOT = Path(__file__).resolve().parents[1]


class CoreTests(unittest.TestCase):
    def test_manifest_boolean_formats(self):
        self.assertTrue(core.manifest_bool('A: http://schemas.android.com/apk/res/android:persistent(0x0)=true', 'persistent'))
        self.assertFalse(core.manifest_bool('A: android:persistent(0x0)=false', 'persistent'))
        self.assertFalse(core.manifest_bool('', 'persistent'))
        with self.assertRaises(ValueError):
            core.manifest_bool('A: android:persistent(0x0)=1', 'persistent')

    def test_atomic_install_grammar(self):
        apks = {role: dict(package=package, size=42) for role, package in core.COMPONENTS.items()}
        script = core.atomic_install_script(apks, '/module/packages/cn')
        self.assertEqual(script.count('pm_ok install-add-session'), 8)
        self.assertEqual(script.count('pm_ok install-commit'), 1)
        self.assertIn('Success: streamed $3 bytes', script)
        self.assertNotIn('\x01', script)
        self.assertNotIn('pm clear', script)
        self.assertIn('install-abandon', script)

    def test_empty_or_wrong_apk_role_rejected(self):
        for apks in ({}, {'matrix': {'package': 'other', 'size': 1}}):
            with self.assertRaises(ValueError):
                core.atomic_install_script(apks, '/module')

    def test_exact_independent_process_aliases(self):
        self.assertTrue(core.regional_process('com.pvr.swift.service'))
        self.assertTrue(core.regional_process('com.pvr.pvrfit.ui'))
        self.assertFalse(core.regional_process('com.pvr.swift.service.attacker'))


class InputTests(unittest.TestCase):
    def test_roundtrip_and_identity_validation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / 'inputs.zip'
            expected = {'cn/example.apk': {'size': 3, 'sha256': hashlib.sha256(b'apk').hexdigest()}}
            with zipfile.ZipFile(archive, 'w') as bundle:
                bundle.writestr('cn/example.apk', b'apk')
            inputs.unpack(archive, root / 'out', expected)
            self.assertEqual((root / 'out/cn/example.apk').read_bytes(), b'apk')
            (root / 'out/cn/example.apk').write_bytes(b'bad')
            with self.assertRaises(ValueError):
                inputs.validate(root / 'out', expected)

    def test_unlisted_and_traversal_entries_are_rejected_before_writing(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for index, name in enumerate(('../outside', '/absolute', 'extra-file')):
                archive = root / f'{index}.zip'
                with zipfile.ZipFile(archive, 'w') as bundle:
                    bundle.writestr(name, b'bad')
                with self.assertRaises(ValueError):
                    inputs.unpack(archive, root / f'out{index}', {})
                self.assertFalse((root / f'out{index}').exists())

    def test_release_input_lock_is_complete_and_contains_only_portable_names(self):
        expected = inputs.lock()
        self.assertEqual(len(expected), 25)
        self.assertEqual(expected['services-original.jar']['sha256'], core.ORIGINAL_FRAMEWORK)
        for name in expected:
            self.assertNotIn('\\', name)
            self.assertFalse(Path(name).is_absolute())

    def test_first_install_profiles_accept_stock_and_preserve_distinct_cn_versions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, tree = root / 'inputs', root / 'module'
            tree.mkdir()
            for region in ('cn', 'global', 'stock-cn'):
                (source / region).mkdir(parents=True)
                for role in core.COMPONENTS:
                    (source / region / (role + '.apk')).write_text(region + role)
            def inspect(path, tools):
                region, role = path.parent.name, path.stem
                return dict(package=core.COMPONENTS[role], signer_sha256=[build.PLATFORM_SIGNER], processes=[core.COMPONENTS[role]],
                            version_code='1', sha256=core.file_digest(path), size=path.stat().st_size)
            with patch.object(core, 'apk_info', side_effect=inspect):
                profiles = build.profiles(source, tree, None)
            initial = json.loads((tree / 'profiles/initial-cn.json').read_text())
            self.assertEqual(len(initial['matrix']), 2)
            self.assertNotEqual(initial['matrix'][0]['sha256'], initial['matrix'][1]['sha256'])
            self.assertFalse((tree / 'packages/stock-cn').exists())
            script = (tree / 'install-upgrade-source-global.sh').read_text()
            self.assertIn('/packages/cn/swift.apk', script)
            self.assertEqual(len(profiles['global']['apks']), 8)


class ReleaseTests(unittest.TestCase):
    def test_exact_tag_and_changelog(self):
        version = json.loads((ROOT / 'version.json').read_text())['version']
        self.assertEqual(release.check_tag('v' + version), version)
        for bad in ('v99.0.0', version, 'v1.0.0;echo unsafe'):
            with self.assertRaises(ValueError):
                release.check_tag(bad)
        for language in ('CHANGELOG.md', 'CHANGELOG_zh.md'):
            self.assertTrue(release.section(ROOT / language, version))

    def test_zip_tree_modes_and_fixed_timestamps(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            tree = root / 'tree'
            tree.mkdir()
            (tree / 'service.sh').write_text('#!/system/bin/sh\n', newline='\n')
            build.zip_tree(tree, root / 'a.zip')
            build.zip_tree(tree, root / 'b.zip')
            self.assertEqual((root / 'a.zip').read_bytes(), (root / 'b.zip').read_bytes())
            with zipfile.ZipFile(root / 'a.zip') as archive:
                self.assertTrue(archive.getinfo('service.sh').external_attr >> 16 & 0o100)


if __name__ == '__main__':
    unittest.main()
