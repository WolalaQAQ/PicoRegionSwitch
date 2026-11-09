"""Host regression tests for raw ZIP preservation and DEX input checks."""
import argparse
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

import build

TEST_ROOT = None


class Unseekable(io.BytesIO):
    def seek(self, *args):
        raise io.UnsupportedOperation('descriptor fixture')


class BuildTests(unittest.TestCase):
    def check_roundtrip(self, compression, descriptors):
        with tempfile.TemporaryDirectory(dir=TEST_ROOT) as folder:
            original, candidate = Path(folder) / 'input.jar', Path(folder) / 'output.jar'
            stream = Unseekable() if descriptors else io.BytesIO()
            with zipfile.ZipFile(stream, 'w', compression=compression) as archive:
                archive.comment = b'framework archive comment'
                archive.writestr('classes.dex', b'original target' * 200)
                info = zipfile.ZipInfo('META-INF/MANIFEST.MF', (2020, 1, 2, 3, 4, 6))
                info.compress_type = compression
                info.extra = b'\xfe\xca\x02\x00xy'
                info.comment = b'entry comment'
                info.external_attr = 0o100644 << 16
                archive.writestr(info, b'unchanged manifest\r\n')
                archive.writestr('classes2.dex', b'unchanged other dex' * 100)
            original.write_bytes(stream.getvalue())
            new = b'new target' * 900
            build.raw_repack(original, candidate, 'classes.dex', new)
            report = build.zip_audit(original, candidate, 'classes.dex')
            with zipfile.ZipFile(candidate) as archive:
                self.assertEqual(archive.read('classes.dex'), new)
                self.assertIsNone(archive.testzip())
            self.assertEqual(sum(row['local_record_equal'] for row in report), 2)
            self.assertEqual(sum(row['payload_equal'] for row in report), 2)

    def test_stored(self):
        self.check_roundtrip(zipfile.ZIP_STORED, False)

    def test_deflated(self):
        self.check_roundtrip(zipfile.ZIP_DEFLATED, False)

    def test_stored_descriptors(self):
        self.check_roundtrip(zipfile.ZIP_STORED, True)

    def test_deflated_descriptors(self):
        self.check_roundtrip(zipfile.ZIP_DEFLATED, True)

    def test_component_source_matches(self):
        self.assertEqual(len(build.components()), 7)

    def test_generated_dex_headers(self):
        for name in ['control.dex', 'patched.dex', 'helper-dex/classes.dex']:
            self.assertTrue(build.dex_header((TEST_ROOT / name).read_bytes())['sha1_valid'])

    def test_corrupt_dex_rejected(self):
        data = bytearray((TEST_ROOT / 'patched.dex').read_bytes())
        data[-1] ^= 1
        with self.assertRaises(AssertionError):
            build.dex_header(data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    TEST_ROOT = args.output
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(BuildTests))
    print(f'TESTS={result.testsRun}')
    print('RESULT=success' if result.wasSuccessful() else 'RESULT=failure')
    raise SystemExit(0 if result.wasSuccessful() else 1)
