#include "root_gps_fence.h"
#include "root_gps_observation.h"
#include "root_gps_ipc.h"
#include "root_provider_journal.h"
#include "root_provider_stop.h"
#include <cstdio>
#include <poll.h>

// Standalone Root lifetime actor. Launcher must authenticate these bytes.
// Root-owned fence-only observation; no shell, intent, game API,
// OwnerAuthority or WriterLease publication.
// Missing fixed directory refuses; provisioning belongs to the audited manager.
static bool nonce(const std::string &s){
    if(s.size()!=36)return false;
    for(size_t i=0;i<s.size();++i){
        if(i==8||i==13||i==18||i==23){if(s[i]!='-')return false;}
        else if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f')))return false;
    }
    return true;
}
int main(int argc,char **argv){
#ifdef PCC_GPS_FENCE_FIXTURE
    if(argc!=3&&argc!=4)return 1;
    const bool prepare=argc==4;
    if(prepare&&std::string(argv[3])!="--prepare-provider")return 1;
#else
    if(argc!=2&&argc!=3)return 1;
    const bool prepare=argc==3;
    if(prepare&&std::string(argv[2])!="--prepare-provider")return 1;
#endif
    if(geteuid()!=0||!nonce(argv[1]))return 1;
    emu_gps_work::RootGpsFence fence;
#ifdef PCC_GPS_FENCE_FIXTURE
    if(!fence.acquire_fixture(argv[2]))return 1;
#else
    if(!fence.acquire())return 1;
#endif
    const std::string token=argv[1];
    emu_gps_work::ProviderPreparation preparation;
    if(prepare&&!emu_gps_work::prepare_root_provider(fence,token,preparation))return 1;
    emu_gps_work::RootFenceIpcServer ipc;
    if(!ipc.start(fence,token))return 1;
    if(!emu_gps_work::publish_fence_observation(fence,token))return 1;
    if(std::printf("@gps-fence\t%d\t%s\n",getpid(),token.c_str())<0||std::fflush(stdout)!=0)return 1;
    std::string input;unsigned query=0;
    for(;;){
        // EOF is the lifetime owner. A polling timeout is NOT exit/restart.
        struct pollfd fd[2]{{STDIN_FILENO,POLLIN,0},{ipc.descriptor(),POLLIN,0}};
        int n;do{n=poll(fd,2,100);}while(n<0&&errno==EINTR);
        if(n<0||!fence.valid()||(fd[0].revents&(POLLERR|POLLNVAL))||(fd[1].revents&(POLLERR|POLLNVAL))||
           !emu_gps_work::publish_fence_observation(fence,token))return 1;
        if(n==0)continue;
        if(fd[1].revents&POLLIN){if(!ipc.serve_one())return 1;}
        if(!(fd[0].revents&(POLLIN|POLLHUP)))continue;
        char b{};ssize_t bytes;do{bytes=read(STDIN_FILENO,&b,1);}while(bytes<0&&errno==EINTR);
        if(bytes<0)return 1;
        if(bytes==0){
            if(!input.empty()||!fence.valid()||!fence.release())return 1;
            if(std::printf("@gps-released\t%s\n",token.c_str())<0||std::fflush(stdout)!=0)return 1;
            return 0;
        }
        if(b=='\n'){
            // Exact, monotonic request sequence; no command or mutation payload.
            if(query>=16||input!="Q\t"+std::to_string(query+1)||!fence.valid())return 1;
            input.clear();++query;
            if(std::printf("@gps-held\t%u\t%s\n",query,token.c_str())<0||std::fflush(stdout)!=0)return 1;
        }else{
            if(b=='\r'||b=='\0'||input.size()>=16)return 1;input.push_back(b);
        }
    }
}
