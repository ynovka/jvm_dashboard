import importlib.util
import os
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("files", Path(__file__).parents[2] / "deployment/helpers/files.py")
files = importlib.util.module_from_spec(spec)
spec.loader.exec_module(files)


class Paths(unittest.TestCase):
    def test_paths_and_internal_names(self):
        for path in ("../secret", "/etc/passwd", "a/../b", "a//b", "a\\b", "", ".panel-upload/x", "a/./b"):
            with self.subTest(path=path), self.assertRaises(files.Failure):
                files.validate(path)
        self.assertEqual(files.validate("plugins/server.yml"), "plugins/server.yml")

    def test_invalid_upload_ids(self):
        for value in ("../x", "abc", "A" * 32, 4):
            with self.assertRaises(files.Failure):
                files.upload_id(value)


@unittest.skipUnless(os.name == "posix", "Linux openat2 integration")
class LinuxFiles(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        files.ROOT = self.temp.name
        files.root_fd = os.open(self.temp.name, os.O_RDONLY | os.O_DIRECTORY)
        for name in (".panel-upload", ".panel-trash"):
            Path(self.temp.name, name).mkdir()

    def tearDown(self):
        os.close(files.root_fd)
        self.temp.cleanup()

    def test_symlink_and_hardlink_are_blocked(self):
        Path(self.temp.name, "outside").symlink_to("/etc/passwd")
        with self.assertRaises(files.Failure):
            files.handle({"action": "read", "path": "outside"})
        Path(self.temp.name, "a").write_text("hello")
        os.link(Path(self.temp.name, "a"), Path(self.temp.name, "b"))
        with self.assertRaises(files.Failure):
            files.handle({"action": "read", "path": "a"})

    def test_etag_and_trash_restore(self):
        files.handle({"action": "write", "path": "a.txt", "text": "first"})
        old = files.handle({"action": "read", "path": "a.txt"})
        Path(self.temp.name, "a.txt").write_text("external")
        with self.assertRaises(files.Failure) as e:
            files.handle({"action": "write", "path": "a.txt", "text": "stale", "etag": old["etag"]})
        self.assertEqual(e.exception.code, "ETAG_CONFLICT")
        files.handle({"action": "trash", "path": "a.txt"})
        trash = files.handle({"action": "trashList", "path": ""})
        files.handle({"action": "restore", "path": "", "id": trash["items"][0]["id"]})
        self.assertEqual(Path(self.temp.name, "a.txt").read_text(), "external")

    def test_upload_retry_offset_and_finish(self):
        u = files.handle({"action": "uploadStart", "path": "app.jar", "size": 4})
        import base64
        part = {"action": "uploadChunk", "id": u["id"], "offset": 0, "data": base64.b64encode(b"test").decode()}
        self.assertEqual(files.handle(part)["offset"], 4)
        self.assertEqual(files.handle(part)["offset"], 4)
        files.handle({"action": "uploadFinish", "id": u["id"]})
        self.assertEqual(Path(self.temp.name, "app.jar").read_bytes(), b"test")

    def test_zip_slip_is_rejected_before_writing(self):
        import zipfile
        with zipfile.ZipFile(Path(self.temp.name, "bad.zip"), "w") as z:
            z.writestr("valid.txt", "safe")
            z.writestr("../outside.txt", "bad")
        with self.assertRaises(files.Failure):
            files.handle({"action": "extract", "path": "bad.zip"})
        self.assertFalse(Path(self.temp.name, "valid.txt").exists())


if __name__ == "__main__":
    unittest.main()
