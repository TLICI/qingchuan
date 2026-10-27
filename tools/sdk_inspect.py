"""Inspect genuine AAR bytecode and generate a compile-time Bright V2 binding.

No vendor classes are emulated. Unsupported or ambiguous APIs cause a hard error.
"""
from __future__ import annotations
import hashlib
import io
import json
import re
import struct
import zipfile
from pathlib import Path

class SdkError(RuntimeError):
    pass

class ClassReader:
    def __init__(self, data: bytes):
        self.data, self.pos = data, 0
    def take(self, n: int) -> bytes:
        if n < 0 or self.pos + n > len(self.data):
            raise SdkError('Truncated class file')
        result = self.data[self.pos:self.pos+n]
        self.pos += n
        return result
    def u1(self): return self.take(1)[0]
    def u2(self): return struct.unpack('>H', self.take(2))[0]
    def u4(self): return struct.unpack('>I', self.take(4))[0]

def parse_class(data: bytes) -> dict:
    r = ClassReader(data)
    if r.u4() != 0xCAFEBABE:
        raise SdkError('Not a Java class file')
    r.u2(); major = r.u2()
    count = r.u2()
    pool: list = [None] * count
    i = 1
    while i < count:
        tag = r.u1()
        if tag == 1:
            pool[i] = r.take(r.u2()).decode('utf-8', errors='replace')
        elif tag in (3, 4): r.take(4)
        elif tag in (5, 6): r.take(8); i += 1
        elif tag in (7, 8, 16, 19, 20): pool[i] = (tag, r.u2())
        elif tag in (9, 10, 11, 12, 17, 18): r.take(4)
        elif tag == 15: r.take(3)
        else: raise SdkError(f'Unsupported class constant tag {tag}')
        i += 1
    access, this_class = r.u2(), r.u2()
    name = pool[pool[this_class][1]]
    r.u2()  # superclass
    r.take(2 * r.u2())  # interfaces
    def members():
        result = []
        for _ in range(r.u2()):
            flags, ni, di = r.u2(), r.u2(), r.u2()
            result.append({'name': pool[ni], 'descriptor': pool[di], 'access': flags})
            for _ in range(r.u2()):
                r.u2(); r.take(r.u4())
        return result
    fields, methods = members(), members()
    return {'name': name, 'major': major, 'access': access, 'fields': fields, 'methods': methods}

def read_aar(path: Path) -> dict[str, bytes]:
    classes: dict[str, bytes] = {}
    try:
        with zipfile.ZipFile(path) as aar:
            names = set(aar.namelist())
            if 'AndroidManifest.xml' not in names or 'classes.jar' not in names:
                raise SdkError('AAR must contain AndroidManifest.xml and classes.jar')
            if sum(i.file_size for i in aar.infolist()) > 512 * 1024 * 1024:
                raise SdkError('AAR uncompressed size exceeds safety limit')
            jars = [n for n in names if n == 'classes.jar' or (n.startswith('libs/') and n.endswith('.jar'))]
            total = 0
            for entry in jars:
                with zipfile.ZipFile(io.BytesIO(aar.read(entry))) as jar:
                    for info in jar.infolist():
                        if not info.filename.endswith('.class'): continue
                        total += info.file_size
                        if total > 512 * 1024 * 1024:
                            raise SdkError('Embedded class data exceeds safety limit')
                        blob = jar.read(info)
                        key = info.filename[:-6]
                        if key in classes and classes[key] != blob:
                            raise SdkError(f'Conflicting duplicate class: {key}')
                        classes[key] = blob
    except (OSError, zipfile.BadZipFile) as exc:
        raise SdkError(f'Invalid AAR/JAR archive: {exc.__class__.__name__}') from exc
    return classes

def require_method(info: dict, name: str, desc: str, static: bool = False):
    for m in info['methods']:
        if m['name'] == name and m['descriptor'] == desc:
            if not m['access'] & 0x0001:
                continue
            if bool(m['access'] & 0x0008) != static:
                continue
            return
    raise SdkError(f"Required public API absent: {info['name']}.{name}{desc}")

def inspect_sdk(path: Path) -> dict:
    classes = read_aar(path)
    candidates = [n for n in classes if n.rsplit('/', 1)[-1] == 'BrightApi']
    if len(candidates) != 1:
        raise SdkError(f'Expected one BrightApi class, found {len(candidates)}. No package name will be guessed.')
    api_name = candidates[0]
    api = parse_class(classes[api_name])
    settings_names = []
    for m in api['methods']:
        match = re.fullmatch(r'\(Landroid/content/Context;L([^;]+);\)V', m['descriptor'])
        if m['name'] == 'init' and match and m['access'] & 0x0009 == 0x0009:
            settings_names.append(match.group(1))
    if len(set(settings_names)) != 1:
        raise SdkError('BrightApi.init(Context, Settings) could not be identified unambiguously')
    settings_name = settings_names[0]
    if settings_name not in classes:
        raise SdkError('Settings class missing from AAR')
    settings = parse_class(classes[settings_name])
    ctx = 'Landroid/content/Context;'
    require_method(api, 'init', f'({ctx}L{settings_name};)V', static=True)
    require_method(api, 'getConsentChoice', f'({ctx})Ljava/lang/Boolean;', static=True)
    require_method(api, 'optOut', f'({ctx})V', static=True)
    try:
        require_method(api, 'showConsent', '(Landroid/app/Activity;)V', static=True)
    except SdkError:
        require_method(api, 'showConsent', f'({ctx})V', static=True)
    require_method(settings, '<init>', f'({ctx})V')
    require_method(settings, 'setSkipConsent', '(Z)V')
    require_method(settings, 'setBenefit', '(Ljava/lang/String;)V')
    require_method(settings, 'setAgreeBtn', '(Ljava/lang/String;)V')
    require_method(settings, 'setDisagreeBtn', '(Ljava/lang/String;)V')
    require_method(settings, 'setOptOutInstructions', '(Ljava/lang/String;)V')
    callbacks = [m for m in settings['methods'] if m['name'] == 'setOnStatusChange' and m['access'] & 1]
    if len(callbacks) != 1:
        raise SdkError('Cannot identify one public setOnStatusChange method')
    match = re.fullmatch(r'\(L([^;]+);\)V', callbacks[0]['descriptor'])
    if not match or match.group(1) not in classes:
        raise SdkError('Unknown consent callback interface')
    callback = parse_class(classes[match.group(1)])
    abstract = [m for m in callback['methods'] if m['access'] & 0x0400 and not m['access'] & 0x0008]
    if len(abstract) != 1 or not re.fullmatch(r'\((?:[ZBCSIJFD]|L[^;]+;)\)V', abstract[0]['descriptor']):
        raise SdkError('Consent callback is not the documented single-argument, void-returning interface')
    # Use the original consent UI. Do not fabricate a Chinese translation of vendor legal text.
    language = None
    lang_methods = [m for m in settings['methods'] if m['name'] == 'setLanguage' and m['access'] & 1]
    for m in lang_methods:
        match = re.fullmatch(r'\(L([^;]+);\)V', m['descriptor'])
        if match and match.group(1) in classes:
            lang_info = parse_class(classes[match.group(1)])
            if any(f['name'] == 'en' and f['access'] & 0x0009 == 0x0009 for f in lang_info['fields']):
                language = match.group(1)
                break
    if not language:
        raise SdkError('Documented English consent language enum was not found')
    for n in [api_name, settings_name, language]:
        if not re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)*', n):
            raise SdkError('Class name is not safe for generated Java source')
    return {
        'bright_api': api_name.replace('/', '.'),
        'settings': settings_name.replace('/', '.').replace('$', '.'),
        'language': language.replace('/', '.').replace('$', '.'),
        'callback': callback['name'].replace('/', '.'),
        'class_count': len(classes),
        'aar_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
    }

def generate_binding(info: dict, version: str) -> str:
    # JSON quoting is also valid Java string syntax for the ASCII version/hash values.
    version = str(version)
    if not re.fullmatch(r'[A-Za-z0-9_.+\-]{1,100}', version):
        raise SdkError('Invalid SDK version text')
    template = '''package com.qingchuan.connect;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import __API__;
import __SETTINGS__;

/** Generated from the real AAR. No reflection, consent bypass or SDK modification. */
final class SdkBinding {
    static final String SDK_VERSION = __VERSION__;
    static final String AAR_SHA256 = __HASH__;
    interface Listener { void onConsentChanged(); }
    private static WeakReference<Listener> listener = new WeakReference<>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean initialized;

    static synchronized void initialize(Activity activity, Listener target) {
        attach(target);
        if (initialized) return;
        Settings settings = new Settings(activity);
        settings.setSkipConsent(true);
        settings.setLanguage(__LANGUAGE__.en);
        settings.setBenefit("To support this app");
        settings.setAgreeBtn("Yes, I agree");
        settings.setDisagreeBtn("No, thank you");
        settings.setOptOutInstructions("Open this app and turn off Web Indexing.");
        settings.setOnStatusChange(choice -> MAIN.post(() -> {
            Listener current = listener.get();
            if (current != null) current.onConsentChanged();
        }));
        BrightApi.init(activity, settings);
        initialized = true;
    }
    static void attach(Listener target) { listener = new WeakReference<>(target); }
    static void detach(Listener target) {
        if (listener.get() == target) listener.clear();
    }
    static void showConsent(Activity activity) { BrightApi.showConsent(activity); }
    static void optOut(Context context) { BrightApi.optOut(context); }
    static Boolean getChoice(Context context) { return BrightApi.getConsentChoice(context); }
}
'''
    for old, new in {
        '__API__': info['bright_api'], '__SETTINGS__': info['settings'],
        '__LANGUAGE__': info['language'], '__VERSION__': json.dumps(version),
        '__HASH__': json.dumps(info['aar_sha256'])}.items():
        template = template.replace(old, new)
    return template
