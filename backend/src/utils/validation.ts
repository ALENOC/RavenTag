/**
 * @file utils/validation.ts
 *
 * Zod schemas for all RavenTag backend API request bodies.
 *
 * Every Express route that reads from req.body uses one of these schemas via
 * safeParse, which returns a typed result without throwing. Invalid requests
 * receive a 400 response with the Zod issue details.
 *
 * Schema groups:
 *   Asset naming     - assetNameSchema, assetNameWithUniqueSchema
 *   SUN verification - sunVerifyRequestSchema, fullVerifyRequestSchema,
 *                      serverVerifySchema
 *   Admin / tag reg  - adminRegisterTagSchema
 *   Brand operations - brandRevokeSchema, brandRegisterChipSchema,
 *                      brandDeriveChipKeySchema
 *
 * TypeScript types are inferred from each schema and exported alongside it
 * so route handlers can be fully typed without a separate types file.
 */

import { z } from 'zod'

/**
 * Helper factory: Zod string that must be a valid hexadecimal string.
 *
 * @param len - If provided, the string must be EXACTLY this many hex characters.
 *              If omitted, any non-empty hex string is accepted.
 * @returns A Zod string schema with a hex-only regex constraint
 */
const hexString = (len?: number) =>
  z.string().regex(
    len ? new RegExp(`^[0-9a-fA-F]{${len}}$`) : /^[0-9a-fA-F]+$/,
    len ? `Must be ${len}-char hex string` : 'Must be a hex string'
  )

/**
 * Validates a Ravencoin asset name (root or sub-asset, no unique-asset separator).
 *
 * Rules:
 *   - 3-100 characters total
 *   - Characters: uppercase A-Z, digits 0-9, underscore, dot
 *   - Segments separated by "/" (sub-asset) or "." (dot-separated)
 *   - Each segment must match [A-Z0-9_.]+
 *
 * Examples of valid names: "BRAND", "BRAND/PRODUCT", "BRAND/PRODUCT.V2"
 * Examples of invalid names: "brand" (lowercase), "BRAND#001" (unique separator)
 */
export const assetNameSchema = z
  .string()
  .min(3)
  .max(100)
  .regex(/^[A-Z0-9_]+([./][A-Z0-9_.]+)*$/, 'Invalid asset name format')

/**
 * Validates a Ravencoin asset name including the unique-asset "#" separator.
 *
 * Ravencoin supports three asset tiers:
 *   ROOT   - e.g., "BRAND"                  (costs 500 RVN to issue)
 *   SUB    - e.g., "BRAND/PRODUCT"           (costs 100 RVN to issue)
 *   UNIQUE - e.g., "BRAND/PRODUCT#SERIAL001" (costs 5 RVN per token)
 *
 * Unique assets use the "#" separator and allow a serial/tag suffix with
 * A-Z, 0-9, underscore, and dot characters.
 */
export const assetNameWithUniqueSchema = z
  .string()
  .min(3)
  .max(100)
  .regex(/^[A-Z0-9_]+([./][A-Z0-9_.]+)*(#[A-Z0-9._]+)?$/, 'Invalid asset name format')

/**
 * Core SUN verification fields shared by all verify endpoints.
 *
 * Fields:
 *   e          - 32 hex chars: AES-128-CBC encrypted PICCData (exactly 16 bytes, one block)
 *   m          - 16 hex chars: truncated SDMMAC (NXP odd-byte truncation, 8 bytes)
 *   sdmmac_key - 32 hex chars: K_SDMMetaRead (Key 2), used to decrypt "e"
 *   sun_mac_key - 32 hex chars: K_SDMFileRead (Key 3), base key for session MAC derivation
 *   salt       - 32 hex chars (optional): 16-byte random salt for nfc_pub_id derivation
 */
export const sunVerifyRequestSchema = z.object({
  e: hexString(32),            // Encrypted SUN data, exactly 16 bytes (32 hex chars), AES-128-CBC one block
  m: hexString(16),            // SUN MAC truncated to 8 bytes (16 hex chars)
  sdmmac_key: hexString(32),   // AES-128 SDMMAC key (16 bytes = 32 hex chars)
  sun_mac_key: hexString(32),  // AES-128 SUN MAC key
  salt: hexString(32).optional() // 16-byte salt for nfc_pub_id
})

/**
 * Request body schema for POST /api/verify/sun.
 *
 * This endpoint is operator-key protected. Salt is REQUIRED here (HIGH-1):
 * without a salt, nfc_pub_id cannot be derived and counter-replay detection
 * (the only defense against replay attacks) is disabled. Operators must always
 * supply a salt when calling this endpoint.
 */
export const sunVerifyWithSaltSchema = sunVerifyRequestSchema.extend({
  salt: hexString(32) // Required (not optional) to enforce counter-replay detection
})

/**
 * Request body schema for POST /api/verify/full.
 *
 * Extends sunVerifyRequestSchema with an optional asset name. When provided,
 * the server fetches the Ravencoin asset and its IPFS metadata and checks
 * that the derived nfc_pub_id matches the stored value.
 *
 * Additional field:
 *   expected_asset - Ravencoin asset name (optional): triggers blockchain lookup
 */
export const fullVerifyRequestSchema = sunVerifyRequestSchema.extend({
  expected_asset: assetNameSchema.optional()
})

/**
 * Request body schema for POST /api/admin/register-tag.
 *
 * Registers a new NFC tag binding in the admin database. The nfc_pub_id
 * must match what was stored in the asset's IPFS metadata at issuance time.
 *
 * Fields:
 *   asset_name     - Ravencoin asset name this chip is linked to
 *   nfc_pub_id     - 64 hex chars: SHA-256(uid || salt), 32 bytes
 *   brand_info     - Optional brand contact/description block
 *   metadata_ipfs  - Optional IPFS URI pointing to the full RTP-1 metadata JSON
 */
export const adminRegisterTagSchema = z.object({
  asset_name: assetNameSchema,
  nfc_pub_id: hexString(64),   // SHA-256 = 32 bytes = 64 hex chars
  brand_info: z.object({
    website: z.string().url().optional(),
    description: z.string().max(500).optional(),
    contact: z.string().max(200).optional()
  }).optional(),
  // Must be a well-formed ipfs:// CIDv0 URI (Qm... = 46 chars)
  metadata_ipfs: z.string().regex(/^ipfs:\/\/Qm[1-9A-Za-z]{44}$/).optional()
})

/**
 * Request body schema for POST /api/brand/revoke.
 *
 * Marks an asset as revoked in the backend SQLite database.
 * Revocation is backend-only (soft revocation): the asset record is updated
 * in the local database and all future scans return REVOKED status.
 *
 * Fields:
 *   asset_name - Ravencoin asset name to revoke
 *   reason     - Human-readable revocation reason (max 500 chars, optional)
 */
export const brandRevokeSchema = z.object({
  asset_name: assetNameWithUniqueSchema,
  reason: z.string().max(500).optional()
})

export type SunVerifyRequest = z.infer<typeof sunVerifyRequestSchema>
export type SunVerifyWithSaltRequest = z.infer<typeof sunVerifyWithSaltSchema>
export type FullVerifyRequest = z.infer<typeof fullVerifyRequestSchema>
export type AdminRegisterTagRequest = z.infer<typeof adminRegisterTagSchema>
export type BrandRevokeRequest = z.infer<typeof brandRevokeSchema>


/**
 * Request body schema for POST /api/brand/register-chip.
 *
 * Registers a physical NTAG 424 DNA chip UID in the backend database and
 * associates it with a Ravencoin asset name. The server derives nfc_pub_id
 * using BRAND_SALT from the environment, so the salt never travels over the
 * network.
 *
 * Fields:
 *   asset_name - Ravencoin asset name (supports unique-asset "#" separator)
 *   tag_uid    - 14 hex chars: 7-byte NTAG 424 DNA hardware UID
 */
export const brandRegisterChipSchema = z.object({
  asset_name: assetNameWithUniqueSchema,
  /** 7-byte NTAG 424 DNA UID (14 hex chars) */
  tag_uid: hexString(14)
})

/**
 * Request body schema for POST /api/verify/tag.
 *
 * Used by the brand-sovereign server-side verification endpoint. The client
 * sends only the asset name and the raw SUN URL parameters; the server holds
 * the AES keys and performs the full verification pipeline internally.
 *
 * Fields:
 *   asset - Ravencoin asset name (supports unique-asset "#" separator)
 *   e     - 32 hex chars: AES-128-CBC encrypted PICCData (16 bytes)
 *   m     - 16 hex chars: truncated SDMMAC (8 bytes)
 */
export const serverVerifySchema = z.object({
  asset: assetNameWithUniqueSchema,
  e: hexString(32),  // 16-byte AES-128-CBC block = 32 hex chars
  m: hexString(16)   // MACt = 8 bytes = 16 hex chars
})

/**
 * Request body schema for POST /api/brand/derive-chip-keys.
 *
 * Returns the four per-chip AES-128 keys derived from BRAND_MASTER_KEY + UID.
 * Requires operator key. The keys are transmitted over HTTPS and are never
 * persisted server-side; they are computed on demand each time this endpoint
 * is called.
 *
 * The response includes: app_master_key, sdmmac_input_key, sdm_enc_key,
 * sdm_mac_key, and nfc_pub_id (computed using BRAND_SALT).
 *
 * Fields:
 *   tag_uid - 14 hex chars: 7-byte NTAG 424 DNA hardware UID
 */
export const brandDeriveChipKeySchema = z.object({
  /** 7-byte NTAG 424 DNA UID (14 hex chars) */
  tag_uid: hexString(14)
})

export type BrandRegisterChipRequest = z.infer<typeof brandRegisterChipSchema>
export type BrandDeriveChipKeyRequest = z.infer<typeof brandDeriveChipKeySchema>
