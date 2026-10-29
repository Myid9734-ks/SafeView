package com.safeguard.tubeguard.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.safeguard.tubeguard.util.PrefsManager
import kotlinx.coroutines.*

class BillingManager(
    private val context: Context,
    private val onPurchaseComplete: (() -> Unit)? = null,
    private val onProductReady: ((price: String?) -> Unit)? = null,
    private val onError: ((message: String) -> Unit)? = null
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_ID = "tubeguard_premium"
        private const val MAX_RETRY_COUNT = 3
    }

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var retryCount = 0

    fun startConnection() {
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()

        connectBillingClient()
    }

    private fun connectBillingClient() {
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing client connected")
                    retryCount = 0
                    queryProduct()
                    queryExistingPurchases()
                } else {
                    val msg = "Billing setup failed: code=${result.responseCode}, ${result.debugMessage}"
                    Log.e(TAG, msg)
                    onError?.invoke(msg)
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
                onError?.invoke("Billing service disconnected, retrying...")
                retryConnection()
            }
        })
    }

    private fun retryConnection() {
        if (retryCount < MAX_RETRY_COUNT) {
            retryCount++
            val delayMs = (1000L * retryCount)
            Log.i(TAG, "Retrying billing connection ($retryCount/$MAX_RETRY_COUNT)")
            scope.launch {
                delay(delayMs)
                connectBillingClient()
            }
        }
    }

    private fun queryProduct() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient?.queryProductDetailsAsync(params) { result, queryProductDetailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                if (queryProductDetailsResult.productDetailsList.isNotEmpty()) {
                    productDetails = queryProductDetailsResult.productDetailsList[0]
                    Log.i(TAG, "Product loaded: ${productDetails?.title}")
                    onProductReady?.invoke(getFormattedPrice())
                } else {
                    val msg = "Product '$PRODUCT_ID' not found in Play Console"
                    Log.e(TAG, msg)
                    onError?.invoke(msg)
                }
            } else {
                val msg = "Product query failed: code=${result.responseCode}, ${result.debugMessage}"
                Log.e(TAG, msg)
                onError?.invoke(msg)
            }
        }
    }

    fun queryExistingPurchases() {
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                for (purchase in purchases) {
                    if (purchase.products.contains(PRODUCT_ID) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        handlePurchase(purchase)
                        return@queryPurchasesAsync
                    }
                }
            }
        }
    }

    fun launchPurchaseFlow(activity: Activity): Boolean {
        val details = productDetails ?: return false
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        billingClient?.launchBillingFlow(activity, flowParams)
        return true
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        handlePurchase(purchase)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "Purchase cancelled by user")
            }
            else -> {
                Log.w(TAG, "Purchase error: ${result.responseCode}")
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        // Acknowledge the purchase if not already
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            scope.launch {
                billingClient?.acknowledgePurchase(params) { result ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.i(TAG, "Purchase acknowledged")
                    }
                }
            }
        }

        // Save purchase state locally
        val prefs = PrefsManager.getInstance(context)
        prefs.isPremium = true
        prefs.purchaseToken = purchase.purchaseToken
        Log.i(TAG, "Premium unlocked!")
        onPurchaseComplete?.invoke()
    }

    fun getFormattedPrice(): String? {
        return productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
    }

    fun isReady(): Boolean = productDetails != null

    fun destroy() {
        scope.cancel()
        billingClient?.endConnection()
        billingClient = null
    }
}
