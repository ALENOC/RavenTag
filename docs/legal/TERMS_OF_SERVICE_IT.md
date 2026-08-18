# RavenTag Verify - Termini di Servizio

**Versione 1.1 - Data di entrata in vigore: 18 agosto 2026**
**Copyright 2026-present Alessandro Nocentini. Tutti i diritti riservati.**

---

> **VERSIONE UFFICIALE.** Questo documento in lingua italiana costituisce la versione legalmente vincolante dei Termini di Servizio. In caso di discrepanza, contraddizione o ambiguita' tra questa versione e qualsiasi traduzione, prevale questa versione italiana.

---

## 1. Accettazione dei Termini

Al primo avvio, l'App presenta questi Termini di Servizio e l'Informativa sulla Privacy. Devi accettare esplicitamente entrambi i documenti spuntando le caselle corrispondenti prima di poter procedere. La spunta di quelle caselle costituisce la tua accettazione espressa e informata di questi Termini. Se non accetti, non devi utilizzare l'App.

Scaricando, installando o continuando a utilizzare l'App dopo l'accettazione, tu ("Utente") confermi di essere legalmente vincolato da questi Termini. Se non sei d'accordo con questi Termini nella loro interezza, devi immediatamente disinstallare e smettere di utilizzare l'App.

Questi Termini costituiscono un accordo legalmente vincolante tra te e Alessandro Nocentini ("Sviluppatore"), autore di RavenTag Verify.

---

## 2. Descrizione dell'App

RavenTag Verify e' un'applicazione mobile che fornisce:

- **Verifica tag NFC**: lettura e verifica crittografica di chip NFC NTAG 424 DNA collegati ad asset blockchain Ravencoin, utilizzando il RavenTag Protocol v1 (RTP-1).
- **Wallet Ravencoin Non Custodiale**: generazione, archiviazione locale e gestione autonoma di un wallet HD BIP39/BIP44 non custodiale per la blockchain Ravencoin (RVN).
- **Gestione asset** (solo versione Brand): emissione, trasferimento e gestione locale di asset Ravencoin collegati a prodotti fisici.

L'App e' uno strumento software per interagire in modalita' non custodiale con la blockchain Ravencoin e l'hardware NFC. Non e' un servizio finanziario, un exchange, una banca, un intermediario di custodia, ne' un prodotto finanziario o di investimento.

---

## 3. Requisiti e Ambito di Utilizzo

Devi avere almeno 18 anni per utilizzare questa App. Utilizzando l'App, dichiari e garantisci di avere almeno 18 anni e di avere la capacita' legale per stipulare questi Termini nella tua giurisdizione.

### 3.1 Uso consumer dell'App Verify
La funzionalita' di verifica tag NFC dell'App RavenTag Verify e' progettata per qualsiasi consumatore che desideri verificare l'autenticita' di un prodotto fisico dotato di chip NFC. L'uso di questa funzionalita' non richiede alcuna capacita' professionale.

### 3.2 Funzionalita' wallet e auto-custodia
La funzionalita' wallet Ravencoin comporta l'auto-custodia (self-custody), la gestione diretta e il trasferimento di asset digitali su una blockchain pubblica decentralizzata. Utilizzando queste funzionalita' riconosci di agire in piena autonomia, sotto la tua esclusiva responsabilita' e a tuo rischio finanziario, avendo piena consapevolezza dei rischi descritti nella Sezione 5.

### 3.3 Codice sorgente e infrastruttura
La restrizione all'uso professionale contenuta nella RavenTag Source License (RTSL-1.0) si applica esclusivamente agli sviluppatori, ai brand e alle entita' che distribuiscono, biforcano o utilizzano in altro modo il codice sorgente di RavenTag. Tale restrizione non si applica agli utenti finali dell'App che la utilizzano esclusivamente per scansionare tag NFC o gestire il proprio wallet in auto-custodia.

---

## 4. Wallet Non Custodiale e Architettura delle Transazioni

### 4.1 Nessuna Custodia da Parte dello Sviluppatore
RavenTag Verify fornisce un wallet Ravencoin esclusivamente non custodiale. Questo significa che:
- Lo Sviluppatore **non** detiene, archivia, gestisce, controlla o ha accessibilita' alle tue chiavi private, alla tua frase mnemonica o ai tuoi fondi in nessun momento.
- Sei l'unico ed esclusivo custode (self-custodian) delle tue chiavi crittografiche, dei tuoi fondi e dei tuoi asset digitali.
- Lo Sviluppatore non ha alcuna capacita' tecnica di autorizzare transazioni, bloccare i tuoi fondi o recuperare la tua frase mnemonica o le tue chiavi in nessuna circostanza.

### 4.2 Creazione, Firma e Trasmissione delle Transazioni
Per ogni transazione effettuata tramite l'App:
1. L'Utente avvia la transazione dall'interfaccia dell'App;
2. L'App costruisce la transazione grezza (raw transaction) localmente sul dispositivo;
3. La transazione viene firmata localmente sul dispositivo utilizzando le chiavi private controllate dall'Utente;
4. L'App trasmette la transazione **gia' firmata** all'infrastruttura ElectrumX (gestita dallo Sviluppatore o da terzi);
5. ElectrumX relaya/inoltra la transazione firmata alla rete Ravencoin Core per l'inclusione nel registro distribuito.

L'infrastruttura ElectrumX non possiede le chiavi private dell'Utente, non puo' generare autonomamente firme valide, non decide il destinatario o l'importo della transazione, non prende possesso dei RVN dell'Utente e non gestisce conti o saldi custodiali.

### 4.3 Frase Mnemonica (Seed Phrase)
Quando crei un wallet, l'App genera una frase mnemonica BIP39 di 12 parole ("seed phrase"). Devi:
- Annotare immediatamente la tua seed phrase e conservarla in un luogo sicuro e offline.
- Non condividere mai la tua seed phrase con nessuno, incluso lo Sviluppatore.
- Non conservare mai la tua seed phrase in forma digitale non protetta o su servizi cloud di terzi.

**La perdita della tua seed phrase comporta la perdita permanente e irrecuperabile di tutti i fondi e gli asset associati al tuo wallet. Lo Sviluppatore non puo' ripristinare l'accesso al tuo wallet in nessuna circostanza.**

### 4.4 Sicurezza del Dispositivo
Sei responsabile del mantenimento della sicurezza del tuo dispositivo. Lo Sviluppatore non e' responsabile per eventuali perdite di fondi derivanti da malware, smarrimento del dispositivo, accessi non autorizzati o compromissioni del sistema operativo.

---

## 5. Rischi Blockchain, Finanziari e Inquadramento Tecnico

### 5.1 Natura di Ravencoin e Infrastruttura di Rete
Ravencoin (RVN) e' una rete blockchain decentralizzata open-source gestita da miner e nodi indipendenti.
- **Infrastruttura dello Sviluppatore**: Lo Sviluppatore opera l'endpoint ElectrumX pubblico `electrumx.raventag.com` / `electrum.raventag.com` a supporto dell'App.
- **Infrastruttura Indipendente di Terze Parti**: L'App puo' interagire anche con nodi ElectrumX o Ravencoin Core gestiti da terzi indipendenti. Lo Sviluppatore non controlla i server di terze parti.
- **Ruolo dei Nodi Ravencoin Core**: Un nodo pubblico Ravencoin Core svolge funzioni di sincronizzazione, validazione di blocchi e transazioni, e propagazione P2P. Il nodo Core non detiene fondi dei clienti, non gestisce account utente, non possiede chiavi private e non esercita custodia sui fondi RVN.

### 5.2 Riconoscimento del Rischio Finanziario ed Irreversibilita'
Utilizzando le funzionalita' wallet di questa App, riconosci ed accetti esplicitamente che:
- RVN e gli asset su blockchain sono asset digitali soggetti a forte volatilita' di prezzo.
- Le transazioni blockchain registrate sulla rete Ravencoin sono **irreversibili**. Una volta confermata, una transazione non puo' essere annullata, modificata o rimborsata dallo Sviluppatore.
- Le commissioni di rete (miner fees) sono pagate direttamente alla rete e non sono rimborsabili.
- Lo Sviluppatore non e' responsabile per perdite finanziarie derivanti da errori dell'utente, variazioni di mercato o guasti della rete blockchain.

### 5.3 Nessuna Consulenza Finanziaria
Nulla in questa App o nelle comunicazioni dello Sviluppatore costituisce consulenza finanziaria, d'investimento o legale.

### 5.4 Inquadramento Regolatorio (MiCA)
RavenTag e' fornito come software non custodiale open-source. Lo Sviluppatore non detiene le chiavi private degli utenti e non esercita alcun controllo o custodia sui crypto-asset degli utenti. La trasmissione di transazioni firmate attraverso server ElectrumX costituisce un'attivita' meramente tecnica di inoltro dati su protocollo distribuito. L'Utente e' il solo responsabile dell'adempimento degli obblighi fiscali e normativi applicabili nella propria giurisdizione.

---

## 6. Hardware NFC e Risultati di Verifica

### 6.1 Limitazioni Hardware
L'App interagisce con chip NFC NTAG 424 DNA. Lo Sviluppatore non fornisce garanzie sulla longevita' o l'integrita' fisica dell'hardware NFC di terze parti.

### 6.2 Risultati di Verifica
I risultati di verifica sono elaborati sulla base di verifiche crittografiche dei dati ricevuti. Un esito positivo indica la validita' crittografica del segnale NFC al momento della scansione, ma non costituisce una perizia o garanzia legale assoluta.

---

## 7. Distribuzione Ufficiale e Avviso di Sicurezza

### 7.1 Canali di Distribuzione Autorizzati
I canali di distribuzione ufficiali per RavenTag sono:
1. **GitHub Releases** (https://github.com/ALENOC/RavenTag/releases)
2. **Google Play Store** (per l'App consumer Verify)

### 7.2 Verifica della Firma Crittografica
Le release ufficiali sono firmate dallo Sviluppatore. Gli utenti possono verificare la firma dei file APK tramite `apksigner`.

### 7.3 Esonero di Responsabilita' per Build Non Ufficiali
Lo Sviluppatore declina ogni responsabilita' per danni, malware o perdite finanziarie derivanti dall'installazione di build dell'App scaricate da fonti non autorizzate o modificate da terzi.

---

## 8. Dipendenza dalla Rete e Distinzione dell'Infrastruttura

L'App dipende dal corretto funzionamento della rete Ravencoin. Lo Sviluppatore non e' responsabile per interruzioni di rete, forchetta (fork) della blockchain o indisponibilita' di nodi di terze parti. Per quanto riguarda l'infrastruttura gestita dallo Sviluppatore (`electrumx.raventag.com`), lo Sviluppatore adotta ragionevoli misure per garantirne la disponibilita' senza rilascio di garanzie di uptime ininterrotto.

---

## 9. Limitazione di Responsabilita'

Nella misura massima consentita dalla legge applicabile:
- L'App e' fornita "COSI' COM'E'" e "COME DISPONIBILE" senza garanzie di alcun tipo.
- Lo Sviluppatore non sara' responsabile per danni diretti, indiretti, speciali o consequenziali (inclusa la perdita di fondi crypto, profitti o dati) derivanti dall'uso o dall'impossibilita' di utilizzare l'App.
- Poiche' l'App e' distribuita gratuitamente, la responsabilita' complessiva dello Sviluppatore e' limitata a zero euro (EUR 0).

---

## 10. Modifiche all'App e ai Termini

Lo Sviluppatore si riserva il diritto di aggiornare o modificare l'App e i presenti Termini in qualsiasi momento. L'uso continuato dell'App costituisce accettazione delle modifiche.

---

## 11. Legge Applicabile e Giurisdizione

Questi Termini sono disciplinati dalla legge italiana. Qualsiasi controversia sara' devoluta alla competenza esclusiva dei tribunali italiani, fatti salvi i diritti inderogabili previsti a tutela dei consumatori.

---

## 12. Nullita' Parziale

Qualora una clausola sia ritenuta non valida o inapplicabile, le restanti clausole rimarranno pienamente valide ed efficaci.

---

## 13. Accordo Completo

I presenti Termini e l'Informativa sulla Privacy costituiscono l'intero accordo tra l'Utente e lo Sviluppatore.

---

## 14. Contatti

**Alessandro Nocentini**
GitHub: https://github.com/ALENOC/RavenTag
Email: legal@raventag.com
