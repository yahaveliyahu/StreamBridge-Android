package dev.yahaveliyahu.streambridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class DiscoveryService(private val context: Context) {
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var pairingServer: ServerSocket? = null
    private var isRunning = false
    private val tag = "DiscoveryService"

    // List of IPs trusted via QR Scan
    private val trustedIps = mutableSetOf<String>()

    var onPairingRequest: ((pcName: String, pcIp: String, callback: (Boolean) -> Unit) -> Unit)? = null

    companion object {
        private const val SERVICE_TYPE = "_phonepclink._tcp."
        private const val SERVICE_NAME = "StreamBridge-android"
        private const val PAIRING_PORT = 8082
    }

    fun startDiscovery(phoneIp: String, phonePort: Int) {
        registerNsdService(phoneIp, phonePort)
        startPairingServer()
    }

    fun addTrustedIp(ip: String) {
        trustedIps.add(ip)
        Log.d(tag, "Added trusted IP: $ip")
    }

    private fun registerNsdService(phoneIp: String, phonePort: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = phonePort // Advertise the HTTP port (8080)
            setAttribute("ip", phoneIp)
        }

        nsdManager = (context.getSystemService(Context.NSD_SERVICE) as NsdManager)

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(tag, "NSD Registration failed", e)
        }
    }

    private fun startPairingServer() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                pairingServer = ServerSocket(PAIRING_PORT)
                while (isRunning) {
                    try {
                        val client = pairingServer?.accept()
                        if (client != null) handlePairingRequest(client)
                    } catch (e: Exception) {
                        Log.e(tag, "Socket accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Pairing server error", e)
            }
        }
    }

    private fun handlePairingRequest(client: Socket) {
        thread {
            try {
                val input = client.getInputStream().bufferedReader()
                val output = client.getOutputStream().bufferedWriter()

                val request = input.readLine() ?: return@thread
                val json = JSONObject(request)
                val pcName = json.optString("name", "Unknown PC")

                // Get IP without the slash (e.g., "/192.168.1.5" -> "192.168.1.5")
                val rawIp = client.inetAddress.hostAddress
                val pcIp = rawIp?.replace("/", "") ?: "Unknown"

                Log.d(tag, "Request from $pcName at $pcIp")

                // CHECK TRUST: If scanned via QR, approve immediately!
                if (trustedIps.contains(pcIp)) {
                    Log.d(tag, "IP $pcIp is trusted. Auto-approving.")
                    sendResponse(output, true)
                    client.close()
                    // Notify UI just for info
                    onPairingRequest?.invoke(pcName, pcIp) { /* No-op, already handled */ }
                    return@thread
                }

                // If not trusted, ask user (this is where timeout happened before)
                onPairingRequest?.invoke(pcName, pcIp) { approved ->
                    thread {
                        try {
                            sendResponse(output, approved)
                        } catch (e: Exception) {
                            Log.e(tag, "Error sending response", e)
                        } finally {
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Handle pairing error", e)
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun sendResponse(output: java.io.BufferedWriter, approved: Boolean) {
        val response = JSONObject().apply {
            put("approved", approved)
            if (approved) put("phone_name", android.os.Build.MODEL)
        }
        output.write(response.toString() + "\n")
        output.flush()
    }

    fun stopDiscovery() {
        isRunning = false
        try { pairingServer?.close() } catch (_: Exception) {}
        try { nsdManager?.unregisterService(registrationListener) } catch (_: Exception) {}
    }
}





//package dev.yahaveliyahu.streambridge
//
//import android.content.Context
//import android.net.nsd.NsdManager
//import android.net.nsd.NsdServiceInfo
//import android.util.Log
//import org.json.JSONObject
//import java.net.ServerSocket
//import java.net.Socket
//import kotlin.concurrent.thread
//
//class DiscoveryService(private val context: Context) {
//    private var nsdManager: NsdManager? = null
//    private var registrationListener: NsdManager.RegistrationListener? = null
//    private var pairingServer: ServerSocket? = null
//    private var isRunning = false
//    private val tag = "DiscoveryService"
//
//    var onPairingRequest: ((pcName: String, pcIp: String, callback: (Boolean) -> Unit) -> Unit)? = null
//
//    companion object {
//        private const val SERVICE_TYPE = "_phonepclink._tcp"
//        private const val SERVICE_NAME = "PhonePCLink"
//        private const val PAIRING_PORT = 8082
//    }
//
//    fun startDiscovery(phoneIp: String, phonePort: Int) {
//        // Register NSD service for discovery
//        registerNsdService(phoneIp, phonePort)
//
//        // Start pairing server
//        startPairingServer()
//    }
//
//    private fun registerNsdService(phoneIp: String, phonePort: Int) {
//        val serviceInfo = NsdServiceInfo().apply {
//            serviceName = SERVICE_NAME
//            serviceType = SERVICE_TYPE
//            port = phonePort
//            setAttribute("ip", phoneIp)
//        }
//
//        nsdManager = (context.getSystemService(Context.NSD_SERVICE) as NsdManager)
//
//        registrationListener = object : NsdManager.RegistrationListener {
//            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
//                Log.d(tag, "Service registered: ${serviceInfo.serviceName}")
//            }
//
//            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
//                Log.e(tag, "Service registration failed: $errorCode")
//            }
//
//            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
//                Log.d(tag, "Service unregistered")
//            }
//
//            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
//                Log.e(tag, "Service unregistration failed: $errorCode")
//            }
//        }
//
//        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
//    }
//
//    private fun startPairingServer() {
//        if (isRunning) return
//
//        isRunning = true
//        thread {
//            try {
//                pairingServer = ServerSocket(PAIRING_PORT)
//                Log.d(tag, "Pairing server started on port $PAIRING_PORT")
//
//                while (isRunning) {
//                    try {
//                        val client = pairingServer?.accept()
//                        if (client != null) {
//                            handlePairingRequest(client)
//                        }
//                    } catch (e: Exception) {
//                        if (isRunning) {
//                            Log.e(tag, "Error accepting client", e)
//                        }
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(tag, "Error starting pairing server", e)
//            }
//        }
//    }
//
//    private fun handlePairingRequest(client: Socket) {
//        thread {
//            try {
//                val input = client.getInputStream().bufferedReader()
//                val output = client.getOutputStream().bufferedWriter()
//
//                val request = input.readLine()
//                val json = JSONObject(request)
//                val pcName = json.getString("name")
//                val pcIp = client.inetAddress.hostAddress ?: "Unknown"
//
//                Log.d(tag, "Pairing request from $pcName ($pcIp)")
//
//                // Ask user for approval
//                onPairingRequest?.invoke(pcName, pcIp) { approved ->
//                    try {
//                        val response = JSONObject().apply {
//                            put("approved", approved)
//                            if (approved) {
//                                put("phone_name", android.os.Build.MODEL)
//                            }
//                        }
//                        output.write(response.toString() + "\n")
//                        output.flush()
//                    } catch (e: Exception) {
//                        Log.e(tag, "Error sending response", e)
//                    } finally {
//                        client.close()
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(tag, "Error handling pairing request", e)
//                client.close()
//            }
//        }
//    }
//
//    fun stopDiscovery() {
//        isRunning = false
//
//        try {
//            pairingServer?.close()
//        } catch (e: Exception) {
//            Log.e(tag, "Error closing pairing server", e)
//        }
//
//        try {
//            nsdManager?.unregisterService(registrationListener)
//        } catch (e: Exception) {
//            Log.e(tag, "Error unregistering NSD service", e)
//        }
//    }
//}
