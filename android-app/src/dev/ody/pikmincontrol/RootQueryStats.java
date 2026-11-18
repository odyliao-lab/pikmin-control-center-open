package dev.ody.pikmincontrol;

/** Process-local counters only: no commands, coordinates, output or file I/O. */
final class RootQueryStats {
    private static final long started = System.nanoTime();
    private static long calls, failures, shared, skipped, elapsedMs, maxMs;
    private static final long[] recent = new long[128];
    private static int next, count;
    static synchronized void completed(long nanos, boolean ok) {
        calls++; if (!ok) failures++;
        long ms = Math.max(0, nanos / 1000000L);
        elapsedMs += ms; maxMs = Math.max(maxMs, ms);
        recent[next]=ms;next=(next+1)%recent.length;count=Math.min(recent.length,count+1);
    }
    static synchronized void shared() { shared++; }
    static synchronized void skipped() { skipped++; }
    static synchronized String summary() {
        long[] sorted=java.util.Arrays.copyOf(recent,count);java.util.Arrays.sort(sorted);
        return "本程序統計 " + Math.max(0, (System.nanoTime()-started)/1000000000L) + " 秒\n"
            + "被統計 root：" + calls + " 次 · 未完成 " + failures + " 次\n"
            + "平均 " + (calls == 0 ? 0 : elapsedMs/calls) + " ms · 最慢 " + maxMs + " ms\n"
            + "累計命令耗時 " + elapsedMs + " ms（非牆鐘總耗時）\n"
            + "最近 " + count + " 次 p50 " + percentile(sorted,50) + " ms · p95 " + percentile(sorted,95) + " ms\n"
            + "重疊畫面查詢合併：" + shared + " 次\n"
            + "重複畫面刷新免排隊：" + skipped + " 次\n"
            + "涵蓋 BoundedCommand 與精華／領取舊橋接；非全機 root 計數。\n"
            + "不保存查詢內容；重啟控制中心程序後歸零。";
    }
    private static long percentile(long[] sorted,int p) {return sorted.length==0?0:sorted[(sorted.length*p+99)/100-1];}
}
