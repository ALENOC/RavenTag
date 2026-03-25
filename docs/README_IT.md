<p align="center">
  <img src="../pictures/RavenTag_Logo.jpg" width="380" alt="RavenTag"/>
</p>

# RavenTag

[![Licenza](https://img.shields.io/badge/Licenza-RTSL--1.0-orange.svg)](../LICENSE)
[![Protocollo](https://img.shields.io/badge/Protocollo-RTP--1-orange.svg)](protocol.md)
[![Attribuzione Richiesta](https://img.shields.io/badge/Attribuzione-Richiesta-green.svg)](../LICENSE)
[![Release](https://img.shields.io/github/v/release/ALENOC/RavenTag)](https://github.com/ALENOC/RavenTag/releases/latest)

**Leggi in altre lingue:**
[🇬🇧 English](../README.md) |
[🇫🇷 Français](README_FR.md) |
[🇩🇪 Deutsch](README_DE.md) |
[🇪🇸 Español](README_ES.md) |
[🇨🇳 中文](README_ZH.md) |
[🇯🇵 日本語](README_JA.md) |
[🇰🇷 한국어](README_KO.md) |
[🇷🇺 Русский](README_RU.md)

---

**RavenTag è una piattaforma anti-contraffazione open-source e trustless. Collega chip NFC NTAG 424 DNA ad asset sulla blockchain Ravencoin usando il protocollo RTP-1, permettendo ai brand di dimostrare l'autenticità dei prodotti fisici senza dipendere da alcuna autorità centrale, inclusa RavenTag stessa.**

## Protocollo RTP-1

RTP-1 (RavenTag Protocol v1) definisce lo schema JSON memorizzato su IPFS e referenziato dai metadati degli asset Ravencoin.

### Schema metadati (v1.1)

```json
{
  "raventag_version": "RTP-1",
  "parent_asset": "FASHIONX",
  "sub_asset": "FASHIONX/BAG001",
  "variant_asset": "SN0001",
  "nfc_pub_id": "<sha256hex>",
  "crypto_type": "ntag424_sun",
  "algo": "aes-128",
  "image": "ipfs://<cid>",
  "description": "<descrizione prodotto>"
}
```

**Descrizione campi:**
- `raventag_version`: Versione del protocollo (sempre "RTP-1")
- `parent_asset`: Nome asset root (es. "FASHIONX")
- `sub_asset`: Percorso sub-asset completo (es. "FASHIONX/BAG001")
- `variant_asset`: Seriale token unico (es. "SN0001") - presente solo per token unici
- `nfc_pub_id`: SHA-256(UID || BRAND_SALT) - impronta pubblica del chip
- `crypto_type`: Tipo crypto NFC (sempre "ntag424_sun" per NTAG 424 DNA)
- `algo`: Algoritmo di cifratura (sempre "aes-128")
- `image`: URI IPFS dell'immagine prodotto (opzionale)
- `description`: Descrizione prodotto (opzionale)

## Cos'è RavenTag?

La contraffazione costa ai brand e ai consumatori miliardi ogni anno. Le soluzioni anti-contraffazione esistenti sono costose, richiedono fiducia in un servizio di terze parti e vincolano i brand a ecosistemi proprietari.

RavenTag risolve il problema combinando tre tecnologie:

**1. Chip NFC hardware-secured (NTAG 424 DNA)**
Ogni prodotto autenticato porta un chip NXP NTAG 424 DNA. Ad ogni lettura il chip produce un URL univoco e firmato crittograficamente tramite AES-128-CMAC (SUN: Secure Unique NFC). La chiave AES risiede nel silicio del chip e non può essere estratta o clonata.

**2. Blockchain Ravencoin**
Il brand registra ogni articolo serializzato come token unico on-chain, ad esempio `FASHIONX/BAG001#SN0001`. I metadati del token (su IPFS) contengono l'impronta pubblica del chip: `nfc_pub_id = SHA-256(chip_uid || BRAND_SALT)`. Il record blockchain è permanente, pubblico e verificabile da chiunque.

**3. Gestione chiavi brand-sovereign**
Le chiavi AES vengono derivate sul server del brand da `BRAND_MASTER_KEY` tramite diversificazione AES-ECB. Le chiavi non lasciano mai il server del brand, non raggiungono mai il browser del consumatore, non transitano mai sull'infrastruttura RavenTag.

## Perché Ravencoin?

[Ravencoin](https://ravencoin.org) e' un fork di Bitcoin costruito specificamente per l'emissione di asset. E' stato scelto per RavenTag perche':

- **Protocollo asset nativo**: asset root, sub-asset e token unici sono funzionalita' di primo livello. Nessun smart contract, nessun bytecode.
- **Gerarchia asset adatta ai brand**: BRAND (root) / PRODOTTO (sub-asset) / SERIALE#TAG (token unico) corrisponde esattamente all'organizzazione dei prodotti.
- **KAWPOW proof-of-work**: algoritmo ASIC-resistant che mantiene il mining decentralizzato.
- **Nessuna ICO, nessun premine**: Ravencoin e' stata lanciata in modo equo nel gennaio 2018 senza monete pre-allocate.
- **Fee prevedibili e basse**: 500 RVN per un asset root, 100 RVN per linea prodotto, 5 RVN per articolo serializzato.

La licenza RTSL-1.0 richiede che tutte le opere derivate usino esclusivamente Ravencoin per tutte le operazioni blockchain.

## Quattro livelli anti-contraffazione

| Livello | Tecnologia | Cosa deve compromettere un attaccante |
|---------|-----------|--------------------------------------|
| **1. Consenso Ravencoin** | Proprieta' asset parent verificata da ogni nodo | Deve possedere l'asset parent Ravencoin |
| **2. Binding nfc_pub_id** | `SHA-256(UID \|\| BRAND_SALT)` nei metadati IPFS | Deve conoscere `BRAND_SALT` (mai pubblico) |
| **3. Derivazione chiave AES** | `AES-ECB(BRAND_MASTER_KEY, [slot \|\| UID])` per chip | Deve conoscere `BRAND_MASTER_KEY` (non lascia mai il server) |
| **4. Silicio NTAG 424 DNA** | Hardware NXP non clonabile, chiavi write-protected | Deve clonare fisicamente il silicio NXP (impossibile) |

## Due app Android

### RavenTag Verify (app per consumatori)

Download: [RavenTag-Verify-v1.3.8.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| Funzionalita' | Dettagli |
|---|---|
| Scansione tag NFC | Tap su chip NTAG 424 DNA, risultato AUTENTICO / REVOCATO |
| Verifica completa | SUN MAC + blockchain Ravencoin + controllo revoca |
| Wallet Ravencoin | BIP44 `m/44'/175'/0'/0/0`, mnemonica BIP39 12 parole, storage AES-256-GCM |
| Multilingua | EN, IT, FR, DE, ES, ZH, JA, KO, RU |

### RavenTag Brand Manager (app per operatori e brand team)

Download: [RavenTag-Brand-v1.3.8.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| Funzionalita' | Dettagli |
|---|---|
| Emissione asset Ravencoin | Root (500 RVN), sub-asset (100 RVN), token unico (5 RVN) |
| Derivazione chiavi chip | Chiama backend `derive-chip-key`, chiavi mai generate on-device |
| Programmazione chip NTAG 424 DNA | Chiavi AES-128 + URL SUN via APDU ISO 7816-4 |
| Wallet HD | BIP44, firma UTXO locale, mnemonica BIP39 12 parole |
| Trasferimento / Revoca | Gestione ciclo di vita completo degli asset |
| Metadati prodotto | Upload JSON RTP-1 su IPFS via Pinata, CIDv0 referenziato on-chain |
| Multilingua | EN, IT, FR, DE, ES, ZH, JA, KO, RU |

## Ruoli e livelli di accesso

I ruoli vengono definiti al momento della creazione del portafoglio inserendo una chiave di controllo (admin o operatore). L'app determina il ruolo automaticamente validando la chiave sul backend. Il ruolo e' bloccato al portafoglio e non puo' essere modificato dalle impostazioni.

| Ruolo | Chiave di controllo | Permessi nell'app Android |
|---|---|---|
| **Admin** | Chiave Admin (`X-Admin-Key`) | Tutte le operazioni: emissione asset root/sub, emissione token unici, revoca/ripristino, invio RVN, trasferimento tutti i tipi di asset |
| **Operatore** | Chiave Operatore (`X-Operator-Key`) | Solo emissione token unici. Creazione asset root/sub, revoca/ripristino, invio RVN e trasferimento asset root/sub sono bloccati. |

**Caso d'uso:** Un admin brand puo' preconfigurare piu' dispositivi operatore sullo stesso portafoglio che contiene gli owner asset. Ogni dispositivo operatore puo' emettere token unici (serial) senza accesso alla gestione degli asset o al portafoglio.

## Comportamento tap NFC

**App installata (Android App Links):**
L'URL del tag viene intercettato direttamente dall'app RavenTag Verify. L'app apre la schermata Scan ed esegue la verifica completa. Il browser non viene mai aperto.

**App non installata:**
L'URL del tag apre il browser del telefono. Il browser mostra una pagina di installazione con logo RavenTag e link download.

**App aperta su un'altra schermata:**
Il foreground dispatch NFC e' attivo solo sulla scheda Scan. Sulle altre schermate il tap viene ignorato dall'app.

## Android App Links - Configurazione

Per configurare Android App Links, devi ottenere l'impronta del certificato di signing:

```bash
keytool -list -v -keystore android/signing/raventag-release.keystore -alias raventag | grep SHA256
```

**Impronta SHA-256:**
```
3E:A5:B9:F3:75:63:1A:4E:1D:E9:5D:E1:DA:9C:22:45:14:1E:4A:D8:FA:7A:63:78:7D:6A:B9:81:96:B4:A3:BE
```

Senza due punti (per il file `.env` del backend):
```
3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE
```

Configura nel backend `.env`:
```bash
ANDROID_APP_FINGERPRINT=3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE
```

## Struttura del progetto

```
RavenTag/
├── backend/        Node.js + Express + SQLite
├── frontend/       Next.js 14 + Tailwind CSS
├── android/        Kotlin + Jetpack Compose
├── docs/           protocol.md, architecture.md, legal/, README_*.md
├── pictures/       Logo assets
├── docker-compose.yml
└── LICENSE         RavenTag Source License (RTSL-1.0)
```

## Legale

- [Termini di Servizio](legal/TERMS_OF_SERVICE.md)
- [Informativa sulla Privacy](legal/PRIVACY_POLICY.md)

Entrambi i documenti vengono mostrati con checkbox di accettazione obbligatoria al primo avvio dell'app.

Per richieste legali: legal@raventag.com

## Attribuzione (RTSL-1.0)

RavenTag e' rilasciato sotto la **RavenTag Source License (RTSL-1.0)**. Consulta il [LICENSE](../LICENSE) per il testo completo.

[![Licenza](https://img.shields.io/badge/Licenza-RTSL--1.0-orange.svg)](../LICENSE)
