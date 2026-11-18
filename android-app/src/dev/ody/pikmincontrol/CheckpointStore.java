package dev.ody.pikmincontrol;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;

final class CheckpointStore {
    private static File path(Context c,String owner){if(!owner.equals("batch")&&!owner.equals("flower-farm"))throw new IllegalArgumentException();return new File(c.getFilesDir(),"checkpoint-"+owner+".properties");}
    static synchronized boolean present(Context c,String owner){File f=path(c,owner);return f.exists()||new File(f+".bak").exists();}
    static synchronized void archiveUncertain(Context c,WorkCheckpoint cp)throws Exception {
        if(!GpsWorkLock.BATCH.equals(cp.owner)||!cp.uncertain)throw new IllegalArgumentException("非未確認批次");
        File dir=new File(c.getFilesDir(),"checkpoint-archives");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("無法建立未確認紀錄封存");
        String name="batch-"+cp.id+"-"+UUID.randomUUID()+".properties";
        AtomicFile archive=new AtomicFile(new File(dir,name));
        FileOutputStream out=null;
        try {out=archive.startWrite();cp.properties().store(out,"unconfirmed; archived with explicit user review; NEVER replay");archive.finishWrite(out);}
        catch(Exception error){if(out!=null)archive.failWrite(out);throw error;}
        Properties p=new Properties();try(InputStream in=archive.openRead()){p.load(in);}
        WorkCheckpoint saved=WorkCheckpoint.restore(p);
        if(!saved.uncertain||!saved.properties().getProperty("checksum").equals(cp.properties().getProperty("checksum")))
            throw new IOException("封存回讀未確認；維持暫停");
        if(!c.getSharedPreferences("batch_reviewed_archives",0).edit().putString(cp.id,name).commit())
            throw new IOException("人工核對封存未保存；維持暫停");
    }
    static synchronized boolean archivedReviewed(Context c,WorkCheckpoint cp)throws Exception {
        String name=c.getSharedPreferences("batch_reviewed_archives",0).getString(cp.id,"");
        if(!name.matches("batch-"+cp.id+"-[a-f0-9-]{36}\\.properties"))return false;
        File f=new File(new File(c.getFilesDir(),"checkpoint-archives"),name);
        if(!f.isFile()||f.length()>1_048_576)return false;
        Properties p=new Properties();try(InputStream in=new AtomicFile(f).openRead()){p.load(in);}
        WorkCheckpoint saved=WorkCheckpoint.restore(p);
        return saved.uncertain&&saved.properties().getProperty("checksum").equals(cp.properties().getProperty("checksum"));
    }
    static synchronized void save(Context c,WorkCheckpoint checkpoint) throws Exception {
        AtomicFile file=new AtomicFile(path(c,checkpoint.owner));FileOutputStream out=null;
        try {out=file.startWrite();checkpoint.properties().store(out,"private work checkpoint");file.finishWrite(out);}
        catch(Exception error){if(out!=null)file.failWrite(out);throw error;}
    }
    static synchronized WorkCheckpoint read(Context c,String owner) throws Exception {
        File path=path(c,owner);if(!path.exists()&&!new File(path+".bak").exists())throw new IllegalStateException("尚無可接續紀錄");
        try(InputStream in=new AtomicFile(path).openRead()) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1){if(bytes.size()+n>1_048_576)throw new IOException("紀錄過大");bytes.write(buffer,0,n);}
            Properties p=new Properties();p.load(new ByteArrayInputStream(bytes.toByteArray()));WorkCheckpoint cp=WorkCheckpoint.restore(p);
            if(!cp.owner.equals(owner))throw new IOException("工作種類不符");return cp;
        }
    }
    static WorkCheckpoint require(Context c,String owner,String id) throws Exception {
        WorkCheckpoint cp=read(c,owner);if(!cp.id.equals(id))throw new IllegalStateException("紀錄已更新，請重新確認");
        String identity=SessionIdentity.read(true);
        if(identity.isEmpty())throw new IllegalStateException("帳號或原生閒置狀態待確認，禁止接續");
        String reason=cp.reason(identity,System.currentTimeMillis());if(!reason.isEmpty())throw new IllegalStateException(reason);return cp;
    }
    static WorkCheckpoint create(String owner,int rounds,int dwell,List<String> entries) throws Exception {
        String account="";try{account=SessionIdentity.read();}catch(Exception unknown){}
        return new WorkCheckpoint(UUID.randomUUID().toString(),owner,account,System.currentTimeMillis(),rounds,dwell,entries);
    }
    static void guard(WorkCheckpoint cp) throws Exception {
        if(cp!=null&&!cp.account.isEmpty()&&!cp.account.equals(SessionIdentity.read()))throw new IllegalStateException("帳號識別失效或已變更，停止工作");
    }
    static List<FlowerFarmService.Point> points(WorkCheckpoint cp){List<FlowerFarmService.Point> out=new ArrayList<>();
        for(String row:cp.entries){String[] f=row.split("\t");out.add(new FlowerFarmService.Point(Double.parseDouble(f[0]),Double.parseDouble(f[1])));}return out;}
    static List<DispatchProbeGateway.Candidate> candidates(WorkCheckpoint cp){List<DispatchProbeGateway.Candidate> out=new ArrayList<>();
        for(int i=0;i<cp.entries.size();i++)if(!cp.done.get(i)){String[] f=cp.entries.get(i).split("\t");out.add(new DispatchProbeGateway.Candidate(f[0],f[1],Long.parseLong(f[2]),Double.parseDouble(f[3]),Double.parseDouble(f[4]),Double.parseDouble(f[5])));}return out;}
}
