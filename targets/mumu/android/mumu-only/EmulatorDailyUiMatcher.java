package dev.ody.pikmincontrol;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Exact156/1080x1920 UI masks only. No OCR, game API, input or generic buttons. */
final class EmulatorDailyUiMatcher {
    enum Scene {
        UNKNOWN(0,0), MAP(0,0), LEVEL_FREE(540,1627), FLOWER_FREE(540,1627),
        PROFILE(104,1815), HOME(540,1818), SPEED(410,1082), NETWORK(479,1148),
        REWARD_CHOICES(0,0), REWARD_LEFT(262,1060), REWARD_MIDDLE(550,1060),
        REWARD_RIGHT(840,1060), REWARD_CONFIRM(540,1627);
        final int x,y;
        Scene(int x,int y){this.x=x;this.y=y;}
        boolean clickable(){return x>0;}
        boolean navigation(){return this==HOME||this==PROFILE;}
        boolean reward(){return this==REWARD_LEFT||this==REWARD_MIDDLE||this==REWARD_RIGHT||this==REWARD_CONFIRM;}
    }
    interface Pixels { int rgb(int x,int y); }
    static final class Raw implements Pixels {
        final byte[] bytes;
        Raw(byte[] bytes){
            if(bytes==null||bytes.length!=8294416||le(bytes,0)!=1080||le(bytes,4)!=1920||le(bytes,8)!=1||le(bytes,12)!=1)
                throw new IllegalArgumentException("Exact Android35 RGBA8888 sRGB frame required");
            this.bytes=bytes;
        }
        public int rgb(int x,int y){int i=16+(y*1080+x)*4;return ((bytes[i]&255)<<16)|((bytes[i+1]&255)<<8)|(bytes[i+2]&255);}
        private static int le(byte[] b,int n){return (b[n]&255)|((b[n+1]&255)<<8)|((b[n+2]&255)<<16)|(b[n+3]<<24);}
    }
    static final class Patch {
        final Scene scene;final int x,y,w,h,kind;final byte[] bits;
        Patch(String[] f)throws Exception{
            if(f.length!=8)throw new IllegalArgumentException("UI mask fields");
            scene=Scene.valueOf(f[0]);x=Integer.parseInt(f[1]);y=Integer.parseInt(f[2]);w=Integer.parseInt(f[3]);h=Integer.parseInt(f[4]);kind=Integer.parseInt(f[5]);
            int minimum=Integer.parseInt(f[6]);
            if(scene==Scene.UNKNOWN||x<0||y<0||w<1||h<1||x+w>1080||y+h>1920||w*h>160000||kind<0||kind>5||minimum<100)throw new IllegalArgumentException("UI mask bounds");
            try(InputStream in=new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(f[7])))){
                bits=read(in,(w*h+7)/8);if(bits.length!=(w*h+7)/8)throw new IllegalArgumentException("UI mask size");
            }
            int count=0;for(int i=0;i<w*h;i++)if(on(i))count++;
            if(count<minimum)throw new IllegalArgumentException("UI mask foreground");
        }
        boolean on(int i){return (bits[i/8]&(1<<(i%8)))!=0;}
        boolean matches(Pixels p){
            if(kind==5){
                // The separator is a long thin line; rasterization may vary by one row.
                // Require the same occupied columns inside the fixed six-row strip.
                int diff=0;for(int i=0;i<w;i++){boolean expected=false,actual=false;for(int j=0;j<h;j++){expected|=on(j*w+i);actual|=mask(p.rgb(x+i,y+j),kind);}if(expected!=actual)diff++;}
                return diff*100L<=2L*w;
            }
            int diff=0,k=0;for(int j=y;j<y+h;j++)for(int i=x;i<x+w;i++)if(on(k++)!=mask(p.rgb(i,j),kind))diff++;return diff*1000L<=8L*w*h;
        }
    }
    private final Map<Scene,List<Patch>> rules=new EnumMap<>(Scene.class);
    EmulatorDailyUiMatcher(InputStream asset)throws Exception{
        String wire=new String(read(asset,65536),java.nio.charset.StandardCharsets.US_ASCII);
        String[] rows=wire.split("\n",-1);
        if(rows.length<3||!rows[0].equals("v1\t157.0\t1080\t1920")||!rows[rows.length-1].isEmpty()||wire.indexOf('\r')>=0)throw new IllegalArgumentException("UI mask profile");
        for(int i=1;i<rows.length-1;i++){Patch p=new Patch(rows[i].split("\t",-1));rules.computeIfAbsent(p.scene,k->new ArrayList<>()).add(p);}
        if(rules.size()!=9||!rules.containsKey(Scene.REWARD_CHOICES)||!rules.containsKey(Scene.REWARD_CONFIRM))throw new IllegalArgumentException("Seven legacy and two reward profiles required");
        for(List<Patch> patches:rules.values())if(patches.size()<2||patches.size()>4)throw new IllegalArgumentException("Multiple UI anchors required");
    }
    Scene classify(Pixels p){
        if(matches(Scene.REWARD_CHOICES,p))return EmulatorRewardChoice.choose(p,matches(Scene.REWARD_CONFIRM,p));
        Scene found=Scene.UNKNOWN;
        for(Map.Entry<Scene,List<Patch>> e:rules.entrySet()){
            if(e.getKey()==Scene.REWARD_CHOICES||e.getKey()==Scene.REWARD_CONFIRM)continue;
            boolean hit=true;for(Patch patch:e.getValue())if(!patch.matches(p)){hit=false;break;}
            if(hit){if(found!=Scene.UNKNOWN)return Scene.UNKNOWN;found=e.getKey();}
        }
        return found;
    }
    private boolean matches(Scene scene,Pixels p){for(Patch patch:rules.get(scene))if(!patch.matches(p))return false;return true;}
    static boolean mask(int rgb,int kind){
        int r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;
        return kind==0?r>=235&&g>=235&&b>=235:kind==1?r<100&&g>=105&&b>=65&&g>r+45:kind==2?r<=35&&g<=35&&b<=35:kind==3?r>=70&&r<=165&&Math.abs(r-g)<12&&Math.abs(g-b)<12:kind==4?r>=35&&r<=90&&Math.abs(r-g)<12&&Math.abs(g-b)<12:r>=150&&g>=100&&b<=120&&r*100>g*115&&g*100>b*125;
    }
    static byte[] read(InputStream in,int max)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
        while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("Complete bounded UI data required");out.write(buf,0,n);}return out.toByteArray();
    }
}
