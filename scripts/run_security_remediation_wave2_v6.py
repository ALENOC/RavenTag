#!/usr/bin/env python3
from pathlib import Path
import runpy

root = Path(__file__).resolve().parents[1]
runpy.run_path(str(Path(__file__).with_name('run_security_remediation_wave2_v5.py')), run_name='__main__')

wallet = root / 'android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt'
main = root / 'android/app/src/main/java/io/raventag/app/MainActivity.kt'

s = wallet.read_text(encoding='utf-8')
# Mutable nullable keys are known non-null at these call sites but the new signing
# lambda prevents Kotlin smart-cast. Make that invariant explicit.
s = s.replace('privKeyBytes = privKey,', 'privKeyBytes = privKey!!,')

# Old post-broadcast reservation code supplied xferNow. The new reservation is
# pre-sign, so only the pending metadata timestamp remains necessary.
if s.count('submittedAt = xferNow') != 1:
    raise SystemExit(f'xferNow pending timestamp count={s.count("submittedAt = xferNow")}')
s = s.replace('submittedAt = xferNow', 'submittedAt = System.currentTimeMillis()', 1)

# The generic argument mapper emitted a typed map for extraRvnInputs=emptyList().
# There are no inputs there; omitting that empty expression is both correct and
# avoids impossible type inference.
needle = ' + (emptyList()).map { it.utxo }'
if s.count(needle) != 1:
    raise SystemExit(f'empty extra input expression count={s.count(needle)}')
s = s.replace(needle, '', 1)
wallet.write_text(s, encoding='utf-8')

m = main.read_text(encoding='utf-8')
if m.count('onSend = viewModel::sendRvn') != 1:
    raise SystemExit('SendRvn callback reference mismatch')
m = m.replace(
    'onSend = viewModel::sendRvn',
    'onSend = { address, amount, explicitMax -> viewModel.sendRvn(address, amount, explicitMax) }',
    1
)
main.write_text(m, encoding='utf-8')

# Final source-level invariants for the integration corrections.
assert 'xferNow' not in wallet.read_text(encoding='utf-8')
assert '(emptyList()).map { it.utxo }' not in wallet.read_text(encoding='utf-8')
assert 'onSend = viewModel::sendRvn' not in main.read_text(encoding='utf-8')
print('wave2 v6 Kotlin integration fixes applied')
