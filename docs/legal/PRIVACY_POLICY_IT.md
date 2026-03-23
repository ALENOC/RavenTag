# RavenTag Verify - Informativa sulla Privacy

**Versione 1.0 - Data di entrata in vigore: 2026-03-21**
**Copyright 2026 Alessandro Nocentini. Tutti i diritti riservati.**

---

> **VERSIONE UFFICIALE.** Questo documento in lingua italiana costituisce la versione legalmente vincolante dell'Informativa sulla Privacy. In caso di discrepanza, contraddizione o ambiguita' tra questa versione e qualsiasi traduzione, prevale questa versione italiana.

---

## 1. Introduzione

Questa Informativa sulla Privacy descrive come RavenTag Verify ("App"), sviluppata da Alessandro Nocentini ("Sviluppatore", "noi", "ci"), raccoglie, utilizza e protegge le informazioni quando utilizzi l'App.

Lo Sviluppatore si impegna a ridurre al minimo la raccolta di dati. L'App e' progettata per operare con la quantita' minima di dati necessaria per funzionare.

Questa Informativa sulla Privacy e' conforme a:
- Regolamento Generale sulla Protezione dei Dati dell'UE (GDPR - Regolamento 2016/679)
- Codice italiano in materia di protezione dei dati personali (D.Lgs. 196/2003 come modificato dal D.Lgs. 101/2018)
- Google Play Developer Policy

---

## 2. Titolare del Trattamento

RavenTag e' un protocollo open-source. Ogni distribuzione dell'App si connette a un server backend scelto dall'entita' che ha compilato o configurato l'App (un brand, un produttore o lo Sviluppatore a scopo dimostrativo).

**Backend demo gestito dallo Sviluppatore:**
Lo Sviluppatore gestisce un'istanza backend su raventag.com esclusivamente a scopo dimostrativo e di test dell'infrastruttura. Se stai utilizzando un'istanza dell'App connessa a questo backend demo, il titolare del trattamento per i dati di verifica lato server (Sezione 3.2) e':

**Alessandro Nocentini**
Contatti: https://github.com/ALENOC/RavenTag
legal@raventag.com

Questo backend demo non e' destinato all'uso in produzione da parte degli utenti finali. I brand dovrebbero distribuire la propria infrastruttura backend per le distribuzioni in produzione.

**Backend gestito da brand (uso in produzione):**
In produzione, i brand e i produttori distribuiscono la propria infrastruttura backend e compilano una versione dell'App configurata per connettersi al proprio server. Quando utilizzi un'istanza dell'App connessa al backend di un brand, quel brand e' il titolare autonomo del trattamento per qualsiasi dato di verifica ricevuto dal loro server. Lo Sviluppatore non ha accesso e non si assume alcuna responsabilita' per i dati trattati dai backend di brand di terze parti. Dovresti fare riferimento all'informativa sulla privacy pubblicata dal brand che gestisce quell'istanza dell'App per informazioni su come gestisce i tuoi dati.

---

## 3. Dati Raccolti e Come Vengono Utilizzati

### 3.1 Dati Archiviati Localmente sul Tuo Dispositivo (Mai Trasmessi)

I seguenti dati sono archiviati esclusivamente sul tuo dispositivo e non vengono mai trasmessi a nessun server gestito dallo Sviluppatore:

| Dato | Scopo | Archiviazione |
|---|---|---|
| Frase mnemonica BIP39 (cifrata) | Recupero wallet | Android Keystore (AES-256-GCM) |
| Chiavi private (derivate, cifrate) | Firma transazioni | Android Keystore (AES-256-GCM) |
| Indirizzo wallet (RVN) | Visualizzazione e transazioni | Archiviazione locale cifrata |
| Chiavi admin/operatore (versione Brand) | Gestione asset | Android Keystore (AES-256-GCM) |
| Impostazioni e preferenze App | Configurazione App | Preferenze locali condivise |

**La tua frase mnemonica e le tue chiavi private non lasciano mai il tuo dispositivo.**

### 3.2 Dati Trasmessi Durante la Verifica Tag NFC

Quando esegui la scansione di un tag NFC, l'App invia i seguenti dati a un server backend al fine di eseguire la verifica crittografica:

| Dato | Scopo |
|---|---|
| Nome asset (es. BRAND/PRODOTTO#001) | Identificazione dell'asset sulla blockchain |
| Contatore NFC cifrato (parametro e) | Verifica SUN MAC |
| Valore MAC NFC (parametro m) | Verifica SUN MAC |
| Indirizzo IP del tuo dispositivo | Limitazione della frequenza lato server e registrazione di sicurezza |

Questi dati sono il minimo necessario per verificare l'autenticita' di un tag NFC. La richiesta di verifica non include alcuna informazione personalmente identificabile oltre all'indirizzo IP.

**Quale server riceve questi dati dipende dalla configurazione dell'App:**

- **Backend predefinito gestito da RavenTag**: se l'App si connette al backend RavenTag gestito dallo Sviluppatore (raventag.com), lo Sviluppatore riceve e tratta questi dati come descritto in questa Informativa sulla Privacy.
- **Backend gestito da brand**: se l'App e' stata compilata o configurata da un brand per connettersi al proprio server, il server di quel brand riceve questi dati. Lo Sviluppatore non riceve, accede o tratta questi dati in alcun modo. Il brand e' il titolare autonomo del trattamento e si applica la propria informativa sulla privacy.

Puoi identificare a quale backend si connette l'App controllando l'URL del server visualizzato nelle impostazioni dell'App.

**Conservazione (backend gestito dallo Sviluppatore)**: gli indirizzi IP e i log delle richieste vengono conservati per un massimo di 90 giorni a scopo di sicurezza e limitazione della frequenza, dopo di che vengono eliminati automaticamente.

**Base giuridica (GDPR, backend gestito dallo Sviluppatore)**: Interesse legittimo (Art. 6(1)(f) GDPR) - monitoraggio della sicurezza e prevenzione delle frodi.

### 3.3 Dati Trasmessi Durante le Operazioni Blockchain

Quando esegui operazioni wallet (controllo saldo, invio RVN, emissione asset), l'App comunica con i nodi della rete Ravencoin. Questa comunicazione puo' includere:

| Dato | Scopo |
|---|---|
| Il tuo indirizzo wallet Ravencoin | Interrogazione del saldo e della cronologia delle transazioni |
| Dati delle transazioni | Trasmissione di transazioni alla rete |
| Indirizzo IP del tuo dispositivo | Comunicazione di rete |

La blockchain Ravencoin e' una rete pubblica e decentralizzata. Tutte le transazioni trasmesse alla rete sono permanentemente e pubblicamente visibili a chiunque. Non utilizzare questo wallet per transazioni che desideri mantenere private.

### 3.4 Dati Trasmessi Durante il Caricamento di Immagini Asset

Quando si caricano immagini di asset ospitate su IPFS, l'App si connette a gateway IPFS pubblici (come ipfs.io, cloudflare-ipfs.com). Questi servizi di terze parti possono registrare il tuo indirizzo IP in conformita' con le proprie informative sulla privacy.

### 3.5 Dati della Fotocamera

Se utilizzi la fotocamera per scansionare codici QR all'interno dell'App, i dati della fotocamera vengono elaborati esclusivamente sul tuo dispositivo in tempo reale e non vengono mai archiviati o trasmessi.

### 3.6 Dati NFC

I dati del tag NFC vengono letti localmente sul tuo dispositivo. I dati NFC grezzi (UID, record NDEF) vengono elaborati sul dispositivo e solo i parametri di verifica derivati (asset, e, m) vengono trasmessi come descritto nella Sezione 3.2.

### 3.7 Dati che Non Raccogliamo

Non raccogliamo esplicitamente:

- Il tuo nome, indirizzo email o qualsiasi informazione di identificazione personale.
- Identificatori del dispositivo (IMEI, Android ID, ID pubblicitario).
- Dati di localizzazione.
- Analisi di utilizzo o telemetria.
- Segnalazioni di arresti anomali (a meno che non siano esplicitamente inviate da te).
- Qualsiasi dato a scopo pubblicitario.

---

## 4. Servizi di Terze Parti

L'App interagisce con i seguenti servizi di terze parti. Le loro informative sulla privacy disciplinano le loro pratiche sui dati:

| Servizio | Scopo | Informativa sulla Privacy |
|---|---|---|
| Nodi della Rete Ravencoin | Query blockchain e transazioni | Rete decentralizzata, nessuna politica unica |
| Gateway IPFS (ipfs.io, cloudflare-ipfs.com) | Caricamento immagini asset | Vedi rispettivi fornitori |
| Pinata (pinata.cloud) | Pinning metadati IPFS (opzionale, solo versione Brand) | https://pinata.cloud/privacy |
| Google Play Store | Distribuzione App | https://policies.google.com/privacy |

Lo Sviluppatore non e' responsabile per le pratiche sui dati di questi servizi di terze parti.

---

## 5. Sicurezza dei Dati

Tutti i dati sensibili archiviati sul tuo dispositivo (frase mnemonica, chiavi private, chiavi API) sono cifrati con AES-256-GCM tramite il sistema Android Keystore, che utilizza la sicurezza supportata da hardware ove disponibile.

La comunicazione tra l'App e il server backend dello Sviluppatore e' cifrata con HTTPS/TLS.

Nonostante queste misure, nessun metodo di archiviazione o trasmissione elettronica e' sicuro al 100%. Sei responsabile del mantenimento della sicurezza del tuo dispositivo e della tua frase mnemonica.

---

## 6. Conservazione dei Dati

- **Dati sul dispositivo**: conservati fino all'eliminazione del wallet o alla disinstallazione dell'App.
- **Log delle richieste lato server**: conservati per un massimo di 90 giorni, poi eliminati automaticamente.
- **Dati blockchain**: tutte le transazioni trasmesse alla blockchain Ravencoin sono permanentemente pubbliche e non possono essere eliminate dallo Sviluppatore o da terzi.

---

## 7. I Tuoi Diritti ai Sensi del GDPR

Se sei residente nello Spazio Economico Europeo, hai i seguenti diritti riguardo ai tuoi dati personali:

- **Diritto di accesso**: richiedere una copia dei dati personali che deteniamo su di te (limitato ai log lato server).
- **Diritto di rettifica**: richiedere la correzione di dati inaccurati.
- **Diritto alla cancellazione**: richiedere l'eliminazione dei tuoi dati personali dai nostri server (log del server), fatte salve le obbligazioni legali di conservazione.
- **Diritto alla limitazione del trattamento**: richiedere che limitiamo il modo in cui utilizziamo i tuoi dati.
- **Diritto di opposizione**: opporsi al trattamento basato sull'interesse legittimo.
- **Diritto alla portabilita' dei dati**: ricevere i tuoi dati in un formato strutturato e leggibile da macchina.
- **Diritto di proporre reclamo**: hai il diritto di proporre reclamo al Garante per la protezione dei dati personali (https://www.garanteprivacy.it).

Per esercitare uno di questi diritti, contattaci a: https://github.com/ALENOC/RavenTag / legal@raventag.com

Risponderemo alla tua richiesta entro 30 giorni.

---

## 8. Privacy dei Minori

L'App non e' rivolta ai minori di 18 anni. Non raccogliamo consapevolmente dati personali da minori di 18 anni. Se ritieni che un minore di 18 anni abbia utilizzato l'App e fornito dati personali, contattaci e adotteremo misure per eliminare tali dati.

---

## 9. Trasferimenti Internazionali di Dati

**Backend demo gestito dallo Sviluppatore**: il server backend demo dello Sviluppatore e' situato all'interno dell'Unione Europea. Se accedi a un'istanza demo dell'App dall'esterno dell'UE, i dati della tua richiesta di verifica (Sezione 3.2) saranno trasferiti ed elaborati all'interno dell'UE, in conformita' con i requisiti del GDPR.

**Backend gestito da brand**: nelle distribuzioni in produzione, la posizione geografica del server backend e' determinata esclusivamente dal brand o dal produttore che lo ha distribuito. Lo Sviluppatore non ha alcun controllo e nessuna conoscenza delle posizioni dei server scelte dai brand di terze parti. Le regole applicabili sui trasferimenti internazionali di dati sono quelle del brand che gestisce quella distribuzione. Fai riferimento alla propria informativa sulla privacy del brand per i dettagli.

---

## 10. Modifiche a Questa Informativa sulla Privacy

Potremmo aggiornare questa Informativa sulla Privacy di tanto in tanto. Ti notificheremo le modifiche sostanziali aggiornando la data di entrata in vigore in cima a questo documento e, ove richiesto dalla legge, fornendo avviso all'interno dell'App.

L'uso continuato dell'App dopo qualsiasi modifica costituisce la tua accettazione dell'Informativa sulla Privacy aggiornata.

---

## 11. Contatti

Per qualsiasi domanda, richiesta o reclamo relativo alla privacy:

**Alessandro Nocentini**
https://github.com/ALENOC/RavenTag
legal@raventag.com

Per reclami relativi alla protezione dei dati, puoi anche contattare:
**Garante per la protezione dei dati personali**
https://www.garanteprivacy.it

---

*RavenTag Verify e' un progetto open-source. Codice sorgente: https://github.com/ALENOC/RavenTag*
