// SPDX-FileCopyrightText: Copyright 2026 CapyPS5 Project
// SPDX-License-Identifier: GPL-2.0-only

#ifndef COMMON_TELEMETRY_H
#define COMMON_TELEMETRY_H

#include <atomic>
#include <chrono>
#include <cstdint>

// Lock-free counters sampled by the frontend performance overlay. Producers only increment;
// the overlay computes rates from deltas between samples.
namespace Common::Telemetry {

// Guest flips that reached the screen (the game's own frame rate).
inline std::atomic<uint64_t> g_game_frames {0};
inline std::atomic<uint64_t> g_last_game_frame_ns {0};
// Worst frame time since the overlay last exchanged it with 0.
inline std::atomic<uint64_t> g_max_frame_ns {0};
// Same as g_max_frame_ns, but exchanged only by the performance log (the overlay resets the other).
inline std::atomic<uint64_t> g_log_max_frame_ns {0};
// Game frame times by bucket: <=17 ms, <=34 ms, <=50 ms, <=100 ms, longer (stutters).
inline std::atomic<uint64_t> g_frame_buckets[5] {};
// Host swapchain presents (can differ from game frames when the presenter repeats images).
inline std::atomic<uint64_t> g_host_presents {0};
// Presents of frames CapyPS5 Framegen generated between guest frames.
inline std::atomic<uint64_t> g_generated_frames {0};
// Places where emulation papered over a problem; non-zero rates point at real bugs.
inline std::atomic<uint64_t> g_wait_reg_mem_timeouts {0};
inline std::atomic<uint64_t> g_wait_reg_mem_unmapped {0};
inline std::atomic<uint64_t> g_null_buffer_fallbacks {0};

// PocketOberon performance log: where frame time goes. All cumulative; the frontend takes deltas.
// Vulkan pipelines the driver compiled, and the time spent compiling them (shader stutter).
inline std::atomic<uint64_t> g_pipelines_created {0};
inline std::atomic<uint64_t> g_pipeline_compile_ns {0};
// Time CPU threads blocked waiting for the GPU to finish work (high = GPU-bound or serialised).
inline std::atomic<uint64_t> g_gpu_waits {0};
inline std::atomic<uint64_t> g_gpu_wait_ns {0};
// Time spent in vkAcquireNextImageKHR and vkQueuePresentKHR (high = display/vsync-bound).
inline std::atomic<uint64_t> g_acquire_ns {0};
inline std::atomic<uint64_t> g_present_ns {0};

// Memory, for the performance log. Gauges (current values), not counters.
// Texture cache: what it holds, and the levels where it starts evicting / evicts harder / hardest.
inline std::atomic<uint64_t> g_mem_texture_used {0};
inline std::atomic<uint64_t> g_mem_texture_trigger {0};
inline std::atomic<uint64_t> g_mem_texture_pressure {0};
inline std::atomic<uint64_t> g_mem_texture_critical {0};
// Buffer cache: what it holds, and the level where it starts evicting.
inline std::atomic<uint64_t> g_mem_buffer_used {0};
inline std::atomic<uint64_t> g_mem_buffer_trigger {0};
// All Vulkan memory the driver reports in use (VK_EXT_memory_budget; 0 when not reported),
// and the budget the caches were sized from.
inline std::atomic<uint64_t> g_mem_vulkan_used {0};
inline std::atomic<uint64_t> g_mem_vulkan_budget {0};
// Images the texture cache evicted (cumulative; a high rate means it is thrashing).
inline std::atomic<uint64_t> g_texture_evictions {0};

// Where guest RAM comes from, for the performance log (cumulative bytes).
// File mappings (mmap of a file) are copied whole into guest memory, unlike on a PS5 where pages
// load on demand; g_file_read_bytes is ordinary read()/pread() for comparison.
inline std::atomic<uint64_t> g_file_map_bytes {0};
inline std::atomic<uint64_t> g_file_maps {0};
inline std::atomic<uint64_t> g_file_read_bytes {0};
// Flexible and direct memory the game mapped (requested sizes; pages count once touched).
inline std::atomic<uint64_t> g_flexible_map_bytes {0};
inline std::atomic<uint64_t> g_direct_map_bytes {0};

inline uint64_t NowNs() {
	return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
	                                 std::chrono::steady_clock::now().time_since_epoch())
	                                 .count());
}

// Adds the time since construction to a counter.
class ScopedTimer {
public:
	explicit ScopedTimer(std::atomic<uint64_t>& counter): m_counter(counter), m_start(NowNs()) {}
	~ScopedTimer() { m_counter.fetch_add(NowNs() - m_start, std::memory_order_relaxed); }
	ScopedTimer(const ScopedTimer&)            = delete;
	ScopedTimer& operator=(const ScopedTimer&) = delete;

private:
	std::atomic<uint64_t>& m_counter;
	uint64_t               m_start;
};

inline void RecordGameFrame() {
	const auto now  = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                                               std::chrono::steady_clock::now().time_since_epoch())
                                               .count());
	const auto prev = g_last_game_frame_ns.exchange(now, std::memory_order_relaxed);
	if (prev != 0 && now > prev) {
		const auto delta = now - prev;
		auto       max   = g_max_frame_ns.load(std::memory_order_relaxed);
		while (delta > max && !g_max_frame_ns.compare_exchange_weak(max, delta, std::memory_order_relaxed)) {
		}
		auto log_max = g_log_max_frame_ns.load(std::memory_order_relaxed);
		while (delta > log_max &&
		       !g_log_max_frame_ns.compare_exchange_weak(log_max, delta, std::memory_order_relaxed)) {
		}
		const auto ms     = delta / 1'000'000u;
		const int  bucket = ms <= 17 ? 0 : ms <= 34 ? 1 : ms <= 50 ? 2 : ms <= 100 ? 3 : 4;
		g_frame_buckets[bucket].fetch_add(1, std::memory_order_relaxed);
	}
	g_game_frames.fetch_add(1, std::memory_order_relaxed);
}

} // namespace Common::Telemetry

#endif /* COMMON_TELEMETRY_H */
