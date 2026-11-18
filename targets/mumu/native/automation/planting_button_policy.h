#pragma once
#include <cstddef>

namespace pcc_planting_button {
// Managed class identity, not a class-name whitelist. A serialized Button
// field can hold a derived Button. Bound traversal and reject corrupt cycles.
template<class Parent>
bool compatible(void *actual, void *button, Parent parent) {
    if (!actual || !button) return false;
    void *seen[16]{};
    for (std::size_t depth=0; depth<16 && actual; ++depth) {
        if (actual==button) return true;
        for (std::size_t i=0; i<depth; ++i) if (seen[i]==actual) return false;
        seen[depth]=actual;
        actual=parent(actual);
    }
    return false;
}
}
