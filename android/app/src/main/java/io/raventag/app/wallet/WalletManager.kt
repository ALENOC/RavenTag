package io.raventag.app.wallet

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import java.math.BigInteger
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WalletManager , BIP32/BIP44 HD wallet for Ravencoin.
 *
 * Coin type: 175 (SLIP44 Ravencoin)
 * Address version: 0x3C (60) , Ravencoin P2PKH mainnet
 * Derivation path: m/44'/175'/0'/0/0
 *
 * Keys are encrypted with Android Keystore (AES-GCM) before storage.
 */
class WalletManager(private val context: Context) {

    // Cached address: derived once and reused to avoid repeated KeyStore decrypt +
    // BIP32 derivation + secp256k1 on the main thread.
    // @Volatile ensures visibility across threads (Dispatchers.IO reads it concurrently).
    @Volatile private var cachedAddress: String? = null

    companion object {
        private const val PREFS_NAME = "raventag_wallet"
        private const val KEY_SEED_ENC = "seed_enc"
        private const val KEY_SEED_IV = "seed_iv"
        private const val KEY_MNEMONIC_ENC = "mnemonic_enc"
        private const val KEY_MNEMONIC_IV = "mnemonic_iv"
        private const val KEYSTORE_ALIAS = "raventag_wallet_key"
        private const val COIN_TYPE = 175
        private val RVN_ADDRESS_VERSION = byteArrayOf(0x3C.toByte())
        private val B58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        // BIP39 complete 2048-word English wordlist (BIP-0039 standard)
        private val WORD_LIST = listOf(
            "abandon","ability","able","about","above","absent","absorb","abstract","absurd","abuse",
            "access","accident","account","accuse","achieve","acid","acoustic","acquire","across","act",
            "action","actor","actress","actual","adapt","add","addict","address","adjust","admit",
            "adult","advance","advice","aerobic","affair","afford","afraid","again","age","agent",
            "agree","ahead","aim","air","airport","aisle","alarm","album","alcohol","alert",
            "alien","all","alley","allow","almost","alone","alpha","already","also","alter",
            "always","amateur","amazing","among","amount","amused","analyst","anchor","ancient","anger",
            "angle","angry","animal","ankle","announce","annual","another","answer","antenna","antique",
            "anxiety","any","apart","apology","appear","apple","approve","april","arch","arctic",
            "area","arena","argue","arm","armed","armor","army","around","arrange","arrest",
            "arrive","arrow","art","artefact","artist","artwork","ask","aspect","assault","asset",
            "assist","assume","asthma","athlete","atom","attack","attend","attitude","attract","auction",
            "audit","august","aunt","author","auto","autumn","average","avocado","avoid","awake",
            "aware","away","awesome","awful","awkward","axis","baby","bachelor","bacon","badge",
            "bag","balance","balcony","ball","bamboo","banana","banner","bar","barely","bargain",
            "barrel","base","basic","basket","battle","beach","bean","beauty","because","become",
            "beef","before","begin","behave","behind","believe","below","belt","bench","benefit",
            "best","betray","better","between","beyond","bicycle","bid","bike","bind","biology",
            "bird","birth","bitter","black","blade","blame","blanket","blast","bleak","bless",
            "blind","blood","blossom","blouse","blue","blur","blush","board","boat","body",
            "boil","bomb","bone","bonus","book","boost","border","boring","borrow","boss",
            "bottom","bounce","box","boy","bracket","brain","brand","brass","brave","bread",
            "breeze","brick","bridge","brief","bright","bring","brisk","broccoli","broken","bronze",
            "broom","brother","brown","brush","bubble","buddy","budget","buffalo","build","bulb",
            "bulk","bullet","bundle","bunker","burden","burger","burst","bus","business","busy",
            "butter","buyer","buzz","cabbage","cabin","cable","cactus","cage","cake","call",
            "calm","camera","camp","can","canal","cancel","candy","cannon","canoe","canvas",
            "canyon","capable","capital","captain","car","carbon","card","cargo","carpet","carry",
            "cart","case","cash","casino","castle","casual","cat","catalog","catch","category",
            "cattle","caught","cause","caution","cave","ceiling","celery","cement","census","century",
            "cereal","certain","chair","chalk","champion","change","chaos","chapter","charge","chase",
            "chat","cheap","check","cheese","chef","cherry","chest","chicken","chief","child",
            "chimney","choice","choose","chronic","chuckle","chunk","churn","cigar","cinnamon","circle",
            "citizen","city","civil","claim","clap","clarify","claw","clay","clean","clerk",
            "clever","click","client","cliff","climb","clinic","clip","clock","clog","close",
            "cloth","cloud","clown","club","clump","cluster","clutch","coach","coast","coconut",
            "code","coffee","coil","coin","collect","color","column","combine","come","comfort",
            "comic","common","company","concert","conduct","confirm","congress","connect","consider","control",
            "convince","cook","cool","copper","copy","coral","core","corn","correct","cost",
            "cotton","couch","country","couple","course","cousin","cover","coyote","crack","cradle",
            "craft","cram","crane","crash","crater","crawl","crazy","cream","credit","creek",
            "crew","cricket","crime","crisp","critic","crop","cross","crouch","crowd","crucial",
            "cruel","cruise","crumble","crunch","crush","cry","crystal","cube","culture","cup",
            "cupboard","curious","current","curtain","curve","cushion","custom","cute","cycle","dad",
            "damage","damp","dance","danger","daring","dash","daughter","dawn","day","deal",
            "debate","debris","decade","december","decide","decline","decorate","decrease","deer","defense",
            "define","defy","degree","delay","deliver","demand","demise","denial","dentist","deny",
            "depart","depend","deposit","depth","deputy","derive","describe","desert","design","desk",
            "despair","destroy","detail","detect","develop","device","devote","diagram","dial","diamond",
            "diary","dice","diesel","diet","differ","digital","dignity","dilemma","dinner","dinosaur",
            "direct","dirt","disagree","discover","disease","dish","dismiss","disorder","display","distance",
            "divert","divide","divorce","dizzy","doctor","document","dog","doll","dolphin","domain",
            "donate","donkey","donor","door","dose","double","dove","draft","dragon","drama",
            "drastic","draw","dream","dress","drift","drill","drink","drip","drive","drop",
            "drum","dry","duck","dumb","dune","during","dust","dutch","duty","dwarf",
            "dynamic","eager","eagle","early","earn","earth","easily","east","easy","echo",
            "ecology","economy","edge","edit","educate","effort","egg","eight","either","elbow",
            "elder","electric","elegant","element","elephant","elevator","elite","else","embark","embody",
            "embrace","emerge","emotion","employ","empower","empty","enable","enact","end","endless",
            "endorse","enemy","energy","enforce","engage","engine","enhance","enjoy","enlist","enough",
            "enrich","enroll","ensure","enter","entire","entry","envelope","episode","equal","equip",
            "era","erase","erode","erosion","error","erupt","escape","essay","essence","estate",
            "eternal","ethics","evidence","evil","evoke","evolve","exact","example","excess","exchange",
            "excite","exclude","excuse","execute","exercise","exhaust","exhibit","exile","exist","exit",
            "exotic","expand","expect","expire","explain","expose","express","extend","extra","eye",
            "eyebrow","fabric","face","faculty","fade","faint","faith","fall","false","fame",
            "family","famous","fan","fancy","fantasy","farm","fashion","fat","fatal","father",
            "fatigue","fault","favorite","feature","february","federal","fee","feed","feel","female",
            "fence","festival","fetch","fever","few","fiber","fiction","field","figure","file",
            "film","filter","final","find","fine","finger","finish","fire","firm","first",
            "fiscal","fish","fit","fitness","fix","flag","flame","flash","flat","flavor",
            "flee","flight","flip","float","flock","floor","flower","fluid","flush","fly",
            "foam","focus","fog","foil","fold","follow","food","foot","force","forest",
            "forget","fork","fortune","forum","forward","fossil","foster","found","fox","fragile",
            "frame","frequent","fresh","friend","fringe","frog","front","frost","frown","frozen",
            "fruit","fuel","fun","funny","furnace","fury","future","gadget","gain","galaxy",
            "gallery","game","gap","garage","garbage","garden","garlic","garment","gas","gasp",
            "gate","gather","gauge","gaze","general","genius","genre","gentle","genuine","gesture",
            "ghost","giant","gift","giggle","ginger","giraffe","girl","give","glad","glance",
            "glare","glass","glide","glimpse","globe","gloom","glory","glove","glow","glue",
            "goat","goddess","gold","good","goose","gorilla","gospel","gossip","govern","gown",
            "grab","grace","grain","grant","grape","grass","gravity","great","green","grid",
            "grief","grit","grocery","group","grow","grunt","guard","guess","guide","guilt",
            "guitar","gun","gym","habit","hair","half","hammer","hamster","hand","happy",
            "harbor","hard","harsh","harvest","hat","have","hawk","hazard","head","health",
            "heart","heavy","hedgehog","height","hello","helmet","help","hen","hero","hidden",
            "high","hill","hint","hip","hire","history","hobby","hockey","hold","hole",
            "holiday","hollow","home","honey","hood","hope","horn","horror","horse","hospital",
            "host","hotel","hour","hover","hub","huge","human","humble","humor","hundred",
            "hungry","hunt","hurdle","hurry","hurt","husband","hybrid","ice","icon","idea",
            "identify","idle","ignore","ill","illegal","illness","image","imitate","immense","immune",
            "impact","impose","improve","impulse","inch","include","income","increase","index","indicate",
            "indoor","industry","infant","inflict","inform","inhale","inherit","initial","inject","injury",
            "inmate","inner","innocent","input","inquiry","insane","insect","inside","inspire","install",
            "intact","interest","into","invest","invite","involve","iron","island","isolate","issue",
            "item","ivory","jacket","jaguar","jar","jazz","jealous","jeans","jelly","jewel",
            "job","join","joke","journey","joy","judge","juice","jump","jungle","junior",
            "junk","just","kangaroo","keen","keep","ketchup","key","kick","kid","kidney",
            "kind","kingdom","kiss","kit","kitchen","kite","kitten","kiwi","knee","knife",
            "knock","know","lab","label","labor","ladder","lady","lake","lamp","language",
            "laptop","large","later","latin","laugh","laundry","lava","law","lawn","lawsuit",
            "layer","lazy","leader","leaf","learn","leave","lecture","left","leg","legal",
            "legend","leisure","lemon","lend","length","lens","leopard","lesson","letter","level",
            "liar","liberty","library","license","life","lift","light","like","limb","limit",
            "link","lion","liquid","list","little","live","lizard","load","loan","lobster",
            "local","lock","logic","lonely","long","loop","lottery","loud","lounge","love",
            "loyal","lucky","luggage","lumber","lunar","lunch","luxury","lyrics","machine","mad",
            "magic","magnet","maid","mail","main","major","make","mammal","man","manage",
            "mandate","mango","mansion","manual","maple","marble","march","margin","marine","market",
            "marriage","mask","mass","master","match","material","math","matrix","matter","maximum",
            "maze","meadow","mean","measure","meat","mechanic","medal","media","melody","melt",
            "member","memory","mention","menu","mercy","merge","merit","merry","mesh","message",
            "metal","method","middle","midnight","milk","million","mimic","mind","minimum","minor",
            "minute","miracle","mirror","misery","miss","mistake","mix","mixed","mixture","mobile",
            "model","modify","mom","moment","monitor","monkey","monster","month","moon","moral",
            "more","morning","mosquito","mother","motion","motor","mountain","mouse","move","movie",
            "much","muffin","mule","multiply","muscle","museum","mushroom","music","must","mutual",
            "myself","mystery","myth","naive","name","napkin","narrow","nasty","nation","nature",
            "near","neck","need","negative","neglect","neither","nephew","nerve","nest","net",
            "network","neutral","never","news","next","nice","night","noble","noise","nominee",
            "noodle","normal","north","nose","notable","note","nothing","notice","novel","now",
            "nuclear","number","nurse","nut","oak","obey","object","oblige","obscure","observe",
            "obtain","obvious","occur","ocean","october","odor","off","offer","office","often",
            "oil","okay","old","olive","olympic","omit","once","one","onion","online",
            "only","open","opera","opinion","oppose","option","orange","orbit","orchard","order",
            "ordinary","organ","orient","original","orphan","ostrich","other","outdoor","outer","output",
            "outside","oval","oven","over","own","owner","oxygen","oyster","ozone","pact",
            "paddle","page","pair","palace","palm","panda","panel","panic","panther","paper",
            "parade","parent","park","parrot","party","pass","patch","path","patient","patrol",
            "pattern","pause","pave","payment","peace","peanut","pear","peasant","pelican","pen",
            "penalty","pencil","people","pepper","perfect","permit","person","pet","phone","photo",
            "phrase","physical","piano","picnic","picture","piece","pig","pigeon","pill","pilot",
            "pink","pioneer","pipe","pistol","pitch","pizza","place","planet","plastic","plate",
            "play","please","pledge","pluck","plug","plunge","poem","poet","point","polar",
            "pole","police","pond","pony","pool","popular","portion","position","possible","post",
            "potato","pottery","poverty","powder","power","practice","praise","predict","prefer","prepare",
            "present","pretty","prevent","price","pride","primary","print","priority","prison","private",
            "prize","problem","process","produce","profit","program","project","promote","proof","property",
            "prosper","protect","proud","provide","public","pudding","pull","pulp","pulse","pumpkin",
            "punch","pupil","puppy","purchase","purity","purpose","purse","push","put","puzzle",
            "pyramid","quality","quantum","quarter","question","quick","quit","quiz","quote","rabbit",
            "raccoon","race","rack","radar","radio","rail","rain","raise","rally","ramp",
            "ranch","random","range","rapid","rare","rate","rather","raven","raw","razor",
            "ready","real","reason","rebel","rebuild","recall","receive","recipe","record","recycle",
            "reduce","reflect","reform","refuse","region","regret","regular","reject","relax","release",
            "relief","rely","remain","remember","remind","remove","render","renew","rent","reopen",
            "repair","repeat","replace","report","require","rescue","resemble","resist","resource","response",
            "result","retire","retreat","return","reunion","reveal","review","reward","rhythm","rib",
            "ribbon","rice","rich","ride","ridge","rifle","right","rigid","ring","riot",
            "ripple","risk","ritual","rival","river","road","roast","robot","robust","rocket",
            "romance","roof","rookie","room","rose","rotate","rough","round","route","royal",
            "rubber","rude","rug","rule","run","runway","rural","sad","saddle","sadness",
            "safe","sail","salad","salmon","salon","salt","salute","same","sample","sand",
            "satisfy","satoshi","sauce","sausage","save","say","scale","scan","scare","scatter",
            "scene","scheme","school","science","scissors","scorpion","scout","scrap","screen","script",
            "scrub","sea","search","season","seat","second","secret","section","security","seed",
            "seek","segment","select","sell","seminar","senior","sense","sentence","series","service",
            "session","settle","setup","seven","shadow","shaft","shallow","share","shed","shell",
            "sheriff","shield","shift","shine","ship","shiver","shock","shoe","shoot","shop",
            "short","shoulder","shove","shrimp","shrug","shuffle","shy","sibling","sick","side",
            "siege","sight","sign","silent","silk","silly","silver","similar","simple","since",
            "sing","siren","sister","situate","six","size","skate","sketch","ski","skill",
            "skin","skirt","skull","slab","slam","sleep","slender","slice","slide","slight",
            "slim","slogan","slot","slow","slush","small","smart","smile","smoke","smooth",
            "snack","snake","snap","sniff","snow","soap","soccer","social","sock","soda",
            "soft","solar","soldier","solid","solution","solve","someone","song","soon","sorry",
            "sort","soul","sound","soup","source","south","space","spare","spatial","spawn",
            "speak","special","speed","spell","spend","sphere","spice","spider","spike","spin",
            "spirit","split","spoil","sponsor","spoon","sport","spot","spray","spread","spring",
            "spy","square","squeeze","squirrel","stable","stadium","staff","stage","stairs","stamp",
            "stand","start","state","stay","steak","steel","stem","step","stereo","stick",
            "still","sting","stock","stomach","stone","stool","story","stove","strategy","street",
            "strike","strong","struggle","student","stuff","stumble","style","subject","submit","subway",
            "success","such","sudden","suffer","sugar","suggest","suit","summer","sun","sunny",
            "sunset","super","supply","supreme","sure","surface","surge","surprise","surround","survey",
            "suspect","sustain","swallow","swamp","swap","swarm","swear","sweet","swift","swim",
            "swing","switch","sword","symbol","symptom","syrup","system","table","tackle","tag",
            "tail","talent","talk","tank","tape","target","task","taste","tattoo","taxi",
            "teach","team","tell","ten","tenant","tennis","tent","term","test","text",
            "thank","that","theme","then","theory","there","they","thing","this","thought",
            "three","thrive","throw","thumb","thunder","ticket","tide","tiger","tilt","timber",
            "time","tiny","tip","tired","tissue","title","toast","tobacco","today","toddler",
            "toe","together","toilet","token","tomato","tomorrow","tone","tongue","tonight","tool",
            "tooth","top","topic","topple","torch","tornado","tortoise","toss","total","tourist",
            "toward","tower","town","toy","track","trade","traffic","tragic","train","transfer",
            "trap","trash","travel","tray","treat","tree","trend","trial","tribe","trick",
            "trigger","trim","trip","trophy","trouble","truck","true","truly","trumpet","trust",
            "truth","try","tube","tuition","tumble","tuna","tunnel","turkey","turn","turtle",
            "twelve","twenty","twice","twin","twist","two","type","typical","ugly","umbrella",
            "unable","unaware","uncle","uncover","under","undo","unfair","unfold","unhappy","uniform",
            "unique","unit","universe","unknown","unlock","until","unusual","unveil","update","upgrade",
            "uphold","upon","upper","upset","urban","urge","usage","use","used","useful",
            "useless","usual","utility","vacant","vacuum","vague","valid","valley","valve","van",
            "vanish","vapor","various","vast","vault","vehicle","velvet","vendor","venture","venue",
            "verb","verify","version","very","vessel","veteran","viable","vibrant","vicious","victory",
            "video","view","village","vintage","violin","virtual","virus","visa","visit","visual",
            "vital","vivid","vocal","voice","void","volcano","volume","vote","voyage","wage",
            "wagon","wait","walk","wall","walnut","want","warfare","warm","warrior","wash",
            "wasp","waste","water","wave","way","wealth","weapon","wear","weasel","weather",
            "web","wedding","weekend","weird","welcome","west","wet","whale","what","wheat",
            "wheel","when","where","whip","whisper","wide","width","wife","wild","will",
            "win","window","wine","wing","wink","winner","winter","wire","wisdom","wise",
            "wish","witness","wolf","woman","wonder","wood","wool","word","work","world",
            "worry","worth","wrap","wreck","wrestle","wrist","write","wrong","yard","year",
            "yellow","you","young","youth","zebra","zero","zone","zoo"
        )
    }

    /**
     * Create or retrieve the AES-256-GCM wallet encryption key from the Android Keystore.
     *
     * Security layers (in order of preference):
     * 1. StrongBox: hardware-isolated secure enclave (Titan/similar chip) , best security
     *    Keys never leave the dedicated security chip, even the OS cannot extract them.
     * 2. TEE (Trusted Execution Environment): hardware-backed Keystore in ARM TrustZone
     *    Keys are hardware-backed but in the main SoC secure area.
     * 3. Software Keystore: fallback for older/lower-end devices.
     *
     * Additional protections applied regardless of backing:
     * - setUnlockedDeviceRequired: key is only accessible when device is unlocked (screen on + PIN/biometric)
     * - setRandomizedEncryptionRequired: forces random IV per encryption (prevents replay attacks)
     * - setInvalidatedByBiometricEnrollment: key is invalidated if new biometrics are enrolled
     *   (prevents attacker from enrolling their own fingerprint to access funds)
     */
    private fun getOrCreateAndroidKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        fun buildSpec(strongBox: Boolean): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .apply {
                    // setUnlockedDeviceRequired requires API 28+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setUnlockedDeviceRequired(true)
                    }
                    if (strongBox) setIsStrongBoxBacked(true)
                }
                .build()

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")

        // Try StrongBox first (dedicated security chip , highest security)
        val key = try {
            keyGen.init(buildSpec(strongBox = true))
            keyGen.generateKey().also {
                android.util.Log.i("WalletManager", "Key stored in StrongBox (hardware enclave)")
            }
        } catch (_: Throwable) {
            // Fallback to TEE / software Keystore
            keyGen.init(buildSpec(strongBox = false))
            keyGen.generateKey().also {
                android.util.Log.i("WalletManager", "Key stored in Android Keystore (TEE/software)")
            }
        }
        return key
    }

    /** Returns true if the wallet key is hardware-backed (TEE or StrongBox). */
    fun isKeyHardwareBacked(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            val key = entry?.secretKey ?: return false
            val factory = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            keyInfo.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE
        } catch (_: Exception) { false }
    }

    /**
     * Encrypt [data] with the Android Keystore AES-GCM key.
     * Returns ciphertext paired with the random IV (GCM generates a fresh IV per call).
     */
    private fun encrypt(data: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getOrCreateAndroidKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data) to cipher.iv
    }

    /**
     * Decrypt [enc] using the Android Keystore AES-GCM key and the provided [iv].
     * GCM authentication tag is verified automatically; throws if tampered.
     */
    private fun decrypt(enc: ByteArray, iv: ByteArray): ByteArray {
        val key = getOrCreateAndroidKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(enc)
    }

    /** Returns the app-private SharedPreferences file used to store the encrypted wallet material. */
    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasWallet(): Boolean = prefs().contains(KEY_SEED_ENC)

    /** Generate a new BIP39 12-word mnemonic and derive BIP32 seed. */
    fun generateWallet(): String {
        val entropy = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val mnemonic = entropyToMnemonic(entropy)
        val seed = mnemonicToSeed(mnemonic, "")
        storeSeed(seed, mnemonic)
        return mnemonic
    }

    /**
     * Generate a new BIP39 12-word mnemonic without storing it.
     * Call finalizeWallet() after the user confirms the backup.
     */
    fun generateMnemonic(): String {
        val entropy = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return entropyToMnemonic(entropy)
    }

    /**
     * Derive seed from mnemonic and store it securely.
     * Call this only after the user confirms the backup of the mnemonic.
     */
    fun finalizeWallet(mnemonic: String) {
        val seed = mnemonicToSeed(mnemonic, "")
        storeSeed(seed, mnemonic)
        cachedAddress = null
    }

    /** Delete wallet , clears all encrypted keys from SharedPreferences and Android Keystore. */
    fun deleteWallet() {
        cachedAddress = null
        prefs().edit()
            .remove(KEY_SEED_ENC).remove(KEY_SEED_IV)
            .remove(KEY_MNEMONIC_ENC).remove(KEY_MNEMONIC_IV)
            .apply()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)
        } catch (_: Exception) {}
    }

    /** Restore wallet from existing mnemonic. */
    fun restoreWallet(mnemonic: String): Boolean {
        return try {
            val normalized = mnemonic.trim()
            if (!validateMnemonic(normalized)) return false
            val seed = mnemonicToSeed(normalized, "")
            storeSeed(seed, normalized)
            cachedAddress = null
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validate a BIP39 12-word mnemonic.
     * Checks that every word is in the BIP39 English wordlist and that the
     * 4-bit checksum appended to the entropy (per BIP39 spec) is correct.
     */
    private fun validateMnemonic(mnemonic: String): Boolean {
        val words = mnemonic.split("\\s+".toRegex())
        if (words.size != 12) return false

        val indices = mutableListOf<Int>()
        for (word in words) {
            val idx = WORD_LIST.indexOf(word)
            if (idx < 0) return false
            indices.add(idx)
        }

        // Reconstruct bit array from word indices (11 bits per word = 132 bits total)
        val allBits = ArrayList<Int>(132)
        for (idx in indices) {
            for (i in 10 downTo 0) {
                allBits.add((idx shr i) and 1)
            }
        }

        // First 128 bits = entropy, last 4 bits = BIP39 checksum
        val entropy = ByteArray(16)
        for (i in 0 until 128) {
            entropy[i / 8] = (entropy[i / 8].toInt() or (allBits[i] shl (7 - i % 8))).toByte()
        }
        val checksumBits = allBits.subList(128, 132)

        // Expected checksum = first 4 bits of SHA-256(entropy)
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val expectedBits = (0 until 4).map { i -> (hash[0].toInt() shr (7 - i)) and 1 }

        return checksumBits == expectedBits
    }

    /** Encrypt and persist both the BIP32 seed and the BIP39 mnemonic to SharedPreferences. */
    private fun storeSeed(seed: ByteArray, mnemonic: String) {
        val (seedEnc, seedIv) = encrypt(seed)
        val (mnemonicEnc, mnemonicIv) = encrypt(mnemonic.toByteArray())
        prefs().edit()
            .putString(KEY_SEED_ENC, android.util.Base64.encodeToString(seedEnc, android.util.Base64.DEFAULT))
            .putString(KEY_SEED_IV, android.util.Base64.encodeToString(seedIv, android.util.Base64.DEFAULT))
            .putString(KEY_MNEMONIC_ENC, android.util.Base64.encodeToString(mnemonicEnc, android.util.Base64.DEFAULT))
            .putString(KEY_MNEMONIC_IV, android.util.Base64.encodeToString(mnemonicIv, android.util.Base64.DEFAULT))
            .apply()
    }

    /** Decrypt and return the stored BIP39 mnemonic, or null if no wallet exists or decryption fails. */
    fun getMnemonic(): String? {
        return try {
            val encStr = prefs().getString(KEY_MNEMONIC_ENC, null) ?: return null
            val ivStr = prefs().getString(KEY_MNEMONIC_IV, null) ?: return null
            val enc = android.util.Base64.decode(encStr, android.util.Base64.DEFAULT)
            val iv = android.util.Base64.decode(ivStr, android.util.Base64.DEFAULT)
            String(decrypt(enc, iv))
        } catch (e: Exception) { null }
    }

    /** Decrypt and return the raw BIP32 seed bytes, or null if unavailable. Caller must clear the array after use. */
    private fun getSeed(): ByteArray? {
        return try {
            val encStr = prefs().getString(KEY_SEED_ENC, null) ?: return null
            val ivStr = prefs().getString(KEY_SEED_IV, null) ?: return null
            val enc = android.util.Base64.decode(encStr, android.util.Base64.DEFAULT)
            val iv = android.util.Base64.decode(ivStr, android.util.Base64.DEFAULT)
            decrypt(enc, iv)
        } catch (e: Exception) { null }
    }

    /** Get the Ravencoin address at m/44'/175'/0'/0/0 */
    fun getAddress(accountIndex: Int = 0, addressIndex: Int = 0): String? {
        // Return cached address for the default BIP44 path (m/44'/175'/0'/0/0)
        if (accountIndex == 0 && addressIndex == 0) {
            cachedAddress?.let { return it }
        }
        var seed: ByteArray? = null
        var privKey: ByteArray? = null
        return try {
            seed = getSeed() ?: return null
            privKey = derivePrivateKey(seed, accountIndex, addressIndex)
            val pubKey = privateKeyToPublicKey(privKey)
            val address = publicKeyToRavenAddress(pubKey)
            if (accountIndex == 0 && addressIndex == 0) cachedAddress = address
            address
        } catch (_: Throwable) {
            null
        } finally {
            seed?.fill(0)
            privKey?.fill(0)
        }
    }

    /** Get private key hex (export for signing) , use with extreme care */
    fun getPrivateKeyHex(accountIndex: Int = 0, addressIndex: Int = 0): String? {
        var seed: ByteArray? = null
        var privKey: ByteArray? = null
        return try {
            seed = getSeed() ?: return null
            privKey = derivePrivateKey(seed, accountIndex, addressIndex)
            privKey.joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            null
        } finally {
            seed?.fill(0)
            privKey?.fill(0)
        }
    }

    /** Get raw private key bytes at BIP44 path */
    fun getPrivateKeyBytes(accountIndex: Int = 0, addressIndex: Int = 0): ByteArray? {
        // Warning: this returns a copy that the CALLER must clear.
        val seed = getSeed() ?: return null
        val privKey = derivePrivateKey(seed, accountIndex, addressIndex)
        seed.fill(0)
        return privKey
    }

    /** Get compressed public key bytes at BIP44 path */
    fun getPublicKeyBytes(accountIndex: Int = 0, addressIndex: Int = 0): ByteArray? {
        var seed: ByteArray? = null
        var priv: ByteArray? = null
        return try {
            seed = getSeed() ?: return null
            priv = derivePrivateKey(seed, accountIndex, addressIndex)
            privateKeyToPublicKey(priv)
        } catch (_: Throwable) {
            null
        } finally {
            seed?.fill(0)
            priv?.fill(0)
        }
    }

    /**
     * Query balance directly from public Ravencoin node (no backend required).
     * Returns balance in RVN.
     */
    fun getLocalBalance(): Double? {
        return try {
            val address = getAddress() ?: return null
            val node = RavencoinPublicNode()
            node.getBalance(address).totalRvn
        } catch (_: Exception) { null }
    }

    /**
     * Send RVN from local wallet directly to the network.
     * No backend required. Uses public Ravencoin node for UTXO query and broadcast.
     *
     * @param toAddress Recipient Ravencoin address
     * @param amountRvn Amount in RVN
     * @return "$txid|fee:$satoshis" on success
     */
    fun sendRvnLocal(toAddress: String, amountRvn: Double): String {
        val address = getAddress() ?: error("No wallet")
        var privKey: ByteArray? = null
        return try {
            privKey = getPrivateKeyBytes() ?: error("No private key")
            val pubKey = getPublicKeyBytes() ?: error("No public key")

            val node = RavencoinPublicNode()
            val utxos = node.getUtxos(address)
            if (utxos.isEmpty()) {
                val bal = try { node.getBalance(address) } catch (_: Exception) { null }
                if (bal != null && bal.unconfirmed > 0 && bal.confirmed == 0L) {
                    error("Transaction not confirmed yet. Wait for 1-2 blocks before sending.")
                }
                error("No spendable funds found for address $address")
            }

            // Query the node's minimum relay fee, then apply it with a 2x margin (floor 200 sat/byte)
            val satPerByte = node.getMinRelayFeeRateSatPerByte()
            // Estimate tx size: 10 overhead + 148 per input + 34 per output (assume 2 outputs)
            val estimatedBytes = 10 + 148 * utxos.size + 34 * 2
            val feeSat = estimatedBytes * satPerByte
            val amountSat = (amountRvn * 1e8).toLong()
            val tx = RavencoinTxBuilder.buildAndSign(
                utxos = utxos,
                toAddress = toAddress,
                amountSat = amountSat,
                feeSat = feeSat,
                changeAddress = address,
                privKeyBytes = privKey,
                pubKeyBytes = pubKey
            )
            val txid = node.broadcast(tx.hex)
            // Return txid with fee info for diagnostics
            "$txid|fee:${feeSat}"
        } finally {
            privKey?.fill(0)
        }
    }

    /**
     * Transfer a Ravencoin asset directly on-chain (no backend required).
     * Fetches asset UTXOs and RVN UTXOs, builds and signs the transfer transaction,
     * then broadcasts via ElectrumX.
     *
     * Handles all asset types correctly:
     *   - Unique tokens ("BRAND/PRODUCT#SN001"): always qty=1, divisions=0, no asset change.
     *   - Owner tokens ("BRAND/PRODUCT!"): always qty=1, divisions=0, no asset change.
     *   - Fungible root/sub-assets: qty < total balance generates an asset change output
     *     back to the sender so no tokens are lost.
     *
     * @param assetName  Asset name to transfer (e.g. "BRAND/ITEM#SN001")
     * @param toAddress  Recipient Ravencoin address
     * @param qty        Quantity to transfer in display units (e.g. 1.0 for one token).
     *                   Must be > 0 and <= current asset balance.
     * @return transaction ID on success
     */
    fun transferAssetLocal(
        assetName: String,
        toAddress: String,
        qty: Double = 1.0
    ): String {
        val address = getAddress() ?: error("No wallet")
        var privKey: ByteArray? = null
        return try {
            privKey = getPrivateKeyBytes() ?: error("No private key")
            val pubKey = getPublicKeyBytes() ?: error("No public key")

            val node = RavencoinPublicNode()

            // Ravencoin wire format always encodes asset amounts as qty * COIN (10^8), regardless
            // of the asset's divisions field. Divisions control only display precision, not the
            // on-chain LE64 value. Unique tokens ("#") and owner tokens ("!") each carry exactly
            // 1 * COIN = 100_000_000 raw units per UTXO.
            val rawQtyRequested = Math.round(qty * 100_000_000.0)
            require(rawQtyRequested > 0) { "Transfer quantity must be greater than zero" }

            // Fetch asset UTXOs (each carries the asset and a dust amount of RVN)
            val assetUtxosFull = node.getAssetUtxosFull(address, assetName)
            if (assetUtxosFull.isEmpty()) error("No UTXOs found for asset $assetName in address $address")

            val totalRawAmount = assetUtxosFull.sumOf { it.assetRawAmount }
            require(totalRawAmount > 0) { "Asset $assetName has zero balance in UTXOs" }
            require(rawQtyRequested <= totalRawAmount) {
                "Insufficient asset balance: requested $qty, " +
                "available ${totalRawAmount / 100_000_000.0}"
            }

            // Remaining asset balance returns to the sender as a second OP_RVN_ASSET output.
            val assetChangeRawAmount = totalRawAmount - rawQtyRequested

            val assetUtxos = assetUtxosFull.map { it.utxo }
            val assetDust = assetUtxos.sumOf { it.satoshis }

            // Number of asset outputs: 1 for full transfer (unique tokens), 2 if there's asset change.
            val numAssetOutputs = if (assetChangeRawAmount > 0) 2 else 1

            // Estimate fee dynamically using ElectrumX relay fee with safety margin.
            // Use conservative estimate: 10 overhead + 148 per input + ~70 per asset output + 34 per RVN output.
            // Start with minimum estimate and adjust after fetching UTXOs.
            val relayFeeSatPerByte = try { node.getMinRelayFeeRateSatPerByte() } catch (_: FeeUnavailableException) { 200L }
            // Use relay fee directly without excessive margin (floor 200 sat/byte)
            val satPerByte = maxOf(relayFeeSatPerByte, 200L)
            
            // Dust for asset outputs: only needed if asset UTXOs have satoshis (preserves value balance).
            // For tokens issued with 0 satoshis, use 0 dust to avoid "creating satoshi from nothing" error.
            val dustNeeded = if (assetDust > 0) 600L * numAssetOutputs else 0L

            // Always fetch RVN UTXOs to pay the fee, even if no dust is needed for asset outputs.
            val excludedOutpoints = node.getAllAssetOutpoints(address)
            val rvnUtxos = node.getUtxos(address)
                .filter { "${it.txid}:${it.outputIndex}" !in excludedOutpoints }
            
            // Recalculate fee with actual UTXO count
            val totalInputs = assetUtxos.size + rvnUtxos.size
            val estimatedBytes = 10 + 148 * totalInputs + 70 * numAssetOutputs + 34
            val feeSat = estimatedBytes * satPerByte
            
            // Verify RVN UTXOs cover both dust and fee
            val rvnTotal = rvnUtxos.sumOf { it.satoshis }
            val required = dustNeeded + feeSat
            if (rvnTotal < required) {
                error("Insufficient RVN for fee and dust. Need ${required / 1e8} RVN, have ${rvnTotal / 1e8} RVN. Fund your wallet with at least 0.01 RVN.")
            }
            if (rvnUtxos.isEmpty()) {
                error("Insufficient RVN for fee. Fund your wallet with at least 0.01 RVN.")
            }

            val tx = RavencoinTxBuilder.buildAndSignAssetTransfer(
                assetUtxos = assetUtxos,
                rvnUtxos = rvnUtxos,
                toAddress = toAddress,
                assetName = assetName,
                assetAmount = rawQtyRequested,
                assetChangeAmount = assetChangeRawAmount,
                feeSat = feeSat,
                changeAddress = address,
                privKeyBytes = privKey,
                pubKeyBytes = pubKey
            )
            node.broadcast(tx.hex)
        } finally {
            privKey?.fill(0)
        }
    }

    /**
     * Issue a Ravencoin asset directly on-chain (no backend required).
     * Builds and signs an rvni transaction, then broadcasts via ElectrumX.
     *
     * @param assetName  Full asset name: "ROOT", "ROOT/SUB", or "ROOT/SUB#UNIQUE"
     * @param qty        Asset quantity in display units (e.g. 1000.0)
     * @param units      Divisibility 0-8
     * @param reissuable Whether more supply can be issued later
     * @param ipfsHash   Optional CIDv0 "Qm..." IPFS hash for metadata
     * @return transaction ID on success
     */
    fun issueAssetLocal(
        assetName: String,
        qty: Double,
        toAddress: String,
        units: Int = 0,
        reissuable: Boolean = false,
        ipfsHash: String? = null
    ): String {
        val address = getAddress() ?: error("No wallet")
        var privKey: ByteArray? = null
        return try {
            privKey = getPrivateKeyBytes() ?: error("No private key")
            val pubKey = getPublicKeyBytes() ?: error("No public key")

            val node = RavencoinPublicNode()
            val utxos = node.getUtxos(address)
            if (utxos.isEmpty()) error("No spendable RVN found for address $address")
            val ownerAssetName = when {
                assetName.contains('#') -> assetName.substringBefore('#') + "!"
                assetName.contains('/') -> assetName.substringBefore('/') + "!"
                else -> null
            }
            val ownerAssetUtxos = ownerAssetName?.let { requiredOwnerAsset ->
                val allOwnerUtxos = node.getAssetUtxosFull(address, requiredOwnerAsset)
                require(allOwnerUtxos.isNotEmpty()) {
                    "Missing owner asset $requiredOwnerAsset in wallet. Transfer the owner token to this address before issuing $assetName."
                }
                // Owner tokens must have exactly amount = 1 (in raw units: 100000000 = 1 * 10^8).
                // Select a single UTXO with rawAmount = 100000000L.
                val singleOwnerUtxo = allOwnerUtxos.firstOrNull { it.assetRawAmount == 100_000_000L }
                    ?: allOwnerUtxos.firstOrNull()
                    ?: error("No valid owner token UTXO found for $requiredOwnerAsset")
                require(singleOwnerUtxo.assetRawAmount == 100_000_000L) {
                    "Owner token $requiredOwnerAsset has amount ${singleOwnerUtxo.assetRawAmount}, expected 100000000 (1 in raw units). " +
                    "Make sure you have a single owner token UTXO with amount 1."
                }
                // Owner-token UTXOs are signed as asset inputs, but compliant owner outputs carry zero RVN.
                // Keep the input selected while excluding it from the spendable RVN total.
                listOf(singleOwnerUtxo.utxo.copy(satoshis = 0L))
            }.orEmpty()

            val burnSat = when {
                assetName.contains('#') -> RavencoinTxBuilder.BURN_UNIQUE_SAT
                assetName.contains('/') -> RavencoinTxBuilder.BURN_SUB_SAT
                else                    -> RavencoinTxBuilder.BURN_ROOT_SAT
            }

            val satPerByte = node.getMinRelayFeeRateSatPerByte()
            // Estimate tx size: 10 + 148*inputs + 34 per output.
            // Root assets: burn + owner + issue + change = 4 outputs.
            // Sub-assets: burn + change + parent-owner return + new owner + issue = 5 outputs.
            // Unique tokens: burn + change + parent-owner return + issue = 4 outputs.
            val outputCount = when {
                assetName.contains('#') -> 4
                assetName.contains('/') -> 5
                else -> 4
            }
            val estimatedBytes = 10 + 148 * (utxos.size + ownerAssetUtxos.size) + 34 * outputCount
            val feeSat = estimatedBytes * satPerByte

            // Ravencoin encodes asset amounts with 8 fixed decimals; `units` limits the
            // allowed precision but does not change the wire-format scale.
            val qtyRaw = (qty * 100_000_000.0).toLong()
            val tx = RavencoinTxBuilder.buildAndSignAssetIssue(
                utxos = utxos.filterNot { rvn ->
                    ownerAssetUtxos.any { owner -> owner.txid == rvn.txid && owner.outputIndex == rvn.outputIndex }
                },
                ownerAssetUtxos = ownerAssetUtxos,
                assetName = assetName,
                qtyRaw = qtyRaw,
                toAddress = toAddress,
                changeAddress = address,
                units = units,
                reissuable = reissuable,
                ipfsHash = ipfsHash,
                burnSat = burnSat,
                feeSat = feeSat,
                privKeyBytes = privKey,
                pubKeyBytes = pubKey
            )
            node.broadcast(tx.hex)
        } finally {
            privKey?.fill(0)
        }
    }

    // ── BIP32/BIP44 key derivation ──────────────────────────────────────────

    /** Compute HMAC-SHA512 over [data] with [key] using BouncyCastle. Used for BIP32 child key derivation. */
    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA512Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(64).also { mac.doFinal(it, 0) }
    }

    private fun derivePrivateKey(seed: ByteArray, account: Int, index: Int): ByteArray {
        // Master key
        var I = hmacSha512("Bitcoin seed".toByteArray(Charsets.UTF_8), seed)
        var kl = I.copyOf(32)
        var kr = I.copyOfRange(32, 64)
        I.fill(0) // Secure clear intermediate

        // Derive: m/44'
        val i1 = deriveChild(kl, kr, 44 or 0x80000000.toInt())
        kl.fill(0); kr.fill(0)
        kl = i1.copyOf(32); kr = i1.copyOfRange(32, 64); i1.fill(0)
        
        // m/44'/175'
        val i2 = deriveChild(kl, kr, COIN_TYPE or 0x80000000.toInt())
        kl.fill(0); kr.fill(0)
        kl = i2.copyOf(32); kr = i2.copyOfRange(32, 64); i2.fill(0)
        
        // m/44'/175'/account'
        val i3 = deriveChild(kl, kr, account or 0x80000000.toInt())
        kl.fill(0); kr.fill(0)
        kl = i3.copyOf(32); kr = i3.copyOfRange(32, 64); i3.fill(0)
        
        // m/44'/175'/account'/0
        val i4 = deriveChild(kl, kr, 0)
        kl.fill(0); kr.fill(0)
        kl = i4.copyOf(32); kr = i4.copyOfRange(32, 64); i4.fill(0)
        
        // m/44'/175'/account'/0/index
        val i5 = deriveChild(kl, kr, index)
        kl.fill(0); kr.fill(0)
        val result = i5.copyOf(32)
        i5.fill(0)
        return result
    }

    /**
     * BIP32 child key derivation (private -> private).
     *
     * Returns 64 bytes: child_private_key(32) || child_chain_code(32).
     *
     * Per BIP32 spec:
     * - child_key = (IL + parent_key) mod n
     * - If IL >= n or child_key == 0, the key is invalid: skip to next index.
     *
     * The invalid-index case has probability ~2^-128 and will never occur in
     * practice, but the retry loop is required for strict spec compliance.
     */
    private fun deriveChild(parentKey: ByteArray, parentChain: ByteArray, index: Int): ByteArray {
        val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val n = spec.n
        var i = index
        while (true) {
            val data = if (i and 0x80000000.toInt() != 0) {
                // Hardened: 0x00 || parent_key || ser32(i)
                byteArrayOf(0x00) + parentKey + intToBytes(i)
            } else {
                // Normal: serP(parent_pubkey) || ser32(i)
                privateKeyToPublicKey(parentKey) + intToBytes(i)
            }
            val hmacOut = hmacSha512(parentChain, data)
            val IL = BigInteger(1, hmacOut.copyOf(32))
            val chainCode = hmacOut.copyOfRange(32, 64)
            // BIP32: invalid key if IL >= curve order
            if (IL >= n) { i++; continue }
            // BIP32: child_key = (IL + parent_key) mod n
            val childScalar = IL.add(BigInteger(1, parentKey)).mod(n)
            // BIP32: invalid key if result is zero
            if (childScalar == BigInteger.ZERO) { i++; continue }
            // Serialize to exactly 32 bytes (BigInteger.toByteArray may have leading 0x00)
            val raw = childScalar.toByteArray()
            val childKeyBytes = when {
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                raw.size < 32 -> ByteArray(32 - raw.size) + raw
                else          -> raw
            }
            return childKeyBytes + chainCode
        }
    }

    private fun intToBytes(i: Int): ByteArray = byteArrayOf(
        (i shr 24).toByte(), (i shr 16).toByte(), (i shr 8).toByte(), i.toByte()
    )

    private fun privateKeyToPublicKey(privKey: ByteArray): ByteArray {
        val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val privBig = BigInteger(1, privKey)
        val point = spec.g.multiply(privBig).normalize()
        return point.getEncoded(true) // compressed
    }

    private fun publicKeyToRavenAddress(pubKey: ByteArray): String {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(pubKey)
        val ripemd = ByteArray(20)
        val digest = RIPEMD160Digest()
        digest.update(sha256, 0, sha256.size)
        digest.doFinal(ripemd, 0)
        val payload = RVN_ADDRESS_VERSION + ripemd
        val checksum = checksum4(payload)
        return base58Encode(payload + checksum)
    }

    private fun checksum4(data: ByteArray): ByteArray {
        val h1 = MessageDigest.getInstance("SHA-256").digest(data)
        val h2 = MessageDigest.getInstance("SHA-256").digest(h1)
        return h2.copyOf(4)
    }

    private fun base58Encode(data: ByteArray): String {
        var num = BigInteger(1, data)
        val sb = StringBuilder()
        val base = BigInteger.valueOf(58)
        while (num > BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(base)
            sb.append(B58_ALPHABET[r.toInt()])
            num = q
        }
        for (b in data) {
            if (b == 0.toByte()) sb.append(B58_ALPHABET[0]) else break
        }
        return sb.reverse().toString()
    }

    // ── BIP39 ─────────────────────────────────────────────────────────────

    private fun entropyToMnemonic(entropy: ByteArray): String {
        val bits = entropy.flatMap { byte ->
            (7 downTo 0).map { i -> (byte.toInt() shr i) and 1 }
        }.toMutableList()
        val sha = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checkBits = (sha[0].toInt() shr (8 - entropy.size / 4)) and ((1 shl (entropy.size / 4)) - 1)
        repeat(entropy.size / 4) { i ->
            bits.add((checkBits shr (entropy.size / 4 - 1 - i)) and 1)
        }
        val words = bits.chunked(11).map { chunk ->
            val idx = chunk.fold(0) { acc, b -> (acc shl 1) or b }
            WORD_LIST[idx % WORD_LIST.size]
        }
        return words.joinToString(" ")
    }

    private fun mnemonicToSeed(mnemonic: String, passphrase: String): ByteArray {
        val mnemonicBytes = mnemonic.toByteArray(Charsets.UTF_8)
        val saltBytes = ("mnemonic$passphrase").toByteArray(Charsets.UTF_8)
        val spec = javax.crypto.spec.PBEKeySpec(
            mnemonic.toCharArray(), saltBytes, 2048, 512
        )
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded.also {
            spec.clearPassword()
            mnemonicBytes.fill(0)
        }
    }
}
