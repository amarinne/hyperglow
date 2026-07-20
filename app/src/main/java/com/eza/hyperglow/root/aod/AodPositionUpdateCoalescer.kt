package com.eza.hyperglow.root.aod

internal data class AodPositionUpdate(
    val generation: Long,
    val translationX: Float,
    val translationY: Float,
    val safeBottom: Int? = null,
    val clockTop: Int? = null,
    val clockBottom: Int? = null,
    val lyricTopSafe: Int? = null,
    val zone: AodSceneZone = AodSceneZone.STOCK
)

internal class AodPositionUpdateCoalescer {
    private var pending: AodPositionUpdate? = null

    @Synchronized
    fun offer(update: AodPositionUpdate): Boolean {
        val shouldSchedule = pending == null
        pending = update
        return shouldSchedule
    }

    @Synchronized
    fun drain(currentGeneration: Long): AodPositionUpdate? =
        pending.also { pending = null }?.takeIf { it.generation == currentGeneration }

    @Synchronized
    fun clear() {
        pending = null
    }
}
