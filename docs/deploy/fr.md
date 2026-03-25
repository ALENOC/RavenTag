# Guide de Déploiement du Backend RavenTag

Ce guide couvre le déploiement complet en production du **Backend RavenTag uniquement**.

**Remarque :** Les applications Android RavenTag (Verify et Brand Manager) se connectent directement à votre API backend. Aucun déploiement frontend n'est requis.

---

## Vue d'Ensemble de l'Architecture

```
Applications Android (Verify + Brand Manager)
              ↓
    Votre API Backend (ce guide)
              ↓
    Réseau Ravencoin + IPFS
```

---

## Prérequis

- Un VPS Linux avec IP publique statique (minimum 1 GB RAM, 10 GB disque, Ubuntu 24.04 LTS recommandé)
- Un nom de domaine (par ex. `api.raventag.com`)
- Un compte Cloudflare (gratuit) gérant le DNS de votre domaine

---

## Étape 1 : Préparer le serveur

Connectez-vous à votre serveur via SSH et exécutez :

```bash
sudo apt update && sudo apt upgrade -y

# Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker

# nginx + nano
sudo apt install -y nginx nano

# Certbot (Let's Encrypt)
sudo snap install --classic certbot
sudo ln -s /snap/bin/certbot /usr/bin/certbot
```

---

## Étape 2 : Cloner le dépôt

```bash
git clone https://github.com/ALENOC/RavenTag.git --depth=1
cd RavenTag
```

---

## Étape 3 : Créer le répertoire des secrets Docker

```bash
mkdir -p secrets
```

---

## Étape 4 : Générer et stocker les clés secrètes

Exécutez ces commandes pour générer des clés aléatoires sécurisées. **Sauvegardez la sortie dans un gestionnaire de mots de passe** - ces clés ne peuvent pas être modifiées après que la première puce NFC a été programmée.

```bash
# Clé API Admin (pour révocation, annulation révocation, enregistrement puce)
openssl rand -hex 24 > secrets/admin_key

# Clé API Operator (enregistrement puce uniquement)
openssl rand -hex 24 > secrets/operator_key

# Clé master de la marque (AES-128 pour dérivation de clés par puce)
openssl rand -hex 16 > secrets/brand_master_key

# Sel de la marque (pour calcul nfc_pub_id)
openssl rand -hex 16 > secrets/brand_salt
```

**Important :** Chaque fichier doit contenir UNIQUEMENT la chaîne hex, sans retour à la ligne. Les commandes ci-dessus gèrent cela automatiquement.

---

## Étape 5 : Obtenir l'empreinte du certificat de l'application Android

Pour que Android App Links fonctionne, vous avez besoin de l'empreinte SHA-256 du certificat de votre APK de production :

```bash
keytool -list -v -keystore android/signing/raventag-release.keystore -alias raventag | grep SHA256
```

Extrayez uniquement l'empreinte (supprimez les deux-points, majuscules) :
```
<YOUR_COLON_FINGERPRINT> → <YOUR_HEX_FINGERPRINT>
```

**Empreinte SHA-256 :**
```
3E:A5:B9:F3:75:63:1A:4E:1D:E9:5D:E1:DA:9C:22:45:14:1E:4A:D8:FA:7A:63:78:7D:6A:B9:81:96:B4:A3:BE
```

Sans deux-points (pour `.env`) :
```
3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE
```

---

## Étape 6 : Configurer les variables d'environnement non secrètes

Créez un fichier `.env` pour la configuration non secrète :

```bash
cat > .env << 'EOF'
NODE_ENV=production
PORT=3001
DB_PATH=/data/raventag.db
# Point de terminaison RPC public Ravencoin (fallback quand nœud local indisponible)
RVN_PUBLIC_RPC_URL=https://rvn-rpc.publicnode.com
# Noms de package des applications Android autorisées à se connecter
ALLOWED_ORIGINS=io.raventag.app,io.raventag.app.brand
# Passerelle IPFS pour récupération des métadonnées
IPFS_GATEWAY=https://ipfs.io/ipfs/
# Empreinte du certificat Android App Links (de l'étape 5)
ANDROID_APP_FINGERPRINT=<VOTRE_EMPREINTE_ICI>
EOF
```

**Important :**
- Remplacez `ANDROID_APP_FINGERPRINT` par votre empreinte de certificat réelle de l'étape 5
- `ALLOWED_ORIGINS` doit contenir les noms de package des applications Android autorisées à se connecter à votre backend
- Ne commitez jamais le fichier `.env` dans le contrôle de version

---

## Étape 7 : Démarrer le backend

```bash
docker compose up -d backend
docker compose logs backend
```

Vérifiez qu'il est en cours d'exécution :

```bash
curl http://localhost:3001/health
# Attendu : {"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

Vérifiez que Android App Links sont configurés :

```bash
curl https://api.votredomaine.com/.well-known/assetlinks.json
# Devrait retourner JSON avec votre empreinte d'application
```

---

## Étape 8 : Configurer nginx

```bash
sudo nano /etc/nginx/sites-available/raventag
```

Collez :

```nginx
server {
    listen 80;
    server_name api.votredomaine.com;

    location / {
        proxy_pass http://127.0.0.1:3001;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Activez et rechargez :

```bash
sudo ln -s /etc/nginx/sites-available/raventag /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

---

## Étape 9 : Configurer le DNS sur Cloudflare

Dans le panneau DNS Cloudflare, ajoutez cet enregistrement (proxy OFF pour l'instant) :

| Type | Nom | Contenu |
|------|------|---------|
| A | `api` | IP de votre serveur |

Attendez la propagation DNS (généralement 2-5 minutes avec Cloudflare).

---

## Étape 10 : Obtenir le certificat SSL

```bash
sudo certbot --nginx -d api.votredomaine.com
```

Si certbot ne peut pas installer automatiquement le certificat, mettez à jour le `server_name` nginx avec le domaine réel et exécutez :

```bash
sudo certbot install --cert-name api.votredomaine.com
```

---

## Étape 11 : Mettre à jour le DNS Cloudflare

Après l'obtention du certificat SSL, activez le proxy (nuage orange) sur l'enregistrement `api` dans Cloudflare :

| Type | Nom | Contenu | Proxy |
|------|------|---------|-------|
| A | `api` | IP de votre serveur | On (Proxied) |

Définissez **le mode SSL/TLS sur Full (strict)** dans Cloudflare.

---

## Étape 12 : Vérification finale

```bash
curl https://api.votredomaine.com/health
```

Réponse attendue :
```json
{"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

---

## Étape 13 : Configurer les applications Android

Après le déploiement de votre backend, configurez les applications Android pour se connecter à votre backend :

1. Ouvrez l'application RavenTag
2. Allez dans Paramètres
3. Entrez l'URL de votre backend : `https://api.votredomaine.com`
4. Entrez votre clé API Admin ou Operator (depuis `secrets/admin_key` ou `secrets/operator_key`)
5. Sauvegardez

Les applications se connecteront maintenant à votre backend pour toutes les opérations.

---

## Mettre à jour le déploiement

Pour télécharger le nouveau code et redémarrer le backend :

```bash
cd ~/RavenTag
git pull
docker compose down backend
docker compose up -d --build backend
```

---

## Sauvegarde

La base de données SQLite est automatiquement sauvegardée quotidiennement par le service backup. Les sauvegardes sont :
- Chiffrées avec AES-256-CBC utilisant la clé admin
- Stockées dans le volume Docker `raventag_backups`
- Conservées pendant les 7 derniers jours

Pour effectuer une sauvegarde manuelle :

```bash
docker run --rm -v raventag_raventag_data:/data -v $(pwd):/backup alpine \
  cp /data/raventag.db /backup/raventag_$(date +%Y%m%d).db
```

Pour restaurer depuis une sauvegarde chiffrée :

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
  -pass file:secrets/admin_key \
  -in raventag_TIMESTAMP.db.enc \
  -out raventag.db
```

---

## Documentation API

Votre backend expose ces points de terminaison :

### Points de terminaison publics
- `GET /health` - Vérification de santé
- `POST /api/verify/tag` - Vérification complète (SUN + blockchain + révocation)
- `GET /api/assets/:name/revocation` - Vérifier le statut de révocation
- `GET /.well-known/assetlinks.json` - Vérification Android App Links

### Points de terminaison Operator (X-Operator-Key requis)
- `POST /api/brand/register-chip` - Enregistrer l'UID de la puce
- `GET /api/brand/chips` - Lister toutes les puces
- `GET /api/brand/chip/:assetName` - Obtenir une puce spécifique

### Points de terminaison Admin (X-Admin-Key requis)
- `POST /api/brand/revoke` - Révoquer un actif
- `DELETE /api/brand/revoke/:name` - Annuler la révocation
- `GET /api/brand/revoked` - Lister les actifs révoqués
- `POST /api/brand/derive-chip-key` - Dériver les clés AES de la puce

Pour la documentation API complète, consultez les spécifications [protocol.md](../protocol.md).

---

## Dépannage

### Le backend ne démarre pas
```bash
docker compose logs backend
```

Vérifiez :
- Fichiers secrets manquants dans le répertoire `./secrets/`
- Port déjà utilisé
- Permissions du chemin de la base de données

### Android App Links ne fonctionne pas
```bash
curl https://api.votredomaine.com/.well-known/assetlinks.json
```

Assurez-vous :
- `ANDROID_APP_FINGERPRINT` est défini dans `.env`
- L'empreinte est en majuscules, sans deux-points
- Le backend a été redémarré après avoir défini l'empreinte

### Problèmes de certificat SSL
```bash
sudo certbot certificates
sudo certbot renew --dry-run
```

### Corruption de la base de données
```bash
docker compose down backend
docker run --rm -v raventag_raventag_data:/data alpine ls -la /data
```

---

## Bonnes Pratiques de Sécurité

1. **Utilisez les secrets Docker** : Toutes les clés sensibles sont chargées depuis les fichiers `./secrets/`, jamais depuis les variables d'environnement
2. **Gardez vos clés secrètes** : Ne commitez jamais `.env` ou `secrets/` dans le contrôle de version
3. **Utilisez des clés fortes** : Toutes les clés doivent comporter au moins 32 caractères hexadécimaux
4. **Activez le proxy Cloudflare** : Proxy toujours votre point de terminaison API via Cloudflare
5. **Sauvegardes régulières** : Le service backup s'exécute automatiquement tous les jours
6. **Surveillez les journaux** : Vérifiez `docker compose logs backend` régulièrement
7. **Mettez à jour régulièrement** : Téléchargez et déployez les mises à jour mensuellement

---

## Format des Fichiers Secrets

Chaque fichier secret dans `./secrets/` doit contenir UNIQUEMENT la valeur de chaîne hex :

```bash
# Correct (sans retour à la ligne) :
echo -n "a1b2c3d4e5f6..." > secrets/admin_key

# Ou utilisez openssl qui gère cela automatiquement :
openssl rand -hex 24 > secrets/admin_key
```

Docker Compose lit ces fichiers et les monte à `/run/secrets/<name>` à l'intérieur du container. Le backend lit depuis les variables d'environnement `<KEY>_FILE` pour charger les secrets de manière sécurisée.

---

## Référence des Variables d'Environnement

| Variable | Requis | Description |
|----------|--------|-------------|
| `NODE_ENV` | Oui | Définir sur `production` |
| `PORT` | Oui | Port backend (défaut : 3001) |
| `DB_PATH` | Oui | Chemin base de données SQLite (`/data/raventag.db`) |
| `RVN_PUBLIC_RPC_URL` | Non | Point de terminaison RPC public Ravencoin (fallback) |
| `ALLOWED_ORIGINS` | Oui | Liste séparée par des virgules de noms de package Android |
| `IPFS_GATEWAY` | Oui | URL passerelle IPFS |
| `ANDROID_APP_FINGERPRINT` | Oui | Empreinte certificat SHA-256 pour Android App Links |

---

**Copyright 2026 Alessandro Nocentini. Tous droits réservés.**
