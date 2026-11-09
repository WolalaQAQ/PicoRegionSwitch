from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


def public_files():
    for name in ('headset', 'framework', 'native', 'tools', 'tests', '.github', 'config'):
        for path in (ROOT / name).rglob('*'):
            if path.is_file() and '__pycache__' not in path.parts:
                yield path
    for name in ('region_switch.py', 'PicoRegionServiceCall.java', 'README.md', 'README_zh.md', 'CHANGELOG.md', 'CHANGELOG_zh.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md', 'version.json', '.gitignore', '.gitattributes'):
        yield ROOT / name


class SourceContractTests(unittest.TestCase):
    def test_public_document_links_resolve(self):
        for path in ROOT.glob('*.md'):
            if path.name == 'lessons.md':
                continue
            for target in re.findall(r'\[[^\]]+\]\(([^)]+)\)', path.read_text(encoding='utf-8')):
                if target.startswith(('http:', 'https:', '#')):
                    continue
                self.assertTrue((path.parent / target.split('#')[0]).is_file(), (path.name, target))

    def test_no_personal_paths_device_ids_or_signing_secrets(self):
        # Assemble sentinel strings so this test does not itself contain the forbidden value.
        forbidden = ['C:' + '\\Users\\', 'D:' + '\\pico4-backup', 'D:' + '/pico4-backup', 'PA8A10' + 'MGH5250115D', 'local-region' + '-switch']
        for path in public_files():
            source = path.read_text(encoding='utf-8-sig')
            for value in forbidden:
                self.assertNotIn(value, source, str(path.relative_to(ROOT)))

    def test_shell_scripts_are_lf_and_no_control_character(self):
        for path in ROOT.rglob('*.sh'):
            if any(part in ('.local', 'build', '.git') for part in path.relative_to(ROOT).parts):
                continue
            data = path.read_bytes()
            self.assertNotIn(b'\r', data, str(path))
            self.assertNotIn(b'\x01', data, str(path))

    def test_networkless_panel_and_fixed_requests(self):
        manifest = (ROOT / 'headset/AndroidManifest.xml').read_text()
        self.assertNotIn('uses-permission', manifest)
        source = (ROOT / 'headset/src/com/pico4/regionswitch/Backend.java').read_text()
        self.assertIn('action.equals("recover-install")', source)
        self.assertIn('initialization_pending', source)
        self.assertNotIn('ServerSocket', source)

    def test_workflow_is_tag_bound_and_secrets_are_not_available_to_pr_tests(self):
        source = (ROOT / '.github/workflows/ci.yml').read_text()
        test, rest = source.split('\n  module:', 1)
        self.assertNotIn('secrets.', test)
        self.assertNotIn('pull_request_target', source)
        self.assertIn('refs/tags/${{ env.RELEASE_TAG }}', rest)
        self.assertIn('needs: test', rest)
        self.assertEqual(source.count('contents: write'), 1)
        self.assertIn('Refusing to replace published assets', source)
        for action in re.findall(r'uses: ([^\s]+)', source):
            self.assertRegex(action, r'^[^@]+@[0-9a-f]{40}$')

    def test_first_install_writes_no_system_partition_or_base_region_properties(self):
        source = (ROOT / 'headset/src/com/pico4/regionswitch/FirstInstall.java').read_text()
        self.assertNotIn('setprop ro.', source)
        self.assertNotIn('pm clear', source)
        self.assertNotIn('activate()', source)
        self.assertIn('install-identical-apks', source)
        installer = (ROOT / 'headset/module/customize.sh').read_text()
        self.assertIn('FirstInstall --prepare', installer)
        self.assertIn('FirstInstall --check-update', installer)


if __name__ == '__main__':
    unittest.main()
