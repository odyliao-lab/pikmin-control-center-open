#include "../cpp/feeding_catalogue.h"
#include <cassert>
#include <set>
#include <cstdio>
int main() {
    using feeding_catalogue::family;
    assert(family(5, "") == "flower-5");
    assert(family(0, "rose2025") == "flower-s-rose2025");
    assert(family(5, "rose2025") == "flower-s-rose2025");
    assert(family(0, "") == "flower-5"); // ordinary nectar may omit both fields
    assert(family(0, "")==family(5, ""));
    assert(family(-1, "rose").empty());
    assert(family(0, "rose\tbad").empty());
    assert(family(0, "rose\nbad").empty());
    assert(family(0, "rose?bad").empty());
    assert(family(0, std::string(81, 'a')).empty());
    assert(family(0, std::string(80, 'a')).size() + 2 <= 96);
    std::set<std::string> keys;
    keys.insert(family(5, ""));
    for (int n = 0; n < 35; ++n) assert(keys.insert(family(0, "kind-" + std::to_string(n))).second);
    assert(keys.size() == 36);
    assert(family(0, "5") != family(5, ""));
    std::puts("PASS feeding catalogue: 36 kinds, string/legacy identity and invalid input guards");
}
