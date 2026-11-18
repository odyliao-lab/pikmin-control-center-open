package dev.ody.pikmincontrol;
import java.lang.reflect.Method;
public final class ReturnRewardLabelsTest {
    public static void main(String[] args) throws Exception {
        Method append = ReturnRewardModuleGateway.class.getDeclaredMethod("appendReadableRewards", StringBuilder.class, String.class, String.class);
        Method label = ReturnRewardModuleGateway.class.getDeclaredMethod("taskLabel", String.class);
        append.setAccessible(true); label.setAccessible(true);
        StringBuilder text = new StringBuilder();
        append.invoke(null,text,"now","carry:fruit:lime x1 honey=common:white:1");
        check(text.toString().contains("精華 +1") && text.toString().contains("一般拾取"));
        text.setLength(0);
        append.invoke(null,text,"now","carry:mushroom-fruit:peach x6 honey=canna:red:35");
        check(text.toString().contains("精華 +35") && text.toString().contains("蘑菇獎勵"));
        text.setLength(0);
        append.invoke(null,text,"now","expedition:fruit:lemon x6 honey=common:yellow:24");
        check(text.toString().contains("精華 +24") && !text.toString().contains("一般拾取"));
        check(label.invoke(null,"carry:resource").toString().startsWith("［果］"));
        check(label.invoke(null,"carry:mushroom-fruit:unknown-fruit").toString().contains("蘑菇"));
        check(label.invoke(null,"carry:fruit:unknown-fruit").toString().contains("拾取"));
        check(label.invoke(null,"carry:seed:23:pikmin:1:category:1:asset:1").toString().startsWith("［盆］"));
        System.out.println("Return reward labels: 7 checks passed");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
