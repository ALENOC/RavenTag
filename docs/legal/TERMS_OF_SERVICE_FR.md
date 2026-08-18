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

## 2. Description de l'Application

RavenTag Verify est une application mobile offrant :

- **Vérification de Tags NFC** : Lecture et vérification cryptographique de puces NFC NTAG 424 DNA liées à des actifs blockchain Ravencoin via le RavenTag Protocol v1 (RTP-1).
- **Portefeuille Ravencoin Non-Custodial** : Génération, stockage local et gestion autonome d'un portefeuille HD BIP39/BIP44 non-custodial pour la blockchain Ravencoin (RVN).
- **Gestion d'Actifs** (version Brand uniquement) : Émission, transfert et gestion locale d'actifs Ravencoin.

L'Application est un outil logiciel pour interagir en auto-garde (self-custody) avec la blockchain Ravencoin et le matériel NFC. Ce n'est ni un service financier, ni une banque, ni un produit financier.

---

## 3. Conditions et Champ d'Utilisation

Vous devez avoir au moins 18 ans pour utiliser cette Application.

### 3.1 Utilisation Consommateur de l'App Verify
La fonctionnalité de vérification NFC est conçue pour tout consommateur souhaitant vérifier l'authenticité d'un produit équipé de NFC.

### 3.2 Fonctionnalité Portefeuille et Auto-garde (Self-Custody)
La fonctionnalité portefeuille Ravencoin implique l'auto-garde (self-custody), la gestion directe et le transfert d'actifs numériques sur une blockchain publique. Vous agissez sous votre propre responsabilité et risque financier.

### 3.3 Code Source et Infrastructure
La restriction d'utilisation professionnelle de la RavenTag Source License (RTSL-1.0) s'applique exclusivement aux développeurs et entités qui utilisent le code source. Les utilisateurs finaux de l'App ne sont pas concernés.

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

### 4.3 Phrase Mnémonique (Seed Phrase)
Vous devez immédiatement noter votre phrase mnémonique BIP39 de 12 mots et la conserver hors ligne en lieu sûr. **La perte de votre phrase mnémonique entraîne la perte permanente et irrécupérable de tous vos fonds.**

### 4.4 Sécurité de l'Appareil
Vous êtes responsable du maintien de la sécurité de votre appareil.

---

## 5. Risques Blockchain, Financiers et Cadre Réglementaire

### 5.1 Nature de Ravencoin et Infrastructure Réseau
- **Infrastructure du Développeur** : Le Développeur exploite le point d'accès public ElectrumX `electrumx.raventag.com` / `electrum.raventag.com`.
- **Infrastructure Indépendante de Tiers** : L'Application peut interagir avec des nœuds tiers indépendants.
- **Rôle des Nœuds Ravencoin Core** : Un nœud public Core remplit des fonctions de validation et de propagation. Il ne détient pas de fonds et ne possède pas de clés privées.

### 5.2 Risque Financier et Irréversibilité
Les transactions sur la blockchain Ravencoin sont **immutables et irréversibles**. Les frais de réseau payés aux mineurs ne sont pas remboursables.

### 5.3 Aucun Conseil Financier
Aucun contenu de cette App ne constitue un conseil financier ou d'investissement.

### 5.4 Cadre Réglementaire (MiCA)
RavenTag est fourni comme un logiciel open-source non-custodial. Le Développeur ne détient pas les clés privées des utilisateurs et n'exerce aucun contrôle ni garde sur les actifs numériques des utilisateurs. La transmission de transactions signées via des serveurs ElectrumX constitue un acheminement technique de données.

---

## 6. Matériel NFC et Résultats de Vérification

Les résultats de vérification reposent sur des contrôles cryptographiques. Un résultat positif ne constitue pas une garantie juridique absolue d'authenticité.

---

## 7. Distribution Officielle et Avertissement de Sécurité

### 7.1 Canaux Autorisés
1. **GitHub Releases** (https://github.com/ALENOC/RavenTag/releases)
2. **Google Play Store** (pour l'App Verify)

### 7.2 Vérification de Signature
Les versions officielles sont signées par le Développeur et peuvent être vérifiées avec `apksigner`.

### 7.3 Exonération de Responsabilité pour les Versions Non Officielles
Le Développeur décline toute responsabilité pour les dommages ou pertes découlant de versions non officielles ou modifiées.

---

## 8. Dépendance au Réseau

L'Application dépend du fonctionnement du réseau Ravencoin. Pour l'infrastructure gérée par le Développeur (`electrumx.raventag.com`), des mesures raisonnables de disponibilité sont prises sans garantie de temps de fonctionnement.

---

## 9. Limitation de Responsabilité

L'Application est fournie « EN L'ÉTAT » sans garantie. La responsabilité totale du Développeur est limitée à zéro euro (EUR 0), l'Application étant distribuée gratuitement.

---

## 10. Modifications de l'Application et des Conditions

Le Développeur se réserve le droit de mettre à jour l'Application et ces Conditions à tout moment.

---

## 11. Droit Applicable et Juridiction

Ces Conditions sont régies par le droit italien. Les litiges relèvent de la compétence exclusive des tribunaux italiens.

---

## 12. Divisibilité

Si une clause est jugée invalide, les autres clauses conservent leur plein effet.

---

## 13. Accord Complet

Ces Conditions et la Politique de Confidentialité constituent l'intégralité de l'accord entre l'Utilisateur et le Développeur.

---

## 14. Informations de Contact

**Alessandro Nocentini**
GitHub : https://github.com/ALENOC/RavenTag
E-mail : legal@raventag.com
