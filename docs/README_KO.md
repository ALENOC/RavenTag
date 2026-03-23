<p align="center">
  <img src="../pictures/RavenTag_Logo.jpg" width="380" alt="RavenTag"/>
</p>

# RavenTag

[![라이선스](https://img.shields.io/badge/라이선스-RTSL--1.0-orange.svg)](../LICENSE)
[![프로토콜](https://img.shields.io/badge/프로토콜-RTP--1-orange.svg)](protocol.md)
[![저작자표시 필요](https://img.shields.io/badge/저작자표시-필요-green.svg)](../LICENSE)
[![릴리스](https://img.shields.io/github/v/release/ALENOC/RavenTag)](https://github.com/ALENOC/RavenTag/releases/latest)

**다른 언어로 읽기:**
[🇬🇧 English](../README.md) |
[🇮🇹 Italiano](README_IT.md) |
[🇫🇷 Français](README_FR.md) |
[🇩🇪 Deutsch](README_DE.md) |
[🇪🇸 Español](README_ES.md) |
[🇨🇳 中文](README_ZH.md) |
[🇯🇵 日本語](README_JA.md) |
[🇷🇺 Русский](README_RU.md)

---

**RavenTag는 오픈소스 트러스트리스 위조방지 플랫폼입니다. RTP-1 프로토콜을 사용하여 NTAG 424 DNA NFC 칩을 Ravencoin 블록체인 자산과 연결하고, 브랜드가 중앙 기관에 의존하지 않고 실물 제품의 진위를 증명할 수 있게 합니다.**

## RTP-1 프로토콜

RTP-1(RavenTag Protocol v1) 은 IPFS 에 저장되고 Ravencoin 자산 메타데이터에서 참조되는 JSON 스키마를 정의합니다.

### 메타데이터 스키마 (v1.1)

```json
{
  "raventag_version": "RTP-1",
  "parent_asset": "FASHIONX",
  "sub_asset": "FASHIONX/BAG001",
  "variant_asset": "SN0001",
  "nfc_pub_id": "<sha256hex>",
  "crypto_type": "ntag424_sun",
  "algo": "aes-128",
  "image": "ipfs://<cid>",
  "description": "<제품 설명>"
}
```

**필드 설명**:
- `raventag_version`: 프로토콜 버전 (항상 "RTP-1")
- `parent_asset`: 루트 자산 이름 (예: "FASHIONX")
- `sub_asset`: 전체 서브 자산 경로 (예: "FASHIONX/BAG001")
- `variant_asset`: 고유 토큰 시리얼 (예: "SN0001") - 고유 토큰에만 존재
- `nfc_pub_id`: SHA-256(UID || BRAND_SALT) - 칩의 공개 지문
- `crypto_type`: NFC 암호화 유형 (NTAG 424 DNA 의 경우 항상 "ntag424_sun")
- `algo`: 암호화 알고리즘 (항상 "aes-128")
- `image`: 제품 이미지의 IPFS URI (선택 사항)
- `description`: 제품 설명 (선택 사항)


## RavenTag란?

위조품은 매년 브랜드와 소비자에게 수십억 달러의 피해를 줍니다. RavenTag는 세 가지 기술을 결합하여 이 문제를 해결합니다:

**1. 하드웨어 보안 NFC 칩 (NTAG 424 DNA)**
인증된 각 제품에는 NXP NTAG 424 DNA 칩이 내장되어 있습니다. 매번 탭할 때마다 칩은 AES-128-CMAC(SUN: Secure Unique NFC)를 사용하여 고유한 암호 서명 URL을 생성합니다. AES 키는 칩 실리콘에 저장되어 있어 추출하거나 복제할 수 없습니다.

**2. Ravencoin 블록체인**
브랜드는 각 일련번호 항목을 체인 상의 고유 토큰(예: `FASHIONX/BAG001#SN0001`)으로 등록합니다. 토큰 메타데이터(IPFS 저장)에는 칩 공개 지문이 포함됩니다: `nfc_pub_id = SHA-256(chip_uid || BRAND_SALT)`.

**3. 브랜드 주권적 키 관리**
AES 키는 브랜드 서버에서 `BRAND_MASTER_KEY`로부터 파생됩니다. 키는 절대 브랜드 서버를 벗어나지 않습니다.

## 왜 Ravencoin인가?

[Ravencoin](https://ravencoin.org)은 자산 발행을 위해 특별히 구축된 Bitcoin 포크:

- **네이티브 자산 프로토콜**: 루트 자산, 하위 자산, 고유 토큰이 1급 프로토콜 기능. 스마트 계약 불필요.
- **브랜드 구조에 적합한 자산 계층**: 브랜드(루트) / 제품(하위 자산) / 일련번호#태그(고유 토큰).
- **KAWPOW 작업증명**: ASIC 저항 알고리즘.
- **ICO 없음, 사전 채굴 없음**: 2018년 1월 공정하게 시작, 사전 할당 없음.
- **예측 가능한 낮은 수수료**: 루트 자산 500 RVN, 제품 라인 100 RVN, 직렬화 항목 5 RVN.

RTSL-1.0 라이선스는 모든 파생 작품이 모든 블록체인 작업에 Ravencoin만을 독점적으로 사용하도록 요구합니다.

## 4계층 위조방지

| 계층 | 기술 | 공격자가 필요한 것 |
|------|------|------------------|
| **1. Ravencoin 합의** | 모든 노드가 부모 자산 소유권 검증 | Ravencoin 부모 자산을 소유해야 함 |
| **2. nfc_pub_id 바인딩** | IPFS 메타데이터의 `SHA-256(UID \|\| BRAND_SALT)` | `BRAND_SALT` 알아야 함(비공개) |
| **3. AES 키 파생** | 칩별 `AES-ECB(BRAND_MASTER_KEY, [slot \|\| UID])` | `BRAND_MASTER_KEY` 알아야 함 |
| **4. NTAG 424 DNA 실리콘** | 복제 불가능한 NXP 하드웨어 | NXP 실리콘 물리적 복제(불가능) |

## 두 가지 Android 앱

### RavenTag Verify (소비자용 앱)

다운로드: [RavenTag-Verify-v1.3.8.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| 기능 | 세부사항 |
|---|---|
| NFC 태그 스캔 | NTAG 424 DNA 칩 탭, 정품 / 취소됨 결과 표시 |
| 완전한 검증 | SUN MAC + Ravencoin 블록체인 + 취소 확인 |
| Ravencoin 지갑 | BIP44 `m/44'/175'/0'/0/0`, BIP39 12단어 니모닉 |
| 다국어 | EN, IT, FR, DE, ES, ZH, JA, KO, RU |

### RavenTag Brand Manager (운영자용 앱)

다운로드: [RavenTag-Brand-v1.3.8.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| 기능 | 세부사항 |
|---|---|
| Ravencoin 자산 발행 | 루트(500 RVN), 하위 자산(100 RVN), 고유 토큰(5 RVN) |
| NTAG 424 DNA 칩 프로그래밍 | ISO 7816-4 APDU를 통한 AES-128 키 + SUN URL |
| HD 지갑 | BIP44, 로컬 UTXO 서명, BIP39 12단어 니모닉 |
| 전송 / 취소 / 소각 | 완전한 자산 수명주기 관리 |
| 제품 메타데이터 | Pinata를 통해 RTP-1 JSON을 IPFS에 업로드, CIDv0 해시를 온체인에서 참조 |

## 역할 및 접근 수준

역할은 지갑 생성 시 제어 키(관리자 또는 운영자)를 입력하여 결정됩니다. 앱은 백엔드에 대해 키를 검증하여 자동으로 역할을 결정합니다. 역할은 지갑에 고정되며 설정에서 변경할 수 없습니다.

| 역할 | 제어 키 | Android 앱 권한 |
|---|---|---|
| **관리자** | 관리자 키 (`X-Admin-Key`) | 모든 작업: 루트/서브 자산 발행, 고유 토큰 발행, 취소/복원, RVN 전송, 모든 자산 유형 이전 |
| **운영자** | 운영자 키 (`X-Operator-Key`) | 고유 토큰 발행만 가능. 루트/서브 자산 생성, 취소/복원, RVN 전송, 루트/서브 자산 이전은 잠금 상태. |

**사용 사례:** 브랜드 관리자는 소유자 자산이 포함된 동일한 지갑에서 여러 운영자 기기를 미리 구성할 수 있습니다. 각 운영자 기기는 자산 관리나 지갑에 접근하지 않고도 고유 토큰(일련번호)을 발행할 수 있습니다.

## NFC 탭 동작

**앱이 설치된 경우:** 태그 URL이 RavenTag Verify에 직접 가로채져 스캔 화면이 열리고 완전한 검증이 수행됩니다.

**앱이 설치되지 않은 경우:** 태그 URL이 브라우저에서 열리고 로고와 다운로드 링크가 있는 설치 안내 페이지가 표시됩니다.

## 프로젝트 구조

```
RavenTag/
├── backend/        Node.js + Express + SQLite
├── frontend/       Next.js 14 + Tailwind CSS
├── android/        Kotlin + Jetpack Compose
├── docs/           protocol.md, architecture.md, legal/, README_*.md
├── pictures/       로고 에셋
├── docker-compose.yml
└── LICENSE         RavenTag Source License (RTSL-1.0)
```

## 법적 문서

- [서비스 이용약관](legal/TERMS_OF_SERVICE.md)
- [개인정보 처리방침](legal/PRIVACY_POLICY.md)

법적 문의: legal@raventag.com

## 저작자표시 (RTSL-1.0)

RavenTag는 **RavenTag Source License (RTSL-1.0)** 하에 배포됩니다. 전문: [LICENSE](../LICENSE).

[![라이선스](https://img.shields.io/badge/라이선스-RTSL--1.0-orange.svg)](../LICENSE)
