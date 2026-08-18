# RavenTag Verify - Privacy Policy

**Version 1.1 - Effective Date: August 18, 2026**
**Copyright 2026-present Alessandro Nocentini. All rights reserved.**

---

> **OFFICIAL VERSION.** This document in the Italian language constitutes the legally binding version of the Privacy Policy. In the event of any discrepancy, contradiction, or ambiguity between this version and any translation, the Italian version shall prevail.

---

## 1. Introduction

This Privacy Policy describes how RavenTag Verify ("App"), developed by Alessandro Nocentini ("Developer", "we", "us"), collects, uses, and protects information when you use the App.

The Developer is committed to data minimisation. The App is designed as non-custodial software under a source-available license and operates with the minimum amount of network and technical data strictly necessary for its functionality.

This Privacy Policy complies with:
- EU General Data Protection Regulation (GDPR - Regulation EU 2016/679)
- Italian Personal Data Protection Code (Legislative Decree 196/2003 as amended by Legislative Decree 101/2018)
- Google Play Developer Policy

---

## 2. Data Controller and Infrastructure Categories

RavenTag is a protocol and software with publicly available source code (source-available software). The App may interact with infrastructure operated directly by the Developer, as well as independent third-party or brand-operated infrastructure.

### 2.1 Developer-operated demo backend
The Developer operates a backend instance at `raventag.com` (e.g. `api.raventag.com`) for demonstration, infrastructure testing, and asset verification purposes. If you use an instance of the App connected to this demo backend, the data controller for server-side verification data and network logs (Section 3.2) is:

**Alessandro Nocentini**
Contact: https://github.com/ALENOC/RavenTag
Email: legal@raventag.com

### 2.2 Developer-operated ElectrumX infrastructure
The Developer operates a public ElectrumX endpoint (e.g. `electrumx.raventag.com` / `electrum.raventag.com`) deployed in front of a dedicated Ravencoin Core node. When the App connects to this specific endpoint for blockchain queries or transaction broadcasting, the processing of connection metadata is managed by the Developer under this Policy. This Developer-operated infrastructure does not constitute a third-party service.

### 2.3 Brand-operated backend (production use)
In production, brands and manufacturers deploy their own backend infrastructure. When you use an instance of the App configured to connect to a specific brand's backend, that brand is the independent data controller for data processed by its servers. The Developer has no access to and assumes no responsibility for data processed by third-party brand backends.

### 2.4 Independent third-party blockchain infrastructure
The App may also connect to public ElectrumX nodes or independent Ravencoin Core nodes operated by third parties. These nodes are entirely outside the Developer's control and operate under their respective management policies.

---

## 3. Data Processed and Technical Architecture

### 3.1 Data Stored Locally on Your Device (Never Transmitted to Developer or ElectrumX)

The following sensitive data is generated and stored exclusively on your device in encrypted form and is never transmitted to any server or infrastructure operated by the Developer or to ElectrumX servers:

| Data | Purpose | Storage |
|---|---|---|
| BIP39 Mnemonic Phrase (seed phrase) | Wallet generation and recovery | Android Keystore (AES-256-GCM) |
| Private Keys (derived, encrypted) | Local transaction signing | Android Keystore (AES-256-GCM) |
| Wallet Address (RVN) | Local display and calculation | Encrypted local storage |
| Admin/Operator Keys (Brand version) | Local asset management | Android Keystore (AES-256-GCM) |
| App Settings & Preferences | Local App configuration | Encrypted/protected local preferences |

**Your mnemonic phrase and private keys never leave your device.**

### 3.2 Data Transmitted During NFC Tag Verification (API Backend)

When you scan an NFC tag to verify product authenticity, the App sends the following parameters to the API backend for cryptographic verification:

| Data | Purpose |
|---|---|
| Asset Name (e.g., BRAND/PRODUCT#001) | Asset identification on the Ravencoin blockchain |
| Encrypted NFC Counter (parameter e) | SUN MAC cryptographic verification |
| NFC MAC Value (parameter m) | SUN MAC cryptographic verification |
| Device IP Address | Server-side rate limiting and network security |

**Backend Log Retention (Developer-operated backend)**: IP addresses and API backend network logs are retained for a maximum period of 30 days (verified at code level in the backend log cleanup middleware), after which they are automatically deleted.

**Legal Basis (GDPR)**: Legitimate Interest (Art. 6(1)(f) GDPR) to ensure infrastructure security, prevent abuse, and mitigate cyber attacks.

### 3.3 Data Processed During Blockchain and ElectrumX Operations

When the App executes balance queries, transaction history lookups, or broadcasts transactions, it communicates with ElectrumX infrastructure (both Developer-operated and third-party).

**A. What an ElectrumX server may observe or receive:**
A public or network ElectrumX server may observe connection data and metadata such as:
- Source IP address of the device;
- TLS connection metadata, timestamps, and request frequency;
- JSON-RPC protocol queries and script-hash lookups;
- Balance requests, transaction history, and UTXOs associated with specific addresses;
- Transaction identifiers (TxID) and asset metadata;
- Raw signed transactions submitted for broadcast.

Depending on wallet query patterns, such information may technically permit correlations between network identifiers (such as the IP address) and public blockchain activity.

> **Explicit Security Disclosure:**
> Private keys and seed phrases are never required by the ElectrumX server and are not transmitted as part of normal wallet operation.

**B. Transaction creation and signing workflow:**
For every transaction executed by the wallet:
1. The user initiates the transaction from the App interface;
2. The App constructs the raw transaction locally on the device;
3. The transaction is cryptographically signed on the device using private keys controlled exclusively by the user;
4. The App sends the already signed transaction to the ElectrumX server;
5. ElectrumX relays/broadcasts the signed transaction to Ravencoin Core nodes for inclusion in blockchain blocks.

ElectrumX infrastructure does not possess the user's private key, cannot independently create a valid signature, does not decide the recipient or amount, does not take possession of user RVN, and maintains no custodial account or balance.

**C. Role of public Ravencoin Core nodes:**
A public Ravencoin Core node performs exclusively infrastructure functions, including blockchain synchronization, block and transaction validation, peer-to-peer communication, and transaction propagation. The Core node does not hold customer funds, maintain user accounts, possess private keys, sign on behalf of users, or exercise custody over RVN tokens.

### 3.4 Asset Image Loading (IPFS Gateways)
To display asset images hosted on IPFS, the App may connect to public IPFS gateways (e.g. ipfs.io, cloudflare-ipfs.com). These third-party providers may log the device IP address in accordance with their own privacy policies.

### 3.5 Camera and NFC Data
- **Camera**: Used exclusively on-device for real-time QR code reading; no image data is saved or transmitted.
- **NFC**: Tag reading occurs locally; only derived verification parameters (asset, e, m) are transmitted to the backend as described in Section 3.2.

### 3.6 Data We Do Not Collect
The Developer does not collect:
- Names, email addresses, or direct personal identifiers;
- Unique hardware identifiers (IMEI, Android ID, Advertising ID);
- Precise geolocation data;
- Usage analytics or commercial tracking telemetry.

---

## 4. Third-Party Services and Nodes

The App interacts with network services and nodes that may be operated by independent third parties:

| Service / Node | Purpose | Privacy Notes |
|---|---|---|
| Independent Third-Party ElectrumX Nodes | Blockchain queries and fallback | Developer does not control third-party node logs. Third-party operators may observe IP address, balance queries, and raw transactions submitted for broadcast. |
| Independent Ravencoin Core Network Nodes | P2P validation and propagation | Distributed decentralized network. |
| Public IPFS Gateways | Asset media and metadata loading | Operated by third-party providers. |
| Google Play Store | App distribution | Privacy policies of Google LLC. |

---

## 5. Data Security and Non-Custodial Architecture

All sensitive data stored on the device (mnemonic phrase, private keys) is protected via AES-256-GCM encryption backed by the Android Keystore system with hardware isolation where available.

Network communication between the App and Developer-operated infrastructure occurs over HTTPS/TLS or TLS encrypted channels with certificate pinning/verification.

---

## 6. Data Retention (Storage Limitation)

- **Device Data**: Retained until wallet deletion or App uninstallation.
- **Developer-operated Backend Logs**: Retained for a maximum of 30 days (in accordance with automated log cleanup routines in backend code) and subsequently permanently deleted.
- **Developer-operated ElectrumX and Network Logs**: Retained for the minimum period strictly necessary for network diagnostics and security per operator deployment settings.
- **Public Ravencoin Blockchain Data**: Transactions confirmed on the Ravencoin blockchain are permanently public and cannot be modified, erased, or removed by the Developer or third parties.

---

## 7. IP Address Processing and GDPR Principles

IP addresses and network metadata processed by Developer-operated infrastructure are handled in accordance with GDPR principles of:
- **Data Minimisation**: Only indispensable technical metadata is recorded;
- **Purpose Limitation**: Used exclusively for security, rate limiting, and DoS mitigation;
- **Storage Limitation**: Automated deletion within 30 days for backend request logs;
- **Integrity and Confidentiality**: Protection of infrastructure through appropriate technical measures.

No blanket or absolute claims of "full GDPR compliance" are made; technical and legal safeguards under applicable law are strictly enforced.

---

## 8. Your Rights Under GDPR

Where applicable under the GDPR, you have the right to exercise against the Developer (limited to data processed by Developer-operated servers, such as network logs):
- Right of Access (Art. 15 GDPR);
- Right to Rectification (Art. 16 GDPR);
- Right to Erasure / Right to be Forgotten (Art. 17 GDPR), subject to immutable data already recorded on the public blockchain;
- Right to Restriction of Processing (Art. 18 GDPR);
- Right to Object (Art. 21 GDPR) to processing based on legitimate interest.

To exercise these rights, contact the Developer at: legal@raventag.com

You also have the right to lodge a complaint with the Garante per la protezione dei dati personali (https://www.garanteprivacy.it).

---

## 9. Minors' Privacy

The App is not intended for individuals under 18 years of age. The Developer does not knowingly collect data from minors.

---

## 10. International Data Transfers

Developer-operated infrastructure is hosted within secure data centers located in the European Union or the United States, providing adequate data protection levels under GDPR. When using an instance connected to third-party brand backends, server location is determined independently by each brand.

---

## 11. Regulatory Framework and MiCA Terminology

RavenTag is designed and distributed as non-custodial software under the source-available RavenTag Source License (RTSL-1.0). The Developer does not hold users' private keys, exercise control or custody over users' crypto-assets (RVN or tokens), or provide crypto-asset custody or administration services on behalf of third parties under Regulation (EU) 2023/1114 (MiCA). The activity of Developer-operated ElectrumX infrastructure consists of technical routing of network data and signed transactions over an open protocol.

---

## 12. Changes to This Privacy Policy

The Developer reserves the right to update this Privacy Policy. Changes become effective upon publication of the updated version.

---

## 13. Contact Information

For privacy inquiries or requests:

**Alessandro Nocentini**
GitHub: https://github.com/ALENOC/RavenTag
Email: legal@raventag.com
Garante Privacy: https://www.garanteprivacy.it
