# RavenTag Verify - Terms of Service

**Version 1.0 - Effective date: 2026-03-21**
**Copyright 2026-present Alessandro Nocentini. All rights reserved.**

---

> **TRANSLATION NOTICE.** This document is a courtesy translation. The Italian version ([TERMS_OF_SERVICE_IT.md](TERMS_OF_SERVICE_IT.md)) is the sole legally binding version. In the event of any discrepancy, contradiction, or ambiguity between this translation and the Italian text, the Italian text shall prevail.

---

## 1. Acceptance of Terms

On first launch, the App presents these Terms of Service and the Privacy Policy. You must explicitly accept both documents by ticking the corresponding checkboxes before you can proceed. Ticking those checkboxes constitutes your express, informed acceptance of these Terms. If you do not accept, you must not use the App.

By downloading, installing, or continuing to use the App after acceptance, you ("User") confirm that you are legally bound by these Terms. If you do not agree to these Terms in their entirety, you must immediately uninstall and stop using the App.

These Terms constitute a legally binding agreement between you and Alessandro Nocentini ("Developer"), the sole author and operator of RavenTag Verify.

---

## 2. Description of the App

RavenTag Verify is a mobile application that provides:

- **NFC tag verification**: reading and cryptographically verifying NTAG 424 DNA NFC chips linked to Ravencoin blockchain assets, using the RavenTag Protocol v1 (RTP-1).
- **Ravencoin wallet**: generation, storage, and management of a non-custodial BIP39/BIP44 HD wallet for the Ravencoin blockchain (RVN).
- **Asset management** (Brand version only): issuance, transfer, and revocation of Ravencoin assets linked to physical products.

The App is a tool for interacting with the Ravencoin blockchain and NFC hardware. It is not a financial service, exchange, or investment product.

---

## 3. Eligibility

You must be at least 18 years of age to use this App. By using the App, you represent and warrant that you are at least 18 years old and have the legal capacity to enter into these Terms in your jurisdiction.

### 3.1 Consumer use of the Verify App

The NFC tag verification feature of the RavenTag Verify App is designed for any consumer who wishes to check the authenticity of a physical product carrying an NFC chip. Use of this feature does not require any professional capacity.

### 3.2 Wallet and blockchain features

The Ravencoin wallet functionality involves the custody, management, and transfer of digital assets on a public blockchain. By using these features you acknowledge that you are acting on your own initiative and at your own financial risk, and that you have sufficient knowledge of the risks described in Section 5.

### 3.3 Source code and infrastructure

The restriction to professional use found in the RavenTag Source License (RTSL-1.0) applies exclusively to developers, brands, and entities who deploy, fork, or otherwise use the RavenTag source code. That restriction does not apply to end users of the App who use it solely to scan NFC tags or manage their own wallet.

---

## 4. Non-Custodial Wallet

### 4.1 You Are Solely Responsible for Your Wallet

RavenTag Verify provides a non-custodial Ravencoin wallet. This means:

- The Developer does **not** hold, store, manage, or have access to your private keys, mnemonic phrase, or funds at any time.
- You are the sole custodian of your cryptographic keys and funds.
- The Developer cannot recover your wallet, mnemonic phrase, private keys, or funds under any circumstances.

### 4.2 Mnemonic Phrase (Seed Phrase)

When you create a wallet, the App generates a 12-word BIP39 mnemonic phrase ("seed phrase"). You must:

- Write down your seed phrase immediately and store it in a safe, offline location.
- Never share your seed phrase with anyone, including the Developer.
- Never store your seed phrase in digital form on an internet-connected device.

**Loss of your seed phrase results in permanent and irrecoverable loss of all funds and assets associated with your wallet. The Developer cannot restore access to your wallet under any circumstances.**

### 4.3 Device Security

You are responsible for maintaining the security of your device. The Developer is not liable for any loss of funds resulting from:

- Unauthorized access to your device.
- Malware, viruses, or spyware on your device.
- Device loss or theft.
- Operating system vulnerabilities.

---

## 5. Blockchain and Financial Risks

### 5.1 Nature of Ravencoin

Ravencoin (RVN) is a decentralized, open-source blockchain network operated by independent third parties. The Developer has no control over the Ravencoin network.

### 5.2 Financial Risk Acknowledgment

By using the wallet functionality of this App, you explicitly acknowledge and accept that:

- Ravencoin (RVN) and all assets issued on the Ravencoin blockchain are volatile digital assets whose value may fluctuate significantly or decrease to zero.
- Any funds used to acquire RVN, pay transaction fees, or issue blockchain assets may be lost entirely.
- Blockchain transactions are **irreversible**. Once a transaction is confirmed on the Ravencoin blockchain, it cannot be reversed, cancelled, or refunded by the Developer or any third party.
- Transaction fees (network fees) are non-refundable regardless of the outcome of the transaction.
- The Developer is not liable for any financial loss, including but not limited to: loss of RVN, loss of issued assets, failed transactions, incorrect transactions, or loss due to market conditions.

### 5.3 No Financial Advice

Nothing in this App or in any communication from the Developer constitutes financial advice, investment advice, or a recommendation to acquire, hold, transfer, or dispose of RVN or any digital asset. You should consult a qualified financial advisor before making any financial decisions.

### 5.4 Regulatory Risk

The legal status of cryptocurrencies and blockchain assets varies by jurisdiction and may change over time. You are solely responsible for ensuring that your use of this App and the Ravencoin blockchain complies with all applicable laws and regulations in your jurisdiction, including but not limited to tax obligations, anti-money laundering (AML) requirements, and securities regulations.

---

## 6. NFC Hardware

### 6.1 Hardware Limitations

The App interacts with NTAG 424 DNA NFC chips and other NFC hardware. The Developer makes no warranty regarding:

- The compatibility of the App with all NFC-capable devices.
- The security, longevity, or physical integrity of any NFC chip.
- The accuracy of NFC verification results in all environmental conditions.

### 6.2 Verification Results

NFC tag verification results provided by the App are based on cryptographic computations and data available at the time of verification. The Developer does not guarantee that verification results are accurate, complete, or up to date. A verification result does not constitute a legal certificate of authenticity.

---

## 7. Official Distribution and Security Notice

### 7.1 Authorized Distribution Channels

The only official and authorized distribution channels for RavenTag Verify and RavenTag Brand Manager applications are:

1. **GitHub Releases** at https://github.com/ALENOC/RavenTag/releases (primary official channel)
2. **Google Play Store** (only for Verify consumer app)

Any other distribution channel, including but not limited to third-party app stores, APK download websites, torrent networks, or peer-to-peer file sharing platforms, is **not authorized** and is not affiliated with the Developer.

### 7.2 Cryptographic Signature Verification

All official releases are cryptographically signed with the Developer's release key. Users who download the App from GitHub Releases can verify the authenticity of their download by checking the APK signature using the following command:

```bash
apksigner verify --print-certs RavenTag-*.apk
```

The certificate fingerprint for official releases will be published on the GitHub Releases page. Users are strongly encouraged to verify this fingerprint before installing.

### 7.3 Risks of Unofficial Builds

Downloading, installing, or using the App from any source other than the official channels listed in Section 7.1 carries significant risks, including but not limited to:

- **Modified or malicious code**: Unofficial builds may contain malware, spyware, backdoors, or altered security logic designed to steal sensitive information.
- **Credential theft**: Modified apps may capture and transmit your wallet recovery phrase, private keys, administrative credentials, or personal data to third parties.
- **No security updates**: Unofficial builds do not receive automatic security patches or updates, leaving users vulnerable to known exploits.
- **No technical support**: The Developer cannot and will not provide technical support, troubleshooting, or assistance for issues arising from unofficial builds.
- **Financial loss**: Compromised wallet keys or credentials can lead to irreversible loss of cryptocurrency funds (RVN) and blockchain assets.

### 7.4 Disclaimer for Unofficial Builds

**The Developer expressly disclaims all liability** for any damages, losses, security incidents, data breaches, financial losses, or other harms resulting from the use of RavenTag applications obtained from unofficial or unauthorized sources.

Only applications downloaded from the official GitHub Releases page or the official Google Play Store listing are considered authentic, supported, and covered by these Terms of Service.

By using the App, you acknowledge and agree that:
- You have downloaded the App from an official channel listed in Section 7.1.
- You understand the risks associated with downloading software from unofficial sources.
- You assume all responsibility for verifying the authenticity of your download.
- The Developer bears no responsibility for any harm resulting from downloads from unofficial sources.

---

## 8. Ravencoin Network Dependency

The App depends on the Ravencoin blockchain network and associated infrastructure, including RPC nodes and IPFS gateways, which are operated by independent third parties. The Developer is not responsible for:

- Unavailability or downtime of the Ravencoin network or any node.
- Network congestion causing delayed or failed transactions.
- Changes to the Ravencoin protocol that affect the functionality of the App.
- Chain splits or forks of the Ravencoin network.

---

## 8. Limitation of Liability

To the maximum extent permitted by applicable law:

- The App is provided "AS IS" without any warranty of any kind.
- The Developer shall not be liable for any direct, indirect, incidental, special, consequential, or punitive damages, including but not limited to loss of funds, loss of data, loss of profits, or business interruption, arising from your use of or inability to use the App.
- The Developer's total aggregate liability to you for any claim arising from or related to these Terms or the App shall not exceed zero euros (EUR 0), as the App is provided free of charge.

### 8.1 Specific Disclaimer for Unofficial Builds

Notwithstanding any other provision in these Terms:

- **No liability for unofficial builds**: The Developer expressly disclaims all liability, whether in tort, contract, or otherwise, for any damages, losses, security incidents, data breaches, financial losses, identity theft, or other harms resulting from the use of RavenTag applications obtained from any source other than the official channels listed in Section 7.1.
- **User assumption of risk**: By downloading the App from any source other than the official channels, you expressly acknowledge that you are doing so at your own risk and that you assume full and exclusive responsibility for any consequences arising from such download or use.
- **No warranty for unofficial builds**: The Developer makes no representations or warranties of any kind, express or implied, regarding any application obtained from unofficial sources. Such applications are provided "AS IS" with all faults and without any warranty whatsoever.
- **Exclusion from support and updates**: Applications obtained from unofficial sources are not covered by these Terms, are not entitled to receive updates or security patches, and are not eligible for any form of technical support from the Developer.

---

## 10. Privacy

Your use of the App is also governed by the RavenTag Verify Privacy Policy, which is incorporated into these Terms by reference. Please read the Privacy Policy carefully.

---

## 11. Modifications to the App and Terms

The Developer reserves the right to:

- Modify, suspend, or discontinue the App or any of its features at any time, with or without notice.
- Update these Terms at any time. Updated Terms will be posted within the App and on the project repository. Continued use of the App after any update constitutes acceptance of the revised Terms.

---

## 12. Governing Law and Jurisdiction

These Terms shall be governed by and construed in accordance with the laws of Italy, including the Italian Consumer Code (D.Lgs. 206/2005) where applicable, the Italian Civil Code, and Legislative Decree 231/2007 on anti-money laundering where relevant.

Any dispute arising from these Terms shall be subject to the jurisdiction of the competent Italian courts. Notwithstanding the foregoing, you may also be entitled to bring proceedings in the courts of your country of residence under applicable EU consumer protection law.

---

## 13. Severability

If any provision of these Terms is found to be invalid or unenforceable, the remaining provisions shall continue in full force and effect.

---

## 14. Entire Agreement

These Terms, together with the Privacy Policy, constitute the entire agreement between you and the Developer regarding your use of the App. They supersede all prior agreements, representations, and understandings.

---

## 15. Contact

For any questions regarding these Terms, contact the Developer at:
https://github.com/ALENOC/RavenTag
legal@raventag.com

---

*RavenTag Verify is an open-source project licensed under the RavenTag Source License (RTSL-1.0).*
*Source code: https://github.com/ALENOC/RavenTag*
