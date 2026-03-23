# RavenTag Verify - Privacy Policy

**Version 1.0 - Effective date: 2026-03-21**
**Copyright 2026 Alessandro Nocentini. All rights reserved.**

---

> **TRANSLATION NOTICE.** This document is a courtesy translation. The Italian version ([PRIVACY_POLICY_IT.md](PRIVACY_POLICY_IT.md)) is the sole legally binding version. In the event of any discrepancy, contradiction, or ambiguity between this translation and the Italian text, the Italian text shall prevail.

---

## 1. Introduction

This Privacy Policy describes how RavenTag Verify ("App"), developed by Alessandro Nocentini ("Developer", "we", "us"), collects, uses, and protects information when you use the App.

The Developer is committed to minimizing data collection. The App is designed to operate with the minimum amount of data necessary to function.

This Privacy Policy complies with:
- EU General Data Protection Regulation (GDPR - Regulation 2016/679)
- Italian Personal Data Protection Code (D.Lgs. 196/2003 as amended by D.Lgs. 101/2018)
- Google Play Developer Policy

---

## 2. Data Controller

RavenTag is an open-source protocol. Each deployment of the App connects to a backend server chosen by the entity that compiled or configured the App (a brand, manufacturer, or the Developer for demonstration purposes).

**Developer-operated demo backend:**
The Developer operates a backend instance at raventag.com exclusively for demonstration and infrastructure testing purposes. If you are using an App instance connected to this demo backend, the data controller for server-side verification data (Section 3.2) is:

**Alessandro Nocentini**
Contact: https://github.com/ALENOC/RavenTag
legal@raventag.com

This demo backend is not intended for end-user production use. Brands should deploy their own backend infrastructure for production deployments.

**Brand-operated backend (production use):**
In production, brands and manufacturers deploy their own backend infrastructure and compile a version of the App configured to connect to their own server. When you use an App instance connected to a brand's backend, that brand is the independent data controller for any verification data received by their server. The Developer has no access to, and bears no responsibility for, data processed by third-party brand backends. You should refer to the privacy policy published by the brand operating that App instance for information on how they handle your data.

---

## 3. Data Collected and How It Is Used

### 3.1 Data Stored Locally on Your Device (Never Transmitted)

The following data is stored exclusively on your device and is never transmitted to any server operated by the Developer:

| Data | Purpose | Storage |
|---|---|---|
| BIP39 mnemonic phrase (encrypted) | Wallet recovery | Android Keystore (AES-256-GCM) |
| Private keys (derived, encrypted) | Transaction signing | Android Keystore (AES-256-GCM) |
| Wallet address (RVN) | Display and transactions | Encrypted local storage |
| Admin/operator keys (Brand version) | Asset management | Android Keystore (AES-256-GCM) |
| App settings and preferences | App configuration | Local shared preferences |

**Your mnemonic phrase and private keys never leave your device.**

### 3.2 Data Transmitted During NFC Tag Verification

When you scan an NFC tag, the App sends the following data to a backend server in order to perform cryptographic verification:

| Data | Purpose |
|---|---|
| Asset name (e.g. BRAND/PRODUCT#001) | Identify the asset on the blockchain |
| Encrypted NFC counter (e parameter) | SUN MAC verification |
| NFC MAC value (m parameter) | SUN MAC verification |
| Your device IP address | Server-side rate limiting and security logging |

This data is the minimum necessary to verify the authenticity of an NFC tag. The verification request does not include any personally identifiable information beyond the IP address.

**Which server receives this data depends on the App configuration:**

- **Default / RavenTag-operated backend**: if the App connects to the RavenTag backend operated by the Developer (raventag.com), the Developer receives and processes this data as described in this Privacy Policy.
- **Brand-operated backend**: if the App has been compiled or configured by a brand to connect to their own server, that brand's server receives this data. The Developer does not receive, access, or process this data in any way. The brand is the independent data controller and their own privacy policy applies.

You can identify which backend the App is connecting to by checking the server URL displayed in the App settings.

**Retention (Developer-operated backend)**: IP addresses and request logs are retained for a maximum of 90 days for security and rate-limiting purposes, after which they are automatically deleted.

**Legal basis (GDPR, Developer-operated backend)**: Legitimate interest (Art. 6(1)(f) GDPR) - security monitoring and fraud prevention.

### 3.3 Data Transmitted During Blockchain Operations

When you perform wallet operations (check balance, send RVN, issue assets), the App communicates with Ravencoin network nodes. This communication may include:

| Data | Purpose |
|---|---|
| Your Ravencoin wallet address | Querying balance and transaction history |
| Transaction data | Broadcasting transactions to the network |
| Your device IP address | Network communication |

The Ravencoin blockchain is a public, decentralized network. All transactions broadcast to the network are permanently and publicly visible to anyone. Do not use this wallet for transactions you wish to keep private.

### 3.4 Data Transmitted During Asset Image Loading

When loading IPFS-hosted asset images, the App connects to public IPFS gateways (such as ipfs.io, cloudflare-ipfs.com). These third-party services may log your IP address in accordance with their own privacy policies.

### 3.5 Camera Data

If you use the camera to scan QR codes within the App, camera data is processed exclusively on your device in real time and is never stored or transmitted.

### 3.6 NFC Data

NFC tag data is read locally on your device. Raw NFC data (UID, NDEF records) is processed on-device and only the derived verification parameters (asset, e, m) are transmitted as described in Section 3.2.

### 3.7 Data We Do Not Collect

We explicitly do not collect:

- Your name, email address, or any personal identification information.
- Device identifiers (IMEI, Android ID, advertising ID).
- Location data.
- Usage analytics or telemetry.
- Crash reports (unless explicitly submitted by you).
- Any data for advertising purposes.

---

## 4. Third-Party Services

The App interacts with the following third-party services. Their privacy policies govern their data practices:

| Service | Purpose | Privacy Policy |
|---|---|---|
| Ravencoin Network Nodes | Blockchain queries and transactions | Decentralized network, no single policy |
| IPFS Gateways (ipfs.io, cloudflare-ipfs.com) | Asset image loading | See respective providers |
| Pinata (pinata.cloud) | IPFS metadata pinning (optional, Brand version only) | https://pinata.cloud/privacy |
| Google Play Store | App distribution | https://policies.google.com/privacy |

The Developer is not responsible for the data practices of these third-party services.

---

## 5. Data Security

All sensitive data stored on your device (mnemonic phrase, private keys, API keys) is encrypted using AES-256-GCM via the Android Keystore system, which uses hardware-backed security where available.

Communication between the App and the Developer's backend server is encrypted using HTTPS/TLS.

Despite these measures, no method of electronic storage or transmission is 100% secure. You are responsible for maintaining the security of your device and your mnemonic phrase.

---

## 6. Data Retention

- **On-device data**: stored until you delete the wallet or uninstall the App.
- **Server-side request logs**: retained for a maximum of 90 days, then automatically deleted.
- **Blockchain data**: all transactions broadcast to the Ravencoin blockchain are permanently public and cannot be deleted by the Developer or any third party.

---

## 7. Your Rights Under GDPR

If you are located in the European Economic Area, you have the following rights regarding your personal data:

- **Right of access**: request a copy of the personal data we hold about you (limited to server-side logs).
- **Right of rectification**: request correction of inaccurate data.
- **Right of erasure**: request deletion of your personal data from our servers (server logs), subject to legal retention obligations.
- **Right to restriction of processing**: request that we limit how we use your data.
- **Right to object**: object to processing based on legitimate interest.
- **Right to data portability**: receive your data in a structured, machine-readable format.
- **Right to lodge a complaint**: you have the right to lodge a complaint with the Italian Data Protection Authority (Garante per la protezione dei dati personali, https://www.garanteprivacy.it).

To exercise any of these rights, contact us at: https://github.com/ALENOC/RavenTag / legal@raventag.com

We will respond to your request within 30 days.

---

## 8. Children's Privacy

The App is not directed at children under the age of 18. We do not knowingly collect personal data from children under 18. If you believe that a child under 18 has used the App and provided personal data, please contact us and we will take steps to delete such data.

---

## 9. International Data Transfers

**Developer-operated demo backend**: The Developer's demo backend server is located within the European Union. If you access a demo App instance from outside the EU, your verification request data (Section 3.2) will be transferred to and processed within the EU, in accordance with GDPR requirements.

**Brand-operated backend**: In production deployments, the geographic location of the backend server is determined solely by the brand or manufacturer that deployed it. The Developer has no control over, and no knowledge of, the server locations chosen by third-party brands. The applicable international data transfer rules are those of the brand operating that deployment. Please refer to the brand's own privacy policy for details.

---

## 10. Changes to This Privacy Policy

We may update this Privacy Policy from time to time. We will notify you of material changes by updating the effective date at the top of this document and, where required by law, by providing notice within the App.

Your continued use of the App after any changes constitutes your acceptance of the updated Privacy Policy.

---

## 11. Contact

For any privacy-related questions, requests, or complaints:

**Alessandro Nocentini**
https://github.com/ALENOC/RavenTag
legal@raventag.com

For complaints regarding data protection, you may also contact:
**Garante per la protezione dei dati personali**
https://www.garanteprivacy.it

---

*RavenTag Verify is an open-source project. Source code: https://github.com/ALENOC/RavenTag*
