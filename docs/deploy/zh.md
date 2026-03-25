# RavenTag 后端部署指南

本指南涵盖 **RavenTag 后端**的完整生产部署。

**注意：** RavenTag Android 应用（Verify 和 Brand Manager）直接连接到您的后端 API。无需前端部署。

---

## 架构概述

```
Android 应用（Verify + Brand Manager）
              ↓
    您的后端 API（本指南）
              ↓
    Ravencoin 网络 + IPFS
```

---

## 前提条件

- 具有公共静态 IP 的 Linux VPS（至少 1 GB RAM，10 GB 磁盘，推荐 Ubuntu 24.04 LTS）
- 域名（例如 `api.raventag.com`）
- 管理域名 DNS 的 Cloudflare 账户（免费）

---

## 步骤 1：准备服务器

通过 SSH 连接到服务器并运行：

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

## 步骤 2：克隆仓库

```bash
git clone https://github.com/ALENOC/RavenTag.git --depth=1
cd RavenTag
```

---

## 步骤 3：创建 Docker secrets 目录

```bash
mkdir -p secrets
```

---

## 步骤 4：生成和存储密钥

运行这些命令生成安全随机密钥。**将输出保存在密码管理器中** - 这些密钥在第一个 NFC 芯片编程后无法更改。

```bash
# 管理员 API 密钥（用于撤销、取消撤销、芯片注册）
openssl rand -hex 24 > secrets/admin_key

# 操作员 API 密钥（仅用于芯片注册）
openssl rand -hex 24 > secrets/operator_key

# 品牌主密钥（用于每芯片密钥派生的 AES-128）
openssl rand -hex 16 > secrets/brand_master_key

# 品牌盐（用于 nfc_pub_id 计算）
openssl rand -hex 16 > secrets/brand_salt
```

**重要：** 每个文件应仅包含十六进制字符串，无换行符。上述命令会自动处理。

---

## 步骤 5：获取 Android 应用证书指纹

要使 Android App Links 工作，您需要发布 APK 的 SHA-256 证书指纹：

```bash
keytool -list -v -keystore android/signing/raventag-release.keystore -alias raventag | grep SHA256
```

仅提取指纹（移除冒号，大写）：
```
<YOUR_COLON_FINGERPRINT> → <YOUR_HEX_FINGERPRINT>
```

**当前指纹（新密钥库 2026-03-25）：**
```
3E:A5:B9:F3:75:63:1A:4E:1D:E9:5D:E1:DA:9C:22:45:14:1E:4A:D8:FA:7A:63:78:7D:6A:B9:81:96:B4:A3:BE
```

无冒号（用于 `.env`）：
```
3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE
```

---

## 步骤 6：配置非秘密环境变量

为non-secret配置创建 `.env` 文件：

```bash
cat > .env << 'EOF'
NODE_ENV=production
PORT=3001
DB_PATH=/data/raventag.db
# 公共 Ravencoin RPC 端点（当本地节点不可用时的回退）
RVN_PUBLIC_RPC_URL=https://rvn-rpc.publicnode.com
# 允许连接的 Android 应用包名
ALLOWED_ORIGINS=io.raventag.app,io.raventag.app.brand
# 用于元数据获取的 IPFS 网关
IPFS_GATEWAY=https://ipfs.io/ipfs/
# Android App Links 证书指纹（来自步骤 5）
ANDROID_APP_FINGERPRINT=<YOUR_FINGERPRINT_HERE>
EOF
```

**重要：**
- 将 `ANDROID_APP_FINGERPRINT` 替换为步骤 5 中的实际证书指纹
- `ALLOWED_ORIGINS` 应包含允许连接到后端的 Android 应用包名
- 切勿将 `.env` 文件提交到版本控制

---

## 步骤 7：启动后端

```bash
docker compose up -d backend
docker compose logs backend
```

验证其正在运行：

```bash
curl http://localhost:3001/health
# 预期：{"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

验证 Android App Links 已配置：

```bash
curl https://api.yourdomain.com/.well-known/assetlinks.json
# 应返回包含应用指纹的 JSON
```

---

## 步骤 8：配置 nginx

```bash
sudo nano /etc/nginx/sites-available/raventag
```

粘贴：

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

启用并重新加载：

```bash
sudo ln -s /etc/nginx/sites-available/raventag /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

---

## 步骤 9：在 Cloudflare 上配置 DNS

在 Cloudflare DNS 面板中，添加此记录（暂时关闭代理）：

| 类型 | 名称 | 内容 |
|------|------|------|
| A | `api` | 服务器 IP |

等待 DNS 传播（通常 Cloudflare 为 2-5 分钟）。

---

## 步骤 10：获取 SSL 证书

```bash
sudo certbot --nginx -d api.yourdomain.com
```

如果 certbot 无法自动安装证书，请用实际域名更新 nginx `server_name` 并运行：

```bash
sudo certbot install --cert-name api.yourdomain.com
```

---

## 步骤 11：更新 Cloudflare DNS

获得 SSL 证书后，在 Cloudflare 的 `api` 记录上启用代理（橙色云）：

| 类型 | 名称 | 内容 | 代理 |
|------|------|------|------|
| A | `api` | 服务器 IP | On (Proxied) |

在 Cloudflare 中将 **SSL/TLS 模式设置为 Full (strict)**。

---

## 步骤 12：最终验证

```bash
curl https://api.yourdomain.com/health
```

预期响应：
```json
{"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

---

## 步骤 13：配置 Android 应用

部署后端后，配置 Android 应用连接到您的后端：

1. 打开 RavenTag 应用
2. 进入设置
3. 输入您的后端 URL：`https://api.yourdomain.com`
4. 输入您的管理员或操作员 API 密钥（来自 `secrets/admin_key` 或 `secrets/operator_key`）
5. 保存

应用现在将为所有操作连接到您的后端。

---

## 更新部署

拉取新代码并重启后端：

```bash
cd ~/RavenTag
git pull
docker compose down backend
docker compose up -d --build backend
```

---

## 备份

SQLite 数据库由备份服务每天自动备份。备份：
- 使用管理员密钥通过 AES-256-CBC 加密
- 存储在 `raventag_backups` Docker 卷中
- 保留最近 7 天

手动备份：

```bash
docker run --rm -v raventag_raventag_data:/data -v $(pwd):/backup alpine \
  cp /data/raventag.db /backup/raventag_$(date +%Y%m%d).db
```

从加密备份恢复：

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
  -pass file:secrets/admin_key \
  -in raventag_TIMESTAMP.db.enc \
  -out raventag.db
```

---

## API 文档

您的后端公开这些端点：

### 公共端点
- `GET /health` - 健康检查
- `POST /api/verify/tag` - 完整验证（SUN + 区块链 + 撤销）
- `GET /api/assets/:name/revocation` - 检查撤销状态
- `GET /.well-known/assetlinks.json` - Android App Links 验证

### 操作员端点（需要 X-Operator-Key）
- `POST /api/brand/register-chip` - 注册芯片 UID
- `GET /api/brand/chips` - 列出所有芯片
- `GET /api/brand/chip/:assetName` - 获取特定芯片

### 管理员端点（需要 X-Admin-Key）
- `POST /api/brand/revoke` - 撤销资产
- `DELETE /api/brand/revoke/:name` - 取消撤销
- `GET /api/brand/revoked` - 列出已撤销资产
- `POST /api/brand/derive-chip-key` - 派生芯片 AES 密钥

完整 API 文档请参阅 [protocol.md](../protocol.md) 规范。

---

## 故障排除

### 后端无法启动
```bash
docker compose logs backend
```

检查：
- `./secrets/` 目录中是否有秘密文件
- 端口是否已被使用
- 数据库路径权限

### Android App Links 不工作
```bash
curl https://api.yourdomain.com/.well-known/assetlinks.json
```

确保：
- `.env` 中已设置 `ANDROID_APP_FINGERPRINT`
- 指纹为大写，无冒号
- 设置指纹后后端已重启

### SSL 证书问题
```bash
sudo certbot certificates
sudo certbot renew --dry-run
```

### 数据库损坏
```bash
docker compose down backend
docker run --rm -v raventag_raventag_data:/data alpine ls -la /data
```

---

## 安全最佳实践

1. **使用 Docker secrets**：所有敏感密钥从 `./secrets/` 文件加载，从不从环境变量加载
2. **保密密钥**：切勿将 `.env` 或 `secrets/` 提交到版本控制
3. **使用强密钥**：所有密钥应至少为 32 个十六进制字符
4. **启用 Cloudflare 代理**：始终通过 Cloudflare 代理您的 API 端点
5. **定期备份**：备份服务每天自动运行
6. **监控日志**：定期检查 `docker compose logs backend`
7. **定期更新**：每月拉取和部署更新

---

## 秘密文件格式

`./secrets/` 中的每个秘密文件应仅包含十六进制字符串值：

```bash
# 正确（无换行符）：
echo -n "a1b2c3d4e5f6..." > secrets/admin_key

# 或使用自动处理此问题的 openssl：
openssl rand -hex 24 > secrets/admin_key
```

Docker Compose 读取这些文件并将它们挂载到容器内的 `/run/secrets/<name>`。后端从 `<KEY>_FILE` 环境变量读取以安全加载秘密。

---

## 环境变量参考

| 变量 | 必需 | 描述 |
|----------|----------|-------------|
| `NODE_ENV` | 是 | 设置为 `production` |
| `PORT` | 是 | 后端端口（默认：3001） |
| `DB_PATH` | 是 | SQLite 数据库路径（`/data/raventag.db`） |
| `RVN_PUBLIC_RPC_URL` | 否 | 公共 Ravencoin RPC 端点（回退） |
| `ALLOWED_ORIGINS` | 是 | 逗号分隔的 Android 应用包名 |
| `IPFS_GATEWAY` | 是 | IPFS 网关 URL |
| `ANDROID_APP_FINGERPRINT` | 是 | Android App Links 的 SHA-256 证书指纹 |

---

**Copyright 2026 Alessandro Nocentini. All rights reserved.**
