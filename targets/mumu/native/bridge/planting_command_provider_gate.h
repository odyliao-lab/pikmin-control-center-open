#pragma once
#include <cmath>
#include <cstdint>
#include <string>

namespace emu_planting_command {
struct ProviderClock {
    int64_t before{},wall{},after{};
    bool valid()const{return before>0&&after>=before&&after<1000000000000LL&&after-before<=20&&wall>=1000000000000LL&&wall<=INT64_MAX-1000;}
    int64_t low()const{return wall-after;}
    int64_t high()const{return wall-before;}
    bool follows(const ProviderClock &old)const{return valid()&&old.valid()&&before>=old.after&&low()>=old.low()-100&&high()<=old.high()+100;}
};
// Must come from independently verified held Root handoff, NOT command or
// App prefs. Default construction cannot admit anything.
struct ProviderBoundary {
    std::string generation;
    int pid{};uint64_t ticks{};
    ProviderClock original,start;
    double latitude{},longitude{};
};
struct ConsumerSample {
    ProviderClock clock;bool valid{};
    int64_t raw_ms{};double raw_lat{},raw_lng{},game_lat{},game_lng{};
};
struct ConsumerSource {
    virtual ~ConsumerSource()=default;
    virtual bool read(ConsumerSample &)=0;
};
class ProviderGenerationGate {
    ProviderBoundary boundary_;bool begun_{},revoked_{},ready_{};
    int64_t first_{},last_{},seen_{};unsigned samples_{};
    static bool point(double a,double b){return std::isfinite(a)&&std::isfinite(b)&&std::abs(a)<=90&&std::abs(b)<=180;}
    static bool token(const std::string &s){
        if(s.size()!=36)return false;
        for(size_t i=0;i<s.size();++i){if(i==8||i==13||i==18||i==23){if(s[i]!='-')return false;}
            else if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f')))return false;}
        return true;
    }
    static double metres(double a,double b,double c,double d){
        constexpr double r=3.14159265358979323846/180;
        const double x=std::sin((c-a)*r/2),y=std::sin((d-b)*r/2);
        const double h=x*x+std::cos(a*r)*std::cos(c*r)*y*y;
        return 12742000*std::asin(std::sqrt(std::fmin(1.0,std::fmax(0.0,h))));
    }
    static bool same_clock(const ProviderClock &a,const ProviderClock &b){return a.before==b.before&&a.wall==b.wall&&a.after==b.after;}
    static bool same(const ProviderBoundary &a,const ProviderBoundary &b){return a.generation==b.generation&&a.pid==b.pid&&a.ticks==b.ticks&&
        same_clock(a.original,b.original)&&same_clock(a.start,b.start)&&a.latitude==b.latitude&&a.longitude==b.longitude;}
public:
    // Missing/revoked exclusion evidence invalidates accumulated consumer
    // readiness. Reappearance of a lease must warm up again, not reuse a
    // previously ready snapshot. An already latched clock/boundary violation
    // still needs a new generation; suspension never clears that latch.
    void suspend(){ready_=false;first_=last_=0;samples_=0;}
    bool observe(const ProviderBoundary &b,const ConsumerSample &f){
        if(!token(b.generation)||b.pid<=0||!b.ticks||!point(b.latitude,b.longitude)||!b.start.follows(b.original)||
           b.start.after>INT64_MAX-500)return false;
        if(!begun_||boundary_.generation!=b.generation){boundary_=b;begun_=true;revoked_=ready_=false;first_=last_=seen_=0;samples_=0;}
        else if(!same(boundary_,b)){revoked_=true;ready_=false;return false;}
        if(revoked_)return false;
        if(!f.clock.follows(b.original)||!f.clock.follows(b.start)||f.clock.before<seen_){revoked_=true;ready_=false;return false;}
        seen_=f.clock.before;
        if(!f.valid||!point(f.raw_lat,f.raw_lng)||!point(f.game_lat,f.game_lng)||f.raw_ms<b.start.after+500||
           f.raw_ms>f.clock.before||f.clock.after-f.raw_ms>5000||f.raw_ms<last_){ready_=false;first_=last_=0;samples_=0;return false;}
        if(ready_){last_=f.raw_ms;return true;} // Other route/location gates still enforce safe movement/lag.
        if(metres(f.raw_lat,f.raw_lng,b.latitude,b.longitude)>5||metres(f.game_lat,f.game_lng,b.latitude,b.longitude)>5||
           metres(f.raw_lat,f.raw_lng,f.game_lat,f.game_lng)>5){first_=last_=0;samples_=0;return false;}
        if(!samples_){first_=last_=f.raw_ms;samples_=1;return false;}
        if(f.raw_ms>last_){last_=f.raw_ms;++samples_;}
        ready_=samples_>=2&&last_-first_>=500;return ready_;
    }
};
}
