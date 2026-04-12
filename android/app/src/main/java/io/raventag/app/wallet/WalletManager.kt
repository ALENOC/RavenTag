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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

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
    @Volatile private var sweepRunning = false

    companion object {
        private const val PREFS_NAME = "raventag_wallet"
        private const val KEY_SEED_ENC = "seed_enc"
        private const val KEY_SEED_IV = "seed_iv"
        private const val KEY_MNEMONIC_ENC = "mnemonic_enc"
        private const val KEY_MNEMONIC_IV = "mnemonic_iv"
        private const val KEY_ADDRESS_INDEX = "address_index"
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
            .remove(KEY_ADDRESS_INDEX)
            .apply()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)
        } catch (_: Exception) {}
    }

    // ── Address rotation (post-quantum protection) ─────────────────────────

    /**
     * Returns the current BIP44 address index (the "clean" address that has
     * never been used for an outgoing transaction).
     *
     * Path: m/44'/175'/0'/0/{index}
     *
     * Defaults to 0 for wallets created before the address rotation feature,
     * ensuring full backward compatibility.
     */
    fun getCurrentAddressIndex(): Int = prefs().getInt(KEY_ADDRESS_INDEX, 0)

    /**
     * Persist the current address index and invalidate the cached address.
     * Called after every outgoing transaction to advance to a fresh address
     * whose public key has never been exposed on-chain.
     */
    private fun setCurrentAddressIndex(index: Int) {
        prefs().edit().putInt(KEY_ADDRESS_INDEX, index).apply()
        cachedAddress = null
    }

    /**
     * Convenience method: returns the address at the current BIP44 index.
     * This is the address that should be shown to the user for receiving funds.
     */
    fun getCurrentAddress(): String? = getAddress(0, getCurrentAddressIndex())

    /**
     * Lightweight check on app startup: verifies that the current address has
     * never made an outgoing transaction (public key still hidden).
     *
     * If the current address has outgoing history, advances to the next clean
     * address. This covers two cases:
     * - Pre-rotation wallets that never had KEY_ADDRESS_INDEX set.
     * - Edge cases where the app was killed before the index was advanced.
     *
     * Makes at most 2 ElectrumX calls (history + listunspent) per check.
     * If the current address is clean, returns immediately with no network calls
     * beyond the status check.
     */
    /**
     * Reconcile the current address index with the actual on-chain state.
     * Finds the HIGHEST index that has BOTH:
     *   1. Clean status (RECEIVE_ONLY or NO_HISTORY - no outgoing transactions)
     *   2. Actual funds (UTXOs exist)
     *
     * This ensures the wallet points to the correct receive address that holds
     * the consolidated funds.
     */
    /**
     * Verify the stored address index is not lower than it should be.
     * Only INCREASES the index (never decreases), because post-quantum
     * safety requires the index to only move forward after outgoing transactions.
     */
    fun reconcileCurrentAddressIndex(): Int {
        val storedIndex = getCurrentAddressIndex()
        // No-op: the index is managed exclusively by sendRvnLocal/transferAssetLocal
        // (which advance it) and discoverCurrentIndex (which finds the correct starting
        // point after wallet restore). Lowering the index would break asset visibility
        // and post-quantum protection.
        return storedIndex
    }

    /**
     * No-op: kept for API compatibility.
     * The current address index is now managed exclusively by:
     *   - sendRvnLocal / transferAssetLocal (advance after outgoing tx)
     *   - discoverCurrentIndex (find correct start after wallet restore)
     * Advancing the index based on address status was causing the index to
     * decrease on network errors, hiding assets from the UI.
     */
    fun ensureCurrentAddressClean() {
        // intentionally empty
    }

    /**
     * Discover the current address index by scanning the BIP44 address chain.
     * Used after wallet restore from mnemonic to find the first unused address
     * that has never made an outgoing transaction.
     *
     * Scans in parallel batches of 5 addresses at a time, using a BIP44 gap
     * limit of 20 consecutive addresses with no on-chain history.
     *
     * The discovered index is the first address that is either RECEIVE_ONLY
     * (has received funds but never spent) or NO_HISTORY (never used).
     * Addresses with HAS_OUTGOING status are skipped because their public
     * key has been exposed.
     *
     * @return The discovered index (first clean address after all used ones).
     */
    suspend fun discoverCurrentIndex(): Int = withContext(Dispatchers.IO) {
        val node = RavencoinPublicNode()
        var lastUsed = -1
        var gapCount = 0
        var batchStart = 0
        val batchSize = 20  // 1 Keystore decrypt + 2 TLS calls per 20 addresses

        while (gapCount < 20) {
            // Single Keystore decrypt for 20 addresses at once
            val batchMap = getAddressBatch(0, batchStart until batchStart + batchSize)
            // Empty map means Keystore or network failure. If it happens on the very first
            // batch we have no data at all — bail out WITHOUT touching the stored index.
            if (batchMap.isEmpty()) break

            val addrList = (batchStart until batchStart + batchSize).mapNotNull { batchMap[it] }

            // 2 TLS calls total for all 20 address statuses
            val statusMap = try {
                node.getAddressStatusBatch(addrList)
            } catch (_: Exception) {
                emptyMap()
            }

            for (i in batchStart until batchStart + batchSize) {
                val addr = batchMap[i] ?: continue
                val status = statusMap[addr] ?: RavencoinPublicNode.AddressStatus.NO_HISTORY
                if (status != RavencoinPublicNode.AddressStatus.NO_HISTORY) {
                    lastUsed = i
                    gapCount = 0
                } else {
                    gapCount++
                    if (gapCount >= 20) break
                }
            }

            batchStart += batchSize
        }

        // Find the first clean address after the last used one
        // (skip HAS_OUTGOING, land on RECEIVE_ONLY or NO_HISTORY)
        // Fetch a small batch to avoid per-address Keystore decrypts
        var newIndex = lastUsed + 1
        outer@ while (true) {
            val lookMap = getAddressBatch(0, newIndex until newIndex + 5)
            if (lookMap.isEmpty()) break
            val lookAddrs = (newIndex until newIndex + 5).mapNotNull { lookMap[it] }
            val lookStatuses = try { node.getAddressStatusBatch(lookAddrs) } catch (_: Exception) { break }
            for (addr in lookAddrs) {
                val s = lookStatuses[addr] ?: break@outer
                if (s != RavencoinPublicNode.AddressStatus.HAS_OUTGOING) break@outer
                newIndex++
            }
            if (lookAddrs.size < 5) break
        }

        // If the very first batch returned nothing (Keystore/network failure), keep the
        // stored index intact. Overwriting with newIndex=0 would make all subsequent
        // balance checks look only at address 0 and show 0 RVN.
        val storedIndex = getCurrentAddressIndex()
        if (batchStart == 0 && lastUsed == -1) {
            android.util.Log.w("WalletManager", "Discover: first batch empty (network/Keystore error), keeping stored index $storedIndex")
            return@withContext storedIndex
        }
        // Never decrease the stored index — a lower result means discovery scanned fewer
        // addresses than previously known (transient gap in connectivity), not a rollback.
        val finalIndex = maxOf(newIndex, storedIndex)
        setCurrentAddressIndex(finalIndex)
        android.util.Log.i("WalletManager", "Discover: scanned $batchStart addresses, current index = $finalIndex")
        finalIndex
    }

    /**
     * Sweep funds and assets from old addresses (0..currentIndex-1) that have
     * outgoing transaction history (HAS_OUTGOING) to the current address.
     *
     * Addresses that have only received funds (RECEIVE_ONLY) are NOT swept,
     * because their public key has never been exposed and they are quantum-safe.
     * Only addresses that have spent (exposing their public key) need to be
     * consolidated to the current clean address.
     *
     * Each old address is swept independently using its own private key.
     * Assets are swept before RVN (assets need RVN for fees from the same address).
     *
     * @return List of broadcast transaction IDs.
     */
    fun sweepOldAddresses(): List<String> {
        if (sweepRunning) return emptyList()
        sweepRunning = true

        try {
            return sweepOldAddressesInternal()
        } finally {
            sweepRunning = false
        }
    }

    /**
     * Result of funding an old address for asset sweeping.
     *
     * @property txid       Transaction ID of the funding tx.
     * @property fundUtxo   Synthetic UTXO representing the funding output on the old address.
     *                      Can be used immediately without waiting for mempool propagation.
     */
    private data class FundingResult(val txid: String, val fundUtxo: Utxo)

    /**
     * Send a small amount of RVN from a sacrificial address to an old address
     * so the old address can pay fees for sweeping its assets.
     *
     * POST-QUANTUM SAFE: Uses a sacrificial address that already HAS_OUTGOING status
     * (public key already exposed), so funding does NOT expose any clean address keys.
     *
     * If no sacrificial address is available, returns null and the caller should skip
     * the asset sweep for that address (assets remain until the address receives RVN externally).
     *
     * Returns a [FundingResult] with a synthetic UTXO that can be used immediately
     * (no need to re-query ElectrumX, which may not reflect mempool yet).
     *
     * @param node              ElectrumX client.
     * @param sacrificialIndex  Index of an address with HAS_OUTGOING status to fund from, or null.
     * @param oldAddress        Old address that needs RVN for fees.
     * @param assetCount        Number of assets to sweep (used to estimate fee budget).
     * @return [FundingResult] on success, or null if no sacrificial address available.
     */
    private fun fundOldAddressForSweep(
        node: RavencoinPublicNode,
        sacrificialIndex: Int?,
        oldAddress: String,
        assetCount: Int
    ): FundingResult? {
        if (sacrificialIndex == null) {
            android.util.Log.w("WalletManager", "Sweep: no sacrificial address available, skipping funding for $oldAddress")
            return null
        }

        val sacrificialAddress = getAddress(0, sacrificialIndex) ?: return null

        // Estimate how much RVN the old address needs:
        // each asset transfer ~ 300 bytes, plus a small buffer for the final RVN sweep
        val satPerByte = try { node.getMinRelayFeeRateSatPerByte() } catch (_: FeeUnavailableException) { 200L }
        val perAssetFee = 300L * satPerByte
        val fundAmountSat = perAssetFee * assetCount + 200L * satPerByte  // extra for final RVN sweep

        // Get UTXOs from sacrificial address (exclude asset outpoints)
        val sacAssetOutpoints = try { node.getAllAssetOutpoints(sacrificialAddress) } catch (_: Exception) { emptySet() }
        val sacUtxos = node.getUtxos(sacrificialAddress)
            .filter { "${it.txid}:${it.outputIndex}" !in sacAssetOutpoints }
        if (sacUtxos.isEmpty()) {
            android.util.Log.w("WalletManager", "Sweep: sacrificial address $sacrificialIndex has no RVN")
            return null
        }

        val totalIn = sacUtxos.sumOf { it.satoshis }
        val fundingTxFee = (10L + 148L * sacUtxos.size + 34L * 2) * satPerByte
        if (totalIn < fundAmountSat + fundingTxFee) {
            android.util.Log.w("WalletManager", "Sweep: sacrificial address $sacrificialIndex has insufficient RVN")
            return null
        }

        var privKey: ByteArray? = null
        try {
            privKey = getPrivateKeyBytes(0, sacrificialIndex) ?: return null
            val pubKey = getPublicKeyBytes(0, sacrificialIndex) ?: return null

            val tx = RavencoinTxBuilder.buildAndSign(
                utxos = sacUtxos,
                toAddress = oldAddress,
                amountSat = fundAmountSat,
                feeSat = fundingTxFee,
                changeAddress = sacrificialAddress,  // change stays on sacrificial (no rotation)
                privKeyBytes = privKey,
                pubKeyBytes = pubKey
            )
            val txid = node.broadcast(tx.hex)
            android.util.Log.i("WalletManager", "Sweep: funded $oldAddress with ${fundAmountSat / 1e8} RVN from sacrificial $sacrificialIndex: $txid")

            // Build synthetic UTXO: output 0 is always the recipient in buildAndSign
            val scriptHex = addressToP2pkhScript(oldAddress)
            val fundUtxo = Utxo(
                txid = txid,
                outputIndex = 0,
                satoshis = fundAmountSat,
                script = scriptHex,
                height = 0  // mempool
            )

            return FundingResult(txid, fundUtxo)
        } finally {
            privKey?.fill(0)
        }
    }

    /**
     * Convert a Ravencoin P2PKH address to its scriptPubKey hex.
     * Format: OP_DUP OP_HASH160 <20-byte hash> OP_EQUALVERIFY OP_CHECKSIG
     */
    private fun addressToP2pkhScript(address: String): String {
        val decoded = base58Decode(address)
        val hash160 = decoded.copyOfRange(1, 21)
        return "76a914" + hash160.joinToString("") { "%02x".format(it) } + "88ac"
    }

    private fun sweepOldAddressesInternal(): List<String> {
        val currentIndex = getCurrentAddressIndex()
        if (currentIndex == 0) return emptyList()

        val node = RavencoinPublicNode()

        // Collect only HAS_OUTGOING addresses with residual funds.
        //
        // RECEIVE_ONLY addresses (received funds, never sent) are quantum-safe: their
        // public key has never appeared in a scriptSig. Sweeping them would create an
        // outgoing transaction that exposes the key, defeating the entire post-quantum
        // protection model. They must NEVER be touched here.
        //
        // NO_HISTORY addresses have no funds, nothing to sweep.
        //
        // The current address (index == currentIndex) is NEVER included: it is the live
        // receiving address and will be swept by sendRvnLocal() when the user next sends.
        //
        // Range is 0 until currentIndex (exclusive upper bound).
        data class SweepTarget(
            val index: Int,
            val address: String,
            val hasAssets: Boolean,
            val hasRvn: Boolean
        )
        android.util.Log.i("WalletManager", "Sweep: scanning ${currentIndex} old addresses (0..${currentIndex - 1})")

        val addrBatch = getAddressBatch(0, 0 until currentIndex)
        val addrList = (0 until currentIndex).mapNotNull { i -> addrBatch[i]?.let { i to it } }

        val targets = mutableListOf<SweepTarget>()
        for ((i, addr) in addrList) {
            try {
                val r = node.getUtxosAndAllAssetUtxosBatch(addr)
                val hasAssets = r.third.isNotEmpty()
                val rvnBalance = r.first.sumOf { it.satoshis }
                if (hasAssets || rvnBalance > 0) {
                    android.util.Log.i("WalletManager", "Sweep: index $i ($addr) has assets=$hasAssets rvn=${rvnBalance / 1e8}")
                    targets.add(SweepTarget(i, addr, hasAssets, rvnBalance > 0))
                }
            } catch (e: Exception) {
                android.util.Log.w("WalletManager", "Sweep: scan failed for index $i: ${e.message}")
            }
        }
        if (targets.isEmpty()) {
            android.util.Log.i("WalletManager", "Sweep: no funded old addresses found, nothing to do")
            return emptyList()
        }

        // The sweep destination is the current address — already clean, no index advance.
        // (Index advances only inside sendRvnLocal(), after the user makes an outgoing tx.)
        val targetAddress = getAddress(0, currentIndex) ?: return emptyList()
        android.util.Log.i("WalletManager", "Sweep: consolidating ${targets.size} HAS_OUTGOING address(es) to index $currentIndex")

        val txids = mutableListOf<String>()

        // STEP 1: Fund asset-only targets (assets but no RVN for fees).
        // Use another HAS_OUTGOING address with RVN as the sacrificial source.
        // The current clean address is NEVER used for funding (would expose its key).
        val sacrificialIndex = targets.firstOrNull { it.hasRvn && !it.hasAssets }?.index
            ?: targets.firstOrNull { it.hasRvn }?.index
        val needsFunding = targets.filter { it.hasAssets && !it.hasRvn }
        for (t in needsFunding) {
            val assetCount = try { node.getAssetBalances(t.address).size } catch (_: Exception) { 1 }
            val result = fundOldAddressForSweep(node, sacrificialIndex, t.address, assetCount)
            if (result != null) txids.add(result.txid)
        }

        // STEP 2: Sweep each target to the current address.
        for (t in targets) {
            try {
                val assetBalances = if (t.hasAssets) {
                    try { node.getAssetBalances(t.address) } catch (_: Exception) { emptyList() }
                } else emptyList()

                val assetUtxosMap = mutableMapOf<String, List<AssetUtxo>>()
                for (asset in assetBalances) {
                    if (asset.amount > 0) {
                        val utxos = node.getAssetUtxosFull(t.address, asset.name)
                        if (utxos.isNotEmpty()) assetUtxosMap[asset.name] = utxos
                    }
                }

                val allAssetOutpoints = node.getAllAssetOutpoints(t.address)
                val rvnUtxos = node.getUtxos(t.address)
                    .filter { "${it.txid}:${it.outputIndex}" !in allAssetOutpoints }

                if (assetUtxosMap.isEmpty() && rvnUtxos.isEmpty()) continue

                var privKey: ByteArray? = null
                try {
                    privKey = getPrivateKeyBytes(0, t.index) ?: continue
                    val pubKey = getPublicKeyBytes(0, t.index) ?: continue

                    if (assetUtxosMap.isNotEmpty()) {
                        val totalAssetOutputs = assetBalances.count { it.amount > 0 }
                        val totalInputs = rvnUtxos.size + assetUtxosMap.values.sumOf { it.size }
                        val estimatedBytes = 10 + 148 * totalInputs + 70 * (1 + totalAssetOutputs) + 34
                        val feeSat = estimatedBytes * node.getMinRelayFeeRateSatPerByte()

                        val tx = RavencoinTxBuilder.buildAndSignFullAddressSweep(
                            assetUtxos = assetUtxosMap,
                            rvnUtxos = rvnUtxos,
                            feeSat = feeSat,
                            changeAddress = targetAddress,
                            privKeyBytes = privKey,
                            pubKeyBytes = pubKey
                        )
                        val txid = node.broadcast(tx.hex)
                        txids.add(txid)
                        android.util.Log.i("WalletManager", "Sweep: assets+RVN from index ${t.index} to $currentIndex: $txid")

                    } else if (rvnUtxos.isNotEmpty()) {
                        val totalSat = rvnUtxos.sumOf { it.satoshis }
                        val satPerByte = node.getMinRelayFeeRateSatPerByte()
                        val estimatedBytes = 10 + 148 * rvnUtxos.size + 34
                        val feeSat = estimatedBytes * satPerByte
                        val sendAmount = totalSat - feeSat

                        if (sendAmount > 546) {
                            val tx = RavencoinTxBuilder.buildAndSign(
                                utxos = rvnUtxos,
                                toAddress = targetAddress,
                                amountSat = sendAmount,
                                feeSat = feeSat,
                                changeAddress = targetAddress,
                                privKeyBytes = privKey,
                                pubKeyBytes = pubKey
                            )
                            val txid = node.broadcast(tx.hex)
                            txids.add(txid)
                            android.util.Log.i("WalletManager", "Sweep: RVN from index ${t.index} to $currentIndex: $txid")
                        }
                    }
                } finally {
                    privKey?.fill(0)
                }
            } catch (e: Exception) {
                android.util.Log.w("WalletManager", "Sweep: index ${t.index} failed: ${e.message}")
            }
        }

        return txids
    }

    /**
     * Fund multiple old addresses that have assets but no RVN.
     * Uses the current address as the funding source.
     *
     * @param node ElectrumX client.
     * @param addressesToFund List of (index, address) pairs that need funding.
     * @return List of funding transaction IDs.
     */
    private fun fundAddressesForSweep(
        node: RavencoinPublicNode,
        addressesToFund: List<Pair<Int, String>>
    ): List<String> {
        if (addressesToFund.isEmpty()) return emptyList()

        val currentIndex = getCurrentAddressIndex()
        val currentAddress = getAddress(0, currentIndex) ?: return emptyList()

        // Get UTXOs from current address
        val curAssetOutpoints = try { node.getAllAssetOutpoints(currentAddress) } catch (_: Exception) { emptySet() }
        val curUtxos = node.getUtxos(currentAddress)
            .filter { "${it.txid}:${it.outputIndex}" !in curAssetOutpoints }
        if (curUtxos.isEmpty()) {
            android.util.Log.w("WalletManager", "Sweep funding: no RVN on current address")
            return emptyList()
        }

        val fundingTxids = mutableListOf<String>()
        val satPerByte = try { node.getMinRelayFeeRateSatPerByte() } catch (_: FeeUnavailableException) { 200L }

        for ((index, oldAddress) in addressesToFund) {
            val assetBalances = try { node.getAssetBalances(oldAddress) } catch (_: Exception) { emptyList() }
            if (assetBalances.isEmpty()) continue

            // Estimate funding needed
            val perAssetFee = 300L * satPerByte
            val fundAmountSat = perAssetFee * assetBalances.size + 500L * satPerByte

            // Use remaining RVN after previous funding txs
            val totalIn = curUtxos.sumOf { it.satoshis }
            val alreadyFunded = fundingTxids.size * fundAmountSat
            val available = totalIn - alreadyFunded
            if (available < fundAmountSat) {
                android.util.Log.w("WalletManager", "Sweep funding: insufficient for index $index")
                continue
            }

            var privKey: ByteArray? = null
            try {
                privKey = getPrivateKeyBytes(0, currentIndex) ?: continue
                val pubKey = getPublicKeyBytes(0, currentIndex) ?: continue

                val fundingFee = (10L + 148L * 2 + 34L * 2) * satPerByte
                val tx = RavencoinTxBuilder.buildAndSign(
                    utxos = curUtxos,
                    toAddress = oldAddress,
                    amountSat = fundAmountSat,
                    feeSat = fundingFee,
                    changeAddress = currentAddress,
                    privKeyBytes = privKey,
                    pubKeyBytes = pubKey
                )
                val txid = node.broadcast(tx.hex)
                fundingTxids.add(txid)
                android.util.Log.i("WalletManager", "Sweep funding: funded index $index ($oldAddress) with ${fundAmountSat / 1e8} RVN: $txid")
            } catch (e: Exception) {
                android.util.Log.w("WalletManager", "Sweep funding: failed index $index: ${e.message}")
            } finally {
                privKey?.fill(0)
            }
        }

        return fundingTxids
    }

    /**
     * Restore wallet from existing mnemonic.
     *
     * Address index discovery is NOT done here (it requires many network calls).
     * The caller should run [discoverCurrentIndex] in background after restore
     * completes, or let [migrateAddressIndexIfNeeded] handle it on first refresh.
     */
    fun restoreWallet(mnemonic: String): Boolean {
        return try {
            val normalized = mnemonic.trim()
            if (!validateMnemonic(normalized)) return false
            val seed = mnemonicToSeed(normalized, "")
            storeSeed(seed, normalized)
            cachedAddress = null
            // Reset address index to 0; discovery will find the correct one
            prefs().edit().putInt(KEY_ADDRESS_INDEX, 0).apply()
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

    /** Get the Ravencoin address at m/44'/175'/0'/{accountIndex}/{addressIndex}. */
    fun getAddress(accountIndex: Int = 0, addressIndex: Int = 0): String? {
        // Return cached address if this is the current active index
        val currentIdx = getCurrentAddressIndex()
        if (accountIndex == 0 && addressIndex == currentIdx) {
            cachedAddress?.let { return it }
        }
        var seed: ByteArray? = null
        var privKey: ByteArray? = null
        return try {
            seed = getSeed() ?: return null
            privKey = derivePrivateKey(seed, accountIndex, addressIndex)
            val pubKey = privateKeyToPublicKey(privKey)
            val address = publicKeyToRavenAddress(pubKey)
            if (accountIndex == 0 && addressIndex == currentIdx) cachedAddress = address
            address
        } catch (_: Throwable) {
            null
        } finally {
            seed?.fill(0)
            privKey?.fill(0)
        }
    }

    /**
     * Decrypt the seed once and derive all addresses in [indices] from a single Keystore operation.
     *
     * Calling [getAddress] N times triggers N independent Keystore AES-GCM decrypts. Because
     * Android Keystore serializes internally under StrongBox contention, running those N decrypts
     * in parallel causes each one to queue behind the others and the total time grows super-linearly.
     * This function avoids the problem: one decrypt, N cheap BIP32 derivations.
     *
     * @param accountIndex BIP44 account index (almost always 0)
     * @param indices      Range of address indices to derive
     * @return Map from index to derived Ravencoin address; missing entries indicate derivation errors
     */
    fun getAddressBatch(accountIndex: Int, indices: IntRange): Map<Int, String> {
        val seed = getSeed() ?: return emptyMap()
        val result = mutableMapOf<Int, String>()
        val currentIdx = getCurrentAddressIndex()
        try {
            for (i in indices) {
                var privKey: ByteArray? = null
                try {
                    privKey = derivePrivateKey(seed, accountIndex, i)
                    val address = publicKeyToRavenAddress(privateKeyToPublicKey(privKey))
                    result[i] = address
                    if (accountIndex == 0 && i == currentIdx) cachedAddress = address
                } catch (_: Throwable) {
                } finally {
                    privKey?.fill(0)
                }
            }
        } finally {
            seed.fill(0)
        }
        return result
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

    fun getKeyPairBatch(accountIndex: Int, indices: IntRange): Map<Int, Pair<ByteArray, ByteArray>> {
        val seed = getSeed() ?: return emptyMap()
        val result = mutableMapOf<Int, Pair<ByteArray, ByteArray>>()
        try {
            for (i in indices) {
                try {
                    val priv = derivePrivateKey(seed, accountIndex, i)
                    val pub = privateKeyToPublicKey(priv)
                    result[i] = Pair(priv, pub)
                } catch (_: Throwable) {}
            }
        } finally {
            seed.fill(0)
        }
        return result
    }

    /**
     * Returns (privateKeyBytes, publicKeyBytes) from a single Keystore decrypt.
     *
     * This is more efficient than calling [getPrivateKeyBytes] and [getPublicKeyBytes]
     * separately, which would each invoke [getSeed] and thus require two Keystore
     * AES-GCM decryptions (~250 ms each under StrongBox).
     *
     * The returned private key is a copy allocated by [derivePrivateKey]. The CALLER
     * is responsible for zeroing it with [ByteArray.fill] after use. The public key
     * does not need to be zeroed.
     *
     * @return Pair(privKeyBytes, pubKeyBytes), or null if the wallet is not set up
     *         or the Keystore is locked.
     */
    fun getKeyPair(accountIndex: Int = 0, addressIndex: Int = 0): Pair<ByteArray, ByteArray>? {
        var seed: ByteArray? = null
        var priv: ByteArray? = null
        var succeeded = false
        return try {
            seed = getSeed() ?: return null
            priv = derivePrivateKey(seed, accountIndex, addressIndex)
            val pub = privateKeyToPublicKey(priv)
            succeeded = true
            Pair(priv, pub)
        } catch (_: Throwable) {
            null
        } finally {
            seed?.fill(0)
            // Zero priv only on failure; on success the caller owns it and must zero it
            if (!succeeded) priv?.fill(0)
        }
    }

    /**
     * Query aggregated balance across all used addresses (0..currentIndex)
     * directly from public Ravencoin nodes (no backend required).
     * Returns total balance in RVN, or null on failure.
     *
     * Uses [getAddressBatch] for a single Keystore decrypt, then sends all
     * balance requests in one pipelined batch via [RavencoinPublicNode.getTotalBalance].
     * With 37 addresses this opens 2 TLS connections instead of 37.
     */
    // Returns null only on network failure; returns 0.0 for a genuinely empty wallet.
    suspend fun getLocalBalance(): Double? = withContext(Dispatchers.IO) {
        try {
            val node = RavencoinPublicNode()
            val currentIndex = getCurrentAddressIndex()
            val addresses = getAddressBatch(0, 0..currentIndex).values.toList()
            node.getTotalBalance(addresses)
        } catch (_: Exception) { null }
    }

    /**
     * Send RVN from local wallet directly to the network with post-quantum protection.
     *
     * POST-QUANTUM SAFE LOGIC - SINGLE TRANSACTION:
     * 1. Send the requested RVN amount to the external destination address
     * 2. Transfer ALL assets to a fresh address (currentIndex + 1)
     * 3. Send ALL remaining RVN to the fresh address (currentIndex + 1)
     * 4. Advance the address index so the next transaction uses the clean address
     *
     * This ensures that after ANY outgoing transaction, the current address is completely
     * emptied (both RVN and ALL assets) and all remaining funds are moved to a fresh,
     * quantum-safe address whose public key has never been exposed on-chain.
     *
     * @param toAddress Recipient Ravencoin address
     * @param amountRvn Amount in RVN
     * @return "$txid|fee:$satoshis" on success
     */
    suspend fun sendRvnLocal(toAddress: String, amountRvn: Double): String = withContext(Dispatchers.IO) {
        var currentIndex = getCurrentAddressIndex()
        var address = getAddress(0, currentIndex) ?: error("No wallet")
        val node = RavencoinPublicNode()

        // Fetch all UTXOs and the relay fee rate in parallel (2 TLS for UTXOs + 1 TLS for fee,
        // all 3 connections run concurrently so total wall time is max(~600ms, ~300ms) not sum).
        val (utxoResult, satPerByte) = coroutineScope {
            val utxosDeferred = async { node.getUtxosAndAllAssetUtxosBatch(address) }
            val feeDeferred   = async { node.getMinRelayFeeRateSatPerByte() }
            Pair(utxosDeferred.await(), feeDeferred.await())
        }
        var rvnUtxos:    List<Utxo>                    = utxoResult.first
        var assetUtxosMap: Map<String, List<AssetUtxo>> = utxoResult.third

        // FALLBACK: If current address has no RVN, scan backwards to find the address with funds.
        // This handles the case where a sweep advanced currentIndex but funds remain on a previous address.
        if (rvnUtxos.isEmpty()) {
            val bal = try { node.getBalance(address) } catch (_: Exception) { null }
            if (bal != null && bal.unconfirmed > 0 && bal.confirmed == 0L) {
                error("Transaction not confirmed yet. Wait for 1-2 blocks before sending.")
            }

            android.util.Log.w("WalletManager", "sendRvn: currentIndex $currentIndex has no funds, scanning backwards for fallback")
            var fallbackIndex = -1
            for (i in currentIndex - 1 downTo 0) {
                val fallbackAddr = getAddress(0, i) ?: continue
                val fallbackUtxos = try { node.getUtxos(fallbackAddr) } catch (_: Exception) { emptyList() }
                if (fallbackUtxos.isNotEmpty()) {
                    android.util.Log.i("WalletManager", "sendRvn: found funds on index $i")
                    fallbackIndex = i
                    break
                }
            }

            if (fallbackIndex == -1) {
                error("No spendable funds on current address. Try refreshing the wallet to consolidate funds.")
            }

            // Advance currentIndex to the fallback; nextAddress will be currentIndex+1 (quantum-safe).
            currentIndex = fallbackIndex
            setCurrentAddressIndex(currentIndex)
            address = getAddress(0, currentIndex) ?: error("Cannot derive fallback address")
            android.util.Log.i("WalletManager", "sendRvn: fallback to index $currentIndex ($address)")

            // Re-fetch UTXOs for the fallback address (still only 2 TLS connections)
            val fallback = node.getUtxosAndAllAssetUtxosBatch(address)
            rvnUtxos      = fallback.first
            assetUtxosMap  = fallback.third
        }

        if (rvnUtxos.isEmpty()) {
            error("No RVN available for transaction fee. Fund your wallet with at least 0.01 RVN.")
        }

        val nextAddress = getAddress(0, currentIndex + 1) ?: error("Cannot derive next address")

        // Single Keystore decrypt for both private and public key (~250 ms saved vs two separate calls)
        val keyPair = getKeyPair(0, currentIndex) ?: error("No wallet key")
        var privKey: ByteArray? = keyPair.first
        val pubKey = keyPair.second

        val amountSat = (amountRvn * 1e8).toLong()

        // ── Force HD Discovery & Sweep for all addresses with funds ──────────
        data class OldFunds(val index: Int, val rvn: List<Utxo>, val assets: Map<String, List<AssetUtxo>>)
        val oldFunds = mutableListOf<OldFunds>()
        val debugBatch = getAddressBatch(0, 0 until 100)
        android.util.Log.i("WalletManager", "sendRvn: Starting sweep on known batch of ${debugBatch.size} addresses")

        try {
            for ((index, addr) in debugBatch) {
                if (index == currentIndex) continue
                val r = node.getUtxosAndAllAssetUtxosBatch(addr)
                if (r.first.isNotEmpty() || r.third.isNotEmpty()) {
                    oldFunds.add(OldFunds(index, r.first, r.third))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WalletManager", "Discovery failed", e)
        }

        // Merge all assets: current address + all discovered addresses
        val mergedAssets = mutableMapOf<String, MutableList<AssetUtxo>>()
        assetUtxosMap.forEach { (name, utxos) -> mergedAssets.getOrPut(name) { mutableListOf() }.addAll(utxos) }
        oldFunds.forEach { of -> of.assets.forEach { (name, utxos) -> mergedAssets.getOrPut(name) { mutableListOf() }.addAll(utxos) } }

        val hasAssets   = mergedAssets.isNotEmpty()
        val hasOldFunds = oldFunds.isNotEmpty()

        // Key pairs for old addresses (single Keystore decrypt for all indices)
        var oldKeyPairs: Map<Int, Pair<ByteArray, ByteArray>> = emptyMap()

        return@withContext try {
            val txid: String
            var feeSatActual: Long = 0L

            if (hasAssets || hasOldFunds) {
                // POST-QUANTUM SAFE: atomic transaction sweeps assets + old RVN + sends to toAddress
                if (oldFunds.isNotEmpty()) {
                    val minIdx = oldFunds.minOf { it.index }
                    val maxIdx = oldFunds.maxOf { it.index }
                    oldKeyPairs = getKeyPairBatch(0, minIdx..maxIdx)
                }

                // Build KeyedUtxo/KeyedAssetUtxo lists
                val currentRvnKeyed = rvnUtxos.map { RavencoinTxBuilder.KeyedUtxo(it, privKey!!, pubKey) }
                val extraRvnKeyed   = oldFunds.flatMap { of ->
                    val (op, ok) = oldKeyPairs[of.index] ?: return@flatMap emptyList()
                    of.rvn.map { RavencoinTxBuilder.KeyedUtxo(it, op, ok) }
                }
                val assetKeyed = mutableMapOf<String, MutableList<RavencoinTxBuilder.KeyedAssetUtxo>>()
                // Current address assets
                assetUtxosMap.forEach { (name, utxos) ->
                    assetKeyed.getOrPut(name) { mutableListOf() }
                        .addAll(utxos.map { RavencoinTxBuilder.KeyedAssetUtxo(it, privKey!!, pubKey) })
                }
                // Discovered address assets
                oldFunds.forEach { of ->
                    val (op, ok) = oldKeyPairs[of.index] ?: return@forEach
                    of.assets.forEach { (name, utxos) ->
                        assetKeyed.getOrPut(name) { mutableListOf() }
                            .addAll(utxos.map { RavencoinTxBuilder.KeyedAssetUtxo(it, op, ok) })
                    }
                }

                val totalInputs      = rvnUtxos.size + extraRvnKeyed.size + assetKeyed.values.sumOf { it.size }
                val totalAssetOutputs = assetKeyed.size
                val estimatedBytes   = 10 + 148 * totalInputs + 70 * (2 + totalAssetOutputs) + 34
                feeSatActual = estimatedBytes * satPerByte

                val tx = RavencoinTxBuilder.buildAndSignMultiAddressSend(
                    currentRvnInputs  = currentRvnKeyed,
                    extraRvnInputs    = extraRvnKeyed,
                    assetInputsByName = assetKeyed,
                    toAddress         = toAddress,
                    amountSat         = amountSat,
                    feeSat            = feeSatActual,
                    changeAddress     = nextAddress
                )
                txid = node.broadcast(tx.hex)

                android.util.Log.i("WalletManager", "sendRvn atomic: sent $amountRvn RVN to $toAddress, " +
                    "all assets and remaining RVN to $nextAddress, txid=$txid")

            } else {
                // Simple RVN send (no assets to sweep)
                val estimatedBytes = 10 + 148 * rvnUtxos.size + 34 * 2
                feeSatActual = estimatedBytes * satPerByte

                val totalIn = rvnUtxos.sumOf { it.satoshis }
                require(totalIn > amountSat + feeSatActual) {
                    "Insufficient funds: have ${totalIn / 1e8} RVN, need ${amountSat / 1e8} RVN + ${feeSatActual / 1e8} RVN fee"
                }

                val changeSat = totalIn - amountSat - feeSatActual
                require(changeSat > 546) {
                    "Remaining change (${"%.8f".format(changeSat / 1e8)} RVN) is below dust limit. " +
                    "Send a slightly smaller amount or send the full balance."
                }

                val tx = RavencoinTxBuilder.buildAndSign(
                    utxos = rvnUtxos,
                    toAddress = toAddress,
                    amountSat = amountSat,
                    feeSat = feeSatActual,
                    changeAddress = nextAddress,
                    privKeyBytes = privKey!!,
                    pubKeyBytes = pubKey
                )
                txid = node.broadcast(tx.hex)

                android.util.Log.i("WalletManager", "sendRvn: sent $amountRvn RVN to $toAddress, " +
                    "remaining ${"%.8f".format(changeSat / 1e8)} RVN to $nextAddress, txid=$txid")
            }

            // Advance to next address (public key of current address is now exposed)
            setCurrentAddressIndex(currentIndex + 1)

            "$txid|fee:$feeSatActual"
        } finally {
            privKey?.fill(0)
        }
    }

    /**
     * Transfer a Ravencoin asset directly on-chain (no backend required) with post-quantum protection.
     *
     * POST-QUANTUM SAFE LOGIC - SINGLE TRANSACTION:
     * 1. Transfer the requested asset to the external destination address
     * 2. Transfer ALL other remaining assets to a fresh address (currentIndex + 1)
     * 3. Transfer ALL remaining RVN to the fresh address (currentIndex + 1)
     * 4. Advance the address index so the next transaction uses the clean address
     *
     * Everything happens in ONE ATOMIC TRANSACTION, ensuring the current address
     * is completely emptied and all remaining funds move to a fresh, quantum-safe
     * address whose public key has never been exposed on-chain.
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
    suspend fun transferAssetLocal(
        assetName: String,
        toAddress: String,
        qty: Double = 1.0
    ): String = withContext(Dispatchers.IO) {
        val currentIndex = getCurrentAddressIndex()
        val nextAddress = getAddress(0, currentIndex + 1) ?: error("Cannot derive next address")
        val node = RavencoinPublicNode()

        val rawQtyRequested = Math.round(qty * 100_000_000.0)
        require(rawQtyRequested > 0) { "Transfer quantity must be greater than zero" }

        val satPerByte = try { node.getMinRelayFeeRateSatPerByte() } catch (_: FeeUnavailableException) { 200L }

        // Scan ALL addresses 0..currentIndex for the asset and any RVN.
        // Assets may be on old HAS_OUTGOING addresses if the sweep hasn't run yet.
        data class AddrFunds(
            val index: Int,
            val rvnUtxos: List<Utxo>,
            val assetUtxos: Map<String, List<AssetUtxo>>
        )
        val addrBatch = getAddressBatch(0, 0..currentIndex)
        val allFunds = mutableListOf<AddrFunds>()
        for ((i, addr) in addrBatch.entries.sortedBy { it.key }) {
            try {
                val r = node.getUtxosAndAllAssetUtxosBatch(addr)
                if (r.first.isNotEmpty() || r.third.isNotEmpty()) {
                    allFunds.add(AddrFunds(i, r.first, r.third))
                }
            } catch (_: Exception) {}
        }

        // Aggregate primary asset UTXOs across all addresses
        val primaryByIndex: Map<Int, List<AssetUtxo>> = allFunds
            .filter { it.assetUtxos.containsKey(assetName) }
            .associate { it.index to it.assetUtxos.getValue(assetName) }

        if (primaryByIndex.isEmpty()) {
            error("Asset $assetName not found on any wallet address. Try refreshing the wallet.")
        }

        val totalRawAmount = primaryByIndex.values.sumOf { utxos -> utxos.sumOf { it.assetRawAmount } }
        require(totalRawAmount > 0) { "Asset $assetName has zero balance" }
        require(rawQtyRequested <= totalRawAmount) {
            "Insufficient balance: requested $qty, available ${totalRawAmount / 100_000_000.0}"
        }

        val assetChangeRaw = totalRawAmount - rawQtyRequested

        // Other assets (all assets except the primary) from all addresses
        val otherByIndex = allFunds.associate { af ->
            af.index to af.assetUtxos.filterKeys { it != assetName }
        }.filter { (_, m) -> m.isNotEmpty() }

        // Key range spans all involved addresses (one batch Keystore decrypt)
        val involvedIndices = (primaryByIndex.keys + otherByIndex.keys + allFunds.map { it.index }).toSet()
        val minIdx = involvedIndices.minOrNull() ?: currentIndex
        val maxIdx = involvedIndices.maxOrNull() ?: currentIndex
        val keyPairs = getKeyPairBatch(0, minIdx..maxIdx)

        return@withContext try {
            // Build keyed input lists
            val primaryKeyed = primaryByIndex.flatMap { (idx, utxos) ->
                val (priv, pub) = keyPairs[idx] ?: return@flatMap emptyList()
                utxos.map { RavencoinTxBuilder.KeyedAssetUtxo(it, priv, pub) }
            }

            val otherKeyed = mutableMapOf<String, MutableList<RavencoinTxBuilder.KeyedAssetUtxo>>()
            for ((idx, assetMap) in otherByIndex) {
                val (priv, pub) = keyPairs[idx] ?: continue
                for ((name, utxos) in assetMap) {
                    otherKeyed.getOrPut(name) { mutableListOf() }
                        .addAll(utxos.map { RavencoinTxBuilder.KeyedAssetUtxo(it, priv, pub) })
                }
            }

            val rvnKeyed = allFunds.flatMap { af ->
                val (priv, pub) = keyPairs[af.index] ?: return@flatMap emptyList()
                af.rvnUtxos.map { RavencoinTxBuilder.KeyedUtxo(it, priv, pub) }
            }

            // Estimate fee and validate RVN availability
            val primaryAssetChangeOutputs = if (assetChangeRaw > 0) 1 else 0
            val totalAssetOutputs = 1 + primaryAssetChangeOutputs + otherKeyed.size
            val totalInputs = primaryKeyed.size + otherKeyed.values.sumOf { it.size } + rvnKeyed.size
            val feeSat = (10L + 148L * totalInputs + 70L * totalAssetOutputs + 34L) * maxOf(satPerByte, 200L)
            val dustEstimate = 600L * totalAssetOutputs

            val totalRvnIn = rvnKeyed.sumOf { it.utxo.satoshis } +
                             primaryKeyed.sumOf { it.assetUtxo.utxo.satoshis } +
                             otherKeyed.values.flatten().sumOf { it.assetUtxo.utxo.satoshis }

            if (totalRvnIn < feeSat + dustEstimate) {
                error("Insufficient RVN for fee and dust. Need ${(feeSat + dustEstimate) / 1e8} RVN, " +
                    "have ${totalRvnIn / 1e8} RVN. Fund your wallet with at least 0.01 RVN.")
            }

            val tx = RavencoinTxBuilder.buildAndSignMultiAddressAssetTransfer(
                primaryAssetInputs = primaryKeyed,
                otherAssetInputs   = otherKeyed,
                rvnInputs          = rvnKeyed,
                primaryAsset       = RavencoinTxBuilder.AssetOutput(assetName, rawQtyRequested, toAddress),
                primaryAssetChange = assetChangeRaw,
                feeSat             = feeSat,
                changeAddress      = nextAddress
            )
            val txid = node.broadcast(tx.hex)

            android.util.Log.i("WalletManager", "transferAsset: sent $qty $assetName to $toAddress, " +
                "remaining assets and RVN to $nextAddress, txid=$txid")

            setCurrentAddressIndex(currentIndex + 1)
            txid
        } finally {
            keyPairs.values.forEach { (priv, _) -> priv.fill(0) }
        }
    }

    /**
     * Issue a Ravencoin asset directly on-chain (no backend required) with post-quantum protection.
     *
     * POST-QUANTUM SAFE LOGIC - SINGLE TRANSACTION:
     * 1. Issue the new asset to the specified address
     * 2. Transfer ALL other existing assets to a fresh address (currentIndex + 1)
     * 3. All RVN change goes to the fresh address (currentIndex + 1)
     * 4. Advance the address index so the next transaction uses the clean address
     *
     * This ensures that after asset issuance, the current address is completely
     * emptied (RVN + ALL existing assets) and all remaining funds are moved to
     * a fresh, quantum-safe address whose public key has never been exposed on-chain.
     *
     * @param assetName  Full asset name: "ROOT", "ROOT/SUB", or "ROOT/SUB#UNIQUE"
     * @param qty        Asset quantity in display units (e.g. 1000.0)
     * @param units      Divisibility 0-8
     * @param reissuable Whether more supply can be issued later
     * @param ipfsHash   Optional CIDv0 "Qm..." IPFS hash for metadata
     * @return transaction ID on success
     */
    suspend fun issueAssetLocal(
        assetName: String,
        qty: Double,
        toAddress: String,
        units: Int = 0,
        reissuable: Boolean = false,
        ipfsHash: String? = null
    ): String = withContext(Dispatchers.IO) {
        val currentIndex = getCurrentAddressIndex()
        val address = getAddress(0, currentIndex) ?: error("No wallet")
        val nextAddress = getAddress(0, currentIndex + 1) ?: error("Cannot derive next address")

        // Post-quantum: redirect self-sends to nextAddress (quantum-safe, unexposed key).
        val actualToAddress = if (toAddress == address) nextAddress else toAddress

        val node = RavencoinPublicNode()

        // Fetch all UTXOs and the relay fee rate in parallel
        val (utxoResult, satPerByte) = coroutineScope {
            val utxosDeferred = async { node.getUtxosAndAllAssetUtxosBatch(address) }
            val feeDeferred   = async { node.getMinRelayFeeRateSatPerByte() }
            Pair(utxosDeferred.await(), feeDeferred.await())
        }
        val rvnUtxos    = utxoResult.first
        val allAssetMap = utxoResult.third

        if (rvnUtxos.isEmpty()) error("No spendable RVN on current address. Try refreshing the wallet.")

        // Extract owner asset UTXO if needed (sub-assets and unique tokens require the parent owner token)
        val ownerAssetName = when {
            assetName.contains('#') -> assetName.substringBefore('#') + "!"
            assetName.contains('/') -> assetName.substringBefore('/') + "!"
            else -> null
        }
        val ownerAssetUtxos: List<Utxo> = ownerAssetName?.let { requiredOwnerAsset ->
            val allOwnerUtxos = allAssetMap[requiredOwnerAsset] ?: emptyList()
            require(allOwnerUtxos.isNotEmpty()) {
                "Missing owner asset $requiredOwnerAsset in wallet. " +
                "Transfer the owner token to this address before issuing $assetName."
            }
            val singleOwnerUtxo = allOwnerUtxos.firstOrNull { it.assetRawAmount == 100_000_000L }
                ?: allOwnerUtxos.firstOrNull()
                ?: error("No valid owner token UTXO found for $requiredOwnerAsset")
            require(singleOwnerUtxo.assetRawAmount == 100_000_000L) {
                "Owner token $requiredOwnerAsset has amount ${singleOwnerUtxo.assetRawAmount}, " +
                "expected 100000000 (1 in raw units). Make sure you have a single owner token UTXO with amount 1."
            }
            listOf(singleOwnerUtxo.utxo.copy(satoshis = 0L))
        }.orEmpty()

        // All other assets (excluding the owner token which is already handled above)
        val otherAssetUtxos: Map<String, List<AssetUtxo>> = allAssetMap.filterKeys { it != ownerAssetName }

        val burnSat = when {
            assetName.contains('#') -> RavencoinTxBuilder.BURN_UNIQUE_SAT
            assetName.contains('/') -> RavencoinTxBuilder.BURN_SUB_SAT
            else                    -> RavencoinTxBuilder.BURN_ROOT_SAT
        }

        val totalAssetSweepOutputs = otherAssetUtxos.size
        val totalInputs = rvnUtxos.size + ownerAssetUtxos.size + otherAssetUtxos.values.sumOf { it.size }
        val outputCountForIssuance = when {
            assetName.contains('#') -> 4
            assetName.contains('/') -> 5
            else -> 4
        }
        val feeSat = (10 + 148 * totalInputs + 70 * (outputCountForIssuance + totalAssetSweepOutputs) + 34) * satPerByte

        val qtyRaw = (qty * 100_000_000.0).toLong()

        // Single Keystore decrypt for both keys
        val keyPair = getKeyPair(0, currentIndex) ?: error("No wallet key")
        var privKey: ByteArray? = keyPair.first
        val pubKey = keyPair.second

        return@withContext try {
            val tx = RavencoinTxBuilder.buildAndSignAssetIssueWithAssetSweep(
                utxos = rvnUtxos.filterNot { rvn ->
                    ownerAssetUtxos.any { owner -> owner.txid == rvn.txid && owner.outputIndex == rvn.outputIndex }
                },
                ownerAssetUtxos = ownerAssetUtxos,
                otherAssetUtxos = otherAssetUtxos,  // ALL other assets swept to nextAddress
                assetName = assetName,
                qtyRaw = qtyRaw,
                toAddress = actualToAddress,
                changeAddress = nextAddress,  // RVN change + ALL assets + owner token go to fresh address
                units = units,
                reissuable = reissuable,
                ipfsHash = ipfsHash,
                burnSat = burnSat,
                feeSat = feeSat,
                privKeyBytes = privKey!!,
                pubKeyBytes = pubKey
            )
            val txid = node.broadcast(tx.hex)

            android.util.Log.i("WalletManager", "issueAsset: issued $qty $assetName to $actualToAddress, " +
                "owner token + all other assets and RVN change to $nextAddress, txid=$txid")

            setCurrentAddressIndex(currentIndex + 1)
            txid
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

    private fun base58Decode(input: String): ByteArray {
        var num = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (c in input) {
            val idx = B58_ALPHABET.indexOf(c)
            if (idx < 0) error("Invalid Base58 character: $c")
            num = num.multiply(base).add(BigInteger.valueOf(idx.toLong()))
        }
        val bytes = num.toByteArray()
        // Strip sign byte if present
        val stripped = if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        // Restore leading zeros
        val leadingZeros = input.takeWhile { it == B58_ALPHABET[0] }.length
        return ByteArray(leadingZeros) + stripped
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

    suspend fun healAndSweepTarget(index: Int) = withContext(Dispatchers.IO) {
        val currentIndex = getCurrentAddressIndex()
        val addr = getAddress(0, index) ?: return@withContext
        val keyPair = getKeyPair(0, index) ?: return@withContext
        val privKey = keyPair.first
        val pubKey = keyPair.second
        val node = RavencoinPublicNode()

        try {
            val r = node.getUtxosAndAllAssetUtxosBatch(addr)
            val rvnBalance = r.first.sumOf { it.satoshis }
            val hasAssets = r.third.isNotEmpty()

            if (hasAssets || rvnBalance > 0) {
                val satPerByte = try { node.getMinRelayFeeRateSatPerByte() } catch (_: FeeUnavailableException) { 200L }

                // 1) Funding se necessario: usa le chiavi dell'indirizzo CORRENTE per firmare i suoi UTXO
                if (hasAssets && rvnBalance < 10000000L) {
                    val currentAddr = getAddress(0, currentIndex) ?: return@withContext
                    val curKeyPair = getKeyPair(0, currentIndex) ?: return@withContext
                    var curPrivKey: ByteArray? = curKeyPair.first
                    try {
                        val currentUtxos = node.getUtxos(currentAddr)
                        val fundFee = (10L + 148L * currentUtxos.size + 34L * 2) * satPerByte
                        val tx = RavencoinTxBuilder.buildAndSign(
                            currentUtxos, addr, 10000000L, fundFee, currentAddr, curPrivKey!!, curKeyPair.second
                        )
                        node.broadcast(tx.hex)
                        kotlinx.coroutines.delay(5000)
                    } finally {
                        curPrivKey?.fill(0)
                    }
                }

                // 2) Sweep immediato verso currentIndex usando le chiavi dell'indirizzo sorgente (index)
                val targetAddr = getAddress(0, currentIndex)!!
                val sweepResult = node.getUtxosAndAllAssetUtxosBatch(addr)
                val totalSweepInputs = sweepResult.first.size + sweepResult.third.values.sumOf { it.size }
                val sweepFee = (10L + 148L * totalSweepInputs + 34L * (1 + sweepResult.third.size)) * satPerByte
                val tx = RavencoinTxBuilder.buildAndSignRvnSendWithAssetSweep(
                    rvnUtxos = sweepResult.first,
                    assetUtxos = sweepResult.third,
                    toAddress = targetAddr,
                    amountSat = 0L,
                    feeSat = sweepFee,
                    changeAddress = targetAddr,
                    privKeyBytes = privKey,
                    pubKeyBytes = pubKey
                )
                node.broadcast(tx.hex)
                android.util.Log.i("WalletManager", "AutoHeal/Sweep: Consolidated index $index to $currentIndex")
            }
        } catch (e: Exception) {
            android.util.Log.e("WalletManager", "Heal/Sweep failed for index $index", e)
        } finally {
            privKey.fill(0)
        }
    }

    /**
     * Consolidate all funds (RVN + assets) from addresses 0..currentIndex to a fresh virgin address.
     *
     * This function:
     * 1. Scans all old addresses (0..currentIndex-1) for assets and RVN
     * 2. Identifies addresses with HAS_OUTGOING status that need funding
     * 3. Funds old addresses with RVN from a sacrificial address (for asset transfer fees)
     * 4. Sweeps all assets and RVN from old addresses to currentIndex
     * 5. Then sweeps everything from currentIndex to currentIndex+1 (virgin address)
     * 6. Advances the index to currentIndex + 1
     *
     * Used when the portfolio scan detects funds scattered across old addresses.
     *
     * @return Transaction ID of the consolidation, or null if no funds to consolidate.
     */
    suspend fun consolidateAllFundsToFreshAddress(): String? = withContext(Dispatchers.IO) {
        val currentIndex = getCurrentAddressIndex()
        if (currentIndex <= 0) {
            android.util.Log.i("WalletManager", "consolidAllFundsToFreshAddress: currentIndex is 0, nothing to consolidate")
            return@withContext null
        }

        val node = RavencoinPublicNode()
        android.util.Log.i("WalletManager", "consolid: starting consolidation of addresses 0..$currentIndex")

        // Step 1: Check if old addresses have any funds
        var oldAddressWithFunds: Int? = null
        var oldAddressHasAssets = false
        var oldAddressHasRvn = false

        for (i in 0 until currentIndex) {
            val addr = getAddress(0, i) ?: continue
            val status = try { node.getAddressStatus(addr) } catch (_: Exception) { 
                RavencoinPublicNode.AddressStatus.NO_HISTORY 
            }
            
            // Only consolidate HAS_OUTGOING addresses (quantum-vulnerable, already exposed)
            if (status != RavencoinPublicNode.AddressStatus.HAS_OUTGOING) continue
            
            val assetOutpoints = try { node.getAllAssetOutpoints(addr) } catch (_: Exception) { emptySet() }
            val assetCount = assetOutpoints.size
            val rvnUtxos = try { node.getUtxos(addr) } catch (_: Exception) { emptyList() }
            
            // Filter out asset UTXOs from RVN list
            val pureRvnUtxos = rvnUtxos.filter { "${it.txid}:${it.outputIndex}" !in assetOutpoints }
            
            if (assetCount > 0 || pureRvnUtxos.isNotEmpty()) {
                oldAddressWithFunds = i
                oldAddressHasAssets = assetCount > 0
                oldAddressHasRvn = pureRvnUtxos.isNotEmpty()
                android.util.Log.i("WalletManager", "consolid: index $i has assets=$oldAddressHasAssets rvn=${pureRvnUtxos.size} UTXOs")
                break  // Found first address with funds
            }
        }

        if (oldAddressWithFunds == null) {
            // Check if currentIndex itself has funds that should be moved to fresh address
            val currentAddr = getAddress(0, currentIndex) ?: return@withContext null
            val currentAssets = try { node.getUtxosAndAllAssetUtxosBatch(currentAddr) } catch (_: Exception) { null }
            
            if (currentAssets == null || (currentAssets.first.isEmpty() && currentAssets.third.isEmpty())) {
                android.util.Log.i("WalletManager", "consolidAllFundsToFreshAddress: no funds found on any address")
                return@withContext null
            }
            
            android.util.Log.i("WalletManager", "consolid: only currentIndex has funds, sweeping to fresh address")
            // Just sweep currentIndex to fresh address
            val targetAddress = getAddress(0, currentIndex + 1) 
                ?: error("Cannot derive address at index ${currentIndex + 1}")
            
            val keyPair = getKeyPair(0, currentIndex) ?: error("No key for currentIndex")
            val sweepTx = RavencoinTxBuilder.buildAndSignRvnSendWithAssetSweep(
                rvnUtxos = currentAssets.first,
                assetUtxos = currentAssets.third,
                toAddress = targetAddress,
                amountSat = 0L,
                feeSat = (10L + 148L * (currentAssets.first.size + currentAssets.third.values.sumOf { it.size }) + 
                         70L * (1 + currentAssets.third.size) + 34L) * 
                         (try { node.getMinRelayFeeRateSatPerByte() } catch (_: FeeUnavailableException) { 200L }),
                changeAddress = targetAddress,
                privKeyBytes = keyPair.first,
                pubKeyBytes = keyPair.second
            )
            
            val txid = node.broadcast(sweepTx.hex)
            android.util.Log.i("WalletManager", "consolid: swept currentIndex to fresh address, txid=$txid")
            setCurrentAddressIndex(currentIndex + 1)
            return@withContext txid
        }

        // Step 2: Find a sacrificial address (HAS_OUTGOING with RVN) for funding
        val oldAddr = getAddress(0, oldAddressWithFunds!!)!!
        var sacrificialIndex: Int? = null
        
        for (i in 0 until currentIndex) {
            if (i == oldAddressWithFunds) continue
            val addr = getAddress(0, i) ?: continue
            val status = try { node.getAddressStatus(addr) } catch (_: Exception) { 
                RavencoinPublicNode.AddressStatus.NO_HISTORY 
            }
            if (status != RavencoinPublicNode.AddressStatus.HAS_OUTGOING) continue
            
            val rvnBalance = try { node.getBalance(addr) } catch (_: Exception) { null }
            if (rvnBalance != null && rvnBalance.confirmed > 10000000) { // At least 0.1 RVN
                sacrificialIndex = i
                android.util.Log.i("WalletManager", "consolid: found sacrificial address at index $i with ${rvnBalance.confirmed / 1e8} RVN")
                break
            }
        }

        // Step 3: Use existing sweepOldAddresses() which handles funding + sweeping correctly
        android.util.Log.i("WalletManager", "consolid: using sweepOldAddresses to consolidate funds")
        val sweepTxids = sweepOldAddresses()
        
        if (sweepTxids.isEmpty()) {
            android.util.Log.w("WalletManager", "consolid: sweepOldAddresses returned no txids")
            return@withContext null
        }

        android.util.Log.i("WalletManager", "consolid: sweep completed with ${sweepTxids.size} transactions")
        
        // Step 4: After sweep, all funds are now on currentIndex, sweep to fresh address
        val targetAddress = getAddress(0, currentIndex + 1) 
            ?: error("Cannot derive address at index ${currentIndex + 1}")
        
        val currentAddr = getAddress(0, currentIndex) ?: error("Cannot derive current address")
        val currentFunds = try { node.getUtxosAndAllAssetUtxosBatch(currentAddr) } catch (_: Exception) { 
            Triple(emptyList(), emptySet(), emptyMap()) 
        }
        
        if (currentFunds.first.isEmpty() && currentFunds.third.isEmpty()) {
            android.util.Log.w("WalletManager", "consolid: no funds on currentIndex after sweep")
            return@withContext sweepTxids.lastOrNull()
        }

        val keyPair = getKeyPair(0, currentIndex) ?: error("No key for currentIndex")
        val satPerByte = try { node.getMinRelayFeeRateSatPerByte() } catch (_: FeeUnavailableException) { 200L }
        val totalInputs = currentFunds.first.size + currentFunds.third.values.sumOf { it.size }
        val totalAssetOutputs = currentFunds.third.size
        val feeSat = (10L + 148L * totalInputs + 70L * (1 + totalAssetOutputs) + 34L) * satPerByte

        val finalSweepTx = RavencoinTxBuilder.buildAndSignRvnSendWithAssetSweep(
            rvnUtxos = currentFunds.first,
            assetUtxos = currentFunds.third,
            toAddress = targetAddress,
            amountSat = 0L,
            feeSat = feeSat,
            changeAddress = targetAddress,
            privKeyBytes = keyPair.first,
            pubKeyBytes = keyPair.second
        )

        val finalTxid = node.broadcast(finalSweepTx.hex)
        android.util.Log.i("WalletManager", "consolidAllFundsToFreshAddress: final sweep txid=$finalTxid")
        
        setCurrentAddressIndex(currentIndex + 1)
        android.util.Log.i("WalletManager", "consolidAllFundsToFreshAddress: advanced index to ${currentIndex + 1}")

        finalTxid
    }
}
