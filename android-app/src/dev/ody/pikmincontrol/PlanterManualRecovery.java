package dev.ody.pikmincontrol;

/** Observe only the ACK for this one request. Old ACKs never authorize retirement. */
final class PlanterManualRecovery {
    interface Port {
        void guard()throws Exception;
        PlanterRecovery submit()throws Exception;
        PlanterRecovery read()throws Exception;
        void waitForUpdate()throws Exception;
        long elapsed();
    }
    static PlanterRecovery run(Port p)throws Exception {
        p.guard();PlanterRecovery request=p.submit();long start=p.elapsed();
        for(int i=0;i<10&&p.elapsed()-start<25000;i++){
            p.waitForUpdate();p.guard();PlanterRecovery ack=p.read();
            if(p.elapsed()-start>=25000)break;
            if(!ack.supported||!request.pid.equals(ack.pid))
                throw new IllegalStateException("遊戲或恢復回報已變更，尚未確認恢復；不重送");
            if(!request.nonce.equals(ack.nonce))continue;
            if(!"checking-inventory".equals(ack.result))return ack;
        }
        throw new IllegalStateException("本次育苗核對尚未收到對應結果；保留紀錄，請留在遊戲後重試");
    }
}
