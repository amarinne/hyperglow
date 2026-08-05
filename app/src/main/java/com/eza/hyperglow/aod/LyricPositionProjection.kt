package com.eza.hyperglow.aod

import com.eza.hyperglow.producer.LyricProducerState

/**
 * 共享的位置投影逻辑：把「采样时刻的位置 + 经过的时间 + 速度」前向投影到 [now]。
 *
 * 这是 [AodProjectionEngine.project] 历史上的 `projectedPosition(state, now)` 的纯函数提取，
 * 现在同时服务于：
 * - 引擎侧（[AodProjectionEngine.projectedPosition] 委托给它，Spicy 路径仍走 SpicyBridgeState）
 * - [AodStateProjector]（消费 [LyricProducerState]，Phase 3 引擎切换后的唯一入口）
 * - Phase 2 的 `SpicyLyricProducer` 100ms 重投影定时器
 *
 * 行为与原实现严格一致：playing 时按 speed 线性外推，非 playing 时取采样位置；最后 clamp 到
 * `[0, durationMs]`（原实现即 `coerceIn(0L, durationMs)`，`durationMs == 0` 时结果恒为 0，
 * 保留该语义以保证零回归）。
 *
 * 注意：lyricon 的 `onPositionChanged` 回调本身就是最新位置，但 `sampledAtElapsedMs` 也是
 * emit 时刻的 now，且 `speed` 为 1f/0f，因此对其投影是安全的近似 no-op —— 统一走本函数可让
 * 两条路径共享同一套位置语义，无需在引擎里区分 producer 类型（spec clause 8）。
 */
internal fun projectedPosition(
    positionMs: Long,
    sampledAtElapsedMs: Long,
    speed: Float,
    playing: Boolean,
    durationMs: Long,
    now: Long
): Long {
    val projected = if (playing) {
        positionMs + ((now - sampledAtElapsedMs).coerceAtLeast(0L) * speed).toLong()
    } else {
        positionMs
    }
    return projected.coerceIn(0L, durationMs)
}

/** [LyricProducerState] 适配重载——[AodStateProjector] 与 Phase 2 生产者使用。 */
internal fun projectedPosition(state: LyricProducerState, now: Long): Long = projectedPosition(
    positionMs = state.positionMs,
    sampledAtElapsedMs = state.sampledAtElapsedMs,
    speed = state.speed,
    playing = state.playing,
    durationMs = state.durationMs,
    now = now
)
