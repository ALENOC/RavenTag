#!/usr/bin/env python3
from pathlib import Path
import runpy

here = Path(__file__).resolve()
root = here.parents[1]
runpy.run_path(str(here.with_name('run_security_remediation_final.py')), run_name='__main__')

p = root / 'android/app/src/main/java/io/raventag/app/nfc/NfcReader.kt'
s = p.read_text(encoding='utf-8')
old = '''            val prefix = uriPrefix(payload[0]) ?: continue
            val suffix = if (payload.size > 1) String(payload, 1, payload.size - 1, Charsets.UTF_8) else ""
'''
new = '''            val prefixCode = payload.firstOrNull() ?: continue
            val prefix = uriPrefix(prefixCode) ?: continue
            val suffix = if (payload.size > 1) String(payload, 1, payload.size - 1, Charsets.UTF_8) else ""
'''
if s.count(old) != 1:
    raise SystemExit(f'NDEF prefix anchor count={s.count(old)}')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
assert 'payload[0]' not in p.read_text(encoding='utf-8')
print('final v2: NDEF prefix byte now uses bounds-safe firstOrNull()')
