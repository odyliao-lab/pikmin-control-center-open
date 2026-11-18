#pragma once
#include <cstdint>
#include <string>

// MuMu-only producer policy. Backend must read confirmed inventory on the
// original game thread, never resolve/create services or invoke a mutation.
namespace emu_daily_steps {
struct Binding {
    int pid=0;
    int64_t epoch=0;
    std::string account;
    bool operator==(const Binding &b) const {return pid==b.pid && epoch==b.epoch && account==b.account;}
    bool valid() const {
        if(pid<=0 || epoch<=0 || account.size()!=64)return false;
        for(char c:account)if(!((c>='0' && c<='9')||(c>='a' && c<='f')))return false;
        return true;
    }
};
struct Values {
    std::string day;
    int64_t today=-1,canonical=-1;
    bool operator==(const Values &v) const {return day==v.day && today==v.today && canonical==v.canonical;}
};
inline bool day_valid(const std::string &s) {
    if(s.size()!=10 || s[4]!='-' || s[7]!='-')return false;
    for(size_t i=0;i<s.size();++i)if(i!=4 && i!=7 && (s[i]<'0'||s[i]>'9'))return false;
    int y=std::stoi(s.substr(0,4)),m=std::stoi(s.substr(5,2)),d=std::stoi(s.substr(8,2));
    if(y<1000 || m<1 || m>12 || d<1)return false;
    const int days[]={31,28,31,30,31,30,31,31,30,31,30,31};
    int limit=days[m-1]+(m==2 && y%4==0 && (y%100!=0 || y%400==0));
    return d<=limit;
}
struct Snapshot {
    Binding binding;
    Values values;
    int64_t captured=0,revision=0;
    bool complete=false;
};
template<class Backend> Snapshot capture(Backend &b) {
    Snapshot s;
    s.captured=b.now();s.binding=b.binding();
    if(s.captured<=0 || !s.binding.valid() || !b.ready())return s;
    s.revision=b.revision();
    if(s.revision<=0 || !b.read_confirmed(s.values) || !day_valid(s.values.day) ||
       s.values.today<0 || s.values.canonical<0)return s;
    Values again;
    if(!b.read_confirmed(again) || !(s.values==again))return s;
    const auto end=b.now();
    s.complete=b.ready() && s.binding==b.binding() && s.revision==b.revision() &&
        end>=s.captured && end-s.captured<=200;
    return s;
}
inline std::string packet(const Snapshot &s) {
    // Incomplete observation is never encoded as zero steps or a guessed day.
    if(!s.complete || !s.binding.valid() || !day_valid(s.values.day) ||
       s.captured<=0 || s.revision<=0 || s.values.today<0 || s.values.canonical<0)return {};
    return "v1\t"+std::to_string(s.captured)+"\t"+std::to_string(s.binding.pid)+"\t"+
        std::to_string(s.binding.epoch)+"\t"+s.binding.account+"\t157.0\tcomplete\t"+
        s.values.day+"\t"+std::to_string(s.values.today)+"\t"+std::to_string(s.values.canonical)+
        "\t"+std::to_string(s.revision)+"\tplayer-statistics-v157\tend\n";
}
}
