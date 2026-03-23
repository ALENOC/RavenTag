# RavenTag Verify - Politica de Privacidad

**Version 1.0 - Fecha de entrada en vigor: 2026-03-21**
**Copyright 2026 Alessandro Nocentini. Todos los derechos reservados.**

---

> **AVISO DE TRADUCCION.** Este documento es una traduccion de cortesia. La version italiana ([PRIVACY_POLICY_IT.md](PRIVACY_POLICY_IT.md)) es la unica version juridicamente vinculante. En caso de discrepancia, contradiccion o ambiguedad entre esta traduccion y el texto italiano, prevalece el texto italiano.

---

## 1. Introduccion

Esta Politica de Privacidad describe como RavenTag Verify ("App"), desarrollada por Alessandro Nocentini ("Desarrollador", "nosotros"), recopila, utiliza y protege la informacion cuando utiliza la App.

El Desarrollador se compromete a minimizar la recopilacion de datos. La App esta disenada para operar con la cantidad minima de datos necesaria para funcionar.

Esta Politica de Privacidad cumple con:
- Reglamento General de Proteccion de Datos de la UE (RGPD - Reglamento 2016/679)
- Codigo italiano de proteccion de datos personales (D.Lgs. 196/2003 modificado por D.Lgs. 101/2018)
- Politica para desarrolladores de Google Play

---

## 2. Responsable del Tratamiento

RavenTag es un protocolo de codigo abierto. Cada despliegue de la App se conecta a un servidor backend elegido por la entidad que compilo o configuro la App (una marca, fabricante o el Desarrollador con fines demostrativos).

**Backend demo operado por el Desarrollador:**
El Desarrollador opera una instancia de backend en raventag.com exclusivamente con fines de demostracion y prueba de infraestructura. Si esta utilizando una instancia de la App conectada a este backend demo, el responsable del tratamiento de los datos de verificacion del lado del servidor (Seccion 3.2) es:

**Alessandro Nocentini**
Contacto: https://github.com/ALENOC/RavenTag
legal@raventag.com

Este backend demo no esta destinado al uso en produccion por parte de usuarios finales. Las marcas deben implementar su propia infraestructura de backend para las implementaciones en produccion.

**Backend operado por una marca (uso en produccion):**
En produccion, las marcas y los fabricantes implementan su propia infraestructura de backend y compilan una version de la App configurada para conectarse a su propio servidor. Cuando utiliza una instancia de la App conectada al backend de una marca, esa marca es el responsable independiente del tratamiento de cualquier dato de verificacion recibido por su servidor. El Desarrollador no tiene acceso ni asume ninguna responsabilidad por los datos procesados por backends de marcas de terceros. Debe consultar la politica de privacidad publicada por la marca que opera esa instancia de la App para saber como gestionan sus datos.

---

## 3. Datos Recopilados y Como se Utilizan

### 3.1 Datos Almacenados Localmente en su Dispositivo (Nunca Transmitidos)

Los siguientes datos se almacenan exclusivamente en su dispositivo y nunca se transmiten a ningun servidor operado por el Desarrollador:

| Dato | Proposito | Almacenamiento |
|---|---|---|
| Frase mnemonica BIP39 (cifrada) | Recuperacion de cartera | Android Keystore (AES-256-GCM) |
| Claves privadas (derivadas, cifradas) | Firma de transacciones | Android Keystore (AES-256-GCM) |
| Direccion de cartera (RVN) | Visualizacion y transacciones | Almacenamiento local cifrado |
| Claves admin/operador (version Brand) | Gestion de activos | Android Keystore (AES-256-GCM) |
| Configuracion y preferencias de la App | Configuracion de la App | Preferencias locales compartidas |

**Su frase mnemonica y claves privadas nunca abandonan su dispositivo.**

### 3.2 Datos Transmitidos Durante la Verificacion de Etiquetas NFC

Cuando escanea una etiqueta NFC, la App envia los siguientes datos a un servidor backend para realizar la verificacion criptografica:

| Dato | Proposito |
|---|---|
| Nombre del activo (ej. MARCA/PRODUCTO#001) | Identificacion del activo en la blockchain |
| Contador NFC cifrado (parametro e) | Verificacion SUN MAC |
| Valor MAC NFC (parametro m) | Verificacion SUN MAC |
| Direccion IP de su dispositivo | Limitacion de tasa del servidor y registro de seguridad |

Estos datos son el minimo necesario para verificar la autenticidad de una etiqueta NFC. La solicitud de verificacion no incluye ninguna informacion de identificacion personal mas alla de la direccion IP.

**El servidor que recibe estos datos depende de la configuracion de la App:**

- **Backend predeterminado operado por RavenTag**: si la App se conecta al backend RavenTag operado por el Desarrollador (raventag.com), el Desarrollador recibe y procesa estos datos como se describe en esta Politica de Privacidad.
- **Backend operado por una marca**: si la App fue compilada o configurada por una marca para conectarse a su propio servidor, el servidor de esa marca recibe estos datos. El Desarrollador no recibe, accede ni procesa estos datos de ninguna manera. La marca es el responsable independiente del tratamiento y se aplica su propia politica de privacidad.

Puede identificar a que backend se conecta la App comprobando la URL del servidor que se muestra en la configuracion de la App.

**Retencion (backend operado por el Desarrollador)**: las direcciones IP y los registros de solicitudes se retienen durante un maximo de 90 dias para fines de seguridad y limitacion de tasa, tras lo cual se eliminan automaticamente.

**Base juridica (RGPD, backend operado por el Desarrollador)**: Interes legitimo (Art. 6(1)(f) RGPD) - monitoreo de seguridad y prevencion de fraudes.

### 3.3 Datos Transmitidos Durante las Operaciones Blockchain

Cuando realiza operaciones de cartera (verificar saldo, enviar RVN, emitir activos), la App se comunica con nodos de la red Ravencoin. Esta comunicacion puede incluir:

| Dato | Proposito |
|---|---|
| Su direccion de cartera Ravencoin | Consulta de saldo e historial de transacciones |
| Datos de transacciones | Difusion de transacciones a la red |
| Direccion IP de su dispositivo | Comunicacion de red |

La blockchain de Ravencoin es una red publica y descentralizada. Todas las transacciones difundidas a la red son permanentes y visibles publicamente para cualquiera. No utilice esta cartera para transacciones que desee mantener privadas.

### 3.4 Datos Transmitidos Durante la Carga de Imagenes de Activos

Al cargar imagenes de activos alojadas en IPFS, la App se conecta a gateways IPFS publicos (como ipfs.io, cloudflare-ipfs.com). Estos servicios de terceros pueden registrar su direccion IP de acuerdo con sus propias politicas de privacidad.

### 3.5 Datos de Camara

Si utiliza la camara para escanear codigos QR dentro de la App, los datos de la camara se procesan exclusivamente en su dispositivo en tiempo real y nunca se almacenan ni transmiten.

### 3.6 Datos NFC

Los datos de la etiqueta NFC se leen localmente en su dispositivo. Los datos NFC sin procesar (UID, registros NDEF) se procesan en el dispositivo y solo los parametros de verificacion derivados (activo, e, m) se transmiten como se describe en la Seccion 3.2.

### 3.7 Datos que No Recopilamos

No recopilamos expresamente:

- Su nombre, direccion de correo electronico ni ninguna informacion de identificacion personal.
- Identificadores del dispositivo (IMEI, Android ID, ID de publicidad).
- Datos de ubicacion.
- Analiticas de uso o telemetria.
- Informes de fallos (a menos que los envie explicitamente usted).
- Ningun dato con fines publicitarios.

---

## 4. Servicios de Terceros

La App interactua con los siguientes servicios de terceros. Sus politicas de privacidad rigen sus practicas de datos:

| Servicio | Proposito | Politica de Privacidad |
|---|---|---|
| Nodos de la Red Ravencoin | Consultas blockchain y transacciones | Red descentralizada, sin politica unica |
| Gateways IPFS (ipfs.io, cloudflare-ipfs.com) | Carga de imagenes de activos | Ver proveedores respectivos |
| Pinata (pinata.cloud) | Fijacion de metadatos IPFS (opcional, solo version Brand) | https://pinata.cloud/privacy |
| Google Play Store | Distribucion de la App | https://policies.google.com/privacy |

El Desarrollador no es responsable de las practicas de datos de estos servicios de terceros.

---

## 5. Seguridad de los Datos

Todos los datos sensibles almacenados en su dispositivo (frase mnemonica, claves privadas, claves API) se cifran con AES-256-GCM a traves del sistema Android Keystore, que utiliza seguridad respaldada por hardware donde esta disponible.

La comunicacion entre la App y el servidor backend del Desarrollador esta cifrada con HTTPS/TLS.

A pesar de estas medidas, ningun metodo de almacenamiento o transmision electronica es 100 % seguro. Usted es responsable de mantener la seguridad de su dispositivo y su frase mnemonica.

---

## 6. Retencion de Datos

- **Datos en el dispositivo**: almacenados hasta que elimine la cartera o desinstale la App.
- **Registros de solicitudes del lado del servidor**: retenidos durante un maximo de 90 dias, luego eliminados automaticamente.
- **Datos blockchain**: todas las transacciones difundidas a la blockchain de Ravencoin son permanentes y publicas y no pueden ser eliminadas por el Desarrollador ni por terceros.

---

## 7. Sus Derechos bajo el RGPD

Si se encuentra en el Espacio Economico Europeo, tiene los siguientes derechos con respecto a sus datos personales:

- **Derecho de acceso**: solicitar una copia de los datos personales que tenemos sobre usted (limitado a registros del lado del servidor).
- **Derecho de rectificacion**: solicitar la correccion de datos inexactos.
- **Derecho de supresion**: solicitar la eliminacion de sus datos personales de nuestros servidores (registros del servidor), sujeto a las obligaciones legales de retencion.
- **Derecho a la limitacion del tratamiento**: solicitar que limitemos como usamos sus datos.
- **Derecho de oposicion**: oponerse al tratamiento basado en interes legitimo.
- **Derecho a la portabilidad de datos**: recibir sus datos en un formato estructurado y legible por maquina.
- **Derecho a presentar una reclamacion**: tiene derecho a presentar una reclamacion ante la autoridad italiana de proteccion de datos (Garante per la protezione dei dati personali, https://www.garanteprivacy.it).

Para ejercer cualquiera de estos derechos, contactenos en: https://github.com/ALENOC/RavenTag / legal@raventag.com

Responderemos a su solicitud en un plazo de 30 dias.

---

## 8. Privacidad de los Menores

La App no esta dirigida a menores de 18 anos. No recopilamos conscientemente datos personales de menores de 18 anos. Si cree que un menor de 18 anos ha utilizado la App y proporcionado datos personales, contactenos y tomaremos medidas para eliminar dichos datos.

---

## 9. Transferencias Internacionales de Datos

**Backend demo operado por el Desarrollador**: el servidor backend demo del Desarrollador esta ubicado dentro de la Union Europea. Si accede a una instancia demo de la App desde fuera de la UE, los datos de su solicitud de verificacion (Seccion 3.2) se transferiran y procesaran dentro de la UE, de conformidad con los requisitos del RGPD.

**Backend operado por una marca**: en las implementaciones en produccion, la ubicacion geografica del servidor backend esta determinada unicamente por la marca o fabricante que lo implemento. El Desarrollador no tiene control ni conocimiento de las ubicaciones de servidores elegidas por marcas de terceros. Las reglas de transferencia internacional de datos aplicables son las de la marca que opera ese despliegue. Consulte la politica de privacidad de la marca para obtener mas detalles.

---

## 10. Cambios en Esta Politica de Privacidad

Podemos actualizar esta Politica de Privacidad de vez en cuando. Le notificaremos los cambios importantes actualizando la fecha de entrada en vigor en la parte superior de este documento y, cuando lo exija la ley, proporcionando un aviso dentro de la App.

Su uso continuado de la App despues de cualquier cambio constituye su aceptacion de la Politica de Privacidad actualizada.

---

## 11. Contacto

Para cualquier pregunta, solicitud o reclamacion relacionada con la privacidad:

**Alessandro Nocentini**
https://github.com/ALENOC/RavenTag
legal@raventag.com

Para reclamaciones de proteccion de datos, tambien puede contactar con:
**Garante per la protezione dei dati personali**
https://www.garanteprivacy.it

---

*RavenTag Verify es un proyecto de codigo abierto. Codigo fuente: https://github.com/ALENOC/RavenTag*
