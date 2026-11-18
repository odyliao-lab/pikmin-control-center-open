#include "../cpp/planter_recovery.h"
#include <cassert>
#include <cstring>
#include <cstdio>
using namespace pikmin::planter;
int main(){
 Observation o;assert(!o.expiry(0));o.begin(100,1,"a");
 assert(o.same_session(1,"a")&&!o.same_session(2,"a")&&!o.same_session(1,"b")&&!o.same_session(1,""));
 assert(!o.expiry(30099));assert(!std::strcmp(o.expiry(30100),"rpc-timeout-unknown"));
 o.response(30100);assert(!o.expiry(60099));assert(!std::strcmp(o.expiry(60100),"inventory-timeout-unknown"));
 assert(!std::strcmp(o.expiry(99),"clock-reset-unknown"));
 Adoption a;a.wait(100,true);assert(!a.observe(true,1,"a",100));assert(!a.observe(true,1,"a",30099));assert(a.observe(true,1,"a",30100));
 assert(!a.observe(false,1,"a",30101));assert(!a.observe(true,1,"a",30102));assert(a.observe(true,1,"a",30103));
 assert(!a.observe(true,2,"a",30104));assert(!a.observe(true,2,"b",30105));assert(a.observe(true,2,"b",30106));
 assert(!a.observe(true,2,"b",0));assert(!a.observe(true,2,"b",29999));assert(a.observe(true,2,"b",30000));
 a.wait(30000,false);assert(!a.observe(true,2,"b",30000));assert(!a.observe(true,2,"b",34999));assert(a.observe(true,2,"b",35000));
 std::puts("PASS continuous planter deadline/session/adoption boundaries");
}
