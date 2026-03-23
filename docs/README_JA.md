<p align="center">
  <img src="../pictures/RavenTag_Logo.jpg" width="380" alt="RavenTag"/>
</p>

# RavenTag

[![ライセンス](https://img.shields.io/badge/ライセンス-RTSL--1.0-orange.svg)](../LICENSE)
[![プロトコル](https://img.shields.io/badge/プロトコル-RTP--1-orange.svg)](protocol.md)
[![帰属表示必須](https://img.shields.io/badge/帰属表示-必須-green.svg)](../LICENSE)
[![リリース](https://img.shields.io/github/v/release/ALENOC/RavenTag)](https://github.com/ALENOC/RavenTag/releases/latest)

**他の言語で読む:**
[🇬🇧 English](../README.md) |
[🇮🇹 Italiano](README_IT.md) |
[🇫🇷 Français](README_FR.md) |
[🇩🇪 Deutsch](README_DE.md) |
[🇪🇸 Español](README_ES.md) |
[🇨🇳 中文](README_ZH.md) |
[🇰🇷 한국어](README_KO.md) |
[🇷🇺 Русский](README_RU.md)

---

**RavenTag はオープンソースのトラストレス偽造防止プラットフォームです。RTP-1 プロトコルを使用して NTAG 424 DNA NFC チップを Ravencoin ブロックチェーンアセットに紐付け、ブランドが中央機関に依存することなく物理製品の真正性を証明できるようにします。**

## RTP-1 プロトコル

RTP-1 (RavenTag Protocol v1) は、IPFS に保存され Ravencoin アセットメタデータによって参照される JSON スキーマを定義します。

### メタデータスキーマ (v1.1)

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
  "description": "<製品説明>"
}
```

**フィールドの説明**:
- `raventag_version`: プロトコルバージョン (常に "RTP-1")
- `parent_asset`: ルートアセット名 (例："FASHIONX")
- `sub_asset`: 完全なサブアセットパス (例："FASHIONX/BAG001")
- `variant_asset`: ユニークトークンのシリアル (例："SN0001") - ユニークトークンのみに存在
- `nfc_pub_id`: SHA-256(UID || BRAND_SALT) - チップの公開フィンガープリント
- `crypto_type`: NFC 暗号タイプ (NTAG 424 DNA の場合常に "ntag424_sun")
- `algo`: 暗号化アルゴリズム (常に "aes-128")
- `image`: 製品画像の IPFS URI (オプション)
- `description`: 製品説明 (オプション)


## RavenTag とは?

偽造品は毎年ブランドと消費者に数十億ドルの損害を与えています。RavenTag は三つの技術を組み合わせてこの問題を解決します:

**1. ハードウェアセキュア NFC チップ (NTAG 424 DNA)**
認証された各製品には NXP NTAG 424 DNA チップが搭載されています。毎回のタップで、チップは AES-128-CMAC (SUN: Secure Unique NFC) を使用して一意の暗号署名付き URL を生成します。AES キーはチップのシリコンに格納されており、抽出やクローンは不可能です。

**2. Ravencoin ブロックチェーン**
ブランドは各シリアル化アイテムをチェーン上のユニークトークン（例: `FASHIONX/BAG001#SN0001`）として登録します。トークンメタデータ（IPFS 上）にはチップの公開フィンガープリントが含まれます: `nfc_pub_id = SHA-256(chip_uid || BRAND_SALT)`。

**3. ブランド主権型鍵管理**
AES キーはブランドサーバー上で `BRAND_MASTER_KEY` から派生されます。鍵はブランドサーバーを離れることはありません。

## なぜ Ravencoin?

[Ravencoin](https://ravencoin.org) はアセット発行のために特別に構築された Bitcoin フォーク:

- **ネイティブアセットプロトコル**: ルートアセット、サブアセット、ユニークトークンは第一級のプロトコル機能。スマートコントラクト不要。
- **ブランド構造に合ったアセット階層**: ブランド（ルート）/ 製品（サブアセット）/ シリアル番号#タグ（ユニークトークン）。
- **KAWPOW プルーフオブワーク**: ASIC 耐性アルゴリズム。
- **ICO なし、プレマインなし**: 2018 年 1 月に公平に開始、ゼロ事前割り当て。
- **予測可能な低手数料**: ルートアセット 500 RVN、製品ライン 100 RVN、シリアル化アイテム 5 RVN。

RTSL-1.0 ライセンスは、すべての派生作品が全ブロックチェーン操作に Ravencoin を排他的に使用することを要求します。

## 四層の偽造防止

| 層 | 技術 | 攻撃者が必要とするもの |
|----|------|----------------------|
| **1. Ravencoin コンセンサス** | 各ノードが親アセットの所有権を検証 | Ravencoin 親アセットを所有する必要あり |
| **2. nfc_pub_id バインディング** | IPFS メタデータの `SHA-256(UID \|\| BRAND_SALT)` | `BRAND_SALT` を知る必要あり（非公開）|
| **3. AES 鍵派生** | チップごとの `AES-ECB(BRAND_MASTER_KEY, [slot \|\| UID])` | `BRAND_MASTER_KEY` を知る必要あり |
| **4. NTAG 424 DNA シリコン** | クローン不可能な NXP ハードウェア | NXP シリコンを物理的にクローン（不可能）|

## 二つの Android アプリ

### RavenTag Verify（消費者向けアプリ）

ダウンロード: [RavenTag-Verify-v1.3.8.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| 機能 | 詳細 |
|---|---|
| NFC タグのスキャン | NTAG 424 DNA チップをタップ、本物 / 無効化済み の結果表示 |
| 完全な検証 | SUN MAC + Ravencoin ブロックチェーン + 失効チェック |
| Ravencoin ウォレット | BIP44 `m/44'/175'/0'/0/0`、BIP39 12 ワードニーモニック |
| 多言語対応 | EN, IT, FR, DE, ES, ZH, JA, KO, RU |

### RavenTag Brand Manager（事業者向けアプリ）

ダウンロード: [RavenTag-Brand-v1.3.8.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| 機能 | 詳細 |
|---|---|
| Ravencoin アセット発行 | ルート (500 RVN)、サブアセット (100 RVN)、ユニークトークン (5 RVN) |
| NTAG 424 DNA チップのプログラミング | ISO 7816-4 APDU 経由の AES-128 鍵 + SUN URL |
| HD ウォレット | BIP44、ローカル UTXO 署名、BIP39 12 ワードニーモニック |
| 転送 / 失効 / バーン | 完全なアセットライフサイクル管理 |
| 製品メタデータ | Pinata 経由で RTP-1 JSON を IPFS にアップロード、CIDv0 ハッシュをオンチェーンで参照 |

## ロールとアクセスレベル

ロールはウォレット作成時にコントロールキー（管理者またはオペレーター）を入力することで決定されます。アプリはバックエンドに対してキーを検証することで自動的にロールを判断します。ロールはウォレットに紐付けられ、設定から変更することはできません。

| ロール | コントロールキー | Android アプリの権限 |
|---|---|---|
| **管理者** | 管理者キー (`X-Admin-Key`) | すべての操作: ルート/サブ資産の発行、ユニークトークンの発行、失効/復元、RVN 送信、すべての資産タイプの転送 |
| **オペレーター** | オペレーターキー (`X-Operator-Key`) | ユニークトークンの発行のみ。ルート/サブ資産の作成、失効/復元、RVN 送信、ルート/サブ資産の転送はロックされています。 |

**ユースケース:** ブランド管理者はオーナー資産を保有する同一ウォレットに複数のオペレーターデバイスを事前設定できます。各オペレーターデバイスは資産管理やウォレットにアクセスすることなくユニークトークン（シリアル番号）を発行できます。

## NFC タップの動作

**アプリがインストールされている場合:** タグ URL が直接 RavenTag Verify にインターセプトされ、スキャン画面が開いて完全な検証が実行されます。

**アプリがインストールされていない場合:** タグ URL がブラウザで開き、ロゴとダウンロードリンク付きのインストール案内ページが表示されます。

## プロジェクト構造

```
RavenTag/
├── backend/        Node.js + Express + SQLite
├── frontend/       Next.js 14 + Tailwind CSS
├── android/        Kotlin + Jetpack Compose
├── docs/           protocol.md, architecture.md, legal/, README_*.md
├── pictures/       ロゴアセット
├── docker-compose.yml
└── LICENSE         RavenTag Source License (RTSL-1.0)
```

## 法的文書

- [利用規約](legal/TERMS_OF_SERVICE.md)
- [プライバシーポリシー](legal/PRIVACY_POLICY.md)

法的お問い合わせ: legal@raventag.com

## 帰属表示 (RTSL-1.0)

RavenTag は **RavenTag Source License (RTSL-1.0)** の下で公開されています。全文: [LICENSE](../LICENSE)。

[![ライセンス](https://img.shields.io/badge/ライセンス-RTSL--1.0-orange.svg)](../LICENSE)
