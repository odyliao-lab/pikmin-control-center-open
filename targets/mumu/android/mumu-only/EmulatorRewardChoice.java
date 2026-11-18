package dev.ody.pikmincontrol;

/** Exact three-card156 UI only.
 * Checks mean catalogue-complete, not a claim receipt. */
final class EmulatorRewardChoice {
    enum Completion { INCOMPLETE, COMPLETE, UNKNOWN }
    enum Choice { FIRST, SECOND, RIGHTMOST, REVIEW }
    static Choice choose(boolean verifiedThreeOptionDialog,Completion first,Completion second){
        if(!verifiedThreeOptionDialog||first==null||second==null)return Choice.REVIEW;
        if(first==Completion.INCOMPLETE)return Choice.FIRST;
        if(second==Completion.INCOMPLETE)return Choice.SECOND;
        if(first==Completion.COMPLETE&&second==Completion.COMPLETE)return Choice.RIGHTMOST;
        return Choice.REVIEW;
    }
    private static final int[] X={262,550,840};
    static EmulatorDailyUiMatcher.Scene choose(EmulatorDailyUiMatcher.Pixels p,boolean confirmation){
        int selected=-1,top=confirmation?1040:1015;
        if(confirmation){
            for(int i=0;i<3;i++)if(white(p,X[i]-40,1023,80,8)>=512){
                if(selected!=-1)return EmulatorDailyUiMatcher.Scene.UNKNOWN;selected=i;
            }
            if(selected<0)return EmulatorDailyUiMatcher.Scene.UNKNOWN;
        }
        // All three cards must exist before deciding from their icon/check regions.
        for(int i=0;i<3;i++){
            if(i==selected){
                if(white(p,X[i]-112,1090,7,70)<340)return EmulatorDailyUiMatcher.Scene.UNKNOWN;
            }else{
                int line=0;
                for(int x=X[i]-40;x<X[i]+40;x++)if(grey(p.rgb(x,top)))line++;
                if(line<70)return EmulatorDailyUiMatcher.Scene.UNKNOWN;
            }
        }
        int candidate=-1;
        for(int i=0;i<3;i++){
            int gold=0,visible=0;
            for(int y=top+85;y<top+145;y++)for(int x=X[i]-55;x<X[i]+35;x++){
                int rgb=p.rgb(x,y);if(gold(rgb))gold++;
                if(Math.max((rgb>>>16)&255,Math.max((rgb>>>8)&255,rgb&255))>=110)visible++;
            }
            if(visible<1000)return EmulatorDailyUiMatcher.Scene.UNKNOWN;
            if(gold>=1890){
                // A visible JoyStick pad can cover the right card's check badge.
                // Absence beneath an overlay must never mean catalogue-incomplete.
                if(i==2&&white(p,908,1258,20,20)>300)return EmulatorDailyUiMatcher.Scene.UNKNOWN;
                int badge=badge(p,X[i],top);
                if(badge<0)return EmulatorDailyUiMatcher.Scene.UNKNOWN;
                if(badge==1)continue;
            }else if(gold>270)return EmulatorDailyUiMatcher.Scene.UNKNOWN;
            candidate=i;break;
        }
        if(candidate<0)return EmulatorDailyUiMatcher.Scene.UNKNOWN;
        if(confirmation&&selected==candidate)return EmulatorDailyUiMatcher.Scene.REWARD_CONFIRM;
        return candidate==0?EmulatorDailyUiMatcher.Scene.REWARD_LEFT:
                candidate==1?EmulatorDailyUiMatcher.Scene.REWARD_MIDDLE:EmulatorDailyUiMatcher.Scene.REWARD_RIGHT;
    }
    private static int badge(EmulatorDailyUiMatcher.Pixels p,int cx,int top){
        int left=cx+95,right=cx+20,up=top+205,down=top+105,green=0;
        for(int y=top+105;y<top+205;y++)for(int x=cx+20;x<cx+95;x++)if(green(p.rgb(x,y))){
            green++;left=Math.min(left,x);right=Math.max(right,x);up=Math.min(up,y);down=Math.max(down,y);
        }
        if(green<30)return 0;
        int w=right-left+1,h=down-up+1;
        if(w<48||w>76||h<48||h>76||Math.abs(w-h)>5||green*100<55*w*h)return -1;
        // Both diagonal strokes must be present inside the green circular badge.
        if(!stroke(p,left,up,w,h,0.37,0.55)||!stroke(p,left,up,w,h,0.47,0.62)||
                !stroke(p,left,up,w,h,0.65,0.40))return -1;
        return 1;
    }
    private static boolean stroke(EmulatorDailyUiMatcher.Pixels p,int x,int y,int w,int h,double u,double v){
        return white(p,x+(int)Math.round((w-1)*u)-3,y+(int)Math.round((h-1)*v)-3,7,7)>=12;
    }
    private static int white(EmulatorDailyUiMatcher.Pixels p,int x,int y,int w,int h){
        int n=0;for(int yy=y;yy<y+h;yy++)for(int xx=x;xx<x+w;xx++)if(EmulatorDailyUiMatcher.mask(p.rgb(xx,yy),0))n++;return n;
    }
    private static boolean grey(int rgb){int r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;return r>=70&&r<=165&&Math.abs(r-g)<12&&Math.abs(g-b)<12;}
    private static boolean green(int rgb){int r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;return g>=110&&g>r+45&&b>60&&b<200;}
    private static boolean gold(int rgb){int r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;return r>=100&&g>=65&&b<=140&&r*100>g*113&&g*100>b*135;}
}
