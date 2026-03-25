# RavenTag Backend-Bereitstellungsanleitung

Diese Anleitung behandelt die vollständige Produktionsbereitstellung des **RavenTag Backends ausschließlich**.

**Hinweis:** Die RavenTag Android-Apps (Verify und Brand Manager) verbinden sich direkt mit Ihrer Backend-API. Es ist keine Frontend-Bereitstellung erforderlich.

---

## Architekturübersicht

```
Android-Apps (Verify + Brand Manager)
              ↓
    Ihre Backend-API (diese Anleitung)
              ↓
    Ravencoin-Netzwerk + IPFS
```

---

## Voraussetzungen

- Ein Linux-VPS mit öffentlicher statischer IP (mindestens 1 GB RAM, 10 GB Festplatte, Ubuntu 24.04 LTS empfohlen)
- Ein Domainname (z.B. `api.raventag.com`)
- Ein Cloudflare-Konto (kostenlos) zur Verwaltung Ihrer Domain-DNS

---

## Schritt 1: Server vorbereiten

Verbinden Sie sich per SSH mit Ihrem Server und führen Sie aus:

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

## Schritt 2: Repository klonen

```bash
git clone https://github.com/ALENOC/RavenTag.git --depth=1
cd RavenTag
```

---

## Schritt 3: Docker secrets-Verzeichnis erstellen

```bash
mkdir -p secrets
```

---

## Schritt 4: Geheime Schlüssel generieren und speichern

Führen Sie diese Befehle aus, um sichere zufällige Schlüssel zu generieren. **Speichern Sie die Ausgabe in einem Passwort-Manager** - diese Schlüssel können nach der Programmierung des ersten NFC-Chips nicht mehr geändert werden.

```bash
# Admin-API-Schlüssel (für Widerruf, Widerruf aufheben, Chip-Registrierung)
openssl rand -hex 24 > secrets/admin_key

# Operator-API-Schlüssel (nur Chip-Registrierung)
openssl rand -hex 24 > secrets/operator_key

# Marken-Masterschlüssel (AES-128 für pro-Chip-Schlüsselableitung)
openssl rand -hex 16 > secrets/brand_master_key

# Marken-Salz (für nfc_pub_id-Berechnung)
openssl rand -hex 16 > secrets/brand_salt
```

**Wichtig:** Jede Datei sollte NUR den Hex-String enthalten, ohne Zeilenumbruch. Die obigen Befehle erledigen dies automatisch.

---

## Schritt 5: Android-App-Zertifikatsfingerabdruck erhalten

Damit Android App Links funktioniert, benötigen Sie den SHA-256-Zertifikatsfingerabdruck Ihrer Release-APK:

```bash
keytool -list -v -keystore android/signing/raventag-release.keystore -alias raventag | grep SHA256
```

Extrahieren Sie nur den Fingerabdruck (Doppelpunkte entfernen, Großbuchstaben):
```
<YOUR_COLON_FINGERPRINT> → <YOUR_HEX_FINGERPRINT>
```

**Aktueller Fingerabdruck (neuer Keystore 2026-03-25):**
```
3E:A5:B9:F3:75:63:1A:4E:1D:E9:5D:E1:DA:9C:22:45:14:1E:4A:D8:FA:7A:63:78:7D:6A:B9:81:96:B4:A3:BE
```

Ohne Doppelpunkte (für `.env`):
```
3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE
```

---

## Schritt 6: Nicht-geheime Umgebungsvariablen konfigurieren

Erstellen Sie eine `.env`-Datei für nicht-geheime Konfiguration:

```bash
cat > .env << 'EOF'
NODE_ENV=production
PORT=3001
DB_PATH=/data/raventag.db
# Öffentlicher Ravencoin-RPC-Endpunkt (Fallback wenn lokaler Knoten nicht verfügbar)
RVN_PUBLIC_RPC_URL=https://rvn-rpc.publicnode.com
# Paketnamen der Android-Apps, die eine Verbindung herstellen dürfen
ALLOWED_ORIGINS=io.raventag.app,io.raventag.app.brand
# IPFS-Gateway für Metadaten-Abruf
IPFS_GATEWAY=https://ipfs.io/ipfs/
# Android App Links Zertifikatsfingerabdruck (aus Schritt 5)
ANDROID_APP_FINGERPRINT=<DEIN_FINGERABDRUCK_HIER>
EOF
```

**Wichtig:**
- Ersetzen Sie `ANDROID_APP_FINGERPRINT` durch Ihren tatsächlichen Zertifikatsfingerabdruck aus Schritt 5
- `ALLOWED_ORIGINS` sollte die Paketnamen der Android-Apps enthalten, die eine Verbindung zu Ihrem Backend herstellen dürfen
- Committen Sie die `.env`-Datei niemals in die Versionskontrolle

---

## Schritt 7: Backend starten

```bash
docker compose up -d backend
docker compose logs backend
```

Überprüfen Sie, ob es läuft:

```bash
curl http://localhost:3001/health
# Erwartet: {"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

Überprüfen Sie, ob Android App Links konfiguriert sind:

```bash
curl https://api.ihredomain.com/.well-known/assetlinks.json
# Sollte JSON mit Ihrem App-Fingerabdruck zurückgeben
```

---

## Schritt 8: nginx konfigurieren

```bash
sudo nano /etc/nginx/sites-available/raventag
```

Einfügen:

```nginx
server {
    listen 80;
    server_name api.ihredomain.com;

    location / {
        proxy_pass http://127.0.0.1:3001;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Aktivieren und neu laden:

```bash
sudo ln -s /etc/nginx/sites-available/raventag /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

---

## Schritt 9: DNS bei Cloudflare konfigurieren

Fügen Sie im Cloudflare DNS-Panel diesen Datensatz hinzu (Proxy zunächst AUS):

| Typ | Name | Inhalt |
|------|------|---------|
| A | `api` | Ihre Server-IP |

Warten Sie auf die DNS-Verbreitung (normalerweise 2-5 Minuten mit Cloudflare).

---

## Schritt 10: SSL-Zertifikat erhalten

```bash
sudo certbot --nginx -d api.ihredomain.com
```

Wenn certbot das Zertifikat nicht automatisch installieren kann, aktualisieren Sie den nginx `server_name` mit der tatsächlichen Domain und führen Sie aus:

```bash
sudo certbot install --cert-name api.ihredomain.com
```

---

## Schritt 11: Cloudflare DNS aktualisieren

Nachdem das SSL-Zertifikat erhalten wurde, aktivieren Sie den Proxy (orangefarbene Wolke) beim `api`-Datensatz in Cloudflare:

| Typ | Name | Inhalt | Proxy |
|------|------|---------|-------|
| A | `api` | Ihre Server-IP | An (Proxied) |

Stellen Sie den **SSL/TLS-Modus auf Voll (streng)** in Cloudflare ein.

---

## Schritt 12: Abschließende Überprüfung

```bash
curl https://api.ihredomain.com/health
```

Erwartete Antwort:
```json
{"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

---

## Schritt 13: Android-Apps konfigurieren

Nach der Bereitstellung Ihres Backends konfigurieren Sie die Android-Apps für die Verbindung mit Ihrem Backend:

1. Öffnen Sie die RavenTag-App
2. Gehen Sie zu Einstellungen
3. Geben Sie Ihre Backend-URL ein: `https://api.ihredomain.com`
4. Geben Sie Ihren Admin- oder Operator-API-Schlüssel ein (von `secrets/admin_key` oder `secrets/operator_key`)
5. Speichern

Die Apps verbinden sich nun für alle Vorgänge mit Ihrem Backend.

---

## Bereitstellung aktualisieren

Um neuen Code herunterzuladen und das Backend neu zu starten:

```bash
cd ~/RavenTag
git pull
docker compose down backend
docker compose up -d --build backend
```

---

## Sicherung

Die SQLite-Datenbank wird automatisch täglich vom Backup-Service gesichert. Backups sind:
- Mit AES-256-CBC unter Verwendung des Admin-Schlüssels verschlüsselt
- Im Docker-Volume `raventag_backups` gespeichert
- Für die letzten 7 Tage aufbewahrt

So führen Sie eine manuelle Sicherung durch:

```bash
docker run --rm -v raventag_raventag_data:/data -v $(pwd):/backup alpine \
  cp /data/raventag.db /backup/raventag_$(date +%Y%m%d).db
```

So stellen Sie von einer verschlüsselten Sicherung wieder her:

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
  -pass file:secrets/admin_key \
  -in raventag_TIMESTAMP.db.enc \
  -out raventag.db
```

---

## API-Dokumentation

Ihr Backend stellt diese Endpunkte bereit:

### Öffentliche Endpunkte
- `GET /health` - Gesundheitscheck
- `POST /api/verify/tag` - Vollständige Überprüfung (SUN + Blockchain + Widerruf)
- `GET /api/assets/:name/revocation` - Widerrufsstatus überprüfen
- `GET /.well-known/assetlinks.json` - Android App Links-Verifizierung

### Operator-Endpunkte (X-Operator-Key erforderlich)
- `POST /api/brand/register-chip` - Chip-UID registrieren
- `GET /api/brand/chips` - Alle Chips auflisten
- `GET /api/brand/chip/:assetName` - Spezifischen Chip abrufen

### Admin-Endpunkte (X-Admin-Key erforderlich)
- `POST /api/brand/revoke` - Asset widerrufen
- `DELETE /api/brand/revoke/:name` - Widerruf rückgängig machen
- `GET /api/brand/revoked` - Widerrufene Assets auflisten
- `POST /api/brand/derive-chip-key` - Chip-AES-Schlüssel ableiten

Die vollständige API-Dokumentation finden Sie in der [protocol.md](../protocol.md) Spezifikation.

---

## Fehlerbehebung

### Backend startet nicht
```bash
docker compose logs backend
```

Überprüfen Sie:
- Fehlende secret-Dateien im `./secrets/`-Verzeichnis
- Bereits verwendeter Port
- Datenbankpfad-Berechtigungen

### Android App Links funktioniert nicht
```bash
curl https://api.ihredomain.com/.well-known/assetlinks.json
```

Stellen Sie sicher:
- `ANDROID_APP_FINGERPRINT` ist in `.env` gesetzt
- Fingerabdruck ist in Großbuchstaben, ohne Doppelpunkte
- Backend wurde nach dem Setzen des Fingerabdrucks neu gestartet

### SSL-Zertifikatprobleme
```bash
sudo certbot certificates
sudo certbot renew --dry-run
```

### Datenbankbeschädigung
```bash
docker compose down backend
docker run --rm -v raventag_raventag_data:/data alpine ls -la /data
```

---

## Sicherheits-Best-Practices

1. **Docker secrets verwenden**: Alle sensiblen Schlüssel werden aus `./secrets/`-Dateien geladen, niemals aus Umgebungsvariablen
2. **Schlüssel geheim halten**: `.env` oder `secrets/` niemals in die Versionskontrolle committen
3. **Starke Schlüssel verwenden**: Alle Schlüssel sollten mindestens 32 Hex-Zeichen lang sein
4. **Cloudflare-Proxy aktivieren**: API-Endpunkt immer durch Cloudflare proxyen
5. **Regelmäßige Sicherungen**: Der Backup-Service läuft täglich automatisch
6. **Logs überwachen**: `docker compose logs backend` regelmäßig überprüfen
7. **Regelmäßig aktualisieren**: Updates monatlich herunterladen und bereitstellen

---

## Secret-Dateiformat

Jede secret-Datei in `./secrets/` sollte NUR den Hex-String-Wert enthalten:

```bash
# Korrekt (ohne Zeilenumbruch):
echo -n "a1b2c3d4e5f6..." > secrets/admin_key

# Oder verwenden Sie openssl, das dies automatisch handhabt:
openssl rand -hex 24 > secrets/admin_key
```

Docker Compose liest diese Dateien und mountet sie unter `/run/secrets/<name>` im Container. Das Backend liest aus den Umgebungsvariablen `<KEY>_FILE`, um Secrets sicher zu laden.

---

## Umgebungsvariablen-Referenz

| Variable | Erforderlich | Beschreibung |
|----------|--------------|--------------|
| `NODE_ENV` | Ja | Auf `production` setzen |
| `PORT` | Ja | Backend-Port (Standard: 3001) |
| `DB_PATH` | Ja | SQLite-Datenbankpfad (`/data/raventag.db`) |
| `RVN_PUBLIC_RPC_URL` | Nein | Öffentlicher Ravencoin-RPC-Endpunkt (Fallback) |
| `ALLOWED_ORIGINS` | Ja | Durch Komma getrennte Liste von Android-Paketnamen |
| `IPFS_GATEWAY` | Ja | IPFS-Gateway-URL |
| `ANDROID_APP_FINGERPRINT` | Ja | SHA-256-Zertifikatsfingerabdruck für Android App Links |

---

**Copyright 2026 Alessandro Nocentini. Alle Rechte vorbehalten.**
