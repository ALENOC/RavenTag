# RavenTag Verify - Privacy Policy

**Version 1.2 - Effective Date: August 18, 2026**  
**Copyright 2026-present Alessandro Nocentini. All rights reserved.**

---

> **OFFICIAL VERSION.** The Italian-language document is the legally binding version of this Privacy Policy. If this translation conflicts with or is ambiguous compared with the Italian version, the Italian version prevails, subject to any mandatory rights that may apply.

---

## 1. Introduction

This Privacy Policy describes how RavenTag Verify (the "App"), developed by Alessandro Nocentini (the "Developer"), processes information when you use the App.

The App is designed as non-custodial software whose source code is publicly available under the RavenTag Source License (RTSL-1.0). Its architecture is intended to minimize the network and technical data processed.

This Policy is drafted with reference to Regulation (EU) 2016/679 (GDPR), the Italian Personal Data Protection Code and other applicable rules. It is not a blanket certification of regulatory compliance.

---

## 2. Controller and Infrastructure Categories

RavenTag may interact with infrastructure directly operated by the Developer and with independent infrastructure operated by brands or third parties.

### 2.1 Developer-operated demo backend
The Developer may operate a backend at `raventag.com` (for example `api.raventag.com`) for demonstration, infrastructure testing and asset verification. For data processed by that backend, the controller is:

**Alessandro Nocentini**  
GitHub: https://github.com/ALENOC/RavenTag  
Email: legal@raventag.com

### 2.2 Developer-operated ElectrumX infrastructure
The Developer operates a public ElectrumX endpoint, currently identified in the project as `electrumx.raventag.com`, connected to a Ravencoin Core node. When the App uses that endpoint, processing of connection data by this infrastructure is attributable to the Developer rather than to an independent third-party operator.

### 2.3 Brand-operated backends
Brands and manufacturers may deploy independent backend instances. When an App instance is configured to use a backend operated by a brand, that entity independently determines the purposes and means of processing carried out by its systems, subject to any different legal arrangement that may apply.

### 2.4 Independent third-party blockchain infrastructure
The App may connect to ElectrumX nodes, Ravencoin Core nodes, IPFS gateways or other services operated by independent parties. The Developer does not control their logging, retention, security or privacy practices.

---

## 3. Data Processed and Technical Architecture

### 3.1 Data stored locally on the device
The following sensitive data is designed to be generated or stored locally on the device and is not required by ElectrumX servers for normal wallet operation:

| Data | Purpose | Intended storage |
|---|---|---|
| BIP39 mnemonic / seed phrase | Wallet generation and recovery | Android Keystore / protected local storage |
| Derived private keys | Local transaction signing | Android Keystore / protected local storage |
| RVN wallet address | Wallet display and operations | Local storage |
| Admin/operator keys, where present | Brand functions | Protected local storage |
| Settings and preferences | App configuration | Local preferences |

**Private keys and the seed phrase are not transmitted to an ElectrumX server during normal wallet operation.**

### 3.2 NFC verification and API backend
When you verify an NFC tag, the App may transmit technical data required for verification, including:

| Data | Purpose |
|---|---|
| Asset name | Identification of the Ravencoin asset |
| Encrypted NFC counter / verification parameter | Cryptographic verification |
| NFC MAC value | Cryptographic verification |
| IP address | Security, abuse prevention, rate limiting and technical request handling |

The backend request logger records request metadata consisting of **HTTP method, path, status code, duration and IP address**. It does not log request or response bodies. These metadata may be used for security, abuse prevention, rate limiting, technical diagnostics and aggregated operational metrics.

**Code-verified retention:** records persisted in the application tables `request_logs` and `rate_limit_events` are subject to an automated routine that removes records older than 30 days.

That routine **does not govern** possible console/stdout, container, operating-system, reverse-proxy, CDN, hosting-provider or ElectrumX-process logs. Retention of any such logs depends on the actual production configuration and this Policy does not invent a fixed retention period for them.

**Legal basis:** where applicable, processing of technical metadata by Developer-operated infrastructure relies on legitimate interests under Article 6(1)(f) GDPR for infrastructure security, abuse prevention, rate limiting, technical diagnostics and proportionate operational monitoring.

### 3.3 Blockchain and ElectrumX operations
When the App queries balances, history or UTXOs, or submits a transaction, it communicates with an ElectrumX server.

Depending on the request, an ElectrumX server may technically observe or receive:
- source IP address;
- timestamps, request frequency and connection metadata;
- JSON-RPC and script-hash queries;
- balance, history and UTXO queries;
- transaction identifiers and blockchain metadata;
- already-signed raw transactions submitted for broadcast.

Query patterns may permit correlation between network identifiers and activity visible on the public blockchain.

**Transaction creation and signing:** a transaction is initiated by the user, constructed by the App and signed on the device with keys controlled by the user. ElectrumX may receive the already-signed transaction and relay it to the Ravencoin network. ElectrumX does not possess the user's private key, independently choose the recipient or amount, or maintain a custodial account for the user.

### 3.4 Ravencoin Core node
A Ravencoin Core node performs network infrastructure functions including synchronization, validation and peer-to-peer propagation. By performing those functions it does not possess RavenTag users' private keys or maintain custodial RavenTag wallet accounts.

### 3.5 IPFS gateways and external content
The App may use IPFS gateways or other external resources to load images or metadata. Their operators may receive an IP address and other network metadata according to their own practices.

### 3.6 Camera and NFC
- **Camera:** used on-device to read QR codes; this function does not require the App to send camera images to the backend.
- **NFC:** reading occurs on the device; only verification parameters needed by the backend are transmitted as described above.

### 3.7 Data the Developer does not intentionally request
Normal App use does not require the Developer to request names, identity documents, postal addresses, IMEI, Android Advertising ID or precise geolocation. This does not change the fact that IP addresses and other network metadata may constitute personal data when processed by infrastructure.

---

## 4. Third-Party Services and Infrastructure

Independent infrastructure may include ElectrumX nodes, Ravencoin Core nodes, IPFS gateways, operating systems, app stores, network providers and hosting services not directly operated by the Developer.

When independent infrastructure is used, its operator may process network and blockchain data for its own purposes, legal bases and retention periods. The Developer cannot guarantee or control those practices.

---

## 5. Security and Non-Custodial Architecture

RavenTag uses local protection mechanisms and encrypted network channels where provided by the implementation. No technical measure can guarantee absolute security against every vulnerability, device compromise or network attack.

The non-custodial architecture means the Developer normally does not possess the keys required to recover or transfer the user's funds.

---

## 6. Data Retention

- **Local wallet data:** until wallet deletion, App data deletion or uninstall, according to device behavior.
- **Developer backend `request_logs` and `rate_limit_events`:** automated deletion of records older than 30 days according to the backend cleanup routine.
- **Runtime/console, reverse-proxy, system, CDN, hosting or ElectrumX logs:** retention depends on the actual deployment configuration and is not controlled by the application-database cleanup routine described above.
- **Ravencoin blockchain:** data recorded on the public blockchain is replicated by a decentralized network and cannot be unilaterally erased or modified by the Developer.

---

## 7. Purposes and GDPR Principles

For Developer-operated infrastructure, technical metadata may be processed where necessary and proportionate for:
- infrastructure security;
- abuse and attack prevention;
- rate limiting;
- diagnosis of errors and operational problems;
- technical statistics and aggregated operational metrics.

Processing is subject to GDPR principles including data minimization, purpose limitation, storage limitation, integrity and confidentiality.

---

## 8. Data Subject Rights

Where the GDPR applies to processing carried out by the Developer, a data subject may exercise, subject to the conditions and limitations laid down by law, rights of access, rectification, erasure, restriction and objection, together with any other applicable rights.

Requests may be sent to: legal@raventag.com

The right to lodge a complaint with the competent supervisory authority remains unaffected, including the Italian Garante per la protezione dei dati personali where competent.

Rights exercisable against the Developer concern data under the Developer's control and do not give the Developer unilateral power to erase data already recorded and replicated on the public Ravencoin blockchain.

---

## 9. Minors

The App is not intended for persons under 18. The Developer does not intend to knowingly collect minors' data through normal App use.

---

## 10. International Transfers

System and provider locations may vary with infrastructure configuration. Where personal data processed by the Developer is transferred to a country outside the European Economic Area, the transfer is subject to **Chapter V GDPR** and must rely on the applicable transfer mechanism, for example an adequacy decision where relevant or appropriate safeguards under Article 46 GDPR where required.

The mere physical location of a server in the United States or another third country is not, by itself, treated as proof that a valid transfer mechanism exists. Information about the mechanism applicable to infrastructure actually used may be requested at legal@raventag.com.

For backends or services operated independently, the relevant operator is responsible for its own location and transfer choices under applicable law.

---

## 11. Non-Custodial Nature and Technical Role of Infrastructure

RavenTag is designed as non-custodial software. Private keys remain under the user's control and the Developer does not maintain custodial crypto-asset accounts for users.

Developer-operated ElectrumX infrastructure is designed to perform technical blockchain-query functions and to relay to the network transactions already signed with user-controlled keys. ElectrumX does not independently choose the recipient or amount and does not sign on the user's behalf.

This section describes the technical architecture and **is not a blanket statement of exemption, authorization or regulatory classification under Regulation (EU) 2023/1114 (MiCA) or other laws**.

---

## 12. Changes to this Policy

The Developer may update this Policy when the App, infrastructure, processing practices or legal framework changes. The version and effective date appear at the beginning of the document.

---

## 13. Contact

**Alessandro Nocentini**  
GitHub: https://github.com/ALENOC/RavenTag  
Email: legal@raventag.com
