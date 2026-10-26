#!/usr/bin/env python3
"""Validate the shared Play/F-Droid listing without third-party Python packages."""
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[1]
LOCALES = ('en-US', 'es-ES', 'de-DE', 'fr-FR', 'it-IT', 'pt-BR', 'pt-PT', 'tr-TR')
IMAGE_SUFFIXES = ('.png', '.jpg', '.jpeg')
SOF_MARKERS = (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
               0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)


def normalized(path):
    """Compare text content without platform-specific CRLF/LF differences."""
    return path.read_bytes().replace(b'\r\n', b'\n')


def image_size(path):
    """Return (width, height) for a PNG or JPEG file using only the standard library."""
    data = path.read_bytes()
    if len(data) >= 24 and data[:8] == b'\x89PNG\r\n\x1a\n':
        return struct.unpack('>II', data[16:24])
    if data[:2] == b'\xff\xd8':
        offset = 2
        while offset + 4 <= len(data):
            if data[offset] != 0xFF:
                offset += 1
                continue
            while offset < len(data) and data[offset] == 0xFF:
                offset += 1
            if offset >= len(data):
                break
            marker = data[offset]
            offset += 1
            if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
                continue
            if offset + 2 > len(data):
                break
            length = struct.unpack('>H', data[offset:offset + 2])[0]
            if marker in SOF_MARKERS:
                assert offset + 7 <= len(data), f'{path}: truncated JPEG header'
                height, width = struct.unpack('>HH', data[offset + 3:offset + 7])
                return width, height
            offset += length
    raise AssertionError(f'{path}: unsupported or malformed image')


def main():
    properties = dict(re.findall(r'^(VERSION_CODE|VERSION_NAME)=(\S+)$', (ROOT / 'version.properties').read_text(encoding='utf-8'), re.M))
    code = properties['VERSION_CODE']
    listing = ROOT / 'fastlane/metadata/android'
    assert (listing / 'en-US/images/phoneScreenshots').is_dir(), 'default listing needs screenshots'
    for locale in LOCALES:
        directory = listing / locale
        for name, limit in [('title.txt', 50), ('short_description.txt', 80), ('full_description.txt', 4000), (f'changelogs/{code}.txt', 500)]:
            text = (directory / name).read_text(encoding='utf-8').strip()
            assert text and len(text) <= limit, f'{locale}/{name}: empty or exceeds {limit} characters'
            assert not any(ord(c) < 32 and c not in '\n\t' for c in text), f'{locale}/{name}: control character'
            if name == 'short_description.txt':
                assert not text.endswith('.'), f'{locale}: short description has a trailing dot'
        screens_dir = directory / 'images/phoneScreenshots'
        if not screens_dir.is_dir():
            continue
        screens = sorted(p for p in screens_dir.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES)
        assert 2 <= len(screens) <= 8, f'{locale}: expected 2-8 phone screenshots'
        for screen in screens:
            width, height = image_size(screen)
            assert 320 <= min(width, height) and max(width, height) <= 3840, f'{screen}: outside 320-3840 px'
            assert 0.5 <= width / height <= 2.0, f'{screen}: aspect ratio outside 1:2-2:1'
    icon = listing / 'en-US/images/icon.png'
    header = icon.read_bytes()[:24]
    assert header[:8] == b'\x89PNG\r\n\x1a\n', 'icon must be PNG'
    assert struct.unpack('>II', header[16:24]) == (512, 512), 'shared icon must be 512x512'
    assert normalized(ROOT / 'LICENSE') == normalized(ROOT / 'app/src/main/res/raw/gpl_3_0.txt'), 'bundled GPL differs from root LICENSE'
    print(f'PASS: {len(LOCALES)} locales, version {properties["VERSION_NAME"]} ({code}), shared icon and Play/F-Droid screenshots')


if __name__ == '__main__':
    main()
