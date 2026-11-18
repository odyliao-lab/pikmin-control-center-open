package dev.ody.pikmincontrol;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Explicit bounded test namespace, not readiness or dispatch authority.
 * Formal keys/windows remain owned by the unchanged daily plan/port. */
final class EmulatorDevelopmentBatch {
    static final long LIMIT_MS=15*60*1000L;
    static final int MAX_SWEEPS=3;
    static final class Window {
        final String key;
        final long created,deadline;
        Window(String key,long created,long deadline){
            requireKeyString(key);
            if(!development(key)||created<=0||created>Long.MAX_VALUE-LIMIT_MS||deadline!=created+LIMIT_MS||
                    !day(created).equals(key.substring(0,10)))bad();
            this.key=key;this.created=created;this.deadline=deadline;
        }
        boolean open(long now){return now>=created&&now<deadline&&day(now).equals(key.substring(0,10));}
        Window sweep(int index){return new Window(keyAt(key,index),created,deadline);}
        boolean sameRun(Window other){return other!=null&&sameRunKey(key,other.key)&&created==other.created&&deadline==other.deadline;}
    }
    static Window create(String day,String nonce,long now){
        if(nonce==null||!nonce.matches("[0-9a-f]{32}")||now<=0||now>Long.MAX_VALUE-LIMIT_MS)bad();
        return new Window(day+":development-batch-"+nonce+"-1",now,now+LIMIT_MS);
    }
    static void requireBatchKey(EmulatorDailyRunner.Binding binding,String key){
        if(!development(key)){EmulatorAndroidDailyPort.requireKey(binding,key,true);return;}
        if(binding==null)bad();requireKeyString(key);
    }
    static void requireKeyString(String key){
        if(key==null||!key.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}:(?:(?:n22:|e23:|e23h2:)?batch-[1-3]|development-batch-[0-9a-f]{32}-[1-3])")||
                !LocalDate.parse(key.substring(0,10)).toString().equals(key.substring(0,10)))bad();
    }
    static boolean development(String key){return key!=null&&key.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}:development-batch-[0-9a-f]{32}-[1-3]");}
    static int index(String key){requireKeyString(key);return key.charAt(key.length()-1)-'0';}
    static String keyAt(String key,int index){requireKeyString(key);if(index<1||index>MAX_SWEEPS)bad();return key.substring(0,key.length()-1)+index;}
    static boolean sameRunKey(String first,String second){requireKeyString(first);requireKeyString(second);return first.substring(0,first.length()-1).equals(second.substring(0,second.length()-1));}
    static boolean earlier(String first,String second){return sameRunKey(first,second)&&index(first)<index(second);}
    static boolean sameWindow(Window first,Window second){return first==null?second==null:first.sameRun(second);}
    /** Pure sequencing only. Actual immutable-file reconciliation and fresh
     * identity/location/resources stay mandatory in the original consumers. */
    static final class Coordinator {
        final EmulatorDailyRunner.Binding binding;final Window window;
        Coordinator(EmulatorDailyRunner.Binding binding,Window first){
            if(binding==null||first==null||index(first.key)!=1)bad();this.binding=binding;window=first;
        }
        String next(List<EmulatorDailyBatchOutcome> completed,EmulatorDailyBatchScan.Snapshot fresh,long now){
            if(completed==null||completed.size()>MAX_SWEEPS||fresh==null||!binding.same(fresh.binding)||fresh.scanMs<=0||fresh.scanMs>now||now-fresh.scanMs>12000)bad();
            for(int n=0;n<completed.size();n++){
                EmulatorDailyBatchOutcome outcome=completed.get(n);
                if(outcome==null||!binding.same(outcome.inventory.binding)||!window.sweep(n+1).key.equals(outcome.inventory.key)||
                        !window.sameRun(outcome.inventory.developmentWindow)||outcome.needsReview())bad();
            }
            if(!window.open(now))return ""; // no new admission; not a completion claim
            if(completed.isEmpty())return window.key;
            EmulatorDailyBatchDay day=new EmulatorDailyBatchDay(binding,window.sweep(completed.size()).key,completed,fresh);
            if(!day.clean||day.unresolved!=0)bad();
            if(day.allCollected()||completed.size()==MAX_SWEEPS)return "";
            return window.sweep(completed.size()+1).key;
        }
    }
    private static String day(long now){return Instant.ofEpochMilli(now).atZone(EmulatorDailyPlan.ZONE).toLocalDate().toString();}
    private static void bad(){throw new IllegalArgumentException("Exact new bounded development batch required; never adopt or extend");}
}
