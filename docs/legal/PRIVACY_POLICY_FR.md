# RavenTag Verify - Politique de Confidentialite

**Version 1.0 - Date d'entree en vigueur : 2026-03-21**
**Copyright 2026 Alessandro Nocentini. Tous droits reserves.**

---

> **AVIS DE TRADUCTION.** Ce document est une traduction fournie a titre indicatif. La version italienne ([PRIVACY_POLICY_IT.md](PRIVACY_POLICY_IT.md)) est la seule version juridiquement contraignante. En cas de divergence, contradiction ou ambiguite entre cette traduction et le texte italien, le texte italien prevaut.

---

## 1. Introduction

Cette Politique de Confidentialite decrit comment RavenTag Verify ("App"), developpee par Alessandro Nocentini ("Developpeur", "nous"), collecte, utilise et protege les informations lorsque vous utilisez l'App.

Le Developpeur s'engage a minimiser la collecte de donnees. L'App est concue pour fonctionner avec la quantite minimale de donnees necessaires.

Cette Politique de Confidentialite est conforme a :
- Reglement General sur la Protection des Donnees de l'UE (RGPD - Reglement 2016/679)
- Code italien de protection des donnees personnelles (D.Lgs. 196/2003 tel que modifie par le D.Lgs. 101/2018)
- Politique des developpeurs Google Play

---

## 2. Responsable du Traitement

RavenTag est un protocole open-source. Chaque deploiement de l'App se connecte a un serveur backend choisi par l'entite qui a compile ou configure l'App (une marque, un fabricant ou le Developpeur a des fins de demonstration).

**Backend demo exploite par le Developpeur :**
Le Developpeur exploite une instance backend sur raventag.com exclusivement a des fins de demonstration et de test d'infrastructure. Si vous utilisez une instance de l'App connectee a ce backend demo, le responsable du traitement pour les donnees de verification cote serveur (Section 3.2) est :

**Alessandro Nocentini**
Contact : https://github.com/ALENOC/RavenTag
legal@raventag.com

Ce backend demo n'est pas destine a un usage en production par les utilisateurs finaux. Les marques doivent deployer leur propre infrastructure backend pour les deployments en production.

**Backend exploite par une marque (usage en production) :**
En production, les marques et les fabricants deployent leur propre infrastructure backend et compilent une version de l'App configuree pour se connecter a leur propre serveur. Lorsque vous utilisez une instance de l'App connectee au backend d'une marque, cette marque est le responsable independant du traitement pour toutes les donnees de verification recues par leur serveur. Le Developpeur n'a pas acces et n'assume aucune responsabilite pour les donnees traitees par des backends de marques tierces. Vous devriez vous referer a la politique de confidentialite publiee par la marque exploitant cette instance de l'App pour savoir comment elle traite vos donnees.

---

## 3. Donnees Collectees et Leur Utilisation

### 3.1 Donnees Stockees Localement sur Votre Appareil (Jamais Transmises)

Les donnees suivantes sont stockees exclusivement sur votre appareil et ne sont jamais transmises a aucun serveur exploite par le Developpeur :

| Donnee | Finalite | Stockage |
|---|---|---|
| Phrase mnemonique BIP39 (chiffree) | Recuperation portefeuille | Android Keystore (AES-256-GCM) |
| Cles privees (derivees, chiffrees) | Signature des transactions | Android Keystore (AES-256-GCM) |
| Adresse portefeuille (RVN) | Affichage et transactions | Stockage local chiffre |
| Cles admin/operateur (version Brand) | Gestion des actifs | Android Keystore (AES-256-GCM) |
| Parametres et preferences App | Configuration App | Preferences locales partagees |

**Votre phrase mnemonique et vos cles privees ne quittent jamais votre appareil.**

### 3.2 Donnees Transmises lors de la Verification de Tags NFC

Lorsque vous scannez un tag NFC, l'App envoie les donnees suivantes a un serveur backend pour effectuer la verification cryptographique :

| Donnee | Finalite |
|---|---|
| Nom de l'actif (ex. MARQUE/PRODUIT#001) | Identification de l'actif sur la blockchain |
| Compteur NFC chiffre (parametre e) | Verification SUN MAC |
| Valeur MAC NFC (parametre m) | Verification SUN MAC |
| Adresse IP de votre appareil | Limitation de frequence cote serveur et journalisation de securite |

Ces donnees sont le minimum necessaire pour verifier l'authenticite d'un tag NFC. La demande de verification n'inclut aucune information personnellement identifiable au-dela de l'adresse IP.

**Le serveur qui recoit ces donnees depend de la configuration de l'App :**

- **Backend par defaut gere par RavenTag** : si l'App se connecte au backend RavenTag exploite par le Developpeur (raventag.com), le Developpeur recoit et traite ces donnees comme decrit dans cette Politique de Confidentialite.
- **Backend gere par une marque** : si l'App a ete compilee ou configuree par une marque pour se connecter a son propre serveur, le serveur de cette marque recoit ces donnees. Le Developpeur ne recoit, n'accede ni ne traite ces donnees de quelque maniere que ce soit. La marque est le responsable independant du traitement et sa propre politique de confidentialite s'applique.

Vous pouvez identifier a quel backend l'App se connecte en verifiant l'URL du serveur affichee dans les parametres de l'App.

**Conservation (backend gere par le Developpeur)** : les adresses IP et les journaux de requetes sont conserves pendant 90 jours maximum a des fins de securite et de limitation de frequence, apres quoi ils sont automatiquement supprimes.

**Base juridique (RGPD, backend gere par le Developpeur)** : Interet legitime (Art. 6(1)(f) RGPD) - surveillance de la securite et prevention des fraudes.

### 3.3 Donnees Transmises lors des Operations Blockchain

Lorsque vous effectuez des operations de portefeuille (verification du solde, envoi de RVN, emission d'actifs), l'App communique avec les noeuds du reseau Ravencoin. Cette communication peut inclure :

| Donnee | Finalite |
|---|---|
| Votre adresse portefeuille Ravencoin | Consultation du solde et de l'historique des transactions |
| Donnees des transactions | Diffusion des transactions sur le reseau |
| Adresse IP de votre appareil | Communication reseau |

La blockchain Ravencoin est un reseau public et decentralise. Toutes les transactions diffusees sur le reseau sont permanentes et publiquement visibles par tous. N'utilisez pas ce portefeuille pour des transactions que vous souhaitez garder privees.

### 3.4 Donnees Transmises lors du Chargement d'Images d'Actifs

Lors du chargement d'images d'actifs hebergees sur IPFS, l'App se connecte a des passerelles IPFS publiques (telles que ipfs.io, cloudflare-ipfs.com). Ces services tiers peuvent enregistrer votre adresse IP conformement a leurs propres politiques de confidentialite.

### 3.5 Donnees Camera

Si vous utilisez la camera pour scanner des codes QR dans l'App, les donnees de la camera sont traitees exclusivement sur votre appareil en temps reel et ne sont jamais stockees ou transmises.

### 3.6 Donnees NFC

Les donnees du tag NFC sont lues localement sur votre appareil. Les donnees NFC brutes (UID, enregistrements NDEF) sont traitees sur l'appareil et seuls les parametres de verification derives (actif, e, m) sont transmis comme decrit a la Section 3.2.

### 3.7 Donnees que Nous Ne Collectons Pas

Nous ne collectons explicitement pas :

- Votre nom, adresse email ou toute information d'identification personnelle.
- Identifiants d'appareil (IMEI, Android ID, ID publicitaire).
- Donnees de localisation.
- Analyses d'utilisation ou telemetrie.
- Rapports de plantage (sauf soumis explicitement par vous).
- Toute donnee a des fins publicitaires.

---

## 4. Services Tiers

L'App interagit avec les services tiers suivants. Leurs politiques de confidentialite regissent leurs pratiques en matiere de donnees :

| Service | Finalite | Politique de Confidentialite |
|---|---|---|
| Noeuds du Reseau Ravencoin | Requetes blockchain et transactions | Reseau decentralise, aucune politique unique |
| Passerelles IPFS (ipfs.io, cloudflare-ipfs.com) | Chargement d'images d'actifs | Voir les fournisseurs respectifs |
| Pinata (pinata.cloud) | Epinglage de metadonnees IPFS (optionnel, version Brand uniquement) | https://pinata.cloud/privacy |
| Google Play Store | Distribution de l'App | https://policies.google.com/privacy |

Le Developpeur n'est pas responsable des pratiques en matiere de donnees de ces services tiers.

---

## 5. Securite des Donnees

Toutes les donnees sensibles stockees sur votre appareil (phrase mnemonique, cles privees, cles API) sont chiffrees avec AES-256-GCM via le systeme Android Keystore, qui utilise la securite materielle disponible.

La communication entre l'App et le serveur backend du Developpeur est chiffree avec HTTPS/TLS.

Malgre ces mesures, aucune methode de stockage ou de transmission electronique n'est sure a 100 %. Vous etes responsable du maintien de la securite de votre appareil et de votre phrase mnemonique.

---

## 6. Conservation des Donnees

- **Donnees sur l'appareil** : conservees jusqu'a la suppression du portefeuille ou la desinstallation de l'App.
- **Journaux de requetes cote serveur** : conserves pendant 90 jours maximum, puis automatiquement supprimes.
- **Donnees blockchain** : toutes les transactions diffusees sur la blockchain Ravencoin sont permanentes et publiques et ne peuvent pas etre supprimees par le Developpeur ou un tiers.

---

## 7. Vos Droits au Titre du RGPD

Si vous etes situe dans l'Espace Economique Europeen, vous disposez des droits suivants concernant vos donnees personnelles :

- **Droit d'acces** : demander une copie des donnees personnelles que nous detenons sur vous (limite aux journaux cote serveur).
- **Droit de rectification** : demander la correction de donnees inexactes.
- **Droit a l'effacement** : demander la suppression de vos donnees personnelles de nos serveurs (journaux serveur), sous reserve des obligations legales de conservation.
- **Droit a la limitation du traitement** : demander que nous limitions la facon dont nous utilisons vos donnees.
- **Droit d'opposition** : s'opposer au traitement base sur l'interet legitime.
- **Droit a la portabilite des donnees** : recevoir vos donnees dans un format structure et lisible par machine.
- **Droit de deposer une plainte** : vous avez le droit de deposer une plainte aupres de l'autorite italienne de protection des donnees (Garante per la protezione dei dati personali, https://www.garanteprivacy.it).

Pour exercer l'un de ces droits, contactez-nous a : https://github.com/ALENOC/RavenTag / legal@raventag.com

Nous repondrons a votre demande dans les 30 jours.

---

## 8. Confidentialite des Mineurs

L'App ne s'adresse pas aux mineurs de moins de 18 ans. Nous ne collectons pas sciemment de donnees personnelles aupres de mineurs de moins de 18 ans. Si vous pensez qu'un mineur de moins de 18 ans a utilise l'App et fourni des donnees personnelles, contactez-nous et nous prendrons des mesures pour supprimer ces donnees.

---

## 9. Transferts Internationaux de Donnees

**Backend demo exploite par le Developpeur** : le serveur backend demo du Developpeur est situe au sein de l'Union Europeenne. Si vous accedet a une instance demo de l'App depuis l'exterieur de l'UE, les donnees de votre demande de verification (Section 3.2) seront transferees et traitees au sein de l'UE, conformement aux exigences du RGPD.

**Backend exploite par une marque** : dans les deployments en production, la localisation geographique du serveur backend est determinee exclusivement par la marque ou le fabricant qui l'a deploye. Le Developpeur n'a aucun controle et aucune connaissance des emplacements de serveurs choisis par les marques tierces. Les regles applicables en matiere de transferts internationaux de donnees sont celles de la marque exploitant ce deploiement. Referez-vous a la politique de confidentialite de la marque pour plus de details.

---

## 10. Modifications de Cette Politique de Confidentialite

Nous pouvons mettre a jour cette Politique de Confidentialite de temps a autre. Nous vous informerons des changements importants en mettant a jour la date d'entree en vigueur en haut de ce document et, lorsque la loi l'exige, en fournissant un avis dans l'App.

Votre utilisation continue de l'App apres toute modification constitue votre acceptation de la Politique de Confidentialite mise a jour.

---

## 11. Contact

Pour toute question, demande ou plainte relative a la confidentialite :

**Alessandro Nocentini**
https://github.com/ALENOC/RavenTag
legal@raventag.com

Pour les plaintes relatives a la protection des donnees, vous pouvez egalement contacter :
**Garante per la protezione dei dati personali**
https://www.garanteprivacy.it

---

*RavenTag Verify est un projet open-source. Code source : https://github.com/ALENOC/RavenTag*
