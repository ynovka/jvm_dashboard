#!/usr/bin/env python3
"""One request per sandboxed process. All user paths resolve through Linux openat2.

Invoked by bwrap with a per-application UID and volume; no network or Docker socket.
Internal upload/trash names are never accepted as user paths.
"""
import base64
import ctypes
import errno
import hashlib
import io
import json
import os
import re
import shutil
import stat
import sys
import time
import uuid
import zipfile

ROOT = "/data"
LIMIT = 1024 * 1024
MAX_UPLOAD = 8 * 1024**3
libc = ctypes.CDLL(None, use_errno=True) if sys.platform == "linux" else None


class Failure(Exception):
    def __init__(self, code, message, status=400):
        self.code, self.message, self.status = code, message, status


class OpenHow(ctypes.Structure):
    _fields_ = [("flags", ctypes.c_uint64), ("mode", ctypes.c_uint64), ("resolve", ctypes.c_uint64)]


def validate(path, empty=False):
    if not isinstance(path, str) or len(path) > 1024 or "\x00" in path or "\\" in path or path.startswith("/"):
        raise Failure("INVALID_PATH", "Недопустимый путь")
    if path == "" and empty:
        return path
    if any(p in ("", ".", "..") or p.startswith(".panel-") for p in path.split("/")):
        raise Failure("INVALID_PATH", "Недопустимый путь")
    return path


def opened(path, flags=os.O_RDONLY, mode=0, internal=False):
    if not internal:
        validate(path, empty=True)
    how = OpenHow(flags | os.O_CLOEXEC | os.O_NOFOLLOW, mode, 0x10 | 0x04 | 0x02)
    fd = libc.syscall(437, root_fd, (path or ".").encode(), ctypes.byref(how), ctypes.sizeof(how))
    if fd < 0:
        e = ctypes.get_errno()
        if e in (errno.ELOOP, errno.EXDEV):
            raise Failure("UNSAFE_PATH", "Ссылки и выход за пределы volume запрещены")
        raise OSError(e, os.strerror(e))
    return fd


def parent(path):
    validate(path)
    directory, _, name = path.rpartition("/")
    return opened(directory, os.O_RDONLY | os.O_DIRECTORY), name


def regular(fd):
    s = os.fstat(fd)
    if not stat.S_ISREG(s.st_mode) or s.st_nlink != 1:
        raise Failure("UNSAFE_FILE", "Допустимы только обычные файлы без hardlink")
    return s


def etag(fd):
    s = regular(fd)
    return hashlib.sha256(f"{s.st_dev}:{s.st_ino}:{s.st_size}:{s.st_mtime_ns}:{s.st_ctime_ns}".encode()).hexdigest()


def atomic(path, data, expected=None):
    pfd, name = parent(path)
    temp = ".panel-write-" + uuid.uuid4().hex
    try:
        try:
            fd = opened(path)
            try:
                current = etag(fd)
                if expected is None or expected != current:
                    raise Failure("ETAG_CONFLICT", "Файл изменён. Обновите его перед сохранением", 409)
            finally:
                os.close(fd)
        except FileNotFoundError:
            if expected not in (None, ""):
                raise Failure("ETAG_CONFLICT", "Файл удалён", 409)
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=pfd)
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(data)
                f.flush()
                os.fsync(f.fileno())
            # Recheck directly before rename. The worker is the sole panel writer.
            if expected:
                check = opened(path)
                try:
                    if etag(check) != expected:
                        raise Failure("ETAG_CONFLICT", "Файл изменён во время сохранения", 409)
                finally:
                    os.close(check)
            os.replace(temp, name, src_dir_fd=pfd, dst_dir_fd=pfd)
            os.fsync(pfd)
        finally:
            try:
                os.unlink(temp, dir_fd=pfd)
            except FileNotFoundError:
                pass
        fd = opened(path)
        try:
            return {"etag": etag(fd)}
        finally:
            os.close(fd)
    finally:
        os.close(pfd)


def upload_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{32}", value):
        raise Failure("INVALID_UPLOAD", "Недопустимый ID загрузки")
    return value


def internal_json(path):
    fd = opened(path, internal=True)
    with os.fdopen(fd) as f:
        return json.load(f)


def list_tree(path=""):
    fd = opened(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        rows = []
        with os.scandir(fd) as entries:
            for e in entries:
                if e.name.startswith(".panel-"):
                    continue
                s = e.stat(follow_symlinks=False)
                kind = "directory" if stat.S_ISDIR(s.st_mode) else "file" if stat.S_ISREG(s.st_mode) and s.st_nlink == 1 else "unsupported"
                rows.append({"name": e.name, "type": kind, "size": s.st_size, "modified": int(s.st_mtime * 1000)})
                if len(rows) >= 10000:
                    raise Failure("DIRECTORY_LIMIT", "Слишком много файлов в каталоге")
        return {"items": sorted(rows, key=lambda e: (e["type"] != "directory", e["name"].lower()))}
    finally:
        os.close(fd)


def walk(path, max_files=10000):
    result = []
    pending = [path]
    while pending:
        current = pending.pop()
        for e in list_tree(current)["items"]:
            child = current + "/" + e["name"] if current else e["name"]
            if e["type"] == "directory":
                pending.append(child)
            elif e["type"] == "file":
                result.append((child, e["size"]))
            else:
                raise Failure("UNSAFE_FILE", "Каталог содержит ссылку или специальный файл")
            if len(result) + len(pending) > max_files:
                raise Failure("FILE_LIMIT", "Превышен лимит файлов")
    return result


def handle(b):
    action = b.get("action")
    path = b.get("path", "")
    if action == "purge":
        # Only used by DELETE; the sandbox exposes no other application volume.
        for e in os.scandir(ROOT):
            if e.is_dir(follow_symlinks=False):
                shutil.rmtree(e.path)
            else:
                os.unlink(e.path)
        return {}
    validate(path, empty=action in ("list", "archive", "trashList", "restore", "uploadStatus", "uploadChunk", "uploadCancel", "uploadFinish"))
    if action == "list":
        return list_tree(path)
    if action in ("read", "download"):
        fd = opened(path)
        try:
            s = regular(fd)
            version = etag(fd)
            if b.get("etag") and b["etag"] != version:
                raise Failure("ETAG_CONFLICT", "Файл изменён при скачивании", 409)
            if b.get("metadataOnly"):
                return {"size": s.st_size, "etag": version}
            offset = int(b.get("offset", 0))
            if offset < 0 or offset > s.st_size:
                raise Failure("INVALID_OFFSET", "Недопустимое смещение")
            os.lseek(fd, offset, os.SEEK_SET)
            data = os.read(fd, LIMIT)
            if action == "read":
                if s.st_size > LIMIT or b"\0" in data:
                    raise Failure("BINARY_FILE", "Большой или бинарный файл: используйте скачивание")
                try:
                    return {"text": data.decode("utf-8"), "etag": version, "size": s.st_size}
                except UnicodeDecodeError:
                    raise Failure("BINARY_FILE", "Файл не является UTF-8 текстом")
            return {"data": base64.b64encode(data).decode(), "offset": offset + len(data), "size": s.st_size, "etag": version}
        finally:
            os.close(fd)
    if action == "write":
        data = b.get("text", "").encode()
        if len(data) > LIMIT:
            raise Failure("TEXT_LIMIT", "Текстовый файл больше 1 MiB")
        return atomic(path, data, b.get("etag"))
    if action == "mkdir":
        fd, name = parent(path)
        try:
            os.mkdir(name, mode=0o700, dir_fd=fd)
            os.fsync(fd)
        finally:
            os.close(fd)
        return {}
    if action in ("rename", "move", "copy", "trash"):
        source = opened(path)
        try:
            s = os.fstat(source)
            if stat.S_ISREG(s.st_mode):
                regular(source)
            elif not stat.S_ISDIR(s.st_mode):
                raise Failure("UNSAFE_FILE", "Специальный файл")
            if action == "copy":
                if not stat.S_ISREG(s.st_mode) or s.st_size > 64 * LIMIT:
                    raise Failure("COPY_LIMIT", "Копирование: обычный файл до 64 MiB")
                data = bytearray()
                while chunk := os.read(source, LIMIT):
                    data.extend(chunk)
                return atomic(validate(b["target"]), data)
            src, name = parent(path)
            try:
                if action == "trash":
                    tid = uuid.uuid4().hex
                    dst = opened(".panel-trash", os.O_RDONLY | os.O_DIRECTORY, internal=True)
                    target = tid
                    meta = os.open(tid + ".json", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600, dir_fd=dst)
                    with os.fdopen(meta, "w") as f:
                        json.dump({"path": path, "deleted": int(time.time() * 1000), "id": tid}, f)
                else:
                    dst, target = parent(validate(b["target"]))
                try:
                    # renameat2 RENAME_NOREPLACE protects the destination even with a racing container.
                    if libc.syscall(316, src, name.encode(), dst, target.encode(), 1) < 0:
                        e = ctypes.get_errno()
                        raise OSError(e, os.strerror(e))
                    os.fsync(src)
                    os.fsync(dst)
                finally:
                    os.close(dst)
            finally:
                os.close(src)
        finally:
            os.close(source)
        return {}
    if action == "trashList":
        return {"items": [internal_json(".panel-trash/" + n) for n in os.listdir(ROOT + "/.panel-trash") if re.fullmatch(r"[a-f0-9]{32}\.json", n)]}
    if action == "restore":
        tid = upload_id(b["id"])
        meta = internal_json(".panel-trash/" + tid + ".json")
        dst, name = parent(meta["path"])
        src = opened(".panel-trash", os.O_RDONLY | os.O_DIRECTORY, internal=True)
        try:
            if libc.syscall(316, src, tid.encode(), dst, name.encode(), 1) < 0:
                e = ctypes.get_errno()
                raise OSError(e, os.strerror(e))
            os.unlink(tid + ".json", dir_fd=src)
            os.fsync(dst)
        finally:
            os.close(src)
            os.close(dst)
        return {}
    if action == "uploadStart":
        size = int(b["size"])
        if not 0 <= size <= MAX_UPLOAD:
            raise Failure("UPLOAD_LIMIT", "Размер загрузки: до 8 GiB")
        uid = uuid.uuid4().hex
        folder = ROOT + "/.panel-upload/"
        with open(folder + uid + ".json", "x") as f:
            json.dump({"path": path, "size": size, "etag": b.get("etag"), "created": time.time()}, f)
        with open(folder + uid + ".part", "xb"):
            pass
        return {"id": uid, "offset": 0}
    if action in ("uploadChunk", "uploadStatus", "uploadFinish", "uploadCancel"):
        uid = upload_id(b["id"])
        folder = ".panel-upload/"
        meta = internal_json(folder + uid + ".json")
        if time.time() - meta["created"] > 86400:
            raise Failure("UPLOAD_EXPIRED", "Загрузка истекла")
        fd = opened(folder + uid + ".part", os.O_RDWR, internal=True)
        try:
            regular(fd)
            size = os.fstat(fd).st_size
            if action == "uploadStatus":
                return {"offset": size, "size": meta["size"], "path": meta["path"]}
            if action == "uploadChunk":
                data = base64.b64decode(b["data"], validate=True)
                offset = int(b["offset"])
                if len(data) > LIMIT or offset < 0 or offset + len(data) > meta["size"]:
                    raise Failure("INVALID_CHUNK", "Недопустимый размер части")
                if offset < size and os.pread(fd, len(data), offset) == data:
                    return {"offset": offset + len(data)}
                if offset != size:
                    raise Failure("UPLOAD_OFFSET", "Обновите смещение загрузки", 409)
                os.lseek(fd, offset, os.SEEK_SET)
                remaining = memoryview(data)
                while remaining:
                    remaining = remaining[os.write(fd, remaining):]
                os.fsync(fd)
                return {"offset": offset + len(data)}
            if action == "uploadFinish":
                if size != meta["size"]:
                    raise Failure("UPLOAD_INCOMPLETE", "Загрузка не завершена", 409)
                dst, name = parent(meta["path"])
                src = opened(".panel-upload", os.O_RDONLY | os.O_DIRECTORY, internal=True)
                try:
                    try:
                        check = opened(meta["path"])
                        try:
                            if not meta.get("etag") or etag(check) != meta["etag"]:
                                raise Failure("ETAG_CONFLICT", "Файл изменён. Загрузка сохранена для повторения", 409)
                        finally:
                            os.close(check)
                    except FileNotFoundError:
                        if meta.get("etag"):
                            raise Failure("ETAG_CONFLICT", "Исходный файл удалён", 409)
                    os.replace(uid + ".part", name, src_dir_fd=src, dst_dir_fd=dst)
                    os.unlink(uid + ".json", dir_fd=src)
                    os.fsync(dst)
                finally:
                    os.close(src)
                    os.close(dst)
                return {"path": meta["path"]}
        finally:
            os.close(fd)
        os.unlink(ROOT + "/" + folder + uid + ".part")
        os.unlink(ROOT + "/" + folder + uid + ".json")
        return {}
    if action == "archive":
        files = walk(path)
        if sum(size for _, size in files) > 64 * LIMIT:
            raise Failure("ARCHIVE_LIMIT", "Архивирование: до 64 MiB")
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED) as z:
            for name, _ in files:
                fd = opened(name)
                try:
                    regular(fd)
                    with os.fdopen(fd, "rb") as f:
                        z.writestr(name, f.read(64 * LIMIT + 1))
                except BaseException:
                    raise
        target = b.get("target", "download.zip")
        return atomic(validate(target), data.getvalue())
    if action == "extract":
        fd = opened(path)
        with os.fdopen(fd, "rb") as f, zipfile.ZipFile(f) as z:
            entries = z.infolist()
            if len(entries) > 10000 or sum(e.file_size for e in entries) > 64 * LIMIT:
                raise Failure("ARCHIVE_LIMIT", "Распаковка: до 10000 файлов / 64 MiB")
            for e in entries:
                validate(e.filename.rstrip("/"))
                mode = e.external_attr >> 16
                if stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)) or e.flag_bits & 1:
                    raise Failure("UNSAFE_ARCHIVE", "Архив содержит ссылки, устройства или шифрование")
                if e.compress_size and e.file_size / e.compress_size > 200:
                    raise Failure("ARCHIVE_BOMB", "Слишком высокая степень сжатия")
            for e in entries:
                parts = e.filename.rstrip("/").split("/")
                for i in range(1, len(parts) + (1 if e.is_dir() else 0)):
                    directory = "/".join(parts[:i])
                    try:
                        handle({"action": "mkdir", "path": directory})
                    except FileExistsError:
                        d = opened(directory, os.O_DIRECTORY)
                        os.close(d)
                if not e.is_dir():
                    data = z.read(e)
                    if len(data) != e.file_size:
                        raise Failure("ARCHIVE_SIZE", "Размер записи не совпадает")
                    atomic(e.filename, data)
        return {}
    raise Failure("INVALID_ACTION", "Неизвестное файловое действие")


def main():
    if sys.platform != "linux":
        raise SystemExit("Linux with openat2 required")
    global root_fd
    root_fd = os.open(ROOT, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        request = json.loads(sys.stdin.buffer.read(2 * LIMIT + 1))
        # Abandoned chunks and trash also count against the hard project quota.
        for folder, ttl in ((".panel-upload", 86400), (".panel-trash", 7 * 86400)):
            for e in os.scandir(ROOT + "/" + folder):
                if time.time() - e.stat(follow_symlinks=False).st_mtime > ttl:
                    if e.is_dir(follow_symlinks=False):
                        shutil.rmtree(e.path)
                    else:
                        os.unlink(e.path)
        print(json.dumps({"ok": True, **handle(request)}, ensure_ascii=False))
    except Failure as e:
        print(json.dumps({"ok": False, "code": e.code, "message": e.message, "status": e.status}, ensure_ascii=False))
    except OSError as e:
        code = "DISK_QUOTA" if e.errno in (errno.EDQUOT, errno.ENOSPC) else "FILE_EXISTS" if e.errno == errno.EEXIST else "NOT_FOUND" if e.errno == errno.ENOENT else "FILE_ERROR"
        print(json.dumps({"ok": False, "code": code, "message": os.strerror(e.errno), "status": 409 if code == "FILE_EXISTS" else 400}))
    except (ValueError, KeyError, zipfile.BadZipFile):
        print(json.dumps({"ok": False, "code": "INVALID_REQUEST", "message": "Неверная файловая операция"}))
    finally:
        os.close(root_fd)


if __name__ == "__main__":
    main()
