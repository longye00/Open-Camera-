#!/usr/bin/env python3
"""Fail-closed source patch for Open Camera 1.56.2; no APK/binary modification.
SPDX-License-Identifier: GPL-3.0-or-later
"""
import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parents[1]
UPSTREAM_COMMIT = '0dd4cbe78872df2c6e4eb6cee3fb0d5637b0f52e'
APP_ID = 'net.sourceforge.opencamera.namequeue'
JAVA_REL = Path('app/src/main/java/net/sourceforge/opencamera')
MARKER = 'PHOTO_NAME_QUEUE_PATCH_V1'


def masked_java(text):
    """Preserve offsets/newlines, removing comments and string/character literal contents."""
    chars = list(text)
    i = 0
    while i < len(text):
        if text.startswith('//', i):
            end = text.find('\n', i)
            if end < 0: end = len(text)
        elif text.startswith('/*', i):
            close = text.find('*/', i + 2)
            if close < 0: raise ValueError('Unterminated Java comment')
            end = close + 2
        elif text[i] in '\"\'':
            quote = text[i]
            end = i + 1
            while end < len(text):
                if text[end] == '\\': end += 2
                elif text[end] == quote:
                    end += 1
                    break
                else: end += 1
            else: raise ValueError('Unterminated Java literal')
        else:
            i += 1
            continue
        for j in range(i, min(end, len(text))):
            if chars[j] not in '\r\n': chars[j] = ' '
        i = end
    return ''.join(chars)


def method(text, name, count):
    mask = masked_java(text)
    # Match a declaration, not a method call. These version-pinned hooks have simple signatures.
    pattern = re.compile(r'(?m)^[ \t]*(?:(?:public|protected|private|static|final|synchronized)\s+)*'
                         r'[\w.$\[\]<>]+\s+' + re.escape(name)
                         + r'\s*\(([^()]*)\)\s*(?:throws\s+[\w.$,\s]+)?\{')
    matches = []
    for match in pattern.finditer(mask):
        parameters = [p.strip() for p in match.group(1).split(',') if p.strip()]
        if len(parameters) != count: continue
        names = []
        for parameter in parameters:
            found = re.search(r'(\w+)\s*(?:\[\])?$', parameter)
            if not found: raise ValueError('Unrecognized parameter: ' + parameter)
            names.append(found.group(1))
        opening = match.end() - 1
        depth = 1
        end = opening + 1
        while end < len(mask) and depth:
            depth += (mask[end] == '{') - (mask[end] == '}')
            end += 1
        if depth: raise ValueError('Unbalanced method: ' + name)
        matches.append({'start': match.start(), 'open': opening, 'end': end,
                        'params': names, 'header': text[match.start():opening]})
    if len(matches) != 1:
        raise ValueError(f'Expected exactly one {name}/{count}; found {len(matches)}. No files changed.')
    return matches[0]


def prepend(text, name, count, callback):
    m = method(text, name, count)
    code = callback(m['params'])
    return text[:m['open']+1] + '\n        ' + code + '\n' + text[m['open']+1:]


def append_body(text, name, count, callback):
    m = method(text, name, count)
    return text[:m['end']-1] + '\n        ' + callback(m['params']) + '\n    ' + text[m['end']-1:]


def append_member(text, member):
    mask = masked_java(text)
    end = mask.rfind('}')
    if end < 0: raise ValueError('Missing outer class')
    return text[:end] + '\n' + member + '\n' + text[end:]


def wrap(text, name, count, make_body):
    m = method(text, name, count)
    helper_name = 'nameQueueOriginal_' + name
    if re.search(r'\b' + helper_name + r'\s*\(', masked_java(text)):
        raise ValueError('Already patched: ' + name)
    helper_header = re.sub(r'\b' + re.escape(name) + r'\b(?=\s*\()', helper_name, m['header'])
    helper = helper_header + text[m['open']:m['end']]
    call = helper_name + '(' + ', '.join(m['params']) + ')'
    wrapper = m['header'] + '{\n' + make_body(m['params'], call) + '\n    }'
    text = text[:m['start']] + wrapper + text[m['end']:]
    return append_member(text, helper)


def patch_java(files):
    out = dict(files)
    main = out['MainActivity.java']
    main = prepend(main, 'takePicture', 1,
                   lambda p: f'if (!PhotoNameQueue.allowShutter(this, {p[0]})) return;')
    main = prepend(main, 'takePicturePressed', 2,
                   lambda p: f'if (!PhotoNameQueue.allowShutter(this, {p[0]} || {p[1]})) return;')
    main = append_body(main, 'onResume', 0, lambda p: 'PhotoNameQueue.onResume(this);\n        AppleLevelGuide.onResume(this);')
    main = prepend(main, 'onPause', 0, lambda p: 'AppleLevelGuide.onPause();')
    out['MainActivity.java'] = main

    api = out['MyApplicationInterface.java']
    api = prepend(api, 'canTakeNewPhoto', 0,
                  lambda p: 'if (!PhotoNameQueue.queueReadyForCapture(main_activity)) return false;')
    api = prepend(api, 'saveInBackground', 1,
                  lambda p: 'if (PhotoNameQueue.hasActiveShot()) return false;')
    api = prepend(api, 'getPausePreviewPref', 0,
                  lambda p: 'if (PhotoNameQueue.enabledForCamera(main_activity)) return false;')
    api = append_member(api, '    boolean nameQueueUsesJpegFormat() {\n'
                        '        return getImageFormatPref() == ImageSaver.Request.ImageFormat.STD;\n    }')

    def on_picture(p, original):
        return f'''        if (!PhotoNameQueue.enabledForCamera(main_activity)) return {original};
        PhotoNameQueue.Shot nameQueueShot = null;
        boolean nameQueueSaved = false;
        try {{
            nameQueueShot = PhotoNameQueue.begin(main_activity, {p[1]});
            // saveInBackground() is false while this thread-local transaction exists.
            nameQueueSaved = {original};
            return nameQueueSaved;
        }} catch (java.io.IOException | RuntimeException nameQueueError) {{
            PhotoNameQueue.reportError(main_activity, nameQueueError);
            return false;
        }} finally {{
            if (nameQueueShot != null) PhotoNameQueue.finish(main_activity, nameQueueShot, nameQueueSaved);
        }}'''
    api = wrap(api, 'onPictureTaken', 3, on_picture)
    api = append_body(api, 'onDrawPreview', 1, lambda p: f'AppleLevelGuide.draw(main_activity, {p[0]});')
    out['MyApplicationInterface.java'] = api

    storage = out['StorageUtils.java']
    storage = prepend(storage, 'createMediaFilename', 5,
                      lambda p: 'String nameQueueFilename = PhotoNameQueue.filename(' + ', '.join(p) + ');\n'
                      '        if (nameQueueFilename != null) return nameQueueFilename;')
    # The five-argument overload has (File imageFolder, int type, String suffix, String extension, Date).
    storage = wrap(storage, 'createOutputMediaFile', 5,
                   lambda p, call: f'        return PhotoNameQueue.reserveFile({call}, {p[1]});')
    storage = prepend(storage, 'setLastMediaScanned', 4,
                      lambda p: f'PhotoNameQueue.recordSavedUri({p[0]});')
    out['StorageUtils.java'] = storage
    out['MyPreferenceFragment.java'] = append_body(out['MyPreferenceFragment.java'], 'onCreate', 1,
                                                  lambda p: 'PhotoNameQueue.addSettings(this);\n        AppleLevelGuide.addSettings(this);')
    return {name: '/* ' + MARKER + ' - GPL-3.0-or-later */\n' + text for name, text in out.items()}


def patch_identity(root, modifications):
    gradle = root / 'app/build.gradle'
    text = gradle.read_text(encoding='utf-8')
    if not re.search(r'versionName\s*[= ]\s*[\"\']1\.56\.2[\"\']', text):
        raise ValueError('This patch requires upstream versionName 1.56.2. No files changed.')
    modifications[gradle] = text + f'''\n// {MARKER}: keep the original code/resource namespace, use a separate installation.
android {{
    defaultConfig {{
        applicationId "{APP_ID}"
        versionName "1.56.2-namequeue-source1"
    }}
    buildTypes {{ debug {{ applicationIdSuffix "" }} }}
}}
'''
    manifest = root / 'app/src/main/AndroidManifest.xml'
    xml = manifest.read_text(encoding='utf-8')
    # Update only launch label, relative component names and manifest authorities/own permissions.
    xml, replacements = re.subn(r'(<application\b[^>]*?android:label\s*=\s*)[\"\'][^\"\']+[\"\']',
                                r'\1"Open Camera 名单版"', xml, count=1, flags=re.S)
    if replacements != 1: raise ValueError('Missing application label; refusing partial patch.')
    xml = re.sub(r'(android:(?:name|targetActivity)\s*=\s*[\"\'])\.',
                 r'\1net.sourceforge.opencamera.', xml)
    xml = re.sub(r'(android:authorities\s*=\s*[\"\'])net\.sourceforge\.opencamera',
                 r'\1${applicationId}', xml)
    xml = xml.replace('net.sourceforge.opencamera.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION',
                      '${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')
    ET.fromstring(xml)
    modifications[manifest] = xml
    for path in (root / 'app/src/main/res').rglob('*.xml'):
        text = path.read_text(encoding='utf-8')
        revised = text.replace('android:targetPackage="net.sourceforge.opencamera"',
                               f'android:targetPackage="{APP_ID}"')
        if revised != text:
            ET.fromstring(revised)
            modifications[path] = revised


def apply(root, dry_run=False):
    root = root.resolve()
    originals = {}
    for name in ('MainActivity.java', 'MyApplicationInterface.java', 'StorageUtils.java', 'MyPreferenceFragment.java'):
        path = root / JAVA_REL / name
        originals[name] = path.read_text(encoding='utf-8')
        if MARKER in originals[name]: raise ValueError('This source tree is already patched.')
    patched = patch_java(originals)
    modifications = {root / JAVA_REL / name: text for name, text in patched.items()}
    patch_identity(root, modifications)
    for name in ('PhotoNameQueue.java', 'PhotoNameQueueCore.java', 'AppleLevelGuide.java', 'LevelGuideCore.java'):
        target = root / JAVA_REL / name
        if target.exists(): raise ValueError('Refusing to overwrite existing ' + str(target))
        modifications[target] = (HERE / 'src/net/sourceforge/opencamera' / name).read_text(encoding='utf-8')
    report = {'patch': MARKER, 'upstream_commit': UPSTREAM_COMMIT,
              'application_id': APP_ID, 'dry_run': dry_run, 'files': []}
    for path, text in modifications.items():
        report['files'].append({'path': str(path.relative_to(root)),
                                'sha256': hashlib.sha256(text.encode()).hexdigest()})
    if not dry_run:
        # Validation above finishes before the first source mutation. Keep every original in a backup.
        backup = root / 'namequeue-patch-backup'
        if backup.exists(): raise ValueError('Backup folder already exists; refusing to overwrite it.')
        backup.mkdir()
        for path in modifications:
            if path.exists():
                dest = backup / path.relative_to(root)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, dest)
        for path, text in modifications.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8')
        (root / 'namequeue-patch-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source_dir', type=Path)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    try:
        print(json.dumps(apply(args.source_dir, args.dry_run), ensure_ascii=False, indent=2))
    except (ValueError, OSError) as error:
        parser.exit(1, 'Patch stopped: ' + str(error) + '\n')

if __name__ == '__main__': main()
