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
import io.raventag.app.ravencoin.OwnedAsset
import io.raventag.app.wallet.cache.ReservedUtxoDao
import io.raventag.app.wallet.cache.PendingConsolidationDao
import io.raventag.app.worker.RebroadcastWorker

class WalletManager(private val context: Context) {

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
        // D-15 mnemonic-safety additions (plan 30-06)
        private const val KEY_SEED_HMAC = "seed_hmac"
        private const val KEY_MNEMONIC_HMAC = "mnemonic_hmac"
        private const val KEY_HMAC_MATERIAL_CT = "hmac_material_ct"
        private const val KEY_HMAC_MATERIAL_IV = "hmac_material_iv"
        private const val KEY_BACKUP_COMPLETED = "backup_completed"
        private val VALID_WORD_COUNTS = setOf(12, 15, 18, 21, 24)
        private const val COIN_TYPE = 175
        private val RVN_ADDRESS_VERSION = byteArrayOf(0x3C.toByte())
        private val B58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

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
        // Plan 30-06: mnemonic safety helpers.

        /**
         * D-15 + Pitfall 7: normalize whitespace and validate BIP39 word count + checksum.
         * Accepts arbitrary whitespace via `input.trim().split(Regex("\\s+"))`.
         * @throws IllegalArgumentException if the word count is not in {12,15,18,21,24}
         *         or the BIP39 checksum fails.
         */
        @JvmStatic
        fun validateMnemonic(input: String): List<String> {
            val words = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            require(words.size in VALID_WORD_COUNTS) {
                "invalid word count: ${words.size}"
            }
            require(bip39ChecksumValidCompanion(words)) { "BIP39 checksum failed" }
            return words
        }

        /**
         * Pure BIP39 checksum validator, operating on an already-normalized word list.
         * Supports 12/15/18/21/24 word counts per BIP39.
         */
        internal fun bip39ChecksumValidCompanion(words: List<String>): Boolean {
            val n = words.size
            if (n !in VALID_WORD_COUNTS) return false
            val totalBits = n * 11
            val checksumBits = totalBits / 33
            val entropyBits = totalBits - checksumBits
            val entropyBytes = entropyBits / 8

            val indices = IntArray(n)
            for (i in 0 until n) {
                val idx = WORD_LIST.indexOf(words[i])
                if (idx < 0) return false
                indices[i] = idx
            }

            val allBits = IntArray(totalBits)
            var pos = 0
            for (idx in indices) {
                for (b in 10 downTo 0) {
                    allBits[pos++] = (idx shr b) and 1
                }
            }

            val entropy = ByteArray(entropyBytes)
            for (i in 0 until entropyBits) {
                entropy[i / 8] = (entropy[i / 8].toInt() or (allBits[i] shl (7 - i % 8))).toByte()
            }

            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(entropy)
            for (i in 0 until checksumBits) {
                val expected = (hash[i / 8].toInt() shr (7 - i % 8)) and 1
                if (allBits[entropyBits + i] != expected) return false
            }
            return true
        }

        /**
         * D-14: block restore-over-wallet when the current wallet has funds
         * and the user has not confirmed the recovery phrase backup.
         */
        @JvmStatic
        fun checkRestorePreconditions(currentBalanceSat: Long, hasBackedUp: Boolean) {
            if (currentBalanceSat > 0L && !hasBackedUp) {
                throw BackupRequiredException(
                    "Current wallet has $currentBalanceSat sat and has not been backed up"
                )
            }
        }

        /**
         * Test-only / deterministic HMAC-SHA256 over a seed with caller-supplied key bytes.
         * The production HMAC flow (instance method `computeSeedHmac`) loads the key from
         * the Keystore-wrapped material stored in SharedPreferences and delegates here.
         */
        @JvmStatic
        fun computeSeedHmacForTest(seed: ByteArray, keyBytes: ByteArray): ByteArray {
            val mac = org.bouncycastle.crypto.macs.HMac(
                org.bouncycastle.crypto.digests.SHA256Digest()
            )
            mac.init(org.bouncycastle.crypto.params.KeyParameter(keyBytes))
            mac.update(seed, 0, seed.size)
            val out = ByteArray(mac.macSize)
            mac.doFinal(out, 0)
            return out
        }

        /**
         * D-15 / A9: constant-time HMAC verification. On mismatch throws
         * [IntegrityException] (stored seed/mnemonic tampered or wrong key).
         */
        @JvmStatic
        fun verifySeedHmac(seed: ByteArray, tag: ByteArray, keyBytes: ByteArray) {
            val expected = computeSeedHmacForTest(seed, keyBytes)
            val ok = java.security.MessageDigest.isEqual(expected, tag)
            java.util.Arrays.fill(expected, 0)
            if (!ok) throw IntegrityException("seed HMAC mismatch")
        }

        /**
         * Pitfall 3: convert the opaque Keystore "key invalidated" signal into a
         * typed exception the UI can route to the restore flow. All other
         * exceptions pass through unchanged.
         */
        @JvmStatic
        inline fun <T> wrapKeystoreException(block: () -> T): T {
            return try {
                block()
            } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                throw KeystoreInvalidatedException(cause = e)
            }
        }
    }

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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setUnlockedDeviceRequired(true)
                    }
                    if (strongBox) setIsStrongBoxBacked(true)
                }
                .build()

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")

        val key = try {
            keyGen.init(buildSpec(strongBox = true))
            keyGen.generateKey().also {
                android.util.Log.i("WalletManager", "Key stored in StrongBox (hardware enclave)")
            }
        } catch (_: Throwable) {
            keyGen.init(buildSpec(strongBox = false))
            keyGen.generateKey().also {
                android.util.Log.i("WalletManager", "Key stored in Android Keystore (TEE/software)")
            }
        }
        return key
    }

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

    private fun encrypt(data: ByteArray): Pair<ByteArray, ByteArray> = wrapKeystoreException {
        val key = getOrCreateAndroidKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.doFinal(data) to cipher.iv
    }

    private fun decrypt(enc: ByteArray, iv: ByteArray): ByteArray = wrapKeystoreException {
        val key = getOrCreateAndroidKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.doFinal(enc)
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasWallet(): Boolean = prefs().contains(KEY_SEED_ENC)

    fun generateWallet(): String {
        val entropy = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val mnemonic = entropyToMnemonic(entropy)
        val seed = mnemonicToSeed(mnemonic, "")
        storeSeed(seed, mnemonic)
        return mnemonic
    }

    fun generateMnemonic(): String {
        val entropy = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return entropyToMnemonic(entropy)
    }

    fun finalizeWallet(mnemonic: String) {
        val seed = mnemonicToSeed(mnemonic, "")
        storeSeed(seed, mnemonic)
        cachedAddress = null
    }

    fun deleteWallet() {
        cachedAddress = null
        // Wipe ALL wallet-related prefs so a fresh restore does not inherit
        // stale state (backup gate, integrity tags, HMAC material, address index).
        prefs().edit()
            .remove(KEY_SEED_ENC).remove(KEY_SEED_IV)
            .remove(KEY_MNEMONIC_ENC).remove(KEY_MNEMONIC_IV)
            .remove(KEY_ADDRESS_INDEX)
            .remove(KEY_BACKUP_COMPLETED)
            .remove(KEY_HMAC_MATERIAL_CT).remove(KEY_HMAC_MATERIAL_IV)
            .remove(KEY_SEED_HMAC).remove(KEY_MNEMONIC_HMAC)
            .apply()
        // Wipe cached balance / utxos / tx history so restore preconditions
        // (D-14 forced-backup gate) do not flag a wallet that no longer exists.
        try { io.raventag.app.wallet.cache.WalletCacheDao.clearAll() } catch (_: Throwable) {}
        try { io.raventag.app.wallet.cache.TxHistoryDao.clearAll() } catch (_: Throwable) {}
        try { io.raventag.app.wallet.cache.ReservedUtxoDao.clearAll() } catch (_: Throwable) {}
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)
        } catch (_: Exception) {}
    }

    fun getCurrentAddressIndex(): Int = prefs().getInt(KEY_ADDRESS_INDEX, 0)

    private fun setCurrentAddressIndex(index: Int) {
        prefs().edit().putInt(KEY_ADDRESS_INDEX, index).apply()
        cachedAddress = null
    }

    fun getCurrentAddress(): String? = getAddress(0, getCurrentAddressIndex())

    fun getNextAddress(): String? = getAddress(0, getCurrentAddressIndex() + 1)

    fun reconcileCurrentAddressIndex(): Int = getCurrentAddressIndex()

    fun ensureCurrentAddressClean() {}

    suspend fun discoverCurrentIndex(): Int = withContext(Dispatchers.IO) {
        val node = RavencoinPublicNode(context)
        val currentStoredIndex = getCurrentAddressIndex()
        val searchLimit = maxOf(currentStoredIndex + 50, 100)

        android.util.Log.i("WalletManager", "discoverCurrentIndex: Scanning 0..$searchLimit for RVN and assets")

        val batchMap = getAddressBatch(0, 0 until searchLimit)
        if (batchMap.isEmpty()) return@withContext currentStoredIndex

        // Phase 1: Find last address with any history (existing approach)
        val addrList = batchMap.values.toList()
        val statusMap = try {
            node.getAddressStatusBatch(addrList)
        } catch (e: Exception) {
            android.util.Log.e("WalletManager", "discoverCurrentIndex: batch status check failed", e)
            emptyMap()
        }

        var lastUsed = -1
        for (i in 0 until searchLimit) {
            val addr = batchMap[i] ?: continue
            val status = statusMap[addr] ?: RavencoinPublicNode.AddressStatus.NO_HISTORY
            if (status != RavencoinPublicNode.AddressStatus.NO_HISTORY) {
                lastUsed = i
            }
        }

        // Phase 2: Find the highest address that currently holds funds (RVN or assets).
        // Single batch call (get_balance?asset=true) replaces N*2 sequential TLS calls.
        var lastWithFunds = -1
        val addressesWithHistory = (0 until searchLimit).mapNotNull { i ->
            val addr = batchMap[i] ?: return@mapNotNull null
            val status = statusMap[addr] ?: RavencoinPublicNode.AddressStatus.NO_HISTORY
            if (status != RavencoinPublicNode.AddressStatus.NO_HISTORY) i to addr else null
        }
        if (addressesWithHistory.isNotEmpty()) {
            val historyAddrList = addressesWithHistory.map { it.second }
            val withFunds = try {
                node.getAddressesWithFunds(historyAddrList)
            } catch (_: Exception) { emptySet() }
            for ((i, addr) in addressesWithHistory) {
                if (addr in withFunds) {
                    lastWithFunds = maxOf(lastWithFunds, i)
                    android.util.Log.i("WalletManager", "discoverCurrentIndex: index $i has funds")
                }
            }
        }

        // Determine current index:
        // - If funds exist: stay at that address unless its key is already exposed
        //   (HAS_OUTGOING means a signed tx revealed the public key, so move to next).
        // - If no funds anywhere: next address after the last one with any history.
        // - Empty wallet: index 0.
        val finalResult = maxOf(
            when {
                lastWithFunds >= 0 -> {
                    val fundsAddr = batchMap[lastWithFunds]
                    val fundsStatus = fundsAddr?.let { statusMap[it] }
                        ?: RavencoinPublicNode.AddressStatus.NO_HISTORY
                    if (fundsStatus == RavencoinPublicNode.AddressStatus.HAS_OUTGOING) {
                        android.util.Log.i("WalletManager", "discoverCurrentIndex: index $lastWithFunds key exposed, using ${lastWithFunds + 1}")
                        lastWithFunds + 1
                    } else {
                        android.util.Log.i("WalletManager", "discoverCurrentIndex: index $lastWithFunds has funds, key safe, staying there")
                        lastWithFunds
                    }
                }
                lastUsed >= 0 -> lastUsed + 1
                else -> 0
            },
            currentStoredIndex
        )
        setCurrentAddressIndex(finalResult)
        android.util.Log.i("WalletManager", "Discover: current index = $finalResult (lastUsed=$lastUsed, lastWithFunds=$lastWithFunds)")
        finalResult
    }

    /**
     * Lightweight index sync for refresh: checks whether the stored currentIndex is stale
     * (e.g. another app flavor sent a tx and advanced the index) without running a full
     * address discovery scan.
     *
     * Algorithm (3 batch network calls max):
     * 1. Check status of currentIndex address. If not HAS_OUTGOING, index is fine.
     * 2. Scan forward up to 10 addresses for status in one batch.
     * 3. Find the highest funded address forward; advance currentIndex accordingly.
     *
     * @return true if currentIndex was updated, false if already correct.
     */
    suspend fun syncCurrentIndex(): Boolean = withContext(Dispatchers.IO) {
        val node = RavencoinPublicNode(context)
        val storedIndex = getCurrentAddressIndex()
        val currentAddr = getAddress(0, storedIndex) ?: return@withContext false

        // Step 1: one call to check if current address key is exposed
        val currentStatus = try {
            node.getAddressStatusBatch(listOf(currentAddr))[currentAddr]
        } catch (_: Exception) { return@withContext false }

        if (currentStatus != RavencoinPublicNode.AddressStatus.HAS_OUTGOING) {
            android.util.Log.i("WalletManager", "syncCurrentIndex: index $storedIndex is current (status=$currentStatus)")
            return@withContext false
        }

        // Step 2: scan forward up to 10 addresses for status
        val forwardRange = (storedIndex + 1)..(storedIndex + 10)
        val forwardAddrs = getAddressBatch(0, forwardRange)
        val forwardList = forwardRange.mapNotNull { i -> forwardAddrs[i]?.let { i to it } }

        val fwdStatusMap = try {
            node.getAddressStatusBatch(forwardList.map { it.second })
        } catch (_: Exception) { emptyMap<String, RavencoinPublicNode.AddressStatus>() }

        val withHistory = forwardList.filter { (_, addr) ->
            fwdStatusMap[addr] != RavencoinPublicNode.AddressStatus.NO_HISTORY
        }

        if (withHistory.isEmpty()) {
            // No history forward: storedIndex+1 is the fresh address
            val newIndex = storedIndex + 1
            setCurrentAddressIndex(newIndex)
            android.util.Log.i("WalletManager", "syncCurrentIndex: no history forward, advanced to $newIndex")
            return@withContext true
        }

        // Step 3: check which of those addresses still hold funds
        val withFunds = try {
            node.getAddressesWithFunds(withHistory.map { it.second })
        } catch (_: Exception) { emptySet<String>() }

        val lastFunded = withHistory.filter { (_, addr) -> addr in withFunds }.maxByOrNull { it.first }

        val newIndex = when {
            lastFunded != null -> {
                val st = fwdStatusMap[lastFunded.second] ?: RavencoinPublicNode.AddressStatus.NO_HISTORY
                if (st == RavencoinPublicNode.AddressStatus.HAS_OUTGOING) lastFunded.first + 1
                else lastFunded.first
            }
            else -> withHistory.maxOf { it.first } + 1
        }

        if (newIndex > storedIndex) {
            setCurrentAddressIndex(newIndex)
            android.util.Log.i("WalletManager", "syncCurrentIndex: advanced $storedIndex -> $newIndex")
            return@withContext true
        }
        false
    }

    suspend fun sweepOldAddresses(): List<String> {
        if (sweepRunning) return emptyList()
        sweepRunning = true
        try {
            return sweepOldAddressesInternal()
        } finally {
            sweepRunning = false
        }
    }

    private data class FundingResult(val txid: String, val fundUtxo: Utxo)

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

        val satPerByte = try { node.getMinRelayFeeRateSatPerByte() } catch (_: FeeUnavailableException) { 200L }
        val perAssetFee = 300L * satPerByte
        val fundAmountSat = perAssetFee * assetCount + 200L * satPerByte

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
                changeAddress = sacrificialAddress,
                privKeyBytes = privKey,
                pubKeyBytes = pubKey
            )
            val txid = node.broadcast(tx.hex)
            android.util.Log.i("WalletManager", "Sweep: funded $oldAddress with ${fundAmountSat / 1e8} RVN from sacrificial $sacrificialIndex: $txid")

            val scriptHex = addressToP2pkhScript(oldAddress)
            val fundUtxo = Utxo(
                txid = txid,
                outputIndex = 0,
                satoshis = fundAmountSat,
                script = scriptHex,
                height = 0
            )

            return FundingResult(txid, fundUtxo)
        } finally {
            privKey?.fill(0)
        }
    }

    private fun addressToP2pkhScript(address: String): String {
        val decoded = base58Decode(address)
        val hash160 = decoded.copyOfRange(1, 21)
        return "76a914" + hash160.joinToString("") { "%02x".format(it) } + "88ac"
    }

    private suspend fun sweepOldAddressesInternal(): List<String> {
        val currentIndex = getCurrentAddressIndex()
        if (currentIndex == 0) return emptyList()

        val node = RavencoinPublicNode(context)

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

        val targetAddress = getAddress(0, currentIndex) ?: return emptyList()
        android.util.Log.i("WalletManager", "Sweep: consolidating ${targets.size} address(es) to index $currentIndex")

        val txids = mutableListOf<String>()

        val sacrificialIndex = targets.firstOrNull { it.hasRvn && !it.hasAssets }?.index
            ?: targets.firstOrNull { it.hasRvn }?.index
        val needsFunding = targets.filter { it.hasAssets && !it.hasRvn }
        for (t in needsFunding) {
            val assetCount = try { node.getAssetBalances(t.address).size } catch (_: Exception) { 1 }
            val result = fundOldAddressForSweep(node, sacrificialIndex, t.address, assetCount)
            if (result != null) txids.add(result.txid)
        }

        // Wait for funding transactions to appear in mempool before sweeping
        if (needsFunding.isNotEmpty() && txids.isNotEmpty()) {
            var waited = 0
            val maxWaitSec = 60
            while (waited < maxWaitSec) {
                var allVisible = true
                for (t in needsFunding) {
                    val utxos = try { node.getUtxos(t.address) } catch (_: Exception) { emptyList() }
                    if (utxos.isEmpty()) { allVisible = false; break }
                }
                if (allVisible) break
                kotlinx.coroutines.delay(3000)
                waited += 3
            }
            android.util.Log.i("WalletManager", "Sweep: funding txs visible after ${waited}s, proceeding with sweep")
        }

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

    private fun fundAddressesForSweep(
        node: RavencoinPublicNode,
        addressesToFund: List<Pair<Int, String>>
    ): List<String> {
        if (addressesToFund.isEmpty()) return emptyList()

        val currentIndex = getCurrentAddressIndex()
        val currentAddress = getAddress(0, currentIndex) ?: return emptyList()

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

            val perAssetFee = 300L * satPerByte
            val fundAmountSat = perAssetFee * assetBalances.size + 500L * satPerByte

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
     * Restore-over-wallet entry point. D-14 forces a backup gate when the current
     * wallet has funds and the user has not confirmed their recovery phrase.
     *
     * Throws:
     * - [BackupRequiredException] if the forced-backup gate fires.
     * - [IllegalArgumentException] if the phrase fails BIP39 validation.
     * - [KeystoreInvalidatedException] if the Keystore AES key is invalidated.
     *
     * Returns true on successful restore, false only on unexpected I/O failure.
     */
    fun restoreWallet(mnemonic: String): Boolean {
        // Validation + forced-backup gate run BEFORE any Keystore rewrite; their
        // exceptions propagate to the UI so the restore dialog can react.
        val normalizedWords = validateMnemonic(mnemonic)
        val hasBackedUp = prefs().getBoolean(KEY_BACKUP_COMPLETED, false)
        val currentBalanceSat = runCatching {
            io.raventag.app.wallet.cache.WalletCacheDao.readState()?.balanceSat ?: 0L
        }.getOrDefault(0L)
        checkRestorePreconditions(currentBalanceSat, hasBackedUp)

        val normalized = normalizedWords.joinToString(" ")
        return try {
            val seed = mnemonicToSeed(normalized, "")
            storeSeed(seed, normalized)
            cachedAddress = null
            prefs().edit().putInt(KEY_ADDRESS_INDEX, 0).apply()
            // A restore sets a fresh wallet: clear backup gate for the new phrase.
            prefs().edit().putBoolean(KEY_BACKUP_COMPLETED, false).apply()
            true
        } catch (e: KeystoreInvalidatedException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // D-15 HMAC key material (32 random bytes) encrypted under the existing
    // Keystore AES key. We bridge to a raw BouncyCastle HMAC key because a
    // Keystore-bound AES key cannot be extracted as `Mac` key material.
    private fun loadOrCreateHmacKeyBytes(): ByteArray {
        val p = prefs()
        val existingCt = p.getString(KEY_HMAC_MATERIAL_CT, null)
        val existingIv = p.getString(KEY_HMAC_MATERIAL_IV, null)
        if (existingCt != null && existingIv != null) {
            try {
                val ct = android.util.Base64.decode(existingCt, android.util.Base64.NO_WRAP)
                val iv = android.util.Base64.decode(existingIv, android.util.Base64.NO_WRAP)
                return decrypt(ct, iv)
            } catch (_: javax.crypto.AEADBadTagException) {
                // Stale blob (e.g., overwriting an existing wallet with a fresh mnemonic).
                // Old HMAC tags cannot be verified under rotated key material; wipe and rebuild.
                p.edit()
                    .remove(KEY_HMAC_MATERIAL_CT)
                    .remove(KEY_HMAC_MATERIAL_IV)
                    .remove(KEY_SEED_HMAC)
                    .remove(KEY_MNEMONIC_HMAC)
                    .apply()
            }
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val (ct, iv) = encrypt(fresh)
        p.edit()
            .putString(KEY_HMAC_MATERIAL_CT, android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP))
            .putString(KEY_HMAC_MATERIAL_IV, android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            .apply()
        return fresh
    }

    private fun computeSeedHmac(seed: ByteArray): ByteArray {
        val keyBytes = loadOrCreateHmacKeyBytes()
        return try {
            computeSeedHmacForTest(seed, keyBytes)
        } finally {
            java.util.Arrays.fill(keyBytes, 0)
        }
    }

    private fun verifySeedHmacInstance(seed: ByteArray, tag: ByteArray) {
        val keyBytes = loadOrCreateHmacKeyBytes()
        try {
            verifySeedHmac(seed, tag, keyBytes)
        } finally {
            java.util.Arrays.fill(keyBytes, 0)
        }
    }

    private fun storeSeed(seed: ByteArray, mnemonic: String) {
        val (seedEnc, seedIv) = encrypt(seed)
        val mnemonicBytes = mnemonic.toByteArray(Charsets.UTF_8)
        val (mnemonicEnc, mnemonicIv) = encrypt(mnemonicBytes)
        val seedHmac = computeSeedHmac(seed)
        val mnemonicHmac = computeSeedHmac(mnemonicBytes)
        prefs().edit()
            .putString(KEY_SEED_ENC, android.util.Base64.encodeToString(seedEnc, android.util.Base64.DEFAULT))
            .putString(KEY_SEED_IV, android.util.Base64.encodeToString(seedIv, android.util.Base64.DEFAULT))
            .putString(KEY_MNEMONIC_ENC, android.util.Base64.encodeToString(mnemonicEnc, android.util.Base64.DEFAULT))
            .putString(KEY_MNEMONIC_IV, android.util.Base64.encodeToString(mnemonicIv, android.util.Base64.DEFAULT))
            .putString(KEY_SEED_HMAC, android.util.Base64.encodeToString(seedHmac, android.util.Base64.NO_WRAP))
            .putString(KEY_MNEMONIC_HMAC, android.util.Base64.encodeToString(mnemonicHmac, android.util.Base64.NO_WRAP))
            .apply()
        java.util.Arrays.fill(seedHmac, 0)
        java.util.Arrays.fill(mnemonicHmac, 0)
        java.util.Arrays.fill(mnemonicBytes, 0)
    }

    fun getMnemonic(): String? {
        return try {
            val encStr = prefs().getString(KEY_MNEMONIC_ENC, null) ?: return null
            val ivStr = prefs().getString(KEY_MNEMONIC_IV, null) ?: return null
            val enc = android.util.Base64.decode(encStr, android.util.Base64.DEFAULT)
            val iv = android.util.Base64.decode(ivStr, android.util.Base64.DEFAULT)
            val plaintext = decrypt(enc, iv)
            val tagB64 = prefs().getString(KEY_MNEMONIC_HMAC, null)
            if (tagB64 != null) {
                val tag = android.util.Base64.decode(tagB64, android.util.Base64.NO_WRAP)
                verifySeedHmacInstance(plaintext, tag)
            }
            String(plaintext, Charsets.UTF_8)
        } catch (e: KeystoreInvalidatedException) {
            throw e
        } catch (e: IntegrityException) {
            throw e
        } catch (e: Exception) { null }
    }

    private fun getSeed(): ByteArray? {
        return try {
            val encStr = prefs().getString(KEY_SEED_ENC, null) ?: return null
            val ivStr = prefs().getString(KEY_SEED_IV, null) ?: return null
            val enc = android.util.Base64.decode(encStr, android.util.Base64.DEFAULT)
            val iv = android.util.Base64.decode(ivStr, android.util.Base64.DEFAULT)
            val plaintext = decrypt(enc, iv)
            val tagB64 = prefs().getString(KEY_SEED_HMAC, null)
            if (tagB64 != null) {
                val tag = android.util.Base64.decode(tagB64, android.util.Base64.NO_WRAP)
                verifySeedHmacInstance(plaintext, tag)
            }
            plaintext
        } catch (e: KeystoreInvalidatedException) {
            throw e
        } catch (e: IntegrityException) {
            throw e
        } catch (e: Exception) { null }
    }

    /**
     * D-15 + D-16: reveal the stored mnemonic as a CharArray, gated by a
     * BiometricPrompt authentication bound to the Keystore decrypt operation.
     *
     * Caller is responsible for zero-filling the returned CharArray after display.
     */
    suspend fun revealMnemonicCharsWithBiometric(
        gate: io.raventag.app.security.BiometricGate
    ): CharArray = withContext(Dispatchers.IO) {
        val p = prefs()
        val ctB64 = p.getString(KEY_MNEMONIC_ENC, null)
            ?: throw IllegalStateException("no mnemonic stored")
        val ivB64 = p.getString(KEY_MNEMONIC_IV, null)
            ?: throw IllegalStateException("no mnemonic iv stored")
        val ct = android.util.Base64.decode(ctB64, android.util.Base64.DEFAULT)
        val iv = android.util.Base64.decode(ivB64, android.util.Base64.DEFAULT)
        val cipher = wrapKeystoreException {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateAndroidKey(),
                    GCMParameterSpec(128, iv)
                )
            }
        }
        val plaintext = gate.decryptWithBiometric(
            cipher,
            ct,
            io.raventag.app.R.string.biometricRevealTitle,
            io.raventag.app.R.string.biometricRevealSubtitle
        )
        try {
            val tagB64 = p.getString(KEY_MNEMONIC_HMAC, null)
            if (tagB64 != null) {
                val tag = android.util.Base64.decode(tagB64, android.util.Base64.NO_WRAP)
                verifySeedHmacInstance(plaintext, tag)
            }
            String(plaintext, Charsets.UTF_8).toCharArray()
        } finally {
            java.util.Arrays.fill(plaintext, 0)
        }
    }

    fun getAddress(accountIndex: Int = 0, addressIndex: Int = 0): String? {
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

    fun getPrivateKeyBytes(accountIndex: Int = 0, addressIndex: Int = 0): ByteArray? {
        val seed = getSeed() ?: return null
        val privKey = derivePrivateKey(seed, accountIndex, addressIndex)
        seed.fill(0)
        return privKey
    }

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
                var priv: ByteArray? = null
                try {
                    priv = derivePrivateKey(seed, accountIndex, i)
                    val pub = privateKeyToPublicKey(priv)
                    result[i] = Pair(priv, pub)
                } catch (_: Throwable) {
                    priv?.fill(0)
                }
            }
        } finally {
            seed.fill(0)
        }
        return result
    }

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
            if (!succeeded) priv?.fill(0)
        }
    }

    suspend fun getLocalBalance(): Double? = withContext(Dispatchers.IO) {
        try {
            val node = RavencoinPublicNode(context)
            val currentIndex = getCurrentAddressIndex()
            val addresses = getAddressBatch(0, 0..currentIndex).values.toList()
            // If the Keystore is locked (device asleep + screen lock),
            // getSeed() returns null and getAddressBatch yields an empty map.
            // getTotalBalance(empty) returns 0.0, which would overwrite the
            // last-known correct balance. Return null so callers preserve
            // the cached balance instead of flashing "0.00000000 RVN".
            if (addresses.isEmpty()) return@withContext null
            node.getTotalBalance(addresses)
        } catch (_: Exception) { null }
    }

    suspend fun sendRvnLocal(toAddress: String, amountRvn: Double): String = withContext(Dispatchers.IO) {
        var currentIndex = getCurrentAddressIndex()
        var address = getAddress(0, currentIndex) ?: error("No wallet")
        val node = RavencoinPublicNode(context)

        val (utxoResult, satPerByte) = coroutineScope {
            val utxosDeferred = async { node.getUtxosAndAllAssetUtxosBatch(address) }
            val feeDeferred   = async { node.getMinRelayFeeRateSatPerByte() }
            Pair(utxosDeferred.await(), feeDeferred.await())
        }
        var rvnUtxos:    List<Utxo>                    = utxoResult.first
        var assetUtxosMap: Map<String, List<AssetUtxo>> = utxoResult.third

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

            currentIndex = fallbackIndex
            setCurrentAddressIndex(currentIndex)
            address = getAddress(0, currentIndex) ?: error("Cannot derive fallback address")
            android.util.Log.i("WalletManager", "sendRvn: fallback to index $currentIndex ($address)")

            val fallback = node.getUtxosAndAllAssetUtxosBatch(address)
            rvnUtxos      = fallback.first
            assetUtxosMap  = fallback.third
        }

        if (rvnUtxos.isEmpty()) {
            error("No RVN available for transaction fee. Fund your wallet with at least 0.01 RVN.")
        }

        val nextAddress = getAddress(0, currentIndex + 1) ?: error("Cannot derive next address")

        val keyPair = getKeyPair(0, currentIndex) ?: error("No wallet key")
        var privKey: ByteArray? = keyPair.first
        val pubKey = keyPair.second

        val amountSat = (amountRvn * 1e8).toLong()

        data class OldFunds(val index: Int, val rvn: List<Utxo>, val assets: Map<String, List<AssetUtxo>>)
        val oldFunds = mutableListOf<OldFunds>()
        if (currentIndex > 0) {
            val oldAddrBatch = getAddressBatch(0, 0 until currentIndex)
            val oldAddrList = (0 until currentIndex).mapNotNull { i ->
                oldAddrBatch[i]?.let { i to it }
            }
            // Single batch call to find which old addresses have funds before fetching UTXOs.
            val fundedAddrs = try {
                node.getAddressesWithFunds(oldAddrList.map { it.second })
            } catch (_: Exception) { emptySet() }

            if (fundedAddrs.isNotEmpty()) {
                android.util.Log.i("WalletManager", "sendRvn: ${fundedAddrs.size} old address(es) with funds, fetching UTXOs")
                try {
                    for ((index, addr) in oldAddrList) {
                        if (addr !in fundedAddrs) continue
                        val r = node.getUtxosAndAllAssetUtxosBatch(addr)
                        if (r.first.isNotEmpty() || r.third.isNotEmpty()) {
                            oldFunds.add(OldFunds(index, r.first, r.third))
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WalletManager", "sendRvn: old funds fetch failed", e)
                }
            }
        }

        val mergedAssets = mutableMapOf<String, MutableList<AssetUtxo>>()
        assetUtxosMap.forEach { (name, utxos) -> mergedAssets.getOrPut(name) { mutableListOf() }.addAll(utxos) }
        oldFunds.forEach { of -> of.assets.forEach { (name, utxos) -> mergedAssets.getOrPut(name) { mutableListOf() }.addAll(utxos) } }

        val hasAssets   = mergedAssets.isNotEmpty()
        val hasOldFunds = oldFunds.isNotEmpty()

        var oldKeyPairs: Map<Int, Pair<ByteArray, ByteArray>> = emptyMap()

        return@withContext try {
            val txid: String
            var feeSatActual: Long = 0L
            var consumedUtxos: List<Utxo> = emptyList()
            var broadcastRawHex: String = ""

            if (hasAssets || hasOldFunds) {
                if (oldFunds.isNotEmpty()) {
                    val minIdx = oldFunds.minOf { it.index }
                    val maxIdx = oldFunds.maxOf { it.index }
                    oldKeyPairs = getKeyPairBatch(0, minIdx..maxIdx)
                }

                val currentRvnKeyed = rvnUtxos.map { RavencoinTxBuilder.KeyedUtxo(it, privKey!!, pubKey) }
                val extraRvnKeyed   = oldFunds.flatMap { of ->
                    val (op, ok) = oldKeyPairs[of.index] ?: return@flatMap emptyList()
                    of.rvn.map { RavencoinTxBuilder.KeyedUtxo(it, op, ok) }
                }
                val assetKeyed = mutableMapOf<String, MutableList<RavencoinTxBuilder.KeyedAssetUtxo>>()
                assetUtxosMap.forEach { (name, utxos) ->
                    assetKeyed.getOrPut(name) { mutableListOf() }
                        .addAll(utxos.map { RavencoinTxBuilder.KeyedAssetUtxo(it, privKey!!, pubKey) })
                }
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
                broadcastRawHex = tx.hex
                consumedUtxos = rvnUtxos + oldFunds.flatMap { it.rvn } +
                    assetUtxosMap.values.flatten().map { it.utxo } +
                    oldFunds.flatMap { it.assets.values.flatten().map { au -> au.utxo } }

                android.util.Log.i("WalletManager", "sendRvn atomic: sent $amountRvn RVN to $toAddress, " +
                    "all assets and remaining RVN to $nextAddress, txid=$txid")

            } else {
                val totalIn = rvnUtxos.sumOf { it.satoshis }
                // Sweep / MAX detection: when the requested amount + estimated fee
                // would exceed the available balance, treat as a "send all" and let
                // RavencoinTxBuilder subtract the exact fee from the recipient amount.
                // The wallet will end at 0 RVN with no change output.
                val outputsForFee = if (amountSat >= totalIn) 1 else 2
                val estimatedBytes = 10 + 148 * rvnUtxos.size + 34 * outputsForFee
                feeSatActual = estimatedBytes * satPerByte

                val isMaxSend = amountSat + feeSatActual > totalIn
                if (isMaxSend) {
                    require(totalIn > feeSatActual + 546) {
                        "Insufficient funds to cover network fee: have ${totalIn / 1e8} RVN, fee ${feeSatActual / 1e8} RVN"
                    }
                } else {
                    require(totalIn > amountSat + feeSatActual) {
                        "Insufficient funds: have ${totalIn / 1e8} RVN, need ${amountSat / 1e8} RVN + ${feeSatActual / 1e8} RVN fee"
                    }
                    val changeSat = totalIn - amountSat - feeSatActual
                    require(changeSat > 546) {
                        "Remaining change (${"%.8f".format(changeSat / 1e8)} RVN) is below dust limit. " +
                        "Send a slightly smaller amount or send the full balance."
                    }
                }

                val tx = RavencoinTxBuilder.buildAndSign(
                    utxos = rvnUtxos,
                    // Pass totalIn when sweeping so buildAndSign's fee-subtraction branch fires.
                    toAddress = toAddress,
                    amountSat = if (isMaxSend) totalIn else amountSat,
                    feeSat = feeSatActual,
                    changeAddress = nextAddress,
                    privKeyBytes = privKey!!,
                    pubKeyBytes = pubKey
                )
                txid = node.broadcast(tx.hex)
                broadcastRawHex = tx.hex
                consumedUtxos = rvnUtxos

                val totalInLog = rvnUtxos.sumOf { it.satoshis }
                val changeForLog = (totalInLog - (if (amountSat + feeSatActual > totalInLog) totalInLog else amountSat) - feeSatActual).coerceAtLeast(0L)
                android.util.Log.i("WalletManager", "sendRvn: sent $amountRvn RVN to $toAddress, " +
                    "remaining ${"%.8f".format(changeForLog / 1e8)} RVN to $nextAddress, txid=$txid")
            }

            setCurrentAddressIndex(currentIndex + 1)

            // Reserved-UTXO + pending-consolidation bookkeeping (D-20, D-21).
            val now = System.currentTimeMillis()
            val reserved = consumedUtxos.map {
                ReservedUtxoDao.ReservedUtxo(
                    txidIn = it.txid,
                    vout = it.outputIndex,
                    valueSat = it.satoshis,
                    submittedTxid = txid,
                    submittedAt = now
                )
            }
            ReservedUtxoDao.reserve(reserved)
            PendingConsolidationDao.upsert(
                PendingConsolidationDao.PendingConsolidation(
                    submittedTxid = txid, submittedAt = now,
                    lastRetryAt = null, retryCount = 0, lastError = null
                )
            )
            // D-25 auto-rebroadcast in 30 minutes if still unconfirmed
            RebroadcastWorker.schedule(
                context = context,
                txid = txid,
                rawHex = broadcastRawHex,
                attempt = 0,
                initialDelayMinutes = 30L
            )

            "$txid|fee:$feeSatActual"
        } finally {
            privKey?.fill(0)
        }
    }

    /**
     * D-20/D-21 reconciliation: call from refresh flows after fetching confirmed + mempool
     * history. Returns the submittedTxids whose reservations were just released.
     */
    suspend fun reconcileReservations(
        confirmedTxids: Set<String>,
        mempoolTxids: Set<String>
    ): List<String> = withContext(Dispatchers.IO) {
        val allReserved = ReservedUtxoDao.all()
        val bySubmitted = allReserved.groupBy { it.submittedTxid }
        val now = System.currentTimeMillis()
        val released = mutableListOf<String>()
        for ((subTxid, rows) in bySubmitted) {
            val confirmed = confirmedTxids.contains(subTxid)
            val inMempool = mempoolTxids.contains(subTxid)
            val stale = rows.first().submittedAt < (now - 48L * 3600_000L)
            if (confirmed || (!inMempool && stale)) {
                ReservedUtxoDao.releaseFor(subTxid)
                PendingConsolidationDao.clear(subTxid)
                released += subTxid
            }
        }
        released
    }

    suspend fun transferAssetLocal(
        assetName: String,
        toAddress: String,
        qty: Double = 1.0
    ): String = withContext(Dispatchers.IO) {
        val currentIndex = getCurrentAddressIndex()
        val nextAddress = getAddress(0, currentIndex + 1) ?: error("Cannot derive next address")
        val node = RavencoinPublicNode(context)

        val rawQtyRequested = Math.round(qty * 100_000_000.0)
        require(rawQtyRequested > 0) { "Transfer quantity must be greater than zero" }

        val satPerByte = try { node.getMinRelayFeeRateSatPerByte() } catch (_: FeeUnavailableException) { 200L }

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

        val otherByIndex = allFunds.associate { af ->
            af.index to af.assetUtxos.filterKeys { it != assetName }
        }.filter { (_, m) -> m.isNotEmpty() }

        val involvedIndices = (primaryByIndex.keys + otherByIndex.keys + allFunds.map { it.index }).toSet()
        val minIdx = involvedIndices.minOrNull() ?: currentIndex
        val maxIdx = involvedIndices.maxOrNull() ?: currentIndex
        val keyPairs = getKeyPairBatch(0, minIdx..maxIdx)

        return@withContext try {
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

            // Reserved-UTXO + pending-consolidation bookkeeping (D-20, D-21).
            val allConsumedUtxos = allFunds.flatMap { af ->
                af.rvnUtxos + af.assetUtxos.values.flatten().map { it.utxo }
            }
            val xferNow = System.currentTimeMillis()
            ReservedUtxoDao.reserve(allConsumedUtxos.map {
                ReservedUtxoDao.ReservedUtxo(
                    txidIn = it.txid, vout = it.outputIndex, valueSat = it.satoshis,
                    submittedTxid = txid, submittedAt = xferNow
                )
            })
            PendingConsolidationDao.upsert(
                PendingConsolidationDao.PendingConsolidation(
                    submittedTxid = txid, submittedAt = xferNow,
                    lastRetryAt = null, retryCount = 0, lastError = null
                )
            )
            RebroadcastWorker.schedule(
                context = context, txid = txid, rawHex = tx.hex,
                attempt = 0, initialDelayMinutes = 30L
            )

            android.util.Log.i("WalletManager", "transferAsset: sent $qty $assetName to $toAddress, " +
                "remaining assets and RVN to $nextAddress, txid=$txid")

            setCurrentAddressIndex(currentIndex + 1)
            txid
        } finally {
            keyPairs.values.forEach { (priv, _) -> priv.fill(0) }
        }
    }

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

        val actualToAddress = if (toAddress == address) nextAddress else toAddress

        val node = RavencoinPublicNode(context)

        val (utxoResult, satPerByte) = coroutineScope {
            val utxosDeferred = async { node.getUtxosAndAllAssetUtxosBatch(address) }
            val feeDeferred   = async { node.getMinRelayFeeRateSatPerByte() }
            Pair(utxosDeferred.await(), feeDeferred.await())
        }
        val rvnUtxos    = utxoResult.first
        val allAssetMap = utxoResult.third

        if (rvnUtxos.isEmpty()) error("No spendable RVN on current address. Try refreshing the wallet.")

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

        val keyPair = getKeyPair(0, currentIndex) ?: error("No wallet key")
        var privKey: ByteArray? = keyPair.first
        val pubKey = keyPair.second

        return@withContext try {
            val tx = RavencoinTxBuilder.buildAndSignAssetIssueWithAssetSweep(
                utxos = rvnUtxos.filterNot { rvn ->
                    ownerAssetUtxos.any { owner -> owner.txid == rvn.txid && owner.outputIndex == rvn.outputIndex }
                },
                ownerAssetUtxos = ownerAssetUtxos,
                otherAssetUtxos = otherAssetUtxos,
                assetName = assetName,
                qtyRaw = qtyRaw,
                toAddress = actualToAddress,
                changeAddress = nextAddress,
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

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA512Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(64).also { mac.doFinal(it, 0) }
    }

    private fun derivePrivateKey(seed: ByteArray, account: Int, index: Int): ByteArray {
        var I = hmacSha512("Bitcoin seed".toByteArray(Charsets.UTF_8), seed)
        var kl = I.copyOf(32)
        var kr = I.copyOfRange(32, 64)
        I.fill(0)

        val i1 = deriveChild(kl, kr, 44 or 0x80000000.toInt())
        kl.fill(0); kr.fill(0)
        kl = i1.copyOf(32); kr = i1.copyOfRange(32, 64); i1.fill(0)

        val i2 = deriveChild(kl, kr, COIN_TYPE or 0x80000000.toInt())
        kl.fill(0); kr.fill(0)
        kl = i2.copyOf(32); kr = i2.copyOfRange(32, 64); i2.fill(0)

        val i3 = deriveChild(kl, kr, account or 0x80000000.toInt())
        kl.fill(0); kr.fill(0)
        kl = i3.copyOf(32); kr = i3.copyOfRange(32, 64); i3.fill(0)

        val i4 = deriveChild(kl, kr, 0)
        kl.fill(0); kr.fill(0)
        kl = i4.copyOf(32); kr = i4.copyOfRange(32, 64); i4.fill(0)

        val i5 = deriveChild(kl, kr, index)
        kl.fill(0); kr.fill(0)
        val result = i5.copyOf(32)
        i5.fill(0)
        return result
    }

    private fun deriveChild(parentKey: ByteArray, parentChain: ByteArray, index: Int): ByteArray {
        val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val n = spec.n
        var i = index
        while (true) {
            val data = if (i and 0x80000000.toInt() != 0) {
                byteArrayOf(0x00) + parentKey + intToBytes(i)
            } else {
                privateKeyToPublicKey(parentKey) + intToBytes(i)
            }
            val hmacOut = hmacSha512(parentChain, data)
            val IL = BigInteger(1, hmacOut.copyOf(32))
            val chainCode = hmacOut.copyOfRange(32, 64)
            if (IL >= n) { i++; continue }
            val childScalar = IL.add(BigInteger(1, parentKey)).mod(n)
            if (childScalar == BigInteger.ZERO) { i++; continue }
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
        return point.getEncoded(true)
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
        val stripped = if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
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
        val node = RavencoinPublicNode(context)

        try {
            val r = node.getUtxosAndAllAssetUtxosBatch(addr)
            val rvnBalance = r.first.sumOf { it.satoshis }
            val hasAssets = r.third.isNotEmpty()

            if (hasAssets || rvnBalance > 0) {
                val satPerByte = try { node.getMinRelayFeeRateSatPerByte() } catch (_: FeeUnavailableException) { 200L }

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
                        // Poll until funding UTXOs are visible in mempool (up to 60s)
                        var waited = 0
                        while (waited < 60) {
                            val fundedUtxos = try { node.getUtxos(addr) } catch (_: Exception) { emptyList() }
                            if (fundedUtxos.isNotEmpty()) break
                            kotlinx.coroutines.delay(3000)
                            waited += 3
                        }
                    } finally {
                        curPrivKey?.fill(0)
                    }
                }

                val targetAddr = getAddress(0, currentIndex) ?: return@withContext
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
 * Consolidate all funds (RVN + assets) from addresses 0..currentIndex to a fresh address.
 */
suspend fun consolidateAllFundsToFreshAddress(): String? = withContext(Dispatchers.IO) {
    val currentIndex = getCurrentAddressIndex()
    android.util.Log.i("WalletManager", "consolid: START - currentIndex=$currentIndex")

    val node = RavencoinPublicNode(context)
    val nextIndex = currentIndex + 1

    // STEP 1: Derive ALL addresses in ONE Keystore decrypt.
    val allAddresses = getAddressBatch(0, 0..nextIndex)
    val targetAddress = allAddresses[nextIndex]
    if (targetAddress == null) {
        android.util.Log.w("WalletManager", "consolid: cannot derive target at index $nextIndex")
        return@withContext null
    }

    android.util.Log.i("WalletManager", "consolid: derived ${allAddresses.size} addresses in 1 Keystore decrypt, target=$targetAddress (index $nextIndex)")

    // STEP 2: Scan addresses for funds in controlled batches of 5.
    data class AddrFunds(
        val index: Int,
        val rvnUtxos: List<Utxo>,
        val assetUtxos: Map<String, List<AssetUtxo>>
    )

    val allFunds = mutableListOf<AddrFunds>()
    val SCAN_BATCH = 5

    for (batchStart in 0..currentIndex step SCAN_BATCH) {
        val batchEnd = minOf(batchStart + SCAN_BATCH - 1, currentIndex)
        val batchIndices = (batchStart..batchEnd).filter { allAddresses.containsKey(it) }

        android.util.Log.i("WalletManager", "consolid: --- batch ${batchStart}..${batchEnd} ---")

        val results = batchIndices.map { i ->
            async {
                val addr = allAddresses[i]!!
                try {
                    val r = node.getUtxosAndAllAssetUtxosBatch(addr)
                    val rvnCount = r.first.size
                    val rvnTotal = r.first.sumOf { it.satoshis }
                    val assetNames = r.third.keys.toList()

                    if (rvnCount > 0 || assetNames.isNotEmpty()) {
                        android.util.Log.i("WalletManager", "consolid: index $i -> $addr => RVN=$rvnCount (${rvnTotal / 1e8}), assets=$assetNames")
                        AddrFunds(i, r.first, r.third)
                    } else {
                        // SECONDARY ASSET CHECK: many ElectrumX servers don't return
                        // asset UTXOs in listunspent. Use getAssetBalances() instead.
                        try {
                            val assetBalances = node.getAssetBalances(addr)
                            if (assetBalances.isNotEmpty()) {
                                android.util.Log.i("WalletManager", "consolid: index $i -> $addr => EMPTY in listunspent BUT has assets via get_balance: ${assetBalances.map { "${it.name}=${it.amount}" }}")
                                val assetUtxosMap = mutableMapOf<String, List<AssetUtxo>>()
                                for (ab in assetBalances) {
                                    try {
                                        val utxos = node.getAssetUtxosFull(addr, ab.name)
                                        if (utxos.isNotEmpty()) {
                                            assetUtxosMap[ab.name] = utxos
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("WalletManager", "consolid: getAssetUtxosFull failed for ${ab.name}: ${e.message}")
                                    }
                                }
                                val rvnUtxos = node.getUtxos(addr)
                                if (assetUtxosMap.isNotEmpty() || rvnUtxos.isNotEmpty()) {
                                    AddrFunds(i, rvnUtxos, assetUtxosMap)
                                } else null
                            } else {
                                android.util.Log.d("WalletManager", "consolid: index $i -> $addr => EMPTY (no assets either)")
                                null
                            }
                        } catch (e: Exception) {
                            android.util.Log.d("WalletManager", "consolid: index $i -> $addr => EMPTY (asset check failed: ${e.message})")
                            null
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WalletManager", "consolid: index $i -> $addr => FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
        }.awaitAll().filterNotNull()

        allFunds.addAll(results)
    }

    // Summary
    android.util.Log.i("WalletManager", "consolid: SCAN COMPLETE: checked ${currentIndex + 1} addresses, found funds on ${allFunds.size}")
    for (af in allFunds.sortedBy { it.index }) {
        val rvnTotal = af.rvnUtxos.sumOf { it.satoshis }
        val assetNames = af.assetUtxos.keys.toList()
        val assetRvnTotal = af.assetUtxos.values.flatten().sumOf { it.utxo.satoshis }
        android.util.Log.i("WalletManager", "consolid:   index ${af.index}: RVN=${"%.8f".format(rvnTotal / 1e8)}, assetAttachedRVN=${"%.8f".format(assetRvnTotal / 1e8)}, assets=$assetNames")
    }

    if (allFunds.isEmpty()) {
        android.util.Log.i("WalletManager", "consolid: no funds found on any address 0..$currentIndex")
        return@withContext null
    }

    // STEP 2.5: Fund addresses that have assets but no RVN.
    // Find address with most RVN to use as "sacrificial" funder
    val richestRvnAddr = allFunds.maxByOrNull { it.rvnUtxos.sumOf { u -> u.satoshis } }
    val sacrificialIndex = richestRvnAddr?.index

    // Track which outpoints were spent by funding txs, to exclude them from re-scan
    val spentOutpoints = mutableSetOf<String>()

    val addressesNeedingFunding = allFunds.filter { it.rvnUtxos.isEmpty() && it.assetUtxos.isNotEmpty() }
    if (addressesNeedingFunding.isNotEmpty()) {
        android.util.Log.i("WalletManager", "consolid: ${addressesNeedingFunding.size} address(es) have assets but no RVN, funding first")
        for (addrFunds in addressesNeedingFunding) {
            val addr = allAddresses[addrFunds.index] ?: continue
            val assetCount = addrFunds.assetUtxos.keys.size
            android.util.Log.i("WalletManager", "consolid: funding index ${addrFunds.index} ($addr) with 10 RVN for $assetCount asset types")

            // Fund with 10 RVN: enough to pay the consolidation fee, the rest returns as change
            val fundAmountSat = 1_000_000_000L // 10 RVN
            val satPerByte = try { node.getMinRelayFeeRateSatPerByte() } catch (_: FeeUnavailableException) { 200L }
            val fundingTxFee = 300L * satPerByte // simple 1-in, 2-out tx

            val sacAddr = allAddresses[sacrificialIndex] ?: ""
            val sacAssetOutpoints = try { node.getAllAssetOutpoints(sacAddr) } catch (_: Exception) { emptySet() }
            val sacUtxos = try {
                node.getUtxos(allAddresses[sacrificialIndex]!!)
                    .filter { "${it.txid}:${it.outputIndex}" !in sacAssetOutpoints }
            } catch (_: Exception) { emptyList() }

            if (sacrificialIndex == null || sacUtxos.isEmpty()) {
                android.util.Log.w("WalletManager", "consolid: no sacrificial address or no RVN available, skipping funding")
                continue
            }

            val totalIn = sacUtxos.sumOf { it.satoshis }
            if (totalIn < fundAmountSat + fundingTxFee) {
                android.util.Log.w("WalletManager", "consolid: sacrificial address has insufficient RVN: ${totalIn / 1e8} < ${(fundAmountSat + fundingTxFee) / 1e8}")
                continue
            }

            // Track spent outpoints from sacrificial address
            for (utxo in sacUtxos) {
                spentOutpoints.add("${utxo.txid}:${utxo.outputIndex}")
            }
            android.util.Log.i("WalletManager", "consolid: tracking ${spentOutpoints.size} spent outpoint(s) from funding")

            val privKey = try { getPrivateKeyBytes(0, sacrificialIndex) } catch (_: Exception) { null }
            val pubKey = try { getPublicKeyBytes(0, sacrificialIndex) } catch (_: Exception) { null }
            if (privKey == null || pubKey == null) {
                android.util.Log.w("WalletManager", "consolid: cannot get keys for sacrificial index $sacrificialIndex")
                continue
            }

            try {
                val sacChangeAddr = allAddresses[sacrificialIndex]!!
                val tx = RavencoinTxBuilder.buildAndSign(
                    utxos = sacUtxos,
                    toAddress = addr,
                    amountSat = fundAmountSat,
                    feeSat = fundingTxFee,
                    changeAddress = sacChangeAddr,
                    privKeyBytes = privKey,
                    pubKeyBytes = pubKey
                )
                val txid = node.broadcast(tx.hex)
                android.util.Log.i("WalletManager", "consolid: funded $addr with ${fundAmountSat / 1e8} RVN from sacrificial $sacrificialIndex: $txid")

                val fundResult = FundingResult(txid, Utxo(
                    txid = txid,
                    outputIndex = 0,
                    satoshis = fundAmountSat,
                    script = addressToP2pkhScript(addr),
                    height = 0
                ))

                // DO NOT wait for confirmation: proceed immediately to avoid
                // race conditions with background sweep workers that may try to
                // spend the same UTXOs. We know the funding tx is valid.
                android.util.Log.i("WalletManager", "consolid: proceeding immediately with funded UTXO (tx in mempool, not yet confirmed)")

                // Update allFunds with the funded UTXO directly : don't rely on server re-scan
                // which might report stale data or miss the new UTXO
                val idx = allFunds.indexOfFirst { it.index == addrFunds.index }
                if (idx >= 0) {
                    allFunds[idx] = AddrFunds(addrFunds.index, listOf(fundResult.fundUtxo), addrFunds.assetUtxos)
                    android.util.Log.i("WalletManager", "consolid: updated allFunds for index ${addrFunds.index}: 1 funded RVN UTXO, ${addrFunds.assetUtxos.size} asset type(s)")
                }
            } catch (e: Exception) {
                android.util.Log.e("WalletManager", "consolid: funding failed for $addr: ${e.message}")
            }
        }
    }

    // Re-scan ONLY non-funded addresses. Funded addresses already have correct
    // UTXO data set manually (funded UTXO + asset UTXOs from initial scan).
    // Server re-scan would return stale/conflicting data.
    // Filter out spent outpoints (from funding tx inputs) for the sacrificial address.
    val fundedIndices = addressesNeedingFunding.map { it.index }.toSet()
    android.util.Log.i("WalletManager", "consolid: re-scanning non-funded addresses, excluding ${spentOutpoints.size} spent outpoint(s)")

    for (i in allFunds.indices) {
        val af = allFunds[i]
        // Skip funded addresses : they already have correct UTXO data
        if (af.index in fundedIndices) {
            android.util.Log.i("WalletManager", "consolid: skipping re-scan for funded index ${af.index}")
            continue
        }
        val addr = allAddresses[af.index] ?: continue
        try {
            val rawRvnUtxos = node.getUtxos(addr)
            val rvnUtxos = rawRvnUtxos.filter { "${it.txid}:${it.outputIndex}" !in spentOutpoints }
            // Keep existing asset data from initial scan
            allFunds[i] = AddrFunds(af.index, rvnUtxos, af.assetUtxos)
            android.util.Log.i("WalletManager", "consolid: re-scan index ${af.index}: ${rvnUtxos.size} RVN UTXO(s) (filtered ${rawRvnUtxos.size - rvnUtxos.size} spent), ${af.assetUtxos.size} asset type(s)")
        } catch (e: Exception) {
            android.util.Log.w("WalletManager", "consolid: re-scan failed for index ${af.index}: ${e.message}")
        }
    }

    // STEP 3: Get key pairs for ALL involved addresses in ONE batch decrypt.
    val minIdx = allFunds.minOf { it.index }
    val maxIdx = allFunds.maxOf { it.index }
    val keyPairs = getKeyPairBatch(0, minIdx..maxIdx)

    // STEP 4: Build keyed inputs from ALL funded addresses.
    // Deduplicate by outpoint to avoid "bad-txns-inputs-duplicate" errors.
    val allRvnKeyed = mutableListOf<RavencoinTxBuilder.KeyedUtxo>()
    val allAssetKeyed = mutableMapOf<String, MutableList<RavencoinTxBuilder.KeyedAssetUtxo>>()
    val seenRvnOutpoints = mutableSetOf<String>()
    val seenAssetOutpoints = mutableSetOf<String>()

    for (af in allFunds) {
        val keyPair = keyPairs[af.index]
        if (keyPair == null) {
            android.util.Log.w("WalletManager", "consolid: no key pair for index ${af.index}, skipping")
            continue
        }
        val priv = keyPair.first
        val pub = keyPair.second

        val assetOutpoints = af.assetUtxos.values.flatten()
            .map { outpoint -> "${outpoint.utxo.txid}:${outpoint.utxo.outputIndex}" }.toSet()

        val pureRvn = af.rvnUtxos.filter { utxo ->
            val op = "${utxo.txid}:${utxo.outputIndex}"
            op !in assetOutpoints && seenRvnOutpoints.add(op)
        }

        for (utxo in pureRvn) {
            allRvnKeyed.add(RavencoinTxBuilder.KeyedUtxo(utxo, priv, pub))
        }

        for ((name, utxos) in af.assetUtxos) {
            allAssetKeyed.getOrPut(name) { mutableListOf() }
                .addAll(utxos.filter { assetUtxo ->
                    val op = "${assetUtxo.utxo.txid}:${assetUtxo.utxo.outputIndex}"
                    seenAssetOutpoints.add(op)
                }.map { assetUtxo -> RavencoinTxBuilder.KeyedAssetUtxo(assetUtxo, priv, pub) })
        }
    }

    val hasRvn = allRvnKeyed.isNotEmpty()
    val hasAssets = allAssetKeyed.isNotEmpty()

    if (!hasRvn && !hasAssets) {
        android.util.Log.w("WalletManager", "consolid: no spendable UTXOs after filtering")
        return@withContext null
    }

    // STEP 5: Estimate fee with realistic sizing.
    val rawSatPerByte = try {
        node.getMinRelayFeeRateSatPerByte()
    } catch (_: FeeUnavailableException) { 200L }

    // Use a high floor and cap : Ravencoin network has been raising min relay fees.
    // For large consolidation txs, underpaying fees causes silent rejection.
    val minFloor = 500L // minimum 500 sat/byte for safety
    val SAT_PER_BYTE_CAP = 2000L // cap at 2000 sat/byte for very large txs
    val satPerByte = minOf(maxOf(rawSatPerByte, minFloor), SAT_PER_BYTE_CAP)

    val totalRvnInputs = allRvnKeyed.size
    val totalAssetInputs = allAssetKeyed.values.sumOf { it.size }
    val totalInputs = totalRvnInputs + totalAssetInputs
    val totalAssetOutputs = allAssetKeyed.size

    // Tight byte estimate: ~150 bytes per signed P2PKH input, ~34 bytes per RVN output,
    // ~85 bytes per asset output (extra OP_RVN_ASSET payload). +10 bytes header.
    val estimatedBytes = 10L + 150L * totalInputs + 34L + 85L * totalAssetOutputs
    val feeSat = estimatedBytes * satPerByte

    android.util.Log.i("WalletManager", "consolid: fee estimate : ${estimatedBytes} bytes at ${satPerByte} sat/byte = ${feeSat} sat (raw relay fee was ${rawSatPerByte})")

    // ═══════════════════════════════════════════════════════════════════════
    // CRITICAL FIX: Asset dust reservation
    //
    // Each Ravencoin asset output requires at least DUST_LIMIT satoshis
    // of RVN attached (anti-dust rule). With 19 assets that's
    // 19 × 546 = 10,374 sat that CANNOT be used as payment.
    //
    // Previous code set amountSat = totalPureRvn - feeSat, which consumed
    // ALL the RVN and left nothing for the asset dust, causing the
    // transaction to fail (outputs > inputs).
    // ═══════════════════════════════════════════════════════════════════════
    val DUST_LIMIT = 546L
    val totalAssetDust = totalAssetOutputs * DUST_LIMIT

    val totalPureRvn = allRvnKeyed.sumOf { it.utxo.satoshis }
    val totalAssetAttachedRvn = allAssetKeyed.values.flatten().sumOf { it.assetUtxo.utxo.satoshis }
    val totalRvnAvailable = totalPureRvn + totalAssetAttachedRvn

    android.util.Log.i("WalletManager", "consolid: RVN breakdown : pure=$totalPureRvn, assetAttached=$totalAssetAttachedRvn, total=$totalRvnAvailable")
    android.util.Log.i("WalletManager", "consolid: fee = $feeSat sat, assetDust = $totalAssetDust sat ($totalAssetOutputs outputs × $DUST_LIMIT)")

    // Reserve RVN for: fee + asset dust + at least dust for RVN change/output
    val rvnNeeded = feeSat + totalAssetDust + DUST_LIMIT
    if (totalRvnAvailable < rvnNeeded) {
        android.util.Log.e("WalletManager",
            "consolid: insufficient RVN: have ${"%.8f".format(totalRvnAvailable / 1e8)}, " +
            "need ${"%.8f".format(rvnNeeded / 1e8)} (fee + asset dust + min output)")
        return@withContext null
    }

    // amountSat = drain ALL RVN (pure + asset-attached) minus exact byte fee minus
    // dust required for the new asset outputs. Old addresses end with zero satoshis.
    val amountSat = totalRvnAvailable - feeSat - totalAssetDust

    android.util.Log.i("WalletManager", "consolid: amountSat=$amountSat, feeSat=$feeSat, assetDust=$totalAssetDust")

    if (amountSat < DUST_LIMIT && !hasAssets) {
        android.util.Log.e("WalletManager", "consolid: RVN output below dust limit")
        return@withContext null
    }

    // STEP 6: Build and broadcast the consolidation transaction.
    return@withContext try {
        val txid: String

        if (hasAssets || allFunds.size > 1) {
            android.util.Log.i("WalletManager", "consolid: multi-address tx : " +
                "rvnInputs=$totalRvnInputs, assetInputs=$totalAssetInputs, " +
                "assetOutputs=$totalAssetOutputs, amountSat=$amountSat, feeSat=$feeSat, assetDust=$totalAssetDust")

            val tx = RavencoinTxBuilder.buildAndSignMultiAddressSend(
                currentRvnInputs  = allRvnKeyed,
                extraRvnInputs    = emptyList(),
                assetInputsByName = allAssetKeyed,
                toAddress         = targetAddress,
                amountSat         = amountSat,
                feeSat            = feeSat,
                changeAddress     = targetAddress
            )
            txid = node.broadcast(tx.hex)

        } else {
            // Single address, RVN only
            val totalSat = allRvnKeyed.sumOf { it.utxo.satoshis }
            val sendAmount = totalSat - feeSat

            if (sendAmount <= DUST_LIMIT) {
                android.util.Log.e("WalletManager",
                    "consolid: amount after fee ($sendAmount sat) is below dust limit")
                return@withContext null
            }

            val singleKeyPair = keyPairs[allFunds.first().index]
            if (singleKeyPair == null) {
                android.util.Log.e("WalletManager", "consolid: no key pair for single-address sweep")
                return@withContext null
            }
            val utxos = allRvnKeyed.map { it.utxo }

            android.util.Log.i("WalletManager", "consolid: single-address RVN sweep : " +
                "totalIn=$totalSat, send=$sendAmount, fee=$feeSat")

            val tx = RavencoinTxBuilder.buildAndSign(
                utxos = utxos,
                toAddress = targetAddress,
                amountSat = sendAmount,
                feeSat = feeSat,
                changeAddress = targetAddress,
                privKeyBytes = singleKeyPair.first,
                pubKeyBytes = singleKeyPair.second
            )
            txid = node.broadcast(tx.hex)
        }

        setCurrentAddressIndex(nextIndex)
        android.util.Log.i("WalletManager", "consolid: SUCCESS - txid=$txid, new index=$nextIndex")
        txid

    } catch (e: Exception) {
        // Log full exception details for debugging
        android.util.Log.e("WalletManager", "consolid: FAILED : ${e.javaClass.simpleName}: ${e.message}", e)
        null
    } finally {
        keyPairs.values.forEach { (priv, _) -> priv.fill(0) }
    }
}

/**
 * Get owned assets for all wallet addresses.
 *
 * Uses ElectrumX batch API to fetch asset balances for all addresses.
 * Returns list of assets sorted by type and name.
 *
 * @return List of owned assets with name, balance, and type
 */
suspend fun getOwnedAssets(): List<OwnedAsset> = withContext(Dispatchers.IO) {
    val node = RavencoinPublicNode(context)
    val currentIndex = getCurrentAddressIndex()
    val addresses = getAddressBatch(0, 0..currentIndex).values.toList()

    if (addresses.isEmpty()) return@withContext emptyList()

    android.util.Log.i("WalletManager", "Fetching owned assets for ${addresses.size} addresses")

    try {
        val totals = node.getTotalAssetBalances(addresses)

        totals.map { (name, amount) ->
            val type = when {
                name.contains('#') -> io.raventag.app.ravencoin.AssetType.UNIQUE
                name.contains('/') -> io.raventag.app.ravencoin.AssetType.SUB
                else -> io.raventag.app.ravencoin.AssetType.ROOT
            }
            io.raventag.app.ravencoin.OwnedAsset(
                name = name,
                balance = amount,
                type = type,
                ipfsHash = null
            )
        }.sortedWith(compareBy({ it.type.ordinal }, { it.name }))
    } catch (e: Exception) {
        android.util.Log.e("WalletManager", "Failed to fetch owned assets", e)
        emptyList()
    }
}

/**
 * Get transaction history for all wallet addresses.
 *
 * Uses ElectrumX blockchain.address.subscribe to fetch transaction history.
 * Returns list of transactions sorted by height (descending, newest first).
 *
 * @return List of transaction history entries with txid, amount, confirmations
 */
suspend fun getTransactionHistory(): List<TxHistoryEntry> = withContext(Dispatchers.IO) {
    val node = RavencoinPublicNode(context)
    val currentIndex = getCurrentAddressIndex()
    // Include currentIndex+1 (change address) so classification correctly
    // attributes change outputs to the wallet instead of "sent to others".
    val addresses = getAddressBatch(0, 0..(currentIndex + 1)).values.toList()
    val ownedSet = addresses.toSet()

    if (addresses.isEmpty()) return@withContext emptyList()

    android.util.Log.i("WalletManager", "Fetching transaction history for ${addresses.size} addresses")

    try {
        val historyEntries = mutableListOf<TxHistoryEntry>()

        // Fetch history for each address using ElectrumX, passing full owned set
        // so each tx is classified consistently (sent / cycled / fee).
        for (address in addresses) {
            try {
                val history = node.getTransactionHistory(address, ownedAddresses = ownedSet)
                historyEntries.addAll(history)
            } catch (e: Exception) {
                android.util.Log.w("WalletManager", "Failed to fetch history for $address", e)
                // Continue with next address
            }
        }

        // Deduplicate by txid (same tx may appear in multiple address histories)
        val deduped = historyEntries.distinctBy { it.txid }

        // Sort by block height descending (newest first)
        val sorted = deduped.sortedWith(
            compareByDescending<TxHistoryEntry> {
                if (it.height <= 0) Int.MAX_VALUE else it.height
            }.thenByDescending { it.timestamp }
        )

        android.util.Log.i("WalletManager", "Loaded ${sorted.size} transactions from history")
        sorted
    } catch (e: Exception) {
        android.util.Log.e("WalletManager", "Failed to fetch transaction history", e)
        emptyList()
    }
}

}
