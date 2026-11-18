#pragma once
#include "feeding_policy.h"
#include <map>
#include <string>
#include <vector>

namespace feeding_config {
struct Rule { int reserve{-1}, ceiling{-1}; };
struct Config {
    bool rotate{};
    feeding_policy::Config defaults;
    std::string revision;
    std::map<std::string,Rule> rules;
    std::vector<std::string> order;
    feeding_policy::Config for_kind(const std::string &id) const {
        auto c=defaults; auto it=rules.find(id); c.allowed=it!=rules.end();
        if(c.allowed && it->second.reserve>=0) {c.reserve=it->second.reserve;c.ceiling=it->second.ceiling;}
        return c;
    }
};
inline std::vector<std::string> split(const std::string &s,char separator) {
    std::vector<std::string> out; size_t start=0,pos;
    while((pos=s.find(separator,start))!=std::string::npos) {out.push_back(s.substr(start,pos-start));start=pos+1;}
    out.push_back(s.substr(start));return out;
}
inline bool number(const std::string &s,int lo,int hi,int &out) {
    if(s.empty()||s.size()>7)return false;
    int value=0;for(unsigned char c:s) {if(c<'0'||c>'9')return false;value=value*10+c-'0';}
    if(value<lo||value>hi)return false;out=value;return true;
}
inline bool id(const std::string &s) {
    if(s.empty()||s.size()>96)return false;
    for(unsigned char c:s)if(!((c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='_'||c=='.'||c==':'||c=='-'))return false;
    return true;
}
// Parse atomically into a temporary object. Caller must discard prior config on
// false: malformed/truncated input never authorises the previous enabled rules.
inline bool parse(const std::string &raw,Config &out) {
    out=Config{};
    if(raw.empty()||raw.size()>65536)return false;
    auto lines=split(raw,'\n');while(!lines.empty()&&lines.back().empty())lines.pop_back();
    if(lines.size()<2)return false;
    auto h=split(lines[0],'\t');
    const bool legacy=h.size()==8&&h[0]=="v1";
    const bool current=h.size()==9&&h[0]=="v2";
    const bool rotation=h.size()==10&&h[0]=="v3";
    if((!legacy&&!current&&!rotation)||h[1].size()!=36||(h[2]!="0"&&h[2]!="1")||(h[6]!="0"&&h[6]!="1")||(!legacy&&(h[7]!="0"&&h[7]!="1"))||(rotation&&(h[8]!="0"&&h[8]!="1")))return false;
    for(char c:h[1])if(!((c>='a'&&c<='f')||(c>='0'&&c<='9')||c=='-'))return false;
    Config next;int hearts,reserve,ceiling,count;
    if(!number(h[3],1,8,hearts)||!number(h[4],0,1000000,reserve)||!number(h[5],1,1000000,ceiling)||!number(h[legacy?7:rotation?9:8],0,512,count))return false;
    if(lines.size()!=static_cast<size_t>(count+2)||lines.back()!="end")return false;
    next.revision=h[1];next.defaults.enabled=h[2]=="1";next.defaults.collect=h[6]=="1";
    next.defaults.hearts=hearts;next.defaults.reserve=reserve;next.defaults.ceiling=ceiling;
    // Existing v1 files migrate to the new product default: aggressive.
    next.defaults.aggressive_pick=legacy||h[7]=="1";
    next.rotate=rotation&&h[8]=="1";
    for(int i=1;i<=count;++i) {
        auto f=split(lines[i],'\t');if(f.size()!=4||f[0]!="rule"||!id(f[1]))return false;
        Rule r;
        if(!(f[2]=="-1"&&f[3]=="-1")) {
            if(!number(f[2],0,1000000,r.reserve)||!number(f[3],1,1000000,r.ceiling))return false;
        }
        if(!next.rules.emplace(f[1],r).second)return false;
        next.order.push_back(f[1]);
    }
    out=std::move(next);return true;
}
}
