package dev.ody.pikmincontrol;

/** Read-only native processed location. No system-GPS fallback or dispatch authority. */
final class FarmLocation {
    final String pid, state, readiness;
    final long at, rawAt, epoch;
    final double rawLat, rawLng, lat, lng;
    final int samples;
    final boolean valid;
    final JoystickReadiness provider;

    FarmLocation(String raw, String before, String after, long wall, long elapsed, JoystickReadiness provider) {
        this.provider = provider;
        String[] f = raw.trim().split("\\t", -1);
        pid = f.length == 12 ? f[2] : "";
        state = f.length == 12 ? f[3] : "";
        long a=-1, r=-1, e=-1; int s=-1;
        double rl=Double.NaN, rn=Double.NaN, l=Double.NaN, n=Double.NaN;
        try {
            if (f.length == 12) {
                a=Long.parseLong(f[1]); e=Long.parseLong(f[4]); s=Integer.parseInt(f[5]);
                rl=Double.parseDouble(f[6]); rn=Double.parseDouble(f[7]);
                l=Double.parseDouble(f[8]); n=Double.parseDouble(f[9]); r=Long.parseLong(f[10]);
            }
        } catch (NumberFormatException ignored) { }
        at=a; rawAt=r; epoch=e; samples=s; rawLat=rl; rawLng=rn; lat=l; lng=n;
        valid = before.trim().matches("[1-9][0-9]*") && before.trim().equals(after.trim())
                && new NearbyGuard(raw, before, wall).supported && e>=0 && s>=0 && s<=3
                && point(rl,rn) && point(l,n) && fresh(wall,elapsed);
        readiness = valid ? "定位回報已更新"
                : !before.trim().matches("[1-9][0-9]*") ? "等待 Pikmin 啟動"
                : !before.trim().equals(after.trim()) || !before.trim().equals(pid) ? "等待目前遊戲程序回報"
                : f.length != 12 || !("v1".equals(f[0]) || "v2".equals(f[0])) || !"300000".equals(f[11]) ? "原生定位協定不符，需 native 1.4.22 或更新版"
                : at <= 0 || wall < at || wall-at > 2000 ? "等待遊戲定位心跳更新"
                : provider == null || !provider.own ? "等待 GPS JoyStick 接管定位"
                : !provider.fresh(elapsed) ? "等待 GPS JoyStick 新鮮定位"
                : rawAt <= 0 || elapsed < rawAt || elapsed-rawAt > 5000 ? "等待遊戲收到新定位"
                : "等待有效原生定位";
    }
    boolean fresh(long wall, long elapsed) {
        return at>0 && wall>=at && wall-at<=2000 && rawAt>0 && elapsed>=rawAt && elapsed-rawAt<=5000
                && provider!=null && provider.fresh(elapsed) && point(provider.latitude,provider.longitude);
    }
    boolean atTarget(double targetLat, double targetLng, long wall, long elapsed, long flightWall, long flightElapsed) {
        return valid && fresh(wall,elapsed) && point(targetLat,targetLng) && "ready".equals(state) && samples==3
                && at>flightWall && rawAt>flightElapsed && provider.fixMs>flightElapsed
                && distance(rawLat,rawLng,lat,lng)<=8
                && distance(lat,lng,targetLat,targetLng)<=5
                && distance(provider.latitude,provider.longitude,targetLat,targetLng)<=5;
    }
    static boolean point(double lat,double lng) { return Double.isFinite(lat) && Double.isFinite(lng) && Math.abs(lat)<=90 && Math.abs(lng)<=180; }
    static double distance(double a,double b,double c,double d) {
        double x=Math.sin(Math.toRadians(c-a)/2), y=Math.sin(Math.toRadians(d-b)/2);
        return 12742000*Math.asin(Math.sqrt(Math.min(1,Math.max(0,x*x+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*y*y))));
    }
    static final class Arrival {
        private FarmLocation previous;
        boolean observe(FarmLocation fix, double lat, double lng, long wall, long elapsed, long flightWall, long flightElapsed) {
            if (!fix.atTarget(lat,lng,wall,elapsed,flightWall,flightElapsed)) { previous=null; return false; }
            boolean ready=previous!=null && previous.pid.equals(fix.pid) && previous.epoch==fix.epoch
                    && fix.at>previous.at && fix.at-previous.at<=2000 && fix.rawAt>previous.rawAt
                    && fix.provider.fixMs>previous.provider.fixMs;
            // A repeated frame must not replace the last distinct observation.
            if (previous==null || fix.at!=previous.at || !previous.pid.equals(fix.pid) || previous.epoch!=fix.epoch) previous=fix;
            return ready;
        }
    }
}
