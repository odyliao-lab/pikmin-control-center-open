package dev.ody.pikmincontrol;
import java.util.TimeZone;
public final class FeedingHistoryTest {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        String raw="1704067200000\tsent\tflower-5:3\n1704067260000\tfeed-confirmed\t\n1704067320000\tcollect-confirmed\t\n";
        String text=FeedingHistory.format(raw,TimeZone.getTimeZone("Asia/Taipei"));
        check(text.startsWith("01-01 08:02  花瓣已收取"));
        check(text.contains("01-01 08:01  餵食完成\n"));
        check(text.contains("普通精華／藍")&&!text.contains("1704067")&&!text.contains("flower-"));
        check(!text.contains("+1")&&!text.contains("-1"));
        check(FeedingHistory.kind("flower-s-cyclamen:2").equals("兔耳花／紅"));
        check(FeedingHistory.kind("flower-s-new:4").equals("未知花種／黃"));
        check(FeedingHistory.kind("bad").isEmpty());
        check(FeedingHistory.format("broken\n").contains("1 筆格式異常"));
        check(FeedingHistory.format(null).contains("尚無"));
        check(FeedingHistory.label("pending-review").contains("自動重查"));
        check(FeedingHistory.label("unconfirmed-cooldown").contains("稍後重查"));
        check(FeedingHistory.label("recovery-storage-retry").contains("自動重試"));
        check(FeedingHistory.label("future-state").contains("原始紀錄"));
        check(FeedingHistory.format(raw.replace("\n","\r\n")).contains("花瓣已收取"));
        System.out.println("PASS FeedingHistory: Chinese, timezone, newest first, missing identity, malformed and unknown states; no inferred quantities");
    }
}
