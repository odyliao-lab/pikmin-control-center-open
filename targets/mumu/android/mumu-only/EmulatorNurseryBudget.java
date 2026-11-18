package dev.ody.pikmincontrol;

import java.time.Instant;
import java.time.LocalDate;

/** User-reported game rule. Not a total-step limit or proof of game adoption. */
final class EmulatorNurseryBudget {
    static final long LIMIT=50000, MAX_AGE_MS=5000;
    enum Status { UNKNOWN, AVAILABLE, EXHAUSTED, COUNTER_REVIEW }
    static final class Snapshot {
        final EmulatorDailyRunner.Binding binding;
        final LocalDate gameDay;
        final long gameCountedSteps,capturedAt;
        Snapshot(EmulatorDailyRunner.Binding binding,LocalDate gameDay,long gameCountedSteps,long capturedAt) {
            if(binding==null || gameDay==null || gameCountedSteps<0 || capturedAt<=0)
                throw new IllegalArgumentException("Actual game daily counter required, not HC/writer totals");
            this.binding=binding;this.gameDay=gameDay;this.gameCountedSteps=gameCountedSteps;this.capturedAt=capturedAt;
        }
    }
    static final class Advice {
        final Status status;
        final long remaining;
        final String reason;
        Advice(Status status,long remaining,String reason){this.status=status;this.remaining=remaining;this.reason=reason;}
        long expectedCredit(long additionalSteps) {
            if(additionalSteps<0)throw new IllegalArgumentException("Nonnegative steps required");
            // -1 means unknown, not zero observed progress. At/above the ceiling,
            // do not predict additional hatching credit even if steps keep growing.
            if(status==Status.UNKNOWN || status==Status.COUNTER_REVIEW)return -1;
            return Math.min(remaining,additionalSteps);
        }
    }
    static final class Ledger {
        final LocalDate day;
        long highWater,lastCapturedAt;
        boolean counterReview;
        Ledger(LocalDate day,long highWater,long at,boolean review) {
            if(day==null || highWater<0 || at<=0)throw new IllegalArgumentException("Nursery ledger required");
            this.day=day;this.highWater=highWater;lastCapturedAt=at;counterReview=review;
        }
        String encode(){return "v1\t"+day+"\t"+highWater+"\t"+lastCapturedAt+"\t"+counterReview+"\tend";}
        static Ledger decode(String wire) {
            String[] f=wire.split("\t",-1);
            if(f.length!=6 || !f[0].equals("v1") || !f[5].equals("end") ||
                    !(f[4].equals("true")||f[4].equals("false")))throw new IllegalArgumentException("Nursery ledger schema");
            Ledger l=new Ledger(LocalDate.parse(f[1]),Long.parseLong(f[2]),Long.parseLong(f[3]),Boolean.parseBoolean(f[4]));
            if(!wire.equals(l.encode()))throw new IllegalArgumentException("Noncanonical nursery ledger");return l;
        }
    }
    static boolean usable(Snapshot s,EmulatorDailyRunner.Binding binding,LocalDate day,long now,boolean ready) {
        if(!ready || s==null || !binding.same(s.binding) || !day.equals(s.gameDay) ||
                now<s.capturedAt || now-s.capturedAt>MAX_AGE_MS)return false;
        // Only daytime observations whose game-reported date agrees with this run.
        // Do not infer a reset from changing route/city or the host midnight alone.
        java.time.ZonedDateTime clock=Instant.ofEpochMilli(now).atZone(EmulatorDailyPlan.ZONE);
        int minute=clock.getHour()*60+clock.getMinute();
        return day.equals(clock.toLocalDate()) && minute>=EmulatorDailyPlan.OPEN && minute<EmulatorDailyPlan.CLOSE;
    }
    static Ledger update(Ledger old,Snapshot s) {
        if(old==null || s.gameDay.isAfter(old.day))return new Ledger(s.gameDay,s.gameCountedSteps,s.capturedAt,false);
        if(!s.gameDay.equals(old.day))throw new IllegalArgumentException("Nursery day regressed");
        Ledger next=Ledger.decode(old.encode());
        if(s.capturedAt<old.lastCapturedAt || s.gameCountedSteps<old.highWater ||
                (s.capturedAt==old.lastCapturedAt && s.gameCountedSteps!=old.highWater))next.counterReview=true;
        next.highWater=Math.max(old.highWater,s.gameCountedSteps);
        next.lastCapturedAt=Math.max(old.lastCapturedAt,s.capturedAt);
        return next;
    }
    static Advice advice(Ledger ledger,boolean usable) {
        if(!usable || ledger==null)return unknown();
        if(ledger.counterReview)return new Advice(Status.COUNTER_REVIEW,-1,"game-counter-regressed-preserve-high-water");
        long remaining=LIMIT-Math.min(LIMIT,ledger.highWater);
        return new Advice(remaining==0?Status.EXHAUSTED:Status.AVAILABLE,remaining,
                remaining==0?"nursery-credit-ceiling-not-total-step-limit":"prioritize-nursery-before-credit-ceiling");
    }
    static Advice unknown(){return new Advice(Status.UNKNOWN,-1,"fresh-game-daily-counter-required");}
}
