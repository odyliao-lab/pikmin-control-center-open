package dev.ody.pikmincontrol;

/** Contract inspected from the exact installed JoyStick 5.3.3 APK. No coordinates. */
final class EmulatorSavedJoystickProtocol {
    static final String PACKAGE="com.thejhijuqoqy.bdujvrwdcajvzin";
    static final String SERVICE=PACKAGE+"/com.theappninjas.fakegpsjoystick.service.OverlayService";
    static final String APK_SHA="30133899429cbc1e670f4052181b3389132f7e43c091be6c401593238b50fc02";
    enum Operation { STATUS, SPEED, ROUTE, ROUTE_PAUSE, ROUTE_RESUME, ROUTE_SWITCH }
    static String command(Operation operation,EmulatorSavedRoutePlan plan){
        if(operation==null||plan==null)throw new IllegalArgumentException("Explicit saved plan required");
        String command="am start-foreground-service --user 0 -n "+SERVICE+" -a theappninjas.gpsjoystick."+(operation==Operation.ROUTE_SWITCH?"ROUTE":operation.name());
        if(operation==Operation.SPEED)command+=" --ef speed 19.0"; // km/h, not metres/second
        if(operation==Operation.ROUTE||operation==Operation.ROUTE_SWITCH)command+=" --es name "+plan.savedName();
        return command;
    }
    static final class Status {
        final boolean running,active,paused;final int total,remaining;final double speed;final String mode;
        Status(boolean running,boolean active,boolean paused,int total,int remaining,double speed,String mode){
            if(!Double.isFinite(speed)||speed<=0||total<0||remaining<0||remaining>total||
               !("stop".equals(mode)||"loop".equals(mode)||"reverse".equals(mode))||
               (active&&(!running||paused||total==0))||(paused&&(!running||active||total==0)))
                throw new IllegalArgumentException("Unknown JoyStick status");
            this.running=running;this.active=active;this.paused=paused;this.total=total;this.remaining=remaining;this.speed=speed;this.mode=mode;
        }
        boolean configuredLoop(){return running&&"loop".equals(mode);}
        boolean runningAt19(){return configuredLoop()&&active&&Double.compare(speed,19.0)==0;}
        boolean confirmedPaused(){return running&&!active&&paused;}
    }
    static boolean allows(Operation operation,Status status){
        if(operation==null||status==null||operation==Operation.STATUS)return false;
        // Stop our active route even if someone changed its loop/speed setting.
        // Settings mismatch never prevents the explicit/rest/batch handoff pause.
        if(operation==Operation.ROUTE_PAUSE)return status.running&&status.active;
        if(!status.configuredLoop())return false;
        if(operation==Operation.ROUTE_SWITCH)return status.confirmedPaused()&&Double.compare(status.speed,19)==0;
        if(operation==Operation.ROUTE_RESUME)return status.confirmedPaused()&&Double.compare(status.speed,19.0)==0;
        if(status.active||status.paused)return false; // never replace an existing route
        return operation==Operation.SPEED||operation==Operation.ROUTE&&Double.compare(status.speed,19.0)==0;
    }
    private EmulatorSavedJoystickProtocol(){}
}
