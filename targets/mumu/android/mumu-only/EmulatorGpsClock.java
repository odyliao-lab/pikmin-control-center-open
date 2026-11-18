package dev.ody.pikmincontrol;

/** Bracketed wall/BOOTTIME sample. No provider identity or gameplay authority.
 * v154 converts TimestampS using wall-to-elapsed offset; a changed offset must
 * not make a delayed pre-handoff timestamp look like a new fix. */
final class EmulatorGpsClock {
    static final long MAX_BRACKET_MS=20, MAX_OFFSET_CHANGE_MS=100, GUARD_MS=500;
    final long before,wall,after;
    EmulatorGpsClock(long before,long wall,long after){
        if(before<=0||after<before||after>=1000000000000L||after-before>MAX_BRACKET_MS||wall<1000000000000L||wall>Long.MAX_VALUE-1000)
            throw new IllegalArgumentException("Invalid bracketed clock sample");
        this.before=before;this.wall=wall;this.after=after;
    }
    long offsetLow(){return wall-after;}
    long offsetHigh(){return wall-before;}
    boolean consistent(EmulatorGpsClock later){
        return later!=null&&later.before>=after&&
            later.offsetLow()>=offsetLow()-MAX_OFFSET_CHANGE_MS&&
            later.offsetHigh()<=offsetHigh()+MAX_OFFSET_CHANGE_MS;
    }
    long minimumFixMs(){return Math.addExact(after,GUARD_MS);}
    String encode(){return before+","+wall+","+after;}
    static EmulatorGpsClock decode(String wire){
        if(wire==null||!wire.matches("[1-9][0-9]*,[1-9][0-9]*,[1-9][0-9]*"))throw new IllegalArgumentException("Clock frame");
        String[] f=wire.split(",");EmulatorGpsClock value=new EmulatorGpsClock(Long.parseLong(f[0]),Long.parseLong(f[1]),Long.parseLong(f[2]));
        if(!wire.equals(value.encode()))throw new IllegalArgumentException("Noncanonical clock");return value;
    }
}
