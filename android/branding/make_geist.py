"""Regenerate Geist fonts and Geist UI VectorDrawables from pinned, checksum-verified sources.

    python3 branding/make_geist.py [fonts|icons|all]

Uses only the standard library. Fonts retain complete upstream character coverage.
The app bundles native vectors, with no React dependency or runtime downloads.
"""
from pathlib import Path
import hashlib
import html
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

APP = Path(__file__).resolve().parents[1] / 'app/src/main'
FONT_REV = 'b5d164a0c066c2c9bfcd5ab65dd8244551f0ac06'
ICON_REV = '5937509f49f82f82e7b28147ec729a9cfa6d0483'

# TV viewing needs a stronger stroke than the upstream 1.5. Dense and naturally broad silhouettes
# are optically reduced around the canvas centre; filled transport glyphs keep their lighter original rim.
TV_STROKE = 2.0
OPTICAL_SCALE = {'settings': .88, 'film': .90, 'home': .94, 'layers': .94, 'compass': .96,
                 'globe': .94, 'volume-2': .94, 'info': .94, 'rotate-cw': .94, 'delete': .94}


def fetch(repo, revision, path, digest):
    url = f'https://raw.githubusercontent.com/{repo}/{revision}/{path}'
    with urllib.request.urlopen(url, timeout=30) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f'Unexpected upstream checksum: {url}')
    return data


def fonts():
    faces = {
        'Regular': '85a1c6b18a6b0a06dfe9fd4f6d6a5d4979f74ec861eaef4bc7868b5492b8a117',
        'Medium': '3a3b36f0d0b981f4857f7f00eeef4a5ee123605575d362ab31ee7c19e3d11f2f',
        'SemiBold': 'b9f52322d6d20a7ff1e7fe1310ff12ec851aebb83d24f8b4f1d4149298f489b2',
    }
    for face, digest in faces.items():
        (APP / f'res/font/geist_{face.lower()}.ttf').write_bytes(fetch(
            'vercel/geist-font', FONT_REV, f'fonts/Geist/ttf/Geist-{face}.ttf', digest))
    (APP / 'assets/notices/geist.txt').write_bytes(fetch('vercel/geist-font', FONT_REV, 'LICENSE.txt',
        '930853ee1daa68554d9e35c8a9175affb74f699fad9a5da6ee5ebe76379d9137'))


# Stable resource names retain all control wiring. Shared player icons use app resource overrides.
ICONS = {
    'ic_tv_add': 'plus', 'ic_tv_audio': 'volume-2', 'ic_tv_check': 'check',
    'ic_tv_chevron': 'chevron-right', 'ic_tv_clock': 'clock', 'ic_tv_close': 'x',
    'ic_tv_delete': 'trash-2', 'ic_tv_backspace': 'delete', 'ic_tv_download': 'download', 'ic_tv_edit': 'edit-2',
    'ic_tv_explore': 'compass', 'ic_tv_extension': 'package', 'ic_tv_heart': 'heart-fill',
    'ic_tv_heart_outline': 'heart', 'ic_tv_home': 'home', 'ic_tv_info': 'info',
    'ic_tv_language': 'globe', 'ic_tv_library': 'layers', 'ic_tv_link': 'link',
    'ic_tv_list': 'list', 'ic_tv_more': 'more-horizontal', 'ic_tv_movie': 'film',
    'ic_tv_offline': 'alert-triangle', 'ic_tv_play': 'play-fill', 'ic_tv_refresh': 'rotate-cw',
    'ic_tv_search': 'search', 'ic_tv_settings': 'settings', 'ic_tv_unwatched': 'eye-off',
    'ic_play': 'play-fill', 'ic_pause': 'pause-fill', 'ic_forward': 'fast-forward',
    'ic_rewind': 'rewind', 'ic_chevron_left': 'chevron-left', 'ic_chevron_right': 'chevron-right',
    'ic_check': 'check', 'ic_menu': 'menu', 'ic_alert': 'alert-triangle',
}

# These concepts are absent upstream. Original drawings share its 24-unit grid and round 1.5 stroke.
SUPPLEMENTS = {
    'ic_tv_keyboard': '<rect x="2" y="5" width="20" height="14" rx="2"/>'
        '<path d="M6 9h.01M10 9h.01M14 9h.01M18 9h.01M6 12h.01M10 12h.01M14 12h.01M18 12h.01M7 16h10"/>',
    'ic_tv_subtitles': '<rect x="2" y="5" width="20" height="14" rx="2"/>'
        '<path d="M6 11h4M14 11h4M6 15h7M16 15h2"/>',
}


def shape_path(node):
    a, tag = node.attrib, node.tag
    if tag == 'path':
        return a['d']
    if tag in ('polygon', 'polyline'):
        points = re.findall(r'[-+]?\d*\.?\d+', a['points'])
        return 'M' + ','.join(points[:2]) + ' ' + ' '.join(
            'L' + ','.join(points[i:i+2]) for i in range(2, len(points), 2)) + ('z' if tag == 'polygon' else '')
    if tag == 'circle':
        x, y, r = (float(a[k]) for k in ('cx', 'cy', 'r'))
        return f'M{x-r:g},{y:g}a{r:g},{r:g} 0 1,0 {2*r:g},0a{r:g},{r:g} 0 1,0 {-2*r:g},0z'
    if tag == 'rect':
        x, y = float(a.get('x', 0)), float(a.get('y', 0))
        w, h = float(a['width']), float(a['height'])
        rx = min(float(a.get('rx', a.get('ry', 0))), w/2)
        ry = min(float(a.get('ry', a.get('rx', 0))), h/2)
        if not rx or not ry:
            return f'M{x:g},{y:g}h{w:g}v{h:g}h{-w:g}z'
        return (f'M{x+rx:g},{y:g}H{x+w-rx:g}a{rx:g},{ry:g} 0 0,1 {rx:g},{ry:g}V{y+h-ry:g}'
            f'a{rx:g},{ry:g} 0 0,1 {-rx:g},{ry:g}H{x+rx:g}a{rx:g},{ry:g} 0 0,1 {-rx:g},{-ry:g}'
            f'V{y+ry:g}a{rx:g},{ry:g} 0 0,1 {rx:g},{-ry:g}z')
    raise ValueError(f'Unsupported SVG shape: {tag}')


def expanded_path(path):
    """Expand compact SVG arc flags: Android's PathParser expects separate numbers for each flag."""
    arity = {'m': 2, 'l': 2, 'h': 1, 'v': 1, 'c': 6, 's': 4, 'q': 4, 't': 2, 'a': 7}
    number = re.compile(r'[+-]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][+-]?\d+)?')
    index, command, parts = 0, None, []
    while index < len(path):
        if path[index] in ' ,\t\r\n':
            index += 1
            continue
        if path[index].isalpha():
            command = path[index]
            index += 1
            if command.lower() == 'z':
                parts.append(command)
                command = None
                continue
        if command is None or command.lower() not in arity:
            raise ValueError(f'Invalid SVG path: {path}')
        values = []
        for parameter in range(arity[command.lower()]):
            while index < len(path) and path[index] in ' ,\t\r\n':
                index += 1
            if command.lower() == 'a' and parameter in (3, 4):
                if index >= len(path) or path[index] not in '01':
                    raise ValueError(f'Invalid arc flag: {path}')
                value = path[index]
            else:
                match = number.match(path, index)
                if not match:
                    raise ValueError(f'Invalid path parameter: {path[index:]}')
                value = match[0]
            values.append(value)
            index += len(value)
        parts.append(command + ','.join(values))
        if command in ('M', 'm'):
            command = 'L' if command == 'M' else 'l'
    return ' '.join(parts)


def vector(svg, attribution, scale=1.0):
    root = ET.fromstring(svg)
    if root.get('viewBox') != '0 0 24 24':
        raise ValueError('Unexpected icon viewport')
    filled = '--geist-fill:currentColor' in root.get('style', '')
    paths = []
    for node in root:
        # Fail instead of silently losing geometry or painting opaque cutouts.
        if any(k in node.attrib for k in ('transform', 'mask', 'clip-path', 'style')):
            raise ValueError(f'Unsupported SVG attributes: {node.attrib}')
        fill = node.get('fill', root.get('fill', 'black'))
        if fill == 'var(--geist-fill)':
            fill = 'currentColor' if filled else 'none'
        if fill not in ('none', 'currentColor', 'black'):
            raise ValueError(f'Unsupported fill: {fill}')
        color = '#00000000' if fill == 'none' else '#FFFFFFFF'
        attributes = f'android:fillColor="{color}"'
        if node.get('stroke', root.get('stroke')) != 'none':
            width = root.get('stroke-width', '1.5') if filled else f'{TV_STROKE / scale:g}'
            attributes += (f' android:strokeColor="#FFFFFFFF" android:strokeWidth="{width}"'
                ' android:strokeLineCap="round" android:strokeLineJoin="round"')
        paths.append(f'    <path {attributes} android:pathData="{html.escape(expanded_path(shape_path(node)), quote=True)}" />')
    geometry = '\n'.join(paths)
    if scale != 1:
        geometry = (f'    <group android:pivotX="12" android:pivotY="12" android:scaleX="{scale}" android:scaleY="{scale}">\n'
                    + geometry + '\n    </group>')
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- {attribution} Generated by branding/make_geist.py. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" '
        'xmlns:tools="http://schemas.android.com/tools" tools:ignore="UnusedResources" '
        'android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
        + geometry + '\n</vector>\n')


def icons():
    source = fetch('geist-org/icons', ICON_REV, '.source',
        'ae72a58f94aed72c3e978425cb2f2dee52652c3a78721aac106626748a015fac').decode()
    collection = {}
    for block in re.findall(r'<button[^>]*class="[^\"]*\bicon"[^>]*>(.*?)</button>', source):
        svg, name = re.search(r'<svg\b.*?</svg>', block), re.search(r'<small[^>]*>(.*?)</small>', block)
        if svg and name:
            collection[name[1]] = svg[0]
    for resource, name in ICONS.items():
        (APP / f'res/drawable/{resource}.xml').write_text(vector(collection[name], f'Geist UI {name}, MIT. TV optical sizing.', OPTICAL_SCALE.get(name, 1)))
    for resource, shapes in SUPPLEMENTS.items():
        svg = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">' + shapes + '</svg>'
        (APP / f'res/drawable/{resource}.xml').write_text(vector(svg, 'Original Stream icon, matching the Geist UI grid.'))
    (APP / 'assets/notices/geist-icons.txt').write_bytes(fetch('geist-org/icons', ICON_REV, 'LICENSE',
        'c4e815b845a0b5b00021f7777076815fab877796eccd2ed80a466f61dbf1a155'))


if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) == 2 else 'all'
    if mode not in ('fonts', 'icons', 'all'):
        raise SystemExit('Usage: make_geist.py [fonts|icons|all]')
    if mode in ('fonts', 'all'):
        fonts()
    if mode in ('icons', 'all'):
        icons()
