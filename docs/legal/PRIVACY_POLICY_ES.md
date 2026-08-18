# RavenTag Verify - Política de Privacidad

**Versión 1.1 - Fecha de entrada en vigor: 18 de agosto de 2026**
**Copyright 2026-presente Alessandro Nocentini. Todos los derechos reservados.**

---

> **VERSIÓN OFICIAL.** Este documento en idioma italiano constituye la versión legalmente vinculante de la Política de Privacidad. En caso de discrepancia, contradicción o ambigüedad entre esta versión y cualquier traducción, prevalecerá la versión italiana.

---

## 1. Introducción

Esta Política de Privacidad describe cómo RavenTag Verify ("Aplicación"), desarrollada por Alessandro Nocentini ("Desarrollador", "nosotros"), recopila, utiliza y protege la información cuando utiliza la Aplicación.

El Desarrollador se compromete con la minimización de datos. La Aplicación está diseñada como software sin custodia (non-custodial) y opera con la cantidad mínima de datos técnicos y de red estrictamente necesarios para su funcionamiento.

Esta Política de Privacidad cumple con:
- El Reglamento General de Protección de Datos de la UE (RGPD - Reglamento UE 2016/679)
- El Código italiano de protección de datos personales (D.Lgs. 196/2003 modificado por D.Lgs. 101/2018)
- La Política para Desarrolladores de Google Play

---

## 2. Responsable del Tratamiento y Categorías de Infraestructura

RavenTag es un protocolo de código abierto. La Aplicación puede interactuar con infraestructura operada directamente por el Desarrollador, así como con infraestructura independiente de terceros o marcas.

### 2.1 Backend de demostración operado por el Desarrollador
El Desarrollador opera una instancia backend en `raventag.com` (ej. `api.raventag.com`) con fines de demostración, pruebas de infraestructura y verificación. Si utiliza una instancia conectada a este backend de demostración, el responsable del tratamiento para los datos de verificación y registros de red (Sección 3.2) es:

**Alessandro Nocentini**
Contacto: https://github.com/ALENOC/RavenTag
Correo electrónico: legal@raventag.com

### 2.2 Infraestructura ElectrumX operada por el Desarrollador
El Desarrollador opera un punto de acceso público ElectrumX (ej. `electrumx.raventag.com` / `electrum.raventag.com`) situado frente a un nodo Ravencoin Core dedicado. Cuando la Aplicación se conecta a este punto de acceso específico para consultas blockchain o retransmisión de transacciones, el tratamiento de metadatos de conexión es gestionado por el Desarrollador bajo esta Política. Esta infraestructura no constituye un servicio de terceros.

### 2.3 Backend operado por marcas (uso en producción)
En producción, las marcas y fabricantes despliegan su propia infraestructura backend. Cuando utiliza una Aplicación conectada al backend de una marca, esa marca es el responsable independiente del tratamiento de los datos procesados por sus servidores. El Desarrollador no tiene acceso ni asume responsabilidad sobre los datos procesados por servidores de marcas de terceros.

### 2.4 Infraestructura blockchain independiente de terceros
La Aplicación también puede conectarse a nodos públicos ElectrumX o nodos independientes Ravencoin Core operados por terceros. Estos nodos están completamente fuera del control del Desarrollador.

---

## 3. Datos Procesados y Arquitectura Técnica

### 3.1 Datos Almacenados Localmente en su Dispositivo (Nunca Transmitidos al Desarrollador o a ElectrumX)

Los siguientes datos sensibles se generan y almacenan exclusivamente en su dispositivo de forma cifrada y nunca se transmiten a ningún servidor o infraestructura del Desarrollador ni a servidores ElectrumX:

| Dato | Propósito | Almacenamiento |
|---|---|---|
| Frase Mnemónica BIP39 (seed phrase) | Generación y recuperación de cartera | Android Keystore (AES-256-GCM) |
| Claves Privadas (derivadas, cifradas) | Firma local de transacciones | Android Keystore (AES-256-GCM) |
| Dirección de Cartera (RVN) | Visualización y cálculo local | Almacenamiento local cifrado |
| Claves de Admin/Operador (versión Brand) | Gestión local de activos | Android Keystore (AES-256-GCM) |
| Ajustes y Preferencias de la App | Configuración local de la App | Preferencias locales protegidas |

**Su frase mnemónica y sus claves privadas nunca salen de su dispositivo.**

### 3.2 Datos Transmitidos Durante la Verificación de Etiquetas NFC (Backend API)

Al escanear una etiqueta NFC para verificar la autenticidad de un producto, la Aplicación envía los siguientes parámetros al backend API:

| Dato | Propósito |
|---|---|
| Nombre del Activo (ej. BRAND/PRODUCT#001) | Identificación del activo en la blockchain Ravencoin |
| Contador NFC Cifrado (parámetro e) | Verificación criptográfica SUN MAC |
| Valor MAC NFC (parámetro m) | Verificación criptográfica SUN MAC |
| Dirección IP de su Dispositivo | Limitación de tasa (rate limiting) y seguridad de red |

**Retención de Registros del Backend del Desarrollador**: Las direcciones IP y registros de red del backend API se conservan durante un período máximo de 30 días (verificado a nivel de código en el middleware de limpieza de registros del backend) y posteriormente se eliminan automáticamente.

**Base Legal (RGPD)**: Interés legítimo (Art. 6(1)(f) RGPD) para garantizar la seguridad de la infraestructura y prevenir abusos.

### 3.3 Datos Procesados Durante Operaciones Blockchain y ElectrumX

Al consultar saldos, historiales de transacciones o transmitir transacciones, la Aplicación se comunica con la infraestructura ElectrumX.

**A. Lo que un servidor ElectrumX puede observar o recibir:**
Un servidor público ElectrumX puede observar datos y metadatos de conexión:
- Dirección IP de origen del dispositivo;
- Metadatos de conexión TLS, marcas de tiempo y frecuencia de solicitudes;
- Consultas JSON-RPC y búsquedas por script-hash;
- Solicitudes de saldo, historial de transacciones y UTXOs;
- Identificadores de transacción (TxID) y metadatos de activos;
- Transacciones en bruto ya firmadas (raw signed transactions) enviadas para su emisión.

Debido a los patrones de consulta, esta información puede permitir técnicamente correlaciones entre identificadores de red (como la dirección IP) y la actividad en la blockchain pública.

> **Aviso Explícito de Seguridad:**
> Las claves privadas y las frases mnemónicas no son requeridas por el servidor ElectrumX y nunca se transmiten a él durante el funcionamiento normal de la cartera.

**B. Flujo de creación y firma de transacciones:**
Para cada transacción ejecutada desde la cartera:
1. El usuario inicia la transacción en la interfaz de la Aplicación;
2. La Aplicación construye la transacción en bruto localmente en el dispositivo;
3. La transacción se firma criptográficamente en el dispositivo utilizando las claves privadas del usuario;
4. La Aplicación envía la transacción ya firmada al servidor ElectrumX;
5. ElectrumX retransmite (relay/broadcast) la transacción firmada a los nodos Ravencoin Core para su inclusión en bloques.

La infraestructura ElectrumX no posee las claves privadas del usuario, no puede generar firmas válidas por sí misma, no decide destinatario ni montos y no custodia fondos.

**C. Rol de los nodos públicos Ravencoin Core:**
Un nodo público Ravencoin Core realiza exclusivamente funciones de infraestructura (sincronización de la blockchain, validación de bloques y transacciones, propagación P2P). No custodia fondos de clientes ni posee claves privadas.

### 3.4 Carga de Imágenes de Activos (Pasarelas IPFS)
Para mostrar imágenes de activos alojadas en IPFS, la Aplicación puede conectarse a pasarelas públicas IPFS (ej. ipfs.io, cloudflare-ipfs.com).

### 3.5 Datos de Cámara y NFC
- **Cámara**: Utilizada exclusivamente en el dispositivo para lectura de códigos QR en tiempo real.
- **NFC**: La lectura se realiza localmente; solo se transmiten los parámetros de verificación derivados (asset, e, m).

### 3.6 Datos que No Recopilamos
El Desarrollador no recopila nombres, correos electrónicos, identificadores de hardware (IMEI), datos de ubicación ni telemetría comercial.

---

## 4. Servicios y Nodos de Terceros

| Servicio / Nodo | Propósito | Notas de Privacidad |
|---|---|---|
| Nodos ElectrumX independientes de terceros | Consultas blockchain y respaldo | El Desarrollador no controla sus registros. Los operadores pueden ver la dirección IP y transacciones en bruto. |
| Nodos independientes de la Red Ravencoin Core | Validación P2P y propagación | Red descentralizada distribuida. |
| Pasarelas IPFS públicas | Carga de medios y metadatos | Operadas por terceros. |
| Google Play Store | Distribución de la Aplicación | Políticas de privacidad de Google LLC. |

---

## 5. Seguridad de Datos y Arquitectura Sin Custodia

Los datos sensibles en el dispositivo están protegidos mediante cifrado AES-256-GCM respaldado por Android Keystore. Las conexiones de red con la infraestructura del Desarrollador utilizan canales HTTPS/TLS cifrados.

---

## 6. Conservación de Datos (Limitación del Plazo de Conservación)

- **Datos del Dispositivo**: Conservados hasta la eliminación de la cartera o desinstalación de la App.
- **Registros del Backend del Desarrollador**: Conservados por un máximo de 30 días (conforme al código de limpieza automática del backend) y posteriormente eliminados permanentemente.
- **Registros ElectrumX del Desarrollador**: Conservados solo el tiempo estrictamente necesario para diagnóstico y seguridad.
- **Datos en la Blockchain Pública Ravencoin**: Las transacciones confirmadas en la blockchain son permanentemente públicas e inalterables.

---

## 7. Tratamiento de Dirección IP y Principios del RGPD

Las direcciones IP y metadatos de red se tratan bajo los principios de minimización de datos, limitación de la finalidad, limitación del plazo de conservación (30 días) e integridad. La base legal es el interés legítimo (Art. 6(1)(f) RGPD).

---

## 8. Sus Derechos bajo el RGPD

Si reside en el Espacio Económico Europeo, tiene derecho de acceso (Art. 15), rectificación (Art. 16), supresión (Art. 17), limitación (Art. 18) y oposición (Art. 21 RGPD) respecto a los registros del servidor. Contacto: legal@raventag.com

Tiene derecho a presentar una reclamación ante una autoridad de protección de datos.

---

## 9. Privacidad de Menores

La Aplicación no está dirigida a menores de 18 años.

---

## 10. Transferencias Internacionales de Datos

La infraestructura del Desarrollador está ubicada en centros de datos seguros en la UE o EE. UU. conforme al RGPD.

---

## 11. Marco Regulatorio y Términos MiCA

RavenTag está diseñado y distribuido como software de código abierto sin custodia (non-custodial). El Desarrollador no posee las claves privadas de los usuarios, no ejerce control ni custodia sobre los criptoactivos (RVN o tokens) de los usuarios, y no presta servicios de custodia o administración de criptoactivos en nombre de terceros conforme al Reglamento (UE) 2023/1114 (MiCA). La actividad de la infraestructura ElectrumX del Desarrollador se limita al reenvío técnico de datos de red y transacciones firmadas.

---

## 12. Cambios a esta Política de Privacidad

El Desarrollador se reserva el derecho de actualizar esta Política de Privacidad.

---

## 13. Información de Contacto

**Alessandro Nocentini**
GitHub: https://github.com/ALENOC/RavenTag
Correo electrónico: legal@raventag.com
