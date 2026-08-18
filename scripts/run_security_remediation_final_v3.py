#!/usr/bin/env python3
from pathlib import Path
import runpy

here = Path(__file__).resolve()
root = here.parents[1]
runpy.run_path(str(here.with_name('run_security_remediation_final_v2.py')), run_name='__main__')

p = root / 'android/app/src/main/java/io/raventag/app/ravencoin/RpcClient.kt'
s = p.read_text(encoding='utf-8')
anchor = '''    private val http = if (context != null) NetworkModule.getHttpClient(context)
                       else OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .build()
'''
replacement = anchor + '''
    // IPFS/backend fetches in this component must never transparently follow a
    // redirect to a host that was not selected/validated by the caller. A 3xx is
    // handled as a non-successful response and the next explicit candidate may be
    // tried instead.
    private val noRedirectHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
'''
if s.count(anchor) != 1:
    raise SystemExit(f'RpcClient HTTP anchor count={s.count(anchor)}')
s = s.replace(anchor, replacement, 1)
count = s.count('http.newCall(')
if count < 3:
    raise SystemExit(f'unexpected RpcClient http.newCall count={count}')
s = s.replace('http.newCall(', 'noRedirectHttp.newCall(')
p.write_text(s, encoding='utf-8')
final = p.read_text(encoding='utf-8')
assert '.followRedirects(false)' in final
assert '.followSslRedirects(false)' in final
assert 'http.newCall(' not in final
print(f'final v3: {count} RpcClient network calls use explicit no-redirect client')
