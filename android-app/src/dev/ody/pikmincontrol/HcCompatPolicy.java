package dev.ody.pikmincontrol;

/** Shared UI/payload boundary; eligibility alone is not HC capability or permission. */
public final class HcCompatPolicy {
    public static boolean allowedSdk(int sdk) {return sdk>=28&&sdk<=33;}
    public static boolean requested(String raw) {return "on".equals(raw)||"on\n".equals(raw);}
    private HcCompatPolicy() {}
}
