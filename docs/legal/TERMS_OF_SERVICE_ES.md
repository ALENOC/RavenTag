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

## 2. Descripción de la Aplicación

RavenTag Verify es una aplicación móvil que proporciona:

- **Verificación de Etiquetas NFC**: Lectura y verificación criptográfica de chips NFC NTAG 424 DNA vinculados a activos de la blockchain Ravencoin mediante RavenTag Protocol v1 (RTP-1).
- **Cartera Ravencoin Sin Custodia**: Generación, almacenamiento local y gestión autónoma de una cartera HD BIP39/BIP44 sin custodia para la blockchain Ravencoin (RVN).
- **Gestión de Activos** (solo versión Brand): Emisión, transferencia y gestión local de activos Ravencoin.

La Aplicación es una herramienta de software para interactuar en autocustodia con la blockchain Ravencoin y hardware NFC. No es un servicio financiero, un exchange, un banco ni un producto financiero.

---

## 3. Requisitos y Ámbito de Uso

Debe tener al menos 18 años para utilizar esta Aplicación.

### 3.1 Uso del Consumidor de la App Verify
La función de verificación NFC está diseñada para cualquier consumidor que desee comprobar la autenticidad de un producto equipado con NFC.

### 3.2 Funcionalidad de Cartera y Autocustodia (Self-Custody)
La funcionalidad de cartera Ravencoin implica la autocustodia (self-custody), gestión directa y transferencia de activos digitales en una blockchain pública. Usted actúa bajo su propia responsabilidad y riesgo financiero.

### 3.3 Código Fuente e Infraestructura
La restricción de uso profesional de la RavenTag Source License (RTSL-1.0) se aplica únicamente a desarrolladores y entidades que utilizan el código fuente. Los usuarios finales de la App no se ven afectados.

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

### 4.3 Frase Mnemónica (Seed Phrase)
Debe anotar su frase mnemónica BIP39 de 12 palabras inmediatamente y guardarla fuera de línea en un lugar seguro. **La pérdida de su frase mnemónica conlleva la pérdida permanente e irrecuperable de todos sus fondos.**

### 4.4 Seguridad del Dispositivo
Usted es responsable de mantener la seguridad de su dispositivo.

---

## 5. Riesgos Blockchain, Financieros y Marco Regulatorio

### 5.1 Naturaleza de Ravencoin e Infraestructura de Red
- **Infraestructura del Desarrollador**: El Desarrollador opera el punto de acceso público ElectrumX `electrumx.raventag.com` / `electrum.raventag.com`.
- **Infraestructura Independiente de Terceros**: La Aplicación puede interactuar con nodos independientes de terceros.
- **Rol de los Nodos Ravencoin Core**: Un nodo público Core realiza funciones de validación y propagación. No custodia fondos ni posee claves privadas.

### 5.2 Riesgo Financiero e Irreversibilidad
Las transacciones en la blockchain Ravencoin son **irreversibles**. Las tarifas de red pagadas a los mineros no son reembolsables.

### 5.3 Sin Asesoramiento Financiero
Ningún contenido de esta App constituye asesoramiento financiero o de inversión.

### 5.4 Marco Regulatorio (MiCA)
RavenTag se proporciona como software de código abierto sin custodia. El Desarrollador no posee las claves privadas de los usuarios y no ejerce control ni custodia sobre los activos digitales de los usuarios. La transmisión de transacciones firmadas a través de servidores ElectrumX constituye un reenvío técnico de datos.

---

## 6. Hardware NFC y Resultados de Verificación

Los resultados de verificación se basan en comprobaciones criptográficas. Un resultado positivo no constituye un certificado legal absoluto de autenticidad.

---

## 7. Distribución Oficial y Aviso de Seguridad

### 7.1 Canales Autorizados
1. **GitHub Releases** (https://github.com/ALENOC/RavenTag/releases)
2. **Google Play Store** (para la App Verify)

### 7.2 Verificación de Firma
Las versiones oficiales están firmadas por el Desarrollador y pueden verificarse con `apksigner`.

### 7.3 Exención de Responsabilidad para Versiones No Oficiales
El Desarrollador no asume responsabilidad por daños o pérdidas derivados de versiones no oficiales o modificadas.

---

## 8. Dependencia de la Red

La Aplicación depende del funcionamiento de la red Ravencoin. Para la infraestructura del Desarrollador (`electrumx.raventag.com`), se adoptan medidas razonables de disponibilidad sin ofrecer garantías de tiempo de actividad.

---

## 9. Limitación de Responsabilidad

La Aplicación se proporciona "TAL CUAL" sin garantías. La responsabilidad total del Desarrollador se limita a cero euros (EUR 0), ya que la Aplicación se distribuye gratuitamente.

---

## 10. Modificaciones a la Aplicación y Términos

El Desarrollador se reserva el derecho de actualizar la Aplicación y estos Términos en cualquier momento.

---

## 11. Ley Aplicable y Jurisdicción

Estos Términos se rigen por la legislación italiana. Las disputas estarán sujetas a la jurisdicción exclusiva de los tribunales italianos.

---

## 12. Divisibilidad

Si alguna cláusula se considera inválida, las demás mantendrán su plena vigencia.

---

## 13. Acuerdo Completo

Estos Términos y la Política de Privacidad constituyen el acuerdo completo entre el Usuario y el Desarrollador.

---

## 14. Información de Contacto

**Alessandro Nocentini**
GitHub: https://github.com/ALENOC/RavenTag
Correo electrónico: legal@raventag.com
