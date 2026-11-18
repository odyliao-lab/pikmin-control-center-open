package dev.ody.pikmincontrol;

/** MuMu route presentation only. Uses the SAME ordered parser as confirmation;
 * never grants work/GPS/start authority or rewrites the user's input. */
final class EmulatorOrderedRouteDraft {
    final boolean valid;
    final int points;
    final long meters;
    final String issue;
    EmulatorOrderedRouteDraft(String raw) {
        HarvestRoute parsed=null;String problem="";
        try {
            if(raw==null)throw new IllegalArgumentException("請輸入路線");
            String input=raw.trim();
            parsed=input.startsWith("<")?HarvestRoute.gpx(input):HarvestRoute.coordinates(input);
        }catch(Exception e){problem="路線格式無效，請檢查座標或單段GPX";}
        valid=parsed!=null;points=valid?parsed.points.size():0;meters=valid?Math.round(parsed.meters):0;issue=problem;
    }
    String summary(){return valid?"沿路 "+points+" 個節點 · 約 "+meters+" 公尺 · 保留順序與重複點":issue;}
}
