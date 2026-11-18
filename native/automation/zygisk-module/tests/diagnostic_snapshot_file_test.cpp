#include "../cpp/diagnostic_snapshot_file.h"
#include <cassert>
#include <dirent.h>
#include <fstream>
#include <iostream>
#include <set>
using pikmin::diagnostic_snapshot::publish;
static std::string read(const std::string &p) {
    std::ifstream f(p);return {std::istreambuf_iterator<char>(f),{}};
}
static std::set<std::string> entries(const std::string &p) {
    std::set<std::string> result;auto d=opendir(p.c_str());assert(d);
    while(auto e=readdir(d))if(std::string(e->d_name)!="."&&std::string(e->d_name)!="..")result.insert(e->d_name);
    closedir(d);return result;
}
int main() {
    std::string base=access("/data/local/tmp",W_OK)==0?"/data/local/tmp":"/tmp";
    auto directory=base+"/pcc-diagnostic-file-XXXXXX";assert(mkdtemp(directory.data()));
    auto path=directory+"/snapshot",orphan=path+".tmp",secret=directory+"/secret";
    std::ofstream(orphan)<<"prior-dead-pid";std::ofstream(secret)<<"must-retain";
    assert(publish(path,"current-real-observation"));assert(read(path)=="current-real-observation");
    assert(read(orphan)=="prior-dead-pid");
    assert(unlink(orphan.c_str())==0);assert(symlink(secret.c_str(),orphan.c_str())==0);
    assert(publish(path,"new-observation"));assert(read(secret)=="must-retain");
    struct stat st{};assert(lstat(orphan.c_str(),&st)==0&&S_ISLNK(st.st_mode));
    assert(unlink(path.c_str())==0);assert(symlink(secret.c_str(),path.c_str())==0);
    assert(publish(path,"replace-link-only"));assert(read(secret)=="must-retain");
    assert(lstat(path.c_str(),&st)==0&&S_ISREG(st.st_mode)&&st.st_uid==getuid()&&st.st_nlink==1&&(st.st_mode&0777)==0644);
    auto blocked=directory+"/blocked";assert(mkdir(blocked.c_str(),0700)==0);
    auto before=entries(directory);assert(!publish(blocked,"rename-must-fail"));
    assert(entries(directory)==before);assert(read(path)=="replace-link-only");
    assert(!publish(path,""));assert(!publish(path,std::string(8193,'x')));assert(entries(directory)==before);
    assert(!publish(directory+"/missing-parent/file","no-parent"));
    for(int i=0;i<64;++i){auto row="fresh-"+std::to_string(i);assert(publish(path,row));assert(read(path)==row);}
    assert(entries(directory)==before);assert(read(secret)=="must-retain");
    assert(unlink(path.c_str())==0);assert(unlink(orphan.c_str())==0);assert(unlink(secret.c_str())==0);
    assert(rmdir(blocked.c_str())==0);assert(rmdir(directory.c_str())==0);
    std::cout<<"PASS production diagnostic writer: prior orphan, symlink isolation, failed rename cleanup, bounds and64 refreshes; no transaction files\n";
}
