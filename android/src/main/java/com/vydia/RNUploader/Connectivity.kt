package com.vydia.RNUploader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_WIFI

enum class Connectivity {
  NoWifi, NoInternet, Ok;

  companion object {
    fun fetch(context: Context, wifiOnly: Boolean): Connectivity {
      val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      val network = manager.activeNetwork
      val capabilities = manager.getNetworkCapabilities(network)

      val hasInternet = capabilities?.hasCapability(NET_CAPABILITY_VALIDATED) == true

      // not wifiOnly, return early
      if (!wifiOnly) return if (hasInternet) Ok else NoInternet

      // handle wifiOnly
      return if (hasInternet && capabilities?.hasTransport(TRANSPORT_WIFI) == true)
        Ok
      else
        NoWifi // don't return NoInternet here, more direct to request to join wifi
    }
  }
}

