# Guía de Despliegue del Backend de RavenTag

Esta guía cubre el despliegue completo en producción del **Backend de RavenTag únicamente**.

**Nota:** Las aplicaciones Android de RavenTag (Verify y Brand Manager) se conectan directamente a tu API backend. No se requiere ningún despliegue de frontend.

---

## Descripción General de la Arquitectura

```
Aplicaciones Android (Verify + Brand Manager)
              ↓
    Tu API Backend (esta guía)
              ↓
    Red Ravencoin + IPFS
```

---

## Requisitos Previos

- Un VPS Linux con IP pública estática (mínimo 1 GB RAM, 10 GB disco, Ubuntu 24.04 LTS recomendado)
- Un nombre de dominio (ej. `api.raventag.com`)
- Una cuenta de Cloudflare (gratuita) gestionando el DNS de tu dominio

---

## Paso 1: Preparar el servidor

Conéctate a tu servidor vía SSH y ejecuta:

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

## Paso 2: Clonar el repositorio

```bash
git clone https://github.com/ALENOC/RavenTag.git --depth=1
cd RavenTag
```

---

## Paso 3: Crear el directorio de secrets de Docker

```bash
mkdir -p secrets
```

---

## Paso 4: Generar y almacenar las claves secretas

Ejecuta estos comandos para generar claves aleatorias seguras. **Guarda la salida en un gestor de contraseñas** - estas claves no pueden cambiarse después de que el primer chip NFC ha sido programado.

```bash
# Clave API Admin (para revocación, anular revocación, registro de chip)
openssl rand -hex 24 > secrets/admin_key

# Clave API Operator (solo registro de chip)
openssl rand -hex 24 > secrets/operator_key

# Clave master de la marca (AES-128 para derivación de claves por chip)
openssl rand -hex 16 > secrets/brand_master_key

# Sal de la marca (para cálculo nfc_pub_id)
openssl rand -hex 16 > secrets/brand_salt
```

**Importante:** Cada archivo debe contener ÚNICAMENTE la cadena hex, sin salto de línea. Los comandos anteriores manejan esto automáticamente.

---

## Paso 5: Obtener la huella del certificado de la aplicación Android

Para que Android App Links funcione, necesitas la huella SHA-256 del certificado de tu APK de producción:

```bash
keytool -list -v -keystore android/signing/raventag-release.keystore -alias raventag | grep SHA256
```

Extrae solo la huella (elimina los dos puntos, mayúsculas):
```
<YOUR_COLON_FINGERPRINT> → <YOUR_HEX_FINGERPRINT>
```

**Huella actual (nuevo keystore 2026-03-25):**
```
3E:A5:B9:F3:75:63:1A:4E:1D:E9:5D:E1:DA:9C:22:45:14:1E:4A:D8:FA:7A:63:78:7D:6A:B9:81:96:B4:A3:BE
```

Sin dos puntos (para `.env`):
```
3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE
```

---

## Paso 6: Configurar las variables de entorno no secretas

Crea un archivo `.env` para configuración no secreta:

```bash
cat > .env << 'EOF'
NODE_ENV=production
PORT=3001
DB_PATH=/data/raventag.db
# Endpoint RPC público Ravencoin (fallback cuando nodo local no disponible)
RVN_PUBLIC_RPC_URL=https://rvn-rpc.publicnode.com
# Nombres de paquete de aplicaciones Android autorizadas para conectarse
ALLOWED_ORIGINS=io.raventag.app,io.raventag.app.brand
# Puerta de enlace IPFS para recuperación de metadatos
IPFS_GATEWAY=https://ipfs.io/ipfs/
# Huella del certificado Android App Links (del Paso 5)
ANDROID_APP_FINGERPRINT=<TU_HUELLA_AQUI>
EOF
```

**Importante:**
- Reemplaza `ANDROID_APP_FINGERPRINT` con tu huella de certificado real del Paso 5
- `ALLOWED_ORIGINS` debe contener los nombres de paquete de las aplicaciones Android autorizadas para conectarse a tu backend
- Nunca hagas commit del archivo `.env` en el control de versiones

---

## Paso 7: Iniciar el backend

```bash
docker compose up -d backend
docker compose logs backend
```

Verifica que esté en ejecución:

```bash
curl http://localhost:3001/health
# Esperado: {"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

Verifica que Android App Links estén configurados:

```bash
curl https://api.tudominio.com/.well-known/assetlinks.json
# Debería devolver JSON con tu huella de aplicación
```

---

## Paso 8: Configurar nginx

```bash
sudo nano /etc/nginx/sites-available/raventag
```

Pega:

```nginx
server {
    listen 80;
    server_name api.tudominio.com;

    location / {
        proxy_pass http://127.0.0.1:3001;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Activa y recarga:

```bash
sudo ln -s /etc/nginx/sites-available/raventag /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

---

## Paso 9: Configurar DNS en Cloudflare

En el panel DNS de Cloudflare, añade este registro (proxy OFF por ahora):

| Tipo | Nombre | Contenido |
|------|------|-----------|
| A | `api` | IP de tu servidor |

Espera la propagación DNS (generalmente 2-5 minutos con Cloudflare).

---

## Paso 10: Obtener el certificado SSL

```bash
sudo certbot --nginx -d api.tudominio.com
```

Si certbot no puede instalar automáticamente el certificado, actualiza el `server_name` de nginx con el dominio real y ejecuta:

```bash
sudo certbot install --cert-name api.tudominio.com
```

---

## Paso 11: Actualizar DNS de Cloudflare

Después de obtener el certificado SSL, activa el proxy (nube naranja) en el registro `api` en Cloudflare:

| Tipo | Nombre | Contenido | Proxy |
|------|------|-----------|-------|
| A | `api` | IP de tu servidor | On (Proxied) |

Establece **el modo SSL/TLS en Full (strict)** en Cloudflare.

---

## Paso 12: Verificación final

```bash
curl https://api.tudominio.com/health
```

Respuesta esperada:
```json
{"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

---

## Paso 13: Configurar las aplicaciones Android

Después de desplegar tu backend, configura las aplicaciones Android para conectarse a tu backend:

1. Abre la aplicación RavenTag
2. Ve a Configuración
3. Introduce la URL de tu backend: `https://api.tudominio.com`
4. Introduce tu clave API Admin u Operator (desde `secrets/admin_key` o `secrets/operator_key`)
5. Guarda

Las aplicaciones se conectarán ahora a tu backend para todas las operaciones.

---

## Actualizar el despliegue

Para descargar nuevo código y reiniciar el backend:

```bash
cd ~/RavenTag
git pull
docker compose down backend
docker compose up -d --build backend
```

---

## Copia de Seguridad

La base de datos SQLite se respalda automáticamente diariamente por el servicio backup. Las copias de seguridad están:
- Cifradas con AES-256-CBC usando la clave admin
- Almacenadas en el volumen Docker `raventag_backups`
- Conservadas durante los últimos 7 días

Para realizar una copia de seguridad manual:

```bash
docker run --rm -v raventag_raventag_data:/data -v $(pwd):/backup alpine \
  cp /data/raventag.db /backup/raventag_$(date +%Y%m%d).db
```

Para restaurar desde una copia de seguridad cifrada:

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
  -pass file:secrets/admin_key \
  -in raventag_TIMESTAMP.db.enc \
  -out raventag.db
```

---

## Documentación de la API

Tu backend expone estos endpoints:

### Endpoints Públicos
- `GET /health` - Verificación de estado
- `POST /api/verify/tag` - Verificación completa (SUN + blockchain + revocación)
- `GET /api/assets/:name/revocation` - Verificar estado de revocación
- `GET /.well-known/assetlinks.json` - Verificación Android App Links

### Endpoints Operator (X-Operator-Key requerido)
- `POST /api/brand/register-chip` - Registrar UID del chip
- `GET /api/brand/chips` - Listar todos los chips
- `GET /api/brand/chip/:assetName` - Obtener chip específico

### Endpoints Admin (X-Admin-Key requerido)
- `POST /api/brand/revoke` - Revocar activo
- `DELETE /api/brand/revoke/:name` - Anular revocación
- `GET /api/brand/revoked` - Listar activos revocados
- `POST /api/brand/derive-chip-key` - Derivar claves AES del chip

Para la documentación API completa, consulta las especificaciones [protocol.md](../protocol.md).

---

## Solución de Problemas

### El backend no inicia
```bash
docker compose logs backend
```

Verifica:
- Archivos secrets faltantes en el directorio `./secrets/`
- Puerto ya en uso
- Permisos de la ruta de la base de datos

### Android App Links no funciona
```bash
curl https://api.tudominio.com/.well-known/assetlinks.json
```

Asegúrate:
- `ANDROID_APP_FINGERPRINT` está configurado en `.env`
- La huella está en mayúsculas, sin dos puntos
- El backend ha sido reiniciado después de configurar la huella

### Problemas con el certificado SSL
```bash
sudo certbot certificates
sudo certbot renew --dry-run
```

### Corrupción de la base de datos
```bash
docker compose down backend
docker run --rm -v raventag_raventag_data:/data alpine ls -la /data
```

---

## Mejores Prácticas de Seguridad

1. **Usa Docker secrets**: Todas las claves sensibles se cargan desde archivos `./secrets/`, nunca desde variables de entorno
2. **Mantén tus claves secretas**: Nunca hagas commit de `.env` o `secrets/` en el control de versiones
3. **Usa claves fuertes**: Todas las claves deben tener al menos 32 caracteres hexadecimales
4. **Activa el proxy de Cloudflare**: Proxy siempre tu endpoint API a través de Cloudflare
5. **Copias de seguridad regulares**: El servicio backup se ejecuta automáticamente todos los días
6. **Monitorea los registros**: Verifica `docker compose logs backend` regularmente
7. **Actualiza regularmente**: Descarga y despliega actualizaciones mensualmente

---

## Formato de Archivos Secrets

Cada archivo secret en `./secrets/` debe contener ÚNICAMENTE el valor de cadena hex:

```bash
# Correcto (sin salto de línea):
echo -n "a1b2c3d4e5f6..." > secrets/admin_key

# O usa openssl que maneja esto automáticamente:
openssl rand -hex 24 > secrets/admin_key
```

Docker Compose lee estos archivos y los monta en `/run/secrets/<name>` dentro del contenedor. El backend lee desde las variables de entorno `<KEY>_FILE` para cargar los secrets de manera segura.

---

## Referencia de Variables de Entorno

| Variable | Requerida | Descripción |
|----------|-----------|-------------|
| `NODE_ENV` | Sí | Establecer en `production` |
| `PORT` | Sí | Puerto backend (predeterminado: 3001) |
| `DB_PATH` | Sí | Ruta base de datos SQLite (`/data/raventag.db`) |
| `RVN_PUBLIC_RPC_URL` | No | Endpoint RPC público Ravencoin (fallback) |
| `ALLOWED_ORIGINS` | Sí | Lista separada por comas de nombres de paquete Android |
| `IPFS_GATEWAY` | Sí | URL puerta de enlace IPFS |
| `ANDROID_APP_FINGERPRINT` | Sí | Huella de certificado SHA-256 para Android App Links |

---

**Copyright 2026 Alessandro Nocentini. Todos los derechos reservados.**
