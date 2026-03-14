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
import org.json.JSONObject


class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var ipAddressText: TextView
    private lateinit var startServerButton: Button
    private lateinit var stopServerButton: Button
    private lateinit var scanQRButton: Button
    private lateinit var cameraButton: Button
    private lateinit var filesButton: Button



    companion object {
        private const val SERVER_PORT = 8080
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private val qrScanLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val pcIp = data.getStringExtra("pc_ip") ?: return@registerForActivityResult
        val pcName = data.getStringExtra("pc_name") ?: "Unknown PC"
        // Trust this IP immediately and tell the PC to connect
        notifyPcToConnect(pcIp)
        Toast.makeText(this, "Found $pcName ($pcIp) – check your PC!", Toast.LENGTH_LONG).show()
        }

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

        requestBatteryOptimizationExemption()
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
        startService(Intent(this, StreamBridgeService::class.java).apply {
            action = StreamBridgeService.ACTION_STOP
        })
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

    // ── QR → notify PC ─────────────────────────────────────────────────────────

    /**
     * After scanning the PC's QR code, open a TCP connection to port 8083 on the PC
     * and send a JSON payload containing:
     *   - the phone's IP  (so the PC knows where to connect)
     *   - the phone's TLS certificate (base64 DER) so the PC can pin to it
     *
     * Previously this just sent the raw IP string. Now it sends JSON so the cert
     * travels alongside the IP in the same single round-trip.
     */
    private fun notifyPcToConnect(pcIp: String) {
        Thread {
            try {
                // Gets the phone's IP
                val myIp        = getLocalIpAddress() ?: throw Exception("Could not get phone IP")
                val certManager = CertificateManager()
                val payload     = JSONObject().apply {
                    put("ip",          myIp)
                    put("cert",        certManager.getCertificateBase64())
                    put("fingerprint", certManager.getFingerprint())
                }.toString()

                // Connects to the computer on port 8083 and sends it our IP
                java.net.Socket(pcIp, 8083).use { socket ->
                    socket.getOutputStream().bufferedWriter().use { out ->
                        out.write(payload + "\n")
                        out.flush()
                    }
                }
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

    // ─────────── Helpers ───────────

    private fun isServiceRunning(): Boolean = StreamBridgeService.instance?.isRunning() == true

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

    // ─────────── Battery optimization ───────────

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
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