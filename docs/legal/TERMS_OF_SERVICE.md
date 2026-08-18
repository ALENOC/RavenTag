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

## 2. Description of the App and License Nature

RavenTag Verify is a mobile application providing:

- **NFC Tag Verification**: Reading and cryptographic verification of NTAG 424 DNA NFC chips linked to Ravencoin blockchain assets using RavenTag Protocol v1 (RTP-1).
- **Non-Custodial Ravencoin Wallet**: Generation, local storage, and autonomous management of a non-custodial BIP39/BIP44 HD wallet for the Ravencoin blockchain (RVN).
- **Asset Management** (Brand version only): Issuance, transfer, and local management of Ravencoin assets linked to physical products.

The App is a software tool for interacting in a self-custody manner with the Ravencoin blockchain and NFC hardware. It is not a financial service, an exchange, a bank, a custodial intermediary, or an investment or financial product.

The App and its related source code are distributed under the **RavenTag Source License (RTSL-1.0)**, a source-available software license that restricts certain commercial uses and third-party entity distributions. RavenTag does not constitute open-source software under OSI definitions.

---

## 3. Eligibility and Scope of Use

You must be at least 18 years old to use this App. By using the App, you represent and warrant that you are at least 18 years old and have the legal capacity to enter into these Terms in your jurisdiction.

### 3.1 Consumer Use of the Verify App
The NFC tag verification functionality of the RavenTag Verify App is designed for any consumer wishing to verify the authenticity of a physical product equipped with an NFC chip. Use of this feature does not require professional capability.

### 3.2 Wallet Functionality and Self-Custody
The Ravencoin wallet functionality involves self-custody, direct management, and transfer of digital assets on a public decentralized blockchain. By using these features, you acknowledge that you are acting independently, under your sole responsibility and financial risk, with full awareness of the risks described in Section 5.

### 3.3 Source Code and RTSL-1.0 License
The commercial use restriction contained in the source-available RavenTag Source License (RTSL-1.0) applies exclusively to developers, brands, and entities distributing, forking, or otherwise utilizing RavenTag source code. This restriction does not apply to end users of the App who use it solely to scan NFC tags or manage their wallet in self-custody.

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

### 4.3 Mnemonic Phrase (Seed Phrase) and User Responsibility
When creating a wallet, the App generates a 12-word BIP39 mnemonic phrase ("seed phrase"). To the maximum extent permitted by applicable law, the User is solely responsible for:
- Immediately writing down their seed phrase and storing it in a secure offline location;
- Maintaining the confidentiality and security of seed phrase and private key backups;
- Preventing unauthorized access, phishing, or social engineering attacks;
- Verifying recovery information.

**Loss of your seed phrase results in the permanent and unrecoverable loss of all funds and assets associated with your wallet. The Developer cannot restore access to your wallet under any circumstances.**

### 4.4 User Device Security
You are responsible for maintaining the security of your device. To the maximum extent permitted by law, the Developer is not liable for any loss of funds resulting from malware, device loss or theft, rooted/jailbroken devices, unauthorized access, or operating system compromises.

---

## 5. Blockchain, Financial Risks, and Regulatory Framework

### 5.1 Nature of Ravencoin and Network Infrastructure
Ravencoin (RVN) is a decentralized blockchain network maintained by independent miners and nodes.
- **Developer Infrastructure**: The Developer operates the public ElectrumX endpoint `electrumx.raventag.com` / `electrum.raventag.com` to support the App. For instances connected to it, this Developer-operated infrastructure does not constitute a third-party service.
- **Independent Third-Party Infrastructure**: The App may also interact with independent ElectrumX or Ravencoin Core nodes operated by third parties. The Developer does not control third-party servers.
- **Role of Ravencoin Core Nodes**: A public Ravencoin Core node performs synchronization, block and transaction validation, and P2P propagation functions. The Core node does not hold customer funds, manage user accounts, possess private keys, or exercise custody over RVN funds.

### 5.2 Financial Risk Acknowledgment, Volatility, and Irreversibility
By using the wallet features of this App, to the maximum extent permitted by applicable law, you acknowledge and accept that:
- RVN and blockchain assets are digital assets subject to severe price volatility and may lose all value;
- Blockchain transactions recorded on the Ravencoin network are **irreversible**. Once confirmed, a transaction cannot be cancelled, modified, or refunded by the Developer;
- Network transaction fees (miner fees) are paid directly to the network and are non-refundable;
- The User assumes all risks of chain reorganizations, forks, network congestion, node failure, or user input errors regarding addresses and amounts.

### 5.3 No Financial, Investment, Legal, or Tax Advice
Nothing in this App or in Developer communications constitutes financial, investment, legal, or tax advice. Displaying balances or asset metadata does not constitute an offer or solicitation to buy, sell, or hold crypto-assets.

### 5.4 Regulatory Framework (MiCA) and User Compliance
RavenTag is provided as non-custodial source-available software. The Developer does not hold users' private keys and does not exercise control or custody over users' crypto-assets. Relay of signed transactions through ElectrumX servers constitutes a purely technical data routing activity on a distributed protocol. The User is solely responsible for compliance with applicable tax and regulatory requirements in their jurisdiction.

---

## 6. NFC Hardware, Third-Party Assets, and IPFS Metadata

### 6.1 Third-Party NFC Hardware
The App interacts with third-party NTAG 424 DNA NFC chips. The Developer provides no warranty regarding the physical longevity or integrity of third-party NFC hardware.

### 6.2 Verification Results and Third-Party IPFS Metadata
Verification results are processed based on cryptographic checks. A positive result indicates cryptographic validity at the time of scanning but does not constitute a legal certificate of title. External content and images hosted on third-party IPFS gateways are created by independent entities and are not endorsed or controlled by the Developer.

---

## 7. Official Distribution and Security Warning

### 7.1 Authorized Channels and Signature Verification
The official distribution channels for RavenTag are:
1. **GitHub Releases** (https://github.com/ALENOC/RavenTag/releases)
2. **Google Play Store** (for the consumer Verify App)

Official releases are signed by the Developer and can be verified using `apksigner`.

### 7.2 Disclaimer for Unofficial Builds
To the maximum extent permitted by law, the Developer disclaims all liability for damages, malware, or financial losses resulting from the installation of App builds downloaded from unauthorized sources, forked, or modified by third parties.

---

## 8. Infrastructure Availability and No Duty of Perpetual Maintenance

The App depends on the Ravencoin network and network services. For Developer-operated infrastructure (`electrumx.raventag.com`), the Developer takes reasonable technical measures without offering uptime guarantees. The Developer reserves the right to maintain, suspend, modify, or discontinue Developer-operated infrastructure without generating a duty of perpetual maintenance, subject to mandatory law.

---

## 9. General Limitation of Liability and Savings Clause

### 9.1 Exclusion of Indirect and Consequential Damages
To the maximum extent permitted by applicable law, the Developer shall not be liable for indirect, consequential, incidental, special, or punitive damages, including loss of crypto funds, lost profits, lost opportunities, data loss, or business interruption.

### 9.2 Maximum Liability Cap and Differentiation
As the App is provided free of charge:
- For professional / business users: the total aggregate liability of the Developer is limited to the maximum extent permitted by law, up to zero euros (EUR 0).
- For consumer users: the Developer's liability for direct damages is limited to the minimum mandatory limit permitted by applicable mandatory law, taking into account that the App is provided free of charge.

### 9.3 Mandatory Law Savings Clause (Art. 1229 Italian Civil Code & Consumer Protection)
Nothing in these Terms excludes or limits the liability of the Developer for intent (dolo) or gross negligence (colpa grave) under Article 1229 of the Italian Civil Code, or any other liability that cannot be lawfully excluded or limited under applicable mandatory consumer protection law.

---

## 10. No Fiduciary Relationship and No Duty to Monitor

Use of the App does not create any fiduciary, agency, brokerage, or partnership relationship between the User and the Developer. The Developer has no duty to monitor User transactions or detect scams, malicious addresses, or fraudulent assets issued by third parties.

---

## 11. Modifications to App and Terms

The Developer reserves the right to update the App and these Terms for justified grounds (e.g., regulatory compliance, cybersecurity, technical evolution). Modifications will be published and continued use constitutes acceptance.

---

## 12. Governing Law, Jurisdiction, and Consumer Carve-Out

These Terms are governed by Italian law. Disputes with non-consumer users shall be subject to the exclusive jurisdiction of the competent courts in Italy. For consumer users in the European Union, mandatory rights and forum rules under Regulation (EC) 593/2008 (Rome I) and Regulation (EU) 1215/2012 (Brussels I bis) remain unaffected.

---

## 13. Severability and No Waiver

If any provision of these Terms is held invalid or unenforceable, that provision shall be limited to the minimum extent necessary and the remaining provisions shall remain in full force and effect. Failure to enforce a right shall not constitute a waiver of future enforcement.

---

## 14. Entire Agreement and Document Priority

These Terms, together with the Privacy Policy, constitute the entire agreement between the User and the Developer. In case of conflict with the RTSL-1.0 license regarding source code usage, the terms of the RTSL-1.0 license shall prevail.

---

## 15. Contact Information

**Alessandro Nocentini**
GitHub: https://github.com/ALENOC/RavenTag
Email: legal@raventag.com
