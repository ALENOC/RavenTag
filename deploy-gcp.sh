#!/bin/bash
# Script per aggiornare il backend RavenTag su Google Cloud
# Utilizza SSH per connettersi al server e aggiornare il deployment

set -e

# Configurazione
GCP_INSTANCE_NAME="${GCP_INSTANCE_NAME:-raventag-backend}"
GCP_ZONE="${GCP_ZONE:-europe-west1-b}"
GCP_PROJECT="${GCP_PROJECT:-raventag}"
LOCAL_PATH="/home/ale/Projects/RavenTag"

echo "========================================"
echo "RavenTag Backend - Google Cloud Update"
echo "========================================"
echo ""
echo "Instance: $GCP_INSTANCE_NAME"
echo "Zone: $GCP_ZONE"
echo "Project: $GCP_PROJECT"
echo ""

# Step 1: Verifica connessione
echo "[1/8] Verifica connessione a Google Cloud..."
if ! gcloud compute instances describe "$GCP_INSTANCE_NAME" \
    --zone="$GCP_ZONE" \
    --project="$GCP_PROJECT" &>/dev/null; then
    echo "❌ Errore: Impossibile connettersi all'istanza $GCP_INSTANCE_NAME"
    echo "Verifica che l'istanza esista e che le credenziali GCP siano configurate."
    exit 1
fi
echo "✓ Connessione verificata"

# Step 2: Push ultime modifiche su GitHub
echo ""
echo "[2/8] Push ultime modifiche su GitHub..."
cd "$LOCAL_PATH"
git push origin master
echo "✓ Push completato"

# Step 3: SSH e aggiornamento
echo ""
echo "[3/8] Connessione SSH e aggiornamento..."
gcloud compute ssh "$GCP_INSTANCE_NAME" \
    --zone="$GCP_ZONE" \
    --project="$GCP_PROJECT" \
    --command="
set -e

echo '=== Aggiornamento RavenTag Backend ==='
echo ''

# Step 4: Pull ultime modifiche
echo '[4/8] Pull ultime modifiche da GitHub...'
cd ~/RavenTag
git pull origin master
echo '✓ Pull completato'

# Step 5: Verifica secrets
echo ''
echo '[5/8] Verifica secrets...'
if [ ! -d 'secrets' ]; then
    echo '⚠️  Directory secrets/ non trovata!'
    echo 'Creazione directory secrets...'
    mkdir -p secrets
fi

# Verifica file secrets
for secret in admin_key operator_key brand_master_key brand_salt; do
    if [ ! -f \"secrets/\$secret\" ]; then
        echo '⚠️  File secrets/'\$secret' mancante!'
        echo 'Generazione nuovo secret...'
        openssl rand -hex 24 > \"secrets/\$secret\"
        chmod 600 \"secrets/\$secret\"
    fi
done
echo '✓ Secrets verificati'

# Step 6: Verifica .env
echo ''
echo '[6/8] Verifica configurazione .env...'
if [ ! -f '.env' ]; then
    echo '⚠️  File .env non trovato!'
    echo 'Creazione .env con configurazione base...'
    cat > .env << 'EOF'
NODE_ENV=production
PORT=3001
DB_PATH=/data/raventag.db
RVN_PUBLIC_RPC_URL=https://rvn-rpc.publicnode.com
ALLOWED_ORIGINS=io.raventag.app
IPFS_GATEWAY=https://ipfs.io/ipfs/
ANDROID_APP_FINGERPRINT=3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE
EOF
    echo '⚠️  MODIFICA IL FILE .env CON I TUOI VALORI!'
else
    echo '✓ File .env presente'
fi

# Step 7: Build e restart
echo ''
echo '[7/8] Build e restart del backend...'
docker compose down backend || true
docker compose up -d --build backend
echo '✓ Backend riavviato'

# Step 8: Verifica salute
echo ''
echo '[8/8] Verifica salute backend...'
sleep 5
curl -s http://localhost:3001/health || echo '⚠️  Backend non risponde ancora, attendere...'

echo ''
echo '=== Aggiornamento Completato! ==='
echo ''
echo 'Logs: docker compose logs backend'
echo 'Health: curl http://localhost:3001/health'
"

echo ""
echo "========================================"
echo "✅ Aggiornamento Completato!"
echo "========================================"
echo ""
echo "Per verificare:"
echo "  gcloud compute ssh $GCP_INSTANCE_NAME --zone=$GCP_ZONE --project=$GCP_PROJECT"
echo "  docker compose logs backend"
echo ""
echo "Health check:"
echo "  curl https://api.tuodominio.com/health"
echo ""
