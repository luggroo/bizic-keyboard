#!/usr/bin/env python3
"""Verify in-place IME upgrades and the preferences actually read by the installed new APK."""

import argparse
from pathlib import Path
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET


PACKAGE = 'biz.tujia.ime'
COMPONENT = PACKAGE + '/.TujiaIme'
PREFERENCES = {
    'shared_prefs/keyboard.xml': {'spelling_tone_marks': ('boolean', 'false'),
                                 'ipa_completion_dialects': ('int', '3')},
    'shared_prefs/user_dictionary.xml': {'YE\tqvqvupgrade': ('int', '3'),
                                        'IPA\tŋa': ('int', '2')},
}
REPORT_PATH = 'files/last-crash.txt'
REPORT = 'Synthetic upgrade fixture · 土家语\n'.encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Disposable emulator with biz.tujia.ime absent')
    parser.add_argument('--adb', default='adb')
    for name in ('old-typing', 'old-handwriting', 'typing', 'handwriting'):
        parser.add_argument('--' + name, required=True, type=Path, metavar='APK')
    parser.add_argument('--fixture', type=Path, help='Matching instrumentation APK for runtime preference reads')
    parser.add_argument('--previous-typing', type=Path, help='Previous unified typing APK with the same signer')
    args = parser.parse_args()
    for apk in filter(None, (args.old_typing, args.old_handwriting, args.typing, args.handwriting,
                             args.fixture, args.previous_typing)):
        if not apk.is_file():
            parser.error(f'Missing APK {apk}')

    def adb(*argv, data=None, check=True):
        result = subprocess.run([args.adb, '-s', args.serial, *argv], input=data,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
        if check and result.returncode:
            raise RuntimeError(f'{argv}: {result.stdout.decode(errors="replace")}')
        return result.stdout

    def shell(*words):
        return adb('shell', '-T', shlex.join(words)).decode().strip()

    def private_file(path):
        return adb('exec-out', 'run-as', PACKAGE, 'cat', path)

    def seed_private_files(expected):
        blobs = {REPORT_PATH: REPORT}
        for path, prefs in expected.items():
            root = ET.Element('map')
            for name, (tag, value) in prefs.items():
                ET.SubElement(root, tag, name=name, value=value)
            blobs[path] = ET.tostring(root, encoding='utf-8', xml_declaration=True)
        shell('run-as', PACKAGE, 'mkdir', '-p', 'files', 'shared_prefs')
        for path, blob in blobs.items():
            cmd = shlex.join(['run-as', PACKAGE, 'sh', '-c', 'cat > ' + path])
            adb('shell', '-T', cmd, data=blob)

    def setting(name):
        return shell('settings', 'get', 'secure', name)

    def check_state(preferences):
        for path, expected in preferences.items():
            stored = {node.get('name'): (node.tag, node.get('value'))
                      for node in ET.fromstring(private_file(path))}
            for key, value in expected.items():
                if stored.get(key) != value:
                    raise AssertionError(f'{path} did not preserve {key!r}')
        if private_file(REPORT_PATH) != REPORT:
            raise AssertionError('Crash record changed')
        accepted = (COMPONENT, PACKAGE + '/' + PACKAGE + '.TujiaIme')
        enabled = {item.split(';', 1)[0] for item in setting('enabled_input_methods').split(':')}
        if setting('default_input_method') not in accepted or not enabled.intersection(accepted):
            raise AssertionError('Android did not retain the enabled and selected IME')

    def wait_for_registration():
        for _ in range(40):
            if 'TujiaIme' in shell('ime', 'list', '-a', '-s'):
                return
            time.sleep(0.5)
        raise RuntimeError('Android did not register the IME service')

    if shell('getprop', 'ro.kernel.qemu') != '1' and shell('getprop', 'ro.boot.qemu') != '1':
        parser.error('Select a disposable Android emulator')
    if shell('pm', 'list', 'packages', PACKAGE).splitlines().count('package:' + PACKAGE):
        parser.error('Use a fresh emulator. An existing installation was found')
    original = setting('default_input_method')
    if original in ('', 'null'):
        parser.error('The emulator needs an existing default keyboard')

    # The first two scenarios include both directions of the new flavor switch.
    scenarios = [
        (args.old_typing, [args.typing, args.handwriting, args.typing], "crash_recording", True),
        (args.old_handwriting, [args.handwriting], "crash_recording", False),
        (args.old_typing, [args.typing], "trial_recording", True),
        (args.old_handwriting, [args.handwriting], "trial_recording", False),
    ]
    if args.previous_typing:
        scenarios.append((args.previous_typing, [args.typing], None, False))
    fixture_package = PACKAGE + '.e2e'
    if args.fixture:
        if ('package:' + fixture_package) in shell('pm', 'list', 'packages', fixture_package).splitlines():
            parser.error('Use an emulator without an existing keyboard test fixture')
        adb('install', str(args.fixture))
    try:
        for old, updates, key, recording in scenarios:
            expected = {path: dict(prefs) for path, prefs in PREFERENCES.items()}
            if key:
                expected['shared_prefs/keyboard.xml'][key] = ('boolean', str(recording).lower())
            adb('install', str(old))
            try:
                seed_private_files(expected)
                wait_for_registration()
                shell('ime', 'enable', COMPONENT)
                shell('ime', 'set', COMPONENT)
                check_state(expected)
                for new in updates:
                    result = adb('install', '-r', str(new)).decode().strip()
                    if 'Success' not in result.splitlines():
                        raise AssertionError(f'{new.name}: {result}')
                    wait_for_registration()
                    check_state(expected)
                    if args.fixture:
                        runtime = shell('am', 'instrument', '-w', '-r', '-e', 'expectedRecording',
                                        str(recording).lower(), fixture_package + '/.PrivateStorageTest')
                        if 'INSTRUMENTATION_RESULT: outcome=PASS' not in runtime.splitlines():
                            raise AssertionError(runtime)
                    print(f'PASS {old.name} → {new.name}  •  {key or "unset"}='
                          f'{str(recording).lower()}  •  selected IME, settings, user dictionary and crash '
                          'record preserved', flush=True)
                    old = new
            finally:
                try:
                    shell('ime', 'set', original)
                finally:
                    adb('uninstall', PACKAGE)
    finally:
        if args.fixture:
            adb('uninstall', fixture_package)


if __name__ == '__main__':
    main()
