package dev.ody.pikmincontrol;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/** Deterministic plan only. Adapter must persist Intent BEFORE any side effect. */
final class EmulatorDailyPlan {
    static final ZoneId ZONE=ZoneId.of("Asia/Taipei");
    static final int OPEN=7*60, FINAL=16*60+30, CLOSE=18*60, SLOT_MINUTES=45, MAX_RESCANS=3;
    // Legacy constants/wires remain readable, never silently reinterpreted.
    // n22 work dates start at 06:00 and include the following morning.
    static final int NIGHT_OPEN=6*60, NIGHT_FINAL=27*60+30, NIGHT_CLOSE=28*60, NIGHT_SLOTS=128;
    static final String NIGHT_ROUTE_NUMBER="(?:[0-9]|[1-9][0-9]|1[01][0-9]|12[0-7])";
    static boolean overnightKey(String key){return key!=null&&key.contains(":n22:");}
    static boolean eveningBatchKey(String key){return key!=null&&key.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}:e23:batch-[1-3]");}
    // New two-hour namespace; legacy e23 intents retain their original 23:30 deadline.
    static boolean twoHourEveningKey(String key){return key!=null&&key.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}:e23h2:batch-[1-3]");}
    static LocalDate eveningWorkDay(long epoch){
        ZonedDateTime t=Instant.ofEpochMilli(epoch).atZone(ZONE);
        return t.getHour()<1?t.toLocalDate().minusDays(1):t.toLocalDate();
    }
    static LocalDate workDay(long epoch,boolean overnight){
        ZonedDateTime t=Instant.ofEpochMilli(epoch).atZone(ZONE);
        return overnight&&t.getHour()<6?t.toLocalDate().minusDays(1):t.toLocalDate();
    }
    static int workMinute(long epoch,boolean overnight){
        ZonedDateTime t=Instant.ofEpochMilli(epoch).atZone(ZONE);int m=t.getHour()*60+t.getMinute();
        return overnight&&m<NIGHT_OPEN?m+1440:m;
    }
    static boolean active(long epoch,boolean overnight){int m=workMinute(epoch,overnight);return m>=(overnight?NIGHT_OPEN:OPEN)&&m<(overnight?NIGHT_CLOSE:CLOSE);}
    static boolean routeWindow(long epoch,boolean overnight){return active(epoch,overnight)&&workMinute(epoch,overnight)<(overnight?NIGHT_FINAL:FINAL);}
    static boolean keyDay(String key,long epoch){return workDay(epoch,overnightKey(key)).toString().equals(key.substring(0,10));}
    static boolean batchWindow(String key,long epoch){
        if(twoHourEveningKey(key)){
            LocalDate day=LocalDate.parse(key.substring(0,10));
            long start=day.atTime(23,0).atZone(ZONE).toInstant().toEpochMilli();
            return epoch>=start&&epoch<day.plusDays(1).atTime(1,0).atZone(ZONE).toInstant().toEpochMilli();
        }
        if(eveningBatchKey(key)){
            ZonedDateTime t=Instant.ofEpochMilli(epoch).atZone(ZONE);
            int minute=t.getHour()*60+t.getMinute();
            return keyDay(key,epoch)&&minute>=1380&&minute<1410;
        }
        boolean n=overnightKey(key);return keyDay(key,epoch)&&active(epoch,n)&&!routeWindow(epoch,n);
    }
    static long deadline(String key){
        LocalDate day=LocalDate.parse(key.substring(0,10));
        if(twoHourEveningKey(key))return day.plusDays(1).atTime(1,0).atZone(ZONE).toInstant().toEpochMilli();
        return eveningBatchKey(key)?day.atTime(23,30).atZone(ZONE).toInstant().toEpochMilli():deadline(day,overnightKey(key));
    }
    static long deadline(LocalDate day,boolean overnight){return (overnight?day.plusDays(1).atTime(4,0):day.atTime(18,0)).atZone(ZONE).toInstant().toEpochMilli();}
    // User-established planting range; not an official guarantee of flower yield.
    static final double DEFAULT_PLANTING_KMH=19.0, FALLBACK_PLANTING_KMH=17.0;
    static double plantingSpeed(double kmh) {
        if(Double.isNaN(kmh) || Double.isInfinite(kmh) ||
                kmh<FALLBACK_PLANTING_KMH || kmh>DEFAULT_PLANTING_KMH)
            throw new IllegalArgumentException("Planting speed must be 17–19 km/h");
        return kmh;
    }
    enum Action { WAIT, START_ROUTE, STOP_ROUTE, FINAL_BATCH, CONFIRM_CURRENT, REVIEW, DONE }
    enum Pending { NONE, ROUTE, BATCH }
    static final class Journal {
        LocalDate day;
        final boolean overnight;
        String prefix(){return day+(overnight?":n22:":":");}
        int slots(){return overnight?NIGHT_SLOTS:13;}
        long lastEpoch;
        Pending pending=Pending.NONE;
        String pendingKey="";
        boolean uncertain, finalComplete, failed;
        int finalAttempts;
        final Set<Integer> routeSlots=new HashSet<>();
        Journal(LocalDate day) { this(day,false); }
        Journal(LocalDate day,boolean overnight) { if(day == null)throw new IllegalArgumentException("Day required"); this.day=day;this.overnight=overnight; }
        String encode() {
            StringBuilder slots=new StringBuilder();
            for(int i=0;i<slots();i++)if(routeSlots.contains(i)){if(slots.length()>0)slots.append(',');slots.append(i);}
            return (overnight?"v2-n22":"v1")+"\t"+day+"\t"+lastEpoch+"\t"+pending+"\t"+pendingKey+"\t"+
                    uncertain+"\t"+finalComplete+"\t"+failed+"\t"+finalAttempts+"\t"+(slots.length()==0?"-":slots)+"\n";
        }
        static Journal decode(String wire) {
            if(wire == null || wire.length()>2048 || !wire.endsWith("\n") || wire.indexOf('\r')>=0)throw new IllegalArgumentException("Invalid journal");
            String[] f=wire.substring(0,wire.length()-1).split("\t",-1);
            if(f.length!=10 || !("v1".equals(f[0])||"v2-n22".equals(f[0])))throw new IllegalArgumentException("Journal schema");
            Journal j=new Journal(LocalDate.parse(f[1]),"v2-n22".equals(f[0])); j.lastEpoch=Long.parseLong(f[2]);j.pending=Pending.valueOf(f[3]);j.pendingKey=f[4];
            j.uncertain=bool(f[5]);j.finalComplete=bool(f[6]);j.failed=bool(f[7]);j.finalAttempts=Integer.parseInt(f[8]);
            if(j.lastEpoch<0 || j.finalAttempts<0 || j.finalAttempts>MAX_RESCANS ||
                    (j.pending==Pending.NONE && (!j.pendingKey.isEmpty() || j.uncertain)) ||
                    (j.pending!=Pending.NONE && !j.pendingKey.matches("[0-9-]{10}:"+(j.overnight?"n22:":"")+"(route-"+(j.overnight?NIGHT_ROUTE_NUMBER:"[0-9]{1,2}")+"|batch-[1-3])")))throw new IllegalArgumentException("Invalid journal state");
            if(!"-".equals(f[9]))for(String s:f[9].split(",",-1)){
                int slot=Integer.parseInt(s);if(slot<0 || slot>=j.slots() || !j.routeSlots.add(slot))throw new IllegalArgumentException("Invalid route slot");
            }
            // Canonical representation rejects alternate dates/numbers/keys and inconsistent records.
            if(!j.encode().equals(wire))throw new IllegalArgumentException("Noncanonical journal");
            if(j.pending==Pending.ROUTE && (!j.pendingKey.startsWith(j.prefix()+"route-") ||
                    !j.routeSlots.contains(Integer.parseInt(j.pendingKey.substring(j.pendingKey.lastIndexOf('-')+1)))))throw new IllegalArgumentException("Unreserved route");
            if(j.pending==Pending.BATCH && (!j.pendingKey.equals(j.prefix()+"batch-"+j.finalAttempts) || j.finalComplete))throw new IllegalArgumentException("Unreserved batch");
            return j;
        }
        private static boolean bool(String s){if(!s.equals("true")&&!s.equals("false"))throw new IllegalArgumentException("Invalid boolean");return Boolean.parseBoolean(s);}
    }
    static final class Decision {
        final Action action;
        final String key, reason;
        Decision(Action action,String key,String reason){this.action=action;this.key=key;this.reason=reason;}
    }
    static Decision next(Journal j,long epoch,boolean ready,boolean routeBusy,boolean batchBusy,boolean routeConfigured) {
        if(j == null || epoch<0)throw new IllegalArgumentException("Clock/journal required");
        ZonedDateTime local=Instant.ofEpochMilli(epoch).atZone(ZONE);
        int minute=workMinute(epoch,j.overnight),open=j.overnight?NIGHT_OPEN:OPEN,finish=j.overnight?NIGHT_FINAL:FINAL,close=j.overnight?NIGHT_CLOSE:CLOSE;
        LocalDate day=workDay(epoch,j.overnight);
        if(epoch<j.lastEpoch)return decision(Action.REVIEW,"","clock-moved-backwards");
        j.lastEpoch=epoch;
        if(!day.equals(j.day)) {
            if(day.isBefore(j.day) || j.pending!=Pending.NONE || routeBusy || batchBusy || j.uncertain)
                return decision(Action.REVIEW,j.pendingKey,"previous-day-needs-confirmation");
            j.day=day;j.routeSlots.clear();j.finalAttempts=0;j.finalComplete=false;j.failed=false;
        }
        // The adapter supplies owned routeBusy only. Pausing that owned route is
        // safe even while an earlier start acknowledgement is uncertain.
        if(routeBusy && (minute>=finish || minute<open))return decision(Action.STOP_ROUTE,j.pendingKey,"time-boundary-stop-owned-route");
        if(j.failed)return decision(Action.REVIEW,j.pendingKey,"operator-review-required");
        if(j.uncertain)return decision(Action.CONFIRM_CURRENT,j.pendingKey,"never-replay-uncertain-command");
        if(minute>=close || minute<open) {
            if(routeBusy)return decision(Action.STOP_ROUTE,j.pendingKey,"outside-window-stop-owned-route");
            if(batchBusy || j.pending!=Pending.NONE)return decision(Action.CONFIRM_CURRENT,j.pendingKey,"no-new-work-outside-window");
            return decision(minute>=close?Action.DONE:Action.WAIT,"","outside-window");
        }
        if(minute>=finish) {
            if(routeBusy)return decision(Action.STOP_ROUTE,j.pendingKey,"final-sweep-needs-gps-lock");
            if(batchBusy || j.pending!=Pending.NONE)return decision(Action.CONFIRM_CURRENT,j.pendingKey,"confirm-current-first");
            if(j.finalComplete)return decision(Action.WAIT,"","final-sweep-confirmed");
            // Pending/uncertain and real safety failures were handled above.
            // Only independently closed sweeps can reach this idle state.
            // Keep their todos, stop admissions for today, but do not poison
            // tomorrow with a permanent review latch just for clean leftovers.
            if(j.finalAttempts>=MAX_RESCANS)return decision(Action.WAIT,"","finite-final-sweeps-exhausted-todos-retained");
            if(!ready)return decision(Action.WAIT,"","fresh-runtime-required");
            return decision(Action.FINAL_BATCH,j.prefix()+"batch-"+(j.finalAttempts+1),"all-three-types-with-exact-claims");
        }
        if(routeBusy || batchBusy || j.pending!=Pending.NONE)return decision(Action.CONFIRM_CURRENT,j.pendingKey,"exclusive-gps-work");
        if(!ready || !routeConfigured)return decision(Action.WAIT,"",!ready?"fresh-runtime-required":"private-route-required");
        int slot=(minute-open)/SLOT_MINUTES;
        if(j.overnight){
            // Sequential, not a 45-minute timer: safely closed work may continue
            // immediately, without idle gaps or replay/catch-up of old intents.
            slot=0;for(int used:j.routeSlots)slot=Math.max(slot,used+1);
            if(slot>=NIGHT_SLOTS)return decision(Action.WAIT,"","finite-route-budget-exhausted");
        }
        if(j.routeSlots.contains(slot))return decision(Action.WAIT,"","slot-already-consumed");
        return decision(Action.START_ROUTE,j.prefix()+"route-"+slot,j.overnight?"next-closed-segment-no-replay":"one-segment-no-catch-up");
    }
    /** Commit this reservation atomically before handing the command to the existing service. */
    static void reserve(Journal j,Decision d) {
        if(j.pending!=Pending.NONE || j.uncertain || j.failed)throw new IllegalStateException("Work already reserved");
        if(d.action==Action.START_ROUTE) {
            if(!d.key.startsWith(j.prefix()+"route-"))throw new IllegalArgumentException("Wrong route day");
            int slot=Integer.parseInt(d.key.substring(d.key.lastIndexOf('-')+1));
            if(slot<0 || slot>=j.slots() || !d.key.equals(j.prefix()+"route-"+slot) || !j.routeSlots.add(slot))throw new IllegalStateException("Route slot already used");
            j.pending=Pending.ROUTE;
        } else if(d.action==Action.FINAL_BATCH) {
            if(j.finalComplete || j.finalAttempts>=MAX_RESCANS || !d.key.equals(j.prefix()+"batch-"+(j.finalAttempts+1)))throw new IllegalStateException("Invalid batch reservation");
            j.finalAttempts++;j.pending=Pending.BATCH;
        } else throw new IllegalArgumentException("Only commands can be reserved");
        j.pendingKey=d.key;j.uncertain=true;
    }
    /** Acknowledged means observed service admission, NOT gameplay completion. */
    static void acknowledged(Journal j,String key) { requirePending(j,key);j.uncertain=false; }
    static void confirmed(Journal j,String key,boolean allClaimsConfirmed) {
        requirePending(j,key);
        if(j.pending==Pending.BATCH)j.finalComplete=allClaimsConfirmed;
        j.pending=Pending.NONE;j.pendingKey="";j.uncertain=false;
    }
    private static void requirePending(Journal j,String key){if(j.pending==Pending.NONE || !j.pendingKey.equals(key))throw new IllegalStateException("Wrong confirmation key");}
    private static Decision decision(Action a,String key,String reason){return new Decision(a,key,reason);}
}
