package dev.ody.pikmincontrol;

import java.util.*;

/** UI-only catalogue. Never used as a stock observation or permission to spend.
 * Game157 shared type index: 188 observed family/color identities;
 * no account quantities retained.
 * New observed colors extend the picker, never the saved whitelist automatically.
 */
final class FeedingCatalogue {
    private static final String[] FAMILIES={
        "flower-5|普通精華|1234",
        "anemone|銀蓮花|1234", "bellflower|風鈴草|123",
        "bougainvillea|九重葛|1234", "callalily|海芋|1234",
        "camellia|山茶花|1234", "canna|美人蕉|124",
        "canola|油菜花|4", "carnation|康乃馨|124", "cattleya|嘉德麗雅蘭|1234",
        "celosia|雞冠花|1234", "cherryblossom|櫻花|1",
        "chrysanthemum|菊花|1234", "clematis|鐵線蓮|1234", "clusteramaryllis|石蒜|124",
        "convallaria|鈴蘭|12", "cosmos|波斯菊|124",
        "cyclamen|兔耳花|123", "dahlia|大理花|1234",
        "dianthus|石竹|123", "epiphyllum|曇花|1234",
        "forgetmenot|勿忘草|1234", "freesia|小蒼蘭|1234",
        "geranium|天竺葵|1234",
        "helleborus|聖誕玫瑰|1234", "hibiscus|扶桑花|1234",
        "hyacinth|風信子|1234", "hydrangea|繡球花|123", "iris|鳶尾花|1234",
        "lilium|百合|124", "lisianthus|洋桔梗|1234",
        "marigold|萬壽菊|124", "morningglory|牽牛花|1234",
        "mothorchid|蝴蝶蘭|1234", "nemophila|粉蝶花|3", "nymphaea|睡蓮|1234",
        "pansy|三色堇|1234", "peony|牡丹|1234",
        "petunia|矮牽牛|1234", "plumblossom|梅花|124",
        "plumeria|雞蛋花|124", "poinsettia|聖誕紅|1234",
        "primula|櫻草花|1234", "rose|玫瑰|1234",
        "rose2025|週年紀念玫瑰|1234", "salvia|鼠尾草|1234",
        "snapdragon|金魚草|1234", "snowdrop|雪花蓮|124",
        "strelitzia|天堂鳥|124", "sunflower|向日葵|4",
        "sweetpea|香豌豆|1234", "thistle|薊花|1234", "tulip|鬱金香|1234",
        "tulip2026|鸚鵡鬱金香|1234"
    };
    private static String family(String key){return key.startsWith("flower-")?key:"flower-s-"+key;}
    static String name(String key){
        for(String row:FAMILIES){String[] f=row.split("\\|");if(family(f[0]).equals(key))return f[1];}
        return "未知花種（"+key+"）";
    }
    static String colorName(String code){
        return "1".equals(code)?"white":"2".equals(code)?"red":"3".equals(code)?"blue":"4".equals(code)?"yellow":"other";
    }
    static String colorCode(String name){
        return "white".equals(name)?"1":"red".equals(name)?"2":"blue".equals(name)?"3":"yellow".equals(name)?"4":"";
    }
    /** Shared nectar/petal type index. Identity-only additions; every quantity remains unknown. */
    static List<FeedingSnapshot.Kind> index(Collection<FeedingSnapshot.Kind> observedTypes){
        Map<String,FeedingSnapshot.Kind> entries=new LinkedHashMap<>();
        for(String row:FAMILIES){String[] f=row.split("\\|");String family=family(f[0]);
            for(char c:f[2].toCharArray()){
                FeedingSnapshot.Kind k=new FeedingSnapshot.Kind(new String[]{"kind",family+":"+c,family,f[1],
                        colorName(String.valueOf(c)),f[0].equals("flower-5")?"1":"0","-1","-1","-1"});
                entries.put(k.id,k);
            }
        }
        for(FeedingSnapshot.Kind k:observedTypes){
            if(k.family.toLowerCase(Locale.ROOT).contains("happy"))continue;
            entries.put(k.id,new FeedingSnapshot.Kind(new String[]{"kind",k.id,k.family,name(k.family),k.color,
                    k.ordinary?"1":"0","-1","-1","-1"}));
        }
        return new ArrayList<>(entries.values());
    }
    static FeedingSnapshot display(FeedingSnapshot observed){
        FeedingSnapshot out=new FeedingSnapshot();
        Map<String,FeedingSnapshot.Kind> fresh=new LinkedHashMap<>();
        if(observed.fresh)for(FeedingSnapshot.Kind k:observed.kinds)
            if(!k.family.toLowerCase(Locale.ROOT).contains("happy"))fresh.put(k.id,k);
        for(FeedingSnapshot.Kind identity:index(observed.fresh?observed.kinds:Collections.emptyList())){
            FeedingSnapshot.Kind k=fresh.get(identity.id);
            out.kinds.add(k==null?identity:new FeedingSnapshot.Kind(new String[]{"kind",k.id,k.family,name(k.family),
                    k.color,k.ordinary?"1":"0",Long.toString(k.nectar),Long.toString(k.petals),Long.toString(k.capacity)}));
        }
        // Intentionally leave fresh/ready false: this is NOT a native report.
        return out;
    }
    static void select(FeedingConfig draft,List<FeedingSnapshot.Kind> matching){
        for(FeedingSnapshot.Kind k:matching)if(!draft.rules.containsKey(k.id)&&draft.rules.size()<FeedingConfig.MAX_KINDS)
            draft.rules.put(k.id,new FeedingConfig.Rule(-1,-1));
    }
}
