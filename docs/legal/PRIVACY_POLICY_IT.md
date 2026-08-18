# RavenTag Verify - Informativa sulla Privacy

**Versione 1.1 - Data di entrata in vigore: 18 agosto 2026**
**Copyright 2026-present Alessandro Nocentini. Tutti i diritti riservati.**

---

> **VERSIONE UFFICIALE.** Questo documento in lingua italiana costituisce la versione legalmente vincolante dell'Informativa sulla Privacy. In caso di discrepanza, contraddizione o ambiguita' tra questa versione e qualsiasi traduzione, prevale questa versione italiana.

---

## 1. Introduzione

Questa Informativa sulla Privacy descrive come RavenTag Verify ("App"), sviluppata da Alessandro Nocentini ("Sviluppatore", "noi", "ci"), raccoglie, utilizza e protegge le informazioni quando utilizzi l'App.

Lo Sviluppatore si impegna a ridurre al minimo la raccolta di dati. L'App e' progettata come software non custodiale (non-custodial) con licenza source-available e opera con la quantita' minima di dati di rete e tecnici strettamente necessaria al suo funzionamento.

Questa Informativa sulla Privacy e' redatta in conformita' a:
- Regolamento Generale sulla Protezione dei Dati dell'UE (GDPR - Regolamento UE 2016/679)
- Codice italiano in materia di protezione dei dati personali (D.Lgs. 196/2003 come modificato dal D.Lgs. 101/2018)
- Google Play Developer Policy

---

## 2. Titolare del Trattamento e Categorie di Infrastruttura

RavenTag e' un protocollo e software con codice sorgente disponibile pubblicamente (source-available software). L'App puo' interagire sia con infrastrutture gestite direttamente dallo Sviluppatore, sia con infrastrutture indipendenti di terze parti o di brand.

### 2.1 Backend demo gestito dallo Sviluppatore
Lo Sviluppatore gestisce un'istanza backend su `raventag.com` (es. `api.raventag.com`) a scopo dimostrativo, di test dell'infrastruttura e di verifica delle risorse. Se utilizzi un'istanza dell'App connessa a questo backend demo, il titolare del trattamento per i dati di verifica e i log di rete lato server (Sezione 3.2) e':

**Alessandro Nocentini**
Contatti: https://github.com/ALENOC/RavenTag
Email: legal@raventag.com

### 2.2 Infrastruttura ElectrumX gestita dallo Sviluppatore
Lo Sviluppatore gestisce un endpoint pubblico ElectrumX (es. `electrumx.raventag.com` / `electrum.raventag.com`) posto a monte di un nodo Ravencoin Core dedicato. Quando l'App si connette a questo endpoint specifico per l'interrogazione della blockchain o l'inoltro di transazioni, il trattamento dei metadati di connessione e' gestito dallo Sviluppatore ai sensi della presente Informativa. Tale infrastruttura gestita dallo Sviluppatore non costituisce un servizio di terze parti.

### 2.3 Backend gestito da brand (uso in produzione)
In produzione, i brand e i produttori possono distribuire la propria infrastruttura backend. Quando utilizzi un'istanza dell'App configurata per connettersi al backend di un determinato brand, quel brand e' il titolare autonomo del trattamento per i dati elaborati dai propri server. Lo Sviluppatore non ha accesso e non si assume responsabilita' per i dati trattati dai backend di brand di terze parti.

### 2.4 Infrastruttura blockchain indipendente di terze parti
L'App puo' inoltre connettersi a nodi ElectrumX pubblici o nodi Ravencoin Core indipendenti gestiti da terzi. Tali nodi sono totalmente al di fuori del controllo dello Sviluppatore e operano secondo le rispettive politiche di gestione.

---

## 3. Dati Trattati e Architettura Tecnica

### 3.1 Dati Archiviati Localmente sul Tuo Dispositivo (Mai Trasmessi allo Sviluppatore o a ElectrumX)

I seguenti dati sensibili sono generati e conservati esclusivamente sul tuo dispositivo in forma cifrata e non vengono mai trasmessi a nessun server o infrastruttura gestita dallo Sviluppatore o a server ElectrumX:

| Dato | Scopo | Archiviazione |
|---|---|---|
| Frase mnemonica BIP39 (seed phrase) | Generazione e recupero wallet | Android Keystore (AES-256-GCM) |
| Chiavi private (derivate, cifrate) | Firma locale delle transazioni | Android Keystore (AES-256-GCM) |
| Indirizzo wallet (RVN) | Visualizzazione e calcolo locale | Archiviazione locale cifrata |
| Chiavi admin/operatore (versione Brand) | Gestione locale degli asset | Android Keystore (AES-256-GCM) |
| Impostazioni e preferenze App | Configurazione locale dell'App | Preferenze locali cifrate/protette |

**La tua frase mnemonica e le tue chiavi private non lasciano mai il tuo dispositivo.**

### 3.2 Dati Trasmessi Durante la Verifica Tag NFC (Backend API)

Quando esegui la scansione di un tag NFC per verificare l'autenticita' di un prodotto, l'App invia i seguenti parametri al backend API per l'elaborazione crittografica:

| Dato | Scopo |
|---|---|
| Nome asset (es. BRAND/PRODOTTO#001) | Identificazione dell'asset sulla blockchain Ravencoin |
| Contatore NFC cifrato (parametro e) | Verifica crittografica SUN MAC |
| Valore MAC NFC (parametro m) | Verifica crittografica SUN MAC |
| Indirizzo IP del tuo dispositivo | Limitazione della frequenza (rate-limiting) e sicurezza di rete |

**Conservazione log backend gestito dallo Sviluppatore**: gli indirizzi IP e i log di rete del backend API vengono conservati per un periodo massimo di 30 giorni (verificato a livello di codice nel middleware di pulizia dei log del backend), dopo di che vengono eliminati automaticamente.

**Base giuridica (GDPR)**: Legittimo interesse (Art. 6(1)(f) GDPR) per garantire la sicurezza dell'infrastruttura, prevenire abusi e limitare attacchi cibernetici.

### 3.3 Dati Elaborati Durante le Operazioni Blockchain ed ElectrumX

Quando l'App esegue interrogazioni di saldo, consultazioni di cronologia o invii di transazioni, comunica con l'infrastruttura ElectrumX (sia gestita dallo Sviluppatore sia di terze parti).

**A. Che cosa un server ElectrumX puo' osservare o ricevere:**
Un server ElectrumX pubblico o di rete puo' osservare dati e metadati di connessione quali:
- Indirizzo IP di origine del dispositivo;
- Metadati della connessione TLS, timestamp e frequenza di richiesta;
- Query di protocollo JSON-RPC e interrogazioni basate su script-hash;
- Richieste di bilancio, storico transazioni e UTXO associate a determinati indirizzi;
- Identificativi di transazione (TxID) e metadati di asset;
- Transazioni grezze gia' firmate (raw signed transactions) sottomesse per il broadcast.

In base ai modelli di interrogazione del wallet, tali informazioni possono tecnicamente permettere correlazioni tra identificativi di rete (come l'indirizzo IP) e l'attivita' sulla blockchain pubblica.

> **Dichiarazione Esplicita di Sicurezza:**
> Le chiavi private e le frasi mnemoniche non sono mai necessarie per il server ElectrumX e non vengono trasmesse ad esso durante il normale funzionamento del wallet.

**B. Processo di creazione e firma delle transazioni:**
Per ogni transazione eseguita dal wallet:
1. L'utente avvia l'operazione dall'interfaccia dell'App;
2. L'App costruisce la transazione grezza (raw transaction) localmente sul dispositivo;
3. La transazione viene firmata crittograficamente sul dispositivo utilizzando le chiavi private controllate esclusivamente dall'utente;
4. L'App invia la transazione gia' firmata al server ElectrumX;
5. ElectrumX trasmette (relay/broadcast) la transazione firmata ai nodi della rete Ravencoin Core per l'inclusione nei blocchi.

L'infrastruttura ElectrumX non possiede la chiave privata dell'utente, non puo' generare una firma valida per conto dell'utente, non decide l'importo o il destinatario della transazione, non prende possesso dei RVN dell'utente e non mantiene alcun conto o saldo custodiale.

**C. Ruolo dei nodi pubblici Ravencoin Core:**
Un nodo pubblico Ravencoin Core svolge esclusivamente funzioni infrastrutturali di rete, quali sincronizzazione della blockchain, validazione di blocchi e transazioni, comunicazione peer-to-peer e propagazione delle transazioni. Il nodo Core non detiene fondi dei clienti, non gestisce account utente, non possiede chiavi private, non firma per conto degli utenti e non esercita la custodia sui token RVN.

### 3.4 Caricamento Immagini Asset (Gateway IPFS)
Per la visualizzazione di immagini di asset ospitate su IPFS, l'App puo' connettersi a gateway IPFS pubblici (es. ipfs.io, cloudflare-ipfs.com). Tali fornitori terzi possono registrare l'indirizzo IP del dispositivo in conformita' alle proprie informative sulla privacy.

### 3.5 Dati della Fotocamera e NFC
- **Fotocamera**: utilizzata esclusivamente sul dispositivo per la lettura di codici QR in tempo realtime; nessun dato visivo viene salvato o trasmesso.
- **NFC**: la lettura dei tag avviene localmente; solo i parametri di verifica derivati (asset, e, m) vengono trasmessi al backend come descritto nella Sezione 3.2.

### 3.6 Dati che Non Raccogliamo
Lo Sviluppatore non raccoglie:
- Nomi, indirizzi email o identificativi personali diretti degli utenti;
- Identificatori unici hardware (IMEI, Android ID, ID pubblicitari);
- Dati di geolocalizzazione precisa;
- Analisi comportamentali o telemetria di tracciamento commerciale.

---

## 4. Servizi e Nodi di Terze Parti

L'App interagisce con servizi e nodi di rete che possono essere gestiti da terze parti indipendenti:

| Servizio / Nodo | Scopo | Note sulla Privacy |
|---|---|---|
| Nodi ElectrumX indipendenti di terze parti | Interrogazione blockchain e fallback | Lo Sviluppatore non controlla i log di nodi terzi. L'operatore terzo puo' osservare l'indirizzo IP, le query di bilancio e le transazioni grezze inviate per il broadcast. |
| Nodi della Rete Ravencoin Core indipendenti | Validazione e propagazione P2P | Rete decentralizzata distribuita. |
| Gateway IPFS pubblici | Caricamento media e metadati asset | Gestiti da fornitori terzi. |
| Google Play Store | Distribuzione dell'App | Politiche privacy di Google Inc. |

---

## 5. Sicurezza dei Dati e Architettura Non Custodiale

Tutti i dati sensibili archiviati sul dispositivo (frase mnemonica, chiavi private) sono protetti tramite cifratura AES-256-GCM supportata dal sistema Android Keystore con isolamento hardware dove disponibile.

Le comunicazioni di rete tra l'App e le infrastrutture gestite dallo Sviluppatore avvengono tramite canali cifrati HTTPS/TLS o TLS con pinning/verifica dei certificati.

---

## 6. Conservazione dei Dati (Storage Limitation)

- **Dati sul dispositivo**: conservati fino alla cancellazione del wallet o alla disinstallazione dell'App.
- **Log del backend gestito dallo Sviluppatore**: conservati per un massimo di 30 giorni (in conformita' alla routine automatica di pulizia dei log implementata nel codice backend), e successivamente eliminati in modo permanente.
- **Log dell'infrastruttura ElectrumX e di rete dello Sviluppatore**: conservati per il tempo minimo strettamente necessario a finalita' di diagnostica e sicurezza di rete secondo le configurazioni dell'operatore.
- **Dati sulla blockchain pubblica Ravencoin**: le transazioni confermate sulla blockchain Ravencoin sono permanentemente pubbliche e non possono essere modificate, cancellate o rimosse dallo Sviluppatore o da terzi.

---

## 7. Trattamento dell'Indirizzo IP e Principi GDPR

L'indirizzo IP e i metadati di rete elaborati dall'infrastruttura gestita dallo Sviluppatore sono trattati nel rispetto dei principi GDPR di:
- **Minimizzazione dei dati**: vengono memorizzati solo i metadati tecnici indispensabili;
- **Limitazione della finalita'**: utilizzati esclusivamente per sicurezza, rate-limiting e mitigazione di attacchi Denial of Service (DoS);
- **Limitazione della conservazione**: cancellazione automatica entro 30 giorni per i log di richiesta backend;
- **Integrita' e riservatezza**: protezione delle infrastrutture tramite misure tecniche adeguate.

Non si rilascia alcuna dichiarazione generica o assoluta di "totale conformita' GDPR", ma si applicano rigorosamente le tutele tecniche e legali previste dalla normativa vigente.

---

## 8. I Tuoi Diritti ai Sensi del GDPR

Ove applicabile ai sensi del GDPR, hai il diritto di esercitare nei confronti dello Sviluppatore (limitatamente ai dati elaborati dai propri server gestiti, quali i log di rete):
- Diritto di accesso (Art. 15 GDPR);
- Diritto di rettifica (Art. 16 GDPR);
- Diritto alla cancellazione / oblio (Art. 17 GDPR), fatte salve le informazioni immutabili gia' iscritte sulla blockchain pubblica;
- Diritto di limitazione del trattamento (Art. 18 GDPR);
- Diritto di opposizione (Art. 21 GDPR) al trattamento basato su legittimo interesse.

Per esercitare tali diritti, puoi contattare lo Sviluppatore all'indirizzo: legal@raventag.com

Hai inoltre il diritto di proporre reclamo all'Autorita' Garante per la Protezione dei Dati Personali (https://www.garanteprivacy.it).

---

## 9. Privacy dei Minori

L'App non e' destinata ai minori di 18 anni. Lo Sviluppatore non raccoglie consapevolmente dati da minori.

---

## 10. Trasferimenti Internazionali di Dati

Le infrastrutture gestite dallo Sviluppatore sono collocate all'interno di data center situati nell'Unione Europea o negli Stati Uniti, garantendo livelli adeguati di protezione dei dati ai sensi del GDPR. Qualora utilizzi un'istanza connessa a backend di brand di terze parti, la posizione dei server e' stabilita autonomamente da ciascun brand.

---

## 11. Inquadramento Normativo e Termini MiCA

RavenTag e' progettato e distribuito come software non custodiale con licenza source-available (RavenTag Source License RTSL-1.0). Lo Sviluppatore non detiene le chiavi private degli utenti, non esercita alcun controllo o custodia sui crypto-asset (RVN o token) degli utenti, e non fornisce servizi di custodia o amministrazione di cripto-attivita' per conto di terzi ai sensi del Regolamento (UE) 2023/1114 (MiCA). L'attivita' dell'infrastruttura ElectrumX gestita dallo Sviluppatore consiste nell'inoltro tecnico di dati di rete e transazioni firmate su protocollo aperto.

---

## 12. Modifiche a Questa Informativa

Lo Sviluppatore si riserva il diritto di aggiornare la presente Informativa sulla Privacy. Le modifiche avranno efficacia dalla data di pubblicazione della versione aggiornata.

---

## 13. Contatti

Per qualsiasi chiarimento o richiesta in materia di privacy:

**Alessandro Nocentini**
GitHub: https://github.com/ALENOC/RavenTag
Email: legal@raventag.com
Garante Privacy: https://www.garanteprivacy.it
