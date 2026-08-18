#!/usr/bin/env python3
from pathlib import Path
import runpy

root = Path(__file__).resolve().parents[1]
runpy.run_path(str(Path(__file__).with_name('run_security_remediation_wave2_v6.py')), run_name='__main__')

main = root / 'android/app/src/main/java/io/raventag/app/MainActivity.kt'
s = main.read_text(encoding='utf-8')

# Final post-condition: the ViewModel transaction intent MUST explicitly carry
# whether MAX was requested. Apply this after all compatibility wrappers so no
# later transformation can accidentally restore the old two-argument method.
old_sig = '    fun sendRvn(toAddress: String, amount: Double) {\n'
new_sig = '    fun sendRvn(toAddress: String, amount: Double, explicitMax: Boolean) {\n'
if old_sig in s:
    s = s.replace(old_sig, new_sig, 1)

old_call = '                        wm.sendRvnLocal(toAddress, amount) { msg ->\n'
new_call = '                        wm.sendRvnLocal(toAddress, amount, explicitMax) { msg ->\n'
if old_call in s:
    s = s.replace(old_call, new_call, 1)

main.write_text(s, encoding='utf-8')
final = main.read_text(encoding='utf-8')
if 'fun sendRvn(toAddress: String, amount: Double) {' in final:
    raise SystemExit('two-argument MainViewModel.sendRvn still present')
if 'wm.sendRvnLocal(toAddress, amount) {' in final:
    raise SystemExit('sendRvnLocal still called without explicit MAX intent')
if 'fun sendRvn(toAddress: String, amount: Double, explicitMax: Boolean)' not in final:
    raise SystemExit('three-argument sendRvn postcondition missing')
print('wave2 v7: explicit MAX intent is present end-to-end')
