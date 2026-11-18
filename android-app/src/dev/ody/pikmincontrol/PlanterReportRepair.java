package dev.ody.pikmincontrol;

/** Phone-only manual report recovery. Never confirms, deletes or replays a planter RPC. */
final class PlanterReportRepair {
    static final String MISSING="__PCC_REPORT_MISSING__", ERROR="__PCC_REPORT_ERROR__";
    static final long BUDGET_MS=25_000;
    enum Kind { CURRENT, PAUSED, OFF, WAIT_LOGIN, SOURCE_WAIT, CORE_UNKNOWN, OLD_PROCESS, STALE, UNKNOWN }
    static final class Sample {
        final String pid,boot,ticks,event,pending,account;
        final long now,statusAt,inventoryAt;
        final PlanterState state;
        final CoreCapabilities caps;
        final boolean processKnown,sessionKnown,healthy;
        final Kind kind;
        Sample(String[] raw,CorePair pair,long now) {
            if(raw==null||raw.length!=15||pair==null)throw new IllegalStateException("育苗診斷未完整讀取");
            if(raw[0]==null||raw[0].contains(ERROR)||raw[0].getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192)
                throw new IllegalStateException("育苗帳號前置回報讀取失敗或過大");
            String[] r=java.util.Arrays.copyOfRange(raw,1,15);
            int[] limits={128,128,128,128,2048,262144,2048,512,2048,8192,2048,128,128,128};
            for(int i=0;i<r.length;i++)if(r[i]==null||r[i].contains(ERROR)
                    ||r[i].getBytes(java.nio.charset.StandardCharsets.UTF_8).length>limits[i])
                throw new IllegalStateException("育苗診斷讀取失敗或過大；不當成沒有待確認操作");
            pid=r[0].trim();boot=r[1].trim();ticks=r[2].trim();this.now=now;
            if(!pid.equals(r[13].trim())||!boot.equals(r[12].trim())||!ticks.equals(r[11].trim()))
                throw new IllegalStateException("診斷期間遊戲程序已變更，請重新診斷");
            processKnown=pid.matches("[1-9][0-9]*")&&boot.matches("[a-f0-9-]{36}")&&ticks.matches("[1-9][0-9]*");
            String status=value(r[4]),inventory=value(r[5]);
            String[] f=status.trim().split("\t",-1),h=inventory.trim().split("\t",-1);
            event=f.length>=5?f[3]:"";
            statusAt=f.length>=5?PlanterState.number(f[1]):-1;
            inventoryAt=h.length>=5?PlanterState.number(h[1]):-1;
            pending=r[7].trim(); // Empty existing files are NOT missing.
            state=new PlanterState(value(r[3]).trim(),status,inventory,"",processKnown?pid:"",now);
            CoreCapabilities before=parseCaps(raw[0],processKnown?pid:"",pair,now),after=parseCaps(r[9],processKnown?pid:"",pair,now);
            if(before.ok()&&after.ok()&&(before.sessionEpoch!=after.sessionEpoch||before.lastLoginMs!=after.lastLoginMs||!before.identity.equals(after.identity)))
                throw new IllegalStateException("讀取期間帳號狀態已變更，請重新診斷");
            caps=before.ok()?after:before;
            String[] identity=value(r[10]).trim().split("\t",-1);
            account=identity.length==7&&"v1".equals(identity[0])&&pid.equals(identity[2])
                    &&identity[3].matches("[a-f0-9]{64}")&&"ready".equals(identity[4])&&"end".equals(identity[6])
                    &&PlanterState.number(identity[1])>=caps.lastLoginMs&&PlanterState.number(identity[1])<=now?identity[3]:"";
            sessionKnown=processKnown&&caps.identityReady()&&caps.sessionEpoch>0&&caps.lastLoginMs>0;
            healthy=sessionKnown&&state.fresh&&statusAt>=caps.lastLoginMs&&inventoryAt>=caps.lastLoginMs
                    &&caps.live(CoreCapabilities.CAP_PREPARER)&&caps.has(CoreCapabilities.CAP_PLANTER)&&caps.domainReady()
                    &&now>=statusAt&&now>=inventoryAt&&pair.runtime(value(r[8]),pid,pid,now)
                    &&!SessionIdentity.parse(value(r[10]),pid,pid,now).isEmpty();
            if(!processKnown)kind=Kind.UNKNOWN;
            else if(!caps.ok())kind=Kind.CORE_UNKNOWN;
            else if(!sessionKnown)kind=Kind.WAIT_LOGIN;
            else if(!caps.live(CoreCapabilities.CAP_PREPARER)||!caps.has(CoreCapabilities.CAP_PLANTER))kind=Kind.SOURCE_WAIT;
            else if(healthy)kind=!MISSING.equals(pending)||paused()?Kind.PAUSED:state.mode.equals("off")?Kind.OFF:Kind.CURRENT;
            else if(f.length>=5&&!pid.equals(f[2]))kind=Kind.OLD_PROCESS;
            else kind=Kind.STALE;
        }
        private static CoreCapabilities parseCaps(String raw,String pid,CorePair pair,long now){
            return CoreCapabilities.parse(MISSING.equals(raw.trim())?ReadinessSample.Read.missing():ReadinessSample.Read.ok(raw.trim()),pid,pair.version,pair.code,now);
        }
        private static String value(String s){return MISSING.equals(s.trim())?"":s;}
        boolean paused(){return state.legacyReviewRequired()||"manual-recovery-checking".equals(event);}
        String process(){return processKnown?pid+":"+boot+":"+ticks:"";}
        String session(){return sessionKnown?process()+":"+caps.sessionEpoch+":"+caps.lastLoginMs:"";}
        boolean canRestart(){
            // No mutation ledger, no live/uncertain request, known current loader/session.
            return sessionKnown&&!account.isEmpty()&&MISSING.equals(pending)&&!healthy&&statusAt<=now&&inventoryAt<=now
                    &&!paused()&&!event.endsWith("-sent")&&!event.contains("rpc")
                    &&!event.contains("inventory-no-retry")&&!event.contains("review-required");
        }
        String message(){switch(kind){
            case CURRENT:return "育苗回報持續更新 · "+state.status;
            case OFF:return "育苗回報正常；自動育苗目前關閉，未替你更改開關";
            case PAUSED:return "回報已更新，但有育苗操作待核對；請使用手動恢復，不清除紀錄";
            case WAIT_LOGIN:return "核心有回報，但帳號尚未就緒；請完成登入並回到地圖";
            case SOURCE_WAIT:return "目前帳號的育苗資料來源尚未重新確認；未把舊槽位當成目前狀態";
            case CORE_UNKNOWN:return "尚未確認本程序的配對核心；請到系統核對核心，不建議清除育苗狀態";
            case OLD_PROCESS:return "育苗回報仍屬於舊遊戲程序，尚未恢復";
            case STALE:return "已切回遊戲，但育苗回報仍未完整更新，尚未恢復";
            default:return "主空間遊戲程序無法確認；請開啟主空間遊戲後重新診斷";
        }}
    }
    static final class Result {
        final Sample sample;final boolean advancing;final String message;
        Result(Sample s,boolean advancing){sample=s;this.advancing=advancing;
            message=(advancing?s.message():s.healthy?"目前資料可讀，但尚未證明持續更新；未重啟或清除紀錄":s.message());}
        boolean offerRestart(){return !advancing&&sample.canRestart();}
    }
    /** Manual recovery adopts one fresh, same-session paused sample; it does not need two advancing reports. */
    static boolean readyForManualRecovery(Sample sample){
        return sample!=null&&sample.healthy&&sample.paused();
    }
    interface Port {
        Sample read()throws Exception;
        long elapsed();
        void guard()throws Exception;
        void openGame()throws Exception;
        void waitForUpdate()throws Exception;
        void audit(String code)throws Exception;
        void pause()throws Exception;
        void resume()throws Exception;
        void restart(Sample expected)throws Exception;
    }
    static Result refresh(Port p)throws Exception {
        long start=p.elapsed();p.guard();Sample initial=p.read();
        String process=initial.process(),session=initial.session();long status=initial.statusAt,inventory=initial.inventoryAt;
        if(p.elapsed()-start>=BUDGET_MS){p.audit("reports-not-confirmed:budget");return new Result(initial,false);}
        p.guard();p.openGame();Sample last=initial;int advances=0;
        for(int i=0;i<5;i++) {
            if(p.elapsed()-start>=BUDGET_MS)break;
            p.waitForUpdate();p.guard();if(p.elapsed()-start>=BUDGET_MS)break;last=p.read();
            if(p.elapsed()-start>=BUDGET_MS)break;
            if(!process.isEmpty()&&!process.equals(last.process()))throw new IllegalStateException("更新期間遊戲程序已變更，請重新診斷");
            if(process.isEmpty())process=last.process();
            if(!session.isEmpty()&&!session.equals(last.session()))throw new IllegalStateException("更新期間帳號狀態已變更，請重新診斷");
            if(session.isEmpty())session=last.session();
            if(last.healthy&&last.statusAt>status&&last.inventoryAt>inventory){
                status=last.statusAt;inventory=last.inventoryAt;
                if(++advances==2){p.audit("reports-advancing:"+last.kind);return new Result(last,true);}
            }else advances=0;
        }
        p.audit("reports-not-confirmed:"+last.kind);return new Result(last,false);
    }
    static Result restart(Port p,Sample consent)throws Exception {
        p.guard();Sample current=p.read();
        if(!consent.canRestart()||!current.canRestart()||!consent.session().equals(current.session())
                ||!consent.account.equals(current.account))
            throw new IllegalStateException("遊戲、帳號或育苗狀態已改變；未重啟，請重新診斷");
        boolean restarted=false;
        try {
            p.pause();p.guard();Sample paused=p.read();
            if(!consent.session().equals(paused.session())||!consent.account.equals(paused.account))
                throw new IllegalStateException("暫停期間帳號或程序已變更，未重啟");
            // Returning to the foreground may have repaired the producer while pausing.
            if(!paused.healthy){
                if(!paused.canRestart())throw new IllegalStateException("育苗出現待確認操作，保留紀錄，未重啟");
                p.audit("game-restart-requested:no-ledger-no-replay");p.guard();p.restart(paused);restarted=true;
            }
        }finally{p.resume();}
        // Command acceptance alone is never reported as recovery success.
        Result result=refresh(p);
        if(restarted&&consent.process().equals(result.sample.process()))throw new IllegalStateException("未觀察到新的遊戲程序，尚未確認恢復；不重複重啟");
        return result;
    }
}
