package dev.ody.pikmincontrol;
import android.app.*;
import android.widget.*;
import android.text.InputType;
import android.view.View;
import java.util.*;
import java.util.concurrent.Executor;
/** One switch; explicit ordered allowlist. No background Android polling service. */
final class AutoPetalPanel {
    final Activity activity;final Executor worker;final LinearLayout box;final Switch toggle;final TextView state,summary;final Button choose,openGame,review;boolean rendering,busy,visible,reading;long editGeneration;
    final Runnable poll=()->read();
    AutoPetalConfig config;String original="";
    AutoPetalInventory inventory=new AutoPetalInventory();AlertDialog pickerDialog;Runnable pickerRefresh;Button pickerUpdate;
    AutoPetalPanel(Activity a,Executor w){activity=a;worker=w;box=new LinearLayout(a){
            @Override protected void onWindowVisibilityChanged(int visibility){
                super.onWindowVisibilityChanged(visibility);
                visible=visibility==View.VISIBLE;removeCallbacks(poll);
                if(visible)post(()->{if(config!=null&&isAttachedToWindow())read();});
            }
            @Override protected void onDetachedFromWindow(){visible=false;removeCallbacks(poll);super.onDetachedFromWindow();}
        };box.setOrientation(1);box.setPadding(24,24,24,24);
        toggle=new Switch(a);toggle.setText("種花自動換花");toggle.setTextSize(18);box.addView(toggle);
        summary=new TextView(a);box.addView(summary);state=new TextView(a);box.addView(state);
        choose=new Button(a);choose.setText("花瓣設定");box.addView(choose);choose.setOnClickListener(v->picker());
        TextView explanation=new TextView(a);explanation.setText("使用說明 ▸");box.addView(explanation);
        TextView details=new TextView(a);details.setText("先在遊戲開始種花，再按清單順序自動換花。每種接近保留量時提前約5片切換；全部不足便停止種花。手動換花後暫停接管，重新設定或下一輪才接續。路線／排程正在管理種花時等待，不搶控制。遊戲須持續執行；資料未知或換花未確認時不重送。");details.setVisibility(View.GONE);box.addView(details);explanation.setOnClickListener(v->details.setVisibility(details.getVisibility()==View.GONE?View.VISIBLE:View.GONE));
        openGame=new Button(a);openGame.setText("開啟遊戲");openGame.setVisibility(View.GONE);box.addView(openGame);openGame.setOnClickListener(v->updateFromGame());
        review=new Button(a);review.setText("核對並保留未確認紀錄");review.setVisibility(View.GONE);box.addView(review);
        review.setOnClickListener(v->new AlertDialog.Builder(a).setTitle("保留未確認紀錄")
            .setMessage("請先確認遊戲已停止種花並關閉結算。原始紀錄會保留為 UNKNOWN，不會重送舊操作；核心確認安全後才允許下一輪。")
            .setNegativeButton("取消",null).setPositiveButton("已確認停止",(d,i)->{lock(true);worker.execute(()->{try{AutoPetalGateway.review(config);activity.runOnUiThread(()->state.setText("已要求核對，等待核心保留原件"));}catch(Exception e){activity.runOnUiThread(()->state.setText(e.getMessage()));}finally{activity.runOnUiThread(()->{lock(false);box.postDelayed(()->read(),3000);});}});}).show());
        toggle.setOnCheckedChangeListener((v,on)->{
            if(rendering||busy||config==null)return;
            config.enabled=on;
            save();
        });
        read();
    }
    View view(){return box;}
    void lock(boolean v){busy=v;toggle.setEnabled(!v&&config!=null);choose.setEnabled(!v&&config!=null);openGame.setEnabled(!v);if(review!=null)review.setEnabled(!v);if(pickerUpdate!=null)pickerUpdate.setEnabled(!v);}
    void read(){
        if(busy||reading||activity.isFinishing()||activity.isDestroyed())return;
        reading=true;final long generation=editGeneration;final boolean initial=config==null;
        if(initial){lock(true);state.setText("正在載入花瓣設定…");}
        worker.execute(()->{
            try{
                String raw=AutoPetalGateway.readConfig();
                AutoPetalConfig c=raw.isEmpty()?new AutoPetalConfig():AutoPetalConfig.parse(raw);
                AutoPetalInventory inventoryRead=new AutoPetalInventory();
                AutoPetalState statusRead=new AutoPetalState();
                try {
                    CorePair pair=CoreUpdater.pair(activity);
                    String[] snapshot=AutoPetalGateway.displaySnapshot();
                    long now=System.currentTimeMillis();
                    inventoryRead=AutoPetalInventory.parse(snapshot,pair.version,pair.code,now);
                    statusRead=AutoPetalState.parse(snapshot[4],snapshot[0].trim(),snapshot[7].trim(),c.revision,now);
                } catch(Exception unavailable) {
                    // Keep saved preferences editable while runtime observations are unavailable.
                }
                final AutoPetalInventory captured=inventoryRead;
                final AutoPetalState parsed=statusRead;
                final String message=message(c,parsed,captured.valid);
                activity.runOnUiThread(()->{
                    if(activity.isFinishing()||activity.isDestroyed()||generation!=editGeneration)return;
                    config=c;original=raw;inventory=captured;render();state.setText(message);
                    openGame.setVisibility(captured.needsGame(System.currentTimeMillis())?View.VISIBLE:View.GONE);
                    if(pickerRefresh!=null)pickerRefresh.run();
                    review.setVisibility(!c.enabled&&parsed.valid&&parsed.phase.equals("review-required")?View.VISIBLE:View.GONE);
                });
            }catch(Exception e){activity.runOnUiThread(()->{
                if(generation!=editGeneration||activity.isDestroyed())return;
                inventory=new AutoPetalInventory();state.setText("設定暫時無法讀取，將自動重試");openGame.setVisibility(View.VISIBLE);
                review.setVisibility(View.GONE);
                if(pickerRefresh!=null)pickerRefresh.run();
            });}
            finally{activity.runOnUiThread(()->{reading=false;if(initial)lock(false);schedule();});}
        });
    }
    void schedule(){box.removeCallbacks(poll);if(visible&&box.isAttachedToWindow()&&!activity.isFinishing()&&!activity.isDestroyed())box.postDelayed(poll,3000);}
    /** Explicit fallback only; no query file, automatic foreground launch or planting command. */
    void updateFromGame(){
        if(activity.isFinishing()||activity.isDestroyed())return;
        android.content.Intent launch=activity.getPackageManager().getLaunchIntentForPackage(OwnerGameProcess.GAME);
        if(launch==null){state.setText("找不到遊戲入口，請先安裝支援版本");return;}
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try{activity.startActivity(launch);}catch(RuntimeException error){state.setText("請手動開啟遊戲，返回後會自動同步");}
    }
    static String color(String s){return AutoPetalState.color(s);}
    static String message(String s){switch(s){case "waiting-controller":return "等待遊戲完成開始種花";case "review-required":return "種花已停，請核對並保留未確認紀錄";case "unknown-archived":return "已保留 UNKNOWN 原件，可重新設定下一輪";case "off":return "已關閉";case "planting":return "種花中 · 庫存足夠";case "changed-confirmed":return "換花成功 · 同一輪種花";case "waiting-planting":return "等待你在遊戲開始種花";case "outside-list":return "目前花種不在清單，保留人工選擇";case "manual-change-paused":return "已手動換花 · 自動接管暫停";case "waiting-other-work":return "等待路線／排程交還種花控制";case "waiting-confirmation":case "waiting-stop-confirmation":return "等待遊戲確認 · 不會重送";case "stopped-at-reserve":return "花瓣已到保留量 · 已停止種花";case "method-unavailable":return "此遊戲／核心尚不支援換花";default:return "等待資料或核對 · "+s;}}
    private static String message(AutoPetalConfig config, AutoPetalState observation, boolean hasInventory) {
        if(observation.valid) {
            switch(observation.phase) {
                case "recovering-unknown":return "原種花輪次已結束 · 正在核對待確認紀錄";
                case "unknown-archived":return "已保留未確認紀錄 · 可依目前開關接續";
                case "recovery-record-unavailable":return "待確認紀錄尚無法讀取 · 暫停換花";
                case "storage-error":return "紀錄尚未保存 · 核心將持續核對";
                case "waiting-controller-sync":return "等待遊戲同步目前使用的花瓣";
                case "waiting-inventory":return "等待遊戲庫存更新";
                default:break;
            }
        }
        if(!config.enabled)return hasInventory?"自動換花已關閉 · 庫存自動同步":"自動換花已關閉";
        if(config.order.isEmpty())return "已開啟 · 等待選擇花瓣";
        return observation.valid?message(observation.phase):"已開啟 · 等待遊戲與配對核心資料";
    }
    void render(){rendering=true;toggle.setChecked(config.enabled);rendering=false;summary.setText("已選 "+config.order.size()+" 種 · 每種保留 "+config.reserve+"片");}
    void save(){save(original);}
    void save(String expected){
        editGeneration++;lock(true);config.revision=UUID.randomUUID().toString();final AutoPetalConfig saving=config;
        worker.execute(()->{
            try{
                synchronized(AutoPetalGateway.class) {
                    if(saving.enabled&&(WorkStatus.stoppingAll||SetupDeployment.pending(activity)||
                            UpdateInstaller.preparing||UpdateInstaller.pending(activity)||
                            CoreInstallWizard.preparing(activity)||CoreUpdater.pending(activity))) {
                        throw new IllegalStateException("請先完成停止／安裝核對");
                    }
                    AutoPetalGateway.save(saving,expected);
                }
                activity.runOnUiThread(()->{original=saving.encode();render();state.setText("已保存，等待遊戲確認");
                    box.postDelayed(()->{if(box.isAttachedToWindow())read();},3000);});
            }catch(Exception e){activity.runOnUiThread(()->{config=null;state.setText("保存未完成，請重新核對："+e.getMessage());});}
            finally{activity.runOnUiThread(()->{lock(false);schedule();});}
        });
    }
    int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    TextView text(String value){TextView v=new TextView(activity);v.setText(value);return v;}
    String selectedName(AutoPetalCatalogue source,String id){
        AutoPetalCatalogue.Entry entry=source.byId.get(id);
        return entry==null?"先前選擇（庫存與名稱待核對）":entry.label();
    }
    void picker(){
        if(busy||config==null||pickerDialog!=null)return;
        final AutoPetalConfig draft=AutoPetalConfig.parse(config.encode());final String expected=original;
        final AutoPetalCatalogue[] source={new AutoPetalCatalogue(inventory,System.currentTimeMillis())};
        LinearLayout content=new LinearLayout(activity);content.setOrientation(1);content.setPadding(dp(12),0,dp(12),0);
        content.addView(text("完整目錄共 "+source[0].entries.size()+" 種（含顏色）"));
        content.addView(text("每種花瓣保留量"));
        EditText reserve=new EditText(activity);reserve.setInputType(InputType.TYPE_CLASS_NUMBER);
        reserve.setSingleLine(true);reserve.setText(""+draft.reserve);content.addView(reserve);
        TextView hint=text("");hint.setMaxLines(3);hint.setEllipsize(android.text.TextUtils.TruncateAt.END);content.addView(hint);
        List<String> order=new ArrayList<>(draft.order);
        Runnable showOrder=()->{
            StringBuilder b=new StringBuilder("切換順序（"+order.size()+" / 64，點此查看）：");
            for(int i=0;i<order.size();i++)b.append("\n").append(i+1).append(". ").append(selectedName(source[0],order.get(i)));
            hint.setText(b.toString());
        };
        hint.setOnClickListener(v->{StringBuilder b=new StringBuilder("取消後再選可移到最後。\n");
            for(int i=0;i<order.size();i++)b.append(i+1).append(". ").append(selectedName(source[0],order.get(i))).append('\n');
            new AlertDialog.Builder(activity).setTitle("切換順序").setMessage(b.toString()).setPositiveButton("關閉",null).show();});
        showOrder.run();
        TextView snapshotInfo=text("");content.addView(snapshotInfo);
        Runnable showSnapshot=()->snapshotInfo.setText(source[0].reportedAt>0
                ?"上次庫存快照："+new java.text.SimpleDateFormat("MM/dd HH:mm:ss",Locale.TAIWAN).format(new Date(source[0].reportedAt))+"\n僅供設定參考；核心換花前會重新核對。"
                :"等待遊戲自動同步；遊戲暫停時保留上次快照，未辨識項目維持未知。");
        showSnapshot.run();
        pickerUpdate=new Button(activity);pickerUpdate.setText("開啟遊戲");pickerUpdate.setOnClickListener(v->updateFromGame());
        pickerUpdate.setVisibility(inventory.needsGame(System.currentTimeMillis())?View.VISIBLE:View.GONE);content.addView(pickerUpdate);
        EditText search=new EditText(activity);search.setSingleLine(true);search.setHint("搜尋花種或顏色");content.addView(search);
        LinearLayout filters=new LinearLayout(activity);Spinner mode=new Spinner(activity),colors=new Spinner(activity);
        mode.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"全部","快照有庫存","已選"}));
        colors.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"全部顏色","白","黃","紅","藍"}));
        filters.addView(mode,new LinearLayout.LayoutParams(0,-2,1));filters.addView(colors,new LinearLayout.LayoutParams(0,-2,1));content.addView(filters);
        TextView error=text("");content.addView(error);
        LinearLayout list=new LinearLayout(activity);list.setOrientation(1);ScrollView scroll=new ScrollView(activity);scroll.addView(list);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(dp(240),activity.getResources().getDisplayMetrics().heightPixels/4)));
        int[] page={0};Runnable[] show={null};
        show[0]=()->{
            list.removeAllViews();String filter=new String[]{"all","snapshot-stock","selected"}[mode.getSelectedItemPosition()];
            String color=new String[]{"all","1","4","2","3"}[colors.getSelectedItemPosition()];
            Map<String,List<AutoPetalCatalogue.Entry>> groups=new LinkedHashMap<>();
            for(AutoPetalCatalogue.Entry entry:source[0].filter(search.getText().toString(),filter,color,order))
                groups.computeIfAbsent(entry.family,k->new ArrayList<>()).add(entry);
            page[0]=Math.min(page[0],Math.max(0,(groups.size()-1)/20));
            if(groups.isEmpty())list.addView(text("沒有符合的花種；庫存未知時不會當成 0。"));
            int index=0;
            for(List<AutoPetalCatalogue.Entry> group:groups.values()){
                if(index++<page[0]*20)continue;if(index>page[0]*20+20)break;
                TextView family=text(group.get(0).name);family.setTextSize(18);list.addView(family);
                HorizontalScrollView rowScroll=new HorizontalScrollView(activity);LinearLayout row=new LinearLayout(activity);rowScroll.addView(row);list.addView(rowScroll);
                for(AutoPetalCatalogue.Entry entry:group){
                    LinearLayout item=new LinearLayout(activity);item.setOrientation(1);item.setPadding(0,0,dp(12),dp(6));row.addView(item);
                    CheckBox check=new CheckBox(activity);check.setText(color(entry.color));
                    check.setContentDescription(entry.label()+"，"+entry.quantity()+(entry.selectable()?"":"，等待辨識"));
                    check.setChecked(order.contains(entry.id));check.setEnabled(entry.selectable());item.addView(check);
                    item.addView(text(entry.selectable()?entry.quantity():"待辨識 · 庫存未知"));
                    check.setOnCheckedChangeListener((v,on)->{
                        if(on&&order.size()>=64){check.setChecked(false);error.setText("最多選擇 64 種花瓣");return;}
                        order.remove(entry.id);if(on)order.add(entry.id);error.setText("");showOrder.run();
                    });
                }
            }
            if(page[0]>0){Button previous=new Button(activity);previous.setText("上一頁");list.addView(previous);previous.setOnClickListener(v->{page[0]--;show[0].run();});}
            if(groups.size()>(page[0]+1)*20){Button next=new Button(activity);next.setText("下一頁");list.addView(next);next.setOnClickListener(v->{page[0]++;show[0].run();});}
            if(filter.equals("selected"))for(String id:new ArrayList<>(order))if(!source[0].byId.containsKey(id)){
                CheckBox old=new CheckBox(activity);old.setText((order.indexOf(id)+1)+". "+selectedName(source[0],id));old.setChecked(true);list.addView(old);
                old.setOnCheckedChangeListener((v,on)->{if(!on){order.remove(id);showOrder.run();show[0].run();}});
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){page[0]=0;show[0].run();}
            public void afterTextChanged(android.text.Editable e){}
        });
        android.widget.AdapterView.OnItemSelectedListener changed=new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){page[0]=0;show[0].run();}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        };
        mode.setOnItemSelectedListener(changed);colors.setOnItemSelectedListener(changed);show[0].run();
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("自動換花條件").setView(content)
                .setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        pickerDialog=dialog;
        final String[] contentKey={inventory.contentKey(System.currentTimeMillis())};
        pickerRefresh=()->{
            source[0]=new AutoPetalCatalogue(inventory,System.currentTimeMillis());
            showSnapshot.run();pickerUpdate.setVisibility(inventory.needsGame(System.currentTimeMillis())?View.VISIBLE:View.GONE);
            String nextKey=inventory.contentKey(System.currentTimeMillis());
            if(!nextKey.equals(contentKey[0])){int y=scroll.getScrollY();contentKey[0]=nextKey;showOrder.run();show[0].run();scroll.post(()->scroll.scrollTo(0,y));}
            if(!original.equals(expected))error.setText("設定已在別處更新；請取消後重新開啟，避免覆寫。");
        };
        dialog.setOnDismissListener(d->{pickerDialog=null;pickerRefresh=null;pickerUpdate=null;});dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(busy){error.setText("正在保存設定，請稍候。");return;}
            try{
                draft.reserve=FeedingConfig.number(reserve.getText().toString(),0,1000000);
                draft.order.clear();draft.order.addAll(order);draft.encode(); // Validate before changing live config or dismissing.
                config=draft;dialog.dismiss();save(expected);
            }catch(IllegalArgumentException invalid){error.setText("請輸入有效保留量。");}
        });
    }
}
