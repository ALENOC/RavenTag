# RavenTag Backend Deploy Guide

This guide covers the complete production deployment of the **RavenTag Backend** only.

**Note:** The RavenTag Android apps (Verify and Brand Manager) connect directly to your backend API. No frontend deployment is required.

---

## Architecture Overview

```
Android Apps (Verify + Brand Manager)
              ↓
    Your Backend API (this guide)
              ↓
    Ravencoin Network + IPFS
```

---

## Prerequisites

- A Linux VPS with a public static IP (minimum 1 GB RAM, 10 GB disk, Ubuntu 24.04 LTS recommended)
- A domain name (e.g. `api.raventag.com`)
- A Cloudflare account (free) managing your domain DNS

---

## Step 1: Prepare the server

Connect to your server via SSH and run:

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

## Step 2: Clone the repository

```bash
git clone https://github.com/ALENOC/RavenTag.git --depth=1
cd RavenTag
```

---

## Step 3: Create Docker secrets directory

```bash
mkdir -p secrets
```

---

## Step 4: Generate and store secret keys

Run these commands to generate secure random keys. **Save the output in a password manager** - these keys cannot be changed after the first NFC chip is programmed.

```bash
# Admin API key (for revocation, un-revoke, chip registration)
openssl rand -hex 24 > secrets/admin_key

# Operator API key (for chip registration only)
openssl rand -hex 24 > secrets/operator_key

# Brand master key (AES-128 for per-chip key derivation)
openssl rand -hex 16 > secrets/brand_master_key

# Brand salt (for nfc_pub_id computation)
openssl rand -hex 16 > secrets/brand_salt
```

**Important:** Each file should contain ONLY the hex string, no newline. The commands above handle this correctly.

---

## Step 5: Get Android app certificate fingerprint

For Android App Links to work, you need the SHA-256 certificate fingerprint of your release APK:

```bash
keytool -list -v -keystore android/signing/raventag-release.keystore -alias raventag | grep SHA256
```

Extract just the fingerprint (remove colons, uppercase):
```
<YOUR_COLON_FINGERPRINT> → <YOUR_HEX_FINGERPRINT>
```

**SHA-256 Fingerprint:**
```
3E:A5:B9:F3:75:63:1A:4E:1D:E9:5D:E1:DA:9C:22:45:14:1E:4A:D8:FA:7A:63:78:7D:6A:B9:81:96:B4:A3:BE
```

Without colons (for `.env`):
```
3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE
```

**Single or Multiple Fingerprints:**

```bash
# RavenTag Verify only (REQUIRED by RTSL-1.0 license):
ANDROID_APP_FINGERPRINT=3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE

# Your app + RavenTag Verify (RavenTag fingerprint REQUIRED):
ANDROID_APP_FINGERPRINT=YOUR_FINGERPRINT,3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE
```

**RTSL-1.0 License Requirement:** The RavenTag Verify fingerprint MUST be included in all deployments. The backend will reject configurations that do not include it.

The backend supports multiple fingerprints for co-branding scenarios. Both apps will be able to handle NFC tag URLs.

---

## Step 6: Configure non-secret environment variables

Create a `.env` file for non-secret configuration:

```bash
cat > .env << 'EOF'
NODE_ENV=production
PORT=3001
DB_PATH=/data/raventag.db
# Public Ravencoin RPC endpoint (fallback when local node unavailable)
RVN_PUBLIC_RPC_URL=https://rvn-rpc.publicnode.com
# Android app package names allowed to connect
ALLOWED_ORIGINS=io.raventag.app,io.raventag.app.brand
# IPFS gateway for metadata fetching
IPFS_GATEWAY=https://ipfs.io/ipfs/
# Android App Links certificate fingerprint (from Step 5)
ANDROID_APP_FINGERPRINT=<YOUR_FINGERPRINT_HERE>
EOF
```

**Important:** 
- Replace `ANDROID_APP_FINGERPRINT` with your actual certificate fingerprint from Step 5
- `ALLOWED_ORIGINS` should contain the Android app package names allowed to connect to your backend
- Never commit the `.env` file to version control

---

## Step 7: Start the backend

```bash
docker compose up -d backend
docker compose logs backend
```

Verify it is running:

```bash
curl http://localhost:3001/health
# Expected: {"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

Verify Android App Links are configured:

```bash
curl https://api.yourdomain.com/.well-known/assetlinks.json
# Should return JSON with your app fingerprint
```

---

## Step 8: Configure nginx

```bash
sudo nano /etc/nginx/sites-available/raventag
```

Paste:

```nginx
server {
    listen 80;
    server_name api.yourdomain.com;

    location / {
        proxy_pass http://127.0.0.1:3001;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Enable and reload:

```bash
sudo ln -s /etc/nginx/sites-available/raventag /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

---

## Step 9: Configure DNS on Cloudflare

In Cloudflare DNS panel, add this record (proxy OFF for now):

| Type | Name | Content |
|------|------|---------|
| A | `api` | your server IP |

Wait for DNS propagation (usually 2-5 minutes with Cloudflare).

---

## Step 10: Obtain SSL certificate

```bash
sudo certbot --nginx -d api.yourdomain.com
```

If certbot cannot automatically install the certificate, update nginx `server_name` with the actual domain and run:

```bash
sudo certbot install --cert-name api.yourdomain.com
```

---

## Step 11: Update Cloudflare DNS

After SSL certificate is obtained, enable the proxy (orange cloud) on the `api` record in Cloudflare:

| Type | Name | Content | Proxy |
|------|------|---------|-------|
| A | `api` | your server IP | On (Proxied) |

Set **SSL/TLS mode to Full (strict)** in Cloudflare.

---

## Step 12: Final verification

```bash
curl https://api.yourdomain.com/health
```

Expected response:
```json
{"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

---

## Step 13: Configure Android apps

After your backend is deployed, configure the Android apps to connect to your backend:

1. Open RavenTag app
2. Go to Settings
3. Enter your backend URL: `https://api.yourdomain.com`
4. Enter your Admin or Operator API key (from `secrets/admin_key` or `secrets/operator_key`)
5. Save

The apps will now connect to your backend for all operations.

---

## Updating the deployment

To pull new code and restart the backend:

```bash
cd ~/RavenTag
git pull
docker compose down backend
docker compose up -d --build backend
```

---

## Backup

The SQLite database is automatically backed up daily by the backup service. Backups are:
- Encrypted with AES-256-CBC using the admin key
- Stored in the `raventag_backups` Docker volume
- Retained for the last 7 days

To manually backup:

```bash
docker run --rm -v raventag_raventag_data:/data -v $(pwd):/backup alpine \
  cp /data/raventag.db /backup/raventag_$(date +%Y%m%d).db
```

To restore from an encrypted backup:

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
  -pass file:secrets/admin_key \
  -in raventag_TIMESTAMP.db.enc \
  -out raventag.db
```

---

## API Documentation

Your backend exposes these endpoints:

### Public Endpoints
- `GET /health` - Health check
- `POST /api/verify/tag` - Full verification (SUN + blockchain + revocation)
- `GET /api/assets/:name/revocation` - Check revocation status
- `GET /.well-known/assetlinks.json` - Android App Links verification

### Operator Endpoints (X-Operator-Key required)
- `POST /api/brand/register-chip` - Register chip UID
- `GET /api/brand/chips` - List all chips
- `GET /api/brand/chip/:assetName` - Get specific chip

### Admin Endpoints (X-Admin-Key required)
- `POST /api/brand/revoke` - Revoke asset
- `DELETE /api/brand/revoke/:name` - Un-revoke asset
- `GET /api/brand/revoked` - List revoked assets
- `POST /api/brand/derive-chip-key` - Derive chip AES keys

For complete API documentation, see the [protocol.md](../protocol.md) specification.

---

## Troubleshooting

### Backend won't start
```bash
docker compose logs backend
```

Check for:
- Missing secret files in `./secrets/` directory
- Port already in use
- Database path permissions

### Android App Links not working
```bash
curl https://api.yourdomain.com/.well-known/assetlinks.json
```

Ensure:
- `ANDROID_APP_FINGERPRINT` is set in `.env`
- Fingerprint is uppercase, no colons
- Backend has been restarted after setting the fingerprint

### SSL certificate issues
```bash
sudo certbot certificates
sudo certbot renew --dry-run
```

### Database corruption
```bash
docker compose down backend
docker run --rm -v raventag_raventag_data:/data alpine ls -la /data
```

---

## Security Best Practices

1. **Use Docker secrets**: All sensitive keys are loaded from `./secrets/` files, never from environment variables
2. **Keep your keys secret**: Never commit `.env` or `secrets/` to version control
3. **Use strong keys**: All keys should be at least 32 hex characters
4. **Enable Cloudflare proxy**: Always proxy your API endpoint through Cloudflare
5. **Regular backups**: The backup service runs daily automatically
6. **Monitor logs**: Check `docker compose logs backend` regularly
7. **Update regularly**: Pull and deploy updates monthly

---

## Secret File Format

Each secret file in `./secrets/` should contain ONLY the hex string value:

```bash
# Correct (no newline):
echo -n "a1b2c3d4e5f6..." > secrets/admin_key

# Or use openssl which handles this automatically:
openssl rand -hex 24 > secrets/admin_key
```

Docker Compose reads these files and mounts them at `/run/secrets/<name>` inside the container. The backend reads from `<KEY>_FILE` environment variables to load secrets securely.

---

## Environment Variables Reference

| Variable | Required | Description |
|----------|----------|-------------|
| `NODE_ENV` | Yes | Set to `production` |
| `PORT` | Yes | Backend port (default: 3001) |
| `DB_PATH` | Yes | SQLite database path (`/data/raventag.db`) |
| `RVN_PUBLIC_RPC_URL` | No | Public Ravencoin RPC endpoint (fallback) |
| `ALLOWED_ORIGINS` | Yes | Comma-separated Android app package names |
| `IPFS_GATEWAY` | Yes | IPFS gateway URL |
| `ANDROID_APP_FINGERPRINT` | Yes | SHA-256 certificate fingerprint for Android App Links |

---

**Copyright 2026 Alessandro Nocentini. All rights reserved.**
