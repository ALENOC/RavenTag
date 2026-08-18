# RavenTag Verify - Politique de Confidentialité

**Version 1.2 - Date d’entrée en vigueur : 18 août 2026**  
**Copyright 2026-present Alessandro Nocentini. Tous droits réservés.**

---

> **VERSION OFFICIELLE.** La version italienne est la version juridiquement contraignante de la présente Politique. En cas de divergence ou d’ambiguïté, la version italienne prévaut, sous réserve des droits impératifs applicables.

## 1. Introduction
La présente Politique décrit la manière dont RavenTag Verify (« Application »), développée par Alessandro Nocentini (« Développeur »), traite des informations. L’Application est conçue comme un logiciel non-custodial dont le code source est publiquement disponible sous RavenTag Source License (RTSL-1.0). Son architecture vise à minimiser les données techniques et réseau traitées.

La Politique est rédigée au regard du Règlement (UE) 2016/679 (RGPD), du droit italien de la protection des données et des autres règles applicables. Elle ne constitue pas une certification générale de conformité.

## 2. Responsable et infrastructure
Pour les systèmes exploités par le Développeur, lorsque cela est applicable, le responsable du traitement est :

**Alessandro Nocentini**  
GitHub : https://github.com/ALENOC/RavenTag  
E-mail : legal@raventag.com

Le Développeur peut exploiter un backend de démonstration sur `raventag.com` / `api.raventag.com` et un point d’accès ElectrumX public (`electrumx.raventag.com` / `electrum.raventag.com`). Cette infrastructure exploitée par le Développeur n’est pas un service tiers.

Des marques et fabricants peuvent exploiter leurs propres backends. L’Application peut également utiliser des nœuds ElectrumX/Ravencoin Core, des passerelles IPFS ou d’autres services indépendants. Le Développeur ne contrôle pas leurs pratiques de journalisation, conservation, sécurité ou confidentialité.

## 3. Données traitées et architecture technique
### 3.1 Données locales du portefeuille
La seed phrase, les clés privées et autres identifiants sensibles du portefeuille sont destinés à être générés ou stockés localement. Les clés privées et la seed phrase ne sont pas transmises à ElectrumX durant le fonctionnement normal du portefeuille.

### 3.2 Vérification NFC et backend API
Lors d’une vérification NFC peuvent être transmis le nom de l’actif, un compteur NFC chiffré/paramètre de vérification, le MAC NFC et l’adresse IP.

Le logger du backend enregistre **la méthode HTTP, le chemin, le code de statut, la durée et l’adresse IP** ; il n’enregistre pas le corps des requêtes ou réponses. Ces métadonnées peuvent servir à la sécurité, la prévention des abus, le rate limiting, le diagnostic technique et les métriques opérationnelles agrégées.

**Conservation vérifiée dans le code :** les enregistrements persistés dans `request_logs` et `rate_limit_events` sont automatiquement supprimés lorsqu’ils ont plus de 30 jours.

Cette routine **ne régit pas** d’éventuels journaux console/stdout, conteneur, système d’exploitation, reverse proxy, CDN, hébergeur ou processus ElectrumX. Leur conservation dépend de la configuration réelle de production et aucune durée fixe non vérifiée n’est affirmée.

Lorsque cela est applicable, le traitement des métadonnées techniques par l’infrastructure du Développeur peut reposer sur l’intérêt légitime au titre de l’art. 6(1)(f) RGPD pour la sécurité, la prévention des abus, le rate limiting, le diagnostic et une supervision opérationnelle proportionnée.

### 3.3 Blockchain et ElectrumX
Selon les requêtes, ElectrumX peut observer ou recevoir l’IP source, les horodatages, métadonnées de connexion, requêtes JSON-RPC/script-hash, requêtes de solde/historique/UTXO, identifiants de transaction et transactions brutes déjà signées. Les modèles de requêtes peuvent permettre une corrélation avec l’activité visible sur la blockchain publique.

La transaction est initiée par l’utilisateur, construite par l’App et signée sur l’appareil avec des clés contrôlées par l’utilisateur. ElectrumX peut recevoir la transaction déjà signée et la relayer au réseau Ravencoin. Il ne possède pas la clé privée, ne choisit pas de manière autonome le destinataire ou le montant et ne tient pas de compte custodial pour l’utilisateur.

### 3.4 Ravencoin Core, IPFS, caméra et NFC
Les nœuds Ravencoin Core assurent synchronisation, validation et propagation P2P ; cela n’implique pas la possession des clés privées des utilisateurs. Les opérateurs IPFS et autres services externes peuvent traiter l’IP et des métadonnées réseau selon leurs propres pratiques. La lecture QR par caméra est effectuée sur l’appareil et la lecture NFC est locale ; seuls les paramètres nécessaires à la vérification sont envoyés au backend.

## 4. Données non demandées intentionnellement
L’utilisation normale ne nécessite pas de nom, document d’identité, adresse postale, IMEI, Android Advertising ID ou géolocalisation précise. Les adresses IP et autres métadonnées réseau peuvent néanmoins constituer des données personnelles.

## 5. Sécurité et architecture non-custodial
RavenTag utilise des protections locales et des canaux chiffrés lorsque l’implémentation le prévoit. Aucune mesure ne garantit une sécurité absolue. Dans l’architecture non-custodial, le Développeur ne possède normalement pas les clés nécessaires pour récupérer ou transférer les fonds de l’utilisateur.

## 6. Conservation
- Données locales du portefeuille : jusqu’à leur suppression selon le fonctionnement appareil/App.
- `request_logs` et `rate_limit_events` : suppression automatique des enregistrements de plus de 30 jours.
- Logs runtime/console, proxy, système, CDN, hébergement ou ElectrumX : selon la configuration réelle.
- Blockchain Ravencoin : les données publiques répliquées ne peuvent être supprimées unilatéralement par le Développeur.

## 7. Finalités et principes RGPD
Les métadonnées techniques peuvent être traitées, lorsque nécessaire et proportionné, pour la sécurité, la prévention des abus/attaques, le rate limiting, le diagnostic ainsi que des statistiques et métriques opérationnelles agrégées, dans le respect notamment de la minimisation, limitation des finalités, limitation de conservation, intégrité et confidentialité.

## 8. Droits
Lorsque le RGPD s’applique au traitement du Développeur, la personne concernée peut exercer, sous les conditions légales, notamment les droits d’accès, rectification, effacement, limitation et opposition ainsi que tout autre droit applicable. Contact : legal@raventag.com. Le droit de réclamation auprès de l’autorité de contrôle compétente demeure. Ces droits concernent les données sous contrôle du Développeur et ne lui donnent pas le pouvoir de supprimer unilatéralement des données déjà répliquées sur la blockchain publique.

## 9. Mineurs
L’App ne s’adresse pas aux personnes de moins de 18 ans.

## 10. Transferts internationaux
La localisation des systèmes et prestataires peut varier. Si des données personnelles traitées par le Développeur sont transférées hors EEE, le transfert est soumis au **Chapitre V RGPD** et doit reposer sur le mécanisme applicable, par exemple une décision d’adéquation pertinente ou des garanties appropriées au titre de l’art. 46 RGPD lorsqu’elles sont requises.

La seule localisation physique d’un serveur aux États-Unis ou dans un autre pays tiers n’est pas considérée comme preuve d’un mécanisme de transfert valide. Des informations sur le mécanisme réellement utilisé peuvent être demandées à legal@raventag.com.

## 11. Nature non-custodial et rôle technique
RavenTag est un logiciel non-custodial. Les clés privées restent sous le contrôle de l’utilisateur. L’infrastructure ElectrumX du Développeur est conçue pour les requêtes techniques blockchain et le relais de transactions déjà signées au moyen de clés contrôlées par l’utilisateur.

Cette description **n’est pas une déclaration générale d’exemption, d’autorisation ou de classification réglementaire au titre du Règlement (UE) 2023/1114 (MiCA)**.

## 12. Modifications et contact
La Politique peut être mise à jour lorsque l’App, l’infrastructure, les traitements ou le cadre juridique changent.

**Alessandro Nocentini**  
GitHub : https://github.com/ALENOC/RavenTag  
E-mail : legal@raventag.com
