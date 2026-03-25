# RavenTag Verify - Datenschutzerklarung

**Version 1.0 - Datum des Inkrafttretens: 2026-03-21**
**Copyright 2026-present Alessandro Nocentini. Alle Rechte vorbehalten.**

---

> **UBERSETZUNGSHINWEIS.** Dieses Dokument ist eine unverbindliche Ubersetzung zur Information. Die italienische Version ([PRIVACY_POLICY_IT.md](PRIVACY_POLICY_IT.md)) ist die einzig rechtsverbindliche Fassung. Im Falle einer Abweichung, eines Widerspruchs oder einer Unklarheit zwischen dieser Ubersetzung und dem italienischen Text gilt der italienische Text.

---

## 1. Einfuhrung

Diese Datenschutzerklarung beschreibt, wie RavenTag Verify ("App"), entwickelt von Alessandro Nocentini ("Entwickler", "wir", "uns"), Informationen bei der Nutzung der App erhebt, verwendet und schutzt.

Der Entwickler ist bestrebt, die Datenerhebung zu minimieren. Die App ist darauf ausgelegt, mit der minimal notwendigen Datenmenge zu funktionieren.

Diese Datenschutzerklarung entspricht:
- EU-Datenschutz-Grundverordnung (DSGVO - Verordnung 2016/679)
- Italienisches Datenschutzgesetz (D.Lgs. 196/2003 in der durch D.Lgs. 101/2018 geanderten Fassung)
- Google Play Entwicklerrichtlinie

---

## 2. Verantwortlicher

RavenTag ist ein quelloffenes Protokoll. Jede Bereitstellung der App verbindet sich mit einem Backend-Server, der von der Einheit gewahlt wird, die die App kompiliert oder konfiguriert hat (eine Marke, ein Hersteller oder der Entwickler fur Demonstrationszwecke).

**Vom Entwickler betriebenes Demo-Backend:**
Der Entwickler betreibt eine Backend-Instanz unter raventag.com ausschliesslich fur Demonstrations- und Infrastrukturtestzwecke. Wenn Sie eine App-Instanz verwenden, die mit diesem Demo-Backend verbunden ist, ist der Verantwortliche fur serverseitige Verifizierungsdaten (Abschnitt 3.2):

**Alessandro Nocentini**
Kontakt: https://github.com/ALENOC/RavenTag
legal@raventag.com

Dieses Demo-Backend ist nicht fur den Produktionseinsatz durch Endnutzer gedacht. Marken sollten fur Produktionseinsatze ihre eigene Backend-Infrastruktur bereitstellen.

**Von einer Marke betriebenes Backend (Produktionseinsatz):**
Im Produktionsbetrieb stellen Marken und Hersteller ihre eigene Backend-Infrastruktur bereit und kompilieren eine Version der App, die so konfiguriert ist, dass sie sich mit ihrem eigenen Server verbindet. Wenn Sie eine App-Instanz verwenden, die mit dem Backend einer Marke verbunden ist, ist diese Marke der eigenstandige Verantwortliche fur alle von ihrem Server empfangenen Verifizierungsdaten. Der Entwickler hat keinen Zugang zu und ubernimmt keine Verantwortung fur Daten, die von Backend-Diensten dritter Marken verarbeitet werden. Bezuglich der Datenverarbeitung sollten Sie die Datenschutzerklarung der Marke konsultieren, die diese App-Instanz betreibt.

---

## 3. Erhobene Daten und deren Verwendung

### 3.1 Lokal auf Ihrem Gerat Gespeicherte Daten (Niemals Ubertragen)

Die folgenden Daten werden ausschliesslich auf Ihrem Gerat gespeichert und niemals an Server des Entwicklers ubertragen:

| Daten | Zweck | Speicherung |
|---|---|---|
| BIP39-mnemonische Phrase (verschlusselt) | Wallet-Wiederherstellung | Android Keystore (AES-256-GCM) |
| Private Schlussel (abgeleitet, verschlusselt) | Transaktionssignierung | Android Keystore (AES-256-GCM) |
| Wallet-Adresse (RVN) | Anzeige und Transaktionen | Lokaler verschlusselter Speicher |
| Admin/Operator-Schlussel (Brand-Version) | Asset-Verwaltung | Android Keystore (AES-256-GCM) |
| App-Einstellungen und -Praferenzen | App-Konfiguration | Lokale gemeinsame Einstellungen |

**Ihre mnemonische Phrase und privaten Schlussel verlassen niemals Ihr Gerat.**

### 3.2 Bei der NFC-Tag-Verifizierung Ubertragene Daten

Wenn Sie einen NFC-Tag scannen, sendet die App zur kryptografischen Verifizierung folgende Daten an einen Backend-Server:

| Daten | Zweck |
|---|---|
| Asset-Name (z.B. MARKE/PRODUKT#001) | Identifizierung des Assets auf der Blockchain |
| Verschlusselter NFC-Zahler (Parameter e) | SUN MAC-Verifizierung |
| NFC MAC-Wert (Parameter m) | SUN MAC-Verifizierung |
| IP-Adresse Ihres Gerats | Serverseitige Ratenbegrenzung und Sicherheitsprotokollierung |

Diese Daten sind das Minimum, das zur Verifizierung der Authentizitat eines NFC-Tags erforderlich ist. Die Verifizierungsanfrage enthalt keine personlich identifizierbaren Informationen uber die IP-Adresse hinaus.

**Welcher Server diese Daten empfangt, hangt von der App-Konfiguration ab:**

- **Standard/RavenTag-betriebenes Backend**: Wenn sich die App mit dem vom Entwickler betriebenen RavenTag-Backend (raventag.com) verbindet, empfangt und verarbeitet der Entwickler diese Daten wie in dieser Datenschutzerklarung beschrieben.
- **Von einer Marke betriebenes Backend**: Wenn die App von einer Marke kompiliert oder konfiguriert wurde, um sich mit ihrem eigenen Server zu verbinden, empfangt der Server dieser Marke diese Daten. Der Entwickler empfangt, greift auf oder verarbeitet diese Daten in keiner Weise. Die Marke ist eigenstandiger Verantwortlicher und deren Datenschutzerklarung gilt.

Sie konnen erkennen, mit welchem Backend sich die App verbindet, indem Sie die in den App-Einstellungen angezeigte Server-URL prufen.

**Aufbewahrung (vom Entwickler betriebenes Backend)**: IP-Adressen und Anforderungsprotokolle werden maximal 90 Tage zu Sicherheits- und Ratenbegrenzungszwecken aufbewahrt, danach werden sie automatisch geloscht.

**Rechtsgrundlage (DSGVO, vom Entwickler betriebenes Backend)**: Berechtigtes Interesse (Art. 6(1)(f) DSGVO) - Sicherheitsmonitoring und Betrugsvorbeugung.

### 3.3 Bei Blockchain-Operationen Ubertragene Daten

Wenn Sie Wallet-Operationen durchfuhren (Saldo prufen, RVN senden, Assets ausgeben), kommuniziert die App mit Ravencoin-Netzwerkknoten. Diese Kommunikation kann folgendes umfassen:

| Daten | Zweck |
|---|---|
| Ihre Ravencoin-Wallet-Adresse | Saldo- und Transaktionsverlaufsabfrage |
| Transaktionsdaten | Ubertragung von Transaktionen an das Netzwerk |
| IP-Adresse Ihres Gerats | Netzwerkkommunikation |

Die Ravencoin-Blockchain ist ein offentliches, dezentrales Netzwerk. Alle ans Netzwerk ubertragenen Transaktionen sind dauerhaft und offentlich fur jeden sichtbar. Verwenden Sie dieses Wallet nicht fur Transaktionen, die Sie privat halten mochten.

### 3.4 Beim Laden von Asset-Bildern Ubertragene Daten

Beim Laden von IPFS-gehosteten Asset-Bildern verbindet sich die App mit offentlichen IPFS-Gateways (wie ipfs.io, cloudflare-ipfs.com). Diese Drittanbieterdienste konnen Ihre IP-Adresse gemas ihren eigenen Datenschutzerklarungen protokollieren.

### 3.5 Kameradaten

Wenn Sie die Kamera zum Scannen von QR-Codes in der App verwenden, werden die Kameradaten ausschliesslich in Echtzeit auf Ihrem Gerat verarbeitet und niemals gespeichert oder ubertragen.

### 3.6 NFC-Daten

NFC-Tag-Daten werden lokal auf Ihrem Gerat gelesen. Rohe NFC-Daten (UID, NDEF-Eintrager) werden gerate-seitig verarbeitet, und nur die abgeleiteten Verifizierungsparameter (Asset, e, m) werden wie in Abschnitt 3.2 beschrieben ubertragen.

### 3.7 Daten, die Wir Nicht Erheben

Wir erheben ausdruecklich nicht:

- Ihren Namen, Ihre E-Mail-Adresse oder andere personlich identifizierbare Informationen.
- Gerateidentifikatoren (IMEI, Android ID, Werbe-ID).
- Standortdaten.
- Nutzungsanalysen oder Telemetrie.
- Absturzberichte (es sei denn, diese werden von Ihnen explizit ubermittelt).
- Daten fur Werbezwecke.

---

## 4. Drittanbieterdienste

Die App interagiert mit folgenden Drittanbieterdiensten. Deren Datenschutzerklarungen regeln ihre Datenpraktiken:

| Dienst | Zweck | Datenschutzerklarung |
|---|---|---|
| Ravencoin-Netzwerkknoten | Blockchain-Abfragen und Transaktionen | Dezentrales Netzwerk, keine einheitliche Richtlinie |
| IPFS-Gateways (ipfs.io, cloudflare-ipfs.com) | Asset-Bild-Laden | Siehe jeweilige Anbieter |
| Pinata (pinata.cloud) | IPFS-Metadaten-Pinning (optional, nur Brand-Version) | https://pinata.cloud/privacy |
| Google Play Store | App-Vertrieb | https://policies.google.com/privacy |

Der Entwickler ist nicht fur die Datenpraktiken dieser Drittanbieterdienste verantwortlich.

---

## 5. Datensicherheit

Alle auf Ihrem Gerat gespeicherten sensiblen Daten (mnemonische Phrase, private Schlussel, API-Schlussel) werden mit AES-256-GCM uber das Android-Keystore-System verschlusselt, das hardwaregesicherte Sicherheit verwendet, wo verfugbar.

Die Kommunikation zwischen der App und dem Backend-Server des Entwicklers ist mit HTTPS/TLS verschlusselt.

Trotz dieser Massnahmen ist keine Methode der elektronischen Speicherung oder Ubertragung zu 100 % sicher. Sie sind fur die Aufrechterhaltung der Sicherheit Ihres Gerats und Ihrer mnemonischen Phrase verantwortlich.

---

## 6. Datenspeicherung

- **Daten auf dem Gerat**: gespeichert, bis Sie das Wallet loschen oder die App deinstallieren.
- **Serverseitige Anforderungsprotokolle**: maximal 90 Tage aufbewahrt, dann automatisch geloscht.
- **Blockchain-Daten**: Alle an die Ravencoin-Blockchain ubertragenen Transaktionen sind dauerhaft offentlich und konnen weder vom Entwickler noch von Dritten geloscht werden.

---

## 7. Ihre Rechte nach der DSGVO

Wenn Sie sich im Europaischen Wirtschaftsraum befinden, haben Sie folgende Rechte bezuglich Ihrer personlichen Daten:

- **Auskunftsrecht**: Anforderung einer Kopie der personlichen Daten, die wir uber Sie besitzen (beschrankt auf serverseitige Protokolle).
- **Recht auf Berichtigung**: Anforderung der Korrektur ungenauer Daten.
- **Recht auf Loschung**: Anforderung der Loschung Ihrer personlichen Daten von unseren Servern (Serverprotokolle), vorbehaltlich gesetzlicher Aufbewahrungspflichten.
- **Recht auf Einschrankung der Verarbeitung**: Anforderung, dass wir die Verwendung Ihrer Daten einschranken.
- **Widerspruchsrecht**: Widerspruch gegen die auf berechtigtem Interesse beruhende Verarbeitung.
- **Recht auf Datenuebertragbarkeit**: Empfang Ihrer Daten in einem strukturierten, maschinenlesbaren Format.
- **Recht zur Beschwerde**: Sie haben das Recht, eine Beschwerde bei der italienischen Datenschutzbehorde einzureichen (Garante per la protezione dei dati personali, https://www.garanteprivacy.it).

Um eines dieser Rechte auszuuben, kontaktieren Sie uns unter: https://github.com/ALENOC/RavenTag / legal@raventag.com

Wir antworten auf Ihre Anfrage innerhalb von 30 Tagen.

---

## 8. Datenschutz fur Minderjahrige

Die App richtet sich nicht an Kinder unter 18 Jahren. Wir erheben wissentlich keine personlichen Daten von Kindern unter 18 Jahren. Wenn Sie glauben, dass ein Kind unter 18 Jahren die App genutzt und personliche Daten bereitgestellt hat, kontaktieren Sie uns und wir werden Schritte zur Loschung dieser Daten einleiten.

---

## 9. Internationale Datenuebermittlungen

**Vom Entwickler betriebenes Demo-Backend**: Der Demo-Backend-Server des Entwicklers befindet sich innerhalb der Europaischen Union. Wenn Sie von ausserhalb der EU auf eine Demo-App-Instanz zugreifen, werden Ihre Verifizierungsanfragedaten (Abschnitt 3.2) in Ubereinstimmung mit den DSGVO-Anforderungen in die EU ubertragen und dort verarbeitet.

**Von einer Marke betriebenes Backend**: Bei Produktionseinsatzen wird der geografische Standort des Backend-Servers ausschliesslich von der Marke oder dem Hersteller bestimmt, der ihn bereitgestellt hat. Der Entwickler hat keine Kontrolle uber und kein Wissen von den von Drittmarken gewahlten Serverstandorten. Die geltenden Regeln fur internationale Datenuebermittlungen sind jene der Marke, die diesen Einsatz betreibt. Weitere Details finden Sie in der Datenschutzerklarung der jeweiligen Marke.

---

## 10. Anderungen dieser Datenschutzerklarung

Wir konnen diese Datenschutzerklarung gelegentlich aktualisieren. Wir werden Sie uber wesentliche Anderungen informieren, indem wir das Datum des Inkrafttretens oben in diesem Dokument aktualisieren und, soweit gesetzlich erforderlich, eine Benachrichtigung in der App bereitstellen.

Ihre weitere Nutzung der App nach Anderungen stellt Ihre Annahme der aktualisierten Datenschutzerklarung dar.

---

## 11. Kontakt

Fur datenschutzbezogene Fragen, Anfragen oder Beschwerden:

**Alessandro Nocentini**
https://github.com/ALENOC/RavenTag
legal@raventag.com

Bei Datenschutzbeschwerden konnen Sie sich auch an folgende Stelle wenden:
**Garante per la protezione dei dati personali**
https://www.garanteprivacy.it

---

*RavenTag Verify ist ein quelloffenes Projekt. Quellcode: https://github.com/ALENOC/RavenTag*
