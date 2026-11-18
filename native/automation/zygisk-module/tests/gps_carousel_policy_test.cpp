#include "../cpp/gps_carousel_policy.h"
#include <cassert>
int main() {
    gps_carousel::Selection s;
    assert(!s.observe(u"A",0,true));assert(!s.observe(u"A",399,true));
    assert(s.observe(u"A",400,true));s.copied();assert(!s.observe(u"A",2000,true));
    assert(!s.observe(u"B",2100,true));assert(s.observe(u"B",2500,true));s.copied();
    assert(!s.observe(u"A",2600,true));assert(s.observe(u"A",3000,true));s.copied();
    // Rapid movement only copies the final stable page.
    assert(!s.observe(u"B",3100,true));assert(!s.observe(u"C",3200,true));
    assert(!s.observe(u"C",3599,true));assert(s.observe(u"C",3600,true));s.copied();
    // OFF, ambiguous, closed, unknown, recycled and retry paths.
    assert(!s.observe(u"C",3700,false));assert(!s.observe(u"C",3800,true));
    assert(s.observe(u"C",4200,true));s.copied();
    assert(!s.observe({},4300,true));assert(!s.observe(u"C",4400,true));
    assert(s.observe(u"C",4800,true));s.cancel();
    assert(!s.observe(u"A",5000,true));assert(!s.observe(u"B",5100,true));
    assert(!s.observe(u"A",5200,true));assert(s.observe(u"A",5600,true));
}
