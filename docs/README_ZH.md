<p align="center">
  <img src="../pictures/RavenTag_Logo.jpg" width="380" alt="RavenTag"/>
</p>

# RavenTag

[![许可证](https://img.shields.io/badge/许可证-RTSL--1.0-orange.svg)](../LICENSE)
[![协议](https://img.shields.io/badge/协议-RTP--1-orange.svg)](protocol.md)
[![需要署名](https://img.shields.io/badge/署名-必须-green.svg)](../LICENSE)
[![发布](https://img.shields.io/github/v/release/ALENOC/RavenTag)](https://github.com/ALENOC/RavenTag/releases/latest)

**其他语言阅读:**
[🇬🇧 English](../README.md) |
[🇮🇹 Italiano](README_IT.md) |
[🇫🇷 Français](README_FR.md) |
[🇩🇪 Deutsch](README_DE.md) |
[🇪🇸 Español](README_ES.md) |
[🇯🇵 日本語](README_JA.md) |
[🇰🇷 한국어](README_KO.md) |
[🇷🇺 Русский](README_RU.md)

---

**RavenTag 是一个开源的去信任防伪平台。它使用 RTP-1 协议将 NTAG 424 DNA NFC 芯片与 Ravencoin 区块链资产相关联，使品牌无需依赖任何中央机构即可证明实体产品的真实性。**

## RTP-1 协议

RTP-1 (RavenTag Protocol v1) 定义了存储在 IPFS 上并被 Ravencoin 资产元数据引用的 JSON 模式。

### 元数据模式 (v1.1)

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
  "description": "<产品描述>"
}
```

**字段说明**:
- `raventag_version`: 协议版本（始终为 "RTP-1"）
- `parent_asset`: 主资产名称（例如 "FASHIONX"）
- `sub_asset`: 完整子资产路径（例如 "FASHIONX/BAG001"）
- `variant_asset`: 唯一代币序列号（例如 "SN0001"）- 仅存在于唯一代币
- `nfc_pub_id`: SHA-256(UID || BRAND_SALT) - 芯片公共指纹
- `crypto_type`: NFC 加密类型（对于 NTAG 424 DNA 始终为 "ntag424_sun"）
- `algo`: 加密算法（始终为 "aes-128"）
- `image`: 产品图片的 IPFS URI（可选）
- `description`: 产品描述（可选）


## 什么是 RavenTag?

仿冒品每年给品牌和消费者造成数十亿损失。RavenTag 通过三种组合技术解决这一问题:

**1. 硬件安全 NFC 芯片 (NTAG 424 DNA)**
每个经过认证的产品都携带一枚 NXP NTAG 424 DNA 芯片。每次感应时，芯片通过 AES-128-CMAC（SUN: 安全唯一 NFC）生成唯一的密码签名 URL。AES 密钥存储在芯片硅片中，无法提取或克隆。

**2. Ravencoin 区块链**
品牌将每个序列化商品注册为唯一的链上代币，例如 `FASHIONX/BAG001#SN0001`。代币元数据（存储在 IPFS 上）包含芯片公共指纹: `nfc_pub_id = SHA-256(chip_uid || BRAND_SALT)`。

**3. 品牌主权密钥管理**
AES 密钥从 `BRAND_MASTER_KEY` 在品牌服务器上派生。密钥永远不离开品牌服务器。

## 为什么选择 Ravencoin?

[Ravencoin](https://ravencoin.org) 是专为资产发行而构建的比特币分支:

- **原生资产协议**: 根资产、子资产和唯一代币是一级协议功能，无需智能合约。
- **资产层次结构符合品牌结构**: 品牌（根）/ 产品（子资产）/ 序列号#标签（唯一代币）。
- **KAWPOW 工作量证明**: 抗 ASIC 算法保持挖矿去中心化。
- **无 ICO、无预挖**: Ravencoin 于 2018 年 1 月公平启动，零预分配。
- **可预测的低费用**: 根资产 500 RVN，每条产品线 100 RVN，每个序列化商品 5 RVN。

RTSL-1.0 许可证要求所有衍生作品在所有区块链操作中专用 Ravencoin。

## 四层防伪保护

| 层级 | 技术 | 攻击者需要什么 |
|------|------|---------------|
| **1. Ravencoin 共识** | 每个节点验证父资产所有权 | 必须拥有 Ravencoin 父资产 |
| **2. nfc_pub_id 绑定** | `SHA-256(UID \|\| BRAND_SALT)` 在 IPFS 元数据中 | 必须知道 `BRAND_SALT`（从不公开）|
| **3. AES 密钥派生** | 每芯片 `AES-ECB(BRAND_MASTER_KEY, [slot \|\| UID])` | 必须知道 `BRAND_MASTER_KEY` |
| **4. NTAG 424 DNA 硅片** | 不可克隆的 NXP 硬件 | 必须物理克隆 NXP 硅片（不可能）|

## 两款 Android 应用

### RavenTag Verify（消费者应用）

下载: [RavenTag-Verify-v1.3.8.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| 功能 | 详情 |
|---|---|
| 扫描 NFC 标签 | 感应 NTAG 424 DNA 芯片，显示真实 / 已撤销结果 |
| 完整验证 | SUN MAC + Ravencoin 区块链 + 撤销检查 |
| Ravencoin 钱包 | BIP44 `m/44'/175'/0'/0/0`，BIP39 12 词助记词 |
| 多语言 | EN, IT, FR, DE, ES, ZH, JA, KO, RU |

### RavenTag Brand Manager（运营者应用）

下载: [RavenTag-Brand-v1.3.8.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| 功能 | 详情 |
|---|---|
| 发行 Ravencoin 资产 | 根资产（500 RVN）、子资产（100 RVN）、唯一代币（5 RVN）|
| 编程 NTAG 424 DNA 芯片 | AES-128 密钥 + SUN URL，通过 ISO 7816-4 APDU |
| HD 钱包 | BIP44，本地 UTXO 签名，BIP39 12 词助记词 |
| 转让 / 撤销 / 销毁 | 完整的资产生命周期管理 |
| 产品元数据 | 通过 Pinata 将 RTP-1 JSON 上传至 IPFS，CIDv0 哈希在链上引用 |

## 角色与访问级别

角色在创建钱包时通过输入控制密钥（管理员或操作员）来确定。应用通过验证后端密钥自动判断角色。角色与钱包绑定，无法在设置中更改。

| 角色 | 控制密钥 | Android 应用权限 |
|---|---|---|
| **管理员** | 管理员密钥 (`X-Admin-Key`) | 所有操作: 发行根资产/子资产、发行唯一代币、撤销/恢复、发送 RVN、转移所有类型资产 |
| **操作员** | 操作员密钥 (`X-Operator-Key`) | 仅限发行唯一代币。创建根资产/子资产、撤销/恢复、发送 RVN 和转移根资产/子资产均被锁定。 |

**使用场景:** 品牌管理员可以在持有所有者资产的同一钱包上预配置多个操作员设备。每个操作员设备可以发行唯一代币（序列号），无需访问资产管理或钱包。

## NFC 感应行为

**已安装应用:** 标签 URL 直接被 RavenTag Verify 拦截，应用打开扫描界面并执行完整验证。

**未安装应用:** 标签 URL 在浏览器中打开，显示带有 Logo 和下载链接的安装引导页面。

## 项目结构

```
RavenTag/
├── backend/        Node.js + Express + SQLite
├── frontend/       Next.js 14 + Tailwind CSS
├── android/        Kotlin + Jetpack Compose
├── docs/           protocol.md, architecture.md, legal/, README_*.md
├── pictures/       Logo 资源
├── docker-compose.yml
└── LICENSE         RavenTag Source License (RTSL-1.0)
```

## 法律文件

- [服务条款](legal/TERMS_OF_SERVICE.md)
- [隐私政策](legal/PRIVACY_POLICY.md)

法律咨询: legal@raventag.com

## 署名 (RTSL-1.0)

RavenTag 在 **RavenTag Source License (RTSL-1.0)** 下发布。完整文本见 [LICENSE](../LICENSE)。

[![许可证](https://img.shields.io/badge/许可证-RTSL--1.0-orange.svg)](../LICENSE)
