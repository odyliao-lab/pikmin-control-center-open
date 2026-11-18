#pragma once
#include <cstdint>
namespace emu_independent_stock {
enum class Decision { unknown, keep, rotate };
// No guessed stock. A temporary unavailable catalogue does not interrupt an
// owned planting session. The independent permit expiry/global OFF still stop it.
inline Decision decide(bool same_session,bool selected_known,bool selected_matches,
                       bool coherent,bool found,int64_t stock,
                       bool rechecked,int64_t reserve=50) {
    if(!same_session||!selected_known)return Decision::unknown;
    if(!selected_matches)return Decision::rotate;
    if(!coherent||!found||!rechecked||stock<0)return Decision::unknown;
    return stock>reserve?Decision::keep:Decision::rotate;
}
}
