#!/usr/bin/env python3
from pathlib import Path
import runpy

path = Path(__file__).with_name('apply_security_remediation_wave1.py')
text = path.read_text(encoding='utf-8')
old = "new, count = re.subn(pattern, replacement, text, count=1, flags=flags)"
new = "new, count = re.subn(pattern, lambda _match: replacement, text, count=1, flags=flags)"
if text.count(old) != 1:
    raise SystemExit('wave1 patcher regex helper did not match exactly once')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
runpy.run_path(str(path), run_name='__main__')
