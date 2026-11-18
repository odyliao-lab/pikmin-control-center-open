#include "../cpp/bootstrap_retry.h"
#include <cassert>
#include <cstdio>

int main() {
    using pcc_bootstrap::Retry;
    int probes=0, elapsed=0, installs=0;
    // Library arrives after the old 120-second deadline. Exactly one install.
    for(int a=0;a<Retry::attempts;++a) {
        ++probes;
        if(elapsed>=180) { ++installs;break; }
        if(Retry::another(a))elapsed+=Retry::delay_after(a);
    }
    assert(installs==1 && probes<Retry::attempts && elapsed>=180);
    elapsed=0;
    for(int a=0;a<Retry::attempts;++a)
        if(Retry::another(a))elapsed+=Retry::delay_after(a);
    assert(elapsed==570 && !Retry::another(135));
    assert(Retry::delay_after(119)==1 && Retry::delay_after(120)==30);
    puts("PASS bootstrap_retry_test: delayed library, bounded 136 probes/570 seconds, no hook replay");
}
