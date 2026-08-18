#!/usr/bin/env python3
from pathlib import Path
import re, runpy

root = Path(__file__).resolve().parents[1]
base = Path(__file__).with_name('run_security_remediation_wave2.py')
text = base.read_text(encoding='utf-8')

# Current-source compatibility fixes.
text = text.replace("replace_once(wallet, '                    (totalIn - amountSat - feeSatActual).coerceAtLeast(0L)\\n', '                    (totalIn - recipientAmountSat - feeSatActual).coerceAtLeast(0L)\\n')\n", '', 1)
old = """s,n=re.subn(r'wm\\.sendRvnLocal\\(toAddress, amount\\) \\{ progress ->', 'wm.sendRvnLocal(toAddress, amount, explicitMax) { progress ->', s, count=1)\nif n!=1: raise SystemExit(f'MainViewModel sendRvnLocal caller count={n}')"""
new = """s, n = re.subn(r'wm\\.sendRvnLocal\\(toAddress,\\s*([A-Za-z_][A-Za-z0-9_]*)\\)\\s*\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*->', lambda m: f'wm.sendRvnLocal(toAddress, {m.group(1)}, explicitMax) {{ {m.group(2)} ->', s, count=1)\nif n != 1: raise SystemExit(f'MainViewModel sendRvnLocal caller count={n}')"""
if old not in text: raise SystemExit('send caller compatibility block missing')
text = text.replace(old, new, 1)
text = text.replace("replace_once(suntest, '        return encrypted.toHex() to truncated.copyOf(4).toHex()\\n', '        return encrypted.toHex() to truncated.toHex()\\n')", "replace_once(suntest, '        val mHex = truncated.copyOf(4).joinToString(\"\") { \"%02x\".format(it) }\\n', '        val mHex = truncated.joinToString(\"\") { \"%02x\".format(it) }\\n')", 1)

# Add exact-sent accounting before tests.
needle = "write(main,s)\n\n# ---------------------------------------------------------------------------\n# Tests:"
extra = r'''wallet_text = read(wallet)
old_return = '            "$txid|fee:$feeSatActual|change:$changeSat"\n'
new_return = '            "$txid|fee:$feeSatActual|change:$changeSat|sent:$recipientAmountSat"\n'
if wallet_text.count(old_return) != 1: raise SystemExit('wallet result encoding mismatch')
write(wallet, wallet_text.replace(old_return, new_return, 1))

s = read(main)
anchor = '                val cycledSat = result.substringAfter("|change:", "0").toLongOrNull() ?: 0L\n'
insert = anchor + '                val sentSatActual = result.substringAfter("|sent:", "0").toLongOrNull() ?: (amount * 1e8).toLong()\n                val sentRvnActual = sentSatActual / 1e8\n'
if s.count(anchor) != 1: raise SystemExit('MainViewModel result parsing mismatch')
s = s.replace(anchor, insert, 1)
s = s.replace('sendResult = s.walletSendResult.replace("%1", amount.toString())', 'sendResult = s.walletSendResult.replace("%1", sentRvnActual.toString())', 1)
s = s.replace('(currentBalance - amount - feeRvn).coerceAtLeast(0.0)', '(currentBalance - sentRvnActual - feeRvn).coerceAtLeast(0.0)', 1)
s = s.replace('val sentSatOpt = (amount * 1e8).toLong()', 'val sentSatOpt = sentSatActual', 1)
write(main, s)

# ---------------------------------------------------------------------------
# Tests:'''
if text.count(needle) != 1: raise SystemExit('MainActivity write/test boundary missing')
text = text.replace(needle, extra, 1)

# Defer the generic residual-broadcast assert until the special reissue shape is wrapped.
old_assert = "if 'node.broadcastWithAllServers(tx.hex)' in final_wallet: raise SystemExit('direct WalletManager broadcast bypass remains')"
if text.count(old_assert) != 1: raise SystemExit('residual broadcast assert missing')
text = text.replace(old_assert, "pass  # reissue val-tx-if shape wrapped by v5 after base transform", 1)
base.write_text(text, encoding='utf-8')
runpy.run_path(str(base), run_name='__main__')

wallet_path = root / 'android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt'
s = wallet_path.read_text(encoding='utf-8')
start_marker = '            val tx = if (!needsMultiKey) {'
end_marker = '            val txid = node.broadcastWithAllServers(tx.hex)'
start = s.find(start_marker)
end = s.find(end_marker, start)
if start < 0 or end < 0: raise SystemExit('reissue tx/broadcast shape not found')
block = s[start:end]
# Keep the existing conditional builder logic, but execute it only after reservation.
conditional = block[len('            val tx = '):].rstrip()
wrapped = '''            val tx = signAndBroadcastReserved(
                inputs = rvnUtxos + ownerAssetUtxos + otherAssetUtxos.values.flatten().map { it.utxo },
                broadcaster = { raw -> node.broadcastWithAllServers(raw) }
            ) {
                ''' + conditional + '''
            }
'''
s = s[:start] + wrapped + s[end:].replace(end_marker, '            val txid = tx.txid', 1)
wallet_path.write_text(s, encoding='utf-8')

final = wallet_path.read_text(encoding='utf-8')
if 'node.broadcastWithAllServers(tx.hex)' in final: raise SystemExit('direct tx.hex broadcast remains after reissue wrap')
if 'broadcastConsolidation(tx.hex)' in final: raise SystemExit('direct consolidation tx.hex broadcast remains')
print('wave2 v5: all direct WalletManager tx.hex broadcasts are behind reservation boundary')
