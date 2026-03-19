package dev.yahaveliyahu.streambridge


import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
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
        private const val PREFS_NAME = "permission_prefs"

        // Returns true if this permission was previously denied by the user
        fun wasPermissionDenied(context: Context, permission: String): Boolean =
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(permission, false)

        // Marks a permission as denied so we remember it across kills
        fun markPermissionDenied(context: Context, permission: String) =
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit { putBoolean(permission, true) }


        // Clears the denied flag once the user grants the permission
        fun clearPermissionDenied(context: Context, permission: String) =
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit { remove(permission) }
    }


    // ─────────── flags ───────────

    /**
     *  True while the system dialog is on screen — prevents onResume from
     *  immediately redirecting to PermissionRationaleActivity before we even
     *  know the result
     */
    private var waitingForPermissionResult = false

    /**
     * Reset to false on the very next onResume call (which is the spurious
     * resume caused by the dialog dismissing), so that when the user later
     * returns from PermissionRationaleActivity, onResume works normally.
     */
    private var launchingRationale = false

    private var waitingForBatteryResult = false

    /**
     * True while our own battery rationale AlertDialog is visible.
     * Blocks onResume from re-launching the system battery dialog on top
     * of our explanation dialog when the user dismisses the system dialog.
     */
    private var showingBatteryRationale = false

    // ─────────── Permission launcher ───────────

    /**
     * Shows the system dialog for ONE permission directly from MainActivity.
     * - Granted → onResume re-checks; if more are missing it launches the
     *             next dialog; otherwise the app proceeds normally.
     * - Denied  → we send the user to PermissionRationaleActivity, which
     *             explains why the permission is needed and offers a Settings
     *             button. It stays there until all permissions are granted.
     */
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            waitingForPermissionResult = false

            val permission = PermissionRationaleActivity.firstMissingPermission(this)?.permission
                ?: PermissionRationaleActivity.requiredPermissions()
                    .firstOrNull { wasPermissionDenied(this, it.permission) }?.permission

            if (!isGranted) {
                // Remember this denial so that after a kill (relaunch) we go
                // straight to PermissionRationaleActivity instead of asking again
                if (permission != null) markPermissionDenied(this, permission)
                // Denied → go to rationale screen
                // Set launchingRationale BEFORE startActivity so the
                // spurious onResume that fires right after is blocked
                launchingRationale = true
                startActivity(Intent(this, PermissionRationaleActivity::class.java))
            } else {
                // Granted — clear any stored denial for this permission
                if (permission != null) clearPermissionDenied(this, permission)
                // Permission was just granted — check if any more are missing
                val nextMissing = PermissionRationaleActivity.firstMissingPermission(this)
                if (nextMissing != null) {
                    // Still more permissions to ask — request the next one.
                    handlePermissionsOnStart()
                } else {
                    // All permissions granted — NOW ask for battery optimization.
                    requestBatteryIfNeeded()
                }
            }
        }

    // ─────────── Battery optimization launcher ───────────

    private val batteryOptimizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            waitingForBatteryResult = false
            if (!isBatteryOptimizationIgnored()) {
                // User declined — show our explanation dialog.
                // Set the flag BEFORE showing the dialog so that the spurious
                // onResume caused by the system dialog dismissing does not
                // immediately re-launch the system dialog on top of ours
                showingBatteryRationale = true
                showBatteryRationaleDialog()
            } else {
                finishSetup()
            }
        }

    // ─────────────────────────────────────────────────────────────────────────

    private val qrScanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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

        startServerButton.setOnClickListener { startServer() }
        stopServerButton.setOnClickListener { stopServer() }

        scanQRButton.setOnClickListener {
            if (isServiceRunning()) qrScanLauncher.launch(
                Intent(
                    this,
                    QRScannerActivity::class.java
                )
            )
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

        // Check whether a permission was previously denied before asking
        handlePermissionsOnStart()

        updateUI()
    }

    override fun onResume() {
        super.onResume()

        // While the system dialog is on screen this activity is paused then
        // resumed — ignore that cycle, the launcher callback handles the result
        if (waitingForPermissionResult) return
        if (waitingForBatteryResult) return
        if (showingBatteryRationale) return

        // Ignore the spurious resume that fires in the window between the
        // launcher callback and PermissionRationaleActivity appearing.
        // Reset the flag so the NEXT real resume (returning from
        // PermissionRationaleActivity) works normally
        if (launchingRationale) {
            launchingRationale = false
            return
        }

        // Returning from PermissionRationaleActivity after the user granted
        // a permission manually in Settings
        val missing = PermissionRationaleActivity.firstMissingPermission(this)
        if (missing != null) {
            handlePermissionsOnStart()
            return
        }

        // All permissions are granted. If battery still needs to be asked,
        // do it now (covers the case where user returns from
        // PermissionRationaleActivity having granted the last permission)
        if (!isBatteryOptimizationIgnored()) {
            requestBatteryIfNeeded()
            return
        }
        finishSetup()
    }

    override fun onPause() {
        super.onPause()
        // Clear so the service auto-denies pairings when there is no UI
        StreamBridgeService.onPairingRequestStatic = null
    }

    // ─────────── Permission helpers ───────────

    /**
     * If any required permission was previously denied (persisted in prefs),
     * go straight to PermissionRationaleActivity — don't show the system
     * dialog again (the user already said no once).
     *
     * If no denial is on record but a permission is missing, show the system
     * dialog — this is the user's first encounter with this permission.
     */
    private fun handlePermissionsOnStart() {
        val missing = PermissionRationaleActivity.firstMissingPermission(this) ?: run {
            // No permissions missing — check battery
            requestBatteryIfNeeded()
            return
        }

        if (wasPermissionDenied(this, missing.permission)) {
            // Already denied before — skip the dialog, go to rationale screen
            launchingRationale = true
            startActivity(Intent(this, PermissionRationaleActivity::class.java))
        } else {
            // First time asking — show the system dialog
            waitingForPermissionResult = true
            requestPermissionLauncher.launch(missing.permission)
        }
    }

    // ─────────── Battery optimization ───────────

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryIfNeeded() {
        if (!isBatteryOptimizationIgnored()) {
            requestBatteryOptimizationExemption()
        } else {
            finishSetup()
        }
    }

    @Suppress("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        waitingForBatteryResult = true
        batteryOptimizationLauncher.launch(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:$packageName".toUri()
            )
        )
    }

    private fun showBatteryRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Keep StreamBridge running in the background")
            .setMessage(
                "Without this permission, Android may stop the StreamBridge server " +
                        "whenever you leave the app — disconnecting your PC mid-session.\n\n" +
                        "Tap \"Allow\" on the next screen to keep the server always running."
            )
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                requestBatteryOptimizationExemption()
            }
            .setNegativeButton("No thanks") { _, _ ->
                finishSetup()
            }
            .show()
    }

    // ─────────── Final setup ───────────

    private fun finishSetup() {
        displayIPAddress()
        setupPairingCallback()
        updateUI()
    }

    // ─────────── Server start / stop ───────────

    private fun startServer() {
        val svcIntent = Intent(this, StreamBridgeService::class.java).apply {
            action = StreamBridgeService.ACTION_START
        }
        startForegroundService(svcIntent)
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
                AlertDialog.Builder(this)
                    .setTitle("Connection Request")
                    .setMessage("$pcName ($pcIp) wants to connect to your phone.\n\nAllow connection?")
                    .setPositiveButton("Connect") { _, _ ->
                        callback(true)  // Gives permission to the computer
                        Toast.makeText(this, "Connected to $pcName", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Deny") { _, _ ->
                        callback(false)
                    } // Rejects the computer
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
     */
    private fun notifyPcToConnect(pcIp: String) {
        Thread {
            try {
                // Gets the phone's IP
                val myIp = getLocalIpAddress() ?: throw Exception("Could not get phone IP")
                val certManager = CertificateManager()
                val payload = JSONObject().apply {
                    put("ip", myIp)
                    put("cert", certManager.getCertificateBase64())
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
                    Toast.makeText(this, "Signal sent to PC! Check your screen.", Toast.LENGTH_LONG)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Failed to reach PC: ${e.message}", Toast.LENGTH_LONG)
                        .show()
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
            stopServerButton.isEnabled = true
            scanQRButton.isEnabled = true
            cameraButton.isEnabled = true
            filesButton.isEnabled = true
        } else {
            statusText.text = getString(R.string.status_stopped)
            statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            startServerButton.isEnabled = true
            stopServerButton.isEnabled = false
            scanQRButton.isEnabled = false
            cameraButton.isEnabled = false
            filesButton.isEnabled = false
        }
    }
}