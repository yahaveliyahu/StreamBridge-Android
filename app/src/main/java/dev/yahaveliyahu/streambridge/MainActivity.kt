package dev.yahaveliyahu.streambridge


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var ipAddressText: TextView
    private lateinit var startServerButton: Button
    private lateinit var stopServerButton: Button
    private lateinit var scanQRButton: Button
    private lateinit var cameraButton: Button
    private lateinit var filesButton: Button


    // Server Administrator
//    private lateinit var serverManager: ServerManager
//    private lateinit var discoveryService: DiscoveryService

//    private var nsdManager: NsdManager? = null
//    private var registrationListener: NsdManager.RegistrationListener? = null

    companion object {
        private const val SERVER_PORT = 8080
        private const val PERMISSION_REQUEST_CODE = 100
//        private const val SERVICE_NAME = "StreamBridge"
//        private const val SERVICE_TYPE = "_phonepclink._tcp."
    }

//    private val QR_SCAN_REQUEST_CODE = 101

    private val qrScanLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult

            val data = result.data ?: return@registerForActivityResult
            val pcIp = data.getStringExtra("pc_ip") ?: return@registerForActivityResult
            val pcName = data.getStringExtra("pc_name") ?: "Unknown PC"

            // Trust this IP immediately and tell the PC to connect
            StreamBridgeService.instance?.let { /* service already owns discoveryService */ }
            notifyPcToConnect(pcIp)
            Toast.makeText(this, "Found $pcName ($pcIp) – check your PC!", Toast.LENGTH_LONG).show()
        }

//            Toast.makeText(this, "Connected to $pcName at $pcIp", Toast.LENGTH_SHORT).show()
//
//            discoveryService.addTrustedIp(pcIp)
//            notifyPcToConnect(pcIp)
//
//            Toast.makeText(this, "Found PC: $pcName ($pcIp)\nNow click Connect on PC!", Toast.LENGTH_LONG).show()
//        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        ipAddressText = findViewById(R.id.ipAddressText)
        startServerButton = findViewById(R.id.startServerButton)
        stopServerButton = findViewById(R.id.stopServerButton)
        scanQRButton = findViewById(R.id.scanQRButton)
        cameraButton = findViewById(R.id.cameraButton)
        filesButton = findViewById(R.id.filesButton)

        // Request permissions
        requestPermissions()
        // Display IP address
        displayIPAddress()

        startServerButton.setOnClickListener {startServer()}
        stopServerButton.setOnClickListener {stopServer()}

        // Administrators boot
//        serverManager = ServerManager(this)
//        discoveryService = DiscoveryService(this)
//        nsdManager = getSystemService(NSD_SERVICE) as NsdManager

        // Request permissions and display IP
        // Setup discovery service callbacks
//        setupDiscoveryCallbacks()

        scanQRButton.setOnClickListener {
            if (isServiceRunning()) qrScanLauncher.launch(Intent(this, QRScannerActivity::class.java))
            else Toast.makeText(this, "Please start server first", Toast.LENGTH_SHORT).show()
        }

        cameraButton.setOnClickListener {
            if (isServiceRunning()) startActivity(Intent(this, CameraActivity::class.java))
            else Toast.makeText(this, "Please start server first", Toast.LENGTH_SHORT).show()
        }

        filesButton.setOnClickListener {
            if (isServiceRunning()) startActivity(Intent(this, FileBrowserActivity::class.java))
            else Toast.makeText(this, "Please start server first", Toast.LENGTH_SHORT).show()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        // Register pairing callback every time we come to foreground so the
        // service can show the AlertDialog even after returning from another app.
        setupPairingCallback()
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        // Clear so the service auto-denies pairings when there is no UI
        StreamBridgeService.onPairingRequestStatic = null
    }

    // ─────────── Server start / stop ───────────

    private fun startServer() {
        val svcIntent = Intent(this, StreamBridgeService::class.java).apply {
            action = StreamBridgeService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }
        // Give the service a moment to bind then refresh UI
        startServerButton.postDelayed({ updateUI() }, 500)
        Toast.makeText(this, "Server started", Toast.LENGTH_SHORT).show()
        displayIPAddress()
    }

    private fun stopServer() {
        val svcIntent = Intent(this, StreamBridgeService::class.java).apply {
            action = StreamBridgeService.ACTION_STOP
        }
        startService(svcIntent)
        updateUI()
        Toast.makeText(this, "Server stopped", Toast.LENGTH_SHORT).show()
    }

    // ─────────── Pairing dialog ───────────


    private fun setupPairingCallback() {
        StreamBridgeService.onPairingRequestStatic = { pcName, pcIp, callback ->
            runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Connection Request")
                    .setMessage("$pcName ($pcIp) wants to connect to your phone.\n\nAllow connection?")
                    .setPositiveButton("Connect") { _, _ ->
                        callback(true)  // Gives permission to the computer
                        Toast.makeText(this, "Connected to $pcName", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Deny") { _, _ ->
                        callback(false) } // Rejects the computer
                    .setCancelable(false)
                    .show()
            }
        }
    }

    // ─────────── Helpers ───────────

    private fun isServiceRunning(): Boolean =
        StreamBridgeService.instance?.isRunning() == true

    private fun displayIPAddress() {
        val ip = getLocalIpAddress() ?: "Unknown"
        ipAddressText.text = getString(R.string.ip_display, ip, SERVER_PORT)
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            println("Failed to get local IP: ${e.message}")
            null
        }
    }

    // A function that sends the phone's IP to the computer
    private fun notifyPcToConnect(pcIp: String) {
        Thread {
            try {
                // Gets the phone's IP
                val myIp = getLocalIpAddress() ?: throw Exception("Could not get phone IP")

                // Connects to the computer on port 8083 and sends it our IP
                java.net.Socket(pcIp, 8083).use { socket ->
                    socket.getOutputStream().bufferedWriter().use { output ->
                        output.write("$myIp\n")
                        output.flush()
                    }
                }
//                val socket = java.net.Socket(pcIp, 8083)
//                val output = socket.getOutputStream().bufferedWriter()
//
//                output.write(myIp + "\n") // Sending the IP
//                output.flush()
//                socket.close()

                runOnUiThread {
                    Toast.makeText(this, "Signal sent to PC! Check your screen.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Failed to reach PC: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun updateUI() {
        val running = isServiceRunning()
        if (running) {
            statusText.text = getString(R.string.status_running)
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            startServerButton.isEnabled = false
            stopServerButton.isEnabled  = true
            scanQRButton.isEnabled      = true
            cameraButton.isEnabled      = true
            filesButton.isEnabled       = true
        } else {
            statusText.text = getString(R.string.status_stopped)
            statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            startServerButton.isEnabled = true
            stopServerButton.isEnabled  = false
            scanQRButton.isEnabled      = false
            cameraButton.isEnabled      = false
            filesButton.isEnabled       = false
        }
    }

    // ─────────── Permissions ───────────

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
}










//    private fun registerService() {
//        // Preparing the information for transmission
//        val serviceInfo = NsdServiceInfo().apply {
//            serviceName = SERVICE_NAME
//            serviceType = SERVICE_TYPE
//            port = SERVER_PORT
//        }
//
//        // The listener that reports whether we were able to register online
//        registrationListener = object : NsdManager.RegistrationListener {
//            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
//                // The phone is now transmitting
//                Log.d("StreamBridge", "Service Registered: ${serviceInfo.serviceName}")
//            }
//
//            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
//                Log.e("StreamBridge", "Registration Failed: $errorCode")
//            }
//            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
//            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
//        }
//
//        // Performing the actual registration
//        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
//    }
//
//    private fun unregisterService() {
//        registrationListener?.let {
//            try {
//                nsdManager?.unregisterService(it)
//                registrationListener = null
//            } catch (e: Exception) {e.printStackTrace()}
//        }
//    }






//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
//
//        if (requestCode == QR_SCAN_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
//            val pcIp = data.getStringExtra("pc_ip") ?: return
//            val pcName = data.getStringExtra("pc_name") ?: "Unknown PC"
//
//            Toast.makeText(this, "Connected to $pcName at $pcIp", Toast.LENGTH_SHORT).show()
//            if (pcIp != null) {
//                Toast.makeText(this, "QR Scanned! Sending signal...", Toast.LENGTH_SHORT).show()
//                discoveryService.addTrustedIp(pcIp)
//                notifyPcToConnect(pcIp)
//                Toast.makeText(this, "Found PC: $pcName ($pcIp)\nNow click Connect on PC!", Toast.LENGTH_LONG).show()
//            }
//        }
//    }



//    override fun onDestroy() {
//        super.onDestroy()
//        //stopServer() // This shuts down both the server and the broadcast (NSD). Removed to prevent disconnects when switching applications
//
////        serverManager.stopServer()
////        discoveryService.stopDiscovery()
//    }
//}