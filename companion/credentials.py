"""Saved camera accounts protected by Windows DPAPI (current-user scope)."""

from __future__ import annotations

import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import tempfile


class _Blob(ctypes.Structure):
    _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]


def _windows() -> None:
    if os.name != 'nt':
        raise ValueError('Saved camera accounts are available on Windows only.')


def _crypt(data: bytes, *, decrypt: bool = False) -> bytes:
    _windows()
    crypt32 = ctypes.WinDLL('crypt32', use_last_error=True)
    kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
    convert = crypt32.CryptUnprotectData if decrypt else crypt32.CryptProtectData
    convert.argtypes = [ctypes.POINTER(_Blob), ctypes.c_void_p if decrypt else wintypes.LPCWSTR,
                        ctypes.POINTER(_Blob), ctypes.c_void_p, ctypes.c_void_p,
                        wintypes.DWORD, ctypes.POINTER(_Blob)]
    convert.restype = wintypes.BOOL
    free = kernel32.LocalFree
    free.argtypes = [ctypes.c_void_p]
    free.restype = ctypes.c_void_p
    buffer = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
    source = _Blob(len(data), buffer)
    result = _Blob()
    try:
        # UI_FORBIDDEN only: deliberately do not use LOCAL_MACHINE scope.
        if not convert(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(result)):
            raise RuntimeError('Windows could not unlock or protect the camera account.')
        return ctypes.string_at(result.data, result.size)
    finally:
        ctypes.memset(buffer, 0, len(data))
        if result.data:
            ctypes.memset(result.data, 0, result.size)
            free(result.data)


def _saved_path(host: str) -> Path:
    _windows()
    root = os.environ.get('LOCALAPPDATA')
    if not root:
        raise ValueError('Windows Local AppData is unavailable.')
    key = hashlib.sha256(host.encode('ascii')).hexdigest()
    return Path(root) / 'TapoPeek' / 'camera-accounts' / (key + '.dpapi')


def save_account(host: str, username: str, password: str) -> None:
    if not username or not password:
        raise ValueError('Camera username and password must not be empty.')
    path = _saved_path(host)
    payload = json.dumps([host, username, password], ensure_ascii=True).encode('ascii')
    protected = _crypt(payload)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, suffix='.tmp', delete=False) as temp:
            temp_path = Path(temp.name)
            temp.write(protected)
        temp_path.replace(path)
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


def load_account(host: str) -> tuple[str, str] | None:
    if os.name != 'nt':
        return None
    try:
        with _saved_path(host).open('rb') as store:
            data = store.read(65537)
    except FileNotFoundError:
        return None
    try:
        if not 0 < len(data) <= 65536:
            raise ValueError
        saved_host, username, password = json.loads(_crypt(data, decrypt=True).decode('ascii'))
        if saved_host != host or not all(isinstance(x, str) and x for x in (username, password)):
            raise ValueError
        return username, password
    except (ValueError, TypeError, RuntimeError):
        raise ValueError('Saved camera account could not be read. Use --prompt-account, or save it again.') from None


def forget_account(host: str) -> bool:
    try:
        _saved_path(host).unlink()
    except FileNotFoundError:
        return False
    return True
