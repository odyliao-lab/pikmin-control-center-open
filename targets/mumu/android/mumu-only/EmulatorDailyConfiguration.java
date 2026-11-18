package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Immutable private inputs, not acceptance or authorization to run. There is
 * one slot; rebind/replacement needs an explicit separately audited procedure.
 * Never recover by overwriting, deleting or adopting an older configuration. */
final class EmulatorDailyConfiguration {
    static final int MAX_BYTES=262144;
    final EmulatorDailyRunner.Binding binding;
    final HarvestRoute route;
    final java.util.List<HarvestRoute> routes;
    final String city;
    final long created;
    EmulatorDailyConfiguration(EmulatorDailyRunner.Binding b,HarvestRoute r,long created){
        this(b,java.util.Collections.singletonList(r),"",created);
    }
    /** A v2 city has one independently selected route for each of the thirteen
     * existing daily slots. The primary binding remains the immutable runner
     * identity; each owned route receives its exact segment binding. */
    EmulatorDailyConfiguration(EmulatorDailyRunner.Binding b,java.util.List<HarvestRoute> inputs,String city,long created){
        if(inputs==null||city==null||!(city.isEmpty()?inputs.size()==1:
            java.util.Arrays.asList("taipei","osaka","seoul").contains(city)&&inputs.size()==13))bad();
        java.util.List<HarvestRoute> copy=new java.util.ArrayList<>(inputs);
        java.util.Set<String> distinct=new java.util.HashSet<>();
        for(HarvestRoute next:copy){
            if(next==null||next.estimatedSeconds()>20*60&&!city.isEmpty())bad();
            if(!city.isEmpty()&&!distinct.add(next.waypoints()))bad();
        }
        HarvestRoute r=copy.get(0);
        if(b==null||r==null||created<=0||b.kmh!=HarvestRoute.SPEED_KMH)bad();
        java.util.List<FlowerFarmService.Point> points=new java.util.ArrayList<>();
        for(HarvestRoute.Point p:r.points)points.add(new FlowerFarmService.Point(p.lat,p.lng));
        if(!b.routeSha.equals(EmulatorDailyRouteWork.routeSha(points)))bad();
        binding=b;route=r;this.created=created;this.city=city;
        routes=java.util.Collections.unmodifiableList(copy);
    }
    String encode(){
        if(!city.isEmpty()){
            StringBuilder body=new StringBuilder("v2\t"+EmulatorDailyRunner.base64(binding.encode())+"\t"+created+"\t"+city+"\t13\tend\n");
            for(int i=0;i<13;i++)body.append("route\t").append(i).append('\t')
                .append(EmulatorDailyRunner.base64(routes.get(i).waypoints().replace(';','\n'))).append("\tend\n");
            String wire=body+"sha256\t"+EmulatorDailyRunner.digest(body.toString())+"\n";
            if(wire.length()>MAX_BYTES)bad();return wire;
        }
        String body="v1\t"+EmulatorDailyRunner.base64(binding.encode())+"\t"+EmulatorDailyRunner.base64(route.waypoints().replace(';','\n'))+"\t"+created+"\tend\n";
        String wire=body+"sha256\t"+EmulatorDailyRunner.digest(body)+"\n";if(wire.length()>MAX_BYTES)bad();return wire;
    }
    static EmulatorDailyConfiguration decode(String wire,long now){
        if(wire==null||wire.length()>MAX_BYTES||wire.indexOf('\r')>=0)bad();
        String[] rows=wire.split("\n",-1);
        if(wire.startsWith("v2\t"))return decodeCity(wire,rows,now);
        if(rows.length!=3||!rows[2].isEmpty()||!rows[1].equals("sha256\t"+EmulatorDailyRunner.digest(rows[0]+"\n")))bad();
        String[] f=rows[0].split("\t",-1);if(f.length!=5||!f[0].equals("v1")||!f[4].equals("end"))bad();
        EmulatorDailyConfiguration c=new EmulatorDailyConfiguration(EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(f[1])),
            HarvestRoute.coordinates(EmulatorDailyRunner.unbase64(f[2])),Long.parseLong(f[3]));
        if(now<c.created||!wire.equals(c.encode()))bad();return c;
    }
    private static EmulatorDailyConfiguration decodeCity(String wire,String[] rows,long now){
        if(rows.length!=16||!rows[15].isEmpty())bad();
        StringBuilder body=new StringBuilder();for(int i=0;i<14;i++)body.append(rows[i]).append('\n');
        if(!rows[14].equals("sha256\t"+EmulatorDailyRunner.digest(body.toString())))bad();
        String[] h=rows[0].split("\t",-1);
        if(h.length!=6||!h[0].equals("v2")||!h[4].equals("13")||!h[5].equals("end"))bad();
        java.util.List<HarvestRoute> routes=new java.util.ArrayList<>();
        for(int i=0;i<13;i++){
            String[] f=rows[i+1].split("\t",-1);
            if(f.length!=4||!f[0].equals("route")||!f[1].equals(Integer.toString(i))||!f[3].equals("end"))bad();
            routes.add(HarvestRoute.coordinates(EmulatorDailyRunner.unbase64(f[2])));
        }
        EmulatorDailyConfiguration c=new EmulatorDailyConfiguration(EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(h[1])),routes,h[3],Long.parseLong(h[2]));
        if(now<c.created||!wire.equals(c.encode()))bad();return c;
    }
    HarvestRoute routeFor(String key){
        EmulatorAndroidDailyPort.requireKey(binding,key,false);
        int slot=Integer.parseInt(key.substring(key.lastIndexOf('-')+1));
        return routes.get(EmulatorDailyPlan.overnightKey(key)?slot%routes.size():routes.size()==1?0:slot);
    }
    EmulatorDailyRunner.Binding routeBinding(String key){
        java.util.List<FlowerFarmService.Point> points=new java.util.ArrayList<>();
        for(HarvestRoute.Point p:routeFor(key).points)points.add(new FlowerFarmService.Point(p.lat,p.lng));
        return new EmulatorDailyRunner.Binding(binding.device,binding.boot,binding.pid,binding.account,binding.sessionEpoch,
            binding.coreSha,binding.apkSha,EmulatorDailyRouteWork.routeSha(points),binding.kmh);
    }
    static EmulatorDailyConfiguration read(Path root,long now)throws Exception{
        root=directory(root,true);Path leaf=root.resolve("config.tsv");String first=bounded(leaf);
        EmulatorDailyConfiguration config=decode(first,now);
        directory(root,true);if(!first.equals(bounded(leaf)))bad();return config;
    }
    interface Mode{void apply(Path path,boolean directory)throws Exception;}
    static void persist(Path root,EmulatorDailyConfiguration config,EmulatorAndroidDailyStore.DirectorySync sync,Mode mode)throws Exception{
        if(config==null||sync==null||mode==null)bad();root=directory(root,false);
        // Neither failed visible leaf nor a different binding may replace it.
        Path leaf=root.resolve("config.tsv");if(Files.exists(leaf,LinkOption.NOFOLLOW_LINKS))bad();
        Files.createDirectories(root);directory(root,true);mode.apply(root,true);sync.sync(root.getParent());sync.sync(root);
        byte[] bytes=config.encode().getBytes(StandardCharsets.UTF_8);
        try(FileChannel out=FileChannel.open(leaf,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            mode.apply(leaf,false);ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())out.write(data);out.force(true);
        }
        sync.sync(root);if(!config.encode().equals(read(root,config.created).encode()))bad();
    }
    private static Path directory(Path root,boolean exists){
        if(root==null)bad();root=root.toAbsolutePath().normalize();
        if(root.getFileName()==null||!root.getFileName().toString().equals("daily-configuration")||root.getParent()==null||root.getParent().getFileName()==null||
           !root.getParent().getFileName().toString().equals("files"))bad();
        for(Path p=root;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)))bad();
        if(exists&&!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))bad();return root;
    }
    private static String bounded(Path leaf)throws Exception{
        if(Files.isSymbolicLink(leaf)||!Files.isRegularFile(leaf,LinkOption.NOFOLLOW_LINKS))bad();
        try(FileChannel in=FileChannel.open(leaf,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(in.size()<1||in.size()>MAX_BYTES)bad();ByteBuffer buf=ByteBuffer.allocate(MAX_BYTES+1);
            while(buf.hasRemaining()&&in.read(buf)>0){};if(buf.position()>MAX_BYTES)bad();byte[] bytes=java.util.Arrays.copyOf(buf.array(),buf.position());
            String raw=new String(bytes,StandardCharsets.UTF_8);if(!java.util.Arrays.equals(bytes,raw.getBytes(StandardCharsets.UTF_8)))bad();return raw;
        }
    }
    private static void bad(){throw new IllegalStateException("Exact immutable private daily configuration required; no replacement or adoption");}
}
