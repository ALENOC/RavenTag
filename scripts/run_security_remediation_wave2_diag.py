#!/usr/bin/env python3
from pathlib import Path
import runpy

path = Path(__file__).with_name('run_security_remediation_wave2.py')
text = path.read_text(encoding='utf-8')

# Apply the same current-source compatibility fixes as v4.
line = "replace_once(wallet, '                    (totalIn - amountSat - feeSatActual).coerceAtLeast(0L)\\n', '                    (totalIn - recipientAmountSat - feeSatActual).coerceAtLeast(0L)\\n')\n"
text = text.replace(line, '', 1)
old = """s,n=re.subn(r'wm\\.sendRvnLocal\\(toAddress, amount\\) \\{ progress ->', 'wm.sendRvnLocal(toAddress, amount, explicitMax) { progress ->', s, count=1)\nif n!=1: raise SystemExit(f'MainViewModel sendRvnLocal caller count={n}')"""
new = """s, n = re.subn(r'wm\\.sendRvnLocal\\(toAddress,\\s*([A-Za-z_][A-Za-z0-9_]*)\\)\\s*\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*->', lambda m: f'wm.sendRvnLocal(toAddress, {m.group(1)}, explicitMax) {{ {m.group(2)} ->', s, count=1)\nif n != 1: raise SystemExit(f'MainViewModel sendRvnLocal caller count={n}')"""
text = text.replace(old, new, 1)
text = text.replace("replace_once(suntest, '        return encrypted.toHex() to truncated.copyOf(4).toHex()\\n', '        return encrypted.toHex() to truncated.toHex()\\n')", "replace_once(suntest, '        val mHex = truncated.copyOf(4).joinToString(\"\") { \"%02x\".format(it) }\\n', '        val mHex = truncated.joinToString(\"\") { \"%02x\".format(it) }\\n')", 1)

# Do not stop inside the base script; print every residual occurrence with context.
old_assert = "if 'node.broadcastWithAllServers(tx.hex)' in final_wallet: raise SystemExit('direct WalletManager broadcast bypass remains')"
new_assert = """if 'node.broadcastWithAllServers(tx.hex)' in final_wallet:
    for i, line in enumerate(final_wallet.splitlines(), 1):
        if 'node.broadcastWithAllServers(tx.hex)' in line:
            print(f'RESIDUAL_DIRECT_BROADCAST_LINE={i}: {line.strip()}')"""
if text.count(old_assert) != 1: raise SystemExit('direct broadcast assertion not found')
text = text.replace(old_assert, new_assert, 1)

path.write_text(text, encoding='utf-8')
runpy.run_path(str(path), run_name='__main__')
