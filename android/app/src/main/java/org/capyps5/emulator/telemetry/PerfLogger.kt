// SPDX-FileCopyrightText: Copyright 2026 PocketOberon contributors
// SPDX-License-Identifier: GPL-2.0-only

package org.capyps5.emulator.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.capyps5.emulator.NativeBridge
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * PocketOberon performance log. While a game runs it writes one CSV row per second
 * (`perf-<game>-<time>.csv`) and keeps a readable report next to it (`perf-<game>-<time>.txt`)
 * that says where the time goes and what is worth optimising on this phone.
 *
 * The report is rewritten every 30 s, so a crash still leaves one behind. With [deepProfiling]
 * the core's own profilers (GPU passes, CPU-GPU waits, HLE calls) also run, and their logcat
 * output is saved as `perf-<game>-<time>-detail.txt` when the game is closed.
 */
class PerfLogger(
    private val context: Context,
    private val gameName: String,
    private val titleId: String,
    private val logDir: File,
    /** Settings in effect for this session, as label to value. */
    private val settingsSummary: List<Pair<String, String>>,
    private val gpuDriver: String,
    private val refreshRateHz: Float,
    private val deepProfiling: Boolean,
) {
    private val lock = Any()
    private val startedAt = Date()
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(startedAt)
    private val baseName = "perf-${gameName.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(40)}-$stamp"
    private val csvFile = File(logDir, "$baseName.csv")
    private val reportFile = File(logDir, "$baseName.txt")
    private val detailFile = File(logDir, "$baseName-detail.txt")

    private val monitor = PerfMonitor(context, NativeBridge::nativeGetLogStats)
    private val threads = ThreadSampler()
    private val clusters = CpuClusters.read()
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val totalRamMb = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        .totalMem / (1024 * 1024)

    private var csv: PrintWriter? = null
    private var job: Job? = null
    private var finished = false
    private var startNs = 0L
    private var lastStats: LongArray? = null
    private var lastSampleNs = 0L
    private var firstBuckets: LongArray? = null
    private var lastBuckets: LongArray? = null
    private var firstWarnings: LongArray? = null
    private var lastWarnings: LongArray? = null

    private val rows = ArrayList<Row>()
    private val threadTotals = HashMap<String, ThreadTotal>()

    /** One second of data. Null fields were not readable on this device. */
    private data class Row(
        val timeSec: Float,
        val running: Boolean,
        val fps: Float,
        val hostFps: Float,
        val maxFrameMs: Float,
        val cpuPercent: Float?,
        val clusterMhz: List<Int>,
        val clusterCapMhz: List<Int>,
        val cpuTempC: Float?,
        val gpuPercent: Float?,
        val gpuMhz: Int?,
        val gpuTempC: Float?,
        val batteryTempC: Float?,
        val powerWatts: Float?,
        val appRamMb: Int,
        val availRamMb: Int,
        val mem: MemSample,
        val thermalStatus: Int,
        val thermalHeadroom: Float?,
        val pipelines: Float,
        val compileMs: Float,
        val gpuWaitMs: Float,
        val acquireMs: Float,
        val presentMs: Float,
        val topThread: String,
        val topThreadPercent: Float,
        val topThreadCpu: Int,
    )

    private class ThreadTotal {
        var cpuSec = 0f
        var peakPercent = 0f
        val clusterSec = HashMap<String, Float>()
    }

    fun start(scope: CoroutineScope) {
        runCatching {
            logDir.mkdirs()
            csv = PrintWriter(csvFile.bufferedWriter()).also { it.println(csvHeader()) }
        }.onFailure { Log.w(TAG, "cannot write $csvFile", it) }
        startNs = SystemClock.elapsedRealtimeNanos()
        job = scope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                runCatching { sampleOnce(tick) }.onFailure { Log.w(TAG, "sample failed", it) }
                tick++
                if (tick % 5 == 0) runCatching { writeReport() }
                delay(1000)
            }
        }
    }

    /** Stops sampling and writes the final report. Safe to call more than once. */
    fun finish() {
        synchronized(lock) {
            if (finished) return
            finished = true
        }
        job?.cancel()
        runCatching { writeReport() }.onFailure { Log.w(TAG, "report failed", it) }
        synchronized(lock) { csv?.close() }
        if (deepProfiling) runCatching { saveDetail() }.onFailure { Log.w(TAG, "detail failed", it) }
    }

    private fun sampleOnce(tick: Int) {
        val snapshot = monitor.sample()
        val stats = monitor.lastRawStats
        val now = SystemClock.elapsedRealtimeNanos()
        val prev = lastStats
        val dt = if (lastSampleNs == 0L) 0f else (now - lastSampleNs) / 1e9f
        lastStats = stats
        lastSampleNs = now
        val maxFrameMs = runCatching { NativeBridge.nativeTakeLogMaxFrameNs() }.getOrDefault(0L) / 1e6f
        if (stats == null || prev == null || dt <= 0f) return

        fun perSec(index: Int): Float =
            if (index < stats.size && index < prev.size) (stats[index] - prev[index]).coerceAtLeast(0) / dt else 0f
        fun msPerSec(index: Int): Float = perSec(index) / 1e6f

        val running = stats.getOrNull(NativeBridge.STAT_RUNNING) == 1L && snapshot.fps > 0f
        val buckets = LongArray(5) { stats.getOrNull(NativeBridge.STAT_FRAME_BUCKETS + it) ?: 0L }
        val warnings = longArrayOf(
            stats.getOrNull(NativeBridge.STAT_WAIT_TIMEOUTS) ?: 0L,
            stats.getOrNull(NativeBridge.STAT_WAIT_UNMAPPED) ?: 0L,
            stats.getOrNull(NativeBridge.STAT_NULL_BUFFERS) ?: 0L,
        )
        val threadSample = threads.sample(dt)
        val top = threadSample.firstOrNull()
        val memInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        // getThermalHeadroom returns NaN when asked more than about once a second.
        val headroom = if (tick % 2 == 0) {
            runCatching { powerManager.getThermalHeadroom(10) }.getOrNull()?.takeIf { !it.isNaN() }
        } else null

        val row = Row(
            timeSec = (now - startNs) / 1e9f,
            running = running,
            fps = snapshot.fps,
            hostFps = snapshot.hostFps,
            maxFrameMs = maxFrameMs,
            cpuPercent = snapshot.cpuPercent,
            clusterMhz = clusters.map { it.currentMhz() },
            clusterCapMhz = clusters.map { it.capMhz() },
            cpuTempC = snapshot.cpuTempC,
            gpuPercent = snapshot.gpuPercent,
            gpuMhz = snapshot.gpuMhz,
            gpuTempC = snapshot.gpuTempC,
            batteryTempC = snapshot.batteryTempC,
            powerWatts = snapshot.powerWatts,
            appRamMb = snapshot.appRamMb,
            availRamMb = (memInfo.availMem / (1024 * 1024)).toInt(),
            mem = sampleMemory(stats, tick, perSec(NativeBridge.STAT_TEXTURE_EVICTIONS), memInfo.lowMemory),
            thermalStatus = powerManager.currentThermalStatus,
            thermalHeadroom = headroom,
            pipelines = perSec(NativeBridge.STAT_PIPELINES_CREATED),
            compileMs = msPerSec(NativeBridge.STAT_PIPELINE_COMPILE_NS),
            gpuWaitMs = msPerSec(NativeBridge.STAT_GPU_WAIT_NS),
            acquireMs = msPerSec(NativeBridge.STAT_ACQUIRE_NS),
            presentMs = msPerSec(NativeBridge.STAT_PRESENT_NS),
            topThread = top?.name ?: "",
            topThreadPercent = top?.percent ?: 0f,
            topThreadCpu = top?.cpu ?: -1,
        )
        synchronized(lock) {
            if (finished) return
            rows.add(row)
            if (running) {
                if (firstBuckets == null) firstBuckets = buckets
                lastBuckets = buckets
                if (firstWarnings == null) firstWarnings = warnings
                lastWarnings = warnings
                for (t in threadSample) {
                    val total = threadTotals.getOrPut(t.name) { ThreadTotal() }
                    total.cpuSec += t.percent / 100f * dt
                    total.peakPercent = maxOf(total.peakPercent, t.percent)
                    val cluster = clusters.firstOrNull { t.cpu in it.cpus }?.label ?: "?"
                    total.clusterSec[cluster] = (total.clusterSec[cluster] ?: 0f) + t.percent / 100f * dt
                }
            }
            csv?.let {
                it.println(csvRow(row))
                it.flush()
            }
        }
    }

    private fun csvHeader(): String = buildList {
        addAll(listOf("time_s", "running", "fps", "host_fps", "max_frame_ms", "cpu_pct"))
        clusters.forEach { add("${it.label}_mhz"); add("${it.label}_cap_mhz") }
        addAll(
            listOf(
                "cpu_temp_c", "gpu_pct", "gpu_mhz", "gpu_temp_c", "battery_temp_c", "power_w",
                "app_ram_mb", "avail_ram_mb", "thermal_status", "thermal_headroom",
                "pipelines_per_s", "compile_ms_per_s", "gpu_wait_ms_per_s", "acquire_ms_per_s",
                "present_ms_per_s", "top_thread", "top_thread_pct", "top_thread_cpu",
                "guest_ram_mb", "emu_heap_mb", "files_mb", "swap_mb", "graphics_mb",
                "tex_cache_mb", "tex_gc_start_mb", "buf_cache_mb", "vulkan_used_mb", "evictions_per_s",
                "low_memory", "trim_level",
            )
        )
    }.joinToString(",")

    private fun csvRow(r: Row): String = buildList {
        add(f1(r.timeSec)); add(if (r.running) "1" else "0"); add(f1(r.fps)); add(f1(r.hostFps))
        add(f1(r.maxFrameMs)); add(opt(r.cpuPercent))
        r.clusterMhz.indices.forEach { add(r.clusterMhz[it].toString()); add(r.clusterCapMhz[it].toString()) }
        add(opt(r.cpuTempC)); add(opt(r.gpuPercent)); add(r.gpuMhz?.toString() ?: "")
        add(opt(r.gpuTempC)); add(opt(r.batteryTempC)); add(opt(r.powerWatts))
        add(r.appRamMb.toString()); add(r.availRamMb.toString()); add(r.thermalStatus.toString())
        add(r.thermalHeadroom?.let { "%.2f".format(Locale.US, it) } ?: "")
        add(f1(r.pipelines)); add(f1(r.compileMs)); add(f1(r.gpuWaitMs)); add(f1(r.acquireMs))
        add(f1(r.presentMs)); add(r.topThread.replace(',', ' ')); add(f1(r.topThreadPercent))
        add(r.topThreadCpu.toString())
        val m = r.mem
        add(m.guestMb.toString()); add(m.heapMb.toString()); add(m.fileMb.toString()); add(m.swapMb.toString())
        add(m.graphicsMb?.toString() ?: ""); add(m.textureMb.toString()); add(m.textureTriggerMb.toString())
        add(m.bufferMb.toString()); add(m.vulkanMb?.toString() ?: ""); add(f1(m.evictionsPerSec))
        add(if (m.lowMemory) "1" else "0"); add(m.trimLevel?.toString() ?: "")
    }.joinToString(",")

    private fun writeReport() {
        val text = synchronized(lock) { buildReport() }
        logDir.mkdirs()
        reportFile.writeText(text)
    }

    private fun buildReport(): String = buildString {
        val run = rows.filter { it.running }
        appendLine("PocketOberon performance report")
        appendLine("================================")
        appendLine("Game:        $gameName ($titleId)")
        appendLine("Started:     ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(startedAt)}")
        appendLine("Logged:      ${secs(rows.lastOrNull()?.timeSec ?: 0f)}, of which ${run.size} s in game (rest is loading/menus with no frames)")
        appendLine("Per-second data: ${csvFile.name}")
        appendLine()

        appendLine("DEVICE")
        appendLine("  ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("  RAM ${totalRamMb} MB, display ${refreshRateHz.roundToInt()} Hz, GPU driver: $gpuDriver")
        clusters.forEach {
            appendLine("  CPU ${it.label}: cores ${it.cpus.joinToString(",")} up to ${it.maxMhz} MHz")
        }
        appendLine()

        appendLine("SETTINGS")
        settingsSummary.forEach { (k, v) -> appendLine("  $k: $v") }
        appendLine("  Detailed profiling: ${if (deepProfiling) "on (costs some speed)" else "off"}")
        appendLine()

        appendMemory(this)

        if (run.isEmpty()) {
            appendLine("No in-game frames were recorded yet.")
            if (memoryFindings().isNotEmpty()) {
                appendLine()
                appendLine("WHAT TO LOOK AT")
                memoryFindings().forEach { appendLine("  - $it") }
            }
            return@buildString
        }

        val fpsSorted = run.map { it.fps }.sorted()
        val avgFps = run.map { it.fps }.average().toFloat()
        val low1 = fpsSorted[(fpsSorted.size * 0.01f).toInt().coerceAtMost(fpsSorted.size - 1)]
        val low5 = fpsSorted[(fpsSorted.size * 0.05f).toInt().coerceAtMost(fpsSorted.size - 1)]
        val bucketDelta = lastBuckets?.let { last ->
            val first = firstBuckets ?: LongArray(5)
            LongArray(5) { (last[it] - first[it]).coerceAtLeast(0) }
        } ?: LongArray(5)
        val frames = bucketDelta.sum().coerceAtLeast(1)
        val worstFrame = run.maxOf { it.maxFrameMs }
        appendLine("FRAME RATE (in game)")
        appendLine("  Average ${f1(avgFps)} FPS, 5% low ${f1(low5)}, 1% low ${f1(low1)}, worst second ${f1(fpsSorted.first())}, best ${f1(fpsSorted.last())}")
        appendLine("  Frame times: ${pct(bucketDelta[0], frames)} <=17 ms, ${pct(bucketDelta[1], frames)} 17-34 ms, ${pct(bucketDelta[2], frames)} 34-50 ms, ${pct(bucketDelta[3], frames)} 50-100 ms, ${pct(bucketDelta[4], frames)} >100 ms (stutters: ${bucketDelta[4]})")
        appendLine("  Worst single frame: ${f1(worstFrame)} ms")
        appendLine()

        val avgWait = run.map { it.gpuWaitMs }.average().toFloat()
        val avgCompile = run.map { it.compileMs }.average().toFloat()
        val totalPipelines = run.sumOf { it.pipelines.toDouble() }.roundToInt()
        val avgPresent = run.map { it.presentMs + it.acquireMs }.average().toFloat()
        appendLine("WHERE THE TIME GOES (ms per second of play; 1000 = a whole thread blocked)")
        appendLine("  CPU waiting for the GPU:      ${f1(avgWait)} ms/s (peak ${f1(run.maxOf { it.gpuWaitMs })})")
        appendLine("  Compiling shaders/pipelines:  ${f1(avgCompile)} ms/s (peak ${f1(run.maxOf { it.compileMs })}), $totalPipelines pipelines built")
        appendLine("  Waiting on the display:       ${f1(avgPresent)} ms/s (acquire + present)")
        appendLine()

        val avgCpu = run.mapNotNull { it.cpuPercent }.averageOrNull()
        appendLine("CPU")
        appendLine("  Whole-CPU load: ${avgCpu?.let { "${f1(it)}%" } ?: "not readable"}")
        clusters.forEachIndexed { i, c ->
            val cur = run.map { it.clusterMhz.getOrElse(i) { 0 } }.average().roundToInt()
            val minCap = run.minOf { it.clusterCapMhz.getOrElse(i) { c.maxMhz } }
            appendLine("  ${c.label}: average ${cur} MHz, lowest frequency cap ${minCap} MHz of ${c.maxMhz}")
        }
        val playSec = run.size.toFloat().coerceAtLeast(1f)
        val busiest = threadTotals.entries.sortedByDescending { it.value.cpuSec }.take(10)
        appendLine("  Busiest threads (average % of one core, peak, where they ran):")
        busiest.forEach { (name, t) ->
            val where = t.clusterSec.entries.sortedByDescending { it.value }
                .joinToString(" ") { "${it.key} ${pct(it.value, t.cpuSec)}" }
            appendLine("    ${name.padEnd(24)} ${f1(t.cpuSec / playSec * 100).padStart(6)}%  peak ${f1(t.peakPercent).padStart(5)}%  $where")
        }
        appendLine()

        val gpuBusy = run.mapNotNull { it.gpuPercent }.averageOrNull()
        val gpuMhz = run.mapNotNull { it.gpuMhz?.toFloat() }.averageOrNull()
        appendLine("GPU")
        appendLine("  Busy: ${gpuBusy?.let { "${f1(it)}%" } ?: "not readable on this phone"}, clock: ${gpuMhz?.let { "${it.roundToInt()} MHz" } ?: "not readable"}")
        appendLine()

        val maxCpuT = run.mapNotNull { it.cpuTempC }.maxOrNull()
        val maxGpuT = run.mapNotNull { it.gpuTempC }.maxOrNull()
        val maxBatT = run.mapNotNull { it.batteryTempC }.maxOrNull()
        val worstStatus = run.maxOf { it.thermalStatus }
        val throttledSec = run.count { it.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE }
        val minHeadroom = run.mapNotNull { it.thermalHeadroom }.minOrNull()
        val avgPower = run.mapNotNull { it.powerWatts }.averageOrNull()
        appendLine("HEAT AND POWER")
        appendLine("  Max temps: CPU ${opt(maxCpuT)} C, GPU ${opt(maxGpuT)} C, battery ${opt(maxBatT)} C")
        appendLine("  Worst Android thermal status: ${thermalName(worstStatus)}; ${throttledSec} s at moderate or worse")
        appendLine("  Thermal headroom: ${minHeadroom?.let { "lowest %.2f (1.0 = throttling starts)".format(Locale.US, it) } ?: "not reported"}")
        appendLine("  Average power draw: ${avgPower?.let { "${f1(it)} W" } ?: "not readable"}")
        appendLine()

        val minAvail = rows.minOf { it.availRamMb }
        val warn = lastWarnings?.let { last ->
            val first = firstWarnings ?: LongArray(3)
            LongArray(3) { (last[it] - first[it]).coerceAtLeast(0) }
        } ?: LongArray(3)
        appendLine("EMULATION WARNINGS (not speed problems, but they can cause hangs or glitches)")
        appendLine("  GPU sync timeouts: ${warn[0]}, waits on unmapped memory: ${warn[1]}, missing buffers: ${warn[2]}")
        appendLine()

        appendLine("WHAT TO LOOK AT")
        val findings = findings(run, avgFps, avgWait, avgCompile, avgPresent, gpuBusy, bucketDelta, busiest, playSec, minAvail, throttledSec, warn)
        if (findings.isEmpty()) appendLine("  - Nothing stands out. Run a longer or busier section of the game.")
        findings.forEach { appendLine("  - $it") }
        appendLine()
        appendLine("These are hints from simple rules, not certainties. The CSV has every second.")
    }

    private fun findings(
        run: List<Row>,
        avgFps: Float,
        avgWait: Float,
        avgCompile: Float,
        avgPresent: Float,
        gpuBusy: Float?,
        buckets: LongArray,
        busiest: List<Map.Entry<String, ThreadTotal>>,
        playSec: Float,
        minAvail: Int,
        throttledSec: Int,
        warn: LongArray,
    ): List<String> = buildList {
        val gpuBound = (gpuBusy != null && gpuBusy >= 90f) || avgWait >= 300f
        if (gpuBound) {
            add(
                "GPU-bound: the GPU is ${gpuBusy?.let { "${f1(it)}% busy" } ?: "the bottleneck"} and the CPU waits ${f1(avgWait)} ms/s for it. " +
                    "Lower Resolution or Shadows first; also try another Turnip driver."
            )
        }
        val top = busiest.firstOrNull()
        if (top != null) {
            val topPct = top.value.cpuSec / playSec * 100f
            if (topPct >= 85f && !gpuBound) {
                add(
                    "CPU-bound on one thread: '${top.key}' uses ${f1(topPct)}% of a core. Lower resolution won't help much; " +
                        "this thread is what limits the frame rate (guest code through the FEX JIT, or the GPU command thread)."
                )
            }
            val prime = clusters.lastOrNull()
            if (prime != null && clusters.size > 1 && topPct >= 50f) {
                val onPrime = (top.value.clusterSec[prime.label] ?: 0f) / top.value.cpuSec.coerceAtLeast(0.001f)
                if (onPrime < 0.5f) {
                    add(
                        "Optimisation candidate: the busiest thread '${top.key}' spent only ${pct(onPrime * 100f, 100f)} of its time on the fastest core (${prime.label}, cores ${prime.cpus.joinToString(",")}). " +
                            "Pinning it there could raise the frame rate."
                    )
                }
            }
        }
        if (buckets[4] > 0 && run.any { it.compileMs > 50f }) {
            val stutterSecs = run.count { it.maxFrameMs > 100f }
            val withCompile = run.count { it.maxFrameMs > 100f && it.compileMs > 20f }
            if (withCompile * 2 >= stutterSecs && stutterSecs > 0) {
                add(
                    "Shader stutter: $withCompile of $stutterSecs seconds with a >100 ms frame were also compiling pipelines. " +
                        "Keep Async shaders on; it should get better as the game's shaders are cached."
                )
            }
        }
        if (avgCompile >= 100f) {
            add("Pipeline compiling takes ${f1(avgCompile)} ms per second on average: a lot. A faster driver (Turnip) compiles quicker.")
        }
        if (avgPresent >= 400f && avgFps < refreshRateHz - 5f) {
            add("A thread spends ${f1(avgPresent)} ms/s waiting on the display. Check Frame limit, or try another driver.")
        }
        if (throttledSec > 0 || clusters.indices.any { i -> run.any { it.clusterCapMhz.getOrElse(i) { clusters[i].maxMhz } < clusters[i].maxMhz * 0.8f } }) {
            add(
                "Throttling: the CPU speed was capped below its maximum at times, and Android reported heat for $throttledSec s. " +
                    "Speed will drop over long sessions; a lower resolution or frame limit keeps the phone cooler."
            )
        }
        if (warn.any { it > 0 }) {
            add("Emulation warnings were logged. Those point at emulator bugs, not phone limits.")
        }
        if (avgFps > 0f && avgFps >= refreshRateHz - 2f) {
            add("Running at the display's refresh rate: there is spare speed.")
        }
        addAll(memoryFindings())
    }

    /** Where the emulator's memory is, at one moment. Sizes in MB. */
    private data class MemSample(
        /** Guest (PS5) RAM the game has touched: the shared-memory part of the emulator's RSS. */
        val guestMb: Int,
        /** The emulator's own heap: JIT code cache, caches' CPU copies, driver allocations. */
        val heapMb: Int,
        /** Mapped files (libraries, game files read through mmap). */
        val fileMb: Int,
        /** Pages Android swapped out to zRAM. */
        val swapMb: Int,
        /** GPU memory attributed to the emulator by Android (read every 5 s; null if not reported). */
        val graphicsMb: Int?,
        val textureMb: Int,
        val textureTriggerMb: Int,
        val texturePressureMb: Int,
        val textureCriticalMb: Int,
        val bufferMb: Int,
        val bufferTriggerMb: Int,
        /** All Vulkan memory in use, as the driver reports it (null without VK_EXT_memory_budget). */
        val vulkanMb: Int?,
        val vulkanBudgetMb: Int?,
        val evictionsPerSec: Float,
        val lowMemory: Boolean,
        /** onTrimMemory level Android sent in this second, if any. */
        val trimLevel: Int?,
    )

    private var lastGraphicsMb: Int? = null
    @Volatile private var pendingTrim: Int? = null
    private val trimEvents = ArrayList<Pair<Float, Int>>()

    /** Android's warning that memory is running out (from GameActivity.onTrimMemory). */
    fun noteTrim(level: Int) {
        pendingTrim = maxOf(pendingTrim ?: 0, level)
        synchronized(lock) { trimEvents.add((SystemClock.elapsedRealtimeNanos() - startNs) / 1e9f to level) }
    }

    private fun sampleMemory(stats: LongArray, tick: Int, evictionsPerSec: Float, lowMemory: Boolean): MemSample {
        val status = runCatching { File("/proc/self/status").readLines() }.getOrDefault(emptyList())
        fun kb(key: String): Int =
            status.firstOrNull { it.startsWith("$key:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull() ?: 0
        // Debug.getMemoryInfo takes tens of ms: every 5 s is enough.
        if (tick % 5 == 0) {
            lastGraphicsMb = runCatching {
                val info = android.os.Debug.MemoryInfo()
                android.os.Debug.getMemoryInfo(info)
                info.getMemoryStat("summary.graphics")?.toIntOrNull()?.div(1024)
            }.getOrNull()
        }
        fun mb(index: Int): Int = ((stats.getOrNull(index) ?: 0L) / (1024 * 1024)).toInt()
        val vulkan = mb(NativeBridge.STAT_MEM_VULKAN_USED)
        val budget = mb(NativeBridge.STAT_MEM_VULKAN_BUDGET)
        val trim = pendingTrim
        pendingTrim = null
        return MemSample(
            guestMb = kb("RssShmem") / 1024,
            heapMb = kb("RssAnon") / 1024,
            fileMb = kb("RssFile") / 1024,
            swapMb = kb("VmSwap") / 1024,
            graphicsMb = lastGraphicsMb,
            textureMb = mb(NativeBridge.STAT_MEM_TEXTURE_USED),
            textureTriggerMb = mb(NativeBridge.STAT_MEM_TEXTURE_TRIGGER),
            texturePressureMb = mb(NativeBridge.STAT_MEM_TEXTURE_PRESSURE),
            textureCriticalMb = mb(NativeBridge.STAT_MEM_TEXTURE_CRITICAL),
            bufferMb = mb(NativeBridge.STAT_MEM_BUFFER_USED),
            bufferTriggerMb = mb(NativeBridge.STAT_MEM_BUFFER_TRIGGER),
            vulkanMb = vulkan.takeIf { it > 0 },
            vulkanBudgetMb = budget.takeIf { it > 0 },
            evictionsPerSec = evictionsPerSec,
            lowMemory = lowMemory,
            trimLevel = trim,
        )
    }

    private fun trimName(level: Int) = when (level) {
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "running moderate"
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "running low"
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "running CRITICAL"
        android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI hidden"
        android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "background"
        android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "moderate"
        android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "complete"
        else -> "level $level"
    }

    private fun memLine(label: String, m: MemSample, appMb: Int, availMb: Int) =
        "  $label: emulator ${appMb} MB in RAM = guest (PS5) RAM ${m.guestMb} + emulator heap ${m.heapMb} + files ${m.fileMb}" +
            (if (m.swapMb > 0) ", plus ${m.swapMb} MB swapped out" else "") +
            "; GPU ${m.graphicsMb?.let { "$it MB" } ?: "n/a"}; phone free ${availMb} MB"

    private fun appendMemory(sb: StringBuilder) = with(sb) {
        if (rows.isEmpty()) return@with
        val tightest = rows.minBy { it.availRamMb }
        val last = rows.last()
        val peak = rows.maxBy { it.appRamMb }
        appendLine("MEMORY (whole session, loading included; ${totalRamMb} MB phone)")
        appendLine(memLine("When free RAM was lowest (${secs(tightest.timeSec)})", tightest.mem, tightest.appRamMb, tightest.availRamMb))
        appendLine(memLine("Emulator at its largest (${secs(peak.timeSec)})", peak.mem, peak.appRamMb, peak.availRamMb))
        appendLine(memLine("Last sample (${secs(last.timeSec)})", last.mem, last.appRamMb, last.availRamMb))
        val m = rows.maxBy { it.mem.textureMb }.mem
        appendLine(
            "  Texture cache: peak ${m.textureMb} MB; starts freeing at ${m.textureTriggerMb} MB, harder at " +
                "${m.texturePressureMb} MB, hardest at ${m.textureCriticalMb} MB"
        )
        appendLine("  Buffer cache: peak ${rows.maxOf { it.mem.bufferMb }} MB; starts freeing at ${last.mem.bufferTriggerMb} MB")
        val vk = rows.mapNotNull { it.mem.vulkanMb }.maxOrNull()
        appendLine(
            "  Vulkan memory in use: " + (vk?.let { "peak $it MB" } ?: "not reported by this driver") +
                (last.mem.vulkanBudgetMb?.let { "; caches were sized for a budget of $it MB" } ?: "")
        )
        val evictions = rows.sumOf { it.mem.evictionsPerSec.toDouble() }.roundToInt()
        appendLine("  Textures evicted: $evictions${if (evictions > 0) " (peak ${f1(rows.maxOf { it.mem.evictionsPerSec })}/s)" else ""}")
        val lowSecs = rows.count { it.mem.lowMemory }
        appendLine("  Android 'low memory' flag: ${if (lowSecs == 0) "never set" else "set for $lowSecs s"}")
        if (trimEvents.isEmpty()) {
            appendLine("  Memory warnings from Android: none")
        } else {
            appendLine("  Memory warnings from Android: " + trimEvents.takeLast(8).joinToString(", ") { (t, l) -> "${trimName(l)} at ${secs(t)}" })
        }
        appendLine()
    }

    private fun memoryFindings(): List<String> = buildList {
        if (rows.isEmpty()) return@buildList
        val minAvail = rows.minOf { it.availRamMb }
        val peakApp = rows.maxOf { it.appRamMb }
        if (minAvail < totalRamMb * 0.10f || trimEvents.any { it.second >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW }) {
            add("Memory is tight: as little as $minAvail MB was free while the emulator used up to $peakApp MB. Android kills the game when this runs out.")
        }
        val m = rows.maxBy { it.mem.textureMb }.mem
        if (m.textureTriggerMb > 0 && m.textureTriggerMb > totalRamMb * 0.15f) {
            add(
                "Optimisation candidate: the texture cache only starts freeing memory at ${m.textureTriggerMb} MB (that limit was tuned for phones with more RAM). " +
                    "On this ${totalRamMb} MB phone a lower limit would leave more room."
            )
        }
        val tight = rows.minBy { it.availRamMb }.mem
        val biggest = listOf("guest (PS5) RAM" to tight.guestMb, "emulator heap" to tight.heapMb, "GPU memory" to (tight.graphicsMb ?: 0))
            .maxBy { it.second }
        if (minAvail < totalRamMb * 0.10f && biggest.second > 0) {
            add("When memory was tightest, the largest part was ${biggest.first} (${biggest.second} MB): that is the first thing to shrink.")
        }
        if (rows.maxOf { it.mem.evictionsPerSec } >= 20f) {
            add("The texture cache is evicting a lot (up to ${f1(rows.maxOf { it.mem.evictionsPerSec })} textures/s), which causes re-uploads and stutter.")
        }
    }

    /** Saves the core profilers' logcat lines (GPU passes, submits/waits, HLE calls). */
    private fun saveDetail() {
        val process = Runtime.getRuntime().exec(
            arrayOf(
                "logcat", "-d", "-v", "time", "-s",
                "CapyPS5-GpuProf:*", "CapyPS5-Submit:*", "CapyPS5-Hle:*",
            )
        )
        process.inputStream.use { input -> detailFile.outputStream().use { input.copyTo(it) } }
        process.waitFor()
    }

    private fun f1(v: Float) = "%.1f".format(Locale.US, v)
    private fun opt(v: Float?) = v?.let { f1(it) } ?: ""
    private fun pct(part: Long, whole: Long) = "${(part * 100f / whole.coerceAtLeast(1)).roundToInt()}%"
    private fun pct(part: Float, whole: Float) = "${(part * 100f / whole.coerceAtLeast(0.001f)).roundToInt()}%"
    private fun secs(v: Float): String {
        val s = v.roundToInt()
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }
    private fun List<Float>.averageOrNull(): Float? = if (isEmpty()) null else average().toFloat()
    private fun thermalName(status: Int) = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    companion object {
        private const val TAG = "PocketOberon-Perf"

        /** Core profilers turned on by "Detailed profiling" (frontend options, no adb needed). */
        val DEEP_PROFILING_PROPERTIES = listOf(
            "debug.capy.gpu_profile" to 1L,
            "debug.capy.submit_stats" to 1L,
            "debug.capy.hle_stats" to 1L,
        )
    }
}

/** A CPU frequency domain (cluster) of the phone, e.g. on a Galaxy S23: 3 small, 4 big, 1 prime core. */
internal class CpuCluster(val label: String, val cpus: List<Int>, val maxMhz: Int, private val dir: File) {
    fun currentMhz(): Int = readKhz("scaling_cur_freq")?.div(1000) ?: 0
    /** Highest frequency currently allowed; drops below [maxMhz] when Android throttles for heat. */
    fun capMhz(): Int = readKhz("scaling_max_freq")?.div(1000) ?: maxMhz

    private fun readKhz(name: String): Int? =
        runCatching { File(dir, name).readText().trim().toInt() }.getOrNull()
}

internal object CpuClusters {
    fun read(): List<CpuCluster> {
        val policies = File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") }
            ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 0 }
            .orEmpty()
        val raw = policies.mapNotNull { dir ->
            val cpus = runCatching { parseCpuList(File(dir, "related_cpus").readText().trim()) }.getOrNull()
                ?: return@mapNotNull null
            val max = runCatching { File(dir, "cpuinfo_max_freq").readText().trim().toInt() / 1000 }.getOrNull()
                ?: return@mapNotNull null
            Triple(dir, cpus, max)
        }.sortedBy { it.third }
        val names = when (raw.size) {
            1 -> listOf("cpu")
            2 -> listOf("little", "big")
            3 -> listOf("little", "big", "prime")
            else -> raw.indices.map { "cluster$it" }
        }
        return raw.mapIndexed { i, (dir, cpus, max) -> CpuCluster(names[i], cpus, max, dir) }
    }

    private fun parseCpuList(text: String): List<Int> = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.flatMap { part ->
        val range = part.split('-')
        if (range.size == 2) (range[0].toInt()..range[1].toInt()).toList() else listOf(part.toInt())
    }
}

/** Per-thread CPU use of this process, from /proc/self/task. */
internal class ThreadSampler {
    data class ThreadUse(val name: String, val percent: Float, val cpu: Int)

    private val ticksPerSec = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK).toFloat()
    private var last = HashMap<Int, Long>()

    /** Threads sorted by CPU use over the last [dtSec] seconds (100% = one full core). */
    fun sample(dtSec: Float): List<ThreadUse> {
        val current = HashMap<Int, Long>()
        val uses = ArrayList<ThreadUse>()
        File("/proc/self/task").listFiles()?.forEach { dir ->
            val tid = dir.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            val open = stat.indexOf('(')
            val close = stat.lastIndexOf(')')
            if (open < 0 || close < open) return@forEach
            val name = stat.substring(open + 1, close)
            // Fields after the name start at field 3 (state); utime is 14, stime 15, processor 39.
            val fields = stat.substring(close + 2).split(' ')
            val ticks = (fields.getOrNull(11)?.toLongOrNull() ?: 0L) + (fields.getOrNull(12)?.toLongOrNull() ?: 0L)
            val cpu = fields.getOrNull(36)?.toIntOrNull() ?: -1
            current[tid] = ticks
            val before = last[tid] ?: return@forEach
            if (dtSec <= 0f) return@forEach
            val percent = (ticks - before).coerceAtLeast(0) / ticksPerSec / dtSec * 100f
            if (percent >= 1f) uses.add(ThreadUse("${name.ifBlank { "thread" }} [$tid]", percent, cpu))
        }
        last = current
        return uses.sortedByDescending { it.percent }
    }
}
