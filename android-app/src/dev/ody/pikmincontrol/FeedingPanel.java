package dev.ody.pikmincontrol;

import android.app.*;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Compact page + transactional picker. No demo flower names or game commands in the UI. */
final class FeedingPanel {
    private final Activity activity;
    private final ExecutorService worker;
    private final LinearLayout box;
    private TextView status,summary;
    private Switch enabled,collect,rotate;
    private FeedingSnapshot snapshot=new FeedingSnapshot();
    private long snapshotReadAt;
    private boolean busy,reading;
    private boolean explanationExpanded;
    FeedingPanel(Activity activity,ExecutorService worker){this.activity=activity;this.worker=worker;box=column();render();}
    LinearLayout view(){return box;}
    int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    int color(String name){return activity.getResources().getColor(activity.getResources().getIdentifier(name,"color",activity.getPackageName()),activity.getTheme());}
    LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(1);return v;}
    TextView text(String label,int size){TextView v=new TextView(activity);v.setText(label);v.setTextSize(size);v.setTextColor(color("app_text_primary"));v.setPadding(0,dp(6),0,dp(6));return v;}
    Button button(LinearLayout p,String label,Runnable action){Button b=new Button(activity);b.setText(label);b.setAllCaps(false);b.setMinHeight(dp(48));b.setTextColor(color("app_text_primary"));b.setBackgroundTintList(ColorStateList.valueOf(color("app_selected")));b.setOnClickListener(v->action.run());p.addView(b);return b;}
    void toast(String s){Toast.makeText(activity,s,Toast.LENGTH_LONG).show();}
    Switch toggle(LinearLayout p,String label,boolean value){Switch v=new Switch(activity);v.setText(label);v.setTextSize(17);v.setTextColor(color("app_text_primary"));v.setChecked(value);v.setMinHeight(dp(52));p.addView(v);return v;}
    private void render(){
        FeedingConfig c=FeedingGateway.load(activity);box.removeAllViews();box.setPadding(dp(16),dp(12),dp(16),dp(12));
        GradientDrawable bg=new GradientDrawable();bg.setColor(color("app_surface"));bg.setCornerRadius(dp(24));bg.setStroke(dp(1),color("app_outline"));box.setBackground(bg);
        enabled=toggle(box,"背景自動餵食",c.enabled);enabled.setEnabled(!busy);
        enabled.setOnCheckedChangeListener((v,on)->{FeedingConfig f=FeedingGateway.load(activity);f.enabled=on;save(f,f.revision);});
        box.addView(text(heart(c.hearts)+" · "+(c.rotate?"自動補入合適成員":"只處理目前隊伍"),14));
        button(box,"餵食條件",this::common);
        int overrides=0;for(FeedingConfig.Rule r:c.rules.values())if(!r.inherited())overrides++;
        summary=text("精華保留 "+c.reserve+" · 花瓣上限依遊戲容量",15);box.addView(summary);
        button(box,"搜尋與管理精華 · 已選 "+c.rules.size()+" 種",this::picker);
        collect=toggle(box,"自動收花瓣",c.collect);collect.setEnabled(!busy);
        collect.setOnCheckedChangeListener((v,on)->{FeedingConfig f=FeedingGateway.load(activity);f.collect=on;save(f,f.revision);});
        rotate=toggle(box,"自動換隊",c.rotate);rotate.setEnabled(!busy);
        rotate.setOnCheckedChangeListener((v,on)->{FeedingConfig f=FeedingGateway.load(activity);f.rotate=on;save(f,f.revision);});
        status=text(FeedingGateway.message(activity,snapshot),14);box.addView(status);
        explanation();
        button(box,"工作紀錄與診斷",this::history);
    }
    private void explanation(){
        LinearLayout details=column();
        details.addView(text("設定自動保存 · 收瓣遵守容量上限",13));
        details.addView(text("換下枯萎或達門檻成員 · 無合適替補就等待",13));
        details.setVisibility(explanationExpanded?View.VISIBLE:View.GONE);
        Button header=button(box,"餵食與換隊說明"+(explanationExpanded?"  ▴":"  ▾"),()->{});
        header.setContentDescription("餵食與換隊說明"+(explanationExpanded?"，已展開":"，已收合"));
        header.setOnClickListener(v->{
            explanationExpanded=!explanationExpanded;
            details.setVisibility(explanationExpanded?View.VISIBLE:View.GONE);
            header.setText("餵食與換隊說明"+(explanationExpanded?"  ▴":"  ▾"));
            header.setContentDescription("餵食與換隊說明"+(explanationExpanded?"，已展開":"，已收合"));
        });
        box.addView(details);
    }
    private void history(){
        worker.execute(()->{
            try {
                final String raw=FeedingGateway.history();final String formatted=FeedingHistory.format(raw);
                activity.runOnUiThread(()->{if(!activity.isFinishing())new AlertDialog.Builder(activity)
                    .setTitle("餵食紀錄 · 最新在上").setMessage(formatted)
                    .setPositiveButton("關閉",null).show();});
            }catch(Exception e){activity.runOnUiThread(()->{if(!activity.isFinishing())toast("紀錄讀取失敗，請稍後重試");});}
        });
    }
    static String heart(int n){return "未滿 "+(n<=4?n+" 紅心":(n-4)+" 黃心");}
    private void save(FeedingConfig c,String expected){
        if(busy)return;busy=true;enabled.setEnabled(false);collect.setEnabled(false);rotate.setEnabled(false);status.setText("正在保存設定…");
        worker.execute(()->{
            String error="";
            try {
                synchronized(FeedingGateway.class){
                    if(!FeedingGateway.load(activity).revision.equals(expected))throw new IllegalStateException("設定已變更，請重新開啟編輯");
                    if((c.enabled||c.collect)&&(WorkStatus.stoppingAll||SetupDeployment.pending(activity)||UpdateInstaller.blocking(activity)||CoreUpdater.pending(activity)))throw new IllegalStateException("請先完成停止／安裝核對");
                    FeedingGateway.save(activity,c);
                }
            }catch(Exception e){error=e.getMessage();}
            final String result=error;activity.runOnUiThread(()->{busy=false;if(activity.isFinishing())return;render();if(!result.isEmpty())status.setText("⚠ "+result+"；設定不代表原生已生效");else refresh();});
        });
    }
    void refresh(){
        if(reading||busy)return;reading=true;
        worker.execute(()->{FeedingSnapshot s;try{s=FeedingGateway.read();}catch(Exception e){s=new FeedingSnapshot();}final FeedingSnapshot read=s;
            activity.runOnUiThread(()->{reading=false;if(activity.isFinishing())return;snapshot=read;snapshotReadAt=android.os.SystemClock.elapsedRealtime();status.setText(FeedingGateway.message(activity,snapshot));});});
    }
    void squadDiagnostics(){
        if(busy)return;
        TextView diagnostic=text("正在等待遊戲更新隊伍資料…",14);
        diagnostic.setPadding(dp(20),dp(12),dp(20),dp(12));diagnostic.setTextIsSelectable(true);
        ScrollView scroll=new ScrollView(activity);scroll.addView(diagnostic);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("目前隊伍診斷").setView(scroll)
                .setPositiveButton("關閉",null).create();dialog.show();
        worker.execute(()->{
            String result="尚無可用的隊伍診斷；請停在遊戲內並稍候重試。";
            try {
                // Native observation is deliberately rate-limited to once per five seconds.
                // Request once, then poll the resulting read-only file instead of racing it.
                FeedingGateway.requestSquadSnapshot();
                for(int i=0;i<12&&FeedingSquadDiagnostics.unavailable(result);i++){
                    Thread.sleep(600);
                    result=FeedingGateway.squadDiagnostics(activity);
                }
            }
            catch(Exception e){result="隊伍診斷讀取失敗，請確認遊戲已開啟後重試。";}
            final String text=result;activity.runOnUiThread(()->{if(!activity.isFinishing()&&dialog.isShowing())diagnostic.setText(text);});
        });
    }
    EditText input(LinearLayout form,String label,int value){form.addView(text(label,15));EditText e=new EditText(activity);e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);e.setSingleLine(true);e.setText(String.valueOf(value));e.setTextColor(color("app_text_primary"));e.setBackgroundTintList(ColorStateList.valueOf(color("app_selected")));e.setSelectAllOnFocus(true);form.addView(e);return e;}
    void common(){
        if(busy)return;FeedingConfig draft=FeedingGateway.load(activity).copy();String expected=draft.revision;
        LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(8));form.addView(text("餵食與自動換隊共用愛心門檻",14));
        Spinner hearts=new Spinner(activity);String[] labels=new String[8];for(int i=0;i<8;i++)labels[i]=heart(i+1);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels);hearts.setAdapter(adapter);hearts.setSelection(draft.hearts-1);form.addView(hearts);
        EditText reserve=input(form,"精華至少保留",draft.reserve);
        form.addView(text("花瓣上限自動依遊戲容量 · 容量未知時等待",13));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("共用門檻").setView(form).setNegativeButton("取消",null).setPositiveButton("儲存",null).create();dialog.show();
        dialog.getButton(-1).setOnClickListener(v->{try{draft.hearts=hearts.getSelectedItemPosition()+1;draft.reserve=FeedingConfig.number(reserve.getText().toString(),0,FeedingConfig.MAX_COUNT);draft.useGameCapacity();save(draft,expected);dialog.dismiss();}catch(Exception e){toast(e.getMessage());}});
    }
    private void picker(){
        if(busy)return;final FeedingConfig draft=FeedingGateway.load(activity).copy();final String expected=draft.revision;
        final boolean observedFresh=snapshot.fresh&&snapshotReadAt>0&&android.os.SystemClock.elapsedRealtime()-snapshotReadAt<=12000;
        final FeedingSnapshot catalogue=FeedingCatalogue.display(observedFresh?snapshot:new FeedingSnapshot());
        Dialog dialog=new Dialog(activity);LinearLayout shell=column();shell.setPadding(dp(16),dp(16),dp(16),dp(16));shell.setBackgroundColor(color("app_background"));
        shell.addView(text("管理精華種類",23));
        shell.addView(text("完整目錄共 "+catalogue.kinds.size()+" 種（含顏色）",14));
        EditText search=new EditText(activity);search.setSingleLine(true);search.setHint("搜尋花種");search.setTextColor(color("app_text_primary"));search.setHintTextColor(color("app_text_primary"));shell.addView(search);
        LinearLayout filters=new LinearLayout(activity);Spinner mode=new Spinner(activity),colors=new Spinner(activity);
        mode.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"全部","有庫存","已選","有精華／零花瓣","花瓣滿倉"}));
        colors.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"全部顏色","白","黃","紅","藍","其他"}));
        filters.addView(mode,new LinearLayout.LayoutParams(0,-2,1));filters.addView(colors,new LinearLayout.LayoutParams(0,-2,1));shell.addView(filters);
        HorizontalScrollView shortcutScroll=new HorizontalScrollView(activity);
        LinearLayout shortcuts=new LinearLayout(activity);shortcutScroll.addView(shortcuts);shell.addView(shortcutScroll);
        ScrollView scroll=new ScrollView(activity);LinearLayout list=column();scroll.addView(list);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView count=text("",14);shell.addView(count);shell.addView(text("新花種不自動勾選 · 儲存後才生效",12));
        final int[] page={0};
        Runnable[] refresh=new Runnable[1];
        Button selectAll=button(shortcuts,"全選",()->{
            String m=new String[]{"all","stock","selected","zero-petals","full"}[mode.getSelectedItemPosition()];
            String col=new String[]{"all","white","yellow","red","blue","other"}[colors.getSelectedItemPosition()];
            FeedingCatalogue.select(draft,NectarStockDisplay.filter(catalogue,search.getText().toString(),m,col,draft));refresh[0].run();
        });
        refresh[0]=()->{
            list.removeAllViews();String m=new String[]{"all","stock","selected","zero-petals","full"}[mode.getSelectedItemPosition()];String col=new String[]{"all","white","yellow","red","blue","other"}[colors.getSelectedItemPosition()];
            Map<String,List<FeedingSnapshot.Kind>> groups=new LinkedHashMap<>();
            for(FeedingSnapshot.Kind k:NectarStockDisplay.filter(catalogue,search.getText().toString(),m,col,draft))groups.computeIfAbsent(k.family,x->new ArrayList<>()).add(k);
            selectAll.setText(search.getText().toString().trim().isEmpty()&&m.equals("all")&&col.equals("all")?"全選":"全選篩選結果");
            if(!observedFresh)list.addView(text("內建目錄 · 庫存未知，更新狀態後可查庫存",13));
            else list.addView(text("庫存為開啟時快照 · 實際餵食前重新核對",13));
            if(groups.isEmpty())list.addView(text("沒有符合的精華",16));
            page[0]=Math.min(page[0],Math.max(0,(groups.size()-1)/20));
            int index=0;for(List<FeedingSnapshot.Kind> group:groups.values()){
                if(index++<page[0]*20)continue;if(index>page[0]*20+20)break;
                list.addView(text(group.get(0).name,18));
                HorizontalScrollView rowScroll=new HorizontalScrollView(activity);LinearLayout row=new LinearLayout(activity);rowScroll.addView(row);list.addView(rowScroll);
                for(FeedingSnapshot.Kind k:group){LinearLayout item=column();item.setPadding(dp(4),0,dp(12),0);row.addView(item);
                    CheckBox choice=new CheckBox(activity);choice.setText(FeedingSnapshot.color(k.color));choice.setTextColor(color("app_text_primary"));choice.setChecked(draft.rules.containsKey(k.id));item.addView(choice);
                    TextView stock=text(NectarStockDisplay.summary(k),14);stock.setMinWidth(dp(150));item.addView(stock);
                    choice.setOnCheckedChangeListener((v,on)->{if(on)draft.rules.put(k.id,new FeedingConfig.Rule(-1,-1));else draft.rules.remove(k.id);count.setText("已選 "+draft.rules.size()+" 種");});
                    Button settings=new Button(activity);settings.setText("⋯");settings.setContentDescription(k.name+" "+FeedingSnapshot.color(k.color)+" 個別設定");settings.setMinWidth(dp(40));settings.setMinimumWidth(dp(40));item.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48)));
                    settings.setOnClickListener(v->{if(!draft.rules.containsKey(k.id)){toast("請先勾選這款精華");return;}individual(draft,k,refresh[0]);});
                }
            }
            if(page[0]>0)button(list,"上一頁",()->{page[0]--;refresh[0].run();});
            if(groups.size()>(page[0]+1)*20)button(list,"下一頁",()->{page[0]++;refresh[0].run();});
            // Preserve choices absent from the latest inventory, and allow explicit removal.
            if(m.equals("selected"))for(String id:new ArrayList<>(draft.rules.keySet())){
                boolean found=false;for(FeedingSnapshot.Kind k:catalogue.kinds)if(k.id.equals(id))found=true;
                if(!found)button(list,"未在目前目錄："+id+" · 移除",()->{draft.rules.remove(id);refresh[0].run();});
            }
            count.setText("已選 "+draft.rules.size()+" 種");
        };
        button(shortcuts,"普通精華",()->{for(FeedingSnapshot.Kind k:catalogue.kinds)if(k.ordinary&&!draft.rules.containsKey(k.id))draft.rules.put(k.id,new FeedingConfig.Rule(-1,-1));refresh[0].run();});
        button(shortcuts,"清空選取",()->{draft.rules.clear();refresh[0].run();});
        AdapterView.OnItemSelectedListener change=new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){page[0]=0;refresh[0].run();}public void onNothingSelected(AdapterView<?> p){}};mode.setOnItemSelectedListener(change);colors.setOnItemSelectedListener(change);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){page[0]=0;refresh[0].run();}public void afterTextChanged(Editable e){}});
        LinearLayout actions=new LinearLayout(activity);shell.addView(actions);button(actions,"取消",dialog::dismiss);button(actions,"儲存選取",()->{save(draft,expected);dialog.dismiss();});
        dialog.setContentView(shell);dialog.show();dialog.getWindow().setLayout(-1,-1);refresh[0].run();
    }
    void individual(FeedingConfig draft,FeedingSnapshot.Kind kind,Runnable refresh){
        FeedingConfig.Rule old=draft.rules.get(kind.id);LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(8));
        Switch inherit=toggle(form,"使用共用門檻",old.inherited());
        EditText reserve=input(form,"精華至少保留",draft.reserve(kind.id));
        reserve.setEnabled(!old.inherited());inherit.setOnCheckedChangeListener((v,on)->reserve.setEnabled(!on));
        form.addView(text("花瓣上限由遊戲容量決定，送出前重新核對",13));
        form.addView(text(NectarStockDisplay.summary(kind),14));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(kind.name+" · "+FeedingSnapshot.color(kind.color)).setView(form).setNegativeButton("取消",null).setPositiveButton("套用至選取草稿",null).create();dialog.show();
        dialog.getButton(-1).setOnClickListener(v->{try{draft.rules.put(kind.id,inherit.isChecked()?new FeedingConfig.Rule(-1,-1):new FeedingConfig.Rule(FeedingConfig.number(reserve.getText().toString(),0,FeedingConfig.MAX_COUNT),FeedingConfig.MAX_COUNT));dialog.dismiss();refresh.run();}catch(Exception e){toast(e.getMessage());}});
    }
}
