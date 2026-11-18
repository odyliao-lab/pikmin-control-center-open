package dev.ody.pikmincontrol;
import java.util.*;
/** Only a confirmed preflight rejection (before authorising Start) is retryable. */
final class BatchRetry {
    private final Map<String,Long> deferred = new HashMap<>();
    boolean defer(String task,String reason,long now) {
        if (!("gift-owner-busy".equals(reason) || "no-available-team".equals(reason)) || deferred.containsKey(task)) return false;
        deferred.put(task,now+30_000L); return true;
    }
    long remaining(String task,long now) {return Math.max(0,deferred.getOrDefault(task,0L)-now);}
    static String category(String reason) {
        if ("gift-owner-busy".equals(reason)||"no-available-team".equals(reason)||"gift_pikmin_unavailable".equals(reason)) return "隊員暫時不可用";
        if ("arrival_timeout".equals(reason)) return "定位未到點";
        return "派遣結果待核對，不自動重送";
    }
}
