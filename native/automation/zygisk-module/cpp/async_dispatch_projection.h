#pragma once
#include "capability_state.h"
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <unistd.h>

namespace pcc_projection {
constexpr size_t max_bytes = 1024 * 1024;
// Formatted on the managed thread; the worker owns bytes, never game objects.
struct Rows {
    std::string bytes;
    bool valid{true};
    template<class... Args> void append(const char *format, Args... args) {
        if (!valid) return;
        char row[16384];
        const int size = std::snprintf(row, sizeof(row), format, args...);
        if (size < 0 || static_cast<size_t>(size) >= sizeof(row) ||
                bytes.size() + static_cast<size_t>(size) > max_bytes) { valid = false; bytes.clear(); return; }
        bytes.append(row, static_cast<size_t>(size));
    }
};
struct Snapshot {
    std::string bytes;
    pcc_cap::ScanToken token{};
    int pid{};
    int64_t observed_ms{};
    int32_t count{};
    bool complete{}, target{};
    uint64_t sequence{};
    std::chrono::steady_clock::time_point queued{};
};
// A single worker, one latest pending full projection and one latest named-target
// projection. At most three bounded owned payloads including the in-flight job.
class Worker {
    const int pid_{getpid()};
    std::function<bool(const Snapshot&)> current_, publish_, commit_;
    std::mutex mutex_;
    std::condition_variable wake_, idle_;
    std::array<Snapshot,2> pending_;
    std::array<bool,2> present_{};
    bool stopping_{}, busy_{};
    uint64_t sequence_{};
    std::thread thread_;
    void run() {
        for (;;) {
            Snapshot job;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                wake_.wait(lock,[&]{return stopping_||present_[0]||present_[1];});
                if(stopping_)return;
                const size_t lane=!present_[0]?1:(!present_[1]?0:(pending_[0].sequence<pending_[1].sequence?0:1));
                job=std::move(pending_[lane]);present_[lane]=false;busy_=true;
            }
            const auto begin=std::chrono::steady_clock::now();
            queue_wait_us.store(std::chrono::duration_cast<std::chrono::microseconds>(begin-job.queued).count());
            if(job.pid!=getpid()||!current_(job)) ++stale;
            else if(!publish_(job)) ++failed;
            else {
                ++published;
                if(!current_(job))++stale;
                else if(!job.target&&job.complete&&!commit_(job))++stale;
            }
            publish_us.store(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-begin).count());
            {
                std::lock_guard<std::mutex> lock(mutex_);busy_=false;
                if(!present_[0]&&!present_[1])idle_.notify_all();
            }
        }
    }
public:
    std::atomic<uint64_t> submitted{},coalesced{},published{},failed{},stale{};
    std::atomic<int64_t> publish_us{},queue_wait_us{};
    Worker(std::function<bool(const Snapshot&)> current,std::function<bool(const Snapshot&)> publish,
            std::function<bool(const Snapshot&)> commit)
        :current_(std::move(current)),publish_(std::move(publish)),commit_(std::move(commit)),thread_([this]{run();}){}
    Worker(const Worker&)=delete;Worker& operator=(const Worker&)=delete;
    bool submit(Snapshot job) {
        if(job.pid!=pid_||job.pid!=getpid()||!job.token.valid||job.observed_ms<=0||job.count<0||job.bytes.size()>max_bytes)return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if(stopping_)return false;
        const size_t lane=job.target?1:0;
        if(present_[lane])++coalesced;
        job.sequence=++sequence_;job.queued=std::chrono::steady_clock::now();
        pending_[lane]=std::move(job);present_[lane]=true;++submitted;
        wake_.notify_one();return true;
    }
    // HOST tests only. Gameplay code never waits for file publication.
    bool wait_idle(std::chrono::milliseconds limit) {
        std::unique_lock<std::mutex> lock(mutex_);
        return idle_.wait_for(lock,limit,[&]{return !busy_&&!present_[0]&&!present_[1];});
    }
    ~Worker() {
        {std::lock_guard<std::mutex> lock(mutex_);stopping_=true;present_={false,false};}
        wake_.notify_all();if(thread_.joinable())thread_.join();
    }
};
}
