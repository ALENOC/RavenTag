# RavenTag Verify - Politique de Confidentialité

**Version 1.1 - Date d'entrée en vigueur : 18 août 2026**
**Copyright 2026-présent Alessandro Nocentini. Tous droits réservés.**

---

> **VERSION OFFICIELLE.** Ce document en langue italienne constitue la version juridiquement contraignante de la Politique de Confidentialité. En cas de divergence, contradiction ou ambiguïté entre cette version et une traduction, la version italienne prévaut.

---

## 1. Introduction

Cette Politique de Confidentialité décrit comment RavenTag Verify (« Application »), développée par Alessandro Nocentini (« Développeur », « nous »), collecte, utilise et protège les informations lorsque vous utilisez l'Application.

Le Développeur s'engage à la minimisation des données. L'Application est conçue comme un logiciel non-custodial (sans garde) et fonctionne avec la quantité minimale de données techniques et réseau strictement nécessaires à son fonctionnement.

Cette Politique de Confidentialité est conforme au :
- Règlement Général sur la Protection des Données de l'UE (RGPD - Règlement UE 2016/679)
- Code italien de protection des données personnelles (D.Lgs. 196/2003 modifié par D.Lgs. 101/2018)
- Règlement des développeurs Google Play

---

## 2. Responsable du Traitement et Catégories d'Infrastructure

RavenTag est un protocole open-source. L'Application peut interagir avec des infrastructures gérées directement par le Développeur, ainsi qu'avec des infrastructures indépendantes de tiers ou de marques.

### 2.1 Backend de démonstration géré par le Développeur
Le Développeur exploite une instance backend sur `raventag.com` (ex. `api.raventag.com`) à des fins de démonstration, de test d'infrastructure et de vérification. Si vous utilisez une instance connectée à ce backend de démonstration, le responsable du traitement pour les données de vérification et journaux serveur (Section 3.2) est :

**Alessandro Nocentini**
Contact : https://github.com/ALENOC/RavenTag
E-mail : legal@raventag.com

### 2.2 Infrastructure ElectrumX gérée par le Développeur
Le Développeur exploite un point d'accès public ElectrumX (ex. `electrumx.raventag.com` / `electrum.raventag.com`) placé en amont d'un nœud Ravencoin Core dédié. Lorsque l'Application se connecte à ce point d'accès pour interroger la blockchain ou relayer des transactions, le traitement des métadonnées de connexion est géré par le Développeur au titre de la présente Politique. Cette infrastructure ne constitue pas un service tiers.

### 2.3 Backend géré par une marque (utilisation en production)
En production, les marques et fabricants déploient leur propre infrastructure backend. Lorsque vous utilisez une Application connectée au backend d'une marque, cette marque est le responsable indépendant du traitement. Le Développeur n'a aucun accès ni responsabilité sur les données traitées par les serveurs tiers des marques.

### 2.4 Infrastructure blockchain indépendante de tiers
L'Application peut également se connecter à des nœuds publics ElectrumX ou des nœuds Ravencoin Core indépendants gérés par des tiers. Ces nœuds échappent totalement au contrôle du Développeur.

---

## 3. Données Traitées et Architecture Technique

### 3.1 Données Stockées Localement sur Votre Appareil (Jamais Transmises au Développeur ni à ElectrumX)

Les données sensibles suivantes sont générées et stockées exclusivement sur votre appareil sous forme chiffrée et ne sont jamais transmises au Développeur ni aux serveurs ElectrumX :

| Donnée | Objectif | Stockage |
|---|---|---|
| Phrase Mnémonique BIP39 (seed phrase) | Génération et récupération du portefeuille | Android Keystore (AES-256-GCM) |
| Clés Privées (dérivées, chiffrées) | Signature locale des transactions | Android Keystore (AES-256-GCM) |
| Adresse de Portefeuille (RVN) | Affichage et calcul local | Stockage local chiffré |
| Clés d'Admin/Opérateur (version Brand) | Gestion locale des actifs | Android Keystore (AES-256-GCM) |
| Paramètres et Préférences | Configuration locale | Préférences locales protégées |

**Votre phrase mnémonique et vos clés privées ne quittent jamais votre appareil.**

### 3.2 Données Transmises lors de la Vérification de Tag NFC (Backend API)

Lorsque vous scannez un tag NFC pour vérifier l'authenticité d'un produit, l'Application envoie les paramètres suivants au backend API :

| Donnée | Objectif |
|---|---|
| Nom de l'Actif (ex. BRAND/PRODUCT#001) | Identification de l'actif sur la blockchain Ravencoin |
| Compteur NFC Chiffré (paramètre e) | Vérification cryptographique SUN MAC |
| Valeur MAC NFC (paramètre m) | Vérification cryptographique SUN MAC |
| Adresse IP de votre Appareil | Limitation de fréquence (rate limiting) et sécurité réseau |

**Conservation des Journaux Backend du Développeur** : Les adresses IP et journaux réseau du backend API sont conservés pendant une durée maximale de 30 jours (vérifiée au niveau du code dans le middleware de nettoyage des journaux), puis automatiquement supprimés.

**Base Légale (RGPD)** : Intérêt légitime (Art. 6(1)(f) RGPD) pour garantir la sécurité des infrastructures et prévenir les abus.

### 3.3 Données Traitées Lors des Opérations Blockchain et ElectrumX

Lors des demandes de solde, d'historique ou d'envoi de transactions, l'Application communique avec l'infrastructure ElectrumX.

**A. Ce qu'un serveur ElectrumX peut observer ou recevoir :**
Un serveur public ElectrumX peut observer des données et métadonnées de connexion :
- Adresse IP source de l'appareil ;
- Métadonnées de connexion TLS, horodatages et fréquence des requêtes ;
- Requêtes protocole JSON-RPC et recherches script-hash ;
- Demandes de solde, d'historique de transactions et UTXOs ;
- Identifiants de transaction (TxID) et métadonnées d'actifs ;
- Transactions brutes déjà signées (raw signed transactions) soumises pour diffusion.

En raison des modèles de requête, ces informations peuvent techniquement permettre des corrélations entre identifiants réseau (comme l'adresse IP) et l'activité sur la blockchain publique.

> **Déclaration Explicite de Sécurité :**
> Les clés privées et les phrases mnémoniques ne sont pas requises par le serveur ElectrumX et ne lui sont jamais transmises lors du fonctionnement normal du portefeuille.

**B. Procédure de création et signature des transactions :**
Pour chaque transaction effectuée par le portefeuille :
1. L'utilisateur initie la transaction depuis l'interface de l'Application ;
2. L'Application construit la transaction brute localement sur l'appareil ;
3. La transaction est signée cryptographiquement sur l'appareil avec les clés privées de l'utilisateur ;
4. L'Application envoie la transaction déjà signée au serveur ElectrumX ;
5. ElectrumX retransmet (relay/broadcast) la transaction signée aux nœuds Ravencoin Core pour inclusion dans les blocs.

L'infrastructure ElectrumX ne possède pas la clé privée de l'utilisateur, ne peut pas créer de signature valide, ne décide pas des destinataires/montants et ne détient aucun compte custodial.

**C. Rôle des nœuds publics Ravencoin Core :**
Un nœud public Ravencoin Core remplit exclusivement des fonctions d'infrastructure (synchronisation de la blockchain, validation de blocs et transactions, propagation P2P). Il ne détient pas de fonds clients et ne possède pas de clés privées.

### 3.4 Chargement d'Images d'Actifs (Passerelles IPFS)
Pour afficher des images hébergées sur IPFS, l'Application peut se connecter à des passerelles IPFS publiques (ex. ipfs.io, cloudflare-ipfs.com).

### 3.5 Données Caméra et NFC
- **Caméra** : Utilisée exclusivement sur l'appareil pour la lecture de codes QR en temps réel.
- **NFC** : La lecture est effectuée localement ; seuls les paramètres de vérification dérivés (asset, e, m) sont transmis.

### 3.6 Données que Nous ne Collectons Pas
Le Développeur ne collecte aucun nom, adresse e-mail, identifiant matériel (IMEI), donnée de géolocalisation ou télémétrie commerciale.

---

## 4. Services et Nœuds Tiers

| Service / Nœud | Objectif | Notes de Confidentialité |
|---|---|---|
| Nœuds ElectrumX indépendants de tiers | Requêtes blockchain & secours | Le Développeur ne contrôle pas leurs journaux. Les opérateurs tiers peuvent voir l'adresse IP et les transactions brutes. |
| Nœuds réseau Ravencoin Core indépendants | Validation P2P & propagation | Réseau décentralisé distribué. |
| Passerelles IPFS publiques | Chargement médias & métadonnées | Gérées par des tiers. |
| Google Play Store | Distribution de l'Application | Politiques de confidentialité de Google LLC. |

---

## 5. Sécurité des Données et Architecture Non-Custodiale

Les données sensibles sur l'appareil sont protégées par un chiffrement AES-256-GCM via Android Keystore. Les connexions réseau avec le Développeur utilisent des canaux HTTPS/TLS chiffrés.

---

## 6. Conservation des Données (Limitation de la Conservation)

- **Données de l'Appareil** : Conservées jusqu'à suppression du portefeuille ou désinstallation de l'App.
- **Journaux Backend du Développeur** : Conservés pendant 30 jours maximum (conformément au code de nettoyage automatique du backend), puis définitivement supprimés.
- **Journaux ElectrumX du Développeur** : Conservés le temps strictement nécessaire au diagnostic et à la sécurité.
- **Données sur la Blockchain Publique Ravencoin** : Les transactions confirmées sur la blockchain sont définitivement publiques et inaltérables.

---

## 7. Traitement de l'Adresse IP et Principes du RGPD

Les adresses IP et métadonnées réseau sont traitées selon les principes de minimisation des données, limitation des finalités, limitation de la conservation (30 jours) et intégrité. La base légale est l'intérêt légitime (Art. 6(1)(f) RGPD).

---

## 8. Vos Droits au Titre du RGPD

Si vous résidez dans l'Espace Économique Européen, vous disposez d'un droit d'accès (Art. 15), de rectification (Art. 16), d'effacement (Art. 17), de limitation (Art. 18) et d'opposition (Art. 21 RGPD) sur les journaux serveur du Développeur. Contact : legal@raventag.com

Vous avez le droit d'introduire une réclamation auprès d'une autorité de protection des données.

---

## 9. Confidentialité des Mineurs

L'Application ne s'adresse pas aux personnes de moins de 18 ans.

---

## 10. Transferts Internationaux de Données

L'infrastructure du Développeur est située dans des centres de données sécurisés au sein de l'UE ou des États-Unis conformément au RGPD.

---

## 11. Cadre Réglementaire et Terminologie MiCA

RavenTag est conçu et distribué comme un logiciel open-source non-custodial (sans garde). Le Développeur ne détient pas les clés privées des utilisateurs, n'exerce aucun contrôle ni garde sur les crypto-actifs (RVN ou tokens) des utilisateurs, et ne fournit pas de services de conservation ou d'administration de crypto-actifs pour le compte de tiers au sens du Règlement (UE) 2023/1114 (MiCA). L'activité de l'infrastructure ElectrumX du Développeur consiste en un acheminement technique de données réseau et de transactions signées.

---

## 12. Modifications de cette Politique

Le Développeur se réserve le droit de mettre à jour cette Politique de Confidentialité.

---

## 13. Informations de Contact

**Alessandro Nocentini**
GitHub : https://github.com/ALENOC/RavenTag
E-mail : legal@raventag.com
