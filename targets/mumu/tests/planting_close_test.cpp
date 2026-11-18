#include "../native/automation/planting_transaction_policy.h"
#include "../native/automation/independent_stock_policy.h"
#include "../native/automation/planting_reward_policy.h"
#include <cstdio>
#include <cstdlib>
#include <initializer_list>
using namespace emu_planting_tx;
static int checks;
static void check(bool value){if(!value){std::printf("FAIL %d\n",checks);std::exit(1);}++checks;}
int main(){
 // Reproduce the live mode-ON reserve-stop reward denial. All existing
 // same-session, rooted-task, journal and freshness gates still apply.
 pcc_owned_reward::Context reward{2000,1000,1000,true,true,true,true,false,
     true,true,true,true,true,true,true,false};
 check(!pcc_owned_reward::authorized(reward));
 for(unsigned m=0;m<64;++m){bool known=m&1,active=m&2,clear=m&4,off=m&8,reserve=m&16,lease=m&32;
  reward.mode_off=cleared_result_allowed(known,active,clear,off,reserve,lease);
  check(pcc_owned_reward::authorized(reward)==(known&&!active&&clear&&(off||(reserve&&lease))));
 }
 reward.mode_off=true;check(pcc_owned_reward::collectible(reward));
 reward.same_session=false;check(!pcc_owned_reward::authorized(reward));reward.same_session=true;
 reward.now=61001;check(!pcc_owned_reward::authorized(reward));reward.now=2000;
 reward.consumed=true;check(!pcc_owned_reward::collectible(reward));reward.consumed=false;
 reward.record_owned=false;check(!pcc_owned_reward::authorized(reward));reward.record_owned=true;
 for(unsigned m=0;m<64;++m){bool known=m&1,active=m&2,clear=m&4,off=m&8,reserve=m&16,lease=m&32;
  check(cleared_result_allowed(known,active,clear,off,reserve,lease)==(known&&!active&&clear&&(off||(reserve&&lease))));
 }
 for(long stock:{0L,49L,50L,51L}){
  bool reserve=emu_independent_stock::decide(true,true,true,true,true,stock,true,50)==emu_independent_stock::Decision::rotate;
  check(reserve==(stock<=50));check(cleared_result_allowed(true,false,true,false,reserve,true)==reserve);
 }
 State stop;stop.submitted(false,1000);stop.observe(2000,true,true,false,Task::success,true);check(stop.phase==Phase::stop_pending);
 stop.observe(3000,true,true,false,Task::success,true);check(stop.phase==Phase::stopped);
 State late;late.submitted(false,1000);late.observe(61001,true,true,false,Task::success,true);check(late.phase==Phase::review);
 check(late.late_stop_window(120999));check(!late.late_stop_window(121001));
 State restored;restored.phase=Phase::review;restored.sent=1000;check(!restored.late_stop_window(61001));
 check(!cleared_result_allowed(true,true,true,false,true,true));
 check(!cleared_result_allowed(true,false,false,true,true,true));
 check(!cleared_result_allowed(true,false,true,false,true,false));
 std::printf("PASS owned reserve-close %d isolated policy checks; not gameplay\n",checks);
}
