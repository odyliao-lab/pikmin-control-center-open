from pathlib import Path
import hashlib
root=Path(__file__).resolve().parent.parent
s=(root/'native/automation/zygisk-module/cpp/auto_petal_switch.inc').read_text(encoding='utf-8')
observe=s[s.index('Observation observe(){'):s.index('void status(')]
admission=s[s.index('    std::map<std::string,int> stocks;'):s.index('    unresolved=true; //')]
assert hashlib.sha256(observe.encode()).hexdigest()=="c24024bb878dee7bd8a337b7b4a564ef54d738f37de668dc7271c97e4e72eea5",'Observation/getter admission changed'
assert hashlib.sha256(admission.encode()).hexdigest()=="cf9952715432dbe64e595d3127fcfa958cc91a890821a64bd747dc20491d8584",'Swap/stop choice and pre-send recheck changed'
recovery=s[s.index('if(unresolved){'):s.index('    if(!c.enabled){status')]
assert 'recovery_window.ready(quiet,s.epoch,steady_ms())' in recovery
assert 's.known&&!s.active&&s.started==0&&s.account==acct' in recovery
assert 'controller_clear()&&(!stop_task||stop_terminal())' in recovery and 'auto-petal-UNKNOWN-' in recovery
assert 'feeding_probe::invoke(' not in recovery and 'stop_planting_with_confirmation(' not in recovery
assert s.index('save("auto_petal_pending.tsv",pending_raw,true)')<s.index('stop_task=stop_planting_with_confirmation(')
collector=s[s.index('void inventory_tick(){'):s.index('bool stop_succeeded')]
assert 'publish_inventory(observe())' in collector and 'parse(' not in collector and 'choose(' not in collector
ui=(root/'android-app/src/dev/ody/pikmincontrol/AutoPetalPanel.java').read_text(encoding='utf-8')
assert 'AutoPetalGateway.snapshot(' not in ui and 'AutoPetalGateway.query(' not in ui
assert '重新核對"' not in ui and '重讀快照' not in ui and '前往遊戲更新庫存' not in ui
assert 'onDetachedFromWindow' in ui and 'removeCallbacks(poll)' in ui and 'generation!=editGeneration' in ui
assert 'scroll.scrollTo(0,y)' in ui and 'contentKey' in ui
assert 'if(on&&config.order.isEmpty())' not in ui and 'AutoPetalGateway.save(c,raw)' not in ui
print('PASS original getters and action admission, durable-before-invoke, no replay in recovery, and read-only UI polling')
