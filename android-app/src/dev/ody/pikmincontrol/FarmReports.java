package dev.ody.pikmincontrol;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;

/** App-private reports; no coordinates. Existing native TSV protocols are read-only. */
final class FarmReports {
    private static File directory(Context c){File d=new File(c.getFilesDir(),"farm-reports");d.mkdirs();return d;}
    static synchronized void save(Context c,FarmRunReport r) {
        if(!r.id.matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("report id");
        AtomicFile f=new AtomicFile(new File(directory(c),r.started+"-"+r.id+".properties"));FileOutputStream out=null;
        try{out=f.startWrite();r.properties().store(out,"Pikmin farm report; private task identifiers, no coordinates");f.finishWrite(out);}
        catch(Exception e){r.incomplete=true;if(out!=null)f.failWrite(out);android.util.Log.w("PikminFarmReport","report could not be saved");}
    }
    static synchronized List<FarmRunReport> list(Context c){
        List<FarmRunReport> reports=new ArrayList<>();File[] files=directory(c).listFiles((d,n)->n.matches("[0-9]+-[0-9a-f-]{36}\\.properties"));
        if(files==null)return reports;
        Arrays.sort(files,(a,b)->b.getName().compareTo(a.getName()));
        for(File f:files){
            try {if(f.length()>4*1024*1024)continue;Properties p=new Properties();try(InputStream in=new AtomicFile(f).openRead()){p.load(in);}
                FarmRunReport r=FarmRunReport.restore(p);reports.add(r);
            }catch(Exception ignored){}
            if(reports.size()>=20)break;
        }return reports;
    }
    static synchronized void retain(Context c){
        File[] files=directory(c).listFiles((d,n)->n.matches("[0-9]+-[0-9a-f-]{36}\\.properties"));if(files==null)return;
        Arrays.sort(files,(a,b)->b.getName().compareTo(a.getName()));
        // Only this feature's generated reports, never user screenshots, route files or native logs.
        for(int i=20;i<files.length;i++)new AtomicFile(files[i]).delete();
    }
    static void collect(Context c,FarmRunReport report) {
        try{
            BoundedCommand.Result r=snapshot();
            report.ingestSnapshot(r.text,System.currentTimeMillis(),r.ok&&!r.truncated);
        }catch(Exception e){report.incomplete=true;}
        finally{save(c,report);}
    }
    static BoundedCommand.Result snapshot() throws Exception {
        String dir=EnvironmentGateway.GAME;
        String cmd="echo DISPATCH; if test -r "+dir+"dispatch_history.tsv; then tail -n 5001 "+dir+"dispatch_history.tsv; else echo MISSING; fi; "
                +"echo REWARDS; if test -r "+dir+"return_reward_history.tsv; then tail -n 5001 "+dir+"return_reward_history.tsv; else echo MISSING; fi; echo END";
        return BoundedCommand.root(cmd,2,524288);
    }
    static BoundedCommand.Result dispatchTail() throws Exception {
        String file=EnvironmentGateway.GAME+"dispatch_history.tsv";
        String cmd="if test -r "+file+"; then tail -n 1001 "+file+"; else echo MISSING; fi";
        return BoundedCommand.root(cmd,2,131072);
    }
    static String formatted(Context c,boolean refresh){
        List<FarmRunReport> reports=list(c);
        if(reports.isEmpty())return "尚無新版結果報告；舊工作紀錄保留，但無法回推可靠統計。";
        boolean live=FlowerFarmService.isRunning();long now=System.currentTimeMillis();
        if(refresh&&!live){
            BoundedCommand.Result snapshot=null;
            for(FarmRunReport r:reports)if(r.ended>0&&now>=r.started&&now-r.started<=FarmRunReport.DAY){
                try{if(snapshot==null)snapshot=snapshot();r.ingestSnapshot(snapshot.text,System.currentTimeMillis(),snapshot.ok&&!snapshot.truncated);}
                catch(Exception e){r.incomplete=true;break;}
                finally{save(c,r);}
            }
        }
        StringBuilder text=new StringBuilder();
        for(int i=0;i<reports.size();i++){if(i>0)text.append("\n\n────────\n");text.append(reports.get(i).formatted(i==0&&live,now));}
        return text.toString();
    }
}
