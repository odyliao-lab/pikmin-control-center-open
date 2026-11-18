#include <cassert>
#include <cstdio>
#include "../native/automation/planting_catalogue_policy.h"
#include "../native/automation/independent_stock_policy.h"
int main(){
 using namespace emu_petal;
 static_assert(reserve==50);
 Snapshot s{10000,1,42,1,std::string(64,'a'),true,{}};
 int checks=0;
 for(bool ordinary:{false,true})for(int n:{0,20,49,50,51,999}){
  s.rows={{"a",1,n,ordinary}};
  assert((available(s,"a")!=nullptr)==(n>50));++checks;
 }
 s.rows={{"a",1,51,false},{"b",2,1000,true}};
 assert(available(s,"a")->id=="a");++checks;
 s.rows[0].stock=50;assert(available(s,"a")->id=="b");++checks;
 s.rows={{"c",2,600,false},{"b",2,600,false},{"a",3,600,true}};
 assert(available(s,"")->id=="b");++checks;
 s.complete=false;assert(!available(s,"b"));++checks;s.complete=true;
 s.rows.push_back(s.rows.front());assert(!available(s,"b"));++checks;
 using D=emu_independent_stock::Decision;
 for(int n:{0,20,49,50,51,999}){
  assert(emu_independent_stock::decide(true,true,true,true,true,n,true)==(n>50?D::keep:D::rotate));++checks;
 }
 assert(emu_independent_stock::decide(true,true,true,false,true,50,true)==D::unknown);++checks;
 assert(emu_independent_stock::decide(true,true,true,true,false,50,true)==D::unknown);++checks;
 assert(emu_independent_stock::decide(true,true,true,true,true,-1,true)==D::unknown);++checks;
 assert(emu_independent_stock::decide(true,true,true,true,true,50,false)==D::unknown);++checks;
 assert(emu_independent_stock::decide(false,true,true,true,true,50,true)==D::unknown);++checks;
 assert(emu_independent_stock::decide(true,false,true,true,true,50,true)==D::unknown);++checks;
 assert(emu_independent_stock::decide(true,true,false,false,false,-1,false)==D::rotate);++checks;
 std::printf("PASS all flower kinds / 50 reserve %d isolated native policy checks; NOT game RPC evidence\n",checks);
}
