# RavenTag Verify - Política de Privacidad

**Versión 1.2 - Fecha de entrada en vigor: 18 de agosto de 2026**  
**Copyright 2026-present Alessandro Nocentini. Todos los derechos reservados.**

---

> **VERSIÓN OFICIAL.** La versión en italiano es la versión jurídicamente vinculante de esta Política. En caso de discrepancia o ambigüedad, prevalece la versión italiana, sin perjuicio de los derechos imperativos aplicables.

## 1. Introducción
Esta Política describe cómo RavenTag Verify (la “Aplicación”), desarrollada por Alessandro Nocentini (el “Desarrollador”), trata información. La Aplicación está diseñada como software no custodial con código fuente públicamente disponible bajo RavenTag Source License (RTSL-1.0). Su arquitectura busca minimizar los datos técnicos y de red tratados.

La Política se redacta con referencia al Reglamento (UE) 2016/679 (RGPD), la normativa italiana de protección de datos y demás normas aplicables. No constituye una certificación general de cumplimiento.

## 2. Responsable e infraestructura
Para los sistemas operados por el Desarrollador, cuando corresponda, el responsable es:

**Alessandro Nocentini**  
GitHub: https://github.com/ALENOC/RavenTag  
Email: legal@raventag.com

El Desarrollador puede operar un backend de demostración en `raventag.com` / `api.raventag.com` y un endpoint ElectrumX público (`electrumx.raventag.com`). Esta infraestructura no es de un tercero.

Marcas y fabricantes pueden operar backends propios. La Aplicación también puede usar nodos ElectrumX/Ravencoin Core, gateways IPFS u otros servicios independientes. El Desarrollador no controla sus prácticas de logging, conservación, seguridad o privacidad.

## 3. Datos tratados y arquitectura técnica
### 3.1 Datos locales del wallet
La seed phrase, las claves privadas y otras credenciales sensibles del wallet están destinadas a generarse o almacenarse localmente. Las claves privadas y la seed phrase no se transmiten a ElectrumX durante el funcionamiento normal del wallet.

### 3.2 Verificación NFC y backend API
Durante una verificación NFC pueden transmitirse nombre del activo, contador NFC cifrado/parámetro de verificación, MAC NFC e IP.

El logger del backend registra **método HTTP, ruta, código de estado, duración e IP**; no registra los cuerpos de las solicitudes o respuestas. Estos metadatos pueden utilizarse para seguridad, prevención de abusos, rate limiting, diagnóstico técnico y métricas operativas agregadas.

**Conservación verificada por código:** los registros persistidos en `request_logs` y `rate_limit_events` se eliminan automáticamente cuando superan 30 días.

Esta rutina **no controla** posibles logs de consola/stdout, contenedor, sistema operativo, reverse proxy, CDN, proveedor de hosting o proceso ElectrumX. Su conservación depende de la configuración real de producción y esta Política no atribuye un plazo fijo no verificado.

Cuando resulte aplicable, el tratamiento de metadatos técnicos por infraestructura del Desarrollador puede basarse en el interés legítimo del art. 6.1.f RGPD para seguridad, prevención de abusos, rate limiting, diagnóstico y monitorización operativa proporcionada.

### 3.3 Blockchain y ElectrumX
Según la solicitud, un servidor ElectrumX puede observar o recibir IP de origen, timestamps, metadatos de conexión, consultas JSON-RPC/script-hash, consultas de saldo/historial/UTXO, identificadores de transacción y transacciones raw ya firmadas. Los patrones de consulta pueden permitir correlaciones con actividad visible en la blockchain pública.

La transacción es iniciada por el usuario, construida por la App y firmada en el dispositivo con claves controladas por el usuario. ElectrumX puede recibir la transacción ya firmada y retransmitirla a Ravencoin. No posee la clave privada, no decide autónomamente destinatario o importe y no mantiene una cuenta custodial para el usuario.

### 3.4 Ravencoin Core, IPFS, cámara y NFC
Los nodos Ravencoin Core realizan sincronización, validación y propagación P2P; ello no implica posesión de claves privadas de los usuarios. Los operadores de IPFS y otros servicios externos pueden tratar IP y metadatos de red según sus propias prácticas. La lectura QR por cámara se realiza en el dispositivo y la lectura NFC es local; solo se envían al backend los parámetros necesarios para la verificación.

## 4. Datos no solicitados intencionadamente
El uso normal no requiere nombre, documento de identidad, dirección postal, IMEI, Android Advertising ID ni geolocalización precisa. Las direcciones IP y otros metadatos de red pueden, no obstante, constituir datos personales.

## 5. Seguridad y arquitectura no custodial
RavenTag utiliza medidas locales y canales cifrados donde los prevé la implementación. Ninguna medida garantiza seguridad absoluta. Al ser no custodial, el Desarrollador normalmente no posee las claves necesarias para recuperar o mover los fondos del usuario.

## 6. Conservación
- Datos locales del wallet: hasta su eliminación según el comportamiento del dispositivo/App.
- `request_logs` y `rate_limit_events`: borrado automático de registros de más de 30 días.
- Logs runtime/consola, proxy, sistema, CDN, hosting o ElectrumX: dependen de la configuración real.
- Blockchain Ravencoin: los datos públicos replicados no pueden ser borrados unilateralmente por el Desarrollador.

## 7. Finalidades y principios RGPD
Los metadatos técnicos pueden tratarse, cuando sea necesario y proporcionado, para seguridad, prevención de abusos/ataques, rate limiting, diagnóstico y estadísticas/métricas operativas agregadas, respetando minimización, limitación de finalidad, conservación, integridad y confidencialidad.

## 8. Derechos
Cuando el RGPD sea aplicable al tratamiento del Desarrollador, el interesado podrá ejercer, con los límites legales, los derechos de acceso, rectificación, supresión, limitación, oposición y demás derechos aplicables. Contacto: legal@raventag.com. Se mantiene el derecho a reclamar ante la autoridad de control competente. Estos derechos se refieren a datos bajo control del Desarrollador y no le otorgan poder para borrar unilateralmente datos ya replicados en la blockchain pública.

## 9. Menores
La App no está destinada a menores de 18 años.

## 10. Transferencias internacionales
La ubicación de sistemas y proveedores puede variar. Si datos personales tratados por el Desarrollador se transfieren fuera del EEE, la transferencia queda sujeta al **Capítulo V RGPD** y debe basarse en el mecanismo aplicable, como una decisión de adecuación pertinente o garantías adecuadas del art. 46 RGPD cuando sean necesarias.

La mera ubicación física de un servidor en Estados Unidos u otro tercer país no se considera, por sí sola, prueba de un mecanismo válido. Puede solicitarse información sobre el mecanismo efectivamente utilizado a legal@raventag.com.

## 11. Naturaleza no custodial y función técnica
RavenTag es software no custodial. Las claves privadas permanecen bajo control del usuario. ElectrumX operado por el Desarrollador está diseñado para consultas técnicas de blockchain y retransmisión de transacciones ya firmadas con claves controladas por el usuario.

Esta descripción **no es una declaración general de exención, autorización o clasificación regulatoria bajo el Reglamento (UE) 2023/1114 (MiCA)**.

## 12. Cambios y contacto
La Política puede actualizarse cuando cambien la App, infraestructura, tratamiento o marco jurídico.

**Alessandro Nocentini**  
GitHub: https://github.com/ALENOC/RavenTag  
Email: legal@raventag.com
