# RavenTag Verify - Termini di Servizio

**Versione 1.2 - Data di entrata in vigore: 18 agosto 2026**  
**Copyright 2026-present Alessandro Nocentini. Tutti i diritti riservati.**

---

> **VERSIONE UFFICIALE.** Questo documento in lingua italiana costituisce la versione legalmente vincolante dei Termini di Servizio. In caso di discrepanza, contraddizione o ambiguità tra questa versione e qualsiasi traduzione, prevale questa versione italiana, fatti salvi i diritti inderogabili eventualmente spettanti all'Utente.

---

## 1. Accettazione dei Termini

Al primo avvio, l'App presenta i presenti Termini di Servizio e l'Informativa sulla Privacy. L'uso dell'App è subordinato all'accettazione richiesta dall'interfaccia. Se non accetti i Termini, non devi utilizzare l'App.

L'eventuale approvazione specifica di clausole ai sensi degli artt. 1341 e 1342 c.c., ove richiesta dalla legge applicabile e dal concreto rapporto contrattuale, deve riferirsi alle clausole effettivamente indicate dall'interfaccia e non costituisce rinuncia a diritti inderogabili del consumatore.

I presenti Termini costituiscono l'accordo tra l'Utente e Alessandro Nocentini ("Sviluppatore") relativo all'uso di RavenTag Verify, nei limiti consentiti dalla legge applicabile.

---

## 2. Descrizione dell'App e Natura della Licenza

RavenTag Verify è un'applicazione mobile che fornisce, a seconda della versione e configurazione:

- verifica crittografica di tag NFC collegati ad asset Ravencoin;
- wallet Ravencoin non custodiale BIP39/BIP44;
- funzioni di gestione locale di asset nella versione Brand.

L'App è uno strumento software per interagire in auto-custodia (self-custody) con la blockchain Ravencoin e con hardware NFC. Non è un exchange, una banca, un intermediario custodiale, un servizio di investimento o un prodotto finanziario.

L'App e il relativo codice sorgente sono distribuiti secondo la **RavenTag Source License (RTSL-1.0)**, licenza source-available che può limitare determinati usi commerciali. RavenTag non viene descritto come software open-source secondo le definizioni OSI.

---

## 3. Requisiti e Ambito di Utilizzo

L'App è destinata a utenti di almeno 18 anni. L'Utente è responsabile di verificare che l'uso dell'App sia consentito nella propria giurisdizione.

### 3.1 Uso consumer
La funzione di verifica NFC può essere utilizzata da consumatori per verificare tag e informazioni associate a prodotti fisici.

### 3.2 Wallet e auto-custodia
La funzionalità wallet comporta auto-custodia, gestione diretta delle credenziali crittografiche e interazione autonoma con una blockchain pubblica. L'Utente mantiene il controllo delle proprie chiavi e assume, nei limiti consentiti dalla legge, i rischi ordinari dell'auto-custodia descritti nei presenti Termini.

### 3.3 Licenza RTSL-1.0
Le limitazioni relative all'uso del codice sorgente sono disciplinate dalla RTSL-1.0 e non trasformano lo Sviluppatore in custode o gestore degli asset degli utenti finali.

---

## 4. Wallet Non Custodiale e Transazioni

### 4.1 Nessuna custodia da parte dello Sviluppatore
RavenTag Verify è progettato come wallet non custodiale. Durante il normale funzionamento:
- lo Sviluppatore non riceve né controlla le chiavi private o la seed phrase dell'Utente;
- lo Sviluppatore non mantiene saldi custodiali per conto dell'Utente;
- lo Sviluppatore non può autonomamente firmare una transazione al posto dell'Utente, congelare il wallet o recuperare una seed phrase perduta.

### 4.2 Creazione, firma e inoltro delle transazioni
Per una normale transazione wallet:
1. l'Utente avvia l'operazione;
2. l'App costruisce la transazione sul dispositivo;
3. la transazione viene firmata mediante chiavi controllate dall'Utente;
4. l'App può inviare la transazione già firmata a un server ElectrumX;
5. ElectrumX può inoltrare la transazione alla rete Ravencoin.

L'infrastruttura ElectrumX è progettata per funzioni tecniche di interrogazione della blockchain e di inoltro alla rete di transazioni già firmate. ElectrumX non detiene le chiavi private dell'Utente, non determina autonomamente destinatario o importo e non mantiene conti custodiali per l'Utente.

### 4.3 Seed phrase, backup e recupero
Nella misura massima consentita dalla legge, l'Utente è responsabile della conservazione sicura della propria seed phrase, delle chiavi, dei backup e delle credenziali del dispositivo. La perdita o compromissione delle credenziali può comportare perdita permanente dell'accesso agli asset. Lo Sviluppatore non dispone normalmente delle informazioni necessarie per recuperare una seed phrase o ricostruire chiavi private perse.

### 4.4 Sicurezza del dispositivo
L'Utente è responsabile della ragionevole sicurezza del proprio dispositivo. Nei limiti consentiti dalla legge, lo Sviluppatore non risponde di perdite derivanti da malware, phishing, social engineering, clipboard malware, dispositivi rooted/jailbroken, sistemi operativi compromessi, credenziali divulgate dall'Utente o build non ufficiali.

---

## 5. Rischi Blockchain, Finanziari e Tecnici

### 5.1 Rete Ravencoin
Ravencoin è una rete decentralizzata gestita da miner e nodi indipendenti. Lo Sviluppatore non controlla il consenso della rete, la produzione dei blocchi, i miner, i nodi terzi, i fork, le riorganizzazioni o le future modifiche del protocollo.

Lo Sviluppatore può gestire infrastruttura propria, incluso un endpoint ElectrumX pubblico. Tale infrastruttura non è per questo un servizio di terzi, ma non implica garanzie di disponibilità continua né custodia dei fondi.

### 5.2 Rischi dell'Utente e delle transazioni
Prima di autorizzare una transazione, l'Utente deve verificare destinatario, importo, asset e altri parametri rilevanti.

Nella misura massima consentita dalla legge, restano a carico dell'Utente le conseguenze di operazioni da lui autorizzate o di rischi sotto il suo controllo, inclusi indirizzo errato, importo errato, phishing, truffe, QR code malevoli, sostituzione degli appunti, compromissione della seed o del dispositivo.

Le transazioni blockchain possono divenire praticamente irreversibili dopo l'accettazione o conferma da parte della rete. Lo Sviluppatore non può annullare unilateralmente una transazione registrata sulla rete Ravencoin.

### 5.3 Rischi di rete e protocollo
L'Utente riconosce che blockchain e infrastrutture distribuite possono essere interessate da congestione, ritardi, rifiuto di transazioni, fork, riorganizzazioni, bug, vulnerabilità crittografiche, attacchi, indisponibilità di nodi, problemi DNS o Internet e modifiche del protocollo.

### 5.4 Rischi economici
RVN e gli asset Ravencoin possono perdere valore anche integralmente. Lo Sviluppatore non garantisce prezzo, liquidità, convertibilità, supporto da parte di exchange, continuità di un asset, rendimento o profitto.

### 5.5 Nessuna consulenza finanziaria, di investimento, fiscale o legale
Saldi, dati blockchain, metadati e altre informazioni mostrate dall'App hanno funzione tecnica/informativa e non costituiscono consulenza finanziaria, investimento, fiscale o legale, né una raccomandazione di acquistare, vendere, detenere o trasferire crypto-asset.

### 5.6 Descrizione tecnica e MiCA
RavenTag è progettato come software non custodiale source-available. Lo Sviluppatore non detiene le chiavi private degli utenti e non mantiene conti custodiali di crypto-asset.

La descrizione delle funzioni ElectrumX e Ravencoin Core nei presenti Termini è una descrizione tecnica dell'architettura e **non costituisce una dichiarazione generale di esenzione, autorizzazione o classificazione regolamentare ai sensi del Regolamento (UE) 2023/1114 (MiCA)**.

L'Utente resta responsabile dei propri obblighi fiscali e normativi individuali, ove applicabili.

---

## 6. NFC, Asset di Terzi e Contenuti Esterni

I risultati di verifica NFC derivano da controlli tecnici e crittografici e non costituiscono, da soli, una garanzia legale assoluta sull'autenticità, titolarità, qualità o valore economico di un prodotto o asset.

Contenuti IPFS, metadata, asset Ravencoin e risorse esterne possono essere creati o gestiti da soggetti indipendenti e non sono necessariamente verificati, approvati o controllati dallo Sviluppatore.

---

## 7. Distribuzione Ufficiale e Build Modificate

Lo Sviluppatore non assume, nella misura consentita dalla legge, responsabilità per versioni dell'App modificate, ricompilate, repackaged, forkate o distribuite da soggetti terzi fuori dai canali ufficiali identificati dal progetto.

Questa esclusione non modifica eventuali responsabilità inderogabili relative alle build ufficiali effettivamente distribuite dallo Sviluppatore.

---

## 8. Disponibilità, Aggiornamenti e Discontinuità

L'App e l'infrastruttura dipendono da sistemi software, rete Internet, Ravencoin e servizi terzi. Sono fornite, nei limiti consentiti dalla legge, **“così come sono” (AS IS)** e **“come disponibili” (AS AVAILABLE)**, senza garanzia volontaria di funzionamento ininterrotto, assenza assoluta di errori o vulnerabilità, compatibilità perpetua o uptime continuo.

Lo Sviluppatore può, per giustificati motivi tecnici, di sicurezza, legali, di abuso, manutenzione o evoluzione del progetto, modificare, sospendere, migrare o interrompere funzionalità o infrastrutture, fatti salvi gli obblighi inderogabili applicabili.

Nessuna disposizione crea un obbligo volontario di mantenere l'App o l'infrastruttura per un periodo indefinito.

---

## 9. Limitazione di Responsabilità e Allocazione dei Rischi

### 9.1 Principio generale
Nella misura massima consentita dalla legge applicabile, lo Sviluppatore non assume responsabilità per rischi attribuibili all'Utente, a terzi indipendenti, alla rete Ravencoin o a circostanze fuori dal controllo giuridicamente imputabile allo Sviluppatore.

### 9.2 Perdite di crypto-asset e accesso agli asset
Nella misura massima consentita dalla legge, lo Sviluppatore non è responsabile della perdita di crypto-asset o della perdita di accesso agli stessi quando derivino da rischi attribuiti all'Utente o da eventi fuori dalla responsabilità giuridicamente imputabile allo Sviluppatore, inclusi perdita o compromissione di seed/chiavi, transazioni autorizzate dall'Utente, destinatari o importi errati, malware, phishing, truffe, infrastrutture di terzi, eventi della rete o contenuti esterni.

La presente clausola **non qualifica automaticamente ogni perdita di crypto-asset come danno indiretto**.

### 9.3 Danni indiretti e consequenziali
Nei limiti consentiti dalla legge applicabile, sono esclusi danni indiretti, consequenziali, incidentali o speciali, nonché perdita di profitto, ricavi, opportunità, avviamento, dati o interruzione dell'attività, quando tali esclusioni siano valide nel concreto rapporto.

### 9.4 Nessun limite monetario artificiale
I presenti Termini **non stabiliscono un limite artificiale di EUR 0** e non presumono l'esistenza di un inesistente “minimo legale” numerico. Le responsabilità e i diritti sono esclusi o limitati soltanto nella misura in cui ciò sia consentito dalla legge applicabile.

### 9.5 Norme imperative
Nulla nei presenti Termini esclude o limita responsabilità per dolo o colpa grave nei casi in cui l'art. 1229 c.c. o altra norma imperativa ne vieti l'esclusione, né diritti del consumatore o altre responsabilità che la legge applicabile renda inderogabili.

Le clausole di limitazione sono destinate a operare **solo nella massima misura legalmente consentita**, affinché l'eventuale inefficacia di una specifica esclusione non estenda volontariamente la responsabilità dello Sviluppatore oltre quanto previsto dalla legge.

---

## 10. Nessun Rapporto Fiduciario e Nessun Obbligo Generale di Monitoraggio

L'utilizzo dell'App non crea di per sé un rapporto fiduciario, di agenzia, mandato, brokeraggio, partnership o consulenza finanziaria. Lo Sviluppatore non assume un obbligo generale volontario di monitorare tutte le attività blockchain dell'Utente, individuare truffe, classificare indirizzi o verificare ogni asset di terzi, fatti salvi obblighi inderogabili eventualmente applicabili.

---

## 11. Modifiche all'App e ai Termini

Lo Sviluppatore può modificare l'App o i presenti Termini per giustificati motivi, inclusi modifiche normative, sicurezza, prevenzione degli abusi, evoluzione tecnica, nuove funzionalità, modifica dell'architettura o cessazione di servizi.

Le modifiche sostanziali saranno rese disponibili con modalità ragionevoli e non hanno l'effetto di eliminare retroattivamente diritti inderogabili già maturati. Ove la legge richieda una nuova accettazione, l'App potrà richiederla prima della prosecuzione dell'uso.

---

## 12. Legge Applicabile e Foro

Nei limiti consentiti dalle norme di diritto internazionale privato, i presenti Termini sono disciplinati dalla legge italiana.

Per gli utenti consumatori restano impregiudicate le tutele inderogabili eventualmente spettanti in base alla legge applicabile e le norme imperative sulla competenza giurisdizionale, incluse, ove pertinenti, quelle derivanti dal Regolamento (CE) n. 593/2008 (Roma I) e dal Regolamento (UE) n. 1215/2012 (Bruxelles I bis).

Per gli utenti non consumatori, eventuali clausole di foro operano soltanto nella misura in cui siano valide ed efficaci secondo la legge applicabile e correttamente approvate quando necessario.

---

## 13. Clausole ex artt. 1341 e 1342 c.c.

Qualora, in base alla legge applicabile e alle circostanze del rapporto, talune clausole dei presenti Termini richiedano specifica approvazione ai sensi degli artt. 1341 e 1342 c.c., l'eventuale meccanismo di approvazione separata deve identificare le clausole effettivamente rilevanti.

Una generica dichiarazione di “accettazione dell'art. 1341/1342” **non è il contenuto dei presenti Termini e non sostituisce la specifica individuazione delle clausole quando richiesta**.

L'eventuale approvazione specifica non rende valida una clausola che sia comunque nulla o inefficace per effetto di norme imperative, in particolare in materia consumer.

---

## 14. Nullità Parziale e Non Rinuncia

Se una disposizione è invalida o inefficace, le restanti disposizioni continuano ad applicarsi nella misura consentita dalla legge. L'eventuale mancato esercizio di un diritto non comporta rinuncia generale a esercitarlo in futuro.

---

## 15. Accordo Completo e Rapporto con la Licenza

I presenti Termini e l'Informativa sulla Privacy disciplinano l'uso dell'App, fatti salvi la RTSL-1.0 per l'uso del codice sorgente, eventuali termini inderogabili dell'app store e la legge applicabile.

---

## 16. Contatti

**Alessandro Nocentini**  
GitHub: https://github.com/ALENOC/RavenTag  
Email: legal@raventag.com
