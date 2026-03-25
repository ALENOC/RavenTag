# 📱 RavenTag - Come Compilare le App per GitHub Releases

## Prerequisiti

1. Keystore configurato in `android/signing/signing.properties`
2. Android SDK installato
3. Gradle 8+ disponibile

---

## Procedura di Build

### **Opzione 1: Script Automatico (Raccomandato)**

```bash
cd /home/ale/Projects/RavenTag/android
chmod +x build-release.sh
./build-release.sh
```

I file saranno creati in `/tmp/raventag-release/` con i nomi corretti.

---

### **Opzione 2: Build Manuale**

#### **Step 1: Pulisci build precedenti**
```bash
cd /home/ale/Projects/RavenTag/android
./gradlew clean
```

#### **Step 2: Build Consumer Release (Verify)**
```bash
./gradlew assembleConsumerRelease
```

APK generato: `app/build/outputs/apk/consumer/release/app-consumer-release.apk`

#### **Step 3: Build Brand Release (Brand Manager)**
```bash
./gradlew assembleBrandRelease
```

APK generato: `app/build/outputs/apk/brand/release/app-brand-release.apk`

#### **Step 4: Build AAB (per Google Play Store)**
```bash
# Consumer AAB
./gradlew bundleConsumerRelease

# Brand AAB
./gradlew bundleBrandRelease
```

AAB generati:
- `app/build/outputs/bundle/consumerRelease/app-consumer-release.aab`
- `app/build/outputs/bundle/brandRelease/app-brand-release.aab`

---

## Rinominare i File

I file devono avere questi nomi esatti per GitHub Releases:

```bash
cd /home/ale/Projects/RavenTag/android

# Crea directory temporanea
mkdir -p /tmp/raventag-release

# Copia e rinomina APK
cp app/build/outputs/apk/consumer/release/app-consumer-release.apk \
   /tmp/raventag-release/RavenTag-Verify-v1.0.0.apk

cp app/build/outputs/apk/brand/release/app-brand-release.apk \
   /tmp/raventag-release/RavenTag-Brand-v1.0.0.apk

# Copia e rinomina AAB
cp app/build/outputs/bundle/consumerRelease/app-consumer-release.aab \
   /tmp/raventag-release/RavenTag-Verify-v1.0.0.aab

cp app/build/outputs/bundle/brandRelease/app-brand-release.aab \
   /tmp/raventag-release/RavenTag-Brand-v1.0.0.aab
```

---

## Verifica i File

```bash
ls -lh /tmp/raventag-release/
```

Output atteso:
```
-rw-r--r-- 1 user user 7.0M Mar 25 12:00 RavenTag-Verify-v1.0.0.apk
-rw-r--r-- 1 user user  10M Mar 25 12:00 RavenTag-Verify-v1.0.0.aab
-rw-r--r-- 1 user user 7.0M Mar 25 12:00 RavenTag-Brand-v1.0.0.apk
-rw-r--r-- 1 user user  10M Mar 25 12:00 RavenTag-Brand-v1.0.0.aab
```

---

## Verifica il Fingerprint

Per confermare che le app sono firmate con il keystore corretto:

```bash
# Verifica fingerprint APK Verify
keytool -list -v -keystore /home/ale/Projects/RavenTag/android/signing/raventag-release.keystore \
  -jar /tmp/raventag-release/RavenTag-Verify-v1.0.0.apk | grep SHA256

# Verifica fingerprint APK Brand
keytool -list -v -keystore /home/ale/Projects/RavenTag/android/signing/raventag-release.keystore \
  -jar /tmp/raventag-release/RavenTag-Brand-v1.0.0.apk | grep SHA256
```

Entrambi dovrebbero mostrare:
```
SHA256: 3E:A5:B9:F3:75:63:1A:4E:1D:E9:5D:E1:DA:9C:22:45:14:1E:4A:D8:FA:7A:63:78:7D:6A:B9:81:96:B4:A3:BE
```

---

## Upload su GitHub Releases

### **Opzione 1: GitHub CLI (Raccomandato)**

```bash
cd /tmp/raventag-release

# Crea nuovo release (se non esiste)
gh release create v1.0.0 --title "Version 1.0.0" --notes "Initial release"

# Upload APK e AAB
gh release upload v1.0.0 *.apk *.aab
```

### **Opzione 2: GitHub Web Interface**

1. Vai su: https://github.com/ALENOC/RavenTag/releases
2. Clicca "Create a new release"
3. Tag version: `v1.0.0`
4. Release title: `Version 1.0.0`
5. Descrizione: Scrivi le note di release
6. Clicca "Attach binaries by dropping files here or selecting them"
7. Seleziona tutti e 4 i file:
   - `RavenTag-Verify-v1.0.0.apk`
   - `RavenTag-Verify-v1.0.0.aab`
   - `RavenTag-Brand-v1.0.0.apk`
   - `RavenTag-Brand-v1.0.0.aab`
8. Clicca "Publish release"

---

## Tempi di Build Stimati

| Operazione | Tempo Stimato |
|------------|---------------|
| Clean | 10 secondi |
| Consumer Release APK | 3-5 minuti |
| Brand Release APK | 3-5 minuti |
| Consumer Release AAB | 3-5 minuti |
| Brand Release AAB | 3-5 minuti |
| **Totale** | **15-20 minuti** |

---

## Risoluzione Problemi

### **Errore: Keystore non trovato**
```
Ensure che `android/signing/signing.properties` esista e contenga:
KEYSTORE_FILE=android/signing/raventag-release.keystore
KEYSTORE_PASSWORD=<tua_password>
KEY_ALIAS=raventag
KEY_PASSWORD=<tua_password>
```

### **Errore: Memoria insufficiente**
```bash
# Aumenta memoria Gradle
export ORG_GRADLE_PROJECT_org.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=512m"
```

### **Build lenta**
```bash
# Abilita Gradle Daemon e cache
./gradlew --daemon assembleConsumerRelease
```

---

## Verifica Finale

Dopo l'upload su GitHub, verifica che:

- ✅ Tutti e 4 i file sono presenti nel release
- ✅ I nomi sono esatti (case-sensitive)
- ✅ Il fingerprint SHA-256 corrisponde
- ✅ Le app si installano correttamente

```bash
# Installa e testa
adb install /tmp/raventag-release/RavenTag-Verify-v1.0.0.apk
adb install /tmp/raventag-release/RavenTag-Brand-v1.0.0.apk
```

---

**Nota:** La build corrente è in esecuzione in background. Controlla il log:
```bash
tail -f /tmp/build.log
```

Quando vedi "BUILD SUCCESSFUL", i file saranno pronti in:
```
android/app/build/outputs/apk/consumer/release/
android/app/build/outputs/apk/brand/release/
android/app/build/outputs/bundle/consumerRelease/
android/app/build/outputs/bundle/brandRelease/
```
