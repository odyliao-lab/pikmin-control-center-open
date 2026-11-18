package dev.ody.pikmincontrol;

/** Typed STATUS speed only. The installed JoyStick emits a Float; historical
 * Double senders are accepted without guessing from text or integer extras.
 * This value is NOT an acknowledgement or provider-drain proof. */
final class EmulatorJoystickSpeed {
    static double read(Object value) {
        if (!(value instanceof Float) && !(value instanceof Double))
            throw new IllegalStateException("GPS速度欄位型別不相容");
        double speed=((Number)value).doubleValue();
        if (!Double.isFinite(speed) || speed<=0)
            throw new IllegalStateException("GPS速度無效");
        return speed;
    }
}
