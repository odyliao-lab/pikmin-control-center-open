#pragma once
namespace auto_petal_inventory {
constexpr long long interval_ms=10000;
constexpr bool due(long long now,long long last){return last<=0||now<last||now-last>=interval_ms;}
}
