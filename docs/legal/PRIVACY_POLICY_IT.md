# RavenTag Verify - Informativa sulla Privacy

**Versione 1.2 - Data di entrata in vigore: 18 agosto 2026**  
**Copyright 2026-present Alessandro Nocentini. Tutti i diritti riservati.**

---

> **VERSIONE UFFICIALE.** Questo documento in lingua italiana costituisce la versione legalmente vincolante dell'Informativa sulla Privacy. In caso di discrepanza, contraddizione o ambiguità tra questa versione e qualsiasi traduzione, prevale questa versione italiana, fatti salvi eventuali diritti inderogabili applicabili.

---

## 1. Introduzione

Questa Informativa sulla Privacy descrive come RavenTag Verify ("App"), sviluppata da Alessandro Nocentini ("Sviluppatore"), tratta informazioni quando utilizzi l'App.

L'App è progettata come software non custodiale (non-custodial) con codice sorgente pubblicamente disponibile secondo la RavenTag Source License (RTSL-1.0). L'architettura mira a ridurre al minimo i dati di rete e tecnici trattati.

La presente Informativa è redatta con riferimento al Regolamento (UE) 2016/679 (GDPR), al Codice italiano in materia di protezione dei dati personali e alle altre norme applicabili. Non costituisce una certificazione generale di conformità normativa.

---

## 2. Titolare del Trattamento e Categorie di Infrastruttura

RavenTag può interagire con infrastrutture gestite direttamente dallo Sviluppatore e con infrastrutture indipendenti gestite da brand o terze parti.

### 2.1 Backend demo gestito dallo Sviluppatore
Lo Sviluppatore può gestire un backend su `raventag.com` (ad es. `api.raventag.com`) per dimostrazione, test dell'infrastruttura e verifica delle risorse. Per i dati trattati da tale backend, il titolare del trattamento è:

**Alessandro Nocentini**  
GitHub: https://github.com/ALENOC/RavenTag  
Email: legal@raventag.com

### 2.2 Infrastruttura ElectrumX gestita dallo Sviluppatore
Lo Sviluppatore gestisce un endpoint pubblico ElectrumX, attualmente identificato nel progetto come `electrumx.raventag.com`, collegato a un nodo Ravencoin Core. Quando l'App utilizza tale endpoint, il trattamento dei dati di connessione da parte di questa infrastruttura è riconducibile allo Sviluppatore e non a un operatore terzo.

### 2.3 Backend gestiti da brand
Brand e produttori possono distribuire istanze autonome del backend. Quando un'istanza dell'App è configurata per utilizzare un backend gestito da un brand, tale soggetto determina autonomamente finalità e modalità del trattamento effettuato dai propri sistemi, salvo diversi accordi giuridici applicabili.

### 2.4 Infrastruttura blockchain indipendente di terze parti
L'App può collegarsi a nodi ElectrumX, nodi Ravencoin Core, gateway IPFS o altri servizi gestiti da soggetti indipendenti. Lo Sviluppatore non controlla le loro pratiche di logging, conservazione, sicurezza o privacy.

---

## 3. Dati Trattati e Architettura Tecnica

### 3.1 Dati conservati localmente sul dispositivo

I seguenti dati sensibili sono progettati per essere generati o conservati localmente sul dispositivo e non sono richiesti dai server ElectrumX per il normale funzionamento del wallet:

| Dato | Scopo | Archiviazione prevista |
|---|---|---|
| Frase mnemonica BIP39 (seed phrase) | Generazione e recupero wallet | Android Keystore / archiviazione locale protetta |
| Chiavi private derivate | Firma locale delle transazioni | Android Keystore / archiviazione locale protetta |
| Indirizzo wallet RVN | Visualizzazione e operazioni wallet | Archiviazione locale |
| Chiavi admin/operatore, ove presenti | Funzioni Brand | Archiviazione locale protetta |
| Impostazioni e preferenze | Configurazione App | Preferenze locali |

**Le chiavi private e la seed phrase non vengono trasmesse al server ElectrumX durante il normale funzionamento del wallet.**

### 3.2 Verifica NFC e backend API

Quando esegui la verifica di un tag NFC, l'App può trasmettere al backend dati tecnici necessari alla verifica, tra cui:

| Dato | Scopo |
|---|---|
| Nome asset | Identificazione dell'asset sulla blockchain Ravencoin |
| Contatore NFC cifrato / parametro di verifica | Verifica crittografica |
| Valore MAC NFC | Verifica crittografica |
| Indirizzo IP | Sicurezza, prevenzione abusi, rate limiting e gestione tecnica delle richieste |

Il middleware di logging del backend registra metadati della richiesta quali **metodo HTTP, percorso, codice di stato, durata e indirizzo IP**. Il middleware non registra il corpo della richiesta o della risposta. Tali metadati possono essere utilizzati per sicurezza, prevenzione degli abusi, rate limiting, diagnostica tecnica e metriche operative aggregate.

**Conservazione verificata a livello di codice:** i record persistiti nelle tabelle applicative `request_logs` e `rate_limit_events` sono soggetti a una routine automatica che elimina i record più vecchi di 30 giorni.

Questa routine **non governa** eventuali log di console/stdout, container, sistema operativo, reverse proxy, CDN, hosting provider o processo ElectrumX. L'eventuale conservazione di tali log dipende dalla configurazione effettiva dell'ambiente di produzione e non viene descritta in questa Informativa con un periodo fisso non verificato.

**Base giuridica:** ove applicabile, il trattamento dei metadati tecnici da parte dell'infrastruttura gestita dallo Sviluppatore si basa sul legittimo interesse ai sensi dell'art. 6, par. 1, lett. f) GDPR per sicurezza dell'infrastruttura, prevenzione degli abusi, rate limiting, diagnostica tecnica e monitoraggio operativo proporzionato.

### 3.3 Operazioni blockchain ed ElectrumX

Quando l'App esegue interrogazioni di saldo, cronologia, UTXO o trasmette una transazione, comunica con un server ElectrumX.

Un server ElectrumX può tecnicamente osservare o ricevere, a seconda delle richieste effettuate:
- indirizzo IP di origine;
- timestamp, frequenza delle richieste e metadati di connessione;
- query JSON-RPC e script-hash;
- richieste di saldo, cronologia e UTXO;
- identificativi di transazione e metadati blockchain;
- transazioni grezze già firmate inviate per il broadcast.

I modelli di interrogazione possono consentire correlazioni tra identificativi di rete e attività pubblica sulla blockchain.

**Creazione e firma delle transazioni:** la transazione è iniziata dall'utente, costruita dall'App e firmata sul dispositivo mediante chiavi controllate dall'utente. ElectrumX può ricevere la transazione già firmata e inoltrarla alla rete Ravencoin. ElectrumX non possiede la chiave privata dell'utente, non determina autonomamente destinatario o importo e non mantiene un conto custodiale per l'utente.

### 3.4 Nodo Ravencoin Core
Un nodo Ravencoin Core svolge funzioni di infrastruttura di rete quali sincronizzazione, validazione e propagazione peer-to-peer. Non possiede per questo motivo le chiavi private degli utenti e non mantiene conti custodiali del wallet RavenTag.

### 3.5 Gateway IPFS e contenuti esterni
L'App può utilizzare gateway IPFS o altre risorse esterne per caricare immagini e metadati. Gli operatori di tali servizi possono ricevere l'indirizzo IP e altri metadati di rete secondo le proprie pratiche.

### 3.6 Fotocamera e NFC
- **Fotocamera:** utilizzata sul dispositivo per la lettura di codici QR; l'App non necessita di inviare le immagini al backend per tale funzione.
- **NFC:** la lettura avviene sul dispositivo; al backend vengono trasmessi solo i parametri necessari alla verifica descritti sopra.

### 3.7 Dati che lo Sviluppatore non raccoglie intenzionalmente
Lo Sviluppatore non richiede per il normale utilizzo dell'App nomi, documenti di identità, indirizzi postali, IMEI, Android Advertising ID o dati di geolocalizzazione precisa. Ciò non modifica il fatto che indirizzi IP e altri metadati di rete possano costituire dati personali quando trattati dall'infrastruttura.

---

## 4. Servizi e Infrastrutture di Terze Parti

Le infrastrutture indipendenti possono comprendere nodi ElectrumX, nodi Ravencoin Core, gateway IPFS, sistemi operativi, app store, provider di rete e servizi di hosting non gestiti direttamente dallo Sviluppatore.

Quando utilizzi infrastrutture indipendenti, il relativo operatore può trattare dati di rete e blockchain secondo le proprie finalità, basi giuridiche e tempi di conservazione. Lo Sviluppatore non può garantire o controllare le pratiche di tali soggetti.

---

## 5. Sicurezza e Architettura Non Custodiale

RavenTag utilizza meccanismi di protezione locali e canali di rete cifrati ove previsti dall'implementazione. Nessuna misura tecnica può garantire sicurezza assoluta contro ogni vulnerabilità, compromissione del dispositivo o attacco di rete.

La natura non custodiale del wallet significa che lo Sviluppatore non dispone normalmente delle chiavi necessarie per recuperare o trasferire i fondi dell'utente.

---

## 6. Conservazione dei Dati

- **Dati locali del wallet:** fino alla cancellazione del wallet, alla cancellazione dei dati dell'App o alla disinstallazione, secondo il funzionamento del dispositivo.
- **`request_logs` e `rate_limit_events` del backend gestito dallo Sviluppatore:** eliminazione automatica dei record più vecchi di 30 giorni secondo la routine presente nel codice backend.
- **Log runtime/console, reverse proxy, sistema, CDN, hosting o ElectrumX:** il periodo dipende dalla configurazione effettiva dell'ambiente e non è determinato dalla routine di pulizia del database applicativo sopra descritta.
- **Blockchain Ravencoin:** i dati registrati sulla blockchain pubblica sono replicati da una rete decentralizzata e non possono essere cancellati o modificati unilateralmente dallo Sviluppatore.

---

## 7. Finalità e Principi GDPR

Per l'infrastruttura gestita dallo Sviluppatore, i metadati tecnici possono essere trattati, ove necessario e proporzionato, per:
- sicurezza dell'infrastruttura;
- prevenzione di abusi e attacchi;
- rate limiting;
- diagnosi di errori e problemi operativi;
- statistiche tecniche e metriche operative aggregate.

Il trattamento è soggetto ai principi di minimizzazione, limitazione della finalità, limitazione della conservazione, integrità e riservatezza previsti dal GDPR.

---

## 8. Diritti dell'Interessato

Ove il GDPR si applichi al trattamento effettuato dallo Sviluppatore, l'interessato può esercitare, nei limiti e alle condizioni previste dalla legge, i diritti di accesso, rettifica, cancellazione, limitazione e opposizione, nonché gli altri diritti applicabili.

Le richieste possono essere inviate a: legal@raventag.com

Resta il diritto di proporre reclamo all'autorità di controllo competente, incluso il Garante per la protezione dei dati personali quando competente.

I diritti esercitabili nei confronti dello Sviluppatore riguardano i dati sotto il suo controllo e non attribuiscono allo Sviluppatore il potere di cancellare unilateralmente dati già registrati e replicati sulla blockchain pubblica Ravencoin.

---

## 9. Minori

L'App non è destinata a persone di età inferiore a 18 anni. Lo Sviluppatore non intende raccogliere consapevolmente dati di minori attraverso il normale utilizzo dell'App.

---

## 10. Trasferimenti Internazionali

L'ubicazione dei sistemi e dei fornitori può variare in base alla configurazione dell'infrastruttura. Qualora dati personali trattati dallo Sviluppatore siano trasferiti verso un Paese al di fuori dello Spazio Economico Europeo, il trasferimento è soggetto al **Capo V del GDPR** e deve basarsi sul meccanismo applicabile, ad esempio una decisione di adeguatezza quando pertinente oppure garanzie appropriate ai sensi dell'art. 46 GDPR, ove richieste.

La mera ubicazione di un server negli Stati Uniti o in un altro Paese terzo non viene considerata, da sola, prova dell'esistenza di un meccanismo di trasferimento valido. Informazioni sul meccanismo applicabile all'infrastruttura effettivamente utilizzata possono essere richieste a legal@raventag.com.

Per backend o servizi gestiti da soggetti indipendenti, il relativo operatore è responsabile delle proprie scelte di localizzazione e trasferimento secondo la legge applicabile.

---

## 11. Natura Non Custodiale e Ruolo Tecnico dell'Infrastruttura

RavenTag è progettato come software non custodiale. Le chiavi private restano sotto il controllo dell'utente e lo Sviluppatore non mantiene conti custodiali di crypto-asset per gli utenti.

L'infrastruttura ElectrumX gestita dallo Sviluppatore è progettata per funzioni tecniche di interrogazione della blockchain e di inoltro alla rete di transazioni già firmate mediante chiavi controllate dall'utente. ElectrumX non determina autonomamente il destinatario o l'importo della transazione e non firma al posto dell'utente.

Questa sezione descrive l'architettura tecnica e **non costituisce una dichiarazione generale di esenzione, autorizzazione o classificazione regolamentare ai sensi del Regolamento (UE) 2023/1114 (MiCA) o di altre normative**.

---

## 12. Modifiche a questa Informativa

Lo Sviluppatore può aggiornare la presente Informativa quando cambiano l'App, l'infrastruttura, le pratiche di trattamento o il quadro normativo. La versione e la data di efficacia sono indicate all'inizio del documento.

---

## 13. Contatti

**Alessandro Nocentini**  
GitHub: https://github.com/ALENOC/RavenTag  
Email: legal@raventag.com
