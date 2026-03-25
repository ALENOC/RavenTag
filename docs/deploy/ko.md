# RavenTag 백엔드 배포 가이드

이 가이드는 **RavenTag 백엔드**의 완전한 프로덕션 배포를 다룹니다.

**참고:** RavenTag Android 앱 (Verify 및 Brand Manager) 은 백엔드 API 에 직접 연결됩니다. 프론트엔드 배포는 필요하지 않습니다.

---

## 아키텍처 개요

```
Android 앱 (Verify + Brand Manager)
              ↓
    귀사의 백엔드 API (이 가이드)
              ↓
    Ravencoin 네트워크 + IPFS
```

---

## 사전 요구사항

- 공개 정적 IP 를 가진 Linux VPS (최소 1 GB RAM, 10 GB 디스크, Ubuntu 24.04 LTS 권장)
- 도메인 이름 (예: `api.raventag.com`)
- 도메인 DNS 를 관리하는 Cloudflare 계정 (무료)

---

## 1 단계: 서버 준비

SSH 를 통해 서버에 연결하고 다음을 실행합니다:

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

## 2 단계: 리포지토리 클론

```bash
git clone https://github.com/ALENOC/RavenTag.git --depth=1
cd RavenTag
```

---

## 3 단계: Docker secrets 디렉토리 생성

```bash
mkdir -p secrets
```

---

## 4 단계: 비밀 키 생성 및 저장

이 명령을 실행하여 보안 무작위 키를 생성합니다. **비밀번호 관리자에 출력을 저장하십시오** - 이 키는 첫 번째 NFC 칩이 프로그래밍된 후 변경할 수 없습니다.

```bash
# 관리자 API 키 (취소, 취소 해제, 칩 등록용)
openssl rand -hex 24 > secrets/admin_key

# 운영자 API 키 (칩 등록만)
openssl rand -hex 24 > secrets/operator_key

# 브랜드 마스터 키 (칩별 키 파생용 AES-128)
openssl rand -hex 16 > secrets/brand_master_key

# 브랜드 솔트 (nfc_pub_id 계산용)
openssl rand -hex 16 > secrets/brand_salt
```

**중요:** 각 파일에는 따옴표 없는 16 진수 문자열만 포함되어야 합니다. 위의 명령은 이를 자동으로 처리합니다.

---

## 5 단계: Android 앱 인증서 지문 얻기

Android App Links 가 작동하려면 릴리스 APK 의 SHA-256 인증서 지문이 필요합니다:

```bash
keytool -list -v -keystore android/signing/raventag-release.keystore -alias raventag | grep SHA256
```

지문만 추출 (콜론 제거, 대문자):
```
<YOUR_COLON_FINGERPRINT> → <YOUR_HEX_FINGERPRINT>
```

---

## 6 단계: 비비밀 환경 변수 구성

비밀이 아닌 구성을 위한 `.env` 파일을 생성합니다:

```bash
cat > .env << 'EOF'
NODE_ENV=production
PORT=3001
DB_PATH=/data/raventag.db
# 공개 Ravencoin RPC 엔드포인트 (로컬 노드 사용 불가 시 폴백)
RVN_PUBLIC_RPC_URL=https://rvn-rpc.publicnode.com
# 연결 허용된 Android 앱 패키지 이름
ALLOWED_ORIGINS=io.raventag.app,io.raventag.app.brand
# 메타데이터 가져오기용 IPFS 게이트웨이
IPFS_GATEWAY=https://ipfs.io/ipfs/
# Android App Links 인증서 지문 (5 단계에서)
ANDROID_APP_FINGERPRINT=<YOUR_FINGERPRINT_HERE>
EOF
```

**중요:**
- `ANDROID_APP_FINGERPRINT` 를 5 단계의 실제 인증서 지문으로 교체하십시오
- `ALLOWED_ORIGINS` 에는 백엔드에 연결할 수 있는 Android 앱 패키지 이름이 포함되어야 합니다
- `.env` 파일을 버전 관리에 커밋하지 마십시오

---

## 7 단계: 백엔드 시작

```bash
docker compose up -d backend
docker compose logs backend
```

실행 중인지 확인합니다:

```bash
curl http://localhost:3001/health
# 예상: {"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

Android App Links 가 구성되어 있는지 확인합니다:

```bash
curl https://api.yourdomain.com/.well-known/assetlinks.json
# 앱 지문이 포함된 JSON 이 반환되어야 합니다
```

---

## 8 단계: nginx 구성

```bash
sudo nano /etc/nginx/sites-available/raventag
```

다음 붙여넣기:

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

활성화 및 다시 로드:

```bash
sudo ln -s /etc/nginx/sites-available/raventag /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

---

## 9 단계: Cloudflare 에서 DNS 구성

Cloudflare DNS 패널에서 이 레코드를 추가합니다 (현재 프록시 OFF):

| 유형 | 이름 | 콘텐츠 |
|------|------|---------|
| A | `api` | 서버 IP |

DNS 전파를 기다립니다 (일반적으로 Cloudflare 에서 2-5 분).

---

## 10 단계: SSL 인증서 얻기

```bash
sudo certbot --nginx -d api.yourdomain.com
```

certbot 이 인증서를 자동으로 설치할 수 없는 경우, nginx `server_name` 을 실제 도메인으로 업데이트하고 실행합니다:

```bash
sudo certbot install --cert-name api.yourdomain.com
```

---

## 11 단계: Cloudflare DNS 업데이트

SSL 인증서를 얻은 후, Cloudflare 에서 `api` 레코드에 프록시 (주황색 구름) 를 활성화합니다:

| 유형 | 이름 | 콘텐츠 | 프록시 |
|------|------|---------|-------|
| A | `api` | 서버 IP | On (Proxied) |

Cloudflare 에서 **SSL/TLS 모드를 Full (strict)** 으로 설정합니다.

---

## 12 단계: 최종 확인

```bash
curl https://api.yourdomain.com/health
```

예상 응답:
```json
{"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

---

## 13 단계: Android 앱 구성

백엔드를 배포한 후, Android 앱이 백엔드에 연결하도록 구성합니다:

1. RavenTag 앱 열기
2. 설정으로 이동
3. 백엔드 URL 입력: `https://api.yourdomain.com`
4. 관리자 또는 운영자 API 키 입력 (`secrets/admin_key` 또는 `secrets/operator_key` 에서)
5. 저장

이제 앱은 모든 작업을 위해 백엔드에 연결합니다.

---

## 배포 업데이트

새 코드를 풀하고 백엔드를 다시 시작하려면:

```bash
cd ~/RavenTag
git pull
docker compose down backend
docker compose up -d --build backend
```

---

## 백업

SQLite 데이터베이스는 백업 서비스에 의해 매일 자동으로 백업됩니다. 백업은:
- 관리자 키를 사용하여 AES-256-CBC 로 암호화됨
- `raventag_backups` Docker 볼륨에 저장됨
- 지난 7 일간 유지됨

수동 백업을 실행하려면:

```bash
docker run --rm -v raventag_raventag_data:/data -v $(pwd):/backup alpine \
  cp /data/raventag.db /backup/raventag_$(date +%Y%m%d).db
```

암호화된 백업에서 복원하려면:

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
  -pass file:secrets/admin_key \
  -in raventag_TIMESTAMP.db.enc \
  -out raventag.db
```

---

## API 문서

백엔드는 이러한 엔드포인트를 노출합니다:

### 공개 엔드포인트
- `GET /health` - 상태 확인
- `POST /api/verify/tag` - 전체 검증 (SUN + 블록체인 + 취소)
- `GET /api/assets/:name/revocation` - 취소 상태 확인
- `GET /.well-known/assetlinks.json` - Android App Links 검증

### 운영자 엔드포인트 (X-Operator-Key 필요)
- `POST /api/brand/register-chip` - 칩 UID 등록
- `GET /api/brand/chips` - 모든 칩 목록
- `GET /api/brand/chip/:assetName` - 특정 칩 가져오기

### 관리자 엔드포인트 (X-Admin-Key 필요)
- `POST /api/brand/revoke` - 자산 취소
- `DELETE /api/brand/revoke/:name` - 취소 해제
- `GET /api/brand/revoked` - 취소된 자산 목록
- `POST /api/brand/derive-chip-key` - 칩 AES 키 파생

완전한 API 문서는 [protocol.md](../protocol.md) 명세를 참조하십시오.

---

## 문제 해결

### 백엔드가 시작되지 않음
```bash
docker compose logs backend
```

확인 사항:
- `./secrets/` 디렉토리에 비밀 파일이 있는지
- 포트가 이미 사용 중인지
- 데이터베이스 경로 권한

### Android App Links 가 작동하지 않음
```bash
curl https://api.yourdomain.com/.well-known/assetlinks.json
```

확인 사항:
- `.env` 에 `ANDROID_APP_FINGERPRINT` 가 설정되었는지
- 지문이 대문자이고 콜론이 없는지
- 지문 설정 후 백엔드가 다시 시작되었는지

### SSL 인증서 문제
```bash
sudo certbot certificates
sudo certbot renew --dry-run
```

### 데이터베이스 손상
```bash
docker compose down backend
docker run --rm -v raventag_raventag_data:/data alpine ls -la /data
```

---

## 보안 모범 사례

1. **Docker secrets 사용**: 모든 민감한 키는 `./secrets/` 파일에서 로드되며 환경 변수에서는 로드되지 않습니다
2. **키를 비밀로 유지**: `.env` 또는 `secrets/` 를 버전 관리에 커밋하지 마십시오
3. **강력한 키 사용**: 모든 키는 최소 32 자의 16 진수여야 합니다
4. **Cloudflare 프록시 활성화**: API 엔드포인트를 항상 Cloudflare 를 통해 프록시하십시오
5. **정기 백업**: 백업 서비스는 매일 자동으로 실행됩니다
6. **로그 모니터링**: 정기적으로 `docker compose logs backend` 를 확인하십시오
7. **정기 업데이트**: 매월 업데이트를 풀하고 배포하십시오

---

## 비밀 파일 형식

`./secrets/` 의 각 비밀 파일에는 16 진수 문자열 값만 포함되어야 합니다:

```bash
# 올바름 (따옴표 없음):
echo -n "a1b2c3d4e5f6..." > secrets/admin_key

# 또는 이를 자동으로 처리하는 openssl 사용:
openssl rand -hex 24 > secrets/admin_key
```

Docker Compose 는 이러한 파일을 읽고 컨테이너 내부의 `/run/secrets/<name>` 에 마운트합니다. 백엔드는 `<KEY>_FILE` 환경 변수에서 읽어 비밀을 안전하게 로드합니다.

---

## 환경 변수 참조

| 변수 | 필수 | 설명 |
|----------|----------|-------------|
| `NODE_ENV` | 예 | `production` 으로 설정 |
| `PORT` | 예 | 백엔드 포트 (기본값: 3001) |
| `DB_PATH` | 예 | SQLite 데이터베이스 경로 (`/data/raventag.db`) |
| `RVN_PUBLIC_RPC_URL` | 아니오 | 공개 Ravencoin RPC 엔드포인트 (폴백) |
| `ALLOWED_ORIGINS` | 예 | 쉼표로 구분된 Android 앱 패키지 이름 |
| `IPFS_GATEWAY` | 예 | IPFS 게이트웨이 URL |
| `ANDROID_APP_FINGERPRINT` | 예 | Android App Links 용 SHA-256 인증서 지문 |

---

**Copyright 2026 Alessandro Nocentini. All rights reserved.**
