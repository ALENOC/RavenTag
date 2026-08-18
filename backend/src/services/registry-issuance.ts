export type RegistryAssetType = 'root' | 'sub' | 'unique'

export interface VerboseTxOutput {
  value?: number
  scriptPubKey?: {
    type?: string
    addresses?: string[]
    address?: string
    asset?: {
      name?: string
      amount?: number
      units?: number
      reissuable?: boolean
      ipfs_hash?: string
    }
  }
}

export interface VerboseRavencoinTx {
  txid?: string
  vout?: VerboseTxOutput[]
}

const BURN_POLICY: Record<RegistryAssetType, { address: string; satoshis: number }> = {
  root:   { address: 'RXissueAssetXXXXXXXXXXXXXXXXXhhZGt',       satoshis: 50_000_000_000 },
  sub:    { address: 'RXissueSubAssetXXXXXXXXXXXXXWcwhwL',      satoshis: 10_000_000_000 },
  unique: { address: 'RXissueUniqueAssetXXXXXXXXXXWEAe58',      satoshis:    500_000_000 }
}

function classifyAssetName(name: string): RegistryAssetType | null {
  if (!/^[A-Z0-9._/#]{2,30}$/.test(name)) return null
  if (name.includes('#')) return 'unique'
  if (name.includes('/')) return 'sub'
  return 'root'
}

function outputAddresses(out: VerboseTxOutput): string[] {
  const script = out.scriptPubKey
  if (!script) return []
  if (Array.isArray(script.addresses)) return script.addresses.filter((v): v is string => typeof v === 'string')
  return typeof script.address === 'string' ? [script.address] : []
}

function valueToSatoshis(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return null
  const sat = Math.round(value * 100_000_000)
  if (!Number.isSafeInteger(sat) || Math.abs(value * 100_000_000 - sat) > 0.0001) return null
  return sat
}

/**
 * Validate a public registry notification against Ravencoin Core's verbose
 * getrawtransaction representation. Raven Core itself decodes asset scripts into
 * scriptPubKey.type + scriptPubKey.asset, so this code deliberately reuses that
 * consensus-aware decoder instead of duplicating binary asset parsing here.
 */
export function validateRegistryIssuance(
  tx: VerboseRavencoinTx,
  claimedTxid: string,
  claimedAssetName: string,
  claimedAssetType: RegistryAssetType
): { ok: true; assetName: string; assetType: RegistryAssetType } | { ok: false; reason: string } {
  const assetName = claimedAssetName.trim().toUpperCase()
  const derivedType = classifyAssetName(assetName)
  if (!derivedType) return { ok: false, reason: 'invalid asset name' }
  if (derivedType !== claimedAssetType) return { ok: false, reason: 'asset type does not match asset name' }
  if (typeof tx.txid === 'string' && tx.txid.toLowerCase() !== claimedTxid.toLowerCase()) {
    return { ok: false, reason: 'transaction id mismatch' }
  }

  const outputs = Array.isArray(tx.vout) ? tx.vout : []
  const issuanceOutputs = outputs.filter(out => {
    const script = out.scriptPubKey
    return script?.type === 'new_asset' && script.asset?.name === assetName
  })
  if (issuanceOutputs.length !== 1) {
    return { ok: false, reason: 'claimed new_asset issuance output not found exactly once' }
  }

  // Owner tokens are created by consensus for root/sub issuance. Unique tokens do
  // not create a new owner token for the unique name itself.
  if (claimedAssetType !== 'unique') {
    const ownerName = `${assetName}!`
    const ownerOutputs = outputs.filter(out =>
      out.scriptPubKey?.type === 'new_asset' &&
      out.scriptPubKey?.asset?.name === ownerName &&
      out.scriptPubKey?.asset?.amount === 1
    )
    if (ownerOutputs.length !== 1) {
      return { ok: false, reason: 'required owner-token issuance output missing' }
    }
  }

  const burn = BURN_POLICY[claimedAssetType]
  const matchingBurns = outputs.filter(out =>
    valueToSatoshis(out.value) === burn.satoshis && outputAddresses(out).includes(burn.address)
  )
  if (matchingBurns.length !== 1) {
    return { ok: false, reason: 'canonical issuance burn output missing or duplicated' }
  }

  return { ok: true, assetName, assetType: claimedAssetType }
}
