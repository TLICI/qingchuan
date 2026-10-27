#!/usr/bin/env python3
"""Fetch real Bright SDK with the official Rust CLI; build and verify Qingchuan.
Requires Python 3.10+. Credentials are used only for the SDK download child process.
"""
from __future__ import annotations
import argparse
import getpass
import hashlib
import json
import os
import platform
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from sdk_inspect import SdkError, inspect_sdk, generate_binding

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
DOWNLOADER_TAG = 'latest'  # Resolved once, then the downloaded binary is hash-pinned locally.
GRADLE_VERSION = '8.11.1'
APPLICATION_ID = 'com.qingchuan.connect'
SDK_AAR = ROOT / 'app/libs/bright_sdk.aar'
LOCK_FILE = ROOT / 'sdk-lock.json'
GENERATED = ROOT / 'app/src/generated/java/com/qingchuan/connect/SdkBinding.java'
GITHUB_REPO = 'BrightSDK/bright-sdk-downloader-rs'

class BuildError(RuntimeError):
    pass

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''): h.update(chunk)
    return h.hexdigest()

def save_json(path: Path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def clean_env() -> dict:
    env = os.environ.copy()
    env.pop('SDK_API_KEY', None)
    return env

def scrub(text: str, secret: str = '') -> str:
    if secret: text = text.replace(secret, '<redacted>')
    # Avoid persisting signed download URLs in error reports.
    text = re.sub(r'https?://[^\s<>\"\']+\?[^\s<>\"\']+', '<download-url-redacted>', text)
    return text[-5000:]

def request(url: str):
    if not url.startswith('https://'):
        raise BuildError('Only HTTPS downloads are allowed')
    req = urllib.request.Request(url, headers={'User-Agent': 'Qingchuan-local-build/0.1', 'Accept': '*/*'})
    response = urllib.request.urlopen(req, timeout=60)
    if not response.geturl().startswith('https://'):
        response.close()
        raise BuildError('Refusing an HTTPS-to-HTTP redirect')
    return response

def get_json(url: str):
    try:
        with request(url) as response:
            blob = response.read(8 * 1024 * 1024 + 1)
        if len(blob) > 8 * 1024 * 1024: raise BuildError('Metadata response too large')
        return json.loads(blob)
    except (OSError, ValueError, urllib.error.URLError) as exc:
        raise BuildError(f'Metadata download failed ({exc.__class__.__name__}). Check your network / GitHub access.') from exc

def download(url: str, dest: Path, expected: str | None = None, limit: int = 400 * 1024 * 1024):
    if expected and not re.fullmatch(r'[0-9a-fA-F]{64}', expected):
        raise BuildError('Invalid SHA-256 checksum')
    if dest.exists() and expected and sha256(dest).lower() == expected.lower(): return
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + '.part')
    try:
        with request(url) as response, part.open('wb') as target:
            total = 0
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk: break
                total += len(chunk)
                if total > limit: raise BuildError('Download exceeds safety size limit')
                target.write(chunk)
        if expected and sha256(part).lower() != expected.lower():
            raise BuildError('SHA-256 verification failed; refusing downloaded file')
        part.replace(dest)
    except (OSError, urllib.error.URLError) as exc:
        raise BuildError(f'Download failed ({exc.__class__.__name__}). Check network access.') from exc
    finally:
        part.unlink(missing_ok=True)

def safe_extract(path: Path, destination: Path):
    destination.mkdir(parents=True, exist_ok=True)
    base = destination.resolve()
    with zipfile.ZipFile(path) as archive:
        if sum(i.file_size for i in archive.infolist()) > 1600 * 1024 * 1024:
            raise BuildError('Archive exceeds extraction size limit')
        for info in archive.infolist():
            # Backslash matters on Windows. Disallow traversal on all hosts.
            name = info.filename.replace('\\', '/')
            if ':' in name or name.startswith('/') or '..' in name.split('/'):
                raise BuildError('Unsafe archive path')
            target = (base / name).resolve()
            if target != base and base not in target.parents:
                raise BuildError('Unsafe archive path')
            mode = info.external_attr >> 16
            if stat.S_ISLNK(mode): raise BuildError('Archive symlinks are not supported')
        archive.extractall(base)

def execute(args: list[str], *, cwd: Path = ROOT, env: dict | None = None, capture: bool = False):
    # Windows .bat/.cmd launchers require cmd.exe. Reject cmd metacharacters first.
    command = [str(a) for a in args]
    if os.name == 'nt' and command[0].lower().endswith(('.bat', '.cmd')):
        if any(re.search(r'[&|<>^%\r\n]', a) for a in command):
            raise BuildError('Unsupported shell metacharacters in a Windows path/argument')
        command = ['cmd.exe', '/d', '/s', '/c', subprocess.list2cmdline(command)]
    result = subprocess.run(command, cwd=cwd, env=env or clean_env(), text=True,
                            encoding='utf-8', errors='replace',
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None)
    return result

def java_environment() -> dict:
    env = clean_env()
    executable = 'javac.exe' if os.name == 'nt' else 'javac'
    candidates = []
    if env.get('JAVA_HOME'): candidates.append(Path(env['JAVA_HOME']))
    if shutil.which('javac'): candidates.append(Path(shutil.which('javac')).resolve().parent.parent)
    candidates += [Path('/usr/lib/jvm/default-java'), Path('/opt/android-studio/jbr'),
                   Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')]
    for key in ('ProgramFiles', 'ProgramFiles(x86)', 'LOCALAPPDATA'):
        if env.get(key): candidates.append(Path(env[key]) / 'Android/Android Studio/jbr')
    for home in candidates:
        if not (home / 'bin' / executable).exists(): continue
        java = home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        result = execute([str(java), '-version'], capture=True, env=env)
        match = re.search(r'version "(\d+)', result.stderr + result.stdout)
        if match and int(match.group(1)) in range(17, 24):
            env['JAVA_HOME'] = str(home.resolve())
            env['PATH'] = str(home / 'bin') + os.pathsep + env.get('PATH', '')
            return env
    raise BuildError('JDK 17 or 21 is recommended (17-23 supported by this toolchain). Set JAVA_HOME to a full JDK, not a JRE.')

def sdk_environment(env: dict) -> tuple[dict, Path]:
    candidates = []
    for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if env.get(key): candidates.append(Path(env[key]))
    prop = ROOT / 'local.properties'
    if prop.exists():
        match = re.search(r'^sdk\.dir\s*=\s*(.+)$', prop.read_text(encoding='utf-8'), re.M)
        if match:
            candidates.append(Path(match.group(1).strip().replace('\\:', ':').replace('\\\\', '\\')))
    home = Path.home()
    candidates += [home / 'Android/Sdk', home / 'Library/Android/sdk']
    if env.get('LOCALAPPDATA'): candidates.append(Path(env['LOCALAPPDATA']) / 'Android/Sdk')
    for sdk in candidates:
        if (sdk / 'platforms/android-35/android.jar').is_file() and (sdk / 'build-tools/35.0.0').is_dir():
            env = env.copy()
            env['ANDROID_HOME'] = str(sdk.resolve())
            env['ANDROID_SDK_ROOT'] = str(sdk.resolve())
            escaped = sdk.resolve().as_posix().replace(':', '\\:')
            prop.write_text('sdk.dir=' + escaped + '\n', encoding='utf-8')
            return env, sdk.resolve()
    raise BuildError('Android SDK not ready. Install Android SDK Platform 35 and Build-Tools 35.0.0 using Android Studio > SDK Manager, then set ANDROID_HOME. No licenses are auto-accepted.')

def doctor() -> int:
    print('Python:', sys.version.split()[0])
    print('Application ID:', APPLICATION_ID)
    missing = False
    try:
        env = java_environment(); print('JDK:', env['JAVA_HOME'])
    except BuildError as exc:
        print('MISSING:', exc); env = clean_env(); missing = True
    try:
        _, sdk = sdk_environment(env); print('Android SDK:', sdk)
    except BuildError as exc:
        print('MISSING:', exc); missing = True
    print('Rust cargo (optional):', shutil.which('cargo') or 'not installed')
    print('Official AAR:', 'present' if SDK_AAR.exists() else 'not downloaded yet')
    print('API key:', 'set in environment' if os.environ.get('SDK_API_KEY') else 'will prompt locally when needed')
    return 1 if missing else 0

def platform_asset() -> str:
    system, arch = platform.system(), platform.machine().lower()
    if system == 'Windows' and arch in ('amd64', 'x86_64'): return 'bright-sdk-downloader-win-x64.exe'
    if system == 'Linux' and arch in ('amd64', 'x86_64'): return 'bright-sdk-downloader-linux-x64'
    if system == 'Darwin' and arch in ('arm64', 'aarch64'): return 'bright-sdk-downloader-macos-arm64'
    if system == 'Darwin' and arch in ('amd64', 'x86_64'): return 'bright-sdk-downloader-macos-x64'
    raise BuildError('No documented official binary for this OS/CPU. Use downloader --from-source with Rust installed.')

def downloader_release() -> dict:
    endpoint = 'latest' if DOWNLOADER_TAG == 'latest' else f'tags/{DOWNLOADER_TAG}'
    metadata = get_json(f'https://api.github.com/repos/{GITHUB_REPO}/releases/{endpoint}')
    tag = metadata.get('tag_name', '')
    if not re.fullmatch(r'[A-Za-z0-9_.+\-]{1,100}', tag):
        raise BuildError('Official release returned an invalid tag')
    if metadata.get('draft') or metadata.get('prerelease'):
        raise BuildError('Refusing an unpublished or prerelease downloader')
    return metadata

def get_downloader(provided: str | None = None) -> Path:
    if provided:
        path = Path(provided).expanduser().resolve()
        if not path.is_file(): raise BuildError('Downloader path does not exist')
        return path
    asset_name = platform_asset()
    folder = TOOLS / 'downloader'
    path = folder / asset_name
    receipt = folder / 'provenance.json'
    if path.exists() and receipt.exists():
        cached = json.loads(receipt.read_text(encoding='utf-8'))
        if cached.get('asset') == asset_name and cached.get('sha256') == sha256(path):
            return path
        raise BuildError('Cached downloader hash mismatch. Remove .tools/downloader and retry.')
    metadata = downloader_release()
    assets = [a for a in metadata.get('assets', []) if a.get('name') == asset_name]
    if len(assets) != 1: raise BuildError(f'Official release is missing {asset_name}; specify --downloader PATH')
    item = assets[0]
    digest = item.get('digest') or ''
    checksum = digest[7:] if digest.startswith('sha256:') else None
    url = item.get('browser_download_url', '')
    if not url.startswith(f'https://github.com/{GITHUB_REPO}/releases/download/'):
        raise BuildError('Unexpected downloader asset URL')
    print('Downloading official SDK downloader', metadata['tag_name'])
    download(url, path, checksum, 100 * 1024 * 1024)
    if item.get('size') and path.stat().st_size != item['size']: raise BuildError('Downloader size mismatch')
    if not checksum:
        print('NOTE: GitHub supplied no publisher digest; HTTPS download will be pinned by local SHA-256.')
    save_json(receipt, {'repository': GITHUB_REPO, 'tag': metadata['tag_name'], 'asset': asset_name,
                       'sha256': sha256(path), 'publisher_digest_verified': bool(checksum)})
    if os.name != 'nt': path.chmod(0o755)
    return path

def build_downloader_source() -> Path:
    if not shutil.which('git') or not shutil.which('cargo'):
        raise BuildError('Building the upstream repository requires Git and Rust (cargo). No SDK API key is needed for compilation.')
    tag = downloader_release()['tag_name']
    source = TOOLS / f'downloader-source-{tag}'
    TOOLS.mkdir(parents=True, exist_ok=True)
    if not source.exists():
        result = execute(['git', 'clone', '--depth', '1', '--branch', tag,
                          f'https://github.com/{GITHUB_REPO}.git', str(source)])
        if result.returncode: raise BuildError('Could not clone official source tag')
    for cmd in (['cargo', 'test', '--locked'], ['cargo', 'build', '--release', '--locked']):
        if execute(cmd, cwd=source).returncode: raise BuildError('Upstream Rust build/test failed')
    meta = execute(['cargo', 'metadata', '--no-deps', '--format-version', '1'], cwd=source, capture=True)
    if meta.returncode: raise BuildError('cargo metadata failed')
    data = json.loads(meta.stdout)
    names = [t['name'] for p in data['packages'] for t in p['targets'] if 'bin' in t['kind']]
    if len(names) != 1: raise BuildError('Cannot identify one upstream CLI binary')
    binary = Path(data['target_directory']) / 'release' / (names[0] + ('.exe' if os.name == 'nt' else ''))
    output = ROOT / 'output/downloader' / binary.name
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(binary, output)
    commit = execute(['git', 'rev-parse', 'HEAD'], cwd=source, capture=True).stdout.strip()
    save_json(output.parent / 'provenance.json', {'repository': GITHUB_REPO, 'tag': tag,
              'commit': commit, 'binary_sha256': sha256(output), 'cargo_test_passed': True})
    print('Built upstream downloader:', output)
    return output

def parse_cli_json(stdout: str) -> dict:
    decoder = json.JSONDecoder()
    for pos, char in enumerate(stdout):
        if char != '{': continue
        try:
            value, _ = decoder.raw_decode(stdout[pos:])
            if isinstance(value, dict) and value.get('version'): return value
        except ValueError: pass
    raise BuildError('Official downloader did not return expected version JSON')

def get_key() -> str:
    key = os.environ.get('SDK_API_KEY', '').strip()
    if not key:
        if not sys.stdin.isatty(): raise BuildError('SDK_API_KEY is not set. Run interactively or set it as a CI secret.')
        key = getpass.getpass('Bright SDK API key (hidden; not saved): ').strip()
    if not key or '\n' in key or key in ('YOUR_API_KEY', 'YOUR_SDK_API_KEY', '<your-api-key>'):
        raise BuildError('Supply a real key locally, not a placeholder')
    return key

def import_sdk(aar: Path, version: str, source: str, extra: dict | None = None) -> dict:
    info = inspect_sdk(aar)
    binding = generate_binding(info, version)
    SDK_AAR.parent.mkdir(parents=True, exist_ok=True)
    if aar.resolve() != SDK_AAR.resolve(): shutil.copyfile(aar, SDK_AAR)
    GENERATED.parent.mkdir(parents=True, exist_ok=True)
    GENERATED.write_text(binding, encoding='utf-8')
    lock = {'application_id': APPLICATION_ID, 'sdk_version': version, 'source': source,
            'downloaded_at_utc': datetime.now(timezone.utc).isoformat(), **info}
    if extra: lock.update(extra)
    save_json(LOCK_FILE, lock)
    print('SDK ready:', version, '| SHA-256:', info['aar_sha256'])
    print('Public API identified:', info['bright_api'])
    return lock

def fetch_sdk(version: str, downloader: str | None) -> tuple[dict, str]:
    tool = get_downloader(downloader)
    key = get_key()
    env = clean_env(); env['SDK_API_KEY'] = key
    result = execute([str(tool), 'resolve', '-p', 'android', '-v', version, '--pretty'], env=env, capture=True)
    if result.returncode:
        raise BuildError('SDK authentication/version resolution failed:\n' + scrub(result.stderr, key))
    metadata = parse_cli_json(result.stdout)
    actual = str(metadata['version'])
    if not re.fullmatch(r'[A-Za-z0-9_.+\-]{1,100}', actual) or actual == 'latest':
        raise BuildError('Downloader did not resolve a concrete SDK version')
    TOOLS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='sdk-fetch-', dir=TOOLS) as temp:
        print('Fetching real Android SDK version:', actual)
        result = execute([str(tool), 'fetch', '-p', 'android', '-v', actual, '-o', temp], env=env, capture=True)
        if result.returncode:
            raise BuildError('SDK download failed:\n' + scrub(result.stderr, key))
        aars = list(Path(temp).rglob('bright_sdk.aar'))
        if len(aars) != 1:
            raise BuildError(f'Expected one bright_sdk.aar, found {len(aars)}. Inspect the official archive structure locally.')
        # Refuse a distribution which embeds this exact download credential.
        reject_secret_in_zip(aars[0], key)
        lock = import_sdk(aars[0], actual, 'official-rust-downloader',
                          {'downloader_sha256': sha256(tool), 'downloader_provenance':
                           json.loads((TOOLS / 'downloader/provenance.json').read_text(encoding='utf-8'))
                           if not downloader and (TOOLS / 'downloader/provenance.json').exists() else {'source': 'user-supplied'}})
    env.pop('SDK_API_KEY', None)
    return lock, key

def reject_secret_in_zip(path: Path, secret: str):
    if not secret: return
    patterns = [secret.encode(), secret.encode('utf-16le')]
    def visit(blob: bytes, depth: int):
        if any(p in blob for p in patterns): raise BuildError('Credential found in an archive; refusing to publish it')
        if depth < 2 and blob.startswith(b'PK\x03\x04'):
            import io
            with zipfile.ZipFile(io.BytesIO(blob)) as archive:
                if sum(i.file_size for i in archive.infolist()) > 600 * 1024 * 1024:
                    raise BuildError('Archive too large for credential scan')
                for item in archive.infolist():
                    if not item.is_dir(): visit(archive.read(item), depth + 1)
    visit(path.read_bytes(), 0)

def get_gradle() -> Path:
    base = TOOLS / f'gradle-{GRADLE_VERSION}'
    executable = base / 'bin' / ('gradle.bat' if os.name == 'nt' else 'gradle')
    if executable.exists(): return executable
    url = f'https://services.gradle.org/distributions/gradle-{GRADLE_VERSION}-bin.zip'
    try:
        with request(url + '.sha256') as response: checksum = response.read(1024).decode('ascii').strip().split()[0]
    except (OSError, ValueError, IndexError) as exc:
        raise BuildError('Cannot retrieve official Gradle checksum') from exc
    if not re.fullmatch(r'[a-fA-F0-9]{64}', checksum): raise BuildError('Invalid official Gradle checksum response')
    archive = TOOLS / f'gradle-{GRADLE_VERSION}-bin.zip'
    print('Downloading Gradle', GRADLE_VERSION)
    download(url, archive, checksum)
    safe_extract(archive, TOOLS)
    if not executable.exists(): raise BuildError('Gradle archive layout is not supported')
    if os.name != 'nt': executable.chmod(0o755)
    save_json(TOOLS / 'gradle-provenance.json', {'version': GRADLE_VERSION, 'sha256': checksum})
    return executable

def prepare_wrapper(gradle: Path, env: dict):
    """Generate the authentic Gradle wrapper locally; do not ship invented wrapper binaries."""
    if (ROOT / 'gradle/wrapper/gradle-wrapper.jar').exists(): return
    folder = TOOLS / 'wrapper-bootstrap'
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'settings.gradle').write_text("rootProject.name='wrapper-bootstrap'\n", encoding='utf-8')
    (folder / 'build.gradle').write_text('', encoding='utf-8')
    checksum_file = TOOLS / 'gradle-provenance.json'
    cmd = [str(gradle), '--no-daemon', 'wrapper', '--gradle-version', GRADLE_VERSION, '--distribution-type', 'bin', '--no-validate-url']
    if checksum_file.exists():
        cmd += ['--gradle-distribution-sha256-sum', json.loads(checksum_file.read_text())['sha256']]
    if execute(cmd, cwd=folder, env=env).returncode: raise BuildError('Gradle wrapper generation failed')
    for name in ['gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties']:
        target = ROOT / name; target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(folder / name, target)


def verify_apk(apk: Path, sdk: Path, env: dict, lock: dict, secret: str) -> dict:
    bt = sdk / 'build-tools/35.0.0'
    aapt = bt / ('aapt.exe' if os.name == 'nt' else 'aapt')
    signer = bt / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
    badging = execute([str(aapt), 'dump', 'badging', str(apk)], capture=True, env=env)
    if badging.returncode or f"package: name='{APPLICATION_ID}'" not in badging.stdout:
        raise BuildError('APK package ID verification failed')
    if "application-label:'晴川'" not in badging.stdout:
        raise BuildError('APK label verification failed')
    signature = execute([str(signer), 'verify', '--verbose', '--print-certs', str(apk)], capture=True, env=env)
    if signature.returncode: raise BuildError('APK signature verification failed')
    descriptor = ('L' + lock['bright_api'].replace('.', '/') + ';').encode()
    with zipfile.ZipFile(apk) as archive:
        if not any(descriptor in archive.read(n) for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n)):
            raise BuildError('Real BrightApi class not found in APK DEX files')
    if sha256(SDK_AAR) != lock['aar_sha256']: raise BuildError('AAR changed during build')
    reject_secret_in_zip(apk, secret)
    match = re.search(r'Signer #1 certificate SHA-256 digest: (\S+)', signature.stdout)
    return {'package_verified': True, 'label_verified': True, 'signature_verified': True,
            'bright_api_present_in_dex': True, 'credential_literal_scan_performed': bool(secret),
            'signing_certificate_sha256': match.group(1) if match else 'see local apksigner output'}

def build(args):
    env = java_environment()
    env, sdk = sdk_environment(env)
    secret = os.environ.get('SDK_API_KEY', '').strip()
    if args.aar:
        lock = import_sdk(Path(args.aar).expanduser().resolve(), args.sdk_version or 'manually-supplied', 'user-supplied-aar')
    elif LOCK_FILE.exists() and SDK_AAR.exists() and not args.refresh_sdk:
        lock = json.loads(LOCK_FILE.read_text(encoding='utf-8'))
        if sha256(SDK_AAR) != lock['aar_sha256']: raise BuildError('Pinned AAR checksum mismatch. Re-import a verified SDK.')
        if args.sdk_version and args.sdk_version not in ('latest', lock['sdk_version']):
            raise BuildError('Requested SDK differs from the pinned version. Use --refresh-sdk explicitly.')
        info = inspect_sdk(SDK_AAR)
        GENERATED.parent.mkdir(parents=True, exist_ok=True)
        GENERATED.write_text(generate_binding(info, lock['sdk_version']), encoding='utf-8')
        print('Reusing pinned SDK:', lock['sdk_version'])
    else:
        lock, secret = fetch_sdk(args.sdk_version or 'latest', args.downloader)
    gradle = get_gradle()
    prepare_wrapper(gradle, env)
    # The secret is absent from all Gradle / Java child environments.
    result = execute([str(gradle), '--no-daemon', ':app:assembleDebug', ':app:lintDebug'], env=env)
    if result.returncode: raise BuildError('Android build or lint failed. No APK is published to output/.')
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    if not apk.exists(): raise BuildError('Build returned success but expected APK is missing')
    checks = verify_apk(apk, sdk, env, lock, secret)
    output = ROOT / 'output'; output.mkdir(exist_ok=True)
    published = output / f"Qingchuan-Bright-0.1.0-sdk-{lock['sdk_version']}-debug.apk"
    shutil.copy2(apk, published)
    manifest = {'built_at_utc': datetime.now(timezone.utc).isoformat(), 'application_id': APPLICATION_ID,
                'application_label': '晴川', 'version_name': '0.1.0', 'compile_sdk': 35, 'target_sdk': 35,
                'min_sdk': 24, 'gradle': GRADLE_VERSION, 'android_gradle_plugin': '8.9.2',
                'sdk': lock, 'apk_name': published.name, 'apk_sha256': sha256(published),
                'verification': checks, 'real_device_tested_by_this_script': False}
    save_json(output / 'build-manifest.json', manifest)
    (output / 'SHA256SUMS.txt').write_text(f"{sha256(published)}  {published.name}\n", encoding='ascii')
    print('\nAPK built and verified:', published)
    print('Application ID:', APPLICATION_ID)
    print('Runtime consent, real-device behavior and platform approval still require your validation.')


def main() -> int:
    if sys.version_info < (3, 10): raise BuildError('Python 3.10 or newer is required')
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('doctor', help='Check local prerequisites without downloading anything')
    f = commands.add_parser('fetch', help='Fetch the genuine SDK and generate its typed binding')
    f.add_argument('--sdk-version', default='latest'); f.add_argument('--downloader')
    i = commands.add_parser('import-sdk', help='Use an official AAR you already downloaded')
    i.add_argument('--aar', required=True); i.add_argument('--sdk-version', default='manually-supplied')
    d = commands.add_parser('downloader', help='Obtain or compile the official Rust downloader')
    d.add_argument('--from-source', action='store_true')
    b = commands.add_parser('build', help='Fetch (or reuse pinned) SDK, compile Android APK, verify output')
    b.add_argument('--sdk-version'); b.add_argument('--refresh-sdk', action='store_true')
    b.add_argument('--aar'); b.add_argument('--downloader')
    args = parser.parse_args()
    if args.command == 'doctor': return doctor()
    if args.command == 'fetch': fetch_sdk(args.sdk_version, args.downloader)
    elif args.command == 'import-sdk': import_sdk(Path(args.aar).resolve(), args.sdk_version, 'user-supplied-aar')
    elif args.command == 'downloader':
        print(build_downloader_source() if args.from_source else get_downloader())
    elif args.command == 'build': build(args)
    return 0

if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (BuildError, SdkError, OSError, ValueError) as exc:
        print('ERROR:', scrub(str(exc), os.environ.get('SDK_API_KEY', '')), file=sys.stderr)
        raise SystemExit(2)
    except KeyboardInterrupt:
        print('\nCancelled. No new verified APK was published.', file=sys.stderr)
        raise SystemExit(130)
