/**
 * @file services/electrumx.ts
 *
 * ElectrumX client for Ravencoin.
 *
 * Provides a fallback path for blockchain queries when the full Ravencoin RPC
 * node is unavailable or lacks the asset index. ElectrumX servers expose a
 * JSON-RPC-over-TLS protocol (Electrum protocol 1.4) that supports:
 *   - Address balance queries (via script hash)
 *   - UTXO listing with asset annotations
 *   - Raw transaction broadcast
 *   - Asset metadata retrieval (blockchain.asset.get_meta)
 *
 * Security model:
 *   - TLS is required for all connections (port 50002).
 *   - Self-signed certificates are accepted (common in the Ravencoin ecosystem).
 *   - Trust-On-First-Use (TOFU) pinning is applied: the SHA-256 fingerprint of
 *     the server certificate is persisted in SQLite on the first connection
 *     and compared on every subsequent connection — across restarts. A
 *     fingerprint change is treated as a potential MITM and the connection is
 *     rejected. A pin-store failure fails closed (no new enrollment).
 *
 * Failover:
 *   Multiple public ElectrumX servers are tried in order. If a server is
 *   unreachable or returns an error, the next one is tried automatically.
 */

import * as tls from 'tls'
import { createHash } from 'crypto'
import { getDb } from '../middleware/cache.js'

// ── Address helpers ────────────────────────────────────────────────────────────

/**
 * Base58 alphabet used by Bitcoin-derived chains including Ravencoin.
 * Note: digits 0, O, I, l are omitted to avoid visual ambiguity.
 */
const BASE58_CHARS = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'

/**
 * Decode a Base58-encoded string into a Buffer.
 *
 * Interprets the input as a big-endian base-58 integer, then converts it to
 * a byte array. Leading "1" characters in Base58 map to leading zero bytes.
 *
 * @param input - Base58 string (e.g., a Ravencoin address)
 * @returns Raw bytes as a Buffer
 * @throws If any character is not in the Base58 alphabet
 */
function base58Decode(input: string): Buffer {
  let num = BigInt(0)
  for (const char of input) {
    const digit = BASE58_CHARS.indexOf(char)
    if (digit < 0) throw new Error(`Invalid Base58 character: ${char}`)
    // Shift the accumulator left in base-58 and add the new digit
    num = num * BigInt(58) + BigInt(digit)
  }

  // Convert the big integer to a byte array (big-endian)
  const bytes: number[] = []
  while (num > 0n) {
    bytes.unshift(Number(num % 256n))
    num = num / 256n
  }

  // Each leading "1" in the Base58 string encodes a leading zero byte
  let leading = 0
  for (const c of input) {
    if (c === '1') leading++; else break
  }
  return Buffer.from([...Array(leading).fill(0), ...bytes])
}

/**
 * Convert a Ravencoin P2PKH address to an ElectrumX script hash.
 *
 * ElectrumX identifies addresses by the SHA-256 hash of their locking script,
 * reversed to little-endian (as used in Bitcoin script hash protocols).
 *
 * For P2PKH, the locking script is:
 *   OP_DUP (0x76) OP_HASH160 (0xa9) <push 20 bytes> (0x14) <hash160> OP_EQUALVERIFY (0x88) OP_CHECKSIG (0xac)
 *
 * @param address - Ravencoin P2PKH address (34 chars, starting with "R")
 * @returns Reversed SHA-256 of the locking script, as a lowercase hex string
 * @throws If the decoded address is not exactly 25 bytes (version + hash160 + checksum)
 */
function addressToScripthash(address: string): string {
  const decoded = base58Decode(address)

  // A valid P2PKH address decodes to: 1 byte version + 20 bytes hash160 + 4 bytes checksum = 25 bytes
  if (decoded.length !== 25) throw new Error(`Invalid address length: ${decoded.length}`)
  if (decoded[0] !== 0x3c) throw new Error('Address is not Ravencoin mainnet P2PKH')
  const payload = decoded.subarray(0, 21)
  const expectedChecksum = createHash('sha256').update(
    createHash('sha256').update(payload).digest()
  ).digest().subarray(0, 4)
  if (!decoded.subarray(21).equals(expectedChecksum)) throw new Error('Invalid Ravencoin address checksum')

  // Extract the 20-byte RIPEMD-160 hash
  const hash160 = decoded.slice(1, 21)

  // Build the P2PKH locking script: OP_DUP OP_HASH160 <push20> <hash160> OP_EQUALVERIFY OP_CHECKSIG
  const script = Buffer.concat([Buffer.from([0x76, 0xa9, 0x14]), hash160, Buffer.from([0x88, 0xac])])

  // SHA-256 of script, then reverse bytes for ElectrumX's little-endian convention
  const hash = createHash('sha256').update(script).digest()
  return Buffer.from(hash).reverse().toString('hex')
}

// ── ElectrumX client ───────────────────────────────────────────────────────────

/** Configuration for a single ElectrumX server endpoint. */
interface ElectrumServer { host: string; port: number }

/**
 * Known public Ravencoin ElectrumX servers, tried in order.
 * RT108-SEC-203: aligned with the mobile app pool (AppConfig.ELECTRUM_SERVERS)
 * so the backend and the app verify against the same server set. Raw-IP
 * entries are gone: they defeat TOFU identity across IP rotations.
 */
const SERVERS: ElectrumServer[] = [
  { host: 'electrumx.raventag.com', port: 50002 }, // RavenTag-operated primary
  { host: 'electrum1.cipig.net', port: 20051 },
  { host: 'electrum2.cipig.net', port: 20051 },
  { host: 'electrum3.cipig.net', port: 20051 },
  { host: 'rvn4lyfe.com', port: 50002 },
]

/**
 * TOFU fingerprint cache: maps hostname to its SHA-256 certificate fingerprint.
 * RT108-SEC-202: pins are persisted in SQLite (`electrum_tofu_pins`, migration 8)
 * and loaded lazily per host, so a process restart no longer reopens the
 * first-use MITM window. The in-memory map is a read cache over the table;
 * enrollment and comparison happen synchronously in the TLS callback, so
 * concurrent connections cannot interleave a second, different enrollment.
 */
const certCache = new Map<string, string>()

/** Lazy DB-backed pin store (better-sqlite3 is synchronous — safe in the TLS callback). */
function pinnedFingerprint(host: string): string | undefined {
  const cached = certCache.get(host)
  if (cached) return cached
  try {
    const row = getDb()
      .prepare('SELECT fingerprint FROM electrum_tofu_pins WHERE host = ?')
      .get(host) as { fingerprint: string } | undefined
    if (row?.fingerprint) {
      certCache.set(host, row.fingerprint)
      return row.fingerprint
    }
  } catch (err) {
    // A pin store failure must fail CLOSED for new enrollments: without the
    // ability to persist a pin we would silently degrade to per-process TOFU.
    throw new Error(`TOFU pin store unavailable for ${host}: ${(err as Error).message}`)
  }
  return undefined
}

function persistFingerprint(host: string, fingerprint: string): void {
  getDb()
    .prepare('INSERT OR REPLACE INTO electrum_tofu_pins (host, fingerprint, pinned_at) VALUES (?, ?, unixepoch())')
    .run(host, fingerprint)
  certCache.set(host, fingerprint)
}

/**
 * Monotonically increasing request ID counter.
 * Each JSON-RPC message requires a unique numeric ID so responses can be
 * correlated to their originating request on the same socket.
 */
let idCounter = 1
const MAX_ELECTRUM_RESPONSE_BYTES = 1_048_576

/**
 * Compute the SHA-256 hex digest of a Buffer.
 * Used to fingerprint TLS certificates for TOFU validation.
 *
 * @param data - Raw bytes (e.g., DER-encoded certificate)
 * @returns Lowercase hex string of length 64
 */
function sha256Hex(data: Buffer): string {
  return createHash('sha256').update(data).digest('hex')
}

/**
 * Connect to an ElectrumX server over TLS, perform the protocol handshake,
 * send a single JSON-RPC request, and return the result.
 *
 * Protocol flow:
 *   1. TLS connect (self-signed cert accepted, hostname checked via TOFU).
 *   2. On secureConnect: verify/pin certificate, send server.version handshake.
 *   3. On receiving handshake response: send the actual RPC request.
 *   4. On receiving the response with the correct ID: resolve or reject.
 *   5. On timeout (12 s), error, or unexpected close: reject.
 *
 * The socket is always destroyed after the promise settles to avoid leaks.
 *
 * @param server - ElectrumX server host and port
 * @param method - JSON-RPC method name (e.g., 'blockchain.scripthash.get_balance')
 * @param params - Positional parameters for the method
 * @returns The `result` field from the JSON-RPC response
 */
async function callServer(server: ElectrumServer, method: string, params: unknown[]): Promise<unknown> {
  return new Promise((resolve, reject) => {
    // Use separate IDs for the handshake and the actual request so responses
    // can be routed correctly even if data arrives interleaved.
    const hsId = idCounter++
    const id = idCounter++
    let buffer = ''
    let settled = false

    /** Settle the promise exactly once, then clean up the socket. */
    const done = (err?: Error, result?: unknown) => {
      if (settled) return
      settled = true
      clearTimeout(timeout)
      socket.destroy()
      if (err) reject(err); else resolve(result)
    }

    // 12-second overall timeout covers both connection and response wait
    const timeout = setTimeout(
      () => done(new Error(`Timeout connecting to ${server.host}`)),
      12_000
    )

    const socket = tls.connect({
      host: server.host,
      port: server.port,
      rejectUnauthorized: false,        // self-signed certs are common on ElectrumX
      checkServerIdentity: () => undefined, // hostname check handled via TOFU below
    })

    socket.once('secureConnect', () => {
      // TOFU verification: pin the cert on first connection, reject on mismatch.
      // Fail closed if the pin store itself cannot be consulted.
      const cert = socket.getPeerCertificate(true)
      if (!cert?.raw) { done(new Error(`No certificate from ${server.host}`)); return }
      const fingerprint = sha256Hex(cert.raw)
      let pinned: string | undefined
      try {
        pinned = pinnedFingerprint(server.host)
      } catch (err) {
        done(err as Error); return
      }
      if (!pinned) {
        // First time connecting to this host: persist the fingerprint
        try {
          persistFingerprint(server.host, fingerprint)
        } catch (err) {
          done(new Error(`Cannot persist TOFU pin for ${server.host}: ${(err as Error).message}`)); return
        }
        console.log(`[ElectrumX] TOFU: pinned ${server.host}`)
      } else if (pinned !== fingerprint) {
        // Fingerprint changed since the pin was stored: possible MITM, abort
        done(new Error(`Certificate mismatch for ${server.host} (possible MITM)`)); return
      }
      // Send handshake: server.version announces the client name and minimum protocol version
      socket.write(JSON.stringify({ id: hsId, method: 'server.version', params: ['RavenTag/1.0', '1.4'] }) + '\n')
    })

    socket.on('data', (chunk: Buffer) => {
      // ElectrumX sends newline-delimited JSON. Accumulate chunks and process
      // complete lines, keeping any partial final line in the buffer.
      buffer += chunk.toString()
      if (Buffer.byteLength(buffer, 'utf8') > MAX_ELECTRUM_RESPONSE_BYTES) {
        done(new Error(`Oversized ElectrumX response from ${server.host}`))
        return
      }
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''
      for (const line of lines) {
        if (!line.trim()) continue
        try {
          const msg = JSON.parse(line)
          if (msg.id === hsId) {
            // Handshake done, send actual request
            socket.write(JSON.stringify({ id, method, params }) + '\n')
          } else if (msg.id === id) {
            // Our real request was answered; resolve or reject based on error field
            if (msg.error) done(new Error(`ElectrumX: ${JSON.stringify(msg.error)}`))
            else done(undefined, msg.result)
          }
        } catch { /* ignore partial lines */ }
      }
    })

    socket.on('error', (err: Error) => done(err))
    // If the server closes the connection before we receive our answer, treat it as an error
    socket.on('close', () => { if (!settled) done(new Error(`Connection closed by ${server.host}`)) })
  })
}

/**
 * Attempt a JSON-RPC call against each server in the SERVERS list in order.
 * If a server fails (timeout, TLS error, protocol error), the next server
 * is tried. Throws only if every server has been exhausted.
 *
 * @param method - JSON-RPC method name
 * @param params - Positional parameters
 * @returns The result from the first successful server
 * @throws Aggregated error message listing each server and its failure reason
 */
async function callWithFailover(method: string, params: unknown[]): Promise<unknown> {
  const errors: string[] = []
  for (const server of SERVERS) {
    try {
      return await callServer(server, method, params)
    } catch (e) {
      errors.push(`${server.host}: ${(e as Error).message}`)
    }
  }
  throw new Error(`All ElectrumX servers failed: ${errors.join('; ')}`)
}

// ── Public service ─────────────────────────────────────────────────────────────

/**
 * RVN balance for a single address as returned by ElectrumX.
 * Both confirmed and unconfirmed amounts are in satoshis internally;
 * totalRvn is the human-readable sum divided by 1e8.
 */
export interface ElectrumBalance {
  confirmed: number    // satoshis
  unconfirmed: number  // satoshis
  totalRvn: number
}

/**
 * An individual asset balance entry aggregated from UTXOs for an address.
 */
export interface ElectrumAssetBalance {
  name: string
  amount: number
}

/**
 * Singleton ElectrumX service. Exposes high-level methods that abstract
 * the underlying JSON-RPC protocol and failover logic.
 */
class ElectrumXService {
  /**
   * Retrieve the confirmed and unconfirmed RVN balance for a Ravencoin address.
   *
   * Converts the address to an ElectrumX script hash, then calls
   * blockchain.scripthash.get_balance. Raw values are in satoshis (1 RVN = 1e8).
   *
   * @param address - Ravencoin P2PKH address
   * @returns Balance breakdown with a totalRvn convenience field
   */
  async getBalance(address: string): Promise<ElectrumBalance> {
    const scripthash = addressToScripthash(address)
    const result = await callWithFailover('blockchain.scripthash.get_balance', [scripthash]) as {
      confirmed: number; unconfirmed: number
    }
    return {
      confirmed: result.confirmed,
      unconfirmed: result.unconfirmed,
      // Convert satoshis to RVN by dividing by 1e8
      totalRvn: (result.confirmed + result.unconfirmed) / 1e8,
    }
  }

  /**
   * List all Ravencoin asset balances held by an address.
   *
   * Fetches all UTXOs via blockchain.scripthash.listunspent and aggregates
   * the asset amounts. UTXOs without an asset annotation (i.e., plain RVN)
   * are ignored. Amounts are divided by 1e8 to match Ravencoin's display
   * convention (assets use the same satoshi-like unit internally).
   *
   * @param address - Ravencoin P2PKH address
   * @returns Array of {name, amount} sorted alphabetically by asset name
   */
  async getAssetBalances(address: string): Promise<ElectrumAssetBalance[]> {
    const scripthash = addressToScripthash(address)
    const utxos = await callWithFailover('blockchain.scripthash.listunspent', [scripthash]) as Array<{
      tx_hash: string; tx_pos: number; height: number; value: number
      asset?: { name: string; amount: number }
    }>

    // Aggregate asset amounts across all UTXOs; skip UTXOs that carry only RVN
    const balances = new Map<string, number>()
    for (const utxo of utxos) {
      if (utxo.asset?.name) {
        balances.set(utxo.asset.name, (balances.get(utxo.asset.name) ?? 0) + utxo.asset.amount)
      }
    }

    // Convert from satoshi-like units to display units and sort alphabetically
    return Array.from(balances.entries())
      .map(([name, amount]) => ({ name, amount: amount / 1e8 }))
      .sort((a, b) => a.name.localeCompare(b.name))
  }

  /**
   * Broadcast a signed raw transaction to the Ravencoin network.
   *
   * Sends the hex-encoded transaction to the best available ElectrumX server
   * via blockchain.transaction.broadcast.
   *
   * @param txHex - Hex-encoded signed transaction
   * @returns The transaction ID (txid) as a hex string
   * @throws If all servers reject the transaction or are unreachable
   */
  async broadcast(txHex: string): Promise<string> {
    return await callWithFailover('blockchain.transaction.broadcast', [txHex]) as string
  }

  /**
   * Verify that a transaction ID exists on the Ravencoin blockchain.
   *
   * Returns true if the transaction is found (confirmed or in mempool).
   * Returns false if not found or if all servers are unreachable.
   * Invalid txid format (not 64 hex chars) returns false immediately without
   * making any network request.
   *
   * @param txid - Transaction ID (64 hex chars)
   * @returns true if the transaction is known to any ElectrumX server
   */
  async txExists(txid: string): Promise<boolean> {
    // Validate format before making a network request to avoid pointless calls
    if (!/^[0-9a-fA-F]{64}$/.test(txid)) return false
    try {
      await callWithFailover('blockchain.transaction.get', [txid])
      return true
    } catch {
      return false
    }
  }

  /**
   * Get asset metadata via blockchain.asset.get_meta.
   *
   * Retrieves on-chain asset information including total supply, decimal units,
   * reissuability flag, and IPFS hash (if any). This method is supported by
   * Ravencoin-specific ElectrumX servers.
   *
   * Note: some servers return the IPFS hash under the key "ipfs" instead of
   * "ipfs_hash". Both variants are handled.
   *
   * @param assetName - Ravencoin asset name (e.g., "BRAND/PRODUCT")
   * @returns Asset metadata object, or null if the asset does not exist or
   *          the server does not support this method
   */
  async getAssetMeta(assetName: string): Promise<{
    name: string
    amount: number
    units: number
    reissuable: boolean
    has_ipfs: boolean
    ipfs_hash?: string
  } | null> {
    try {
      const result = await callWithFailover('blockchain.asset.get_meta', [assetName]) as Record<string, unknown>
      // ElectrumX may return the IPFS hash as "ipfs" instead of "ipfs_hash"
      const ipfsHash = (result['ipfs_hash'] ?? result['ipfs']) as string | undefined
      return {
        name: result['name'] as string,
        amount: result['amount'] as number,
        units: result['units'] as number,
        reissuable: result['reissuable'] as boolean,
        has_ipfs: result['has_ipfs'] as boolean,
        ipfs_hash: ipfsHash
      }
    } catch {
      // Return null rather than throwing so callers can fall back gracefully
      return null
    }
  }
}

/** Singleton instance used throughout the backend. */
export const electrumXService = new ElectrumXService()
