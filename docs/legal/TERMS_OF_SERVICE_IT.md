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

## 2. Descrizione dell'App e Natura della Licenza

RavenTag Verify e' un'applicazione mobile che fornisce:

- **Verifica tag NFC**: lettura e verifica crittografica di chip NFC NTAG 424 DNA collegati ad asset blockchain Ravencoin, utilizzando il RavenTag Protocol v1 (RTP-1).
- **Wallet Ravencoin Non Custodiale**: generazione, archiviazione locale e gestione autonoma di un wallet HD BIP39/BIP44 non custodiale per la blockchain Ravencoin (RVN).
- **Gestione asset** (solo versione Brand): emissione, trasferimento e gestione locale di asset Ravencoin collegati a prodotti fisici.

L'App e' uno strumento software per interagire in modalita' di auto-custodia (self-custody) con la blockchain Ravencoin e l'hardware NFC. Non e' un servizio finanziario, un exchange, una banca, un intermediario di custodia, ne' un prodotto finanziario o di investimento.

L'App e il codice sorgente correlato sono distribuiti sotto la **RavenTag Source License (RTSL-1.0)**, una licenza con codice sorgente disponibile (source-available software) che limita determinati usi commerciali ed enti terzi. RavenTag non costituisce software open-source secondo le definizioni OSI.

---

## 3. Requisiti e Ambito di Utilizzo

Devi avere almeno 18 anni per utilizzare questa App. Utilizzando l'App, dichiari e garantisci di avere almeno 18 anni e di avere la capacita' legale per stipulare questi Termini nella tua giurisdizione.

### 3.1 Uso consumer dell'App Verify
La funzionalita' di verifica tag NFC dell'App RavenTag Verify e' progettata per qualsiasi consumatore che desideri verificare l'autenticita' di un prodotto fisico dotato di chip NFC. L'uso di questa funzionalita' non richiede alcuna capacita' professionale.

### 3.2 Funzionalita' wallet e auto-custodia (Self-Custody)
La funzionalita' wallet Ravencoin comporta l'auto-custodia (self-custody), la gestione diretta e il trasferimento di asset digitali su una blockchain pubblica decentralizzata. Utilizzando queste funzionalita' riconosci di agire in piena autonomia, sotto la tua esclusiva responsabilita' e a tuo rischio finanziario, avendo piena consapevolezza dei rischi descritti nella Sezione 5.

### 3.3 Codice sorgente e licenza RTSL-1.0
La restrizione all'uso commerciale contenuta nella licenza source-available RavenTag Source License (RTSL-1.0) si applica esclusivamente agli sviluppatori, ai brand e alle entita' che distribuiscono, biforcano o utilizzano in altro modo il codice sorgente di RavenTag. Tale restrizione non si applica agli utenti finali dell'App che la utilizzano esclusivamente per scansionare tag NFC o gestire il proprio wallet in auto-custodia.

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

### 4.3 Frase Mnemonica (Seed Phrase) e Responsabilita' dell'Utente
Quando crei un wallet, l'App genera una frase mnemonica BIP39 di 12 parole ("seed phrase"). Nella misura massima consentita dalla legge applicabile, l'Utente e' l'unico responsabile di:
- Annotare immediatamente la propria seed phrase e conservarla in un luogo sicuro e offline;
- Mantenere la riservatezza e la sicurezza dei backup della seed phrase e delle chiavi private;
- Impedire accessi non autorizzati o attacchi di phishing/social engineering;
- Verificare le informazioni di recupero.

**La perdita della tua seed phrase comporta la perdita permanente e irrecuperabile di tutti i fondi e gli asset associati al tuo wallet. Lo Sviluppatore non puo' ripristinare l'accesso al tuo wallet in nessuna circostanza.**

### 4.4 Sicurezza del Dispositivo dell'Utente
Sei responsabile del mantenimento della sicurezza del tuo dispositivo. Nella misura massima consentita dalla legge, lo Sviluppatore non e' responsabile per eventuali perdite di fondi derivanti da malware, smarrimento o furto del dispositivo, dispositivi rooted/jailbroken, accessi non autorizzati o compromissioni del sistema operativo.

---

## 5. Rischi Blockchain, Finanziari e Inquadramento Tecnico

### 5.1 Natura di Ravencoin e Infrastruttura di Rete
Ravencoin (RVN) e' una rete blockchain decentralizzata gestita da miner e nodi indipendenti.
- **Infrastruttura gestita dallo Sviluppatore**: Lo Sviluppatore opera l'endpoint ElectrumX pubblico `electrumx.raventag.com` / `electrum.raventag.com` a supporto dell'App. Tale infrastruttura non costituisce un servizio di terze parti per le istanze connesse ad essa.
- **Infrastruttura Indipendente di Terze Parti**: L'App puo' interagire anche con nodi ElectrumX o Ravencoin Core gestiti da terzi indipendenti. Lo Sviluppatore non controlla i server di terze parti.
- **Ruolo dei Nodi Ravencoin Core**: Un nodo pubblico Ravencoin Core svolge funzioni di sincronizzazione, validazione di blocchi e transazioni, e propagazione P2P. Il nodo Core non detiene fondi dei clienti, non gestisce account utente, non possiede chiavi private e non esercita custodia sui fondi RVN.

### 5.2 Assunzione dei Rischi Finanziari, Volatilità e Irreversibilita'
Utilizzando le funzionalita' wallet di questa App, nella misura massima consentita dalla legge applicabile, riconosci ed accetti che:
- RVN e gli asset su blockchain sono soggetti a forte volatilita' di prezzo e il loro valore puo' azzerarsi;
- Le transazioni blockchain registrate sulla rete Ravencoin sono **irreversibili**. Una volta confermata, una transazione non puo' essere annullata, modificata o rimborsata dallo Sviluppatore;
- Le commissioni di rete (miner fees) sono pagate direttamente alla rete e non sono rimborsabili;
- L'Utente si assume tutti i rischi inerenti a riorganizzazioni della catena (chain reorganizations), fork, congestione di rete, guasti dei nodi o errori di inserimento di indirizzi e importi da parte dell'Utente.

### 5.3 Esclusione di Consulenza Finanziaria, di Investimento, Legale o Fiscale
Nulla in questa App o nelle comunicazioni dello Sviluppatore costituisce consulenza finanziaria, d'investimento, legale o fiscale. La visualizzazione di saldi o metadati non costituisce un'offerta o sollecitazione ad acquistare, vendere o detenere crypto-asset.

### 5.4 Inquadramento Regolatorio (MiCA) e Obblighi dell'Utente
RavenTag e' fornito come software non custodiale source-available. Lo Sviluppatore non detiene le chiavi private degli utenti e non esercita alcun controllo o custodia sui crypto-asset degli utenti. La trasmissione di transazioni firmate attraverso server ElectrumX costituisce un'attivita' meramente tecnica di inoltro dati su protocollo distribuito. L'Utente e' il solo responsabile dell'adempimento degli obblighi fiscali e normativi applicabili nella propria giurisdizione.

---

## 6. Hardware NFC, Asset di Terze Parti e Metadati IPFS

### 6.1 Hardware NFC di Terze Parti
L'App interagisce con chip NFC NTAG 424 DNA di terze parti. Lo Sviluppatore non fornisce garanzie sulla longevita' o l'integrita' fisica dell'hardware NFC.

### 6.2 Risultati di Verifica e Metadati IPFS di Terze Parti
I risultati di verifica sono elaborati sulla base di verifiche crittografiche. Un esito positivo indica la validita' crittografica al momento della scansione, ma non costituisce un certificato legale di autenticita'. I contenuti e le immagini ospitati su gateway IPFS di terze parti sono creati da soggetti indipendenti e non sono approvati o controllati dallo Sviluppatore.

---

## 7. Distribuzione Ufficiale e Avviso di Sicurezza

### 7.1 Canali Autorizzati e Verifica della Firma
I canali di distribuzione ufficiali per RavenTag sono:
1. **GitHub Releases** (https://github.com/ALENOC/RavenTag/releases)
2. **Google Play Store** (per l'App consumer Verify)

Le release ufficiali sono firmate dallo Sviluppatore e possono essere verificate tramite `apksigner`.

### 7.2 Esonero di Responsabilita' per Build Non Ufficiali
Nella misura massima consentita dalla legge, lo Sviluppatore declina ogni responsabilita' per danni, malware o perdite finanziarie derivanti dall'installazione di build dell'App scaricate da fonti non autorizzate, biforcate o modificate da terzi.

---

## 8. Disponibilità dell'Infrastruttura e Nessun Obbligo di Manutenzione Perpetua

L'App dipende dalla rete Ravencoin e da servizi di rete. Per quanto riguarda l'infrastruttura gestita dallo Sviluppatore (`electrumx.raventag.com`), lo Sviluppatore adotta ragionevoli misure tecniche senza rilasciare garanzie di uptime ininterrotto. Lo Sviluppatore si riserva il diritto di manutenere, sospendere, modificare o discontinuare le infrastrutture gestite senza che cio' generi un obbligo di manutenzione perpetua, fatti salvi i diritti inderogabili previsti dalla legge.

---

## 9. Limitazione Generale di Responsabilita' e Clausola di Salvaguardia

### 9.1 Esclusione dei Danni Indiretti e Consequenziali
Nella misura massima consentita dalla legge applicabile, lo Sviluppatore non sara' in alcun caso responsabile per danni indiretti, consequenziali, incidentali, speciali o punitivi, inclusi a titolo esemplificativo perdita di fondi crypto, perdita di profitti, perdita di opportunita', perdita di dati o interruzione dell'attivita'.

### 9.2 Limite Massimo di Responsabilita' e Differenziazione
Poiche' l'App e' fornita a titolo gratuito:
- Per gli utenti professionali / commerciali: la responsabilita' complessiva dello Sviluppatore e' limitata al massimo consentito dalla legge e comunque a zero euro (EUR 0).
- Per gli utenti consumatori: la responsabilita' dello Sviluppatore per danni diretti e' limitata al limite minimo inderogabile previsto dalla legge applicabile, tenuto conto della gratuita' dell'App.

### 9.3 Clausola di Salvaguardia delle Norme Imperative (Art. 1229 C.C.)
Niente in questi Termini esclude o limita la responsabilita' dello Sviluppatore per dolo o colpa grave ai sensi dell'Articolo 1229 del Codice Civile italiano, o qualsiasi altra responsabilita' che non possa essere legittimamente esclusa o limitata ai sensi delle norme imperative della legge applicabile a tutela dei consumatori.

---

## 10. Assenza di Rapporto Fiduciario e Nessun Obbligo di Monitoraggio

L'utilizzo dell'App non crea alcun rapporto fiduciario, di agenzia, di mandato o di intermediazione finanziaria tra l'Utente e lo Sviluppatore. Lo Sviluppatore non ha alcun obbligo di monitorare le transazioni dell'Utente o di rilevare truffe, indirizzi malevoli o asset fraudolenti emessi da terzi.

---

## 11. Modifiche all'App e ai Termini

Lo Sviluppatore si riserva il diritto di aggiornare l'App e i presenti Termini per giustificati motivi (es. adeguamenti normativi, sicurezza cibernetica, evoluzione tecnica). Le modifiche saranno rese pubbliche e l'uso continuato dell'App costituisce accettazione dei Termini aggiornati.

---

## 12. Legge Applicabile, Foro Competente e Clausola Consumatori

Questi Termini sono disciplinati dalla legge italiana. Qualsiasi controversia con utenti non consumatori sara' devoluta alla competenza esclusiva del Foro competente in Italia. Per gli utenti che qualificano come consumatori nell'Unione Europea, rimangono fermi i diritti inderogabili e la competenza del foro di residenza previsti dal Regolamento (CE) 593/2008 (Roma I) e dal Regolamento (UE) 1215/2012 (Bruxelles I bis).

---

## 13. Clausola di Salvaguardia (Nullità Parziale) e Non Rinuncia

Qualora una clausola dei presenti Termini sia ritenuta invalida o inapplicabile, tale clausola sara' interpretata o limitata nella misura minima necessaria e le restanti clausole rimarranno pienamente valide ed efficaci. Il mancato esercizio da parte dello Sviluppatore di un diritto non costituisce rinuncia a far valere tale diritto in futuro.

---

## 14. Accordo Completo e Gerarchia dei Documenti

I presenti Termini e l'Informativa sulla Privacy costituiscono l'intero accordo tra l'Utente e lo Sviluppatore riguardo all'uso dell'App. In caso di discrepanza con la licenza RTSL-1.0 per quanto riguarda l'uso del codice sorgente, prevalgono i termini della licenza RTSL-1.0.

---

## 15. Contatti

**Alessandro Nocentini**
GitHub: https://github.com/ALENOC/RavenTag
Email: legal@raventag.com
