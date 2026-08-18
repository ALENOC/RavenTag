#!/usr/bin/env python3
from pathlib import Path
import runpy

path = Path(__file__).with_name('run_security_remediation_wave2.py')
text = path.read_text(encoding='utf-8')

line = "replace_once(wallet, '                    (totalIn - amountSat - feeSatActual).coerceAtLeast(0L)\\n', '                    (totalIn - recipientAmountSat - feeSatActual).coerceAtLeast(0L)\\n')\n"
if text.count(line) != 1:
    raise SystemExit('duplicate-change assertion not found exactly once')
text = text.replace(line, '', 1)

old = """s,n=re.subn(r'wm\\.sendRvnLocal\\(toAddress, amount\\) \\{ progress ->', 'wm.sendRvnLocal(toAddress, amount, explicitMax) { progress ->', s, count=1)\nif n!=1: raise SystemExit(f'MainViewModel sendRvnLocal caller count={n}')"""
new = """s, n = re.subn(
    r'wm\\.sendRvnLocal\\(toAddress,\\s*([A-Za-z_][A-Za-z0-9_]*)\\)\\s*\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*->',
    lambda m: f'wm.sendRvnLocal(toAddress, {m.group(1)}, explicitMax) {{ {m.group(2)} ->',
    s, count=1
)
if n != 1:
    raise SystemExit(f'MainViewModel sendRvnLocal caller count={n}')"""
if text.count(old) != 1:
    raise SystemExit('too-specific MainViewModel replacement block not found')
text = text.replace(old, new, 1)

old_sun = "replace_once(suntest, '        return encrypted.toHex() to truncated.copyOf(4).toHex()\\n', '        return encrypted.toHex() to truncated.toHex()\\n')"
new_sun = "replace_once(suntest, '        val mHex = truncated.copyOf(4).joinToString(\"\") { \"%02x\".format(it) }\\n', '        val mHex = truncated.joinToString(\"\") { \"%02x\".format(it) }\\n')"
if text.count(old_sun) != 1:
    raise SystemExit('old SUN fixture patch not found')
text = text.replace(old_sun, new_sun, 1)

needle = "write(main,s)\n\n# ---------------------------------------------------------------------------\n# Tests:"
extra = r'''wallet_text = read(wallet)
old_return = '            "$txid|fee:$feeSatActual|change:$changeSat"\n'
new_return = '            "$txid|fee:$feeSatActual|change:$changeSat|sent:$recipientAmountSat"\n'
if wallet_text.count(old_return) != 1:
    raise SystemExit(f'wallet result encoding expected once, got {wallet_text.count(old_return)}')
write(wallet, wallet_text.replace(old_return, new_return, 1))

s = read(main)
anchor = '                val cycledSat = result.substringAfter("|change:", "0").toLongOrNull() ?: 0L\n'
insert = anchor + '                val sentSatActual = result.substringAfter("|sent:", "0").toLongOrNull() ?: (amount * 1e8).toLong()\n                val sentRvnActual = sentSatActual / 1e8\n'
if s.count(anchor) != 1:
    raise SystemExit(f'MainViewModel result parsing anchor count={s.count(anchor)}')
s = s.replace(anchor, insert, 1)
s = s.replace('sendResult = s.walletSendResult.replace("%1", amount.toString())', 'sendResult = s.walletSendResult.replace("%1", sentRvnActual.toString())', 1)
s = s.replace('(currentBalance - amount - feeRvn).coerceAtLeast(0.0)', '(currentBalance - sentRvnActual - feeRvn).coerceAtLeast(0.0)', 1)
s = s.replace('val sentSatOpt = (amount * 1e8).toLong()', 'val sentSatOpt = sentSatActual', 1)
write(main, s)

# ---------------------------------------------------------------------------
# Tests:'''
if text.count(needle) != 1:
    raise SystemExit('wave2 MainActivity write/test boundary not found')
text = text.replace(needle, extra, 1)

path.write_text(text, encoding='utf-8')
runpy.run_path(str(path), run_name='__main__')
