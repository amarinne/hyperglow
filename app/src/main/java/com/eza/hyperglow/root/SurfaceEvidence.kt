package com.eza.hyperglow.root

import java.util.Collections

/**
 * Once-per-process surface facts on the always-on bootstrap channel.
 *
 * "Did the AOD view and the lockscreen panel actually take a lyric surface on this ROM" is the
 * first question a HyperOS 4 report has to answer, and the attach lines themselves are
 * diagnostic-gated: they only exist if logging was already enabled when the surface attached. These
 * stages do not depend on bridge-delivered configuration and are written unconditionally, so an
 * ordinary report answers the question without a reproduction window. Each name reports once per
 * process.
 */
internal object SurfaceEvidence {
    private val reported = Collections.synchronizedSet(mutableSetOf<String>())

    fun once(name: String) {
        if (!reported.add(name)) return
        HookLogger.bootstrap(TAG, name)
    }

    private const val TAG = "SurfaceEvidence"
}
