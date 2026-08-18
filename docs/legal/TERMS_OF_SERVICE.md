# RavenTag Verify - Terms of Service

**Version 1.1 - Effective Date: August 18, 2026**
**Copyright 2026-present Alessandro Nocentini. All rights reserved.**

---

> **OFFICIAL VERSION.** This document in the Italian language constitutes the legally binding version of the Terms of Service. In the event of any discrepancy, contradiction, or ambiguity between this version and any translation, the Italian version shall prevail.

---

## 1. Acceptance of Terms

Upon initial launch, the App presents these Terms of Service and Privacy Policy. You must explicitly accept both documents by checking the corresponding boxes before proceeding. Checking those boxes constitutes your express and informed acceptance of these Terms. If you do not accept, you must not use the App.

By downloading, installing, or continuing to use the App after acceptance, you ("User") confirm that you are legally bound by these Terms. If you do not agree with these Terms in their entirety, you must immediately uninstall and cease using the App.

These Terms constitute a legally binding agreement between you and Alessandro Nocentini ("Developer"), author of RavenTag Verify.

---

## 2. Description of the App

RavenTag Verify is a mobile application providing:

- **NFC Tag Verification**: Reading and cryptographic verification of NTAG 424 DNA NFC chips linked to Ravencoin blockchain assets using RavenTag Protocol v1 (RTP-1).
- **Non-Custodial Ravencoin Wallet**: Generation, local storage, and autonomous management of a non-custodial BIP39/BIP44 HD wallet for the Ravencoin blockchain (RVN).
- **Asset Management** (Brand version only): Issuance, transfer, and local management of Ravencoin assets linked to physical products.

The App is a software tool for interacting in a non-custodial manner with the Ravencoin blockchain and NFC hardware. It is not a financial service, an exchange, a bank, a custodial intermediary, or an investment or financial product.

---

## 3. Eligibility and Scope of Use

You must be at least 18 years old to use this App. By using the App, you represent and warrant that you are at least 18 years old and have the legal capacity to enter into these Terms in your jurisdiction.

### 3.1 Consumer Use of the Verify App
The NFC tag verification functionality of the RavenTag Verify App is designed for any consumer wishing to verify the authenticity of a physical product equipped with an NFC chip. Use of this feature does not require professional capability.

### 3.2 Wallet Functionality and Self-Custody
The Ravencoin wallet functionality involves self-custody, direct management, and transfer of digital assets on a public decentralized blockchain. By using these features, you acknowledge that you are acting independently, under your sole responsibility and financial risk, with full awareness of the risks described in Section 5.

### 3.3 Source Code and Infrastructure
The professional use restriction contained in the RavenTag Source License (RTSL-1.0) applies exclusively to developers, brands, and entities distributing, forking, or otherwise utilizing RavenTag source code. This restriction does not apply to end users of the App who use it solely to scan NFC tags or manage their wallet in self-custody.

---

## 4. Non-Custodial Wallet and Transaction Architecture

### 4.1 No Custody by the Developer
RavenTag Verify provides an exclusively non-custodial Ravencoin wallet. This means:
- The Developer **does not** hold, store, manage, control, or have access to your private keys, mnemonic phrase, or funds at any time.
- You are the sole and exclusive custodian (self-custodian) of your cryptographic keys, funds, and digital assets.
- The Developer has no technical ability to authorize transactions, freeze your funds, or recover your mnemonic phrase or keys under any circumstances.

### 4.2 Transaction Creation, Signing, and Relay Workflow
For every transaction initiated through the App:
1. The User initiates the transaction from the App interface;
2. The App constructs the raw transaction locally on the device;
3. The transaction is cryptographically signed locally on the device using private keys controlled by the User;
4. The App transmits the **already signed** transaction to ElectrumX infrastructure (Developer-operated or third-party);
5. ElectrumX relays/broadcasts the signed transaction to the Ravencoin Core network for inclusion in the distributed ledger.

ElectrumX infrastructure does not possess the User's private key, cannot independently generate valid signatures, does not decide the transaction recipient or amount, does not take possession of User RVN, and maintains no custodial accounts or balances.

### 4.3 Mnemonic Phrase (Seed Phrase)
When creating a wallet, the App generates a 12-word BIP39 mnemonic phrase ("seed phrase"). You must:
- Immediately write down your seed phrase and store it in a secure offline location.
- Never share your seed phrase with anyone, including the Developer.
- Never store your seed phrase in unencrypted digital format or on third-party cloud services.

**Loss of your seed phrase results in the permanent and unrecoverable loss of all funds and assets associated with your wallet. The Developer cannot restore access to your wallet under any circumstances.**

### 4.4 Device Security
You are responsible for maintaining the security of your device. The Developer is not responsible for any loss of funds resulting from malware, device loss, unauthorized access, or operating system compromises.

---

## 5. Blockchain, Financial Risks, and Regulatory Framework

### 5.1 Nature of Ravencoin and Network Infrastructure
Ravencoin (RVN) is an open-source decentralized blockchain network maintained by independent miners and nodes.
- **Developer Infrastructure**: The Developer operates the public ElectrumX endpoint `electrumx.raventag.com` / `electrum.raventag.com` to support the App.
- **Independent Third-Party Infrastructure**: The App may also interact with independent ElectrumX or Ravencoin Core nodes operated by third parties. The Developer does not control third-party servers.
- **Role of Ravencoin Core Nodes**: A public Ravencoin Core node performs synchronization, block and transaction validation, and P2P propagation functions. The Core node does not hold customer funds, manage user accounts, possess private keys, or exercise custody over RVN funds.

### 5.2 Financial Risk Acknowledgment and Irreversibility
By using the wallet features of this App, you explicitly acknowledge and accept that:
- RVN and blockchain assets are digital assets subject to severe price volatility.
- Blockchain transactions recorded on the Ravencoin network are **irreversible**. Once confirmed, a transaction cannot be cancelled, modified, or refunded by the Developer.
- Network transaction fees (miner fees) are paid directly to the network and are non-refundable.
- The Developer is not liable for financial losses arising from user error, market shifts, or blockchain network outages.

### 5.3 No Financial Advice
Nothing in this App or in Developer communications constitutes financial, investment, or legal advice.

### 5.4 Regulatory Framework (MiCA)
RavenTag is provided as non-custodial open-source software. The Developer does not hold users' private keys and does not exercise control or custody over users' crypto-assets. Relay of signed transactions through ElectrumX servers constitutes a purely technical data routing activity on a distributed protocol. The User is solely responsible for compliance with applicable tax and regulatory requirements in their jurisdiction.

---

## 6. NFC Hardware and Verification Results

### 6.1 Hardware Limitations
The App interacts with NTAG 424 DNA NFC chips. The Developer provides no warranty regarding the physical longevity or integrity of third-party NFC hardware.

### 6.2 Verification Results
Verification results are processed based on cryptographic checks of received data. A positive result indicates cryptographic signal validity at the time of scanning but does not constitute an absolute legal guarantee or certificate of title.

---

## 7. Official Distribution and Security Warning

### 7.1 Authorized Distribution Channels
The official distribution channels for RavenTag are:
1. **GitHub Releases** (https://github.com/ALENOC/RavenTag/releases)
2. **Google Play Store** (for the consumer Verify App)

### 7.2 Cryptographic Signature Verification
Official releases are signed by the Developer. Users can verify APK file signatures using `apksigner`.

### 7.3 Disclaimer for Unofficial Builds
The Developer disclaims all liability for damages, malware, or financial losses resulting from the installation of App builds downloaded from unauthorized sources or modified by third parties.

---

## 8. Network Dependency and Infrastructure Distinction

The App depends on the proper functioning of the Ravencoin network. The Developer is not liable for network outages, blockchain forks, or third-party node unavailability. For Developer-operated infrastructure (`electrumx.raventag.com`), the Developer takes reasonable measures to ensure availability without offering uptime guarantees.

---

## 9. Limitation of Liability

To the maximum extent permitted by applicable law:
- The App is provided "AS IS" and "AS AVAILABLE" without warranties of any kind.
- The Developer shall not be liable for direct, indirect, special, or consequential damages (including loss of crypto funds, profits, or data) arising from the use or inability to use the App.
- Since the App is distributed free of charge, the total aggregate liability of the Developer is limited to zero euros (EUR 0).

---

## 10. Modifications to App and Terms

The Developer reserves the right to update or modify the App and these Terms at any time. Continued use of the App constitutes acceptance of the modified Terms.

---

## 11. Governing Law and Jurisdiction

These Terms are governed by Italian law. Any disputes shall be subject to the exclusive jurisdiction of the competent Italian courts, subject to mandatory consumer protection laws.

---

## 12. Severability

If any provision of these Terms is held invalid or unenforceable, the remaining provisions shall remain in full force and effect.

---

## 13. Entire Agreement

These Terms, together with the Privacy Policy, constitute the entire agreement between the User and the Developer.

---

## 14. Contact Information

**Alessandro Nocentini**
GitHub: https://github.com/ALENOC/RavenTag
Email: legal@raventag.com
