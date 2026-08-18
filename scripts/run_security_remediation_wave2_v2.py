#!/usr/bin/env python3
from pathlib import Path
import runpy

path = Path(__file__).with_name('run_security_remediation_wave2.py')
text = path.read_text(encoding='utf-8')

# Fix the duplicate change-branch assertion from the first wave2 draft.
line = "replace_once(wallet, '                    (totalIn - amountSat - feeSatActual).coerceAtLeast(0L)\\n', '                    (totalIn - recipientAmountSat - feeSatActual).coerceAtLeast(0L)\\n')\n"
if text.count(line) != 1:
    raise SystemExit('duplicate-change assertion not found exactly once')
text = text.replace(line, '', 1)

# Replace the too-specific MainViewModel caller substitution with a syntax-tolerant
# version. Current source may use either a trailing lambda or named onProgress.
old = """s,n=re.subn(r'wm\\.sendRvnLocal\\(toAddress, amount\\) \\{ progress ->', 'wm.sendRvnLocal(toAddress, amount, explicitMax) { progress ->', s, count=1)\nif n!=1: raise SystemExit(f'MainViewModel sendRvnLocal caller count={n}')"""
new = """n_total = 0
s, n = re.subn(
    r'wm\\.sendRvnLocal\\(toAddress,\\s*([A-Za-z_][A-Za-z0-9_]*)\\)\\s*\\{\\s*progress\\s*->',
    lambda m: f'wm.sendRvnLocal(toAddress, {m.group(1)}, explicitMax) {{ progress ->',
    s, count=1
)
n_total += n
if n_total == 0:
    s, n = re.subn(
        r'wm\\.sendRvnLocal\\(toAddress,\\s*([A-Za-z_][A-Za-z0-9_]*),\\s*onProgress\\s*=\\s*\\{\\s*progress\\s*->',
        lambda m: f'wm.sendRvnLocal(toAddress, {m.group(1)}, explicitMax, onProgress = {{ progress ->',
        s, count=1
    )
    n_total += n
if n_total != 1:
    raise SystemExit(f'MainViewModel sendRvnLocal caller count={n_total}')"""
if text.count(old) != 1:
    raise SystemExit('too-specific MainViewModel replacement block not found')
text = text.replace(old, new, 1)

path.write_text(text, encoding='utf-8')
runpy.run_path(str(path), run_name='__main__')
