package dev.ody.pikmincontrol;
import java.util.*;
final class AutoPetalState {
    static String color(String s){return s.equals("1")?"白":s.equals("2")?"紅":s.equals("3")?"藍":s.equals("4")?"黃":"未知";}
    String phase="waiting-native";boolean valid,complete;long reportedAt;final Map<String,String[]> rows=new LinkedHashMap<>();
    static AutoPetalState parse(String raw,String before,String after,String revision,long now){
        return decode(raw,before,after,revision,now,true);
    }
    /** UI identity catalogue only. Old counts/phase/active state are NEVER returned. */
    static Map<String,String[]> catalogueRows(String raw,String before,String after,String revision,long now){
        AutoPetalState decoded=decode(raw,before,after,revision,now,false);
        Map<String,String[]> result=new LinkedHashMap<>();
        if(decoded.valid&&decoded.complete)for(String[] row:decoded.rows.values()){
            String[] identity=row.clone();identity[4]="-1";result.put(identity[1],identity);
        }
        return result;
    }
    /** Read-only, labelled historical quantities. Never a live status or transaction admission. */
    static Map<String,String[]> displayRows(String raw,String before,String after,String revision,long now){
        AutoPetalState decoded=decode(raw,before,after,revision,now,false);
        Map<String,String[]> result=new LinkedHashMap<>();
        if(decoded.valid&&decoded.complete&&now-decoded.reportedAt<=300000)
            for(String[] row:decoded.rows.values())result.put(row[1],row.clone());
        return result;
    }
    private static AutoPetalState decode(String raw,String before,String after,String revision,long now,boolean requireFresh){
        AutoPetalState s=new AutoPetalState();
        try{
            if(raw==null||raw.length()>131072||before==null||!before.matches("[1-9][0-9]*")||!before.equals(after))return s;
            String[] lines=raw.split("\n",-1),h=lines[0].split("\t",-1);
            if(h.length!=8||!h[0].equals("v1")||!h[2].equals(before)||!h[3].equals(revision)||!h[4].matches("[a-z-]{1,64}")||!h[5].matches("[01]")||lines.length<3||!lines[lines.length-2].equals("end")||!lines[lines.length-1].isEmpty())return s;
            long at=Long.parseLong(h[1]);if(at<=0||at>now||(requireFresh&&now-at>5000)||Long.parseLong(h[7])<0)return s;
            if(!h[6].equals("-")&&!AutoPetalConfig.id(h[6]))return s;
            for(int i=1;i<lines.length-2;i++){
                String[] r=lines[i].split("\t",-1);if(r.length!=5||!r[0].equals("petal")||!AutoPetalConfig.id(r[1])||!AutoPetalConfig.id(r[2])||!r[3].matches("[1-4]")||!r[4].matches("0|[1-9][0-9]{0,6}")||Integer.parseInt(r[4])>1000000||s.rows.put(r[1],r)!=null||s.rows.size()>512)throw new IllegalArgumentException();
            }
            s.complete=h[5].equals("1");if(!s.complete&&!s.rows.isEmpty())throw new IllegalArgumentException();s.reportedAt=at;s.phase=h[4];s.valid=true;
        }catch(Exception e){s.rows.clear();s.valid=false;s.complete=false;}
        return s;
    }
}
