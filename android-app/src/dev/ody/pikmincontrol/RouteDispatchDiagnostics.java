package dev.ody.pikmincontrol;

/** PID- and route-token-bound snapshot written by the native route dispatcher. */
final class RouteDispatchDiagnostics {
    static final long FRESH_MS = 12_000;
    final long at;
    final int candidates, evaluated, selected, started;
    final int catchups, over40, over80, stale, recoveries, recoveredStarts, durationBlocked;
    final boolean gateReady;
    final String reason;

    private RouteDispatchDiagnostics(long at, boolean gateReady, int candidates, int evaluated,
                                     int selected, int started, String reason, int catchups, int over40,
                                     int over80, int stale, int recoveries, int recoveredStarts, int durationBlocked) {
        this.at = at; this.gateReady = gateReady; this.candidates = candidates;
        this.evaluated = evaluated; this.selected = selected; this.started = started; this.reason = reason;
        this.catchups=catchups;this.over40=over40;this.over80=over80;this.stale=stale;
        this.recoveries=recoveries;this.recoveredStarts=recoveredStarts;this.durationBlocked=durationBlocked;
    }

    static RouteDispatchDiagnostics parse(String raw, String expectedPid, String expectedToken, long now) {
        if (raw == null || raw.length() > 2048 || raw.indexOf('\n') != raw.lastIndexOf('\n'))
            throw new IllegalArgumentException("route dispatch diagnostics framing");
        String[] f = raw.trim().split("\\t", -1);
        boolean v1=f.length==11&&"v1".equals(f[0])&&"end".equals(f[10]);
        boolean v2=f.length==18&&"v2".equals(f[0])&&"end".equals(f[17]);
        if (!v1&&!v2)
            throw new IllegalArgumentException("route dispatch diagnostics schema");
        long at = number(f[1], 1, Long.MAX_VALUE);
        if (!expectedPid.equals(f[2]) || !expectedToken.equals(f[3]))
            throw new IllegalStateException("route dispatch diagnostics attribution");
        if (now < at || now - at > FRESH_MS) throw new IllegalStateException("route dispatch diagnostics stale");
        if (!"0".equals(f[4]) && !"1".equals(f[4])) throw new IllegalArgumentException("route gate");
        int candidates = count(f[5]), evaluated = count(f[6]), selected = count(f[7]), started = count(f[8]);
        if (evaluated > candidates || selected > candidates || started > selected)
            throw new IllegalArgumentException("route dispatch diagnostics counts");
        if (!f[9].matches("[a-z0-9-]{1,48}")) throw new IllegalArgumentException("route dispatch reason");
        int catchups=0,over40=0,over80=0,stale=0,recoveries=0,recoveredStarts=0,durationBlocked=0;
        if(v2){
            catchups=count(f[10]);over40=count(f[11]);over80=count(f[12]);stale=count(f[13]);
            recoveries=count(f[14]);recoveredStarts=count(f[15]);durationBlocked=count(f[16]);
            if(over80>over40||recoveries>catchups||recoveredStarts>started||durationBlocked>evaluated)
                throw new IllegalArgumentException("route dispatch diagnostics metrics");
        }
        return new RouteDispatchDiagnostics(at, "1".equals(f[4]), candidates, evaluated, selected, started, f[9],
                catchups,over40,over80,stale,recoveries,recoveredStarts,durationBlocked);
    }

    private static int count(String value) { return (int) number(value, 0, 10_000); }
    private static long number(String value, long min, long max) {
        if (value == null || !value.matches("[0-9]{1,19}")) throw new IllegalArgumentException("number");
        long n;
        try { n = Long.parseLong(value); } catch (NumberFormatException e) { throw new IllegalArgumentException("number", e); }
        if (n < min || n > max) throw new IllegalArgumentException("number range");
        return n;
    }

    String compact() { return "候選 " + candidates + "／評估 " + evaluated + "／送出 " + started; }
    String detail() {
        String base=compact()+" · "+reasonLabel(reason);
        if(catchups+over40+over80+stale+recoveries+recoveredStarts+durationBlocked==0)return base;
        return base+" · 追趕 "+catchups+"／恢復 "+recoveries+"／恢復後送出 "+recoveredStarts
                +"／40m+ "+over40+"／80m+ "+over80+"／過期 "+stale+"／5分阻擋 "+durationBlocked;
    }
    String operatorStatus() {
        switch (reason) {
            case "location-wait":
            case "route-waiting": return "等待遊戲定位";
            case "route-catching-up": return "定位追上中";
            case "route-settling": return "定位追上，快速確認中";
            case "route-stale": return "等待定位更新";
            case "route-discontinuous": return "等待連續定位";
            case "route-lag-over-80m": return "等待遊戲定位追上";
            case "nearby-start-blocked": return "遊戲暫時拒絕派遣";
            default: return "運行正常";
        }
    }
    private static String reasonLabel(String value) {
        switch (value) {
            case "location-wait": return "等待遊戲定位穩定";
            case "route-waiting": return "等待遊戲定位";
            case "route-catching-up": return "遊戲定位追趕中";
            case "route-settling": return "定位追上，快速確認中";
            case "route-stale": return "遊戲定位已過期";
            case "route-discontinuous": return "定位更新不連續";
            case "route-lag-over-80m": return "遊戲定位落後超過 80 公尺";
            case "ready": return "定位就緒";
            case "no-candidates": return "目前沒有水果或花苗";
            case "selection-settling": return "隊伍套用中";
            case "start-requested": return "已送出派遣";
            case "waiting-next-scan": return "等待下一次掃描";
            case "nearby-duration-blocked": return "原生時間超過 5 分鐘";
            case "nearby-start-blocked": return "遊戲拒絕派遣";
            case "nearby-selection-reset": return "定位變動，重新選隊";
            case "lease-not-walk": return "路線尚未開始";
            default: return "未送出，詳見診斷";
        }
    }
}
