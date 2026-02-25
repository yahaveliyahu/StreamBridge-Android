package dev.yahaveliyahu.streambridge

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter

object NetworkUtils {
    fun getWifiIp(ctx: Context): String {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wifi.connectionInfo.ipAddress)
    }
}
