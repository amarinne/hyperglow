package com.eza.hyperglow.root

/**
 * Identifies a class loader in a field log.
 *
 * Every hook family installs once per loader a host builds, and the first loaders a host builds
 * generally cannot see a plugin dex yet — the AOD plugin is attached after the process starts. A
 * "seam unresolved" line on its own therefore cannot say whether the real loader resolved the member
 * later or the member is genuinely gone. The tag makes the two cases distinguishable in one log,
 * without a device session.
 */
internal fun loaderTag(classLoader: ClassLoader): String =
    classLoader.javaClass.simpleName + "@" + System.identityHashCode(classLoader).toString(16)
