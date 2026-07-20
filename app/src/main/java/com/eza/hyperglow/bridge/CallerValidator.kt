package com.eza.hyperglow.bridge

import android.content.Context
import android.os.Binder

object CallerValidator {
    private const val SPOTIFY_PACKAGE = "com.spotify.music"
    private val verdicts = LinkedHashMap<Int, Boolean>()

    @Synchronized
    fun isSpotify(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        verdicts[uid]?.let { return it }
        if (verdicts.size >= 16) verdicts.clear()
        return context.packageManager.getPackagesForUid(uid).orEmpty()
            .contains(SPOTIFY_PACKAGE)
            .also { verdicts[uid] = it }
    }
}
