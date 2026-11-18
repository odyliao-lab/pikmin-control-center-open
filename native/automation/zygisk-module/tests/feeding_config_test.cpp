#include "../cpp/feeding_config.h"
#include <cassert>
#include <cstdio>
int main() {
    using namespace feeding_config;
    const std::string header="v1\t01234567-89ab-cdef-0123-456789abcdef\t1\t4\t100\t250\t1\t";
    Config c;
    assert(parse(header+"2\nrule\tflower-5:1\t-1\t-1\nrule\tflower-s-rose:2\t300\t400\nend\n",c));
    assert(c.order.size()==2&&c.order[0]=="flower-5:1");
    assert(c.for_kind("flower-5:1").reserve==100&&c.for_kind("flower-5:1").ceiling==250);
    assert(c.for_kind("flower-s-rose:2").reserve==300&&c.for_kind("flower-s-rose:2").ceiling==400);
    assert(c.defaults.aggressive_pick); // v1 migrates to the new default.
    const std::string v2="v2\t01234567-89ab-cdef-0123-456789abcdef\t1\t4\t100\t250\t1\t0\t0\nend\n";
    assert(parse(v2,c)&&!c.defaults.aggressive_pick);
    assert(!c.for_kind("unlisted").allowed);
    assert(!parse(header+"1\nrule\tx\t-1\t100\nend\n",c)&&!c.defaults.enabled&&c.rules.empty());
    assert(!parse(header+"2\nrule\tx\t-1\t-1\nrule\tx\t1\t2\nend\n",c));
    assert(!parse(header+"1\nrule\tx\t1\t0\nend\n",c));
    assert(!parse(header+"0\n",c));
    assert(!parse(header+"0\nend\nextra\n",c));
    assert(!parse(header+"513\nend\n",c));
    assert(!parse(std::string(65537,'a'),c));
    std::string many=header+"512\n";
    for(int i=0;i<512;++i)many+="rule\tkind:"+std::to_string(i)+"\t-1\t-1\n";
    assert(parse(many+"end\n",c)&&c.rules.size()==512);
    assert(!parse("broken",c)&&!c.defaults.enabled&&c.order.empty());
    std::puts("PASS native feeding config: limits, overrides, order, duplicates and fail-closed reset; NOT executor validation");
}
