// SPDX-FileCopyrightText: Copyright 2026 PocketOberon contributors
// SPDX-License-Identifier: GPL-2.0-only

package org.capyps5.emulator

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notices games that died without the core's own error report (a crash its handler didn't
 * catch, or Android killing the process), and asks Android why.
 *
 * The game process writes a marker when it starts and removes it on a normal exit. If the
 * launcher finds the marker, the game process is gone and no error report exists, the game
 * stopped unexpectedly: Android keeps the reason (ApplicationExitInfo), including the native
 * crash dump (tombstone) when there was one.
 */
object GameSession {
    private fun marker(context: Context) = File(context.filesDir, "game_session.txt")

    /** Called in the game process once the game is about to start. */
    fun begin(context: Context, title: String) {
        runCatching {
            marker(context).writeText("${android.os.Process.myPid()}\n${System.currentTimeMillis()}\n$title")
        }
    }

    /** Called on a normal exit, and when the core's own error report is shown instead. */
    fun end(context: Context) {
        marker(context).delete()
    }

    /** What Android recorded about an unexpected stop. */
    class Stop(val report: String, val tombstone: ByteArray?)

    /**
     * Returns the stop of the last game when it ended unexpectedly, once; null otherwise
     * (no game ran, it exited normally, it is still running, or the user closed it).
     */
    fun takeUnexpectedStop(context: Context): Stop? {
        val file = marker(context)
        if (!file.exists()) return null
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val pid = lines.getOrNull(0)?.toIntOrNull()
        val started = lines.getOrNull(1)?.toLongOrNull() ?: 0L
        val title = lines.drop(2).joinToString(" ").ifBlank { "the game" }
        val am = context.getSystemService(ActivityManager::class.java)
        // Still running (the user came back to the launcher while it plays): nothing to report.
        if (pid != null && am.runningAppProcesses.orEmpty().any { it.pid == pid }) return null
        file.delete()

        val info = pid?.let {
            runCatching { am.getHistoricalProcessExitReasons(context.packageName, it, 1) }
                .getOrNull()?.firstOrNull()
        }
        if (info != null && info.reason in USER_REASONS) return null

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val report = buildString {
            appendLine("Game: $title")
            if (started > 0) appendLine("Started: ${time.format(Date(started))}")
            if (info == null) {
                appendLine("Android kept no record of why it stopped.")
            } else {
                appendLine("Stopped: ${time.format(Date(info.timestamp))}")
                appendLine("Why: ${explain(info)}")
                info.description?.takeIf { it.isNotBlank() }?.let { appendLine("Android says: $it") }
                if (info.reason == ApplicationExitInfo.REASON_SIGNALED || info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    appendLine("Signal: ${info.status} (${signalName(info.status)})")
                }
                if (info.reason == ApplicationExitInfo.REASON_EXIT_SELF) appendLine("Exit code: ${info.status}")
                appendLine("Memory when it stopped: ${info.rss / 1024} MB in RAM (${info.pss / 1024} MB PSS)")
            }
        }
        val tombstone = info?.takeIf { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }?.let {
            runCatching { it.traceInputStream?.use { s -> s.readBytes() } }.getOrNull()
        }
        return Stop(report, tombstone)
    }

    private val USER_REASONS = setOf(
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
    )

    private fun explain(info: ApplicationExitInfo): String = when (info.reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY ->
            "Android closed it to free memory (the phone ran low on RAM). Close other apps, or lower Resolution."
        ApplicationExitInfo.REASON_CRASH_NATIVE ->
            "The emulator crashed in native code before it could write its own error report."
        ApplicationExitInfo.REASON_CRASH -> "The app's Java/Kotlin code crashed."
        ApplicationExitInfo.REASON_ANR -> "It stopped responding and Android closed it."
        ApplicationExitInfo.REASON_SIGNALED ->
            "It was killed by a signal (often Android's memory killer, or a crash in the emulator)."
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
            "Android closed it for using too much CPU or memory in the background."
        ApplicationExitInfo.REASON_FREEZER -> "Android froze and then closed it in the background."
        ApplicationExitInfo.REASON_EXIT_SELF -> "The emulator closed itself."
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "It failed to start."
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "A permission was changed, so Android restarted it."
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Something it depended on stopped."
        else -> "Other (reason code ${info.reason})."
    }

    private fun signalName(signal: Int) = when (signal) {
        4 -> "SIGILL, illegal instruction"
        6 -> "SIGABRT, abort"
        7 -> "SIGBUS, bad memory access"
        9 -> "SIGKILL, killed from outside"
        11 -> "SIGSEGV, invalid memory access"
        else -> "signal $signal"
    }
}
