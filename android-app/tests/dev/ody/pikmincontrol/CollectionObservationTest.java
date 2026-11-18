package dev.ody.pikmincontrol;

public final class CollectionObservationTest {
    private static String frame(String... fields) {
        StringBuilder out=new StringBuilder();for(int i=0;i<fields.length;i++)out.append("\n__PCC_READ_").append(i).append("__\n").append(fields[i]);
        return out.append("\n__PCC_READ_END__\n").toString();
    }
    private static BoundedCommand.Result read(String... fields) {return new BoundedCommand.Result(frame(fields),true,false,false);}
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        CorePair p=new CorePair("schema=2\nversion=1.5.31\ncode=99\napp=131\nsize=100\nsha256="+new String(new char[64]).replace('\0','a')+"\n");
        String core="v1\t100000\t123\t1.5.31\t99\t157.0\n",gps="v1\t100000\t123\t1.5.31\t99\t1\n";
        CollectionObservation good=CollectionObservation.parse(read("123\n",core,gps,"all\n","auto\n","auto\n","123\n"),p,100001);
        check(good.pid.equals("123")&&good.controls[0].equals("all\n"));
        check(CollectionObservation.parse(read("123",core,gps,"all","auto","auto","124"),p,100001).pid.isEmpty());
        check(CollectionObservation.parse(read("123",core,gps,"all","auto","auto","123"),p,112001).pid.isEmpty());
        check(CollectionObservation.parse(read("123",core,gps.replace("1.5.31","1.5.30"),"all","auto","auto","123"),p,100001).pid.isEmpty());
        check(CollectionObservation.parse(read("",core,gps,"all","auto","auto",""),p,100001).pid.isEmpty());
        for(BoundedCommand.Result broken:new BoundedCommand.Result[]{
            new BoundedCommand.Result("",false,true,false),new BoundedCommand.Result(frame("123",core,gps,"all","auto","auto","123"),true,false,true),
            read("123",core,gps,"__PCC_CONTROL_ERROR__","auto","auto","123"),read("123",core)}) {
            try{CollectionObservation.parse(broken,p,100001);throw new AssertionError();}catch(IllegalStateException expected){}
        }
        check(!CollectionObservation.command().contains(" > "));
        check(ProbeSnapshot.sections(frame("123",core,gps,"","auto","auto","123"),7).length==7);
        System.out.println("PASS CollectionObservation: fresh same-PID pair, expiry, mismatch, read error and framing");
    }
}
