#!/usr/bin/env python3
"""Validate the shared Play/F-Droid listing without third-party Python packages."""
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[1]
LOCALES = ('en-US', 'es-ES', 'de-DE', 'fr-FR', 'it-IT', 'pt-BR', 'pt-PT', 'tr-TR')


def main():
    properties = dict(re.findall(r'^(VERSION_CODE|VERSION_NAME)=(\S+)$', (ROOT / 'version.properties').read_text(encoding='utf-8'), re.M))
    code = properties['VERSION_CODE']
    listing = ROOT / 'fastlane/metadata/android'
    for locale in LOCALES:
        directory = listing / locale
        for name, limit in [('title.txt', 50), ('short_description.txt', 80), ('full_description.txt', 4000), (f'changelogs/{code}.txt', 500)]:
            text = (directory / name).read_text(encoding='utf-8').strip()
            assert text and len(text) <= limit, f'{locale}/{name}: empty or exceeds {limit} characters'
            assert not any(ord(c) < 32 and c not in '\n\t' for c in text), f'{locale}/{name}: control character'
            if name == 'short_description.txt':
                assert not text.endswith('.'), f'{locale}: short description has a trailing dot'
    icon = listing / 'en-US/images/icon.png'
    header = icon.read_bytes()[:24]
    assert header[:8] == b'\x89PNG\r\n\x1a\n', 'icon must be PNG'
    assert struct.unpack('>II', header[16:24]) == (512, 512), 'shared icon must be 512x512'
    screens = list((listing / 'en-US/images/phoneScreenshots').glob('*.png'))
    assert len(screens) >= 2, 'default listing needs at least two screenshots'
    assert (ROOT / 'LICENSE').read_bytes() == (ROOT / 'app/src/main/res/raw/gpl_3_0.txt').read_bytes(), 'bundled GPL differs from root LICENSE'
    print(f'PASS: {len(LOCALES)} locales, version {properties["VERSION_NAME"]} ({code}), shared icon and screenshots')


if __name__ == '__main__':
    main()
