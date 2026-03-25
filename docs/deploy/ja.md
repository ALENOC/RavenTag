# RavenTag バックエンドデプロイガイド

このガイドでは、**RavenTag バックエンド**の完全な本番デプロイメントについて説明します。

**注:** RavenTag Android アプリ（Verify および Brand Manager）はバックエンド API に直接接続します。フロントエンドのデプロイは不要です。

---

## アーキテクチャ概要

```
Android アプリ（Verify + Brand Manager）
              ↓
    貴社のバックエンド API（このガイド）
              ↓
    Ravencoin ネットワーク + IPFS
```

---

## 前提条件

- パブリック静的 IP を持つ Linux VPS（最小 1 GB RAM、10 GB ディスク、Ubuntu 24.04 LTS 推奨）
- ドメイン名（例：`api.raventag.com`）
- ドメイン DNS を管理する Cloudflare アカウント（無料）

---

## ステップ 1: サーバーの準備

SSH でサーバーに接続し、以下を実行します：

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

## ステップ 2: リポジトリのクローン

```bash
git clone https://github.com/ALENOC/RavenTag.git --depth=1
cd RavenTag
```

---

## ステップ 3: Docker secrets ディレクトリの作成

```bash
mkdir -p secrets
```

---

## ステップ 4: 秘密鍵の生成と保存

これらのコマンドを実行して、安全なランダムキーを生成します。**パスワードマネージャーに出力を保存してください** - これらのキーは最初の NFC チップがプログラムされた後に変更できません。

```bash
# 管理者 API キー（revocation、un-revoke、chip registration 用）
openssl rand -hex 24 > secrets/admin_key

# オペレーター API キー（chip registration のみ）
openssl rand -hex 24 > secrets/operator_key

# ブランドマスターキー（チップごとのキー派生用の AES-128）
openssl rand -hex 16 > secrets/brand_master_key

# ブランドソルト（nfc_pub_id 計算用）
openssl rand -hex 16 > secrets/brand_salt
```

**重要:** 各ファイルには改行なしで 16 進文字列のみを含めてください。上記のコマンドはこれを自動的に処理します。

---

## ステップ 5: Android アプリ証明書のフィンガープリントを取得

Android App Links を機能させるには、リリース APK の SHA-256 証明書フィンガープリントが必要です：

```bash
keytool -list -v -keystore android/signing/raventag-release.keystore -alias raventag | grep SHA256
```

フィンガープリントのみを抽出（コロンを削除、大文字）：
```
<YOUR_COLON_FINGERPRINT> → <YOUR_HEX_FINGERPRINT>
```

---

## ステップ 6: 非秘密環境変数の設定

非秘密設定用の `.env` ファイルを作成します：

```bash
cat > .env << 'EOF'
NODE_ENV=production
PORT=3001
DB_PATH=/data/raventag.db
# 公開 Ravencoin RPC エンドポイント（ローカルノードが利用できない場合のフォールバック）
RVN_PUBLIC_RPC_URL=https://rvn-rpc.publicnode.com
# 接続を許可された Android アプリのパッケージ名
ALLOWED_ORIGINS=io.raventag.app,io.raventag.app.brand
# メタデータ取得用 IPFS ゲートウェイ
IPFS_GATEWAY=https://ipfs.io/ipfs/
# Android App Links 証明書フィンガープリント（ステップ 5 から）
ANDROID_APP_FINGERPRINT=<YOUR_FINGERPRINT_HERE>
EOF
```

**重要:**
- `ANDROID_APP_FINGERPRINT` をステップ 5 の実際の証明書フィンガープリントに置き換えてください
- `ALLOWED_ORIGINS` には、バックエンドへの接続が許可された Android アプリのパッケージ名を含めてください
- `.env` ファイルをバージョン管理にコミットしないでください

---

## ステップ 7: バックエンドの起動

```bash
docker compose up -d backend
docker compose logs backend
```

実行されていることを確認します：

```bash
curl http://localhost:3001/health
# 期待値：{"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

Android App Links が設定されていることを確認します：

```bash
curl https://api.yourdomain.com/.well-known/assetlinks.json
# アプリのフィンガープリントを含む JSON が返されるはずです
```

---

## ステップ 8: nginx の設定

```bash
sudo nano /etc/nginx/sites-available/raventag
```

以下を貼り付けます：

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

有効化してリロード：

```bash
sudo ln -s /etc/nginx/sites-available/raventag /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

---

## ステップ 9: Cloudflare で DNS を設定

Cloudflare DNS パネルで、このレコードを追加します（現時点ではプロキシ OFF）：

| タイプ | 名前 | コンテンツ |
|------|------|---------|
| A | `api` | サーバーの IP |

DNS 伝播を待ちます（通常 Cloudflare では 2〜5 分）。

---

## ステップ 10: SSL 証明書の取得

```bash
sudo certbot --nginx -d api.yourdomain.com
```

certbot が証明書を自動的にインストールできない場合は、nginx の `server_name` を実際のドメインに更新して実行します：

```bash
sudo certbot install --cert-name api.yourdomain.com
```

---

## ステップ 11: Cloudflare DNS の更新

SSL 証明書を取得した後、Cloudflare の `api` レコードでプロキシ（オレンジの雲）を有効にします：

| タイプ | 名前 | コンテンツ | プロキシ |
|------|------|---------|-------|
| A | `api` | サーバーの IP | On (Proxied) |

Cloudflare で **SSL/TLS モードを Full (strict)** に設定します。

---

## ステップ 12: 最終確認

```bash
curl https://api.yourdomain.com/health
```

期待されるレスポンス：
```json
{"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

---

## ステップ 13: Android アプリの設定

バックエンドをデプロイした後、Android アプリがバックエンドに接続するように設定します：

1. RavenTag アプリを開く
2. 設定に移動
3. バックエンド URL を入力：`https://api.yourdomain.com`
4. 管理者またはオペレーター API キーを入力（`secrets/admin_key` または `secrets/operator_key` から）
5. 保存

アプリはすべての操作についてバックエンドに接続するようになります。

---

## デプロイの更新

新しいコードをプルしてバックエンドを再起動するには：

```bash
cd ~/RavenTag
git pull
docker compose down backend
docker compose up -d --build backend
```

---

## バックアップ

SQLite データベースはバックアップサービスによって毎日自動的にバックアップされます。バックアップは：
- 管理者キーを使用して AES-256-CBC で暗号化
- `raventag_backups` Docker ボリュームに保存
- 過去 7 日間保持

手動バックアップを実行するには：

```bash
docker run --rm -v raventag_raventag_data:/data -v $(pwd):/backup alpine \
  cp /data/raventag.db /backup/raventag_$(date +%Y%m%d).db
```

暗号化されたバックアップから復元するには：

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
  -pass file:secrets/admin_key \
  -in raventag_TIMESTAMP.db.enc \
  -out raventag.db
```

---

## API ドキュメント

バックエンドはこれらのエンドポイントを公開します：

### 公開エンドポイント
- `GET /health` - ヘルスチェック
- `POST /api/verify/tag` - 完全検証（SUN + ブロックチェーン + 失効）
- `GET /api/assets/:name/revocation` - 失効状態の確認
- `GET /.well-known/assetlinks.json` - Android App Links 検証

### オペレーターエンドポイント（X-Operator-Key 必須）
- `POST /api/brand/register-chip` - チップ UID を登録
- `GET /api/brand/chips` - すべてのチップの一覧
- `GET /api/brand/chip/:assetName` - 特定のチップを取得

### 管理者エンドポイント（X-Admin-Key 必須）
- `POST /api/brand/revoke` - アセットの失効
- `DELETE /api/brand/revoke/:name` - 失効の取り消し
- `GET /api/brand/revoked` - 失効済みアセットの一覧
- `POST /api/brand/derive-chip-key` - チップ AES キーの派生

完全な API ドキュメントについては、[protocol.md](../protocol.md) 仕様を参照してください。

---

## トラブルシューティング

### バックエンドが起動しない
```bash
docker compose logs backend
```

確認事項：
- `./secrets/` ディレクトリに秘密ファイルがあるか
- ポートが既に使用されていないか
- データベースパスの権限

### Android App Links が機能しない
```bash
curl https://api.yourdomain.com/.well-known/assetlinks.json
```

確認事項：
- `.env` に `ANDROID_APP_FINGERPRINT` が設定されているか
- フィンガープリントが大文字でコロンがないか
- フィンガープリント設定後にバックエンドが再起動されたか

### SSL 証明書の問題
```bash
sudo certbot certificates
sudo certbot renew --dry-run
```

### データベースの破損
```bash
docker compose down backend
docker run --rm -v raventag_raventag_data:/data alpine ls -la /data
```

---

## セキュリティベストプラクティス

1. **Docker secrets を使用**: すべての機密キーは `./secrets/` ファイルからロードされ、環境変数からはロードされません
2. **キーを秘密に保つ**: `.env` や `secrets/` をバージョン管理にコミットしないでください
3. **強力なキーを使用**: すべてのキーは少なくとも 32 文字の 16 進数である必要があります
4. **Cloudflare プロキシを有効化**: API エンドポイントは常に Cloudflare を通じてプロキシしてください
5. **定期的なバックアップ**: バックアップサービスは毎日自動的に実行されます
6. **ログを監視**: 定期的に `docker compose logs backend` を確認してください
7. **定期的に更新**: 毎月アップデートをプルしてデプロイしてください

---

## 秘密ファイルの形式

`./secrets/` 内の各秘密ファイルには、16 進文字列値のみを含めてください：

```bash
# 正しい（改行なし）：
echo -n "a1b2c3d4e5f6..." > secrets/admin_key

# または、これを自動的に処理する openssl を使用：
openssl rand -hex 24 > secrets/admin_key
```

Docker Compose はこれらのファイルを読み取り、コンテナ内の `/run/secrets/<name>` にマウントします。バックエンドは `<KEY>_FILE` 環境変数から読み取って、秘密を安全にロードします。

---

## 環境変数リファレンス

| 変数 | 必須 | 説明 |
|----------|----------|-------------|
| `NODE_ENV` | はい | `production` に設定 |
| `PORT` | はい | バックエンドポート（デフォルト：3001） |
| `DB_PATH` | はい | SQLite データベースパス（`/data/raventag.db`） |
| `RVN_PUBLIC_RPC_URL` | いいえ | 公開 Ravencoin RPC エンドポイント（フォールバック） |
| `ALLOWED_ORIGINS` | はい | カンマ区切りの Android アプリパッケージ名 |
| `IPFS_GATEWAY` | はい | IPFS ゲートウェイ URL |
| `ANDROID_APP_FINGERPRINT` | はい | Android App Links 用の SHA-256 証明書フィンガープリント |

---

**Copyright 2026 Alessandro Nocentini. All rights reserved.**
