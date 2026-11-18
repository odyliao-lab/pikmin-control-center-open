package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Current target checks; no game binary or device claims. */
public final class Game156CompatibilityTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError("157 compatibility contract"); }
    static String source(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        check(CoreCapabilities.EXPECTED_GAME.equals("157.0"));
        check(ReadinessModel.EXPECTED_GAME.equals("157.0"));
        CorePair pair = new CorePair("schema=2\nversion=1.5.34\ncode=102\napp=135\nsize=1\nsha256="
                + new String(new char[64]).replace('\0','a') + "\n");
        String live = "v1\t1000\t42\t1.5.34\t102\t157.0";
        check(pair.runtime(live,"42","42",1001));
        for (String wrong : new String[]{"153.0","154.0","155.0","156.0","156.1","158.0"})
            check(!pair.runtime(live.replace("157.0",wrong),"42","42",1001));
        check(!pair.runtime(live,"42","43",1001));
        String nativeCode=source("native/automation/zygisk-module/cpp/nectar_rpc.cpp");
        check(nativeCode.contains("kExpectedIl2CppSize = 257043464"));
        check(nativeCode.contains("il2cpp_stat.st_size != kExpectedIl2CppSize"));
        check(nativeCode.contains("0xd00491b4"));
        check(nativeCode.contains("std::memcmp(action_ctor_entry, capture_prologue"));
        check(nativeCode.contains("info.dli_fbase) != 0x73da0f4"));
        check(!nativeCode.contains("info.dli_fbase) != 0x731DA98"));
        check(!nativeCode.contains("info.dli_fbase) != 0x7226164"));
        check(!nativeCode.contains("info.dli_fbase) != 0x7288B44"));
        int start=nativeCode.indexOf("bool current_location(double &latitude, double &longitude) {");
        String location=nativeCode.substring(start, start+2200);
        check(location.contains("class_get_field(object_get_class(controller), \"latestRawLocation\")"));
        check(location.contains("field_get_value(controller, raw_field, &raw)"));
        check(!nativeCode.contains("kRawLocationOffset"));
        String gps=source("native/gps-copy/cpp/hack.cpp");
        check(gps.contains("kTargetVersion[] = \"157.0\""));
        check(gps.contains("kTargetVersionCode = 1793360009"));
        System.out.println("PASS Game157Compatibility (historical entry name): old/future version and changed PID rejected; metadata location and exact capture guard; NOT game validation");
    }
}
