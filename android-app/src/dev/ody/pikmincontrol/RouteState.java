package dev.ody.pikmincontrol;
final class RouteState {
    final String[] fields; final long at; final boolean stopped, ready;
    RouteState(String raw,String pid,long now,String token,String phase) {
        fields=raw.trim().split("\\t",-1);
        try {
            if(fields.length!=8)throw new IllegalArgumentException();
            at=Long.parseLong(fields[1]);
            if(!fields[0].equals("v1")||!pid.matches("[1-9][0-9]*")||!pid.equals(fields[2])||!fields[7].equals("300000")
                ||at<=0||at>now||now-at>2000||!fields[5].matches("[01]")||!fields[6].matches("[01]")
                ||!fields[4].matches("off|prepare|walk|hold")
                ||(!fields[3].equals("-")&&!fields[3].matches("[0-9a-f-]{36}"))
                ||(token!=null&&(!token.equals(fields[3])||!phase.equals(fields[4]))))throw new IllegalArgumentException();
            stopped=fields[5].equals("1");ready=fields[6].equals("1");
        }catch(RuntimeException e){throw new IllegalArgumentException("沿路核心回報無效或過期",e);}
    }
    static final class StopGate {
        long first, previous;
        boolean observe(RouteState state) {
            if(!state.stopped){first=previous=0;return false;}
            if(first==0||state.at<previous||state.at-previous>2000)first=state.at;
            previous=state.at;return state.at-first>=3000;
        }
    }
}
