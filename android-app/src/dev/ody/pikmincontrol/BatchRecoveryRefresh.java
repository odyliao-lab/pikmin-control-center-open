package dev.ody.pikmincontrol;

/** A bounded foreground refresh; busy is NOT treated as an idle certificate. */
final class BatchRecoveryRefresh {
    interface Backend {
        Sample read() throws Exception;
        long elapsed();
        void openGame() throws Exception;
        void waitForUpdate() throws Exception;
        void guard() throws Exception;
    }
    static final class Sample {
        final String raw,before,after;final long wall;
        Sample(String raw,String before,String after,long wall){this.raw=raw;this.before=before;this.after=after;this.wall=wall;}
        String identity(){return SessionIdentity.parse(raw,before,after,wall);}
        boolean wrongAccount(String expected) {
            String[] f=raw.trim().split("\t",-1);
            return f.length==7 && f[3].matches("[a-f0-9]{64}") && !expected.equals(f[3]);
        }
    }
    static String require(Backend b,String expected)throws Exception {
        if(expected==null || !expected.matches("[a-f0-9]{64}"))throw new IllegalStateException("原批次缺少帳號識別，請保留紀錄並人工處理");
        long start=b.elapsed();String pid=null;boolean opened=false;
        for(;;) {
            b.guard();Sample s=b.read();
            if(!s.before.matches("[1-9][0-9]*") || !s.before.equals(s.after))
                throw new IllegalStateException("遊戲未執行或程序正在改變，尚未恢復");
            if(pid!=null&&!pid.equals(s.before))throw new IllegalStateException("更新回報期間遊戲程序已改變，請重新診斷");
            pid=s.before;
            if(s.wrongAccount(expected))throw new IllegalStateException("目前帳號與原批次不符，禁止恢復");
            if(b.elapsed()-start>=20_000L)throw new IllegalStateException("已切回遊戲，但20秒內未取得新鮮帳號回報；核心可能尚未就緒，保留暫停");
            String identity=s.identity();
            if(expected.equals(identity))return identity;
            if(!opened){b.openGame();opened=true;}
            b.waitForUpdate();
        }
    }
}
