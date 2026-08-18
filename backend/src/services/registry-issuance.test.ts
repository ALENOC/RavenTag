import assert from 'node:assert/strict'
import test from 'node:test'
import { validateRegistryIssuance, type VerboseRavencoinTx } from './registry-issuance.js'

function baseRootTx(): VerboseRavencoinTx {
  return {
    txid: 'aa'.repeat(32),
    vout: [
      {
        value: 500,
        scriptPubKey: {
          type: 'pubkeyhash',
          addresses: ['RXissueAssetXXXXXXXXXXXXXXXXXhhZGt']
        }
      },
      {
        value: 0,
        scriptPubKey: {
          type: 'new_asset',
          asset: { name: 'RAVENTAG', amount: 100, units: 0, reissuable: true }
        }
      },
      {
        value: 0,
        scriptPubKey: {
          type: 'new_asset',
          asset: { name: 'RAVENTAG!', amount: 1 }
        }
      }
    ]
  }
}

test('accepts a canonical root issuance claim', () => {
  const tx = baseRootTx()
  const r = validateRegistryIssuance(tx, tx.txid!, 'RAVENTAG', 'root')
  assert.equal(r.ok, true)
})

test('rejects an unrelated valid transaction id / ordinary payment', () => {
  const tx: VerboseRavencoinTx = {
    txid: 'bb'.repeat(32),
    vout: [{ value: 1, scriptPubKey: { type: 'pubkeyhash', addresses: ['R9dummy'] } }]
  }
  assert.equal(validateRegistryIssuance(tx, tx.txid!, 'RAVENTAG', 'root').ok, false)
})

test('rejects asset-name mismatch', () => {
  const tx = baseRootTx()
  assert.equal(validateRegistryIssuance(tx, tx.txid!, 'OTHER', 'root').ok, false)
})

test('rejects type mismatch', () => {
  const tx = baseRootTx()
  assert.equal(validateRegistryIssuance(tx, tx.txid!, 'RAVENTAG', 'sub').ok, false)
})

test('rejects missing canonical burn', () => {
  const tx = baseRootTx()
  tx.vout = tx.vout!.filter(v => !v.scriptPubKey?.addresses?.includes('RXissueAssetXXXXXXXXXXXXXXXXXhhZGt'))
  assert.equal(validateRegistryIssuance(tx, tx.txid!, 'RAVENTAG', 'root').ok, false)
})

test('rejects root issuance missing owner token', () => {
  const tx = baseRootTx()
  tx.vout = tx.vout!.filter(v => v.scriptPubKey?.asset?.name !== 'RAVENTAG!')
  assert.equal(validateRegistryIssuance(tx, tx.txid!, 'RAVENTAG', 'root').ok, false)
})

test('accepts unique issuance without creating a unique owner token', () => {
  const tx: VerboseRavencoinTx = {
    txid: 'cc'.repeat(32),
    vout: [
      { value: 5, scriptPubKey: { type: 'pubkeyhash', addresses: ['RXissueUniqueAssetXXXXXXXXXXWEAe58'] } },
      { value: 0, scriptPubKey: { type: 'new_asset', asset: { name: 'RAVENTAG/ITEM#001', amount: 1, units: 0, reissuable: false } } }
    ]
  }
  assert.equal(validateRegistryIssuance(tx, tx.txid!, 'RAVENTAG/ITEM#001', 'unique').ok, true)
})
