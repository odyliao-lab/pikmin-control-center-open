package dev.ody.pikmincontrol;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

final class StepSystemClock implements StepClock {
    public long wallMillis() { return System.currentTimeMillis(); }
    public long elapsedMillis() { return System.nanoTime() / 1_000_000L; }
}

final class MutableStepClock implements StepClock {
    long wall;
    long elapsed;
    MutableStepClock(long wall, long elapsed) { this.wall = wall; this.elapsed = elapsed; }
    public long wallMillis() { return wall; }
    public long elapsedMillis() { return elapsed; }
    void advanceWall(long ms) { wall += ms; elapsed += ms; }
}

final class StepSystemZone implements StepZone {
    private final ZoneId fixed;
    StepSystemZone() { this.fixed = null; }
    StepSystemZone(String zoneId) { this.fixed = ZoneId.of(zoneId); }
    StepSystemZone(ZoneId zone) { this.fixed = zone; }
    private ZoneId active() { return fixed != null ? fixed : ZoneId.systemDefault(); }
    public String zoneId() { return active().getId(); }
    public int offsetSecondsAt(long epochMillis) {
        return active().getRules().getOffset(Instant.ofEpochMilli(epochMillis)).getTotalSeconds();
    }
    public long dayStartMillis(long epochMillis) {
        ZoneId zone = active();
        return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
    }
    public long nextMidnightMillis(long epochMillis) {
        ZoneId zone = active();
        return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }
    public int minuteOfDay(long epochMillis) {
        ZonedDateTime zoned = Instant.ofEpochMilli(epochMillis).atZone(active());
        return zoned.getHour() * 60 + zoned.getMinute();
    }
    public String dayKey(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(active()).toLocalDate().toString();
    }
    public boolean localTimeExists(int year, int month, int day, int hour, int minute) {
        return !offsetsAtLocal(year, month, day, hour, minute).isEmpty();
    }
    public List<Integer> offsetsAtLocal(int year, int month, int day, int hour, int minute) {
        LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute, 0);
        List<ZoneOffset> valid = active().getRules().getValidOffsets(local);
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < valid.size(); i++) out.add(Integer.valueOf(valid.get(i).getTotalSeconds()));
        return out;
    }
    public long toEpochMillis(int year, int month, int day, int hour, int minute, int offsetSeconds) {
        LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute, 0);
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);
        if (!active().getRules().isValidOffset(local, offset)) throw new DateTimeException("offset");
        return local.toInstant(offset).toEpochMilli();
    }
    public int clockEpoch(long epochMillis) {
        int base = 31 * zoneId().hashCode() + offsetSecondsAt(epochMillis);
        return 31 * base + dayKey(epochMillis).hashCode();
    }
}

final class StepRandom implements StepRng {
    private final Random random;
    StepRandom() { this(new Random()); }
    StepRandom(Random random) { this.random = random; }
    public double nextUnit() { return random.nextDouble(); }
    public String newId() { return UUID.randomUUID().toString(); }
}

final class StepProcessIdentity {
    static final String JVM = java.util.UUID.randomUUID().toString();
    static final String BOOT = "boot";
    final String processId;
    final String bootId;

    StepProcessIdentity() {
        this(JVM, readBootId());
    }

    StepProcessIdentity(String processId, String bootId) {
        this.processId = processId == null || processId.length() == 0 ? JVM : processId;
        this.bootId = bootId == null || bootId.length() == 0 ? BOOT : bootId;
    }

    boolean matches(String processId, String bootId) {
        return this.processId.equals(processId == null ? "" : processId)
                && this.bootId.equals(bootId == null ? "" : bootId);
    }

    private static String readBootId() {
        String id = firstLine("/proc/sys/kernel/random/boot_id");
        if (id.length() > 0) return id;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/stat"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("btime ")) return "btime-" + line.substring(6).trim();
            }
        } catch (Exception ignored) {
            // Explicit fallback keeps host fixtures portable; injected identities test reboot handling.
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) { }
        }
        return BOOT;
    }

    private static String firstLine(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) { }
        }
    }
}

final class FixedStepRng implements StepRng {
    private final double unit;
    private int n;
    FixedStepRng(double unit) { this.unit = unit; }
    public double nextUnit() { return unit; }
    public String newId() { return "id-" + (++n); }
}
