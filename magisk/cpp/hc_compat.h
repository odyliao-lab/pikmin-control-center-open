#pragma once
#include <jni.h>
void pcc_hc_start(JavaVM *vm,const char *directory,bool legacy_blocked);
void pcc_hc_heartbeat(const char *directory);
