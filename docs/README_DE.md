<p align="center">
  <img src="../pictures/RavenTag_Logo.jpg" width="380" alt="RavenTag"/>
</p>

# RavenTag

[![Lizenz](https://img.shields.io/badge/Lizenz-RTSL--1.0-orange.svg)](../LICENSE)
[![Protokoll](https://img.shields.io/badge/Protokoll-RTP--1-orange.svg)](protocol.md)
[![Namensnennung Erforderlich](https://img.shields.io/badge/Namensnennung-Erforderlich-green.svg)](../LICENSE)
[![Release](https://img.shields.io/github/v/release/ALENOC/RavenTag)](https://github.com/ALENOC/RavenTag/releases/latest)

**In anderen Sprachen lesen:**
[🇬🇧 English](../README.md) |
[🇮🇹 Italiano](README_IT.md) |
[🇫🇷 Français](README_FR.md) |
[🇪🇸 Español](README_ES.md) |
[🇨🇳 中文](README_ZH.md) |
[🇯🇵 日本語](README_JA.md) |
[🇰🇷 한국어](README_KO.md) |
[🇷🇺 Русский](README_RU.md)

---

**RavenTag ist eine Open-Source-Plattform zur Falschungsbekampfung. Sie verknupft NTAG 424 DNA NFC-Chips mit Ravencoin-Blockchain-Assets uber das RTP-1-Protokoll und ermoglicht Marken den Nachweis der Echtheit physischer Produkte ohne Abhangigkeit von einer zentralen Instanz.**

## RTP-1-Protokoll

RTP-1 (RavenTag Protocol v1) definiert das JSON-Schema, das auf IPFS gespeichert und von den Ravencoin-Asset-Metadaten referenziert wird.

### Metadaten-Schema (v1.1)

```json
{
  "raventag_version": "RTP-1",
  "parent_asset": "FASHIONX",
  "sub_asset": "FASHIONX/TASCHE001",
  "variant_asset": "SN0001",
  "nfc_pub_id": "<sha256hex>",
  "crypto_type": "ntag424_sun",
  "algo": "aes-128",
  "image": "ipfs://<cid>",
  "description": "<Produktbeschreibung>"
}
```

**Feldbeschreibungen**:
- `raventag_version`: Protokollversion (immer "RTP-1")
- `parent_asset`: Root-Asset-Name (z.B. "FASHIONX")
- `sub_asset`: Vollständiger Sub-Asset-Pfad (z.B. "FASHIONX/TASCHE001")
- `variant_asset`: Einzigartige Token-Seriennummer (z.B. "SN0001") - nur bei Unique Tokens
- `nfc_pub_id`: SHA-256(UID || BRAND_SALT) - öffentlicher Chip-Fingerabdruck
- `crypto_type`: NFC-Krypto-Typ (immer "ntag424_sun" für NTAG 424 DNA)
- `algo`: Verschlüsselungsalgorithmus (immer "aes-128")
- `image`: IPFS-URI des Produktbilds (optional)
- `description`: Produktbeschreibung (optional)


## Was ist RavenTag?

Falschungen kosten Marken und Verbraucher jedes Jahr Milliarden. RavenTag lost dieses Problem durch drei kombinierte Technologien:

**1. Hardware-gesicherter NFC-Chip (NTAG 424 DNA)**
Jedes authentifizierte Produkt tragt einen NXP NTAG 424 DNA-Chip. Bei jedem Tap erzeugt der Chip eine eindeutige, kryptografisch signierte URL via AES-128-CMAC (SUN: Secure Unique NFC). Der AES-Schlussel liegt im Chip-Silizium und kann nicht extrahiert oder geklont werden.

**2. Ravencoin-Blockchain**
Die Marke registriert jeden serialisierten Artikel als einzigartiges On-Chain-Token, z. B. `FASHIONX/TASCHE001#SN0001`. Die Token-Metadaten (auf IPFS) enthalten den offentlichen Chip-Fingerabdruck: `nfc_pub_id = SHA-256(chip_uid || BRAND_SALT)`.

**3. Markensouverane Schlusselvertaltung**
AES-Schlussel werden serverseitig aus `BRAND_MASTER_KEY` abgeleitet. Sie verlassen niemals den Markenserver.

## Warum Ravencoin?

[Ravencoin](https://ravencoin.org) ist ein Bitcoin-Fork, der speziell fur die Asset-Ausgabe entwickelt wurde:

- **Natives Asset-Protokoll**: Root-Assets, Sub-Assets und einzigartige Token sind erstklassige Protokollfunktionen. Keine Smart Contracts.
- **Asset-Hierarchie passt zur Markenstruktur**: MARKE (Root) / PRODUKT (Sub-Asset) / SERIEN-NR#TAG (einzigartiger Token).
- **KAWPOW Proof-of-Work**: ASIC-resistenter Algorithmus.
- **Kein ICO, kein Premine**: Ravencoin startete fair im Januar 2018.
- **Vorhersehbare niedrige Gebuhren**: 500 RVN fur ein Root-Asset, 100 RVN pro Produktlinie, 5 RVN pro serialisiertem Artikel.

Die RTSL-1.0-Lizenz verlangt, dass alle abgeleiteten Werke ausschliesslich Ravencoin fur alle Blockchain-Operationen verwenden.

## Vier Schutzstufen

| Stufe | Technologie | Was ein Angreifer benotigt |
|-------|-------------|---------------------------|
| **1. Ravencoin-Konsens** | Parent-Asset-Eigentum von jedem Knoten verifiziert | Muss das Ravencoin-Parent-Asset besitzen |
| **2. nfc_pub_id-Bindung** | `SHA-256(UID \|\| BRAND_SALT)` in IPFS-Metadaten | Muss `BRAND_SALT` kennen (nie offentlich) |
| **3. AES-Schlusselableitung** | `AES-ECB(BRAND_MASTER_KEY, [Slot \|\| UID])` pro Chip | Muss `BRAND_MASTER_KEY` kennen |
| **4. NTAG 424 DNA-Silizium** | Nicht klon bares NXP-Hardware | Muss NXP-Silizium physisch klonen (nicht moglich) |

## Zwei Android-Apps

### RavenTag Verify (Verbraucher-App)

Download: [RavenTag-Verify-v1.3.8.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| Funktion | Details |
|---|---|
| NFC-Tag scannen | Tap auf NTAG 424 DNA Chip, Ergebnis AUTHENTISCH / WIDERRUFEN |
| Vollstandige Verifizierung | SUN MAC + Ravencoin-Blockchain + Widerrufsprufung |
| Ravencoin-Wallet | BIP44 `m/44'/175'/0'/0/0`, BIP39 12-Wort-Mnemonic |
| Mehrsprachig | EN, IT, FR, DE, ES, ZH, JA, KO, RU |

### RavenTag Brand Manager (Operator-App)

Download: [RavenTag-Brand-v1.3.8.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| Funktion | Details |
|---|---|
| Ravencoin-Assets ausgeben | Root (500 RVN), Sub-Asset (100 RVN), einzigartiger Token (5 RVN) |
| NTAG 424 DNA-Chips programmieren | AES-128-Schlussel + SUN-URL via ISO 7816-4 APDUs |
| HD-Wallet | BIP44, lokale UTXO-Signierung, BIP39 12-Wort-Mnemonic |
| Ubertragen / Widerrufen / Verbrennen | Vollstandiges Asset-Lebenszyklusmanagement |
| Produktmetadaten | Upload des RTP-1 JSON auf IPFS uber Pinata, CIDv0-Hash on-chain referenziert |

## Rollen und Zugriffsebenen

Rollen werden bei der Wallet-Erstellung durch Eingabe eines Steuerschluessels (Admin oder Operator) festgelegt. Die App bestimmt die Rolle automatisch durch Validierung des Schluessels am Backend. Die Rolle ist an die Wallet gebunden und kann nicht in den Einstellungen geaendert werden.

| Rolle | Steuerschluessel | Berechtigungen in der Android-App |
|---|---|---|
| **Admin** | Admin-Schluessel (`X-Admin-Key`) | Alle Operationen: Root-/Sub-Asset-Ausgabe, einzigartige Token-Ausgabe, Sperrung/Entsperrung, RVN senden, alle Asset-Typen uebertragen |
| **Operator** | Operator-Schluessel (`X-Operator-Key`) | Nur Ausgabe einzigartiger Token. Erstellung von Root-/Sub-Assets, Sperrung/Entsperrung, RVN senden und Root-/Sub-Asset-Transfers sind gesperrt. |

**Anwendungsfall:** Ein Brand-Admin kann mehrere Operator-Geraete auf derselben Wallet vorkonfigurieren, die die Owner-Assets enthaelt. Jedes Operator-Geraet kann einzigartige Token (Seriennummern) ausgeben, ohne Zugang zur Asset-Verwaltung oder Wallet.

## NFC-Tap-Verhalten

**App installiert:** Die Tag-URL wird direkt von RavenTag Verify abgefangen. Die App offnet den Scan-Bildschirm und fuhrt die vollstandige Verifizierung durch.

**App nicht installiert:** Die Tag-URL offnet den Browser, der eine Installationsseite mit Logo und Download-Link anzeigt.

## Projektstruktur

```
RavenTag/
├── backend/        Node.js + Express + SQLite
├── frontend/       Next.js 14 + Tailwind CSS
├── android/        Kotlin + Jetpack Compose
├── docs/           protocol.md, architecture.md, legal/, README_*.md
├── pictures/       Logo-Assets
├── docker-compose.yml
└── LICENSE         RavenTag Source License (RTSL-1.0)
```

## Rechtliches

- [Nutzungsbedingungen](legal/TERMS_OF_SERVICE.md)
- [Datenschutzerklarung](legal/PRIVACY_POLICY.md)

Rechtliche Anfragen: legal@raventag.com

## Namensnennung (RTSL-1.0)

RavenTag wird unter der **RavenTag Source License (RTSL-1.0)** veroffentlicht. Vollstandiger Text: [LICENSE](../LICENSE).

[![Lizenz](https://img.shields.io/badge/Lizenz-RTSL--1.0-orange.svg)](../LICENSE)
