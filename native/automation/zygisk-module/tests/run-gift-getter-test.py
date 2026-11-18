"""Exact production gift getters with fake metadata, in an isolated Android executable."""
import argparse, pathlib, re, subprocess, tempfile
p=argparse.ArgumentParser()
for key in ('compiler','adb','device'): p.add_argument('--'+key,required=True)
a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[1]
s=(root/'cpp/nectar_rpc.cpp').read_text(encoding='utf-8')
capture=s[s.index('void write_dispatch_candidates('):s.index('void *hooked_complete_pikmin_task')]
fence=capture.index('const auto type = gift_dispatch_type(gift_target);')
assert fence<capture.index('projection.append(') and fence<capture.index('available_expedition_pikmins(')
assert capture.count('!is_gift || ordinary_gift_dispatch_allowed(gift_target)')==2
functions=[]
for name in ('pikmin::GiftDispatchType gift_dispatch_type','bool ordinary_gift_dispatch_allowed'):
    m=re.search(re.escape(name)+r'\(void \*gift\) \{.*?\n\}',s,re.S);assert m;functions.append(m[0])
harness=r'''
#include "gift_dispatch_policy.h"
#include <cassert>
#include <cstdio>
struct Gift { int item, box; bool deco, rare; };
static int calls;
int item(void *p,const void*){++calls;return static_cast<Gift*>(p)->item;}
void *deco(void *p,const void*){++calls;return static_cast<Gift*>(p)->deco?p:nullptr;}
void *rare(void *p,const void*){++calls;return static_cast<Gift*>(p)->rare?p:nullptr;}
int box(void *p,const void*){++calls;return static_cast<Gift*>(p)->box;}
int (*get_gift_item_case)(void*,const void*)=item;
void *(*get_gift_deco)(void*,const void*)=deco;
void *(*get_gift_rare_deco)(void*,const void*)=rare;
int (*get_deco_box_type)(void*,const void*)=box;
'''
harness+='\n'.join(functions)
harness+=r'''
int main(){
 assert(!ordinary_gift_dispatch_allowed(nullptr)&&calls==0);
 Gift white{1,1,true,false};
 for(int missing=0;missing<4;++missing){
  get_gift_item_case=missing==0?nullptr:item;get_gift_deco=missing==1?nullptr:deco;
  get_gift_rare_deco=missing==2?nullptr:rare;get_deco_box_type=missing==3?nullptr:box;
  calls=0;assert(!ordinary_gift_dispatch_allowed(&white)&&calls==0);
 }
 get_gift_item_case=item;get_gift_deco=deco;get_gift_rare_deco=rare;get_deco_box_type=box;
 assert(ordinary_gift_dispatch_allowed(&white));
 Gift gold{4,1,false,true};assert(!ordinary_gift_dispatch_allowed(&gold));
 assert(gift_dispatch_type(&gold)==pikmin::GiftDispatchType::rare);
 for(int eventBox=0;eventBox<10;++eventBox){Gift g{1,eventBox,true,false};assert(ordinary_gift_dispatch_allowed(&g)==(eventBox==1));}
 Gift bad{1,1,true,true};assert(!ordinary_gift_dispatch_allowed(&bad));
 bad={1,1,false,false};assert(!ordinary_gift_dispatch_allowed(&bad));
 bad={99,1,true,false};assert(!ordinary_gift_dispatch_allowed(&bad));
 std::puts("PASS exact production gift wrappers: ordinary, rare, unknown/event/unset, null/missing getters; no game RPC");
}
'''
with tempfile.TemporaryDirectory(prefix='pcc-gift-getter-') as tmp:
 stage=pathlib.Path(tmp);cpp=stage/'test.cpp';binary=stage/'gift-getter';cpp.write_text(harness,encoding='utf-8')
 subprocess.run([a.compiler,'-std=c++20','-O2','-static-libstdc++','-I',str(root/'cpp'),str(cpp),'-o',str(binary)],check=True)
 remote='/data/local/tmp/'+stage.name
 for args in (['shell','mkdir','-m','700',remote],['push',str(binary),remote+'/test'],['shell','chmod','700',remote+'/test'],['shell',remote+'/test']):
  subprocess.run([a.adb,'-s',a.device]+args,check=True,timeout=30)
