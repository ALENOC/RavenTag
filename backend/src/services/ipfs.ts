/**
 * @file services/ipfs.ts
 *
 * IPFS integration layer for RavenTag.
 *
 * Provides three functions:
 *   - uploadMetadataToIpfs: pin a JSON metadata object to IPFS via a local Kubo node.
 *   - uploadImageToIpfs: pin a binary image file to IPFS via a local Kubo node.
 *   - fetchIpfsMetadata: retrieve and parse a JSON document from an IPFS URI.
 *
 * Configuration (via environment variables):
 *   IPFS_GATEWAY  - Base URL of the public HTTP IPFS gateway used for reads.
 *                   Defaults to "https://ipfs.io/ipfs/".
 *   IPFS_API_URL  - Base URL of the local Kubo (go-ipfs) HTTP API used for writes.
 *                   Defaults to "http://127.0.0.1:5001".
 *
 * Security (MED-4: SSRF protection):
 *   ipfsUriToHttp rejects http:// URIs and restricts https:// URIs to a static
 *   allow-list of trusted IPFS gateway hostnames. Bare CIDs and ipfs:// URIs
 *   are always routed through the configured IPFS_GATEWAY.
 */

import axios from 'axios'
import FormData from 'form-data'

/** Public IPFS gateway used for reading content addressed by CID. */
const GATEWAY = process.env.IPFS_GATEWAY ?? 'https://ipfs.io/ipfs/'

/** Local Kubo node HTTP API URL used for pinning (writing) content. */
const IPFS_API_URL = process.env.IPFS_API_URL ?? 'http://127.0.0.1:5001'

/**
 * Allowed HTTPS IPFS gateway hostnames (MED-4: SSRF protection).
 * Only exact hostname matches or subdomain matches are permitted.
 * This list prevents metadata fetch requests from being redirected to
 * internal services or arbitrary external hosts.
 */
const ALLOWED_GATEWAYS = ['ipfs.io', 'cloudflare-ipfs.com', 'dweb.link', 'gateway.pinata.cloud']

/**
 * Convert an IPFS URI to a plain HTTPS URL suitable for axios.get.
 *
 * Supported input formats:
 *   ipfs://<CID>         Routed through the configured IPFS_GATEWAY.
 *   https://<allowed>/*  Passed through if the hostname is in ALLOWED_GATEWAYS.
 *   <bare CID>           Treated as ipfs://<CID> and routed through IPFS_GATEWAY.
 *
 * Blocked formats:
 *   http://...           Rejected unconditionally (plaintext, SSRF risk).
 *   https://<other>/*    Rejected if the hostname is not in ALLOWED_GATEWAYS.
 *
 * @param uri - The IPFS URI from Ravencoin asset metadata
 * @returns An HTTPS URL ready for a GET request
 * @throws If the URI uses http:// or an untrusted https:// hostname
 */
function ipfsUriToHttp(uri: string): string {
  if (uri.startsWith('ipfs://')) {
    // Strip the ipfs:// scheme and prepend the configured gateway base URL
    return GATEWAY + uri.slice(7)
  }
  if (uri.startsWith('http://')) {
    throw new Error('Insecure http:// IPFS URI blocked')
  }
  if (uri.startsWith('https://')) {
    // Allow the URI through only if its hostname is in the explicit allow-list.
    // Both exact matches (e.g., "ipfs.io") and subdomain matches (e.g., "sub.ipfs.io") are accepted.
    const host = new URL(uri).hostname
    if (!ALLOWED_GATEWAYS.some(gw => host === gw || host.endsWith('.' + gw))) {
      throw new Error('Untrusted IPFS gateway hostname')
    }
    return uri
  }
  // Assume bare CID (e.g., "QmXyz...") and route through the configured gateway
  return GATEWAY + uri
}

/**
 * Upload a JSON metadata object to IPFS via a local Kubo node.
 *
 * The metadata is serialised to formatted JSON, wrapped in a multipart form
 * upload, and pinned to the local node using the Kubo HTTP API v0.
 * CIDv0 (Qm... format, SHA-256 + SHA2-256-dag-pb) is requested explicitly
 * for compatibility with the Ravencoin `issue` RPC, which stores the IPFS
 * hash as a 34-byte field in the asset script.
 *
 * @param metadata - Any JSON-serialisable object (typically a RTP-1 metadata record)
 * @returns The CIDv0 hash string (starts with "Qm", 46 chars)
 * @throws If the Kubo node is unreachable, returns an error, or the CID is invalid
 */
export async function uploadMetadataToIpfs(metadata: unknown): Promise<string> {
  // Serialise with 2-space indentation for human readability in IPFS explorers
  const json = JSON.stringify(metadata, null, 2)
  const form = new FormData()
  form.append('file', Buffer.from(json, 'utf-8'), {
    filename: 'metadata.json',
    contentType: 'application/json'
  })

  const response = await axios.post(
    // cid-version=0 forces CIDv0 (Qm...), pin=true keeps it in the local store
    `${IPFS_API_URL}/api/v0/add?cid-version=0&pin=true`,
    form,
    {
      headers: form.getHeaders(),
      timeout: 30000,            // 30-second upload timeout
      maxContentLength: 1024 * 1024  // reject metadata larger than 1 MB
    }
  )

  const hash: string = response.data?.Hash
  // Sanity check: a valid CIDv0 always starts with "Qm"
  if (!hash || !hash.startsWith('Qm')) {
    throw new Error('IPFS node returned invalid CID')
  }
  return hash
}

/**
 * Upload a binary image file (JPG, PNG, WEBP, etc.) to IPFS.
 *
 * Wraps the image buffer in a multipart form and pins it via the local Kubo
 * node. The returned CIDv0 hash can be used as the "image" field in
 * RTP-1 metadata JSON or as the ipfs_hash in a Ravencoin unique asset.
 *
 * @param buffer   - Raw image bytes
 * @param filename - Suggested filename for IPFS (e.g., "product.jpg")
 * @param mimeType - MIME type string (e.g., "image/jpeg")
 * @returns The CIDv0 hash string (starts with "Qm", 46 chars)
 * @throws If the Kubo node is unreachable, the image exceeds 10 MB, or the CID is invalid
 */
export async function uploadImageToIpfs(
  buffer: Buffer,
  filename: string,
  mimeType: string
): Promise<string> {
  const form = new FormData()
  form.append('file', buffer, { filename, contentType: mimeType })

  const response = await axios.post(
    `${IPFS_API_URL}/api/v0/add?cid-version=0&pin=true`,
    form,
    {
      headers: form.getHeaders(),
      timeout: 60000,                      // 60-second upload timeout for larger images
      maxContentLength: 10 * 1024 * 1024   // 10 MB upper limit
    }
  )

  const hash: string = response.data?.Hash
  if (!hash || !hash.startsWith('Qm')) {
    throw new Error('IPFS node returned invalid CID for image')
  }
  return hash
}

/**
 * Fetch and parse a JSON document from an IPFS URI.
 *
 * Used by the verification pipeline to retrieve RTP-1 metadata pinned at
 * the ipfs_hash stored in a Ravencoin asset. The IPFS URI is normalised to
 * an HTTPS gateway URL by ipfsUriToHttp before the request is made.
 *
 * @param ipfsUri - IPFS content URI (ipfs://CID, https://gateway/CID, or bare CID)
 * @returns The parsed JSON object returned by the gateway
 * @throws If the URI fails the SSRF check, the gateway is unreachable, the
 *         response exceeds 1 MB, or the body is not valid JSON
 */
export async function fetchIpfsMetadata(ipfsUri: string): Promise<unknown> {
  // Normalise and validate the URI (SSRF protection applied inside ipfsUriToHttp)
  const url = ipfsUriToHttp(ipfsUri)
  const response = await axios.get(url, {
    timeout: 15000,                      // 15-second read timeout
    headers: { Accept: 'application/json' },
    maxContentLength: 1024 * 1024        // reject responses larger than 1 MB
  })
  return response.data
}
