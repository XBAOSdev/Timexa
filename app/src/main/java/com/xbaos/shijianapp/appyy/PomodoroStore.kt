package com.xbaos.shijianapp.appyy

import android.content.Context

/**
 * 番茄钟的运行状态。
 *
 * 与 AppPrefs（用户设定）分开存放，理由：
 *  - AppPrefs 里每一项都是「用户意图」，会被设置页直接改写；
 *  - 这里是「机器恢复用的现场」，用户看不到也不应该手动改，
 *    清空现场不能连带把用户的时长设置一起清掉。
 *
 * 为什么要存：番茄钟一跑就是 25 分钟，用户很可能切出去做别的事、
 * 甚至被系统回收进程，回到 App 时如果计时凭空归零，这个功能就不可信了。
 */
data class PomodoroState(
    /** 阶段：focus / short / long。 */
    val phase: String,
    val running: Boolean,
    /** 运行中：结束时刻的墙上时间（System.currentTimeMillis）。 */
    val endWallClock: Long,
    /** 暂停中：剩余毫秒。 */
    val remainingMs: Long,
    /** 本阶段总时长（毫秒），用于换算进度。 */
    val phaseTotalMs: Long,
    /** 本轮已完成的专注个数。 */
    val completedInSet: Int,
    /** 本次会话累计完成的专注个数。 */
    val totalCompleted: Int
) {
    companion object {
        const val PHASE_FOCUS = "focus"
        const val PHASE_SHORT = "short"
        const val PHASE_LONG = "long"

        /** 冷启动时的初始现场：停在专注阶段、未开始、满时长。 */
        fun idle(totalMs: Long) = PomodoroState(
            phase = PHASE_FOCUS,
            running = false,
            endWallClock = 0L,
            remainingMs = totalMs,
            phaseTotalMs = totalMs,
            completedInSet = 0,
            totalCompleted = 0
        )
    }
}

class PomodoroStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): PomodoroState {
        val phase = prefs.getString(KEY_PHASE, PomodoroState.PHASE_FOCUS)
            ?: PomodoroState.PHASE_FOCUS
        return PomodoroState(
            phase = phase,
            running = prefs.getBoolean(KEY_RUNNING, false),
            endWallClock = prefs.getLong(KEY_END_WALL, 0L),
            remainingMs = prefs.getLong(KEY_REMAINING, 0L),
            phaseTotalMs = prefs.getLong(KEY_TOTAL, 0L),
            completedInSet = prefs.getInt(KEY_IN_SET, 0),
            totalCompleted = prefs.getInt(KEY_TOTAL_DONE, 0)
        )
    }

    fun save(state: PomodoroState) {
        prefs.edit()
            .putString(KEY_PHASE, state.phase)
            .putBoolean(KEY_RUNNING, state.running)
            .putLong(KEY_END_WALL, state.endWallClock)
            .putLong(KEY_REMAINING, state.remainingMs)
            .putLong(KEY_TOTAL, state.phaseTotalMs)
            .putInt(KEY_IN_SET, state.completedInSet)
            .putInt(KEY_TOTAL_DONE, state.totalCompleted)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "pomodoro_state"
        const val KEY_PHASE = "phase"
        const val KEY_RUNNING = "running"
        const val KEY_END_WALL = "end_wall_clock"
        const val KEY_REMAINING = "remaining_ms"
        const val KEY_TOTAL = "phase_total_ms"
        const val KEY_IN_SET = "completed_in_set"
        const val KEY_TOTAL_DONE = "total_completed"
    }
}
