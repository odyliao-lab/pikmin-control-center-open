package dev.ody.pikmincontrol;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;

final class DailyResultsStore {
    static synchronized String refresh(Context c)throws Exception {
        long now=System.currentTimeMillis();DailyResults result=new DailyResults(now,TimeZone.getDefault());
        File dir=new File(c.getFilesDir(),"daily-results");if(!dir.exists()&&!dir.mkdirs())throw new IOException("每日成果無法儲存");
        // Zone identity separates a changed local day; no reset or re-attribution of existing evidence.
        File base=new File(dir,result.day+"-"+DailyResults.digest(result.zone).substring(0,16)+".properties");AtomicFile file=new AtomicFile(base);
        if(base.exists()||new File(base+".bak").exists()){Properties p=new Properties();try(InputStream in=file.openRead()){p.load(new ByteArrayInputStream(SetupBundle.read(in,12*1024*1024)));}result.restore(p);}
        String[] commands=new String[4];String[] names={"dispatch_history.tsv","return_reward_history.tsv","feeding_history.tsv","planter_history.tsv"};
        for(int i=0;i<4;i++){String path=EnvironmentGateway.GAME+names[i];commands[i]="if test -r "+path+"; then tail -n 10001 "+path+" || echo ERROR; else echo MISSING; fi";}
        try {BoundedCommand.Result read=BoundedCommand.root(ProbeSnapshot.command(commands),5,2*1024*1024);
            String[] text=ProbeSnapshot.checkedSections(read,4);for(int i=0;i<4;i++)result.ingest(DailyResults.SOURCES[i],text[i],System.currentTimeMillis(),read.ok&&!read.truncated);
        }catch(Exception unavailable){result.incomplete=true;}
        FileOutputStream out=null;try{out=file.startWrite();result.encode().store(out,"Private hashed evidence, not an execution ledger");file.finishWrite(out);}
        catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
        // Only app-generated snapshots from past local dates; no native receipts/ledgers.
        Calendar cut=Calendar.getInstance();cut.add(Calendar.DAY_OF_MONTH,-7);
        java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);String date=f.format(cut.getTime());
        File[] files=dir.listFiles((d,n)->n.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9a-f]{16}\\.properties"));
        if(files!=null)for(File old:files)if(old.getName().substring(0,10).compareTo(date)<0&&!old.equals(base))new AtomicFile(old).delete();
        return result.formatted();
    }
}
