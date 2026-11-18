package dev.ody.pikmincontrol;

import java.io.IOException;

/** Historical transactions store old as String and target as Integer. Read without rewriting evidence. */
final class CoreStoredVersion {
    static int read(Object value)throws IOException {
        if(!(value instanceof String)&&!(value instanceof Integer))throw invalid();
        String text=value.toString();
        if(!text.matches("[1-9][0-9]{0,9}"))throw invalid();
        try{return Integer.parseInt(text);}catch(NumberFormatException e){throw invalid();}
    }
    private static IOException invalid(){return new IOException("原安裝紀錄的核心版本無效，請保留紀錄並匯出診斷");}
}
