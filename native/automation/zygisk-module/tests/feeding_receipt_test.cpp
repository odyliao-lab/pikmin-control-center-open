#include "../cpp/feeding_receipt.h"
#include <cassert>
#include <cstdio>
using namespace feeding_receipt;
int main() {
    feeding_pending::Intent i{"request-1",std::string(64,'a'),"feed","flower-5:3","pikmin-1",1000,101,240,1,1,{}};
    Evidence e{"request-1",std::string(64,'a'),"pikmin-1","flower-5:3",true,true,true,1001,100,240,{}};
    const auto baseline=e;
    assert(reconcile(i,e)==Result::confirmed);
    auto batch=i;batch.targets={"pikmin-1","pikmin-2"};batch.cost=2;batch.yield=2;
    assert(reconcile(batch,e)==Result::waiting);
    e.confirmed_targets={"pikmin-1"};assert(reconcile(batch,e)==Result::waiting);
    e.confirmed_targets={"pikmin-1","pikmin-2"};assert(reconcile(batch,e)==Result::confirmed);
    e.confirmed_targets={"pikmin-1","pikmin-1"};assert(reconcile(batch,e)==Result::review_required);e=baseline;
    e.response_success=false;assert(reconcile(i,e)==Result::waiting);e=baseline;
    e.authoritative=false;assert(reconcile(i,e)==Result::waiting);e=baseline;
    e.target_changed=false;assert(reconcile(i,e)==Result::waiting);e=baseline;
    e.observed_epoch=1000;assert(reconcile(i,e)==Result::waiting);e=baseline;
    e.account=std::string(64,'b');assert(reconcile(i,e)==Result::review_required);e=baseline;
    e.request="request-2";assert(reconcile(i,e)==Result::review_required);e=baseline;
    e.pikmin="pikmin-2";assert(reconcile(i,e)==Result::review_required);e=baseline;
    e.kind="flower-5:1";assert(reconcile(i,e)==Result::review_required);e=baseline;
    e.nectar=99;assert(reconcile(i,e)==Result::confirmed);e=baseline;
    e.nectar=779;e.petals=948;assert(reconcile(i,e)==Result::confirmed);e=baseline;
    e.petals=241;assert(reconcile(i,e)==Result::confirmed);e=baseline;
    e.nectar=-1;e.petals=-1;assert(reconcile(i,e)==Result::confirmed);
    e.target_changed=false;assert(reconcile(i,e)==Result::waiting);e=baseline;
    i.action="collect";i.cost=0;e.nectar=101;e.petals=241;
    assert(reconcile(i,e)==Result::confirmed);
    e.petals=240;assert(reconcile(i,e)==Result::confirmed);
    e.petals=242;assert(reconcile(i,e)==Result::confirmed);
    e.petals=241;e.nectar=100;assert(reconcile(i,e)==Result::confirmed);
    e.nectar=101;i.yield=0;assert(reconcile(i,e)==Result::review_required);
    std::puts("PASS feeding receipt reconciliation; NOT live RPC/inventory verification");
}
