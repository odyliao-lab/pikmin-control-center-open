#pragma once
#include "loaded_core_pair.h"
#include <atomic>
#include <pthread.h>
namespace emu_payload {
// Process-lifetime worker: no file/mapping/hash I/O on Map Update. Image must
// remain alive until process exit; no unload or reusable worker session.
class PairObservationWorker {
    std::atomic<bool> started_{false},stopped_{false},terminal_{true};
    pthread_mutex_t mutex_=PTHREAD_MUTEX_INITIALIZER;
    LoadedImage *image_{};LoadedCorePair latest_;bool available_{};
    static void *run(void *argument){
        auto &self=*static_cast<PairObservationWorker *>(argument);
        while(!self.stopped_.load(std::memory_order_acquire)){
            LoadedCorePair value;const bool good=read_loaded_core_pair(*self.image_,value);
            if(pthread_mutex_lock(&self.mutex_)!=0){self.stop();break;}
            self.available_=good&&!self.stopped_.load(std::memory_order_acquire);
            if(self.available_)self.latest_=std::move(value);
            if(pthread_mutex_unlock(&self.mutex_)!=0){self.stop();break;}
            const auto end=std::chrono::steady_clock::now()+std::chrono::milliseconds(500);
            timespec delay{0,500000000};
            while(!self.stopped_.load(std::memory_order_acquire)&&nanosleep(&delay,&delay)!=0&&errno==EINTR){
                if(std::chrono::steady_clock::now()>=end)break;
            }
        }
        self.terminal_.store(true,std::memory_order_release);return nullptr;
    }
public:
    bool start(LoadedImage &image){
        if(started_.exchange(true,std::memory_order_acq_rel)||stopped_.load(std::memory_order_acquire))return false;
        // pthread failure is explicit, not std::thread's uncaught exception in
        // the payload's -fno-exceptions build. Never retry resource failure.
        image_=&image;terminal_.store(false,std::memory_order_release);pthread_attr_t attr;
        if(pthread_attr_init(&attr)!=0){stop();terminal_.store(true,std::memory_order_release);return false;}
        bool good=pthread_attr_setdetachstate(&attr,PTHREAD_CREATE_DETACHED)==0;pthread_t thread{};
        bool created=false;if(good){created=pthread_create(&thread,&attr,run,this)==0;good=created;}
        const bool destroyed=pthread_attr_destroy(&attr)==0;
        if(!good||!destroyed){stop();if(!created)terminal_.store(true,std::memory_order_release);return false;}return true;
    }
    void stop(){stopped_.store(true,std::memory_order_release);}
    bool terminal()const{return terminal_.load(std::memory_order_acquire);}
    bool read(LoadedCorePair &out){
        if(!started_.load(std::memory_order_acquire)||stopped_.load(std::memory_order_acquire)||pthread_mutex_trylock(&mutex_)!=0)return false;
        const auto at=root_apk_detail::now();
        const bool good=available_&&!stopped_.load(std::memory_order_acquire)&&latest_.installed.process.pid==getpid()&&
            at>=latest_.sampled&&at-latest_.sampled<=1000&&at<latest_.installed.until;
        LoadedCorePair value;if(good)value=latest_;
        const bool released=pthread_mutex_unlock(&mutex_)==0;
        if(!good||!released||stopped_.load(std::memory_order_acquire))return false;out=std::move(value);return true;
    }
    PairObservationWorker()=default;
    PairObservationWorker(const PairObservationWorker &)=delete;
    PairObservationWorker &operator=(const PairObservationWorker &)=delete;
};
}
