"""Offline tests. Temporary SDK-shaped fixtures are NEVER shipped in app/libs.
They test tooling/contracts, not the behavior or compatibility of the real Bright SDK.
"""
import io
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import build
import sdk_inspect

class SourceTests(unittest.TestCase):
    def test_application_id(self):
        gradle = (ROOT / 'app/build.gradle').read_text()
        self.assertIn("applicationId 'com.qingchuan.connect'", gradle)
        self.assertIn("namespace 'com.qingchuan.connect'", gradle)

    def test_xml_and_string_references(self):
        files = list((ROOT / 'app/src/main').rglob('*.xml'))
        strings = {x.attrib['name'] for x in ET.parse(ROOT / 'app/src/main/res/values/strings.xml').getroot()}
        self.assertGreater(len(files), 3)
        for file in files:
            ET.parse(file)
            for name in re.findall(r'@string/([a-zA-Z0-9_]+)', file.read_text()):
                self.assertIn(name, strings, file.name)
        self.assertEqual('晴川', ET.parse(ROOT / 'app/src/main/res/values/strings.xml').find("string[@name='app_name']").text)

    def test_ui_ids_exist(self):
        layout = (ROOT / 'app/src/main/res/layout/activity_main.xml').read_text()
        ids = set(re.findall(r'@\+id/(\w+)', layout))
        java = (ROOT / 'app/src/main/java/com/qingchuan/connect/MainActivity.java').read_text()
        self.assertTrue(set(re.findall(r'R\.id\.(\w+)', java)) <= ids)

    def test_no_hidden_opt_in_or_runtime_key(self):
        java = (ROOT / 'app/src/main/java/com/qingchuan/connect/MainActivity.java').read_text()
        self.assertNotIn('externalOptIn', java)
        self.assertNotIn('SDK_API_KEY', java)
        self.assertNotIn('setTrackingId', java)
        self.assertIn('SdkBinding.optOut(this)', java)
        strings = (ROOT / 'app/src/main/res/values/strings.xml').read_text()
        self.assertIn('IP 地址', strings)
        self.assertIn('Bright Data', strings)

    def test_scrub(self):
        text = build.scrub('error SECRET123 https://x.example/a?token=value', 'SECRET123')
        self.assertNotIn('SECRET123', text)
        self.assertNotIn('token=value', text)

    def test_parse_downloader_json(self):
        self.assertEqual('9.0.1', build.parse_cli_json('progress\n{"version":"9.0.1","platform":"android"}')['version'])
        with self.assertRaises(build.BuildError): build.parse_cli_json('not json')

    def test_secret_scan_nested_archive(self):
        with tempfile.TemporaryDirectory() as temp:
            inner = io.BytesIO()
            with zipfile.ZipFile(inner, 'w') as z: z.writestr('A.class', b'prefixSECRET123suffix')
            path = Path(temp) / 'sample.aar'
            with zipfile.ZipFile(path, 'w') as z: z.writestr('classes.jar', inner.getvalue())
            with self.assertRaises(build.BuildError): build.reject_secret_in_zip(path, 'SECRET123')
            build.reject_secret_in_zip(path, 'ANOTHER_TOKEN')

    def test_zip_traversal_blocked(self):
        for entry in ('../escape', '..\\escape', '/absolute', 'C:/absolute'):
            with self.subTest(entry=entry), tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / 'bad.zip'
                with zipfile.ZipFile(path, 'w') as z: z.writestr(entry, b'x')
                with self.assertRaises(build.BuildError): build.safe_extract(path, Path(temp) / 'out')

    def test_zip_symlink_blocked(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'bad.zip'
            info = zipfile.ZipInfo('link'); info.create_system = 3; info.external_attr = 0o120777 << 16
            with zipfile.ZipFile(path, 'w') as z: z.writestr(info, '../../target')
            with self.assertRaises(build.BuildError): build.safe_extract(path, Path(temp) / 'out')

    def test_zip_normal(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'good.zip'
            with zipfile.ZipFile(path, 'w') as z: z.writestr('a/b.txt', b'ok')
            build.safe_extract(path, Path(temp) / 'out')
            self.assertEqual(b'ok', (Path(temp) / 'out/a/b.txt').read_bytes())

    def test_invalid_aar_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / 'bad.aar'
            with zipfile.ZipFile(p, 'w') as z: z.writestr('nothing.txt', 'no SDK')
            with self.assertRaises(sdk_inspect.SdkError): sdk_inspect.inspect_sdk(p)

    def test_invalid_class_fails(self):
        for content in (b'', b'not a java class'):
            with self.assertRaises(sdk_inspect.SdkError): sdk_inspect.parse_class(content)

@unittest.skipUnless(shutil.which('javac'), 'JDK required for temporary class fixtures')
class BytecodeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.base = Path(cls.temp.name)
        cls.src, cls.classes = cls.base / 'src', cls.base / 'classes'
        sources = {
            'android/content/Context.java': 'package android.content; public class Context {}',
            'android/app/Activity.java': 'package android.app; public class Activity extends android.content.Context {}',
            'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return null;} }',
            'android/os/Handler.java': 'package android.os; public class Handler {public Handler(Looper l){} public boolean post(Runnable r){return true;} }',
            'fixture/sdk/Settings.java': '''package fixture.sdk;
                public class Settings {
                    public enum ConsentLanguage { en }
                    public interface Callback { void onChange(int value); }
                    public Settings(android.content.Context c) {}
                    public void setSkipConsent(boolean value) {}
                    public void setLanguage(ConsentLanguage value) {}
                    public void setBenefit(String value) {}
                    public void setAgreeBtn(String value) {}
                    public void setDisagreeBtn(String value) {}
                    public void setOptOutInstructions(String value) {}
                    public void setOnStatusChange(Callback cb) {}
                }''',
            'fixture/sdk/BrightApi.java': '''package fixture.sdk;
                public class BrightApi {
                    public static void init(android.content.Context c, Settings s) {}
                    public static void showConsent(android.app.Activity a) {}
                    public static void optOut(android.content.Context c) {}
                    public static Boolean getConsentChoice(android.content.Context c) {return null;}
                }''',
        }
        for name, text in sources.items():
            p = cls.src / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(text)
        result = subprocess.run(['javac', '--release', '8', '-d', str(cls.classes)] + [str(p) for p in cls.src.rglob('*.java')], capture_output=True, text=True)
        if result.returncode: raise AssertionError(result.stderr)
        cls.aar = cls.make_aar(cls.base / 'fixture.aar')

    @classmethod
    def make_aar(cls, output, transform=None):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, 'w') as jar:
            for p in (cls.classes / 'fixture').rglob('*.class'):
                blob = p.read_bytes()
                if transform: blob = transform(blob)
                jar.writestr(p.relative_to(cls.classes).as_posix(), blob)
        with zipfile.ZipFile(output, 'w') as aar:
            aar.writestr('AndroidManifest.xml', '<manifest package="fixture.sdk"/>')
            aar.writestr('classes.jar', buf.getvalue())
        return output

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def test_discovers_public_api_from_bytecode(self):
        info = sdk_inspect.inspect_sdk(self.aar)
        self.assertEqual('fixture.sdk.BrightApi', info['bright_api'])
        self.assertEqual('fixture.sdk.Settings', info['settings'])
        self.assertEqual('fixture.sdk.Settings.ConsentLanguage', info['language'])
        self.assertEqual(4, info['class_count'])

    def test_generated_binding_compiles_against_contract_fixture(self):
        info = sdk_inspect.inspect_sdk(self.aar)
        generated = self.base / 'generated/com/qingchuan/connect/SdkBinding.java'
        generated.parent.mkdir(parents=True, exist_ok=True)
        generated.write_text(sdk_inspect.generate_binding(info, '0.0.0-fixture'), encoding='utf-8')
        result = subprocess.run(['javac', '--release', '8', '-cp', str(self.classes), '-d', str(self.base / 'compiled-binding'), str(generated)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn('externalOptIn', generated.read_text())
        self.assertIn('settings.setSkipConsent(true)', generated.read_text())

    def test_missing_api_fails_closed(self):
        path = self.make_aar(self.base / 'missing.aar', lambda b: b.replace(b'getConsentChoice', b'notConsentChoice'))
        with self.assertRaises(sdk_inspect.SdkError): sdk_inspect.inspect_sdk(path)

    def test_invalid_version_not_injected(self):
        info = sdk_inspect.inspect_sdk(self.aar)
        with self.assertRaises(sdk_inspect.SdkError): sdk_inspect.generate_binding(info, '"; evil(); //')

if __name__ == '__main__': unittest.main(verbosity=2)
