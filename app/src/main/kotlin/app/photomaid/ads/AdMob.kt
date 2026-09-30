package app.photomaid.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import app.photomaid.BuildConfig
import app.photomaid.system.SystemDialogs
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Google AdMob + Einwilligung (UMP). Debug/QA: offizielle Google-Test-IDs; Release: nur mit per Gradle gesetzten IDs,
 * sonst ist Werbung komplett aus (kein SDK-Start, keine Requests).
 *
 * Datenschutz: Anfragen enthalten keinerlei Medieninhalte, Dateinamen, Pfade, IDs oder Schlüsselwörter.
 */
class AdMobSdk(private val context: Context) {
    val isConfigured: Boolean get() = BuildConfig.ADMOB_APP_ID.isNotBlank()
    private val _ready = MutableStateFlow(false)
    /** SDK gestartet und Einwilligung erlaubt Anfragen. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private var consent: ConsentInformation? = null
    private var starting = false

    /** Einwilligung einholen (EU/Schweiz: Google-Dialog), danach SDK starten. Nie für Lifetime-Pro aufrufen. */
    fun start(activity: Activity) {
        if (!isConfigured || starting || _ready.value) return
        starting = true
        val info = UserMessagingPlatform.getConsentInformation(activity).also { consent = it }
        info.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { err ->
                if (err != null) Log.w("PhotoMaid", "consent form: ${err.errorCode}")
                initIfAllowed(info)
            }
        }, { err -> Log.w("PhotoMaid", "consent update: ${err.errorCode}"); initIfAllowed(info) })
        initIfAllowed(info) // bekannte Einwilligung aus früherer Sitzung
    }

    private fun initIfAllowed(info: ConsentInformation) {
        if (_ready.value || !info.canRequestAds()) return
        runCatching { MobileAds.initialize(context) { _ready.value = true } }
            .onFailure { Log.w("PhotoMaid", "ads init: ${it.javaClass.simpleName}") }
    }

    /** Eintrag „Datenschutz-Optionen für Werbung“ nötig? (EU: Widerruf/Änderung der Einwilligung). */
    val privacyOptionsRequired: Boolean
        get() = consent?.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { err -> if (err != null) Log.w("PhotoMaid", "privacy options: ${err.errorCode}") }
    }
}

/** Werbekarte im Feed: fertig geladene Anzeige (oder im Test eine Attrappe ohne SDK). */
class FeedAd(
    val headline: String,
    val body: String?,
    val callToAction: String?,
    val advertiser: String?,
    /** Echte AdMob-Anzeige zum Rendern in `NativeAdView`; `null` in Tests. */
    val native: NativeAd? = null,
) {
    fun destroy() { runCatching { native?.destroy() } }
}

/** Quelle für Werbekarten. Hält höchstens eine Anzeige vorrätig. */
interface FeedAdSource {
    val isAvailable: Boolean
    fun preload()
    /** Vorrätige Anzeige entnehmen (danach wird die nächste vorgeladen). `null` = gerade keine. */
    fun take(): FeedAd?
}

object NoFeedAds : FeedAdSource {
    override val isAvailable = false
    override fun preload() {}
    override fun take(): FeedAd? = null
}

class AdMobFeedAds(private val context: Context, private val sdk: AdMobSdk) : FeedAdSource {
    private var cached: FeedAd? = null
    private var loading = false
    override val isAvailable: Boolean get() = sdk.isConfigured && BuildConfig.ADMOB_NATIVE_UNIT.isNotBlank() && sdk.ready.value

    override fun preload() {
        if (!isAvailable || loading || cached != null) return
        loading = true
        runCatching {
            AdLoader.Builder(context, BuildConfig.ADMOB_NATIVE_UNIT)
                .forNativeAd { ad ->
                    cached?.destroy()
                    cached = FeedAd(ad.headline ?: "", ad.body, ad.callToAction, ad.advertiser, ad)
                    loading = false
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) { loading = false; Log.w("PhotoMaid", "native ad: ${error.code}") }
                })
                .withNativeAdOptions(NativeAdOptions.Builder().setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT).build())
                .build()
                .loadAd(AdRequest.Builder().build())
        }.onFailure { loading = false }
    }

    override fun take(): FeedAd? = cached.also { cached = null; preload() }
}

/** Echte Rewarded-Anzeige (30-Minuten-Pro-Test). Belohnung ausschließlich über `onUserEarnedReward`. */
class AdMobRewardedGateway(private val context: Context, private val sdk: AdMobSdk) : RewardedAdGateway {
    override val isConfigured: Boolean get() = sdk.isConfigured && BuildConfig.ADMOB_REWARDED_UNIT.isNotBlank() && sdk.ready.value

    override suspend fun presentRewardedAd(): RewardedAdResult {
        if (!isConfigured) return RewardedAdResult.NotConfigured
        val activity = SystemDialogs.activity ?: return RewardedAdResult.Failed("no-activity")
        val ad = suspendCancellableCoroutine<Any> { cont ->
            RewardedAd.load(context, BuildConfig.ADMOB_REWARDED_UNIT, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) { if (cont.isActive) cont.resume(ad) }
                override fun onAdFailedToLoad(error: LoadAdError) { if (cont.isActive) cont.resume(error) }
            })
        }
        if (ad is LoadAdError) return if (ad.code == AdRequest.ERROR_CODE_NO_FILL) RewardedAdResult.NoFill else RewardedAdResult.Failed("load:${ad.code}")
        return show(ad as RewardedAd, activity)
    }

    private suspend fun show(ad: RewardedAd, activity: Activity): RewardedAdResult = suspendCancellableCoroutine { cont ->
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (cont.isActive) cont.resume(if (earned) RewardedAdResult.Rewarded(UUID.randomUUID().toString()) else RewardedAdResult.DismissedWithoutReward)
            }
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                if (cont.isActive) cont.resume(RewardedAdResult.Failed("show:${error.code}"))
            }
        }
        ad.show(activity) { earned = true }
    }
}
