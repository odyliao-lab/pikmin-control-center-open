package dev.ody.pikmincontrol;
import java.util.*;
final class AutoPetalConfig {
    String revision=UUID.randomUUID().toString();boolean enabled;int reserve=50;
    final List<String> order=new ArrayList<>();
    static boolean id(String s){return s!=null&&s.matches("[A-Za-z0-9._:-]{1,80}");}
    String encode(){
        if(!revision.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")||reserve<0||reserve>1000000||order.size()>64)throw new IllegalArgumentException("請核對花瓣設定");
        StringBuilder b=new StringBuilder("v1\t"+revision+"\t"+(enabled?"on":"off")+"\t"+reserve+"\n");Set<String> ids=new HashSet<>();
        for(String id:order){if(!id(id)||!ids.add(id))throw new IllegalArgumentException("花種清單重複或不完整");b.append("petal\t").append(id).append('\n');}
        return b.append("end\n").toString();
    }
    static AutoPetalConfig parse(String raw){
        if(raw==null||raw.length()>8192)throw new IllegalArgumentException("設定讀取未完成");
        String[] lines=raw.split("\n",-1);String[] h=lines[0].split("\t",-1);if(h.length!=4||!h[0].equals("v1")||!h[2].matches("on|off"))throw new IllegalArgumentException("設定格式不符");
        AutoPetalConfig c=new AutoPetalConfig();c.revision=h[1];c.enabled=h[2].equals("on");c.reserve=Integer.parseInt(h[3]);
        for(int i=1;i<lines.length-2;i++){String[] row=lines[i].split("\t",-1);if(row.length!=2||!row[0].equals("petal"))throw new IllegalArgumentException("花種清單不完整");c.order.add(row[1]);}
        if(!c.encode().equals(raw))throw new IllegalArgumentException("設定未完整保存");return c;
    }
}
