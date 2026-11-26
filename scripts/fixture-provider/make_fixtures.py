"""Offline byte layout mirrored from smoke_api33.fixtures; never invokes adb."""
from pathlib import Path
import struct
import sys


def s(text):
    data = text.encode()
    return struct.pack("<Q", len(data)) + data


data = b"GGUF" + struct.pack("<IQQ", 3, 1, 2)
data += s("general.architecture") + struct.pack("<I", 8) + s("qwen2")
data += s("general.file_type") + struct.pack("<II", 4, 0)
data += s("test.weight") + struct.pack("<IQIQ", 1, 1, 0, 0)
data += bytes(-len(data) % 32) + struct.pack("<f", 0.0)
dest = Path(sys.argv[1])
(dest / "forge-qwen-fixture.gguf").write_bytes(data)
(dest / "forge-invalid.gguf").write_bytes(b"foo!" + data[4:])
