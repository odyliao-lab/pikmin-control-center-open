#include <cassert>
#include <cstdio>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include "planting_continuation.h"
#include "planting_transaction_policy.h"
#include "task_exclusion.h"
namespace emu_planting_tx {
State state;bool storage_failed{},runtime_metadata_ready=true;
const char *result_wait_reason="not-observed";
namespace emu_late {bool bound=true,revoked=false;void *reference(void*,const char*);}
struct Observation{bool known=true,active=false;};Observation observation;
namespace emu_planting_observation {Observation read(){return observation;}}
struct Registry{unsigned current_epoch(){return 1;}}capability_registry;
std::string control_mode="off";
std::string read_planting_control_mode(){return control_mode;}
std::string directory,account(64,'a'),token,previous_token,owned_petal;
unsigned epoch=1;long long clock_ms=1000,owned_started{},previous_started{};
bool close_attempted{},reserve_stop{},planting_control_owned{},manual=true,ui=true,reserved=false;
struct FakeTask{bool complete;};FakeTask pending{false};
void *task{},*init_task{},*close_task{},*new_result{},*controller_result{},*planting_controller=&pending;
int forgotten{};
unsigned task_root=1,init_root=1,close_root=1,new_root=1;
std::string nectar_account_binding(){return account;}
bool manual_start_clear(){return manual;}bool result_hook_ready(){return true;}
bool planting_ui_clear(){return ui;}bool command_namespace_reserved(){return reserved;}
long long now_ms(){return clock_ms;}
bool task_bool_for(void *p,const char*,bool& value){value=static_cast<FakeTask*>(p)->complete;return true;}
void *emu_late::reference(void*,const char*){return controller_result;}
bool read_terminal_controller_result(void*& p){p=controller_result;return true;}
bool same_session(){return true;}
std::string path(){return directory+"/transaction";}
void forget_task(){task=nullptr;++forgotten;}void forget_result(){init_task=close_task=new_result=nullptr;}
namespace selection {
bool initialized{},review{},pending{},confirmed{};
std::string desired,lease_token,session_account;unsigned session_epoch{};
std::string path(){return emu_planting_tx::path()+".selection";}
}
#include "planting_selection_under_test.inc"
#include "planting_continue_under_test.inc"
}
int main(int argc,char**argv){
    assert(argc==2);using namespace planting_continuation;using namespace emu_planting_tx;
    directory=argv[1];assert(mkdir(directory.c_str(),0700)==0);
    assert(pikmin::account_digest("abc")=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assert(task_exclusion::allowed(directory,account,"old-target"));
    const std::string exclusion="PCCEXCLUDE/1\t"+account+"\t"+pikmin::account_digest("old-target")+"\t"+std::string(64,'b')+"\tUNKNOWN\tend\n";
    assert(preserve(directory+"/"+task_exclusion::leaf(account,"old-target"),exclusion));
    assert(!task_exclusion::allowed(directory,account,"old-target"));
    assert(task_exclusion::allowed(directory,account,"new-target"));
    assert(task_exclusion::allowed(directory,std::string(64,'c'),"old-target"));
    assert(!task_exclusion::allowed(directory,"","old-target"));
    const std::string id="11111111-1111-1111-1111-111111111111";
    const std::string row="v3\t999999\t1\t"+account+"\t"+id+"\treview\t100\t0\t0\tend\n";
    assert(original(row,account));assert(!original(row,std::string(64,'b')));
    assert(!original(row.substr(0,row.size()-1),account));assert(!original(row,std::string(64,'z')));
    assert(preserve(path(),row));assert(preserve(path(),row));assert(!preserve(path(),row+"x"));
    const std::string selected="v1\t999999\t1\t"+account+"\t"+id+"\tconfirmed\twhite\tend\n";
    assert(preserve(selection::path(),selected));
    const auto archive=directory+"/planting-unconfirmed-"+id+".tsv";
    auto observe=[&](int ticks){for(int n=0;n<ticks;++n){clock_ms+=5000;continue_after_unknown();}};
    state.phase=Phase::review;task=&pending;observe(9);
    assert(state.phase==Phase::review&&access(archive.c_str(),F_OK)!=0&&forgotten==0);
    pending.complete=true;controller_result=&pending;observe(9);assert(state.phase==Phase::review);
    controller_result=nullptr;task_root=0;observe(9);assert(state.phase==Phase::review&&forgotten==0);
    task_root=1;
    controller_result=nullptr;manual=false;observe(9);assert(state.phase==Phase::review);
    manual=true;observation.active=true;observe(9);assert(state.phase==Phase::review);
    observation.active=false;observe(3);clock_ms+=20000;continue_after_unknown();
    assert(state.phase==Phase::review); // gaps do not count as quiet observation
    observe(7);assert(state.phase==Phase::idle&&previous_token==id&&token.empty()&&forgotten==1);
    std::string saved;bool exists=false;
    assert(read(archive,saved,exists)&&exists&&saved==row);
    assert(read(archive+".selection",saved,exists)&&exists&&saved==selected);
    assert(read(path(),saved,exists)&&exists&&saved==row); // original not deleted/rewritten
    const std::string live_selection="v1\t"+std::to_string(getpid())+"\t1\t"+account+"\t"+id+"\tconfirmed\twhite\tend\n";
    assert(unlink(selection::path().c_str())==0);assert(preserve(selection::path(),live_selection));
    assert(!preserve_unknown_selection(directory+"/live-selector",row,false));
    selection::confirmed=true;selection::lease_token=id;selection::session_epoch=1;selection::session_account=account;
    assert(preserve_unknown_selection(directory+"/live-selector",row,false));
    selection::pending=true;assert(!preserve_unknown_selection(directory+"/pending-selector",row,false));selection::pending=false;
    assert(!token_available(directory,id));
    assert(token_available(directory,"22222222-2222-2222-2222-222222222222"));
    // Same PID after reboot is not the old owner. Provenance is tied to the
    // original v3 bytes, and native reads the actual kernel boot independently.
    const auto boot=current_boot();assert(uuid(boot));std::string older_boot=boot;older_boot[0]=boot[0]=='a'?'b':'a';
    const std::string reused="22222222-2222-2222-2222-222222222222",pid=std::to_string(getpid());
    const auto reused_row="v3\t"+pid+"\t1\t"+account+"\t"+reused+"\treview\t100\t0\t0\tend\n";
    const auto proof="PCCPLANTOWNER/1\t"+older_boot+"\t"+pid+"\t1\t1\t"+account+"\t"+reused+"\t"+pikmin::account_digest(reused_row)+"\tend\n";
    assert(preserve(directory+"/planting-dead-owner-"+reused+".tsv",proof));
    assert(dead_owner_proof(directory,reused_row));assert(!dead_owner_proof(directory,reused_row+"x"));
    std::string ticks;bool live=false;assert(process_ticks(pid,ticks,live)&&live);
    const std::string current="33333333-3333-3333-3333-333333333333";
    const auto live_row="v3\t"+pid+"\t1\t"+account+"\t"+current+"\treview\t100\t0\t0\tend\n";
    const auto live_proof="PCCPLANTOWNER/1\t"+boot+"\t"+pid+"\t"+ticks+"\t1\t"+account+"\t"+current+"\t"+pikmin::account_digest(live_row)+"\tend\n";
    assert(preserve(directory+"/planting-dead-owner-"+current+".tsv",live_proof));assert(!dead_owner_proof(directory,live_row));
    // Actual recovery for the live failure: a stopped predecessor and a
    // DIFFERENT archived selector from a still older, absent process.
    const auto original_directory=directory;directory+="/terminal-case";assert(mkdir(directory.c_str(),0700)==0);
    const auto terminal_row="v3\t888888\t1\t"+account+"\t"+reused+"\tstopped-confirmed\t300\t1\t100\tend\n";
    const auto settled="PCCSETTLEMENT/1\t"+account+"\t888888\t1\t"+reused+"\t100\t200\t42\tclosed-confirmed\tend\n";
    assert(terminal_selector(terminal_row,selected,row,selected,settled,account));
    for(const auto& invalid:{terminal_row.substr(0,terminal_row.size()-1),terminal_row+"x",row})
        assert(!terminal_selector(invalid,selected,row,selected,settled,account));
    assert(!terminal_selector(terminal_row,selected,row,selected,settled,std::string(64,'b')));
    assert(!terminal_selector(terminal_row,selected,row,selected+"x",settled,account));
    assert(!terminal_selector(terminal_row,selected,row,selected,settled+"x",account));
    assert(preserve(path(),terminal_row));assert(preserve(selection::path(),selected));
    assert(preserve(directory+"/planting-settlement-"+reused+".tsv",settled));
    state=State{};state.phase=Phase::review;token=current;task=init_task=close_task=new_result=nullptr;
    selection::pending=false;selection::review=true;controller_result=nullptr;
    observe(9);assert(state.phase==Phase::review); // archived selector proof absent
    assert(preserve(directory+"/planting-unconfirmed-"+id+".tsv",row));
    assert(preserve(directory+"/planting-unconfirmed-"+id+".tsv.selection",selected));
    control_mode="on";observe(9);assert(state.phase==Phase::review); // no recovery while admission ON
    control_mode="off";task=&pending;pending.complete=false;observe(9);assert(state.phase==Phase::review);
    pending.complete=true;controller_result=&pending;observe(9);assert(state.phase==Phase::review);
    controller_result=nullptr;task=nullptr;observe(3);assert(state.phase==Phase::review);observe(5);
    assert(state.phase==Phase::idle&&previous_token==reused&&token.empty()&&!selection::review);
    assert(read(path(),saved,exists)&&exists&&saved==terminal_row); // terminal never becomes UNKNOWN
    assert(read(directory+"/planting-terminal-"+reused+".tsv",saved,exists)&&exists&&saved==terminal_row);
    assert(read(directory+"/planting-retired-selection-"+id+".tsv",saved,exists)&&exists&&saved==selected);
    assert(access(selection::path().c_str(),F_OK)!=0);
    // Simulated process-cache reset after selector retirement: immutable copies
    // allow the same guarded proof to finish, no selector or terminal replay.
    state.phase=Phase::review;selection::review=true;observe(9);assert(state.phase==Phase::idle&&!selection::review);
    assert(read(path(),saved,exists)&&exists&&saved==terminal_row);
    directory=original_directory;
    assert(symlink(path().c_str(),(directory+"/symlink").c_str())==0);
    assert(!read(directory+"/symlink",saved,exists));
    assert(mkfifo((directory+"/fifo").c_str(),0600)==0);assert(!read(directory+"/fifo",saved,exists));
    assert(link(path().c_str(),(directory+"/hardlink").c_str())==0);assert(!read(path(),saved,exists));
    puts("PASS actual continuation: no pending/manual/result/active bypass; durable UNKNOWN, no rewrite; FIFO/symlink/hardlink rejected");
}
