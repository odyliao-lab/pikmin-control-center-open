package dev.ody.pikmincontrol;

import java.time.LocalDate;

/** Read-only game-adopted counter. No HC estimate, start authority or date inference. */
final class EmulatorGameDailySteps {
    static final String SOURCE="player-statistics-v157";
    static final class Counter {
        final LocalDate day;
        final long today,canonical,revision,captured;
        Counter(LocalDate day,long today,long canonical,long revision,long captured) {
            this.day=day;this.today=today;this.canonical=canonical;this.revision=revision;this.captured=captured;
        }
    }
    static final class Observation {
        final EmulatorNurseryBudget.Snapshot nursery;
        final long canonicalSteps,inventoryRevision;
        Observation(EmulatorNurseryBudget.Snapshot nursery,long canonical,long revision) {
            this.nursery=nursery;canonicalSteps=canonical;inventoryRevision=revision;
        }
    }
    static Observation read(String wire,EmulatorDailyRunner.Binding expected,long now) {
        if(expected==null)bad();
        Counter c=counter(wire,expected.pid,expected.sessionEpoch,expected.account,now);
        return new Observation(new EmulatorNurseryBudget.Snapshot(expected,c.day,c.today,c.captured),c.canonical,c.revision);
    }
    /** Passive diagnostic does not invent a route/core/APK Binding to display counts. */
    static Counter counter(String wire,int expectedPid,long expectedEpoch,String account,long now) {
        if(wire==null || expectedPid<=0 || expectedEpoch<=0 || account==null ||
                !account.matches("[0-9a-f]{64}") || now<=0 || wire.length()>2048)bad();
        for(int i=0;i<wire.length();i++) {
            char c=wire.charAt(i);
            if(c!='\t' && c!='\n' && (c<0x20 || c>0x7e))bad();
        }
        String[] rows=wire.split("\n",-1);
        if(rows.length!=2 || !rows[1].isEmpty())bad();
        String[] f=rows[0].split("\t",-1);
        if(f.length!=13 || !f[0].equals("v1") || !f[5].equals("157.0") ||
                !f[6].equals("complete") || !f[11].equals(SOURCE) || !f[12].equals("end"))bad();
        long captured=number(f[1]),pid=number(f[2]),epoch=number(f[3]),revision=number(f[10]);
        if(captured<=0 || captured>now || now-captured>EmulatorNurseryBudget.MAX_AGE_MS ||
                pid!=expectedPid || epoch!=expectedEpoch || !f[4].equals(account) || revision<=0)bad();
        // This is the game's date, not host midnight or GPS-route timezone.
        if(!f[7].matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}"))bad();
        LocalDate day;
        try {day=LocalDate.parse(f[7]);}
        catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("Invalid game date",invalid);}
        if(!day.toString().equals(f[7]))bad();
        return new Counter(day,number(f[8]),number(f[9]),revision,captured);
    }
    private static long number(String value) {
        long n=Long.parseLong(value);
        if(n<0 || !Long.toString(n).equals(value))bad();
        return n;
    }
    private static void bad(){throw new IllegalArgumentException("Fresh independently bound confirmed game counter required");}
}
