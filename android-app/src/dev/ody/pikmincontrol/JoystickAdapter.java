package dev.ody.pikmincontrol;

/** One explicitly supported provider. Unknown/rebranded packages are not auto-selected. */
final class JoystickAdapter {
    static final String PACKAGE = "com.thejhijuqoqy.bdujvrwdcajvzin";
    static final String VERSION = "5.3.3";
    static boolean supported(String version) { return VERSION.equals(version); }
    private JoystickAdapter() { }
}
