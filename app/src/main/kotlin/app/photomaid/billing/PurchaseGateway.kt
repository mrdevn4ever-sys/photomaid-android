package app.photomaid.billing

import android.app.Activity
import android.content.Context
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.data.KeyValueStore
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Lokalisierter Preis aus dem echten Storeprodukt. Nie erfunden. */
data class StoreProductInfo(val localizedPrice: String, val title: String)

sealed interface PurchaseResult {
    data object Purchased : PurchaseResult
    data object Pending : PurchaseResult
    data object Cancelled : PurchaseResult
    data class Failed(val reason: String) : PurchaseResult
    data object NotConfigured : PurchaseResult
}

/** Einzige Entitlement-Wahrheit (iOS: RevenueCat über Apple-IAP; Android: Google Play Billing). */
interface PurchaseGateway {
    val state: StateFlow<PurchaseState>
    val product: StateFlow<StoreProductInfo?>
    val isConfigured: Boolean
    suspend fun refresh()
    suspend fun purchasePro(activity: Activity): PurchaseResult
    suspend fun restore(): PurchaseResult
}

/** Ohne Produkt-ID: ehrlich „nicht verfügbar“ (kein Preis, kein toter Button). */
class UnconfiguredPurchaseGateway : PurchaseGateway {
    override val state = MutableStateFlow(PurchaseState.NOT_PURCHASED).asStateFlow()
    override val product = MutableStateFlow<StoreProductInfo?>(null).asStateFlow()
    override val isConfigured = false
    override suspend fun refresh() {}
    override suspend fun purchasePro(activity: Activity) = PurchaseResult.NotConfigured
    override suspend fun restore() = PurchaseResult.NotConfigured
}

/**
 * Google Play Billing für das einmalige In-App-Produkt „PhotoMaid Pro“ (Non-Consumable).
 * Behandelt Kauf, Bestätigung (acknowledge), Wiederherstellen (queryPurchases), ausstehende Käufe,
 * Erstattung/Widerruf (Kauf verschwindet aus queryPurchases → `REVOKED`), Offline (zuletzt bekannter Status).
 * Übertragen werden nur Kaufdaten an Google – keine Medien, Dateinamen, Hashes.
 */
class PlayBillingGateway(context: Context, private val productId: String, private val cache: KeyValueStore) : PurchaseGateway {
    private val _state = MutableStateFlow(PurchaseState.UNKNOWN)
    override val state: StateFlow<PurchaseState> = _state.asStateFlow()
    private val _product = MutableStateFlow<StoreProductInfo?>(null)
    override val product: StateFlow<StoreProductInfo?> = _product.asStateFlow()
    override val isConfigured = true
    private var details: ProductDetails? = null
    private var pendingFlow: CompletableDeferred<PurchaseResult>? = null
    private val connectLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object { private const val CACHE_KEY = "purchase.cachedState" }

    private val listener = PurchasesUpdatedListener { result, purchases ->
        val deferred = pendingFlow
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val mine = purchases.orEmpty().filter { productId in it.products }
                scope.launch { deferred?.complete(handle(mine, fromFlow = true)) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> deferred?.complete(PurchaseResult.Cancelled)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                scope.launch {
                    refreshPurchases()
                    deferred?.complete(if (_state.value == PurchaseState.PURCHASED) PurchaseResult.Purchased else PurchaseResult.Pending)
                }
            }
            else -> deferred?.complete(PurchaseResult.Failed("billing-${result.responseCode}"))
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** Muss abseits des Main-Threads aufgerufen werden (liest den Offline-Cache). */
    fun loadCache() {
        cache.value<PurchaseState>(CACHE_KEY)?.let { _state.value = it }
    }

    private fun setState(s: PurchaseState) {
        if (s != _state.value) {
            _state.value = s
            runCatching { cache.setValue(CACHE_KEY, s) }
        }
    }

    private suspend fun connect(): Boolean = connectLock.withLock {
        if (client.isReady) return true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() {}
            })
        }
    }

    /** Status aus Play. Offline/Fehler → letzter bekannter Status bleibt (kein Downgrade ohne Beleg). */
    override suspend fun refresh() {
        if (!connect()) return
        refreshPurchases()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(BillingClient.ProductType.INAPP).build(),
        )).build()
        val res = withContext(Dispatchers.IO) { client.queryProductDetails(params) }
        val d = res.productDetailsList?.firstOrNull()
        details = d
        _product.value = d?.oneTimePurchaseOfferDetails?.let { StoreProductInfo(it.formattedPrice, d.name) }
    }

    private suspend fun refreshPurchases() {
        val res = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build())
        if (res.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return
        handle(res.purchasesList.filter { productId in it.products }, fromFlow = false)
    }

    private suspend fun handle(purchases: List<Purchase>, fromFlow: Boolean): PurchaseResult {
        val purchased = purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        if (purchased != null) {
            if (!purchased.isAcknowledged) {
                // Ohne Bestätigung erstattet Google nach 3 Tagen automatisch.
                runCatching {
                    client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchased.purchaseToken).build())
                }
            }
            setState(PurchaseState.PURCHASED)
            return PurchaseResult.Purchased
        }
        if (pending) {
            setState(PurchaseState.PENDING)
            return PurchaseResult.Pending
        }
        if (!fromFlow) {
            // Früher gekauft, jetzt nicht mehr vorhanden → erstattet/widerrufen.
            setState(if (_state.value == PurchaseState.PURCHASED || _state.value == PurchaseState.REVOKED) PurchaseState.REVOKED else PurchaseState.NOT_PURCHASED)
        }
        return PurchaseResult.Failed("nothing")
    }

    override suspend fun purchasePro(activity: Activity): PurchaseResult {
        if (details == null) refresh()
        val d = details ?: return PurchaseResult.Failed("no-product")
        val deferred = CompletableDeferred<PurchaseResult>()
        pendingFlow = deferred
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build(),
        )).build()
        val launch = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
        if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
            pendingFlow = null
            return if (launch.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) PurchaseResult.Cancelled
            else PurchaseResult.Failed("billing-${launch.responseCode}")
        }
        return deferred.await().also { pendingFlow = null }
    }

    override suspend fun restore(): PurchaseResult {
        if (!connect()) return PurchaseResult.Failed("offline")
        refreshPurchases()
        return if (_state.value == PurchaseState.PURCHASED) PurchaseResult.Purchased else PurchaseResult.Failed("nothing-to-restore")
    }
}
