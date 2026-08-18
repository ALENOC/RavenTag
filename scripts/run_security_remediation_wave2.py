#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(p): return (ROOT / p).read_text(encoding='utf-8')
def write(p, s): (ROOT / p).write_text(s, encoding='utf-8')
def replace_once(p, old, new):
    s = read(p); n = s.count(old)
    if n != 1: raise SystemExit(f'{p}: expected one match, got {n}: {old[:120]!r}')
    write(p, s.replace(old, new, 1))
def replace_all(p, old, new, min_count=1):
    s=read(p); n=s.count(old)
    if n < min_count: raise SystemExit(f'{p}: expected >= {min_count}, got {n}: {old[:120]!r}')
    write(p, s.replace(old,new)); return n
def regex_once(p, pat, repl, flags=re.S):
    s=read(p); out,n=re.subn(pat, lambda m: repl, s, count=1, flags=flags)
    if n != 1: raise SystemExit(f'{p}: regex expected one match, got {n}: {pat[:120]}')
    write(p,out)

wallet='android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt'
dao='android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt'
txb='android/app/src/main/java/io/raventag/app/wallet/RavencoinTxBuilder.kt'
main='android/app/src/main/java/io/raventag/app/MainActivity.kt'
sendui='android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt'
suntest='android/app/src/test/java/io/raventag/app/nfc/SunVerifierTest.kt'

# ---------------------------------------------------------------------------
# RT-SEC-008: persistent reservation must CONFLICT_ABORT before signing.
# ---------------------------------------------------------------------------
replace_once(dao, 'import android.database.sqlite.SQLiteDatabase\n', 'import android.database.sqlite.SQLiteDatabase\nimport android.database.sqlite.SQLiteConstraintException\n')
regex_once(dao, r'''    fun reserve\(entries: List<ReservedUtxo>\) \{.*?\n    \}\n\n    fun releaseFor''', '''    /**
     * Atomically reserve every outpoint. Existing reservations are never replaced.
     * Returns false for a conflicting outpoint or any database failure; callers must
     * fail closed and MUST NOT sign when this returns false.
     */
    fun tryReserve(entries: List<ReservedUtxo>): Boolean {
        if (entries.isEmpty()) return false
        val db = WalletReliabilityDb.getDatabase()
        db.beginTransaction()
        return try {
            for (e in entries) {
                val cv = ContentValues().apply {
                    put("txid_in", e.txidIn)
                    put("vout", e.vout)
                    put("value_sat", e.valueSat)
                    put("submitted_txid", e.submittedTxid)
                    put("submitted_at", e.submittedAt)
                }
                val rowId = db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_ABORT)
                if (rowId == -1L) throw SQLiteConstraintException("outpoint already reserved")
            }
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    /** Backward-compatible strict wrapper used by older call sites/tests. */
    fun reserve(entries: List<ReservedUtxo>) {
        check(tryReserve(entries)) { "UTXO reservation conflict or database failure" }
    }

    /** Associate a pre-sign intent reservation with the locally computed txid. */
    fun retagReservation(fromId: String, submittedTxid: String): Boolean {
        val db = WalletReliabilityDb.getDatabase()
        val cv = ContentValues().apply { put("submitted_txid", submittedTxid) }
        return db.update(TABLE, cv, "submitted_txid = ?", arrayOf(fromId)) > 0
    }

    fun releaseFor''')

# Shared signing critical section + fail-closed reservation helper.
replace_once(wallet, 'import kotlinx.coroutines.withContext\n', 'import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.sync.Mutex\nimport kotlinx.coroutines.sync.withLock\nimport java.util.UUID\n')
replace_once(wallet, '    @Volatile private var consolidationRunning = false\n', '    @Volatile private var consolidationRunning = false\n\n    /** Serializes the security-critical reserve -> sign -> broadcast transition. */\n    private val financialSigningMutex = Mutex()\n')
insert_marker='''    private fun putCachedUtxos(address: String, rvn: List<Utxo>, assetOutpoints: Set<String>, assets: Map<String, List<AssetUtxo>>) {
        utxoCacheAddr = address
        utxoCacheTime = System.currentTimeMillis()
        utxoCacheRvn = rvn
        utxoCacheAssetOutpoints = assetOutpoints
        utxoCacheAssets = assets
    }
'''
helper=insert_marker+'''
    /**
     * Security boundary for every on-device transaction signature.
     *
     * Outpoints are durably reserved under a random intent id BEFORE [build] is
     * invoked. Once a raw transaction exists the reservation is rebound to the
     * locally-computed txid before any network I/O. If broadcast is ambiguous or
     * fails after signing, the reservation deliberately remains in place until
     * reconciliation proves the transaction absent/stale.
     */
    private suspend fun signAndBroadcastReserved(
        inputs: Collection<Utxo>,
        broadcaster: suspend (String) -> String,
        build: () -> RavencoinTxBuilder.SignedTx
    ): RavencoinTxBuilder.SignedTx = financialSigningMutex.withLock {
        val uniqueInputs = inputs.distinctBy { "${it.txid}:${it.outputIndex}" }
        require(uniqueInputs.isNotEmpty()) { "No transaction inputs to reserve" }
        val intentId = "intent:${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val entries = uniqueInputs.map {
            ReservedUtxoDao.ReservedUtxo(
                txidIn = it.txid,
                vout = it.outputIndex,
                valueSat = it.satoshis,
                submittedTxid = intentId,
                submittedAt = now
            )
        }
        check(ReservedUtxoDao.tryReserve(entries)) {
            "UTXO reservation conflict or database failure; transaction not signed"
        }

        var signed: RavencoinTxBuilder.SignedTx? = null
        try {
            signed = build()
            check(ReservedUtxoDao.retagReservation(intentId, signed.txid)) {
                "Could not bind UTXO reservation to signed transaction"
            }
            val networkTxid = broadcaster(signed.hex)
            check(networkTxid.equals(signed.txid, ignoreCase = true)) {
                "Broadcast txid mismatch: local=${signed.txid}, remote=$networkTxid"
            }
            signed
        } catch (t: Throwable) {
            // Before signing it is safe to release. After signing, retain the
            // reservation because a network failure may have an ambiguous outcome.
            if (signed == null) ReservedUtxoDao.releaseFor(intentId)
            throw t
        }
    }
'''
replace_once(wallet, insert_marker, helper)

# ensureNoPendingSends must also fail closed when the reliability DB is unavailable.
replace_once(wallet, '''        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            // DB not initialized yet — safe to proceed
        }
        return true
''', '''        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            throw IllegalStateException("Wallet transaction state unavailable; refusing to sign", e)
        }
        return true
''')

# ---------------------------------------------------------------------------
# RT-SEC-002: builders never silently change recipient amount and always enforce
# a local absolute fee invariant even if a caller forgets to pre-validate.
# ---------------------------------------------------------------------------
replace_once(txb, '''     * The function automatically handles the "send-all / sweep" case: if
     * [amountSat] + [feeSat] exceeds the total UTXO value, the fee is subtracted
     * from the recipient amount so the sender can drain the wallet completely.
     *
''', '''     * [amountSat] is always the exact recipient amount. Explicit MAX/sweep intent
     * must be resolved by the caller before this builder is invoked; the builder
     * never reduces a recipient amount because a fee estimate is high.
     *
''')
replace_once(txb, '''        val totalIn = utxos.sumOf { it.satoshis }
        require(totalIn > feeSat) {
            "Insufficient funds to cover fee: have ${totalIn / 1e8} RVN, fee ${feeSat / 1e8} RVN"
        }

        // If the requested amount leaves no room for the fee, subtract the fee from the
        // recipient amount (send-all / sweep mode). The recipient gets slightly less.
        val effectiveAmount = if (amountSat + feeSat > totalIn) totalIn - feeSat else amountSat
        require(effectiveAmount > 546) { "Amount too small after fee deduction" }

        val changeSat = totalIn - effectiveAmount - feeSat
        val outputs = mutableListOf(TxOutput(effectiveAmount, toAddress))
''', '''        FeeSafetyPolicy.requireSafeAbsoluteFee(feeSat)
        val totalIn = utxos.sumOf { it.satoshis }
        require(amountSat > 546) { "Amount below dust limit" }
        require(totalIn >= amountSat + feeSat) {
            "Insufficient funds: have ${totalIn / 1e8} RVN, need ${amountSat / 1e8} RVN + ${feeSat / 1e8} RVN fee"
        }

        val changeSat = totalIn - amountSat - feeSat
        val outputs = mutableListOf(TxOutput(amountSat, toAddress))
''')

# Defense-in-depth: every public SignedTx builder receiving feeSat checks it.
s=read(txb)
pat=re.compile(r'(    fun buildAndSign\w*\(.*?\n    \): SignedTx \{\n)', re.S)
pos=0; pieces=[]; count=0
for m in pat.finditer(s):
    header=m.group(1)
    if 'feeSat: Long' not in header: continue
    # buildAndSign already contains the check we inserted above.
    if 'fun buildAndSign(' in header: continue
    pieces.append((m.end(), '        FeeSafetyPolicy.requireSafeAbsoluteFee(feeSat)\n'))
if not pieces: raise SystemExit('RavencoinTxBuilder: no additional fee builders found')
for idx, addition in reversed(pieces): s=s[:idx]+addition+s[idx:]
write(txb,s)

# ---------------------------------------------------------------------------
# Explicit MAX is a separate transaction intent from an ordinary send.
# ---------------------------------------------------------------------------
replace_once(wallet, '    suspend fun sendRvnLocal(toAddress: String, amountRvn: Double, onProgress: ((String) -> Unit)? = null): String = withContext(Dispatchers.IO) {\n', '    suspend fun sendRvnLocal(toAddress: String, amountRvn: Double, explicitMax: Boolean = false, onProgress: ((String) -> Unit)? = null): String = withContext(Dispatchers.IO) {\n')
replace_once(wallet, '            var broadcastRawHex: String = ""\n', '            var broadcastRawHex: String = ""\n            var recipientAmountSat: Long = amountSat\n')

# Multi-address/asset send fee and explicit MAX accounting.
replace_once(wallet, '''                val estimatedBytes   = 10 + 148 * totalInputs + 70 * (2 + totalAssetOutputs) + 34
                feeSatActual = estimatedBytes * satPerByte

                val tx = RavencoinTxBuilder.buildAndSignMultiAddressSend(
''', '''                val estimatedBytes   = 10 + 148 * totalInputs + 70 * (2 + totalAssetOutputs) + 34
                feeSatActual = FeeSafetyPolicy.calculateFee(estimatedBytes, satPerByte)
                val totalRvnIn = currentRvnKeyed.sumOf { it.utxo.satoshis } +
                    extraRvnKeyed.sumOf { it.utxo.satoshis } +
                    assetKeyed.values.flatten().sumOf { it.assetUtxo.utxo.satoshis }
                val dustForAssets = assetKeyed.values.sumOf { keyed ->
                    if (keyed.sumOf { it.assetUtxo.utxo.satoshis } > 0L) 600L else 0L
                }
                recipientAmountSat = if (explicitMax) {
                    totalRvnIn - feeSatActual - dustForAssets
                } else {
                    FeeSafetyPolicy.requireSafeNormalSendFee(feeSatActual, amountSat)
                    amountSat
                }
                require(recipientAmountSat > 546L) { "Insufficient balance after safe fee and asset dust" }
                require(totalRvnIn >= recipientAmountSat + feeSatActual + dustForAssets) {
                    "Insufficient balance for amount plus safe fee"
                }

                val tx = RavencoinTxBuilder.buildAndSignMultiAddressSend(
''')
replace_once(wallet, '                    amountSat         = amountSat,\n                    feeSat            = feeSatActual,\n', '                    amountSat         = recipientAmountSat,\n                    feeSat            = feeSatActual,\n')

# Plain send: remove implicit max conversion entirely.
regex_once(wallet, r'''                val totalIn = rvnUtxos\.sumOf \{ it\.satoshis \}\n                // Sweep / MAX detection:.*?                val tx = RavencoinTxBuilder\.buildAndSign\(''', '''                val totalIn = rvnUtxos.sumOf { it.satoshis }
                val outputsForFee = if (explicitMax) 1 else 2
                val estimatedBytes = 10 + 148 * rvnUtxos.size + 34 * outputsForFee
                feeSatActual = FeeSafetyPolicy.calculateFee(estimatedBytes, satPerByte)

                recipientAmountSat = if (explicitMax) {
                    totalIn - feeSatActual
                } else {
                    FeeSafetyPolicy.requireSafeNormalSendFee(feeSatActual, amountSat)
                    amountSat
                }
                require(recipientAmountSat > 546L) { "Amount too small after safe network fee" }
                require(totalIn >= recipientAmountSat + feeSatActual) {
                    "Insufficient balance for amount plus safe fee"
                }
                if (!explicitMax) {
                    val changeSat = totalIn - recipientAmountSat - feeSatActual
                    require(changeSat == 0L || changeSat > 546L) {
                        "Remaining change is below dust limit; lower the amount or use explicit MAX"
                    }
                }

                val tx = RavencoinTxBuilder.buildAndSign(''')
replace_once(wallet, '''                    // Pass totalIn when sweeping so buildAndSign's fee-subtraction branch fires.
                    toAddress = toAddress,
                    amountSat = if (isMaxSend) totalIn else amountSat,
''', '''                    toAddress = toAddress,
                    amountSat = recipientAmountSat,
''')
replace_once(wallet, '''                val totalInLog = rvnUtxos.sumOf { it.satoshis }
                val changeForLog = (totalInLog - (if (amountSat + feeSatActual > totalInLog) totalInLog else amountSat) - feeSatActual).coerceAtLeast(0L)
''', '''                val totalInLog = rvnUtxos.sumOf { it.satoshis }
                val changeForLog = (totalInLog - recipientAmountSat - feeSatActual).coerceAtLeast(0L)
''')
replace_once(wallet, '                    (totalIn - amountSat - feeSatActual).coerceAtLeast(0L)\n', '                    (totalIn - recipientAmountSat - feeSatActual).coerceAtLeast(0L)\n')
# There are two structurally identical change branches; update any remaining one.
replace_all(wallet, '(totalIn - amountSat - feeSatActual).coerceAtLeast(0L)', '(totalIn - recipientAmountSat - feeSatActual).coerceAtLeast(0L)', min_count=1)

# Replace common direct fee products with checked arithmetic.
for old,new in [
    ('val feeEstimate = 500L * maxOf(satPerByte, 200L)', 'val feeEstimate = FeeSafetyPolicy.calculateFee(500L, maxOf(satPerByte, 200L))'),
    ('val feeSat = estimatedBytes * satPerByte', 'val feeSat = FeeSafetyPolicy.calculateFee(estimatedBytes, satPerByte)'),
    ('feeSatActual = estimatedBytes * satPerByte', 'feeSatActual = FeeSafetyPolicy.calculateFee(estimatedBytes, satPerByte)'),
    ('val feeSatEst = estimatedBytes * satPerByte', 'val feeSatEst = FeeSafetyPolicy.calculateFee(estimatedBytes, satPerByte)'),
    ('val fundFee = (10L + 148L * currentUtxos.size + 34L * 2) * satPerByte', 'val fundFee = FeeSafetyPolicy.calculateFee(10L + 148L * currentUtxos.size + 34L * 2, satPerByte)'),
    ('val sweepFee = (10L + 148L * totalSweepInputs + 34L * (1 + sweepResult.third.size)) * satPerByte', 'val sweepFee = FeeSafetyPolicy.calculateFee(10L + 148L * totalSweepInputs + 34L * (1 + sweepResult.third.size), satPerByte)'),
]:
    if old in read(wallet): replace_all(wallet,old,new)

# Handle formula * maxOf(...) / * satPerByte cases left in the primary flows.
s=read(wallet)
s=re.sub(r'val feeSat = \((10L? \+ 148L? \* [^\n]+?)\) \* maxOf\(satPerByte, 200L\)', lambda m: f'val feeSat = FeeSafetyPolicy.calculateFee(({m.group(1)}), maxOf(satPerByte, 200L))', s)
s=re.sub(r'val feeSat = \((10 \+ 148 \* [^\n]+?)\) \* satPerByte', lambda m: f'val feeSat = FeeSafetyPolicy.calculateFee(({m.group(1)}).toLong(), satPerByte)', s)
write(wallet,s)

# ---------------------------------------------------------------------------
# Wrap every direct WalletManager builder+broadcast path with the pre-sign
# reservation security boundary. The parser understands named Kotlin arguments.
# ---------------------------------------------------------------------------
def split_top_level_args(arg_text):
    out=[]; start=0; stack=[]; in_str=False; esc=False
    pairs={')':'(',']':'[','}':'{'}
    for i,ch in enumerate(arg_text):
        if in_str:
            if esc: esc=False
            elif ch=='\\': esc=True
            elif ch=='"': in_str=False
            continue
        if ch=='"': in_str=True; continue
        if ch in '([{': stack.append(ch)
        elif ch in ')]}':
            if stack and stack[-1]==pairs[ch]: stack.pop()
        elif ch==',' and not stack:
            out.append(arg_text[start:i].strip()); start=i+1
    tail=arg_text[start:].strip()
    if tail: out.append(tail)
    return out

def parse_args(arg_text):
    named={}; positional=[]
    for item in split_top_level_args(arg_text):
        # find top-level '=' approximately; named args in these calls are simple
        m=re.match(r'^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$', item, re.S)
        if m: named[m.group(1)]=m.group(2).strip()
        else: positional.append(item)
    return named,positional

def plain(expr): return f'({expr})'
def plain_asset_list(expr): return f'({expr}).map {{ it.utxo }}'
def asset_map(expr): return f'({expr}).values.flatten().map {{ it.utxo }}'
def keyed(expr): return f'({expr}).map {{ it.utxo }}'
def keyed_asset(expr): return f'({expr}).map {{ it.assetUtxo.utxo }}'
def keyed_asset_map(expr): return f'({expr}).values.flatten().map {{ it.assetUtxo.utxo }}'

def input_expression(name,args,pos):
    if name=='buildAndSign':
        e=args.get('utxos') or (pos[0] if pos else None)
        if not e: raise SystemExit('buildAndSign without identifiable utxos')
        return plain(e)
    if name=='buildAndSignFullAddressSweep':
        return f"{plain(args['rvnUtxos'])} + {asset_map(args['assetUtxos'])}"
    if name=='buildAndSignMultiAddressSend':
        return f"{keyed(args['currentRvnInputs'])} + {keyed(args['extraRvnInputs'])} + {keyed_asset_map(args['assetInputsByName'])}"
    if name=='buildAndSignMultiAddressAssetTransfer':
        parts=[keyed_asset(args['primaryAssetInputs']), keyed_asset_map(args['otherAssetInputs']), keyed(args['rvnInputs'])]
        if 'secondaryAssetsToToAddress' in args: parts.append(keyed_asset_map(args['secondaryAssetsToToAddress']))
        return ' + '.join(parts)
    if name=='buildAndSignAssetIssueWithAssetSweep':
        return f"{plain(args['utxos'])} + {plain(args['ownerAssetUtxos'])} + {asset_map(args['otherAssetUtxos'])}"
    if name=='buildAndSignAssetReissue':
        return f"{plain(args['utxos'])} + {plain(args['ownerAssetUtxos'])} + {asset_map(args['otherAssetUtxos'])}"
    if name=='buildAndSignRvnSendWithAssetSweep':
        return f"{plain(args['rvnUtxos'])} + {asset_map(args['assetUtxos'])}"
    raise SystemExit(f'unhandled builder {name}')

def find_matching_paren(text, open_idx):
    depth=0; in_str=False; esc=False
    for i in range(open_idx,len(text)):
        ch=text[i]
        if in_str:
            if esc: esc=False
            elif ch=='\\': esc=True
            elif ch=='"': in_str=False
            continue
        if ch=='"': in_str=True; continue
        if ch=='(': depth+=1
        elif ch==')':
            depth-=1
            if depth==0: return i
    raise SystemExit('unbalanced builder call')

s=read(wallet)
pattern=re.compile(r'val tx = RavencoinTxBuilder\.(buildAndSign(?:MultiAddressAssetTransfer|MultiAddressSend|FullAddressSweep|AssetIssueWithAssetSweep|AssetReissue|RvnSendWithAssetSweep)?)\(')
# Transform from right to left so indices remain stable.
occ=list(pattern.finditer(s))
if len(occ) < 10: raise SystemExit(f'expected >=10 signing sites, got {len(occ)}')
wrapped=0
for m in reversed(occ):
    name=m.group(1); open_idx=m.end()-1; close_idx=find_matching_paren(s,open_idx)
    call=s[m.start()+len('val tx = '):close_idx+1]
    args_txt=s[open_idx+1:close_idx]
    args,pos=parse_args(args_txt)
    inputs=input_expression(name,args,pos)
    # Find the corresponding broadcast before the next tx declaration / 1400 chars.
    tail=s[close_idx+1:close_idx+1600]
    b1=re.search(r'val txid = node\.broadcastWithAllServers\(tx\.hex\)',tail)
    b2=re.search(r'node\.broadcastWithAllServers\(tx\.hex\)',tail)
    b3=re.search(r'txid = broadcastConsolidation\(tx\.hex\)',tail)
    if b1: b=b1; replacement='val txid = tx.txid'; broadcaster='{ raw -> node.broadcastWithAllServers(raw) }'
    elif b3: b=b3; replacement='txid = tx.txid'; broadcaster='{ raw -> broadcastConsolidation(raw) }'
    elif b2: b=b2; replacement='tx.txid // broadcast already completed inside reservation boundary'; broadcaster='{ raw -> node.broadcastWithAllServers(raw) }'
    else:
        # Some builder may be in a branch whose broadcast lies slightly farther away (reissue).
        far=s[close_idx+1:close_idx+3500]
        bf=re.search(r'val txid = node\.broadcastWithAllServers\(tx\.hex\)',far)
        if not bf: raise SystemExit(f'{name}: no following broadcast located near offset {m.start()}')
        b=bf; tail=far; replacement='val txid = tx.txid'; broadcaster='{ raw -> node.broadcastWithAllServers(raw) }'
    indent=s[s.rfind('\n',0,m.start())+1:m.start()]
    wrapped_call='val tx = signAndBroadcastReserved(\n'+indent+'    inputs = '+inputs+',\n'+indent+'    broadcaster = '+broadcaster+'\n'+indent+') {\n'+indent+'    '+call+'\n'+indent+'}'
    s=s[:m.start()]+wrapped_call+s[close_idx+1:]
    delta=len(wrapped_call)-(close_idx+1-m.start())
    # Broadcast index adjusted by builder replacement length.
    bstart=(close_idx+1+delta)+b.start(); bend=(close_idx+1+delta)+b.end()
    s=s[:bstart]+replacement+s[bend:]
    wrapped+=1
write(wallet,s)
print('wrapped signing sites:',wrapped)

# Remove old post-broadcast reservation blocks in normal RVN send and asset transfer;
# reservations are now inserted pre-sign by signAndBroadcastReserved.
s=read(wallet)
s,n1=re.subn(r'''\n            // Reserved-UTXO \+ pending-consolidation bookkeeping \(D-20, D-21\)\.\n            val now = System\.currentTimeMillis\(\)\n            val reserved = consumedUtxos\.map \{.*?            ReservedUtxoDao\.reserve\(reserved\)\n''','\n            val now = System.currentTimeMillis()\n',s,count=1,flags=re.S)
if n1!=1: raise SystemExit(f'send post-reservation block count={n1}')
s,n2=re.subn(r'''\n            // Reserved-UTXO \+ pending-consolidation bookkeeping \(D-20, D-21\)\.\n            val allConsumedUtxos = allFunds\.flatMap \{ af ->.*?            ReservedUtxoDao\.reserve\(allConsumedUtxos\.map \{.*?            \}\)\n''','\n',s,count=1,flags=re.S)
if n2!=1: raise SystemExit(f'transfer post-reservation block count={n2}')
# Cleanup now-unused consumedUtxos assignment/declaration.
s=s.replace('            var consumedUtxos: List<Utxo> = emptyList()\n','')
s=s.replace('                consumedUtxos = rvnUtxos\n','')
write(wallet,s)

# UI and ViewModel propagate an explicit MAX intent instead of inferring it from insolvency.
replace_once(sendui, '    onSend: (toAddress: String, amount: Double) -> Unit\n', '    onSend: (toAddress: String, amount: Double, explicitMax: Boolean) -> Unit\n')
replace_once(sendui, '    var amount by remember { mutableStateOf("") }\n', '    var amount by remember { mutableStateOf("") }\n    var explicitMax by remember { mutableStateOf(false) }\n')
replace_once(sendui, '                        onSend(toAddress, parsedAmount)\n', '                        onSend(toAddress, parsedAmount, explicitMax)\n')
replace_once(sendui, '                onValueChange = { amount = it.replace(\',\', \'.\') },\n', '                onValueChange = { amount = it.replace(\',\', \'.\'); explicitMax = false },\n')
regex_once(sendui, r'''                onClick = \{\n                    // MAX = full balance\..*?                    amount = "%.8f"\.format\(walletBalance\)\n                \},''', '''                onClick = {
                    // MAX is an explicit transaction intent. WalletManager computes
                    // the exact recipient amount only after applying the safe fee/dust policy.
                    amount = "%.8f".format(walletBalance)
                    explicitMax = true
                },''')

# MainViewModel method + call. Use regex to avoid relying on line numbers.
s=read(main)
s,n=re.subn(r'fun sendRvn\(toAddress: String, amount: Double\)', 'fun sendRvn(toAddress: String, amount: Double, explicitMax: Boolean)', s, count=1)
if n!=1: raise SystemExit(f'MainViewModel sendRvn signature count={n}')
s,n=re.subn(r'wm\.sendRvnLocal\(toAddress, amount\) \{ progress ->', 'wm.sendRvnLocal(toAddress, amount, explicitMax) { progress ->', s, count=1)
if n!=1: raise SystemExit(f'MainViewModel sendRvnLocal caller count={n}')
write(main,s)

# ---------------------------------------------------------------------------
# Tests: fix incorrect 4-byte SUN test fixture and add explicit no-implicit-max
# regression/address-network regression.
# ---------------------------------------------------------------------------
replace_once(suntest, '        return encrypted.toHex() to truncated.copyOf(4).toHex()\n', '        return encrypted.toHex() to truncated.toHex()\n')

builder_test='android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt'
s=read(builder_test)
insert='''
    @Test
    fun foreignNetworkBitcoinAddressIsRejected() {
        assertFalse(RavencoinTxBuilder.isValidRavencoinMainnetP2pkh("1BoatSLRHtKNngkdXEeobR76b53LETtpyT"))
    }

    @Test
    fun builderNeverImplicitlyConvertsNormalSendToMax() {
        val utxo = Utxo(
            txid = "11".repeat(32), outputIndex = 0, satoshis = 1_000_000_000L,
            script = "76a914" + "22".repeat(20) + "88ac"
        )
        val priv = ByteArray(32).also { it[31] = 1 }
        val pub = ByteArray(33).also { it[0] = 2; it[32] = 1 }
        try {
            RavencoinTxBuilder.buildAndSign(
                utxos = listOf(utxo),
                toAddress = "R9K8Qpz6rfMe9KvtB7w8KCpzNLKn3D2uGv",
                amountSat = 990_000_000L,
                feeSat = 20_000_000L,
                changeAddress = "R9K8Qpz6rfMe9KvtB7w8KCpzNLKn3D2uGv",
                privKeyBytes = priv,
                pubKeyBytes = pub
            )
            fail("normal send must fail instead of reducing the recipient amount")
        } catch (_: IllegalArgumentException) { }
    }
'''
# Insert before final class closing brace.
idx=s.rfind('\n}')
if idx<0: raise SystemExit('builder test closing brace not found')
s=s[:idx]+insert+s[idx:]
write(builder_test,s)

# Self-audit assertions before runner compilation.
final_wallet=read(wallet)
if 'isMaxSend' in final_wallet: raise SystemExit('implicit isMaxSend path still present')
if 'ReservedUtxoDao.reserve(reserved)' in final_wallet: raise SystemExit('post-sign send reservation still present')
if 'node.broadcastWithAllServers(tx.hex)' in final_wallet: raise SystemExit('direct WalletManager broadcast bypass remains')
if 'broadcastConsolidation(tx.hex)' in final_wallet: raise SystemExit('direct consolidation broadcast bypass remains')
if 'effectiveAmount = if' in read(txb): raise SystemExit('builder implicit max conversion remains')

print('wave2 financial remediation applied successfully')
