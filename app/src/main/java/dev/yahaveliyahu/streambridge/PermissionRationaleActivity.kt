package dev.yahaveliyahu.streambridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class PermissionRationaleActivity : AppCompatActivity() {

    companion object {
        data class PermissionInfo(
            val permission:  String,
            val title:       String,
            val description: String,
            val step2:       String
        )

        fun requiredPermissions(): List<PermissionInfo> {
            val list = mutableListOf(
                PermissionInfo(
                    permission  = Manifest.permission.CAMERA,
                    title       = "StreamBridge needs access to your Camera",
                    description = "Camera access is needed to stream live video from your phone to your PC.",
                    step2       = "2. Tap Camera"
                ),
                PermissionInfo(
                    permission  = Manifest.permission.READ_CONTACTS,
                    title       = "StreamBridge needs access to your Contacts",
                    description = "Contacts access allows you to share contacts directly from your phone to your PC.",
                    step2       = "2. Tap Contacts"
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list += listOf(
                    PermissionInfo(
                        permission  = Manifest.permission.READ_MEDIA_IMAGES,
                        title       = "StreamBridge needs access to your Photos",
                        description = "Photo access is needed so you can browse and send images to your PC.",
                        step2       = "2. Tap Photos & Videos"
                    ),
                    PermissionInfo(
                        permission  = Manifest.permission.READ_MEDIA_VIDEO,
                        title       = "StreamBridge needs access to your Videos",
                        description = "Video access is needed so you can browse and send video files to your PC.",
                        step2       = "2. Tap Photos & Videos"
                    ),
                    PermissionInfo(
                        permission  = Manifest.permission.READ_MEDIA_AUDIO,
                        title       = "StreamBridge needs access to your Audio files",
                        description = "Audio access is needed so you can browse and send music or voice files to your PC.",
                        step2       = "2. Tap Music & Audio"
                    ),
                    PermissionInfo(
                        permission  = Manifest.permission.POST_NOTIFICATIONS,
                        title       = "StreamBridge needs permission to show notifications",
                        description = "Notification permission keeps the server running in the background. Without it, the server will stop when you leave the app.",
                        step2       = "2. Tap Notifications"
                    )
                )
            } else {
                list += PermissionInfo(
                    permission  = Manifest.permission.READ_EXTERNAL_STORAGE,
                    title       = "StreamBridge needs access to your Files",
                    description = "Storage access is needed so you can browse and send files to your PC.",
                    step2       = "2. Tap Storage"
                )
            }
            return list
        }

        fun firstMissingPermission(activity: AppCompatActivity): PermissionInfo? =
            requiredPermissions().firstOrNull {
                ContextCompat.checkSelfPermission(activity, it.permission) !=
                        PackageManager.PERMISSION_GRANTED
            }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var currentPermission: PermissionInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missing = firstMissingPermission(this)
        if (missing == null) {
            finish()
            return
        }
        currentPermission = missing

        setContentView(R.layout.activity_permission_rationale)
        bindViews(currentPermission)

        // Block back — the user must grant the permission to proceed.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* must grant permission */ }
        })
    }

    override fun onResume() {
        super.onResume()
        // The user may have just returned from the system Settings screen.
        val currentStillMissing = ContextCompat.checkSelfPermission(this, currentPermission.permission) !=
                PackageManager.PERMISSION_GRANTED

        if (!currentStillMissing) {
            // The current permission was just granted.
            // Clear its denial record and finish back to MainActivity — even if
            // other permissions are still missing. MainActivity will show the
            // system dialog for the next one, so the user is never taken straight
            // to a rationale screen without seeing the system dialog first.
            MainActivity.clearPermissionDenied(this, currentPermission.permission)
            finish()
        }
        // If the current permission is still missing, stay on this screen
    }

    // ─────────── Helpers ───────────

    private fun bindViews(info: PermissionInfo) {
        findViewById<TextView>(R.id.permissionTitle).text = info.title
        findViewById<TextView>(R.id.permissionDescription).text = info.description
        findViewById<TextView>(R.id.step2Text).text = info.step2

        findViewById<Button>(R.id.goToSettingsButton).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }
    }
}