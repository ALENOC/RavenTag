import axios, { AxiosInstance } from 'axios'
import { electrumXService } from './electrumx.js'

export interface RaventagMetadata {
  raventag_version: 'RTP-1'
  /** Canonical protocol identifier (v1.1+), same value as raventag_version */
  protocol?: 'RTP-1'
  parent_asset: string
  sub_asset?: string
  variant_asset?: string
  nfc_pub_id: string
  crypto_type: 'ntag424_sun'
  algo: 'aes-128'
  /**
   * Note: IPFS metadata is immutable. This field reflects the state at issuance time only.
   * For real-time revocation, use the backend API (/api/brand/revoke) which maintains
   * a SQLite revocation table checked during verification.
   */
  status?: 'active' | 'revoked'
  /** Arbitrary key-value attributes (color, size, batch, etc.) */
  attributes?: Record<string, string>
  /** Per-token image reference, stored as "ipfs://<CID>" or a plain CID */
  image?: string
  /** Per-token description set by the brand at issuance time */
  description?: string
  metadata_ipfs?: string
  brand_info?: {
    website?: string
    description?: string
    contact?: string
  }
}

export interface AssetData {
  name: string
  amount: number
  units: number
  reissuable: boolean
  has_ipfs: boolean
  ipfs_hash?: string
  metadata?: RaventagMetadata
}

export interface AssetHierarchy {
  parent: string
  subAssets: string[]
  variants: Record<string, string[]>
}

interface RpcPayload {
  jsonrpc: '1.0'
  id: string
  method: string
  params: unknown[]
}

interface RpcResponse<T> {
  result: T
  error: null | { code: number; message: string }
  id: string
}

function makeClient(baseUrl: string, user?: string, pass?: string): AxiosInstance {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (user && pass) {
    const token = Buffer.from(`${user}:${pass}`).toString('base64')
    headers['Authorization'] = `Basic ${token}`
  }
  return axios.create({ baseURL: baseUrl, headers, timeout: 10000 })
}

async function rpcCall<T>(client: AxiosInstance, method: string, params: unknown[] = []): Promise<T> {
  const payload: RpcPayload = { jsonrpc: '1.0', id: 'raventag', method, params }
  const response = await client.post<RpcResponse<T>>('/', payload)
  if (response.data.error) {
    throw new Error(`RPC error ${response.data.error.code}: ${response.data.error.message}`)
  }
  return response.data.result
}

class RavencoinService {
  private client: AxiosInstance
  private fallbackClient: AxiosInstance | null = null

  constructor() {
    const host = process.env.RVN_RPC_HOST ?? 'localhost'
    const port = process.env.RVN_RPC_PORT ?? '8766'
    const user = process.env.RVN_RPC_USER
    const pass = process.env.RVN_RPC_PASS
    this.client = makeClient(`http://${host}:${port}`, user, pass)

    const publicUrl = process.env.RVN_PUBLIC_RPC_URL
    if (publicUrl) {
      this.fallbackClient = makeClient(publicUrl)
    }
  }

  private async call<T>(method: string, params: unknown[] = []): Promise<T> {
    try {
      return await rpcCall<T>(this.client, method, params)
    } catch (_err) {
      if (this.fallbackClient) {
        return await rpcCall<T>(this.fallbackClient, method, params)
      }
      throw _err
    }
  }

  /**
   * Get raw asset data from Ravencoin node.
   */
  async getAssetData(assetName: string): Promise<AssetData | null> {
    try {
      const result = await this.call<Record<string, unknown>>('getassetdata', [assetName])
      return result as unknown as AssetData
    } catch (err: unknown) {
      if (err instanceof Error && err.message.includes('-8')) return null // Asset not found
      // RPC node unavailable: fallback to ElectrumX
      try {
        return await electrumXService.getAssetMeta(assetName)
      } catch {
        throw err // re-throw original error if ElectrumX also fails
      }
    }
  }

  /**
   * Parse IPFS metadata and return RaventagMetadata if valid RTP-1.
   */
  parseRaventagMetadata(raw: unknown): RaventagMetadata | null {
    if (typeof raw !== 'object' || raw === null) return null
    const m = raw as Record<string, unknown>
    // Accept both legacy raventag_version and new protocol field (v1.1+)
    const isRtp1 = m['raventag_version'] === 'RTP-1' || m['protocol'] === 'RTP-1'
    if (!isRtp1) return null
    if (m['crypto_type'] !== 'ntag424_sun') return null
    if (typeof m['nfc_pub_id'] !== 'string') return null
    // Normalise: ensure raventag_version is always set for downstream code
    if (!m['raventag_version']) m['raventag_version'] = 'RTP-1'
    return m as unknown as RaventagMetadata
  }

  /**
   * Get asset with parsed RTP-1 metadata from IPFS.
   * IPFS fetching is delegated to ipfs service.
   */
  async getAssetWithMetadata(
    assetName: string,
    ipfsFetcher: (uri: string) => Promise<unknown>
  ): Promise<{ asset: AssetData; metadata: RaventagMetadata | null } | null> {
    const asset = await this.getAssetData(assetName)
    if (!asset) return null

    let metadata: RaventagMetadata | null = null
    if (asset.ipfs_hash) {
      try {
        const raw = await ipfsFetcher(`ipfs://${asset.ipfs_hash}`)
        metadata = this.parseRaventagMetadata(raw)
        if (!metadata) console.warn(`[IPFS] Metadata at ${asset.ipfs_hash} did not parse as valid RTP-1`)
      } catch (err: unknown) {
        console.warn(`[IPFS] Failed to fetch metadata for ${assetName} hash=${asset.ipfs_hash}: ${(err as Error).message}`)
      }
    }

    return { asset, metadata }
  }

  /**
   * Search Ravencoin assets by name pattern.
   */
  async searchAssets(query: string): Promise<string[]> {
    try {
      const result = await this.call<string[]>('listassets', [`*${query}*`, false, 100, 0])
      return result ?? []
    } catch {
      return []
    }
  }

  /**
   * List sub-assets and unique tokens of a parent asset.
   * Includes both PARENT/CHILD (sub-assets) and PARENT/CHILD#TAG (unique tokens).
   */
  async listSubAssets(parentAsset: string): Promise<string[]> {
    try {
      const [subs, uniques] = await Promise.all([
        this.call<string[]>('listassets', [`${parentAsset}/*`, false, 200, 0]).catch(() => [] as string[]),
        this.call<string[]>('listassets', [`${parentAsset}/#*`, false, 200, 0]).catch(() => [] as string[])
      ])
      return [...(subs ?? []), ...(uniques ?? [])]
    } catch {
      return []
    }
  }

  /**
   * List all assets owned by a given Ravencoin address.
   * Primary: local Ravencoin node with assetindex=1.
   * Fallback: ElectrumX community servers (no local node required).
   * Returns null only if both the local node and all ElectrumX servers fail.
   */
  async listAssetsByAddress(address: string): Promise<Record<string, number> | null> {
    try {
      const result = await this.call<Record<string, number>>('listassetbalancesbyaddress', [address])
      return result ?? {}
    } catch {
      // Local node does not support assetindex, try ElectrumX
      try {
        const assets = await electrumXService.getAssetBalances(address)
        return Object.fromEntries(assets.map(a => [a.name, a.amount]))
      } catch {
        return null
      }
    }
  }

  /**
   * Get full asset hierarchy (parent + subs + variants).
   */
  async getAssetHierarchy(parentAsset: string): Promise<AssetHierarchy> {
    const subAssets = await this.listSubAssets(parentAsset)
    const variants: Record<string, string[]> = {}

    for (const sub of subAssets) {
      const subVariants = await this.listSubAssets(sub)
      if (subVariants.length > 0) {
        variants[sub] = subVariants
      }
    }

    return { parent: parentAsset, subAssets, variants }
  }
}

export const ravencoinService = new RavencoinService()
