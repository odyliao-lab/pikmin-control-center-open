#pragma once
#include "root_gps_ipc.h"
#include <atomic>
#include <pthread.h>
namespace emu_gps_work {
// Process-lifetime observation only. Never a WriterExclusionSource, command
// ingress, or ownership grant. All path/proc I/O is on this dedicated worker.
class GpsObservationWorker final {
    std::atomic<bool> started_{false},stopped_{false},terminal_{true};
    pthread_mutex_t mutex_=PTHREAD_MUTEX_INITIALIZER;
    using Reader=bool(*)(FenceObservation &);
    Reader reader_=read_fence_ipc;
    FenceObservation latest_;uint64_t observed_{};bool available_{};
    pid_t process_{};uid_t uid_{};
    static void *run(void *argument){
        auto &self=*static_cast<GpsObservationWorker *>(argument);
        while(!self.stopped_.load(std::memory_order_acquire)){
            FenceObservation value;const bool good=self.reader_(value);
            const auto at=fence_observation_detail::now();
            if(pthread_mutex_lock(&self.mutex_)!=0){self.stop();break;}
            self.available_=good&&at>0&&at>=value.sampled&&at<value.until&&
                !self.stopped_.load(std::memory_order_acquire);
            if(self.available_){self.latest_=std::move(value);self.observed_=at;}
            if(pthread_mutex_unlock(&self.mutex_)!=0){self.stop();break;}
            timespec delay{0,100000000};
            while(!self.stopped_.load(std::memory_order_acquire)&&nanosleep(&delay,&delay)!=0&&errno==EINTR){}
        }
        self.terminal_.store(true,std::memory_order_release);return nullptr;
    }
    bool launch(){
        if(started_.exchange(true,std::memory_order_acq_rel)||stopped_.load(std::memory_order_acquire))return false;
        process_=getpid();uid_=geteuid();terminal_.store(false,std::memory_order_release);
        pthread_attr_t attr;
        if(pthread_attr_init(&attr)!=0){stop();terminal_=true;return false;}
        bool good=pthread_attr_setdetachstate(&attr,PTHREAD_CREATE_DETACHED)==0;
        pthread_t thread{};bool created=false;
        if(good){created=pthread_create(&thread,&attr,run,this)==0;good=created;}
        const bool destroyed=pthread_attr_destroy(&attr)==0;
        if(!good||!destroyed){stop();if(!created)terminal_=true;return false;}return true;
    }
public:
    bool start(){return launch();}
#ifdef PCC_GPS_OBSERVATION_FIXTURE
    // Never compiled into the payload. Fixtures supply a private validated FD,
    // not a configurable production path or caller-provided observation wire.
    bool start_fixture(Reader reader){
        if(!reader||started_.load()||stopped_.load())return false;reader_=reader;return launch();
    }
#endif
    void stop(){stopped_.store(true,std::memory_order_release);}
    bool terminal()const{return terminal_.load(std::memory_order_acquire);}
    bool read(FenceObservation &out){
        if(!started_.load(std::memory_order_acquire)||stopped_.load(std::memory_order_acquire)||
           pthread_mutex_trylock(&mutex_)!=0)return false;
        const auto at=fence_observation_detail::now();
        // No file/proc/PM reads or waiting for the background worker here.
        const bool good=available_&&!stopped_.load(std::memory_order_acquire)&&
            process_==getpid()&&uid_==geteuid()&&at>=observed_&&at-observed_<=150&&
            at>=latest_.sampled&&at<latest_.until;
        FenceObservation value;if(good)value=latest_;
        const bool released=pthread_mutex_unlock(&mutex_)==0;
        if(!good||!released||stopped_.load(std::memory_order_acquire))return false;
        out=std::move(value);return true;
    }
    GpsObservationWorker()=default;
    GpsObservationWorker(const GpsObservationWorker &)=delete;
    GpsObservationWorker &operator=(const GpsObservationWorker &)=delete;
};
}
