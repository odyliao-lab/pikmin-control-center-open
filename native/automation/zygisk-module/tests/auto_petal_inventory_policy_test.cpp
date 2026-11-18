#include "../cpp/auto_petal_inventory_policy.h"
#include <cassert>
#include <iostream>
int main(){
 using auto_petal_inventory::due;
 assert(due(1000,0));assert(!due(9999,1000));assert(!due(10999,1000));assert(due(11000,1000));assert(due(1000,2000));
 long long last=0;int scans=0;for(long long now=1000;now<=31000;now+=1000)if(due(now,last)){last=now;scans++;}
 assert(scans==4);std::cout<<"PASS read-only inventory cadence: initial, bounded idle, rollback and no config dependency\n";
}
