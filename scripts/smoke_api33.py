#!/usr/bin/env python3
"""Run against a dedicated, unlocked Android 13 test AVD with an empty FORGE AI registry."""
import argparse
import os
import re
import struct
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('apk', type=Path)
p.add_argument('--serial', required=True, help='Must be an emulator-* serial')
p.add_argument('--fixture-root', help='Use a separately installed SAF fixture provider instead of Downloads')
args = p.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    p.error('Use a dedicated local emulator, not a physical device.')


def adb(*cmd):
    return subprocess.check_output([os.environ.get('ADB', 'adb'), '-s', args.serial, *cmd],
                                   timeout=45, stderr=subprocess.STDOUT).decode().strip()


def shell(*cmd):
    return adb('shell', *cmd)


def tree():
    shell('uiautomator', 'dump', '/data/local/tmp/forge-ui.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/data/local/tmp/forge-ui.xml'))


def locate(text, exact=True, timeout=75):
    deadline = time.monotonic() + timeout
    seen = []
    while time.monotonic() < deadline:
        try:
            seen = []
            for node in tree().iter('node'):
                for label in (node.get('text', ''), node.get('content-desc', '')):
                    if label:
                        seen.append(label)
                    if (label == text if exact else text in label):
                        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '')))
                        return (x1 + x2) // 2, (y1 + y2) // 2
        except (subprocess.SubprocessError, ET.ParseError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError(f'Missing {text!r}; visible labels: {seen}')


def tap(text):
    x, y = locate(text)
    shell('input', 'tap', str(x), str(y))


def start():
    print(shell('am', 'start', '-W', '-n', 'dev.forgeai/.MainActivity'), flush=True)
    locate('Your offline model inventory.')


def check(label):
    print('PASS:', label, flush=True)


def fixtures(directory):
    def s(text):
        data = text.encode()
        return struct.pack('<Q', len(data)) + data

    data = b'GGUF' + struct.pack('<IQQ', 3, 1, 2)
    data += s('general.architecture') + struct.pack('<I', 8) + s('qwen2')
    data += s('general.file_type') + struct.pack('<II', 4, 0)
    data += s('test.weight') + struct.pack('<IQIQ', 1, 1, 0, 0)
    data += bytes(-len(data) % 32) + struct.pack('<f', 0.0)
    for name, blob in [('forge-qwen-fixture.gguf', data), ('forge-invalid.gguf', b'foo!' + data[4:])]:
        path = directory / name
        path.write_bytes(blob)
        target = f'/sdcard/Download/{name}'
        adb('push', str(path), target)
        shell('am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', f'file://{target}')


def choose(name):
    tap('Import GGUF')
    tap('Show roots')
    tap(args.fixture_root or 'Downloads')
    tap(name)


assert shell('getprop', 'sys.boot_completed') == '1', 'AVD not ready'
assert shell('getprop', 'ro.build.version.sdk') == '33', 'Expected API 33'
print('ABI:', shell('getprop', 'ro.product.cpu.abi'), flush=True)
print(adb('install', '-r', str(args.apk)), flush=True)
if not args.fixture_root:
    with tempfile.TemporaryDirectory() as tmp:
        fixtures(Path(tmp))
start()
check('APK installed and Dashboard rendered on API 33')
tap('Permissions')
locate('Document access is scoped to the model files you select.')
tap('Manage model access')
locate('No models registered')
check('Permissions and Model Manager navigation')
choose('forge-invalid.gguf')
locate('Not a GGUF file.')
locate('No models registered')
check('Invalid GGUF rejected without adding an entry')
tap('Dismiss')
choose('forge-qwen-fixture.gguf')
locate('qwen2 · F32', exact=False)
locate('Readable')
check('SAF import, metadata and readability')
tap('Set preferred')
locate('Readable · Preferred')
shell('am', 'force-stop', 'dev.forgeai')
start()
tap('Open Model Manager')
locate('Readable · Preferred')
check('SQLite selection and persistent SAF read grant survive force-stop and relaunch')
tap('Remove')
locate('Remove registration?')
tap('Cancel')
locate('Readable · Preferred')
tap('Remove')
# Click the dialog rather than the entry button, which is not in the active window.
tap('Remove')
locate('No models registered')
locate('The original file was not deleted.', exact=False)
if args.fixture_root:
    tap('Import GGUF')
    tap('Show roots')
    tap(args.fixture_root)
    locate('forge-qwen-fixture.gguf')
    shell('input', 'keyevent', '4')
else:
    shell('test', '-f', '/sdcard/Download/forge-qwen-fixture.gguf')
check('Removal requires confirmation and preserves the original document')
