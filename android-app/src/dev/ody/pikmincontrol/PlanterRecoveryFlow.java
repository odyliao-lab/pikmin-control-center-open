package dev.ody.pikmincontrol;

/** Captured consent survives UI delays; retirement authority must always be renewed. */
final class PlanterRecoveryFlow {
    static final class Prepared {
        final PlanterRecovery ack;
        final PlanterRetirement.Proof proof;
        final String session;
        Prepared(PlanterRecovery ack,PlanterRetirement.Proof proof,String session){
            if(ack==null||!ack.supported)throw new IllegalStateException("尚未取得本次育苗核對回覆");
            if("result-unknown".equals(ack.result)){
                if(proof==null||!proof.requestNonce.equals(ack.nonce)||!proof.pid.equals(ack.pid)
                    ||session==null||session.trim().isEmpty())
                    throw new IllegalStateException("核對回覆與本次育苗封存證據不匹配");
            }else if(proof!=null)throw new IllegalStateException("非結果不明的回覆不能授權封存");
            this.ack=ack;this.proof=proof;this.session=session;
        }
    }
    interface Port {
        void guard()throws Exception;
        Prepared prepare(Prepared consent)throws Exception;
        void retire(PlanterRetirement.Proof proof,String session)throws Exception;
    }
    /** Check the original consent before requesting another read-only native check. */
    static void requireSame(Prepared consent,PlanterRetirement.Basis basis,String session){
        if(consent==null||consent.proof==null||basis==null||!consent.proof.same(basis)
            ||!consent.session.equals(session))
            throw new IllegalStateException("原育苗紀錄或遊戲工作階段已改變，需重新確認");
    }
    static boolean confirm(Port p,Prepared consent)throws Exception {
        p.guard();
        if(consent==null||consent.proof==null)throw new IllegalStateException("尚未取得育苗封存確認依據");
        Prepared renewed=p.prepare(consent);
        if(renewed==null)throw new IllegalStateException("尚未取得新的育苗核對回覆");
        if(!"result-unknown".equals(renewed.ack.result))return false;
        requireSame(consent,renewed.proof,renewed.session);
        if(consent.ack.nonce.equals(renewed.ack.nonce))
            throw new IllegalStateException("必須重新核對原紀錄，不能沿用先前回覆");
        p.guard();p.retire(renewed.proof,renewed.session);return true;
    }
    private PlanterRecoveryFlow(){}
}
