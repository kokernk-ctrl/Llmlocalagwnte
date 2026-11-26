#!/usr/bin/env python3
"""Manual fixture precondition: model in Downloads; use an unlocked dedicated AVD."""
import argparse
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', default='emulator-5554')
p.add_argument('--model', default='qwen2.5-0.5b-instruct-q4_k_m.gguf')
p.add_argument('--already-preferred', action='store_true')
a = p.parse_args()
assert re.fullmatch(r'emulator-\d+', a.serial), 'Use a dedicated emulator'

def adb(*args):
    return subprocess.check_output([os.getenv('ADB', 'adb'), '-s', a.serial, *args], timeout=45).decode().strip()

def shell(*args):
    return adb('shell', *args)

def node(test, wait=75):
    deadline = time.monotonic() + wait
    seen = []
    while time.monotonic() < deadline:
        try:
            shell('uiautomator', 'dump', '/data/local/tmp/forge-chat-ui.xml')
            data = adb('exec-out', 'cat', '/data/local/tmp/forge-chat-ui.xml')
            seen = []
            for n in ET.fromstring(data).iter('node'):
                for s in (n.get('text'), n.get('content-desc')):
                    if s:
                        seen.append(s)
                        if test(s):
                            return n.attrib, s
        except (ET.ParseError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    raise AssertionError(f'Not found; visible labels: {seen}')

def tap(label):
    n, _ = node(lambda s: s == label)
    x, y, X, Y = map(int, re.findall(r'\d+', n['bounds']))
    shell('input', 'tap', str((x+X)//2), str((y+Y)//2))

assert shell('getprop', 'ro.build.version.sdk') == '33'
print(shell('am', 'start', '-W', '-n', 'dev.forgeai/.MainActivity'), flush=True)
if not a.already_preferred:
    tap('Models')
    tap('Import GGUF')
    tap('Show roots')
    tap('Downloads')
    tap(a.model)
    tap('Set preferred')
    node(lambda s: 'Readable · Preferred' == s)
    print('PASS: real Qwen GGUF imported and selected', flush=True)
tap('Chat')
tap('Load model')
node(lambda s: s == 'Ready', wait=360)
print('PASS: native engine loaded model', flush=True)
tap('Message')
shell('input', 'text', 'What%sis%s2%splus%s2%sReply%swith%sjust%sthe%snumber.')
shell('input', 'keyevent', '4')
tap('Send')
n, response = node(lambda s: re.fullmatch(r'\s*(?:Answer:\s*)?(?:\*\*)?4(?:\*\*)?[.!]?\s*', s) is not None, wait=720)
print(f'PASS: live model generated {response!r}', flush=True)
node(lambda s: s == 'Ready', wait=180)
tap('Unload')
node(lambda s: s == 'Not loaded')
print('PASS: native engine unloaded', flush=True)
