package com.example.controllerapp.ui

import com.example.controllerapp.model.PlaybackHistorySession

internal object HistoryDisplayPolicy {
    fun status(session: PlaybackHistorySession): String = when {
        session.endedAt == null -> if (session.countedPlay) "进行中 · 已计播放" else "进行中 · 未计播放"
        session.completed -> "已完播"
        session.skipped -> "已跳过"
        else -> "播放中断"
    }
}
