#include "../cpp/gift_dispatch_policy.h"
#include "../cpp/nearby_policy.h"
#include <cassert>
#include <cstdio>
using namespace pikmin;
int main() {
    int checks = 0;
    for (bool ready : {false, true}) for (int item : {-1, 0, 1, 2, 3, 4, 5, 99})
    for (bool deco : {false, true}) for (bool rare : {false, true})
    for (int box : {-1, 0, 1, 2, 3, 4, 99}) {
        const auto result = gift_dispatch_type(ready, item, deco, rare, box);
        const bool white = ready && item == 1 && deco && !rare && box == 1;
        assert((result == GiftDispatchType::ordinary_white) == white);
        if (result == GiftDispatchType::rare) assert(ready && item == 4 && rare && !deco);
        assert(nearby_kind_allowed("all", nearby_selection("fruit seed gift"), "fruit"));
        assert(nearby_kind_allowed("all", nearby_selection("fruit seed gift"), "seed"));
        ++checks;
    }
    assert(!nearby_kind_allowed("all", nearby_selection("fruit seed"), "gift"));
    assert(!nearby_kind_allowed("farm", nearby_selection("fruit seed gift"), "gift"));
    std::printf("PASS gift dispatch policy %d metadata combinations; fruit/seed and gift selection preserved\n", checks);
}
