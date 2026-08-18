#!/usr/bin/env python3
from pathlib import Path
import runpy

path = Path(__file__).with_name('run_security_remediation_wave2.py')
text = path.read_text(encoding='utf-8')
line = "replace_once(wallet, '                    (totalIn - amountSat - feeSatActual).coerceAtLeast(0L)\\n', '                    (totalIn - recipientAmountSat - feeSatActual).coerceAtLeast(0L)\\n')\n"
if text.count(line) != 1:
    raise SystemExit('expected duplicate-change replace_once line exactly once')
text = text.replace(line, '', 1)
path.write_text(text, encoding='utf-8')
runpy.run_path(str(path), run_name='__main__')
