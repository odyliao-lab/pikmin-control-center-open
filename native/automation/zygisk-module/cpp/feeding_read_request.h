#pragma once
#include <string>
#include <sstream>
#include <cstdint>
namespace feeding_read_request {
inline bool valid(const std::string &raw,int64_t now,int pid) {
    if(raw.size()>80 || pid<=0)return false;
    std::istringstream in(raw);std::string version,extra;int64_t at{},owner{};
    if(!(in>>version>>at>>owner) || (in>>extra) || version!="v1" || owner!=pid || at<=0 || at>now || now-at>10000)return false;
    return raw=="v1\t"+std::to_string(at)+"\t"+std::to_string(owner)+"\n";
}
}
