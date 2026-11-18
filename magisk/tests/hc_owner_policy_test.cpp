#include "../cpp/hc_owner_policy.h"
#include <cassert>
#include <cstdio>
int main(){
    int n=0;
    for(unsigned uid:{10000u,10023u,99999u})for(auto path:{"/data/user/0/com.nianticlabs.pikmin","/data/data/com.nianticlabs.pikmin"}){assert(pcc::hc::primary_owner(path,uid));++n;}
    for(unsigned uid:{0u,9999u,100000u,1010023u,0xffffffffu}){assert(!pcc::hc::primary_owner("/data/user/0/com.nianticlabs.pikmin",uid));++n;}
    for(auto path:{"","/data/user/10/com.nianticlabs.pikmin","/data/user/0/other","/data/user/0/com.nianticlabs.pikmin/","/data/user/0/com.nianticlabs.pikmin.clone"}){assert(!pcc::hc::primary_owner(path,10023));++n;}
    std::printf("PASS HC primary-space ownership: %d UID/path boundary cases; no other-user hooks or file writes\n",n);
}
