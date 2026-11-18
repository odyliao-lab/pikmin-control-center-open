#pragma once
namespace pcc {
enum class PathState { Missing, Present, Unknown };
// Unknown is not absence; pending GPS install could reintroduce duplicate hooks.
constexpr bool allow_unified(PathState legacy, PathState disabled, PathState staged) {
    return staged == PathState::Missing &&
        (legacy == PathState::Missing ||
         (legacy == PathState::Present && disabled == PathState::Present));
}
}
