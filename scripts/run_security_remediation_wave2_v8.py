#!/usr/bin/env python3
from pathlib import Path
import runpy

root = Path(__file__).resolve().parents[1]
runpy.run_path(str(Path(__file__).with_name('run_security_remediation_wave2_v7.py')), run_name='__main__')

test = root / 'android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt'
s = test.read_text(encoding='utf-8')

# The generated security regression cases use these two JUnit assertions.
if 'import org.junit.Assert.assertFalse\n' not in s:
    anchor = 'import org.junit.Assert.assertEquals\n'
    if s.count(anchor) != 1: raise SystemExit('assert import anchor mismatch')
    s = s.replace(anchor, anchor + 'import org.junit.Assert.assertFalse\nimport org.junit.Assert.fail\n', 1)

# Utxo includes confirmation height in the production model.
needle = '            script = "76a914" + "22".repeat(20) + "88ac"\n        )'
replacement = '            script = "76a914" + "22".repeat(20) + "88ac",\n            height = 100\n        )'
if s.count(needle) != 1:
    raise SystemExit(f'generated Utxo regression vector anchor count={s.count(needle)}')
s = s.replace(needle, replacement, 1)

test.write_text(s, encoding='utf-8')
print('wave2 v8: generated security regression tests compile against current Utxo/JUnit API')
