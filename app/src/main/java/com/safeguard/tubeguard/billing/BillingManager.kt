package com.safeguard.tubeguard.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.safeguard.tubeguard.util.PrefsManager
import kotlinx.coroutines.*

class BillingManager(
    private val context: Context,
    private val onPurchaseComplete: (() -> Unit)? = null
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_ID = "tubeguard_premium"
    }

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun startConnection() {
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing client connected")
                    queryProduct()
                    queryExistingPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
            }
        })
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

        billingClient?.queryProductDetailsAsync(params) { result, productDetailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                productDetails = productDetailsList[0]
                Log.i(TAG, "Product loaded: ${productDetails?.title}")
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
