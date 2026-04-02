package dev.yahaveliyahu.streambridge

import android.content.Context
import android.util.Log
import androidx.core.content.edit


/**
 * Remembers which PCs have been approved by the user so they can
 * reconnect without showing the pairing dialog again.
 *
 * Each PC is identified by the SHA-256 fingerprint of its public key,
 * which is generated once on first run and never changes — even if the
 * PC's IP address changes.
 *
 * Storage: SharedPreferences keyed by fingerprint, value = PC name.
 */
object TrustedPcStore {

    private const val PREFS_NAME = "trusted_pcs"
    private const val TAG = "TrustedPcStore"

    /** Returns true if the PC with this fingerprint was previously approved. */
    fun isTrusted(context: Context, fingerprint: String): Boolean {
        val trusted = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .contains(fingerprint)
        if (trusted) Log.d(TAG, "PC fingerprint recognised — auto-approving")
        return trusted
    }

    /** Saves a PC fingerprint after the user approves pairing (or after a successful HANDSHAKE). */
    fun saveTrusted(context: Context, fingerprint: String, pcName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(fingerprint, pcName) }
        Log.d(TAG, "Saved trusted PC: $pcName ($fingerprint)")
    }

    /** Returns the saved name for a trusted PC, or null if unknown. */
    fun getPcName(context: Context, fingerprint: String): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(fingerprint, null)
    }
}