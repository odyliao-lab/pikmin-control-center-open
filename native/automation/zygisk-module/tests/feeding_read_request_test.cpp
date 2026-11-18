#include "../cpp/feeding_read_request.h"
#include <cassert>
#include <cstdio>
int main(){
    using feeding_read_request::valid;
    const std::string request="v1\t1000\t42\n";
    assert(valid(request,1000,42));assert(valid(request,11000,42));
    assert(!valid(request,11001,42));assert(!valid(request,999,42));assert(!valid(request,1000,43));
    assert(!valid(request+"junk",1000,42));assert(!valid("v1\t01000\t42\n",1000,42));
    assert(!valid("v1\t-1\t42\n",1000,42));assert(!valid("v1\t1000\t42",1000,42));
    std::puts("PASS feeding read request PID, expiry, future and exact framing; no spending permission");
}
