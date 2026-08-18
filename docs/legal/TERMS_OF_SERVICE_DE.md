# RavenTag Verify - Nutzungsbedingungen

**Version 1.1 - Inkrafttreten: 18. August 2026**
**Copyright 2026-heute Alessandro Nocentini. Alle Rechte vorbehalten.**

---

> **OFFIZIELLE VERSION.** Dieses Dokument in italienischer Sprache stellt die rechtlich bindende Version der Nutzungsbedingungen dar. Im Falle von Abweichungen, Widersprüchen oder Unklarheiten zwischen dieser Version und einer Übersetzung hat die italienische Version Vorrang.

---

## 1. Annahme der Bedingungen

Beim ersten Start präsentiert die App diese Nutzungsbedingungen und die Datenschutzrichtlinie. Sie müssen beide Dokumente durch Ankreuzen der entsprechenden Kontrollkästchen ausdrücklich akzeptieren, bevor Sie fortfahren können.

Durch das Herunterladen, Installieren oder die fortgesetzte Nutzung der App bestätigen Sie ("Nutzer"), an diese Bedingungen gebunden zu sein.

Diese Bedingungen stellen eine rechtsverbindliche Vereinbarung zwischen Ihnen und Alessandro Nocentini ("Entwickler"), dem Autor von RavenTag Verify, dar.

---

## 2. Beschreibung der App und Lizenznatur

RavenTag Verify ist eine mobile Anwendung, die Folgendes bietet:

- **NFC-Tag-Verifizierung**: Auslesen und kryptografische Verifizierung von NTAG 424 DNA NFC-Chips, die mit Ravencoin-Blockchain-Assets verknüpft sind, unter Verwendung des RavenTag Protocols v1 (RTP-1).
- **Nicht-verwahrendes Ravencoin-Wallet**: Generierung, lokale Speicherung und autonome Verwaltung eines nicht-verwahrenden (non-custodial) BIP39/BIP44 HD-Wallets für die Ravencoin-Blockchain (RVN).
- **Asset-Verwaltung** (nur Marken-Version): Ausgabe, Übertragung und lokale Verwaltung von Ravencoin-Assets.

Die App ist ein Software-Tool zur Interaktion in Eigenverwahrung (Self-Custody) mit der Ravencoin-Blockchain und NFC-Hardware. Sie ist kein Finanzdienst, keine Börse, keine Bank und kein Finanzprodukt.

Die App und der Quellcode stehen unter der **RavenTag Source License (RTSL-1.0)**, einer Lizenz für Software mit öffentlich zugänglichem Quellcode (source-available software), die bestimmte kommerzielle Nutzungen einschränkt. RavenTag stellt keine Open-Source-Software gemäß OSI-Definitionen dar.

---

## 3. Anforderung und Nutzungsbereich

Sie müssen mindestens 18 Jahre alt sein, um diese App zu nutzen.

### 3.1 Verbrauchernutzung der Verify-App
Die NFC-Verifizierungsfunktion ist für jeden Verbraucher bestimmt, der die Echtheit eines mit NFC ausgestatteten Produkts prüfen möchte.

### 3.2 Wallet-Funktionalität und Eigenverwahrung (Self-Custody)
Die Ravencoin-Wallet-Funktion beinhaltet Eigenverwahrung (Self-Custody), direkte Verwaltung und Übertragung digitaler Vermögenswerte auf einer öffentlichen Blockchain. Sie handeln in voller Eigenverantwortung auf eigenes finanzielles Risiko.

### 3.3 Quellcode und RTSL-1.0-Lizenz
Die Beschränkung der kommerziellen Nutzung in der Source-Available-Lizenz RTSL-1.0 gilt ausschließlich für Entwickler und Unternehmen, die den Quellcode nutzen. Endnutzer der App sind davon nicht betroffen.

---

## 4. Nicht-verwahrendes Wallet und Transaktionsarchitektur

### 4.1 Keine Verwahrung durch den Entwickler
RavenTag Verify stellt ein ausschließlich nicht-verwahrendes Wallet bereit. Das bedeutet:
- Der Entwickler hält, speichert, verwaltet oder kontrolliert **niemals** Ihre privaten Schlüssel, Mnemonic-Phrasen oder Gelder.
- Sie sind der einzige Verwalter (Self-Custodian) Ihrer kryptografischen Schlüssel und digitalen Vermögenswerte.
- Der Entwickler kann unter keinen Umständen Transaktionen autorisieren oder Ihr Wallet wiederherstellen.

### 4.2 Erstellung, Signierung und Übertragung von Transaktionen
Für jede über die App ausgeführte Transaktion:
1. Der Nutzer leitet die Transaktion in der App ein;
2. Die App erstellt die Roh-Transaktion lokal auf dem Gerät;
3. Die Transaktion wird lokal auf dem Gerät mit den Schlüsseln des Nutzers signiert;
4. Die App überträgt die **bereits signierte** Transaktion an die ElectrumX-Infrastruktur;
5. ElectrumX leitet die signierte Transaktion an das Ravencoin-Netzwerk weiter.

Die ElectrumX-Infrastruktur besitzt keine privaten Schlüssel der Nutzer, kann keine eigenen Signaturen erstellen, entscheidet nicht über Empfänger/Beträge und führt keine Verwahrkonten.

### 4.3 Mnemonic-Phrase (Seed-Phrase) und Nutzerverantwortung
Sie müssen Ihre 12-Wörter BIP39 Mnemonic-Phrase sofort handschriftlich notieren und sicher offline aufbewahren. **Der Verlust Ihrer Seed-Phrase führt zum dauerhaften und unwiederbringlichen Verlust aller Gelder.**

### 4.4 Gerätesicherheit
Sie sind für die Sicherheit Ihres Geräts selbst verantwortlich. Der Entwickler haftet nicht für Schäden durch Malware, Verlust des Geräts oder rooted/jailbroken Betriebssysteme.

---

## 5. Blockchain-, Finanzrisiken und regulatorischer Rahmen

### 5.1 Natur von Ravencoin und Netzwerkinfrastruktur
- **Vom Entwickler betriebene Infrastruktur**: Der Entwickler betreibt den öffentlichen ElectrumX-Endpunkt `electrumx.raventag.com` / `electrum.raventag.com`. Diese stellt keine Drittanbieter-Infrastruktur dar.
- **Unabhängige Infrastruktur Dritter**: Die App kann mit unabhängigen Knoten Dritter interagieren.
- **Rolle von Ravencoin Core-Knoten**: Ein öffentlicher Core-Knoten führt Validierungs- und Weiterleitungsfunktionen aus. Er verwahrt keine Gelder und besitzt keine privaten Schlüssel.

### 5.2 Finanzrisiko, Volatilität und Unumkehrbarkeit
Transaktionen auf der Ravencoin-Blockchain sind **unumkehrbar**. Netzwerkeinzahlungen und Transaktionsgebühren sind nicht erstattungsfähig.

### 5.3 Keine Finanzberatung
Kein Inhalt dieser App stellt eine Finanz-, Anlage-, Rechts- oder Steuerberatung dar.

### 5.4 Regulatorischer Rahmen (MiCA)
RavenTag wird als nicht-verwahrende Source-Available-Software bereitgestellt. Der Entwickler hält keine privaten Schlüssel der Nutzer und übt keine Kontrolle oder Verwahrung über Vermögenswerte aus.

---

## 6. NFC-Hardware und IPFS-Metadaten Dritter

Verifizierungsergebnisse basieren auf kryptografischen Prüfungen. Inhalte von IPFS-Gateways Dritter werden von unabhängigen Parteien erstellt.

---

## 7. Offizielle Bereitstellung und Sicherheitswarnung

### 7.1 Autorisierte Kanäle
1. **GitHub Releases** (https://github.com/ALENOC/RavenTag/releases)
2. **Google Play Store** (für die Verify-App)

### 7.2 Haftungsausschluss für inoffizielle Builds
Der Entwickler lehnt jede Haftung für Schäden ab, die durch die Nutzung inoffizieller oder veränderter Builds entstehen.

---

## 8. Netzwerkabhängigkeit und Infrastrukturverfügbarkeit

Für die vom Entwickler betriebene Infrastruktur (`electrumx.raventag.com`) werden angemessene Maßnahmen ergriffen, ohne Uptime-Garantien oder eine Pflicht zur ewigen Wartung einzugehen.

---

## 9. Allgemeine Haftungsbeschränkung und Salvatorische Klausel

### 9.1 Ausschluss indirekter Schäden
Soweit gesetzlich zulässig, haftet der Entwickler nicht für indirekte Schäden, Folgeschäden oder entgangenen Gewinn.

### 9.2 Haftungshöchstgrenze
Da die App kostenlos bereitgestellt wird:
- Für gewerbliche Nutzer ist die Haftung im gesetzlich maximal zulässigen Umfang auf null Euro (EUR 0) beschränkt.
- Für Verbraucher ist die Haftung auf das gesetzlich zwingend vorgeschriebene Mindestmaß beschränkt.

### 9.3 Zwingende gesetzliche Vorbehaltsklausel
Nichts in diesen Bedingungen schließt die Haftung des Entwicklers für Vorsatz oder grobe Fahrlässigkeit (Art. 1229 ital. ZGB) oder nach zwingendem Verbraucherschutzrecht aus.

---

## 10. Kein Treuhandverhältnis und keine Überwachungspflicht

Die Nutzung begründet kein Treuhandverhältnis. Der Entwickler ist nicht verpflichtet, Nutzeraktivitäten zu überwachen.

---

## 11. Änderungen der App und Bedingungen

Der Entwickler behält sich das Recht vor, die App und diese Bedingungen aus berechtigten Gründen anzupassen.

---

## 12. Anwendbares Recht und Gerichtsstand

Es gilt italienisches Recht. Zwingende Verbraucherschutzrechte nach der Rom-I-Verordnung bleiben unberührt.

---

## 13. Salvatorische Klausel und Keine Verwirkung

Sollte eine Bestimmung unwirksam sein, bleiben die übrigen Bestimmungen wirksam.

---

## 14. Gesamte Vereinbarung

Diese Bedingungen und die Datenschutzrichtlinie bilden die gesamte Vereinbarung.

---

## 15. Kontaktinformationen

**Alessandro Nocentini**
GitHub: https://github.com/ALENOC/RavenTag
E-Mail: legal@raventag.com
