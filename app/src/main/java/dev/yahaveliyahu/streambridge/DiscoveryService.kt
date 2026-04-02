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

    var onPairingRequest: ((pcName: String, pcIp: String, callback: (Boolean) -> Unit) -> Unit)? = null

    /**
     * Called when a trusted PC auto-approves (no dialog needed).
     * The callback parameter must start the WebSocket server before returning,
     * so the response is only sent after the server is ready for the PC to connect.
     */
    var onAutoApproved: ((pcName: String, readyCallback: () -> Unit) -> Unit)? = null

    companion object {
        private const val SERVICE_TYPE = "_phonepclink._tcp."
        private const val SERVICE_NAME = "StreamBridge-android"
        private const val PAIRING_PORT = 8082
    }

    fun startDiscovery(phoneIp: String, phonePort: Int) {
        registerNsdService(phoneIp, phonePort)
        startPairingServer()
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
                val pcFingerprint = json.optString("fingerprint", "")

                // Get IP without the slash (e.g., "/192.168.1.5" -> "192.168.1.5")
                val rawIp = client.inetAddress.hostAddress
                val pcIp = rawIp?.replace("/", "") ?: "Unknown"

                Log.d(tag, "Request from $pcName at $pcIp (fingerprint: ${pcFingerprint.take(20)}…)")

                // CHECK TRUST: If this PC's fingerprint was saved before, auto-approve!
                if (pcFingerprint.isNotBlank() && TrustedPcStore.isTrusted(context, pcFingerprint)) {
                    val savedName = TrustedPcStore.getPcName(context, pcFingerprint)
                    Log.d(tag, "PC '$pcName' is trusted (saved as '$savedName'). Auto-approving.")
                    // Start the WebSocket server BEFORE sending approval so the PC
                    // can connect immediately when it receives the response.
                    onAutoApproved?.invoke(pcName) {
                        // Server is ready — now send the approval
                        try {
                            sendResponse(output, true)
                        } catch (e: Exception) {
                            Log.e(tag, "Error sending auto-approve response", e)
                        } finally {
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                    return@thread
                }

                // Unknown PC — ask user to approve or deny
                onPairingRequest?.invoke(pcName, pcIp) { approved ->
                    thread {
                        try {
                            // If user approves AND we have a fingerprint, save for next time
                            if (approved && pcFingerprint.isNotBlank()) {
                                TrustedPcStore.saveTrusted(context, pcFingerprint, pcName)
                            }
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

    /**
     * Sends the pairing response.
     *
     * When approved, we also include the phone's TLS certificate (base64 DER)
     * so Windows can pin to it for all subsequent encrypted connections.
     * The pairing channel itself is plain TCP on the local LAN — TOFU model,
     * same as SSH.  All actual data (messages, files) travels over TLS.
     */
    private fun sendResponse(output: java.io.BufferedWriter, approved: Boolean) {
        val certManager = CertificateManager()
        val response = JSONObject().apply {
            put("approved", approved)
            if (approved) {
                put("phone_name", android.os.Build.MODEL)
                // ── TLS cert for Windows to pin ───────────────────────────────
                put("cert", certManager.getCertificateBase64())
                put("fingerprint", certManager.getFingerprint())
            }
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