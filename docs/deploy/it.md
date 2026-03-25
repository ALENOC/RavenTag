# Guida al Deploy del Backend RavenTag

Questa guida copre il deploy completo in produzione del **solo Backend RavenTag**.

**Nota:** Le app Android RavenTag (Verify e Brand Manager) si connettono direttamente al tuo backend API. Non e richiesto alcun deploy di frontend.

---

## Panoramica Architettura

```
App Android (Verify + Brand Manager)
              ↓
    Tuo Backend API (questa guida)
              ↓
    Rete Ravencoin + IPFS
```

---

## Prerequisiti

- Un VPS Linux con IP pubblico statico (minimo 1 GB RAM, 10 GB disco, Ubuntu 24.04 LTS consigliato)
- Un dominio (es. `api.raventag.com`)
- Un account Cloudflare (gratuito) che gestisce il DNS del dominio

---

## Step 1: Preparare il server

Connettiti al server via SSH ed esegui:

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

## Step 2: Clonare il repository

```bash
git clone https://github.com/ALENOC/RavenTag.git --depth=1
cd RavenTag
```

---

## Step 3: Creare la directory per i Docker secrets

```bash
mkdir -p secrets
```

---

## Step 4: Generare e archiviare le chiavi segrete

Esegui questi comandi per generare chiavi casuali sicure. **Salva l'output in un password manager** - queste chiavi non possono essere cambiate dopo che il primo chip NFC e stato programmato.

```bash
# Chiave API Admin (per revoca, annullamento revoca, registrazione chip)
openssl rand -hex 24 > secrets/admin_key

# Chiave API Operator (solo per registrazione chip)
openssl rand -hex 24 > secrets/operator_key

# Chiave master del brand (AES-128 per derivazione chiavi per-chip)
openssl rand -hex 16 > secrets/brand_master_key

# Sale del brand (per calcolo nfc_pub_id)
openssl rand -hex 16 > secrets/brand_salt
```

**Importante:** Ogni file deve contenere SOLO la stringa hex, senza newline. I comandi sopra gestiscono questo automaticamente.

---

## Step 5: Ottenere l'impronta del certificato dell'app Android

Per far funzionare Android App Links, hai bisogno dell'impronta SHA-256 del certificato della tua APK release:

```bash
keytool -list -v -keystore android/signing/raventag-release.keystore -alias raventag | grep SHA256
```

Estrai solo l'impronta (rimuovi i due punti, maiuscolo):
```
<YOUR_COLON_FINGERPRINT> → <YOUR_HEX_FINGERPRINT>
```

**Impronta SHA-256:**
```
3E:A5:B9:F3:75:63:1A:4E:1D:E9:5D:E1:DA:9C:22:45:14:1E:4A:D8:FA:7A:63:78:7D:6A:B9:81:96:B4:A3:BE
```

Senza due punti (per `.env`):
```
3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE
```

**Configurazione singola o multipla:**

```bash
# Solo RavenTag Verify (OBBLIGATORIO per licenza RTSL-1.0):
ANDROID_APP_FINGERPRINT=3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE

# Tua app + RavenTag Verify (impronta RavenTag OBBLIGATORIA):
ANDROID_APP_FINGERPRINT=YOUR_FINGERPRINT,3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE
```

**RTSL-1.0 License Requirement:** L'impronta di RavenTag Verify DEVE essere sempre inclusa nel backend. Il server rifiuterà configurazioni che non la includono.

Il backend supporta impronte multiple per scenari di co-branding. Entrambe le app potranno gestire gli URL dei tag NFC.

---

## Step 6: Configurare le variabili d'ambiente non segrete

Crea un file `.env` per la configurazione non segreta:

```bash
cat > .env << 'EOF'
NODE_ENV=production
PORT=3001
DB_PATH=/data/raventag.db
# Endpoint RPC pubblico Ravencoin (fallback quando nodo locale non disponibile)
RVN_PUBLIC_RPC_URL=https://rvn-rpc.publicnode.com
# Package name app Android autorizzate a connettersi
ALLOWED_ORIGINS=io.raventag.app,io.raventag.app.brand
# Gateway IPFS per recupero metadati
IPFS_GATEWAY=https://ipfs.io/ipfs/
# Impronta certificato Android App Links (da Step 5)
ANDROID_APP_FINGERPRINT=<INSERISCI_QUI_LA_TUA_IMPRONTA>
EOF
```

**Importante:** 
- Sostituisci `ANDROID_APP_FINGERPRINT` con la tua effettiva impronta del certificato da Step 5
- `ALLOWED_ORIGINS` deve contenere i package name delle app Android autorizzate a connettersi al tuo backend
- Non committare mai il file `.env` nel version control

---

## Step 7: Avviare il backend

```bash
docker compose up -d backend
docker compose logs backend
```

Verifica che sia in esecuzione:

```bash
curl http://localhost:3001/health
# Atteso: {"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

Verifica che Android App Links siano configurati:

```bash
curl https://api.tuodominio.com/.well-known/assetlinks.json
# Dovrebbe restituire JSON con la tua impronta app
```

---

## Step 8: Configurare nginx

```bash
sudo nano /etc/nginx/sites-available/raventag
```

Incolla:

```nginx
server {
    listen 80;
    server_name api.tuodominio.com;

    location / {
        proxy_pass http://127.0.0.1:3001;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Attiva e ricarica:

```bash
sudo ln -s /etc/nginx/sites-available/raventag /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

---

## Step 9: Configurare il DNS su Cloudflare

Nel pannello DNS di Cloudflare, aggiungi questo record (proxy OFF per ora):

| Tipo | Nome | Contenuto |
|------|------|-----------|
| A | `api` | IP del server |

Aspetta la propagazione DNS (di solito 2-5 minuti con Cloudflare).

---

## Step 10: Ottenere il certificato SSL

```bash
sudo certbot --nginx -d api.tuodominio.com
```

Se certbot non riesce a installare automaticamente il certificato, aggiorna il `server_name` di nginx con il dominio reale ed esegui:

```bash
sudo certbot install --cert-name api.tuodominio.com
```

---

## Step 11: Aggiornare il DNS Cloudflare

Dopo aver ottenuto il certificato SSL, attiva il proxy (nuvoletta arancione) sul record `api` in Cloudflare:

| Tipo | Nome | Contenuto | Proxy |
|------|------|-----------|-------|
| A | `api` | IP del server | On (Proxied) |

Imposta **SSL/TLS su Full (strict)** in Cloudflare.

---

## Step 12: Verifica finale

```bash
curl https://api.tuodominio.com/health
```

Risposta attesa:
```json
{"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

---

## Step 13: Configurare le app Android

Dopo aver deployato il backend, configura le app Android per connettersi al tuo backend:

1. Apri l'app RavenTag
2. Vai su Impostazioni
3. Inserisci l'URL del tuo backend: `https://api.tuodominio.com`
4. Inserisci la tua chiave API Admin o Operator (da `secrets/admin_key` o `secrets/operator_key`)
5. Salva

Le app si connetteranno ora al tuo backend per tutte le operazioni.

---

## Aggiornare il deploy

Per scaricare nuovo codice e riavviare il backend:

```bash
cd ~/RavenTag
git pull
docker compose down backend
docker compose up -d --build backend
```

---

## Backup

Il database SQLite viene automaticamente sottoposto a backup giornaliero dal servizio backup. I backup sono:
- Crittografati con AES-256-CBC usando la chiave admin
- Archiviati nel volume Docker `raventag_backups`
- Mantenuti per gli ultimi 7 giorni

Per eseguire un backup manuale:

```bash
docker run --rm -v raventag_raventag_data:/data -v $(pwd):/backup alpine \
  cp /data/raventag.db /backup/raventag_$(date +%Y%m%d).db
```

Per ripristinare da un backup crittografato:

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
  -pass file:secrets/admin_key \
  -in raventag_TIMESTAMP.db.enc \
  -out raventag.db
```

---

## Documentazione API

Il tuo backend espone questi endpoint:

### Endpoint Pubblici
- `GET /health` - Health check
- `POST /api/verify/tag` - Verifica completa (SUN + blockchain + revoca)
- `GET /api/assets/:name/revocation` - Controlla stato revoca
- `GET /.well-known/assetlinks.json` - Verifica Android App Links

### Endpoint Operator (richiede X-Operator-Key)
- `POST /api/brand/register-chip` - Registra UID chip
- `GET /api/brand/chips` - Lista tutti i chip
- `GET /api/brand/chip/:assetName` - Ottieni chip specifico

### Endpoint Admin (richiede X-Admin-Key)
- `POST /api/brand/revoke` - Revoca asset
- `DELETE /api/brand/revoke/:name` - Annulla revoca
- `GET /api/brand/revoked` - Lista asset revocati
- `POST /api/brand/derive-chip-key` - Deriva chiavi AES chip

Per la documentazione API completa, vedi le specifiche [protocol.md](../protocol.md).

---

## Risoluzione Problemi

### Il backend non si avvia
```bash
docker compose logs backend
```

Controlla:
- File secret mancanti nella directory `./secrets/`
- Porta gia in uso
- Permessi percorso database

### Android App Links non funzionano
```bash
curl https://api.tuodominio.com/.well-known/assetlinks.json
```

Assicurati:
- `ANDROID_APP_FINGERPRINT` e impostato in `.env`
- L'impronta e maiuscola, senza due punti
- Il backend e stato riavviato dopo aver impostato l'impronta

### Problemi certificato SSL
```bash
sudo certbot certificates
sudo certbot renew --dry-run
```

### Corruzione database
```bash
docker compose down backend
docker run --rm -v raventag_raventag_data:/data alpine ls -la /data
```

---

## Best Practice di Sicurezza

1. **Usa Docker secrets**: Tutte le chiavi sensibili sono caricate da file `./secrets/`, mai da variabili d'ambiente
2. **Mantieni le chiavi segrete**: Non committare mai `.env` o `secrets/` nel version control
3. **Usa chiavi forti**: Tutte le chiavi dovrebbero essere almeno 32 caratteri hex
4. **Abilita proxy Cloudflare**: Proxy sempre il tuo endpoint API attraverso Cloudflare
5. **Backup regolari**: Il servizio backup esegue automaticamente ogni giorno
6. **Monitora i log**: Controlla `docker compose logs backend` regolarmente
7. **Aggiorna regolarmente**: Scarica e deploya aggiornamenti mensilmente

---

## Formato dei File Secret

Ogni file secret in `./secrets/` deve contenere SOLO il valore stringa hex:

```bash
# Corretto (senza newline):
echo -n "a1b2c3d4e5f6..." > secrets/admin_key

# O usa openssl che gestisce questo automaticamente:
openssl rand -hex 24 > secrets/admin_key
```

Docker Compose legge questi file e li monta in `/run/secrets/<name>` all'interno del container. Il backend legge dalle variabili d'ambiente `<KEY>_FILE` per caricare i secret in modo sicuro.

---

## Riferimento Variabili d'Ambiente

| Variabile | Obbligatoria | Descrizione |
|-----------|--------------|-------------|
| `NODE_ENV` | Si | Imposta su `production` |
| `PORT` | Si | Porta backend (default: 3001) |
| `DB_PATH` | Si | Percorso database SQLite (`/data/raventag.db`) |
| `RVN_PUBLIC_RPC_URL` | No | Endpoint RPC pubblico Ravencoin (fallback) |
| `ALLOWED_ORIGINS` | Si | Lista separata da virgola di package name app Android |
| `IPFS_GATEWAY` | Si | URL gateway IPFS |
| `ANDROID_APP_FINGERPRINT` | Si | Impronta certificato SHA-256 per Android App Links |

---

**Copyright 2026 Alessandro Nocentini. Tutti i diritti riservati.**
