package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.util.*;

/** Immutable explicit-request/reservation correlation, NEVER readiness or a
 * recovered live runner. A retained reservation can only be observed. */
final class EmulatorDevelopmentBatchRun {
    final EmulatorDailyRunner.Binding binding;
    final String configSha;
    final EmulatorDevelopmentBatch.Window window;
    EmulatorDevelopmentBatchRun(EmulatorDailyRunner.Binding binding,String configSha,EmulatorDevelopmentBatch.Window first){
        if(binding==null||configSha==null||!configSha.matches("[a-f0-9]{64}")||first==null||EmulatorDevelopmentBatch.index(first.key)!=1)bad();
        this.binding=binding;this.configSha=configSha;window=first;
    }
    String encode(){return checked("v1-development-run\t"+EmulatorDailyRunner.base64(binding.encode())+"\t"+configSha+"\t"+window.key+"\t"+window.created+"\t"+window.deadline+"\texplicit-intent-not-admitted\tend\n");}
    static EmulatorDevelopmentBatchRun decode(String wire){
        String[] f=body(wire).split("\t",-1);if(f.length!=8||!f[0].equals("v1-development-run")||!f[6].equals("explicit-intent-not-admitted")||!f[7].equals("end"))bad();
        EmulatorDevelopmentBatchRun run=new EmulatorDevelopmentBatchRun(EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(f[1])),f[2],new EmulatorDevelopmentBatch.Window(f[3],Long.parseLong(f[4]),Long.parseLong(f[5])));
        if(!wire.equals(run.encode()))bad();return run;
    }
    // SharedPreferences XML pretty-printing can append indentation after a
    // multiline value ending in newline. Store a canonical single-line
    // envelope, not the checksummed multiline request itself. This pointer
    // is only correlation; it never authorizes recovery or replay.
    static String preference(EmulatorDevelopmentBatchRun run){
        if(run==null)bad();return "v2-development-pointer:"+EmulatorDailyRunner.base64(run.encode());
    }
    static EmulatorDevelopmentBatchRun decodePreference(String wire){
        if(wire==null||wire.length()>8192)bad();
        if(!wire.startsWith("v2-development-pointer:"))return decode(wire); // exact legacy only; never trim padding
        EmulatorDevelopmentBatchRun run=decode(EmulatorDailyRunner.unbase64(wire.substring("v2-development-pointer:".length())));
        if(!wire.equals(preference(run)))bad();return run;
    }
    String reservation(String key,long epoch,long now){
        if(!EmulatorDevelopmentBatch.sameRunKey(window.key,key)||!window.open(now)||epoch<=0)bad();
        return checked("v1-development-reservation\t"+EmulatorDailyRunner.digest(encode())+"\t"+key+"\t"+epoch+"\t"+now+"\tintent-never-replay\tend\n");
    }
    void requireReservation(String key,String wire){
        String[] f=body(wire).split("\t",-1);if(f.length!=7||!f[0].equals("v1-development-reservation")||!f[1].equals(EmulatorDailyRunner.digest(encode()))||!f[2].equals(key)||!f[5].equals("intent-never-replay")||!f[6].equals("end"))bad();
        if(!wire.equals(reservation(key,Long.parseLong(f[3]),Long.parseLong(f[4]))))bad();
    }
    void requireReservation(String key,String wire,long epoch){requireReservation(key,wire);String[] f=body(wire).split("\t",-1);if(Long.parseLong(f[3])!=epoch)bad();}
    static String runLeaf(String firstKey){EmulatorDevelopmentBatch.requireKeyString(firstKey);if(!EmulatorDevelopmentBatch.development(firstKey)||EmulatorDevelopmentBatch.index(firstKey)!=1)bad();return EmulatorDailyRunner.digest(firstKey+":development-run");}
    static String reservationLeaf(String key){EmulatorDevelopmentBatch.requireKeyString(key);if(!EmulatorDevelopmentBatch.development(key))bad();return EmulatorDailyRunner.digest(key+":development-reservation");}
    void persist(Path files,EmulatorAndroidDailyStore.DirectorySync sync,EmulatorDailyRouteWork.PrivateMode mode)throws Exception{
        EmulatorDailyBatchWork.persist(files.resolve("daily-batch-links"),runLeaf(window.key),encode(),sync,mode);
    }
    void reserve(Path files,String key,long epoch,long now,EmulatorAndroidDailyStore.DirectorySync sync,EmulatorDailyRouteWork.PrivateMode mode)throws Exception{
        EmulatorDevelopmentBatchRun actual=read(files,window.key);if(!encode().equals(actual.encode()))bad();
        int index=EmulatorDevelopmentBatch.index(key);
        for(int n=1;n<index;n++){String prior=window.sweep(n).key;requireReservation(prior,EmulatorDailyBatchOutcome.readPrivate(files.resolve("daily-batch-links").resolve(reservationLeaf(prior)+".tsv"),4096));}
        EmulatorDailyBatchWork.persist(files.resolve("daily-batch-links"),reservationLeaf(key),reservation(key,epoch,now),sync,mode);
    }
    static EmulatorDevelopmentBatchRun read(Path files,String firstKey)throws Exception{
        EmulatorDevelopmentBatchRun run=decode(EmulatorDailyBatchOutcome.readPrivate(files.resolve("daily-batch-links").resolve(runLeaf(firstKey)+".tsv"),4096));
        if(!run.window.key.equals(firstKey))bad();return run;
    }
    static EmulatorDevelopmentBatchRun requireInventory(Path files,EmulatorDailyBatchInventory inventory)throws Exception{
        if(inventory==null||inventory.developmentWindow==null)bad();
        EmulatorDevelopmentBatchRun run=read(files,inventory.developmentWindow.sweep(1).key);
        if(!run.binding.same(inventory.binding)||!run.window.sameRun(inventory.developmentWindow))bad();
        String wire=EmulatorDailyBatchOutcome.readPrivate(files.resolve("daily-batch-links").resolve(reservationLeaf(inventory.key)+".tsv"),4096);
        run.requireReservation(inventory.key,wire,inventory.locationEpoch);
        if(Long.parseLong(body(wire).split("\t",-1)[4])>inventory.created)bad();return run;
    }
    String latestReservation(Path files)throws Exception{
        String latest="";boolean gap=false;
        for(int n=1;n<=EmulatorDevelopmentBatch.MAX_SWEEPS;n++){
            String key=window.sweep(n).key;Path file=files.resolve("daily-batch-links").resolve(reservationLeaf(key)+".tsv");
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)){gap=true;continue;}
            if(gap)bad();requireReservation(key,EmulatorDailyBatchOutcome.readPrivate(file,4096));latest=key;
        }
        return latest;
    }
    private static String checked(String body){return body+"sha256\t"+EmulatorDailyRunner.digest(body)+"\n";}
    private static String body(String wire){
        if(wire==null||wire.length()>4096||wire.indexOf('\r')>=0)bad();String[] rows=wire.split("\n",-1);
        if(rows.length!=3||!rows[2].isEmpty()||!rows[1].equals("sha256\t"+EmulatorDailyRunner.digest(rows[0]+"\n")))bad();return rows[0];
    }
    private static void bad(){throw new IllegalArgumentException("Exact new development run/reservation required; never recover dispatch authority");}
}
