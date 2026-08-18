#!/usr/bin/env python3
from pathlib import Path
import runpy

path = Path(__file__).with_name('apply_security_remediation_wave1.py')
text = path.read_text(encoding='utf-8')

# Python re replacement strings interpret backslashes (e.g. Kotlin '\u0000').
old = "new, count = re.subn(pattern, replacement, text, count=1, flags=flags)"
new = "new, count = re.subn(pattern, lambda _match: replacement, text, count=1, flags=flags)"
if text.count(old) != 1:
    raise SystemExit('wave1 patcher regex helper did not match exactly once')
text = text.replace(old, new, 1)

# The current IPFS reader uses a literal 1 MiB expression, not the older
# MAX_RESPONSE_BYTES name. Patch only the read path; uploads remain local Kubo calls.
old_block = """replace_all_required(
    ipfs,
    '        maxContentLength: MAX_RESPONSE_BYTES,\\n',
    '        maxContentLength: MAX_RESPONSE_BYTES,\\n        maxRedirects: 0,\\n',
    minimum=1
)"""
new_block = """replace_once(
    ipfs,
    '    maxContentLength: 1024 * 1024        // reject responses larger than 1 MB\\n',
    '    maxContentLength: 1024 * 1024,       // reject responses larger than 1 MB\\n    maxRedirects: 0                          // do not allow redirect SSRF escapes\\n'
)"""
if text.count(old_block) != 1:
    raise SystemExit('wave1 IPFS patch block did not match exactly once')
text = text.replace(old_block, new_block, 1)

path.write_text(text, encoding='utf-8')
runpy.run_path(str(path), run_name='__main__')
