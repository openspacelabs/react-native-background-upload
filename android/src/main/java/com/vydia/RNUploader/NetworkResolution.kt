package com.vydia.RNUploader

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build

fun observeNetwork(
  connectivityManager: ConnectivityManager,
  onNetworkChange: (network: Network?) -> Unit,
  onWifiOnlyNetworkChange: (network: Network?) -> Unit,
) {

  // Technically we can pick and choose the network with available internet connection using
  // NetworkRequest and ConnectivityManager.bindProcessToNetwork.
  // However, bindProcessToNetwork will cause react-native to unexpectedly
  // use the last bound network (instead of the default one) and other libraries
  // that use network connection to also behave unexpectedly, so this is the best option we have.
  connectivityManager.registerDefaultNetworkCallback(
    networkListener(connectivityManager) { network ->
      onNetworkChange(network)

      val capabilities = connectivityManager.getNetworkCapabilities(network)
      var wifiOnlyNetwork: Network? = network
      if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true)
        wifiOnlyNetwork = null
      if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
        wifiOnlyNetwork = null
      onWifiOnlyNetworkChange(wifiOnlyNetwork)
    }
  )
}


private fun networkListener(
  connectivityManager: ConnectivityManager,
  onChange: (network: Network?) -> Unit
): ConnectivityManager.NetworkCallback {
  var current: Network? = null
  fun setNetwork(network: Network?) {
    val new =
      if (networkCanBeUsed(network, connectivityManager)) network
      else null

    if (new == current) return
    current = new
    onChange(new)
  }

  return object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      setNetwork(network)
    }

    override fun onLosing(network: Network, maxMsToLive: Int) {
      setNetwork(null)
    }

    override fun onLost(network: Network) {
      setNetwork(null)
    }

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
      if (blocked) setNetwork(null) else setNetwork(network)
    }

    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
      setNetwork(network)
    }
  }

}


// Inspired by @react-native-community/net-info
private fun networkCanBeUsed(
  network: Network?,
  connectivityManager: ConnectivityManager
): Boolean {
  if (network == null) return false
  val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
  if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
  if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
  if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) return false
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) return false
  } else {
    // From @react-native-community/net-info
    // This may return null per API docs, and is deprecated, but for older APIs (< VERSION_CODES.P)
    // we need it to test for suspended internet
    val networkInfo = connectivityManager.getNetworkInfo(network) ?: return false
    if (networkInfo.detailedState != NetworkInfo.DetailedState.CONNECTED) return false
  }


  return true
}

