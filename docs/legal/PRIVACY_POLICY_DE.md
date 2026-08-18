# RavenTag Verify - Datenschutzrichtlinie

**Version 1.1 - Inkrafttreten: 18. August 2026**
**Copyright 2026-heute Alessandro Nocentini. Alle Rechte vorbehalten.**

---

> **OFFIZIELLE VERSION.** Dieses Dokument in italienischer Sprache stellt die rechtlich bindende Version der Datenschutzrichtlinie dar. Im Falle von Abweichungen, Widersprüchen oder Unklarheiten zwischen dieser Version und einer Übersetzung hat die italienische Version Vorrang.

---

## 1. Einleitung

Diese Datenschutzrichtlinie beschreibt, wie RavenTag Verify („App“), entwickelt von Alessandro Nocentini („Entwickler“, „wir“, „uns“), Informationen sammelt, verwendet und schützt, wenn Sie die App nutzen.

Der Entwickler verpflichtet sich zur Datenminimierung. Die App ist als nicht-verwahrende (non-custodial) Software unter einer Source-Available-Lizenz konzipiert und arbeitet mit der Mindestmenge an Netz- und Technikdaten, die für ihre Funktion unbedingt erforderlich ist.

Diese Datenschutzrichtlinie entspricht:
- der EU-Datenschutz-Grundverordnung (DSGVO - Verordnung EU 2016/679)
- dem italienischen Datenschutzgesetz (D.Lgs. 196/2003 i.d.F.v. D.Lgs. 101/2018)
- der Google Play Entwicklerrichtlinie

---

## 2. Verantwortlicher für die Datenverarbeitung und Infrastrukturkategorien

RavenTag ist ein Protokoll und Software mit öffentlich zugänglichem Quellcode (source-available software). Die App kann sowohl mit vom Entwickler direkt betriebener Infrastruktur als auch mit unabhängiger Infrastruktur von Drittanbietern oder Marken interagieren.

### 2.1 Vom Entwickler betriebenes Demo-Backend
Der Entwickler betreibt eine Backend-Instanz auf `raventag.com` (z. B. `api.raventag.com`) zu Demonstrations-, Test- und Verifizierungszwecken. Wenn Sie eine App-Instanz nutzen, die mit diesem Demo-Backend verbunden ist, ist der Verantwortliche für serverseitige Verifizierungsdaten und Netzwerkprotokolle (Abschnitt 3.2):

**Alessandro Nocentini**
Kontakt: https://github.com/ALENOC/RavenTag
E-Mail: legal@raventag.com

### 2.2 Vom Entwickler betriebene ElectrumX-Infrastruktur
Der Entwickler betreibt einen öffentlichen ElectrumX-Endpunkt (z. B. `electrumx.raventag.com` / `electrum.raventag.com`) vor einem dedizierten Ravencoin Core-Knoten. Wenn sich die App mit diesem Endpunkt für Blockchain-Abfragen oder Transaktionsübertragungen verbindet, wird die Verarbeitung von Verbindungsmetadaten vom Entwickler im Rahmen dieser Richtlinie verwaltet. Diese vom Entwickler betriebene Infrastruktur stellt keinen Drittanbieterdienst dar.

### 2.3 Von Marken betriebenes Backend (Produktivbetrieb)
In der Produktion stellen Marken und Hersteller ihre eigene Backend-Infrastruktur bereit. Wenn Sie eine App nutzen, die mit dem Backend einer Marke verbunden ist, ist diese Marke der eigenständige Verantwortliche für die von ihren Servern verarbeiteten Daten. Der Entwickler hat keinen Zugriff und übernimmt keine Verantwortung für Daten auf Marken-Servern Dritter.

### 2.4 Unabhängige Blockchain-Infrastruktur von Drittanbietern
Die App kann sich auch mit öffentlichen ElectrumX-Knoten oder unabhängigen Ravencoin Core-Knoten von Drittanbietern verbinden. Diese Knoten liegen außerhalb der Kontrolle des Entwicklers.

---

## 3. Verarbeitete Daten und technische Architektur

### 3.1 Lokal auf Ihrem Gerät gespeicherte Daten (Niemals an Entwickler oder ElectrumX übertragen)

Die folgenden sensiblen Daten werden ausschließlich verschlüsselt auf Ihrem Gerät gespeichert und niemals an den Entwickler oder ElectrumX-Server übertragen:

| Daten | Zweck | Speicherung |
|---|---|---|
| BIP39-Mnemonic-Phrase (Seed-Phrase) | Wallet-Generierung und -Wiederherstellung | Android Keystore (AES-256-GCM) |
| Private Schlüssel (abgeleitet, verschlüsselt) | Lokale Transaktionssignierung | Android Keystore (AES-256-GCM) |
| Wallet-Adresse (RVN) | Lokale Anzeige und Berechnung | Verschlüsselter lokaler Speicher |
| Admin-/Bedienschlüssel (Marken-Version) | Lokale Asset-Verwaltung | Android Keystore (AES-256-GCM) |
| App-Einstellungen & Präferenzen | Lokale App-Konfiguration | Geschützte lokale Einstellungen |

**Ihre Mnemonic-Phrase und Ihre privaten Schlüssel verlassen niemals Ihr Gerät.**

### 3.2 Bei der NFC-Tag-Verifizierung übertragene Daten (API-Backend)

Wenn Sie einen NFC-Tag scannen, um die Produktauthentizität zu überprüfen, sendet die App folgende Parameter an das API-Backend:

| Daten | Zweck |
|---|---|
| Asset-Name (z. B. BRAND/PRODUCT#001) | Identifizierung des Assets auf der Ravencoin-Blockchain |
| Verschlüsselter NFC-Zähler (Parameter e) | Kryptografische SUN MAC-Überprüfung |
| NFC-MAC-Wert (Parameter m) | Kryptografische SUN MAC-Überprüfung |
| IP-Adresse Ihres Geräts | Serverseitige Ratenbegrenzung und Netzwerksicherheit |

**Protokollaufbewahrung (vom Entwickler betriebenes Backend)**: IP-Adressen und API-Backend-Netzwerkprotokolle werden maximal 30 Tage aufbewahrt (auf Code-Ebene im Protokollbereinigungs-Middleware überprüft) und danach automatisch gelöscht.

**Rechtsgrundlage (DSGVO)**: Berechtigtes Interesse (Art. 6 Abs. 1 lit. f DSGVO) zur Gewährleistung der Infrastruktursicherheit und Missbrauchsverhinderung.

### 3.3 Datenverarbeitung bei Blockchain- und ElectrumX-Operationen

Bei Guthabenabfragen, Transaktionsverläufen oder Übertragungen kommuniziert die App mit der ElectrumX-Infrastruktur.

**A. Was ein ElectrumX-Server beobachten oder empfangen kann:**
Ein öffentlicher ElectrumX-Server kann Verbindungsdaten und Metadaten beobachten:
- Quell-IP-Adresse des Geräts;
- TLS-Verbindungsmetadaten, Zeitstempel und Anfragefrequenz;
- JSON-RPC-Protokollabfragen und Script-Hash-Abfragen;
- Guthabenanfragen, Transaktionsverläufe und UTXOs;
- Transaktions-IDs (TxID) und Asset-Metadaten;
- Bereits signierte Roh-Transaktionen (raw signed transactions) zur Übertragung.

Aufgrund von Abfragemustern können diese Informationen technisch Korrelationen zwischen Netzwerkkennungen (wie der IP-Adresse) und öffentlicher Blockchain-Aktivität ermöglichen.

> **Expliziter Sicherheitshinweis:**
> Private Schlüssel und Mnemonic-Phrasen werden vom ElectrumX-Server niemals benötigt und während des normalen Wallet-Betriebs nicht als Teil des Betriebs übertragen.

**B. Ablauf von Transaktionserstellung und -signierung:**
Für jede über das Wallet ausgeführte Transaktion:
1. Der Benutzer leitet die Transaktion in der App ein;
2. Die App erstellt die Roh-Transaktion lokal auf dem Gerät;
3. Die Transaktion wird auf dem Gerät mit den Schlüsseln des Benutzers signiert;
4. Die App sendet die bereits signierte Transaktion an den ElectrumX-Server;
5. ElectrumX überträgt (relay/broadcast) die signierte Transaktion an Ravencoin Core-Knoten zur Einbindung in Blöcke.

Die ElectrumX-Infrastruktur besitzt keine privaten Schlüssel des Benutzers, kann keine eigenen Signaturen erstellen, entscheidet nicht über Empfänger oder Beträge und verwahrt keine Gelder.

**C. Rolle öffentlicher Ravencoin Core-Knoten:**
Ein öffentlicher Ravencoin Core-Knoten führt ausschließlich Infrastrukturfunktionen aus (Blockchain-Synchronisierung, Block- und Transaktionsvalidierung, P2P-Weiterleitung). Er verwahrt keine Kundengelder und besitzt keine privaten Schlüssel.

### 3.4 Laden von Asset-Bildern (IPFS Gateways)
Zum Anzeigen von auf IPFS gehosteten Asset-Bildern kann sich die App mit öffentlichen IPFS-Gateways verbinden (z. B. ipfs.io, cloudflare-ipfs.com).

### 3.5 Kamera- und NFC-Daten
- **Kamera**: Ausschließlich lokal auf dem Gerät zum Lesen von QR-Codes verwendet.
- **NFC**: Tag-Auslesung erfolgt lokal; nur abgeleitete Verifizierungsparameter (asset, e, m) werden übertragen.

### 3.6 Daten, die wir nicht sammeln
Der Entwickler sammelt keine Namen, E-Mail-Adressen, Hardware-IDs (IMEI), Standortdaten oder Telemetriedaten.

---

## 4. Dienste und Knoten von Drittanbietern

| Dienst / Knoten | Zweck | Datenschutzhinweis |
|---|---|---|
| Unabhängige ElectrumX-Knoten Dritter | Blockchain-Abfragen & Fallback | Entwickler kontrolliert deren Protokolle nicht. Drittbetreiber können IP-Adressen und Roh-Transaktionen sehen. |
| Unabhängige Ravencoin Core-Knoten | P2P-Validierung & Weiterleitung | Dezentrales Netzwerk. |
| Öffentliche IPFS-Gateways | Medien- & Metadatenladen | Von Drittanbietern betrieben. |
| Google Play Store | App-Bereitstellung | Datenschutzbestimmungen von Google LLC. |

---

## 5. Datensicherheit und nicht-verwahrende Architektur

Sensible Daten auf dem Gerät sind per AES-256-GCM über das Android Keystore-System geschützt. Netzverbindungen zum Entwickler-Backend nutzen verschlüsselte HTTPS/TLS-Kanäle.

---

## 6. Datenspeicherung (Speicherbegrenzung)

- **Gerätedaten**: Gespeichert bis zur Wallet-Löschung oder App-Deinstallation.
- **Entwickler-Backend-Protokolle**: Maximal 30 Tage aufbewahrt (gemäß automatischem Bereinigungscode) und anschließend dauerhaft gelöscht.
- **Entwickler-ElectrumX-Protokolle**: Nur so lange aufbewahrt, wie für Diagnosen und Sicherheit unbedingt erforderlich nach Betreibereinstellungen.
- **Öffentliche Ravencoin-Blockchain-Daten**: Transaktionen auf der Blockchain sind dauerhaft öffentlich und unlöschbar.

---

## 7. IP-Adressverarbeitung und DSGVO-Grundsätze

IP-Adressen und Netzwerkmetadaten werden nach den DSGVO-Grundsätzen der Datenminimierung, Zweckbindung, Speicherbegrenzung (30 Tage Löschung) und Integrität verarbeitet. Rechtsgrundlage ist das berechtigte Interesse (Art. 6 Abs. 1 lit. f DSGVO).

---

## 8. Ihre Rechte nach der DSGVO

Soweit nach der DSGVO anwendbar, haben Sie das Recht auf Auskunft (Art. 15), Berichtigung (Art. 16), Löschung (Art. 17), Einschränkung (Art. 18) und Widerspruch (Art. 21 DSGVO) bezüglich der vom Entwickler verarbeiteten Server-Protokolle. Kontakt: legal@raventag.com

Sie haben das Recht auf Beschwerde bei einer Datenschutzbehörde.

---

## 9. Datenschutz bei Minderjährigen

Die App richtet sich nicht an Personen unter 18 Jahren.

---

## 10. Internationale Datenübertragungen

Die Infrastruktur des Entwicklers befindet sich in Rechenzentren in der EU oder den USA gemäß DSGVO-Standards.

---

## 11. Regulatorischer Rahmen und MiCA-Begrifflichkeiten

RavenTag ist als nicht-verwahrende Software unter der RavenTag Source License (RTSL-1.0) konzipiert. Der Entwickler hält keine privaten Schlüssel der Nutzer, übt keine Kontrolle oder Verwahrung über Krypto-Werte (RVN oder Token) aus und erbringt keine Kryptowertpapier-Verwahr- oder Verwaltungsdienste gemäß Verordnung (EU) 2023/1114 (MiCA). Die Tätigkeit der ElectrumX-Infrastruktur beschränkt sich auf das technische Weiterleiten von Netzwerkdaten und signierten Transaktionen.

---

## 12. Änderungen dieser Datenschutzrichtlinie

Der Entwickler behält sich das Recht vor, diese Datenschutzrichtlinie zu aktualisieren.

---

## 13. Kontaktinformationen

**Alessandro Nocentini**
GitHub: https://github.com/ALENOC/RavenTag
E-Mail: legal@raventag.com
