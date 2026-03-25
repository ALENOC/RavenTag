<p align="center">
  <img src="../pictures/RavenTag_Logo.jpg" width="380" alt="RavenTag"/>
</p>

# RavenTag

[![Licencia](https://img.shields.io/badge/Licencia-RTSL--1.0-orange.svg)](../LICENSE)
[![Protocolo](https://img.shields.io/badge/Protocolo-RTP--1-orange.svg)](protocol.md)
[![Atribucion Requerida](https://img.shields.io/badge/Atribucion-Requerida-green.svg)](../LICENSE)
[![Release](https://img.shields.io/github/v/release/ALENOC/RavenTag)](https://github.com/ALENOC/RavenTag/releases/latest)

**Leer en otros idiomas:**
[🇬🇧 English](../README.md) |
[🇮🇹 Italiano](README_IT.md) |
[🇫🇷 Français](README_FR.md) |
[🇩🇪 Deutsch](README_DE.md) |
[🇨🇳 中文](README_ZH.md) |
[🇯🇵 日本語](README_JA.md) |
[🇰🇷 한국어](README_KO.md) |
[🇷🇺 Русский](README_RU.md)

---

**RavenTag es una plataforma anti-falsificacion open-source y trustless. Vincula chips NFC NTAG 424 DNA con activos en la blockchain Ravencoin usando el protocolo RTP-1, permitiendo a las marcas demostrar la autenticidad de sus productos fisicos sin depender de ninguna autoridad central.**

## Que es RavenTag?

La falsificacion cuesta miles de millones a marcas y consumidores cada ano. Las soluciones existentes son caras, requieren confianza en un servicio de terceros y encierran a las marcas en ecosistemas propietarios.

RavenTag resuelve esto combinando tres tecnologias:

**1. Chip NFC con seguridad hardware (NTAG 424 DNA)**
Cada producto autenticado lleva un chip NXP NTAG 424 DNA. En cada lectura, el chip genera una URL unica y firmada criptograficamente mediante AES-128-CMAC (SUN: Secure Unique NFC). La clave AES reside en el silicio del chip y no puede extraerse ni clonarse.

**2. Blockchain Ravencoin**
La marca registra cada articulo serializado como un token unico on-chain, por ejemplo `FASHIONX/BOLSO001#SN0001`. Los metadatos del token (en IPFS) contienen la huella publica del chip: `nfc_pub_id = SHA-256(chip_uid || BRAND_SALT)`.

**3. Gestion de claves soberana**
Las claves AES se derivan en el servidor de la marca desde `BRAND_MASTER_KEY`. Las claves nunca abandonan el servidor de la marca.

## Por que Ravencoin?

[Ravencoin](https://ravencoin.org) es un fork de Bitcoin construido especificamente para la emision de activos:

- **Protocolo de activos nativo**: activos root, sub-activos y tokens unicos son caracteristicas de primer nivel. Sin smart contracts.
- **Jerarquia de activos adaptada a marcas**: MARCA (root) / PRODUCTO (sub-activo) / SERIE#TAG (token unico).
- **KAWPOW proof-of-work**: algoritmo resistente a ASICs.
- **Sin ICO, sin premine**: Ravencoin se lanzo de forma justa en enero de 2018.
- **Comisiones bajas y predecibles**: 500 RVN por activo root, 100 RVN por linea de producto, 5 RVN por articulo.

La licencia RTSL-1.0 exige que todas las obras derivadas usen exclusivamente Ravencoin para todas las operaciones blockchain.

## Cuatro niveles de proteccion

| Nivel | Tecnologia | Lo que necesita un atacante |
|-------|------------|------------------------------|
| **1. Consenso Ravencoin** | Propiedad del activo parent verificada por cada nodo | Debe poseer el activo parent Ravencoin |
| **2. Enlace nfc_pub_id** | `SHA-256(UID \|\| BRAND_SALT)` en metadatos IPFS | Debe conocer `BRAND_SALT` (nunca publico) |
| **3. Derivacion de clave AES** | `AES-ECB(BRAND_MASTER_KEY, [slot \|\| UID])` por chip | Debe conocer `BRAND_MASTER_KEY` |
| **4. Silicio NTAG 424 DNA** | Hardware NXP no clonable | Debe clonar fisicamente el silicio NXP (imposible) |

## Dos aplicaciones Android

### RavenTag Verify (app para consumidores)

Descarga: [RavenTag-Verify-v1.0.0.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| Funcionalidad | Detalles |
|---|---|
| Escaneo de tags NFC | Tap en chip NTAG 424 DNA, resultado AUTENTICO / REVOCADO |
| Verificacion completa | SUN MAC + blockchain Ravencoin + comprobacion de revocacion |
| Monedero Ravencoin | BIP44 `m/44'/175'/0'/0/0`, mnemonica BIP39 12 palabras |
| Multilingue | EN, IT, FR, DE, ES, ZH, JA, KO, RU |

### RavenTag Brand Manager (app para operadores)

Descarga: [RavenTag-Brand-v1.0.0.apk](https://github.com/ALENOC/RavenTag/releases/latest)

| Funcionalidad | Detalles |
|---|---|
| Emision de activos Ravencoin | Root (500 RVN), sub-activo (100 RVN), token unico (5 RVN) |
| Programacion de chips NTAG 424 DNA | Claves AES-128 + URL SUN via APDU ISO 7816-4 |
| Monedero HD | BIP44, firma UTXO local, mnemonica BIP39 12 palabras |
| Transferencia / Revocacion / Quema | Gestion completa del ciclo de vida de activos |
| Metadatos del producto | Subida de JSON RTP-1 a IPFS via Pinata, hash CIDv0 referenciado on-chain |

## Roles y niveles de acceso

Los roles se definen al crear la billetera introduciendo una clave de control (admin u operador). La app determina el rol automaticamente validando la clave en el backend. El rol queda vinculado a la billetera y no puede modificarse en la configuracion.

| Rol | Clave de control | Permisos en la app Android |
|---|---|---|
| **Admin** | Clave Admin (`X-Admin-Key`) | Todas las operaciones: emision de activos root/sub, emision de tokens unicos, revocacion/restauracion, envio de RVN, transferencia de todos los tipos de activos |
| **Operador** | Clave Operador (`X-Operator-Key`) | Solo emision de tokens unicos. Creacion de activos root/sub, revocacion/restauracion, envio de RVN y transferencia de activos root/sub estan bloqueados. |

**Caso de uso:** Un admin de marca puede preconfigurar varios dispositivos operador en la misma billetera que contiene los activos propietarios. Cada dispositivo operador puede emitir tokens unicos sin acceso a la gestion de activos ni a la billetera.

## Comportamiento del tap NFC

**App instalada:** La URL del tag es interceptada directamente por RavenTag Verify. La app abre la pantalla de Escaneo y realiza la verificacion completa.

**App no instalada:** La URL abre el navegador que muestra una pagina de instalacion con logo y enlace de descarga.

## Estructura del proyecto

```
RavenTag/
├── backend/        Node.js + Express + SQLite
├── frontend/       Next.js 14 + Tailwind CSS
├── android/        Kotlin + Jetpack Compose
├── docs/           protocol.md, architecture.md, legal/, README_*.md
├── pictures/       Assets del logo
├── docker-compose.yml
└── LICENSE         RavenTag Source License (RTSL-1.0)
```

## Legal

- [Terminos de Servicio](legal/TERMS_OF_SERVICE.md)
- [Politica de Privacidad](legal/PRIVACY_POLICY.md)

Consultas legales: legal@raventag.com

## Atribucion (RTSL-1.0)

RavenTag se publica bajo la **RavenTag Source License (RTSL-1.0)**. Ver [LICENSE](../LICENSE) para el texto completo.

[![Licencia](https://img.shields.io/badge/Licencia-RTSL--1.0-orange.svg)](../LICENSE)
