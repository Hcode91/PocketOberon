// SPDX-FileCopyrightText: Copyright 2026 CapyPS5 Project
// SPDX-License-Identifier: GPL-2.0-only

package org.capyps5.emulator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import org.capyps5.emulator.model.GameItem
import org.capyps5.emulator.telemetry.PerfLogger
import org.libsdl.app.SDL
import org.capyps5.emulator.ui.screens.EmulationScreen
import org.capyps5.emulator.ui.theme.CapyPS5Theme
import java.io.File

/**
 * Runs one game in its own process (":game"). The native core cannot be torn down and started
 * again inside one process (guest threads and global state outlive Emulator::Run), so leaving the
 * game ends this process and the launcher, in the main process, stays where it was.
 */
class GameActivity : CapyActivity() {

    private var perfLogger: PerfLogger? = null

    companion object {
        private const val EXTRA_TITLE_ID = "title_id"
        private const val EXTRA_TITLE_NAME = "title_name"
        private const val EXTRA_DIR = "game_dir"
        private const val EXTRA_ELF = "game_elf"

        /** Native fatal reports land here (see Log::SetFatalReportPath). */
        fun errorFile(context: Context) = File(context.filesDir, "last_error.txt")

        fun launch(context: Context, game: GameItem) {
            context.startActivity(
                Intent(context, GameActivity::class.java)
                    .putExtra(EXTRA_TITLE_ID, game.titleId)
                    .putExtra(EXTRA_TITLE_NAME, game.titleName)
                    .putExtra(EXTRA_DIR, game.directoryPath)
                    .putExtra(EXTRA_ELF, game.elfPath)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The in-game text bar (IME dialog input) must stay visible while the soft keyboard is
        // open, and the game surface must not resize underneath it.
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val elf = intent.getStringExtra(EXTRA_ELF)
        if (elf == null) {
            finish()
            return
        }
        val game = GameItem(
            titleId = intent.getStringExtra(EXTRA_TITLE_ID) ?: "",
            titleName = intent.getStringExtra(EXTRA_TITLE_NAME) ?: File(elf).name,
            directoryPath = intent.getStringExtra(EXTRA_DIR) ?: (File(elf).parent ?: ""),
            elfPath = elf,
        )
        // A report left by an earlier session must not be shown for this one.
        errorFile(this).delete()
        NativeBridge.nativeInit(filesDir = filesDir.absolutePath, cacheDir = cacheDir.absolutePath)
        // SDL (statically linked in the core) needs its Java side for audio output on Android; the
        // frontend is not an SDLActivity, so set it up by hand before the game opens any device.
        runCatching {
            SDL.setupJNI()
            SDL.initialize()
            SDL.setContext(this)
        }.onFailure { android.util.Log.e("CapyPS5-Audio", "SDL Java setup failed", it) }
        InputRouter.onRouteChanged = { toUi -> if (toUi) releaseEmulatedPad() }

        val settings = Settings(this)
        swapFaceButtons = settings.buttonLayout == ButtonLayout.Nintendo
        // Global settings with this game's overrides, applied before the core reads them.
        CoreOption.entries.forEach { option ->
            val value = settings.effectiveOption(game.titleId, option, game.isUnreal)
            // "Same as device" is resolved here: only Android knows the locale.
            NativeBridge.nativeSetOption(
                option.property,
                if (option == CoreOption.Language && value == AUTO_LANGUAGE) deviceConsoleLanguage() else value,
            )
        }
        val driver = GpuDrivers.find(this, settings.effectiveGpuDriver(game.titleId))
        NativeBridge.nativeSetGpuDriver(
            nativeLibraryDir = applicationInfo.nativeLibraryDir,
            driverDir = driver?.dir?.absolutePath.orEmpty(),
            library = driver?.library.orEmpty(),
        )
        if (settings.perfLog) {
            if (settings.perfDeepProfiling) {
                PerfLogger.DEEP_PROFILING_PROPERTIES.forEach { (name, value) -> NativeBridge.nativeSetOption(name, value) }
            }
            perfLogger = runCatching {
                PerfLogger(
                    context = this,
                    gameName = game.titleName,
                    titleId = game.titleId,
                    logDir = File(settings.libraryPaths.first(), "logs"),
                    settingsSummary = CoreOption.entries.filter { it != CoreOption.Language }.map { option ->
                        option.title(this) to option.label(this, settings.effectiveOption(game.titleId, option, game.isUnreal))
                    },
                    gpuDriver = driver?.label ?: getString(R.string.gpu_driver_system),
                    refreshRateHz = display?.refreshRate ?: 60f,
                    deepProfiling = settings.perfDeepProfiling,
                ).also { it.start(lifecycleScope) }
            }.onFailure { android.util.Log.w("PocketOberon-Perf", "performance log not started", it) }.getOrNull()
        }
        GameSession.begin(this, game.titleName)
        val versionText = Updater.versionText(this)
        setContent {
            // The in-game settings can change the button layout while the game runs.
            LaunchedEffect(settings.buttonLayout) {
                swapFaceButtons = settings.buttonLayout == ButtonLayout.Nintendo
            }
            CapyPS5Theme {
                EmulationScreen(
                    game = game,
                    settings = settings,
                    versionText = versionText,
                    onExit = ::exitGame,
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        perfLogger?.noteTrim(level)
    }

    override fun onDestroy() {
        perfLogger?.finish()
        super.onDestroy()
    }

    private fun exitGame() {
        GameSession.end(this)
        perfLogger?.finish()
        runCatching { NativeBridge.nativeStopGame() }
        finish()
        // The launcher activity lives in the main process; ending this one frees everything the
        // emulator held (guest memory, threads, Vulkan device).
        Process.killProcess(Process.myPid())
    }
}
