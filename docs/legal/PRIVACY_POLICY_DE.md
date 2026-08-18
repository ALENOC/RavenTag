# RavenTag Verify - Datenschutzrichtlinie

**Version 1.2 - Inkrafttreten: 18. August 2026**  
**Copyright 2026-present Alessandro Nocentini. Alle Rechte vorbehalten.**

---

> **OFFIZIELLE VERSION.** Die italienische Fassung ist die rechtlich maßgebliche Version dieser Datenschutzrichtlinie. Bei Abweichungen oder Unklarheiten hat die italienische Fassung Vorrang, vorbehaltlich zwingender Rechte.

---

## 1. Einleitung
Diese Richtlinie beschreibt, wie RavenTag Verify („App“), entwickelt von Alessandro Nocentini („Entwickler“), Informationen verarbeitet. Die App ist als nicht-verwahrende Software mit öffentlich verfügbarem Quellcode unter der RavenTag Source License (RTSL-1.0) konzipiert. Die Architektur soll die Verarbeitung technischer und Netzwerkdaten minimieren.

Die Richtlinie orientiert sich an der Verordnung (EU) 2016/679 (DSGVO), dem italienischen Datenschutzrecht und sonstigem anwendbaren Recht. Sie ist keine pauschale Konformitätszertifizierung.

## 2. Verantwortlicher und Infrastruktur
Für die vom Entwickler betriebenen Systeme ist, soweit anwendbar, Verantwortlicher:

**Alessandro Nocentini**  
GitHub: https://github.com/ALENOC/RavenTag  
E-Mail: legal@raventag.com

Der Entwickler kann ein Demo-Backend unter `raventag.com` / `api.raventag.com` und einen öffentlichen ElectrumX-Endpunkt (`electrumx.raventag.com` / `electrum.raventag.com`) betreiben. Diese vom Entwickler betriebene Infrastruktur ist kein Drittanbieterdienst.

Marken können eigene Backend-Instanzen betreiben. Die App kann außerdem unabhängige ElectrumX-/Ravencoin-Core-Knoten, IPFS-Gateways oder andere Dienste Dritter nutzen. Der Entwickler kontrolliert deren Logging-, Aufbewahrungs-, Sicherheits- oder Datenschutzpraktiken nicht.

## 3. Verarbeitete Daten und technische Architektur

### 3.1 Lokal gespeicherte Wallet-Daten
Seed-Phrase, private Schlüssel und andere sensible Wallet-Zugangsdaten sind für die lokale Speicherung und Nutzung auf dem Gerät vorgesehen. Private Schlüssel und Seed-Phrase werden im normalen Wallet-Betrieb nicht an ElectrumX übertragen.

### 3.2 NFC-Verifizierung und API-Backend
Bei einer NFC-Verifizierung können Asset-Name, verschlüsselter NFC-Zähler/Prüfparameter, NFC-MAC und IP-Adresse an das Backend übertragen werden.

Der Backend-Request-Logger speichert **HTTP-Methode, Pfad, Statuscode, Dauer und IP-Adresse**, nicht jedoch Request- oder Response-Bodies. Diese Metadaten können für Infrastruktursicherheit, Missbrauchsprävention, Rate-Limiting, technische Diagnose und aggregierte Betriebsmetriken genutzt werden.

**Code-seitig bestätigte Aufbewahrung:** Datensätze in `request_logs` und `rate_limit_events` werden durch eine automatische Routine gelöscht, wenn sie älter als 30 Tage sind.

Diese Routine erfasst **nicht** mögliche Console/stdout-, Container-, Betriebssystem-, Reverse-Proxy-, CDN-, Hosting-Provider- oder ElectrumX-Prozesslogs. Deren Aufbewahrung hängt von der tatsächlichen Produktionskonfiguration ab; hierfür wird keine nicht verifizierte feste Frist behauptet.

Soweit anwendbar, beruht die Verarbeitung technischer Metadaten durch Entwickler-Infrastruktur auf berechtigten Interessen nach Art. 6 Abs. 1 lit. f DSGVO, insbesondere Sicherheit, Missbrauchsprävention, Rate-Limiting, Diagnose und verhältnismäßiges Betriebsmonitoring.

### 3.3 Blockchain und ElectrumX
Ein ElectrumX-Server kann je nach Anfrage Quell-IP, Zeitstempel, Verbindungsmetadaten, JSON-RPC-/Script-Hash-Abfragen, Guthaben-/Historien-/UTXO-Abfragen, Transaktionskennungen sowie bereits signierte Rohtransaktionen empfangen oder beobachten. Abfragemuster können eine Korrelation zwischen Netzwerkkennung und öffentlicher Blockchain-Aktivität ermöglichen.

Transaktionen werden vom Nutzer initiiert, von der App erstellt und auf dem Gerät mit nutzerkontrollierten Schlüsseln signiert. ElectrumX kann die bereits signierte Transaktion an das Ravencoin-Netzwerk weiterleiten. ElectrumX besitzt nicht den privaten Schlüssel, bestimmt nicht eigenständig Empfänger oder Betrag und führt kein Verwahrkonto für den Nutzer.

### 3.4 Ravencoin Core, IPFS, Kamera und NFC
Ravencoin-Core-Knoten führen Netzwerkfunktionen wie Synchronisierung, Validierung und P2P-Weiterleitung aus; dadurch besitzen sie nicht die privaten Schlüssel von RavenTag-Nutzern. IPFS-Gateways und andere externe Dienste können IP-/Netzwerkmetadaten nach eigenen Regeln verarbeiten. Kamera-QR-Erkennung erfolgt auf dem Gerät; NFC-Lesen erfolgt lokal, während nur die für die Verifizierung erforderlichen Parameter an das Backend gesendet werden.

## 4. Daten, die nicht absichtlich angefordert werden
Für die normale Nutzung fordert der Entwickler keine Namen, Ausweisdokumente, Postanschriften, IMEI, Android Advertising ID oder präzise Standortdaten an. IP-Adressen und Netzwerkmetadaten können dennoch personenbezogene Daten darstellen.

## 5. Sicherheit und nicht-verwahrende Architektur
RavenTag nutzt lokale Schutzmechanismen und verschlüsselte Netzwerkkanäle, soweit implementiert. Keine technische Maßnahme garantiert absolute Sicherheit. Bei der nicht-verwahrenden Architektur besitzt der Entwickler normalerweise nicht die Schlüssel, die zur Wiederherstellung oder Übertragung von Nutzervermögen erforderlich wären.

## 6. Aufbewahrung
- Lokale Wallet-Daten: entsprechend Geräte-/App-Verhalten bis zur Löschung.
- `request_logs` und `rate_limit_events`: automatische Löschung von Datensätzen älter als 30 Tage.
- Runtime-/Console-, Proxy-, System-, CDN-, Hosting- oder ElectrumX-Logs: abhängig von der tatsächlichen Deployment-Konfiguration.
- Ravencoin-Blockchain: öffentlich replizierte Daten können vom Entwickler nicht einseitig gelöscht oder geändert werden.

## 7. Zwecke und DSGVO-Grundsätze
Technische Metadaten können, soweit erforderlich und verhältnismäßig, für Sicherheit, Missbrauchsabwehr, Rate-Limiting, Fehlerdiagnose sowie technische und aggregierte Betriebsstatistiken verarbeitet werden. Es gelten insbesondere Datenminimierung, Zweckbindung, Speicherbegrenzung, Integrität und Vertraulichkeit.

## 8. Betroffenenrechte
Soweit die DSGVO auf die Verarbeitung durch den Entwickler anwendbar ist, können Betroffene unter den gesetzlichen Voraussetzungen insbesondere Auskunft, Berichtigung, Löschung, Einschränkung und Widerspruch verlangen. Kontakt: legal@raventag.com. Das Beschwerderecht bei der zuständigen Aufsichtsbehörde bleibt unberührt. Diese Rechte betreffen Daten unter Kontrolle des Entwicklers und verleihen ihm keine einseitige Löschungsmacht über bereits in der öffentlichen Ravencoin-Blockchain replizierte Daten.

## 9. Minderjährige
Die App richtet sich nicht an Personen unter 18 Jahren.

## 10. Internationale Übermittlungen
Standorte von Systemen und Anbietern können je nach Infrastruktur variieren. Werden vom Entwickler verarbeitete personenbezogene Daten in ein Land außerhalb des EWR übermittelt, unterliegt die Übermittlung **Kapitel V DSGVO** und muss auf dem jeweils anwendbaren Mechanismus beruhen, z. B. einer einschlägigen Angemessenheitsentscheidung oder geeigneten Garantien nach Art. 46 DSGVO, soweit erforderlich.

Allein der physische Standort eines Servers in den USA oder einem anderen Drittland gilt nicht als Nachweis eines gültigen Übermittlungsmechanismus. Informationen zum tatsächlich eingesetzten Mechanismus können unter legal@raventag.com angefragt werden.

## 11. Nicht-verwahrende Natur und technische Rolle
RavenTag ist nicht-verwahrende Software. Private Schlüssel bleiben unter Kontrolle des Nutzers. Die vom Entwickler betriebene ElectrumX-Infrastruktur ist für technische Blockchain-Abfragen und die Weiterleitung bereits mit nutzerkontrollierten Schlüsseln signierter Transaktionen ausgelegt.

Diese Beschreibung ist **keine pauschale Aussage über Ausnahme, Zulassung oder regulatorische Einordnung nach Verordnung (EU) 2023/1114 (MiCA)**.

## 12. Änderungen und Kontakt
Die Richtlinie kann bei Änderungen von App, Infrastruktur, Verarbeitung oder Rechtslage aktualisiert werden.

**Alessandro Nocentini**  
GitHub: https://github.com/ALENOC/RavenTag  
E-Mail: legal@raventag.com
