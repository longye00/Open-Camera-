#!/usr/bin/env python3
"""Real JVM tests for independent logic, plus synthetic-fixture source-patcher tests.
These are NOT Android runtime, real upstream build, or physical camera/sensor tests.
SPDX-License-Identifier: GPL-3.0-or-later
"""
import json
import shutil
import subprocess
import tempfile
from pathlib import Path
import apply_patch as patch

ROOT = Path(__file__).resolve().parents[1]

def run_core(temp):
    package = ROOT / 'src/net/sourceforge/opencamera'
    compiler = [shutil.which('javac')] if shutil.which('javac') else ['java', 'com.sun.tools.javac.Main']
    subprocess.run(compiler + ['--release', '8', '-encoding', 'UTF-8', '-d', str(temp / 'classes'),
                   str(package / 'PhotoNameQueueCore.java'), str(package / 'LevelGuideCore.java'),
                   str(ROOT / 'tests/CoreTests.java')], check=True)
    subprocess.run(['java', '-cp', str(temp / 'classes'), 'net.sourceforge.opencamera.CoreTests'], check=True)

FIXTURES = {
'MainActivity.java': '''package net.sourceforge.opencamera;
public class MainActivity {
    public void takePicture(boolean snapshot) { String s = "} ignored brace"; }
    void takePicturePressed(boolean snapshot, boolean burst) { takePicture(snapshot); }
    protected void onResume() { /* nested { comment } */ if (true) {} }
    protected void onPause() { }
}
''',
'MyApplicationInterface.java': '''package net.sourceforge.opencamera;
public class MyApplicationInterface {
    public boolean canTakeNewPhoto() { return true; }
    private boolean saveInBackground(boolean intent) { return !intent; }
    public boolean getPausePreviewPref() { return false; }
    public boolean onPictureTaken(byte[] image, java.util.Date date, android.location.Location location) {
        String text="// not a comment";
        return true;
    }
    public void onDrawPreview(android.graphics.Canvas canvas) { }
}
''',
'StorageUtils.java': '''package net.sourceforge.opencamera;
public class StorageUtils {
    String createMediaFilename(int type, String suffix, int counter, String ext, java.util.Date time) {
        return "IMG_" + time;
    }
    java.io.File createOutputMediaFile(int type, String suffix, String ext, java.util.Date time) throws java.io.IOException {
        return null;
    }
    java.io.File createOutputMediaFile(java.io.File folder, int type, String suffix, String ext, java.util.Date time) throws java.io.IOException {
        return null;
    }
    void setLastMediaScanned(android.net.Uri uri, boolean raw, boolean noexif, android.net.Uri check) { }
}
''',
'MyPreferenceFragment.java': '''package net.sourceforge.opencamera;
public class MyPreferenceFragment {
    public void onCreate(android.os.Bundle state) { }
}
'''}

def test_patcher(temp):
    root = temp / 'source-fixture'
    for name, value in FIXTURES.items():
        path = root / patch.JAVA_REL / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding='utf-8')
    (root / 'app/build.gradle').write_text('android { defaultConfig { versionName "1.56.2" } }\n')
    (root / 'app/src/main/AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="net.sourceforge.opencamera">
    <application android:label="@string/app_name" android:name=".OpenCameraApplication">
    <activity android:name=".MainActivity"/>
    <provider android:name="example.Provider" android:authorities="net.sourceforge.opencamera.example"/>
    </application></manifest>''')
    res=root / 'app/src/main/res/xml';res.mkdir(parents=True)
    (res/'shortcuts.xml').write_text('<shortcuts xmlns:android="http://schemas.android.com/apk/res/android"><intent android:targetPackage="net.sourceforge.opencamera"/></shortcuts>')
    before={p:p.read_bytes() for p in root.rglob('*') if p.is_file()}
    report=patch.apply(root, True)
    assert report['dry_run']
    assert all(p.read_bytes()==value for p,value in before.items()), 'dry run wrote files'
    patch.apply(root)
    api=(root/patch.JAVA_REL/'MyApplicationInterface.java').read_text()
    assert 'PhotoNameQueue.begin(main_activity, date)' in api
    assert 'nameQueueOriginal_onPictureTaken(image, date, location)' in api
    assert 'PhotoNameQueue.finish(main_activity, nameQueueShot, nameQueueSaved)' in api
    assert 'AppleLevelGuide.draw(main_activity, canvas)' in api
    storage=(root/patch.JAVA_REL/'StorageUtils.java').read_text()
    assert 'PhotoNameQueue.filename(type, suffix, counter, ext, time)' in storage
    assert 'reserveFile(nameQueueOriginal_createOutputMediaFile(folder, type, suffix, ext, time), type)' in storage
    assert storage.count('java.io.File createOutputMediaFile(')==2, 'overload lost'
    assert 'AppleLevelGuide.onPause()' in (root/patch.JAVA_REL/'MainActivity.java').read_text()
    manifest=(root/'app/src/main/AndroidManifest.xml').read_text()
    assert '${applicationId}.example' in manifest
    assert 'android:name="net.sourceforge.opencamera.MainActivity"' in manifest
    assert 'Open Camera 名单版' in manifest
    assert (root/'namequeue-patch-backup').is_dir()
    assert (root/patch.JAVA_REL/'AppleLevelGuide.java').exists()
    for path,text in before.items():
        backup=root/'namequeue-patch-backup'/path.relative_to(root)
        assert backup.read_bytes()==text
    blocked=False
    try:patch.apply(root)
    except ValueError:blocked=True
    assert blocked, 'idempotence guard failed'
    changed=dict(FIXTURES)
    changed['MyApplicationInterface.java']=changed['MyApplicationInterface.java'].replace('saveInBackground','missingMethod')
    blocked=False
    try:patch.patch_java(changed)
    except ValueError:blocked=True
    assert blocked,'missing upstream hook must not be guessed'
    print('PASS: source patcher synthetic-fixture tests (not the upstream Android build)')

def test_version_sources(temp):
    """Regression: versionName may be declared only in AndroidManifest.xml."""
    cases = [
        ('gradle-literal', 'versionName "1.56.2"', None, 'app/build.gradle'),
        ('manifest-only', 'applicationId "net.sourceforge.opencamera"', '1.56.2', 'app/src/main/AndroidManifest.xml'),
        ('gradle-assignment', "versionName = '1.56.2'", None, 'app/build.gradle'),
        ('gradle-call', 'versionName("1.56.2")', None, 'app/build.gradle'),
        ('gradle-overrides-manifest', 'versionName "1.56.2"', '1.55', 'app/build.gradle'),
        ('wrong-gradle-override', 'versionName "1.55"', '1.56.2', None),
        ('wrong-manifest', 'applicationId "net.sourceforge.opencamera"', '1.55', None),
        ('comment-does-not-count', '// versionName "1.56.2"\n', '1.55', None),
        ('block-comment-ignored', '/* versionName "1.55" */', '1.56.2', 'app/src/main/AndroidManifest.xml'),
        ('no-version', '', None, None),
        ('computed-version', 'versionName releaseVersion', '1.56.2', None),
        ('conflicting-versions', 'versionName "1.56.2"\n versionName "1.55"', None, None),
        ('concatenated-version', 'versionName "1.56.2" + "-different"', '1.56.2', None),
    ]
    for label, declaration, manifest_version, expected_source in cases:
        root = temp / ('version-' + label)
        (root / 'app/src/main/res').mkdir(parents=True)
        (root / 'app/build.gradle').write_text('android { defaultConfig {\n' + declaration + '\n} }\n', encoding='utf-8')
        attribute = '' if manifest_version is None else ' android:versionName="' + manifest_version + '"'
        manifest = '<manifest xmlns:android="http://schemas.android.com/apk/res/android"' + attribute + '><application android:label="@string/app_name" /></manifest>'
        (root / 'app/src/main/AndroidManifest.xml').write_text(manifest, encoding='utf-8')
        before = {p: p.read_bytes() for p in root.rglob('*') if p.is_file()}
        try:
            result = patch.read_upstream_version(root)
        except ValueError:
            assert expected_source is None, 'Valid version rejected: ' + label
        else:
            assert expected_source is not None, 'Invalid version accepted: ' + label
            assert result == {'value': '1.56.2', 'source': expected_source}, label
        assert all(p.read_bytes() == data for p, data in before.items()), 'Version check wrote files: ' + label
    print('PASS: ' + str(len(cases)) + ' Gradle/manifest version-check regression cases')

    # Apply the complete source patch to a fixture whose version exists ONLY in the manifest.
    root = temp / 'manifest-only-full-patch'
    for name, value in FIXTURES.items():
        path = root / patch.JAVA_REL / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding='utf-8')
    (root / 'app/build.gradle').write_text('android { defaultConfig { applicationId "net.sourceforge.opencamera" } }\n', encoding='utf-8')
    manifest_path = root / 'app/src/main/AndroidManifest.xml'
    manifest_path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionName="1.56.2" android:versionCode="96"><application android:label="@string/app_name" android:name=".OpenCameraApplication"><activity android:name=".MainActivity" /></application></manifest>', encoding='utf-8')
    (root / 'app/src/main/res').mkdir(parents=True)
    before = {p: p.read_bytes() for p in root.rglob('*') if p.is_file()}
    dry = patch.apply(root, True)
    assert dry['upstream_version']['source'].endswith('AndroidManifest.xml')
    assert all(p.read_bytes() == data for p, data in before.items())
    applied = patch.apply(root)
    assert applied['upstream_version']['value'] == '1.56.2'
    assert applied['script_revision'] == '1.1-manifest-version-fix'
    assert (root / patch.JAVA_REL / 'PhotoNameQueue.java').is_file()
    assert (root / patch.JAVA_REL / 'AppleLevelGuide.java').is_file()
    assert 'Open Camera 名单版' in manifest_path.read_text(encoding='utf-8')
    for path, data in before.items():
        assert (root / 'namequeue-patch-backup' / path.relative_to(root)).read_bytes() == data
    print('PASS: complete patch application with manifest-only version (synthetic source fixture)')

if __name__ == '__main__':
    with tempfile.TemporaryDirectory(prefix='namequeue-tests-') as folder:
        temp=Path(folder)
        run_core(temp)
        test_patcher(temp)
        test_version_sources(temp)
    print('NOT TESTED: real upstream Android build, APK installation, camera/storage/sensor/UI integration.')
