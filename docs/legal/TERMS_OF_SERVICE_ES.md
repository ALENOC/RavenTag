# RavenTag Verify - Términos de Servicio

**Versión 1.1 - Fecha de entrada en vigor: 18 de agosto de 2026**
**Copyright 2026-presente Alessandro Nocentini. Todos los derechos reservados.**

---

> **VERSIÓN OFICIAL.** Este documento en idioma italiano constituye la versión legalmente vinculante de los Términos de Servicio. En caso de discrepancia, contradicción o ambigüedad entre esta versión y cualquier traducción, prevalecerá la versión italiana.

---

## 1. Aceptación de los Términos

En el primer inicio, la Aplicación presenta estos Términos de Servicio y la Política de Privacidad. Debe aceptar explícitamente ambos documentos marcando las casillas correspondientes antes de continuar.

Al descargar, instalar o continuar utilizando la Aplicación, usted ("Usuario") confirma que está legalmente vinculado por estos Términos.

Estos Términos constituyen un acuerdo legalmente vinculante entre usted y Alessandro Nocentini ("Desarrollador"), autor de RavenTag Verify.

---

## 2. Descripción de la Aplicación y Naturaleza de la Licencia

RavenTag Verify es una aplicación móvil que proporciona:

- **Verificación de Etiquetas NFC**: Lectura y verificación criptográfica de chips NFC NTAG 424 DNA vinculados a activos de la blockchain Ravencoin mediante RavenTag Protocol v1 (RTP-1).
- **Cartera Ravencoin Sin Custodia**: Generación, almacenamiento local y gestión autónoma de una cartera HD BIP39/BIP44 sin custodia para la blockchain Ravencoin (RVN).
- **Gestión de Activos** (solo versión Brand): Emisión, transferencia y gestión local de activos Ravencoin.

La Aplicación es una herramienta de software para interactuar en autocustodia (self-custody) con la blockchain Ravencoin y hardware NFC. No es un servicio financiero, un exchange, un banco ni un producto financiero.

La Aplicación y su código fuente se distribuyen bajo la **RavenTag Source License (RTSL-1.0)**, una licencia de código fuente disponible (source-available software) que restringe ciertos usos comerciales. RavenTag no constituye software de código abierto según las definiciones OSI.

---

## 3. Requisitos y Ámbito de Uso

Debe tener al menos 18 años para utilizar esta Aplicación.

### 3.1 Uso del Consumidor de la App Verify
La función de verificación NFC está diseñada para cualquier consumidor que desee comprobar la autenticidad de un producto equipado con NFC.

### 3.2 Funcionalidad de Cartera y Autocustodia (Self-Custody)
La funcionalidad de cartera Ravencoin implica la autocustodia (self-custody), gestión directa y transferencia de activos digitales en una blockchain pública. Usted actúa bajo su propia responsabilidad y riesgo financiero.

### 3.3 Código Fuente y Licencia RTSL-1.0
La restricción de uso comercial de la licencia RTSL-1.0 se aplica únicamente a desarrolladores y entidades que utilizan el código fuente. Los usuarios finales de la App no se ven afectados.

---

## 4. Cartera Sin Custodia y Arquitectura de Transacciones

### 4.1 Sin Custodia por parte del Desarrollador
RavenTag Verify proporciona una cartera exclusivamente sin custodia (non-custodial). Esto significa que:
- El Desarrollador **nunca** posee, almacena, gestiona ni controla sus claves privadas, frases mnemónicas o fondos.
- Usted es el único custodio (Self-Custodian) de sus claves criptográficas y activos digitales.
- El Desarrollador no puede autorizar transacciones ni recuperar su cartera bajo ninguna circunstancia.

### 4.2 Creación, Firma y Transmisión de Transacciones
Para cada transacción realizada desde la Aplicación:
1. El Usuario inicia la transacción en la interfaz de la Aplicación;
2. La Aplicación construye la transacción en bruto localmente en el dispositivo;
3. La transacción se firma localmente en el dispositivo con las claves del Usuario;
4. La Aplicación envía la transacción **ya firmada** a la infraestructura ElectrumX;
5. ElectrumX retransmite la transacción firmada a la red Ravencoin Core.

La infraestructura ElectrumX no posee las claves privadas del Usuario, no puede generar firmas válidas por sí misma, no decide destinatario ni montos y no mantiene cuentas en custodia.

### 4.3 Frase Mnemónica (Seed Phrase) y Responsabilidad del Usuario
Debe anotar su frase mnemónica BIP39 de 12 palabras inmediatamente y guardarla fuera de línea en un lugar seguro. **La pérdida de su frase mnemónica conlleva la pérdida permanente e irrecuperable de todos sus fondos.**

### 4.4 Seguridad del Dispositivo
Usted es responsable de mantener la seguridad de su dispositivo. El Desarrollador no se hace responsable por daños derivados de malware o sistemas operativos alterados.

---

## 5. Riesgos Blockchain, Financieros y Marco Regulatorio

### 5.1 Naturaleza de Ravencoin e Infraestructura de Red
- **Infraestructura del Desarrollador**: El Desarrollador opera el punto de acceso público ElectrumX `electrumx.raventag.com` / `electrum.raventag.com`. Esta no constituye infraestructura de terceros.
- **Infraestructura Independiente de Terceros**: La Aplicación puede interactuar con nodos independientes de terceros.
- **Rol de los Nodos Ravencoin Core**: Un nodo público Core realiza funciones de validación y propagación. No custodia fondos ni posee claves privadas.

### 5.2 Riesgo Financiero, Volatilidad e Irreversibilidad
Las transacciones en la blockchain Ravencoin son **irreversibles**. Las tarifas de red pagadas a los mineros no son reembolsables.

### 5.3 Sin Asesoramiento Financiero
Ningún contenido de esta App constituye asesoramiento financiero, de inversión, legal o fiscal.

### 5.4 Marco Regulatorio (MiCA)
RavenTag se proporciona como software sin custodia con código fuente disponible (source-available). El Desarrollador no posee las claves privadas de los usuarios y no ejerce control ni custodia sobre los activos digitales de los usuarios.

---

## 6. Hardware NFC y Metadatos IPFS de Terceros

Los resultados de verificación se basan en comprobaciones criptográficas. Los contenidos e imágenes alojados en pasarelas IPFS de terceros son creados por entidades independientes.

---

## 7. Distribución Oficial y Aviso de Seguridad

### 7.1 Canales Autorizados
1. **GitHub Releases** (https://github.com/ALENOC/RavenTag/releases)
2. **Google Play Store** (para la App Verify)

### 7.2 Exención de Responsabilidad para Versiones No Oficiales
El Desarrollador no asume responsabilidad por daños o pérdidas derivados de versiones no oficiales o modificadas.

---

## 8. Dependencia de la Red y Disponibilidad de Infraestructura

Para la infraestructura operada por el Desarrollador (`electrumx.raventag.com`), se adoptan medidas razonables de disponibilidad sin ofrecer garantías de tiempo de actividad ni deber de mantenimiento perpetuo.

---

## 9. Limitación General de Responsabilidad y Cláusula de Salvaguardia

### 9.1 Exclusión de Daños Indirectos
En la máxima medida permitida por la ley aplicable, el Desarrollador no será responsable de daños indirectos, emergentes o lucro cesante.

### 9.2 Límite Máximo de Responsabilidad
Dado que la Aplicación se distribuye gratuitamente:
- Para usuarios profesionales: la responsabilidad total del Desarrollador se limita a cero euros (EUR 0) en la medida permitida por la ley.
- Para consumidores: la responsabilidad se limita al mínimo obligatorio establecido por la ley aplicable.

### 9.3 Cláusula de Salvaguardia de Derecho Imperativo
Nada en estos Términos excluye o limita la responsabilidad del Desarrollador por dolo o culpa grave (Art. 1229 del Código Civil italiano) ni cualquier responsabilidad que no pueda ser legalmente excluida en virtud de la legislación imperativa de protección al consumidor.

---

## 10. Sin Relación Fiduciaria ni Deber de Supervisión

El uso de la App no crea una relación fiduciaria. El Desarrollador no tiene la obligación de supervisar las transacciones del Usuario.

---

## 11. Modificaciones a la Aplicación y Términos

El Desarrollador se reserva el derecho de actualizar la Aplicación y estos Términos por motivos justificados.

---

## 12. Ley Aplicable y Jurisdicción

Estos Términos se rigen por la legislación italiana. Las normas imperativas de consumo del Reglamento (CE) 593/2008 (Roma I) quedan a salvo.

---

## 13. Divisibilidad y No Renuncia

Si alguna cláusula se considera inválida, las demás mantendrán su plena vigencia.

---

## 14. Acuerdo Completo

Estos Términos y la Política de Privacidad constituyen el acuerdo completo entre el Usuario y el Desarrollador.

---

## 15. Información de Contacto

**Alessandro Nocentini**
GitHub: https://github.com/ALENOC/RavenTag
Correo electrónico: legal@raventag.com
