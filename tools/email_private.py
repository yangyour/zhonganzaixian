"""Read the locally encrypted mailbox credential without logging or writing plaintext."""
import ctypes
from ctypes import wintypes
import os
from pathlib import Path


def windows_password(path: Path) -> str:
    if os.name != 'nt':
        raise ValueError('Local DPAPI mailbox credentials require the current Windows user')
    class Blob(ctypes.Structure):
        _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_byte))]
    raw = path.read_bytes()
    if not 1 <= len(raw) <= 16384:
        raise ValueError('Invalid encrypted mailbox credential')
    buffer = ctypes.create_string_buffer(raw)
    source = Blob(len(raw), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    target = Blob()
    crypt32 = ctypes.WinDLL('crypt32', use_last_error=True)
    kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
    operation = crypt32.CryptUnprotectData
    operation.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                          ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    operation.restype = wintypes.BOOL
    kernel32.LocalFree.argtypes = [ctypes.c_void_p]
    kernel32.LocalFree.restype = ctypes.c_void_p
    if not operation(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise ValueError('Current Windows user cannot decrypt mailbox credential')
    try:
        return ctypes.string_at(target.data, target.size).decode('utf-8')
    finally:
        kernel32.LocalFree(target.data)
