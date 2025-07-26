package com.ethran.notable.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(private val context: Context) {
    private val TAG = "NetworkMonitor"
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val _isNetworkAvailable = MutableStateFlow(false)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()
    
    private val _networkType = MutableStateFlow(NetworkType.NONE)
    val networkType: StateFlow<NetworkType> = _networkType.asStateFlow()
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available: $network")
            updateNetworkStatus()
        }
        
        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost: $network")
            updateNetworkStatus()
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            Log.d(TAG, "Network capabilities changed: $network")
            updateNetworkStatus()
        }
    }
    
    init {
        updateNetworkStatus()
    }
    
    fun startMonitoring() {
        Log.d(TAG, "Starting network monitoring")
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        updateNetworkStatus()
    }
    
    fun stopMonitoring() {
        Log.d(TAG, "Stopping network monitoring")
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callback", e)
        }
    }
    
    private fun updateNetworkStatus() {
        val networkInfo = getCurrentNetworkInfo()
        _isNetworkAvailable.value = networkInfo.isAvailable
        _networkType.value = networkInfo.type
        
        Log.d(TAG, "Network status updated: available=${networkInfo.isAvailable}, type=${networkInfo.type}")
    }
    
    private fun getCurrentNetworkInfo(): NetworkInfo {
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        
        if (activeNetwork == null || networkCapabilities == null) {
            return NetworkInfo(false, NetworkType.NONE)
        }
        
        val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isAvailable = hasInternet && isValidated
        
        val type = when {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }
        
        return NetworkInfo(isAvailable, type)
    }
    
    /**
     * Check if network is available for sync operations
     * Returns true if network is available and has good connectivity
     */
    fun isNetworkSuitableForSync(): Boolean {
        val networkInfo = getCurrentNetworkInfo()
        return networkInfo.isAvailable && (networkInfo.type == NetworkType.WIFI || networkInfo.type == NetworkType.ETHERNET)
    }
    
    /**
     * Check if network is available but may be limited (cellular with potentially metered connection)
     */
    fun isNetworkLimited(): Boolean {
        val networkInfo = getCurrentNetworkInfo()
        return networkInfo.isAvailable && networkInfo.type == NetworkType.CELLULAR
    }
}

data class NetworkInfo(
    val isAvailable: Boolean,
    val type: NetworkType
)

enum class NetworkType {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER
}