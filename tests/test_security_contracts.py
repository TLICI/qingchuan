"""Offline security and release-resolution tests. All credentials here are fake."""
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import build

class SecurityContracts(unittest.TestCase):
    def test_gradle_environment_has_no_api_key(self):
        with patch.dict(os.environ, {'SDK_API_KEY': 'FAKE_TEST_TOKEN'}):
            self.assertNotIn('SDK_API_KEY', build.clean_env())
    def test_resolves_official_latest_release(self):
        with patch.object(build, 'get_json', return_value={'tag_name':'v9.8.7'}) as mocked:
            self.assertEqual('v9.8.7', build.downloader_release()['tag_name'])
            self.assertEqual('https://api.github.com/repos/BrightSDK/bright-sdk-downloader-rs/releases/latest', mocked.call_args.args[0])
    def test_rejects_draft_and_prerelease(self):
        for field in ('draft', 'prerelease'):
            with self.subTest(field=field), patch.object(build, 'get_json', return_value={'tag_name':'v9.8.7',field:True}):
                with self.assertRaises(build.BuildError): build.downloader_release()
    def test_rejects_unsafe_tag(self):
        with patch.object(build, 'get_json', return_value={'tag_name':'../../arbitrary'}):
            with self.assertRaises(build.BuildError): build.downloader_release()

if __name__ == '__main__': unittest.main(verbosity=2)
