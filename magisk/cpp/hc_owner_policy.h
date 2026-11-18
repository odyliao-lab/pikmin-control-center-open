#pragma once
#include <string_view>
namespace pcc::hc {
constexpr bool primary_owner(std::string_view directory,unsigned uid) {
    return uid>=10000 && uid<100000 &&
        (directory=="/data/user/0/com.nianticlabs.pikmin" || directory=="/data/data/com.nianticlabs.pikmin");
}
}
