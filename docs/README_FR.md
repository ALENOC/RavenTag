<p align="center">
  <img src="../pictures/RavenTag_Logo.jpg" width="380" alt="RavenTag"/>
</p>

# RavenTag

[![Licence](https://img.shields.io/badge/Licence-RTSL--1.0-orange.svg)](../LICENSE)
[![Protocole](https://img.shields.io/badge/Protocole-RTP--1-orange.svg)](protocol.md)
[![Attribution Requise](https://img.shields.io/badge/Attribution-Requise-green.svg)](../LICENSE)
[![Release](https://img.shields.io/github/v/release/ALENOC/RavenTag)](https://github.com/ALENOC/RavenTag/releases/latest)

**Lire dans d'autres langues:**
[🇬🇧 English](../README.md) |
[🇮🇹 Italiano](README_IT.md) |
[🇩🇪 Deutsch](README_DE.md) |
[🇪🇸 Español](README_ES.md) |
[🇨🇳 中文](README_ZH.md) |
[🇯🇵 日本語](README_JA.md) |
[🇰🇷 한국어](README_KO.md) |
[🇷🇺 Русский](README_RU.md)

---

**RavenTag est une plateforme anti-contrefacon open-source et trustless. Elle relie des puces NFC NTAG 424 DNA a des actifs sur la blockchain Ravencoin via le protocole RTP-1, permettant aux marques de prouver l'authenticite de leurs produits physiques sans dependre d'aucune autorite centrale.**

## Protocole RTP-1

RTP-1 (RavenTag Protocol v1) definit le schema JSON stocke sur IPFS et reference par les metadonnees des actifs Ravencoin.

### Schema des metadonnees (v1.1)

```json
{
  "raventag_version": "RTP-1",
  "parent_asset": "FASHIONX",
  "sub_asset": "FASHIONX/SAC001",
  "variant_asset": "SN0001",
  "nfc_pub_id": "<sha256hex>",
  "crypto_type": "ntag424_sun",
  "algo": "aes-128",
  "image": "ipfs://<cid>",
  "description": "<description du produit>"
}
```

**Description des champs:**
- `raventag_version`: Version du protocole (toujours "RTP-1")
- `parent_asset`: Nom de l'actif racine (ex: "FASHIONX")
- `sub_asset`: Chemin complet du sous-actif (ex: "FASHIONX/SAC001")
- `variant_asset`: Serial du token unique (ex: "SN0001") - present uniquement pour les tokens uniques
- `nfc_pub_id`: SHA-256(UID || BRAND_SALT) - empreinte publique de la puce
- `crypto_type`: Type crypto NFC (toujours "ntag424_sun" pour NTAG 424 DNA)
- `algo`: Algorithme de chiffrement (toujours "aes-128")
- `image`: URI IPFS de l'image du produit (optionnel)
- `description`: Description du produit (optionnel)

## Qu'est-ce que RavenTag?

La contrefacon coute des milliards aux marques et aux consommateurs chaque annee. RavenTag resout ce probleme en combinant trois technologies:

**1. Puce NFC securisee materiellement (NTAG 424 DNA)**
Chaque produit authentifie porte une puce NXP NTAG 424 DNA. A chaque lecture, la puce genere une URL unique et signee cryptographiquement via AES-128-CMAC (SUN: Secure Unique NFC). La cle AES reside dans le silicium de la puce et ne peut pas etre extraite ou clonee.

**2. Blockchain Ravencoin**
La marque enregistre chaque article serialise comme un token unique on-chain, par exemple `FASHIONX/SAC001#SN0001`. Les metadonnees du token (sur IPFS) contiennent l'empreinte publique de la puce: `nfc_pub_id = SHA-256(chip_uid || BRAND_SALT)`.

**3. Gestion des cles souveraine**
Les cles AES sont derivees sur le serveur de la marque a partir de `BRAND_MASTER_KEY`. Les cles ne quittent jamais le serveur de la marque.

## Pourquoi Ravencoin?

[Ravencoin](https://ravencoin.org) est un fork de Bitcoin concu specifiquement pour l'emission d'actifs:

- **Protocole d'actifs natif**: actifs root, sous-actifs et tokens uniques sont des fonctionnalites de premier niveau. Pas de smart contracts.
- **Hierarchie d'actifs adaptee aux marques**: MARQUE (root) / PRODUIT (sous-actif) / SERIE#TAG (token unique).
- **KAWPOW proof-of-work**: algorithme resistant aux ASIC.
- **Aucune ICO, aucun premine**: lancement equitable en janvier 2018.
- **Frais previsibles et bas**: 500 RVN par actif root, 100 RVN par gamme, 5 RVN par article.

La licence RTSL-1.0 exige que toutes les oeuvres derivees utilisent exclusivement Ravencoin pour toutes les operations blockchain.

## Quatre niveaux de protection

| Niveau | Technologie | Ce dont un attaquant a besoin |
|--------|-------------|-------------------------------|
| **1. Consensus Ravencoin** | Propriete de l'actif parent verifiee par chaque noeud | Doit posseder l'actif parent Ravencoin |
| **2. Liaison nfc_pub_id** | `SHA-256(UID \|\| BRAND_SALT)` dans les metadonnees IPFS | Doit connaitre `BRAND_SALT` (jamais public) |
| **3. Derivation de cle AES** | `AES-ECB(BRAND_MASTER_KEY, [slot \|\| UID])` par puce | Doit connaitre `BRAND_MASTER_KEY` |
| **4. Silicium NTAG 424 DNA** | Hardware NXP non clonable | Doit cloner physiquement le silicium NXP (impossible) |

## Deux applications Android

### RavenTag Verify (application consommateur)

Telechargement: [RavenTag-Verify-v1.3.8.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| Fonctionnalite | Details |
|---|---|
| Scan de tags NFC | Tap sur puce NTAG 424 DNA, resultat AUTHENTIQUE / REVOQUE |
| Verification complete | SUN MAC + blockchain Ravencoin + verification de revocation |
| Portefeuille Ravencoin | BIP44 `m/44'/175'/0'/0/0`, mnemonique BIP39 12 mots |
| Multilingue | EN, IT, FR, DE, ES, ZH, JA, KO, RU |

### RavenTag Brand Manager (application operateurs)

Telechargement: [RavenTag-Brand-v1.3.8.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| Fonctionnalite | Details |
|---|---|
| Emission d'actifs Ravencoin | Root (500 RVN), sous-actif (100 RVN), token unique (5 RVN) |
| Programmation puces NTAG 424 DNA | Cles AES-128 + URL SUN via APDU ISO 7816-4 |
| Portefeuille HD | BIP44, signature UTXO locale, mnemonique BIP39 12 mots |
| Transfert / Revocation | Gestion complete du cycle de vie des actifs |
| Metadonnees produit | Upload JSON RTP-1 sur IPFS via Pinata, hash CIDv0 reference on-chain |

## Roles et niveaux d'acces

Les roles sont definis au moment de la creation du portefeuille en saisissant une cle de controle (admin ou operateur). L'application determine le role automatiquement en validant la cle sur le backend. Le role est lie au portefeuille et ne peut pas etre modifie dans les parametres.

| Role | Cle de controle | Permissions dans l'app Android |
|---|---|---|
| **Admin** | Cle Admin (`X-Admin-Key`) | Toutes les operations: emission actifs root/sub, emission jetons uniques, revocation/restauration, envoi RVN, transfert tous types d'actifs |
| **Operateur** | Cle Operateur (`X-Operator-Key`) | Emission de jetons uniques uniquement. Creation d'actifs root/sub, revocation/restauration, envoi RVN et transfert d'actifs root/sub sont bloques. |

**Cas d'usage:** Un admin peut preconfigurer plusieurs appareils operateurs sur le meme portefeuille contenant les actifs proprietaires. Chaque appareil operateur peut emettre des jetons uniques sans acces a la gestion des actifs ou au portefeuille.

## Comportement du tap NFC

**App installee:** L'URL du tag est intercepte directement par RavenTag Verify. L'app ouvre l'ecran Scan et effectue la verification complete.

**App non installee:** L'URL ouvre le navigateur qui affiche une page d'installation avec le logo et un lien de telechargement.

## Structure du projet

```
RavenTag/
├── backend/        Node.js + Express + SQLite
├── frontend/       Next.js 14 + Tailwind CSS
├── android/        Kotlin + Jetpack Compose
├── docs/           protocol.md, architecture.md, legal/, README_*.md
├── pictures/       Assets du logo
├── docker-compose.yml
└── LICENSE         RavenTag Source License (RTSL-1.0)
```

## Mentions legales

- [Conditions d'utilisation](legal/TERMS_OF_SERVICE.md)
- [Politique de confidentialite](legal/PRIVACY_POLICY.md)

Pour toute demande juridique: legal@raventag.com

## Attribution (RTSL-1.0)

RavenTag est publie sous la **RavenTag Source License (RTSL-1.0)**. Voir [LICENSE](../LICENSE) pour le texte complet.

[![Licence](https://img.shields.io/badge/Licence-RTSL--1.0-orange.svg)](../LICENSE)
