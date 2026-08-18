#!/usr/bin/env python3
from pathlib import Path
import re, runpy

base = Path(__file__).with_name('run_security_remediation_wave3.py')
runpy.run_path(str(base), run_name='__main__')

root = Path(__file__).resolve().parents[1]
wallet = root / 'android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt'
s = wallet.read_text(encoding='utf-8')
pat = re.compile(r'''    private fun mnemonicToSeed\(mnemonic: String, passphrase: String\): ByteArray \{.*?\n    \}\n\n    suspend fun healAndSweepTarget''', re.S)
replacement = '''    private fun mnemonicToSeed(mnemonic: String, passphrase: String): ByteArray =
        Bip39Kdf.deriveSeed(mnemonic, passphrase)

    suspend fun healAndSweepTarget'''
s, n = pat.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit(f'BIP39 production delegation replacement count={n}')
wallet.write_text(s, encoding='utf-8')

assert 'Bip39Kdf.deriveSeed(mnemonic, passphrase)' in s
print('wave3 full: production BIP39 KDF now uses the vector-tested implementation')
