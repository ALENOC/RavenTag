# RavenTag Verify - Conditions d'Utilisation

**Version 1.1 - Date d'entrée en vigueur : 18 août 2026**
**Copyright 2026-présent Alessandro Nocentini. Tous droits réservés.**

---

> **VERSION OFFICIELLE.** Ce document en langue italienne constitue la version juridiquement contraignante des Conditions d'Utilisation. En cas de divergence, contradiction ou ambiguïté entre cette version et une traduction, la version italienne prévaut.

---

## 1. Acceptation des Conditions

Lors du premier lancement, l'Application présente ces Conditions d'Utilisation et la Politique de Confidentialité. Vous devez accepter explicitement les deux documents en cochant les cases correspondantes avant de pouvoir continuer.

En téléchargeant, installant ou continuant à utiliser l'Application, vous (« Utilisateur ») confirmez être lié par ces Conditions.

Ces Conditions constituent un contrat juridiquement contraignant entre vous et Alessandro Nocentini (« Développeur »), auteur de RavenTag Verify.

---

## 2. Description de l'Application et Nature de la Licence

RavenTag Verify est une application mobile offrant :

- **Vérification de Tags NFC** : Lecture et vérification cryptographique de puces NFC NTAG 424 DNA liées à des actifs blockchain Ravencoin via le RavenTag Protocol v1 (RTP-1).
- **Portefeuille Ravencoin Non-Custodial** : Génération, stockage local et gestion autonome d'un portefeuille HD BIP39/BIP44 non-custodial pour la blockchain Ravencoin (RVN).
- **Gestion d'Actifs** (version Brand uniquement) : Émission, transfert et gestion locale d'actifs Ravencoin.

L'Application est un outil logiciel pour interagir en auto-garde (self-custody) avec la blockchain Ravencoin et le matériel NFC. Ce n'est ni un service financier, ni une banque, ni un produit financier.

L'Application et son code source sont distribués sous la **RavenTag Source License (RTSL-1.0)**, une licence de logiciel à code source disponible (source-available software) qui restreint certains usages commerciaux. RavenTag ne constitue pas un logiciel open-source selon les définitions de l'OSI.

---

## 3. Conditions et Champ d'Utilisation

Vous devez avoir au moins 18 ans pour utiliser cette Application.

### 3.1 Utilisation Consommateur de l'App Verify
La fonctionnalité de vérification NFC est conçue pour tout consommateur souhaitant vérifier l'authenticité d'un produit équipé de NFC.

### 3.2 Fonctionnalité Portefeuille et Auto-garde (Self-Custody)
La fonctionnalité portefeuille Ravencoin implique l'auto-garde (self-custody), la gestion directe et le transfert d'actifs numériques sur une blockchain publique. Vous agissez sous votre propre responsabilité et risque financier.

### 3.3 Code Source et Licence RTSL-1.0
La restriction d'utilisation commerciale de la licence RTSL-1.0 s'applique exclusivement aux développeurs et entités qui utilisent le code source. Les utilisateurs finaux de l'App ne sont pas concernés.

---

## 4. Portefeuille Non-Custodial et Architecture des Transactions

### 4.1 Aucune Garde par le Développeur
RavenTag Verify fournit un portefeuille exclusivement non-custodial. Cela signifie que :
- Le Développeur ne détient, ne stocke, ne gère ni ne contrôle **jamais** vos clés privées, phrases mnémoniques ou fonds.
- Vous êtes le seul gardien (Self-Custodian) de vos clés cryptographiques et actifs numériques.
- Le Développeur ne peut en aucun cas autoriser de transactions ou restaurer votre portefeuille.

### 4.2 Création, Signature et Transmission des Transactions
Pour chaque transaction effectuée depuis l'Application :
1. L'Utilisateur initie la transaction dans l'interface de l'Application ;
2. L'Application construit la transaction brute localement sur l'appareil ;
3. La transaction est signée localement sur l'appareil avec les clés de l'Utilisateur ;
4. L'Application envoie la transaction **déjà signée** à l'infrastructure ElectrumX ;
5. ElectrumX retransmet la transaction signée au réseau Ravencoin Core.

L'infrastructure ElectrumX ne possède pas les clés privées de l'Utilisateur, ne peut pas créer de signatures valides et ne détient aucun compte custodial.

### 4.3 Phrase Mnémonique (Seed Phrase) et Responsabilité de l'Utilisateur
Vous devez immédiatement noter votre phrase mnémonique BIP39 de 12 mots et la conserver hors ligne en lieu sûr. **La perte de votre phrase mnémonique entraîne la perte permanente et irrécupérable de tous vos fonds.**

### 4.4 Sécurité de l'Appareil
Vous êtes responsable du maintien de la sécurité de votre appareil. Le Développeur n'est pas responsable des dommages résultant de logiciels malveillants ou de systèmes d'exploitation modifiés.

---

## 5. Risques Blockchain, Financiers et Cadre Réglementaire

### 5.1 Nature de Ravencoin et Infrastructure Réseau
- **Infrastructure du Développeur** : Le Développeur exploite le point d'accès public ElectrumX `electrumx.raventag.com` / `electrum.raventag.com`. Celle-ci ne constitue pas une infrastructure tierce.
- **Infrastructure Indépendante de Tiers** : L'Application peut interagir avec des nœuds tiers indépendants.
- **Rôle des Nœuds Ravencoin Core** : Un nœud public Core remplit des fonctions de validation et de propagation. Il ne détient pas de fonds et ne possède pas de clés privées.

### 5.2 Risque Financier, Volatilité et Irréversibilité
Les transactions sur la blockchain Ravencoin sont **immutables et irréversibles**. Les frais de réseau payés aux mineurs ne sont pas remboursables.

### 5.3 Aucun Conseil Financier
Aucun contenu de cette App ne constitue un conseil financier, d'investissement, juridique ou fiscal.

### 5.4 Cadre Réglementaire (MiCA)
RavenTag est fourni comme un logiciel non-custodial à code source disponible (source-available). Le Développeur ne détient pas les clés privées des utilisateurs et n'exerce aucun contrôle ni garde sur les actifs numériques des utilisateurs.

---

## 6. Matériel NFC et Métadonnées IPFS Tiers

Les résultats de vérification reposent sur des contrôles cryptographiques. Les contenus et images hébergés sur des passerelles IPFS tierces sont créés par des entités indépendantes.

---

## 7. Distribution Officielle et Avertissement de Sécurité

### 7.1 Canaux Autorisés
1. **GitHub Releases** (https://github.com/ALENOC/RavenTag/releases)
2. **Google Play Store** (pour l'App Verify)

### 7.2 Exonération de Responsabilité pour les Versions Non Officielles
Le Développeur décline toute responsabilité pour les dommages ou pertes découlant de versions non officielles ou modifiées.

---

## 8. Dépendance au Réseau et Disponibilité de l'Infrastructure

Pour l'infrastructure gérée par le Développeur (`electrumx.raventag.com`), des mesures raisonnables sont prises sans garantie de temps de fonctionnement ni obligation de maintenance perpétuelle.

---

## 9. Limitation Générale de Responsabilité et Clause de Sauvegarde

### 9.1 Exclusion des Dommages Indirects
Dans la mesure maximale permise par la loi applicable, le Développeur ne sera pas responsable des dommages indirects, consécutifs ou de la perte de profits.

### 9.2 Plafond de Responsabilité
L'Application étant fournie à titre gratuit :
- Pour les utilisateurs professionnels : la responsabilité totale du Développeur est limitée à zéro euro (EUR 0) dans la mesure permise par la loi.
- Pour les consommateurs : la responsabilité est limitée au minimum obligatoire prévu par la loi applicable.

### 9.3 Clause de Sauvegarde de Droit Impératif
Rien dans ces Conditions n'exclut ni ne limite la responsabilité du Développeur en cas de dol ou de faute lourde (Art. 1229 du Code civil italien) ou toute responsabilité qui ne peut être légalement exclue en vertu des règles impératives de protection des consommateurs.

---

## 10. Aucun Rapport Fiduciaire ni Obligation de Surveillance

L'utilisation de l'App ne crée aucun rapport fiduciaire. Le Développeur n'a pas l'obligation de surveiller les transactions de l'Utilisateur.

---

## 11. Modifications de l'Application et des Conditions

Le Développeur se réserve le droit de mettre à jour l'Application et ces Conditions pour des motifs justifiés.

---

## 12. Droit Applicable et Juridiction

Ces Conditions sont régies par le droit italien. Les règles impératives de consommation du Règlement (CE) 593/2008 (Rome I) restent réservées.

---

## 13. Divisibilité et Non-Renonciation

Si une clause est jugée invalide, les autres clauses conservent leur plein effet.

---

## 14. Accord Complet

Ces Conditions et la Politique de Confidentialité constituent l'intégralité de l'accord entre l'Utilisateur et le Développeur.

---

## 15. Informations de Contact

**Alessandro Nocentini**
GitHub : https://github.com/ALENOC/RavenTag
E-mail : legal@raventag.com
