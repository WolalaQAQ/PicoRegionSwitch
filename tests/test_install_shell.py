import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import region_switch as core


def bash():
    if os.name == 'nt':
        return str(Path(os.environ['ProgramFiles']) / 'Git/bin/bash.exe')
    return shutil.which('bash')


class InstallShellTests(unittest.TestCase):
    def run_plan(self, fail=False):
        plan = core.atomic_install_script({role: dict(package=package, size=17) for role, package in core.COMPONENTS.items()}, '/module/cn')
        mock = r'''
pm() {
  echo "$*" >> calls
  case "$1" in
    install-create) n=$(cat counter); n=$((n+1)); echo "$n" > counter; echo "Success: created install session [$n]" ;;
    install-write) if [ "$FAIL_WRITE" = yes ]; then echo 'Failure [mock]'; return 1; fi; echo "Success: streamed $3 bytes" ;;
    *) echo Success ;;
  esac
}
'''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'counter').write_text('0')
            result = subprocess.run([bash()], cwd=root, input=(mock + plan).encode(), capture_output=True,
                                    env={**os.environ, 'FAIL_WRITE': 'yes' if fail else 'no'}, timeout=10)
            return result, (root / 'calls').read_text()

    def test_actual_shell_commits_one_parent_after_all_eight_children(self):
        result, calls = self.run_plan()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls.count('install-write'), 8)
        self.assertEqual(calls.count('install-commit'), 1)
        self.assertNotIn('install-abandon', calls)
        self.assertIn(b'REGION_ATOMIC_INSTALL_SUCCESS', result.stdout)

    def test_failed_write_abandons_both_parent_and_child_without_committing(self):
        result, calls = self.run_plan(True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls.count('install-abandon'), 2)
        self.assertNotIn('install-commit', calls)

    def test_all_module_shell_scripts_parse(self):
        root = Path(__file__).resolve().parents[1]
        for path in list((root / 'headset/module').glob('*.sh')) + [root / 'tools/restore-first-install.sh']:
            result = subprocess.run([bash(), '-n', str(path)], capture_output=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
