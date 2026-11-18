package dev.ody.pikmincontrol;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public final class UiPreferencesTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    static String read(Path p)throws Exception{return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception {
        Path app=Paths.get(System.getProperty("pcc.fixtures")).getParent().getParent();
        String manifest=read(app.resolve("AndroidManifest.xml"));
        javax.xml.parsers.DocumentBuilderFactory f=javax.xml.parsers.DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        org.w3c.dom.NodeList activities=f.newDocumentBuilder().parse(app.resolve("AndroidManifest.xml").toFile()).getElementsByTagName("activity");
        check(activities.getLength()==7); // Original six plus the APK-provider's disclosure-only rationale.
        for(int i=0;i<activities.getLength();i++)check("portrait".equals(((org.w3c.dom.Element)activities.item(i)).getAttributeNS("http://schemas.android.com/apk/res/android","screenOrientation")));
        String settings=read(app.resolve("src/dev/ody/pikmincontrol/ControlSettings.java"));
        check(settings.contains("getBoolean(\"route_harvest\", true)")); // missing preference only; explicit false remains false
        check(settings.contains("putBoolean(\"route_harvest\",route)"));
        String main=read(app.resolve("src/dev/ody/pikmincontrol/MainActivity.java"));
        check(!main.contains("pairedButtons(shortcuts,"));
        check(main.contains("Button batchTab = expeditionTab")&&main.contains("Button farmTab = expeditionTab"));
        System.out.println("PASS UI preference source contracts: portrait activities, default route preserving choices, expedition-only actions; NOT device validation");
    }
}
