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
    private val TAG = "DiscoveryService"

    // List of IPs trusted via QR Scan
    private val trustedIps = mutableSetOf<String>()

    var onPairingRequest: ((pcName: String, pcIp: String, callback: (Boolean) -> Unit) -> Unit)? = null

    companion object {
        private const val SERVICE_TYPE = "_phonepclink._tcp"
        private const val SERVICE_NAME = "PhonePCLink"
        private const val PAIRING_PORT = 8082
    }

    fun startDiscovery(phoneIp: String, phonePort: Int) {
        registerNsdService(phoneIp, phonePort)
        startPairingServer()
    }

    fun addTrustedIp(ip: String) {
        trustedIps.add(ip)
        Log.d(TAG, "Added trusted IP: $ip")
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
            Log.e(TAG, "NSD Registration failed", e)
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
                        Log.e(TAG, "Socket accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing server error", e)
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

                Log.d(TAG, "Request from $pcName at $pcIp")

                // CHECK TRUST: If scanned via QR, approve immediately!
                if (trustedIps.contains(pcIp)) {
                    Log.d(TAG, "IP $pcIp is trusted. Auto-approving.")
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
                            Log.e(TAG, "Error sending response", e)
                        } finally {
                            try { client.close() } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Handle pairing error", e)
                try { client.close() } catch (e: Exception) {}
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
        try { pairingServer?.close() } catch (e: Exception) {}
        try { nsdManager?.unregisterService(registrationListener) } catch (e: Exception) {}
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
//    private val TAG = "DiscoveryService"
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
//                Log.d(TAG, "Service registered: ${serviceInfo.serviceName}")
//            }
//
//            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
//                Log.e(TAG, "Service registration failed: $errorCode")
//            }
//
//            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
//                Log.d(TAG, "Service unregistered")
//            }
//
//            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
//                Log.e(TAG, "Service unregistration failed: $errorCode")
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
//                Log.d(TAG, "Pairing server started on port $PAIRING_PORT")
//
//                while (isRunning) {
//                    try {
//                        val client = pairingServer?.accept()
//                        if (client != null) {
//                            handlePairingRequest(client)
//                        }
//                    } catch (e: Exception) {
//                        if (isRunning) {
//                            Log.e(TAG, "Error accepting client", e)
//                        }
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error starting pairing server", e)
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
//                Log.d(TAG, "Pairing request from $pcName ($pcIp)")
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
//                        Log.e(TAG, "Error sending response", e)
//                    } finally {
//                        client.close()
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error handling pairing request", e)
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
//            Log.e(TAG, "Error closing pairing server", e)
//        }
//
//        try {
//            nsdManager?.unregisterService(registrationListener)
//        } catch (e: Exception) {
//            Log.e(TAG, "Error unregistering NSD service", e)
//        }
//    }
//}
