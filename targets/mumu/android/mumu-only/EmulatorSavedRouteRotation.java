package dev.ody.pikmincontrol;

import java.time.*;
import java.time.temporal.ChronoUnit;

/** Explicit dated opt-in. Calendar days are Taipei 06:00 work days, never boot counts. */
final class EmulatorSavedRouteRotation {
    static final ZoneId ZONE=ZoneId.of("Asia/Taipei");
    static final int FIRST=360,SECOND=1005,CLOSING=210,REST=240;
    final LocalDate firstDay;final boolean daytime;
    EmulatorSavedRouteRotation(LocalDate day){this(day,true);}
    private EmulatorSavedRouteRotation(LocalDate day,boolean daytime){if(day==null)throw new IllegalArgumentException("Explicit first day required");firstDay=day;this.daytime=daytime;}
    static LocalDate firstDay(long wall){ZonedDateTime t=Instant.ofEpochMilli(wall).atZone(ZONE);return t.getHour()>=23?t.toLocalDate().plusDays(1):t.toLocalDate();}
    static EmulatorSavedRouteRotation daytimePreserving(String prior,long wall){
        if(wall<=0)throw new IllegalArgumentException("Real wall clock required");
        return new EmulatorSavedRouteRotation(prior==null||prior.isEmpty()?firstDay(wall):decode(prior).firstDay,true);
    }
    static EmulatorSavedRouteRotation overnightPreserving(String prior,long wall){
        if(wall<=0)throw new IllegalArgumentException("Real wall clock required");
        if(prior==null||prior.isEmpty())throw new IllegalArgumentException("Original dated rotation required");
        LocalDate anchor=decode(prior).firstDay;
        return new EmulatorSavedRouteRotation(anchor,false);
    }
    static boolean daytimeActive(int minute){return minute>=420&&minute<1380;}
    static boolean maySelectInitial(boolean owned,boolean active,boolean paused){return !owned&&!active&&!paused;}
    String encode(){return daytime?"two-saved-routes-v2\t"+firstDay+"\t07:00\t15:00\t23:00\tTaipei-01,Seoul-01,Tokyo-01\tend":"two-saved-routes-v1\t"+firstDay+"\t06:00\t16:45\t03:30\tTaipei-01,Seoul-01,Tokyo-01\tend";}
    static EmulatorSavedRouteRotation decode(String wire){
        if(wire==null||wire.length()>180)throw new IllegalArgumentException("Rotation framing");
        String[] f=wire.split("\t",-1);
        if(f.length!=7)throw new IllegalArgumentException("Rotation fields");
        EmulatorSavedRouteRotation p=new EmulatorSavedRouteRotation(LocalDate.parse(f[1]),f[0].equals("two-saved-routes-v2"));
        if(!p.encode().equals(wire))throw new IllegalArgumentException("Exact approved rotation required");
        return p;
    }
    static final class Slot {
        final LocalDate day;final int half;final EmulatorSavedRoutePlan.City city;
        Slot(LocalDate day,int half,EmulatorSavedRoutePlan.City city){this.day=day;this.half=half;this.city=city;}
        String key(){return day+":"+half+":"+city.savedName;}
    }
    Slot at(long wall){
        if(wall<=0)throw new IllegalArgumentException("Real wall clock required");
        ZonedDateTime t=Instant.ofEpochMilli(wall).atZone(ZONE);int minute=t.getHour()*60+t.getMinute();
        if(daytime){if(!daytimeActive(minute)||t.toLocalDate().isBefore(firstDay))return null;return slot(t.toLocalDate(),minute<900?0:1);}
        // Closing and rest never begin a route switch. Their original stop/claim lanes win.
        if(minute>=CLOSING&&minute<FIRST)return null;
        LocalDate day=minute<FIRST?t.toLocalDate().minusDays(1):t.toLocalDate();
        if(day.isBefore(firstDay))return null;
        int half=minute>=FIRST&&minute<SECOND?0:1;
        return slot(day,half);
    }
    Slot retained(String key){
        String[] f=key.split(":",-1);
        if(f.length!=3||!(f[1].equals("0")||f[1].equals("1")))throw new IllegalArgumentException("Original rotation slot required");
        Slot slot=slot(LocalDate.parse(f[0]),Integer.parseInt(f[1]));
        if(!slot.key().equals(key))throw new IllegalArgumentException("Original rotation target differs");
        return slot;
    }
    private Slot slot(LocalDate day,int half){
        if(day.isBefore(firstDay))throw new IllegalArgumentException("Before rotation opt-in");
        long days=ChronoUnit.DAYS.between(firstDay,day);
        int index=(int)((Math.floorMod(days,3)*(daytime?1:2)+half)%3);
        return new Slot(day,half,EmulatorSavedRoutePlan.City.values()[index]);
    }
    static boolean mayReplace(boolean enabled,boolean owned,boolean paused,boolean loop19,
            boolean plantClosed,boolean helpersOff,boolean pending,boolean batch,boolean maintenance){
        return enabled&&owned&&paused&&loop19&&plantClosed&&helpersOff&&!pending&&!batch&&!maintenance;
    }
}
