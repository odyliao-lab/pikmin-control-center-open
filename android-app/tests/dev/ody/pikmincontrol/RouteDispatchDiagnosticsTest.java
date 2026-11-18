package dev.ody.pikmincontrol;

public final class RouteDispatchDiagnosticsTest {
    static int checks;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    static void reject(String raw,String pid,String token,long now,String name){
        boolean bad=false;try{RouteDispatchDiagnostics.parse(raw,pid,token,now);}catch(RuntimeException e){bad=true;}
        check(bad,name);
    }
    public static void main(String[] args){
        long now=2_000_000;
        String raw="v1\t1999000\t4242\troute-a\t1\t12\t7\t4\t3\tstart-requested\tend\n";
        RouteDispatchDiagnostics d=RouteDispatchDiagnostics.parse(raw,"4242","route-a",now);
        check(d.gateReady&&d.candidates==12&&d.evaluated==7&&d.selected==4&&d.started==3,"valid snapshot");
        check(d.compact().equals("候選 12／評估 7／送出 3"),"compact overlay counts");
        check(d.detail().contains("已送出派遣"),"reason localized");
        reject(raw,"7","route-a",now,"pid mismatch");
        reject(raw,"4242","route-b",now,"token mismatch");
        reject(raw.replace("1999000","1987999"),"4242","route-a",now,"stale");
        reject(raw.replace("\t12\t7\t4\t3\t","\t2\t3\t1\t0\t"),"4242","route-a",now,"evaluated exceeds candidates");
        reject(raw.replace("\t4\t3\t","\t2\t3\t"),"4242","route-a",now,"started exceeds selected");
        reject(raw.replace("start-requested","bad reason"),"4242","route-a",now,"reason alphabet");
        reject(raw+"extra\n","4242","route-a",now,"one line only");
        check(RouteDispatchDiagnostics.parse(raw.replace("start-requested","location-wait"),"4242","route-a",now)
                .detail().contains("定位穩定"),"location gate explained");
        String v2="v2\t1999000\t4242\troute-a\t0\t28\t12\t8\t6\troute-catching-up\t9\t4\t1\t2\t7\t5\t2\tend\n";
        d=RouteDispatchDiagnostics.parse(v2,"4242","route-a",now);
        check(!d.gateReady&&d.catchups==9&&d.over40==4&&d.over80==1&&d.stale==2,"v2 gate metrics");
        check(d.recoveries==7&&d.recoveredStarts==5&&d.durationBlocked==2,"v2 outcome metrics");
        check(d.detail().contains("追趕 9／恢復 7／恢復後送出 5"),"v2 concise recovery detail");
        check(d.detail().contains("40m+ 4／80m+ 1／過期 2／5分阻擋 2"),"v2 safety detail");
        check(d.operatorStatus().equals("定位追上中"),"operator catch-up status");
        check(RouteDispatchDiagnostics.parse(v2.replace("route-catching-up","route-settling"),"4242","route-a",now)
                .operatorStatus().equals("定位追上，快速確認中"),"operator settling status");
        check(d.detail().contains("遊戲定位追趕中"),"v2 reason localized");
        reject(v2.replace("\t4\t1\t2\t7\t5\t2\tend","\t0\t1\t2\t7\t5\t2\tend"),"4242","route-a",now,"over80 cannot exceed over40");
        reject(v2.replace("\t9\t4\t1\t2\t7\t5\t2\tend","\t2\t4\t1\t2\t7\t5\t2\tend"),"4242","route-a",now,"recovery cannot exceed catchup");
        reject(v2.replace("\t7\t5\t2\tend","\t7\t7\t2\tend"),"4242","route-a",now,"recovered starts bounded by starts");
        reject(v2.replace("\t7\t5\t2\tend","\t7\t5\t13\tend"),"4242","route-a",now,"duration blocks bounded by evaluated");
        System.out.println("PASS RouteDispatchDiagnostics: "+checks+" checks");
    }
}
