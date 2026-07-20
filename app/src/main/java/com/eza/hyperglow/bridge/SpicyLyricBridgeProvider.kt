package com.eza.hyperglow.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.eza.hyperglow.AppLog

class SpicyLyricBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.grantUriPermission(
            SPOTIFY_PACKAGE,
            BRIDGE_URI,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val host = context ?: return Bundle.EMPTY
        if (!CallerValidator.isSpotify(host)) {
            AppLog.w(TAG, "Rejected caller uid=${Binder.getCallingUid()}")
            return Bundle.EMPTY
        }
        when (method) {
            "publish" -> {
                val state = extras?.getBundle("state") ?: return Bundle.EMPTY
                val accepted = runCatching { SpicyBridgeStore.accept(state) }.getOrElse {
                    AppLog.w(TAG, "Rejected malformed provider state", it)
                    false
                }
                if (!accepted) AppLog.w(TAG, "Rejected stale or invalid provider state")
            }
            "clear" -> {
                val producerId = extras?.getString("producerId").orEmpty()
                val generation = extras?.getLong("generation", -1L) ?: -1L
                SpicyBridgeStore.clear(producerId, generation)
                SpicyBridgeDocumentStore.clear(producerId, generation)
            }
        }
        return Bundle.EMPTY
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "SpicyBridgeProvider"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private val BRIDGE_URI = Uri.parse("content://com.eza.hyperglow.spicybridge/session")
    }
}
