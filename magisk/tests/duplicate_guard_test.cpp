#include <cassert>
#include <cstdio>
#include <initializer_list>
#include "../cpp/duplicate_guard.h"
int main() {
    using pcc::PathState;
    int allowed=0;
    for(auto old:{PathState::Missing,PathState::Present,PathState::Unknown})
      for(auto disabled:{PathState::Missing,PathState::Present,PathState::Unknown})
        for(auto staged:{PathState::Missing,PathState::Present,PathState::Unknown}) {
            bool expected=staged==PathState::Missing&&(old==PathState::Missing||(old==PathState::Present&&disabled==PathState::Present));
            assert(pcc::allow_unified(old,disabled,staged)==expected);
            allowed+=expected;
        }
    assert(allowed==4);
    puts("PASS 27 duplicate-loader states (policy only)");
}
