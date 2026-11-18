from pathlib import Path
import subprocess,os,tempfile,itertools,shutil,argparse,re
p=argparse.ArgumentParser();p.add_argument('--java',required=True);p.add_argument('--classpath',required=True);args=p.parse_args()
repo=Path(__file__).resolve().parents[2]
panels=[]
for relative in ['android-app/src/dev/ody/pikmincontrol/MainActivity.java','targets/mumu/android/override/MainActivity.java']:
 source=(repo/relative).read_text(encoding='utf8')
 panel=source.split('sectionLabel("遊戲資訊顯示");',1)[1].split('addCard(previewCard);',1)[0]
 assert 'String[] displayLabels={"花苗資訊","皮克敏資訊","探險資訊","精華／花瓣對照"};' in panel
 assert panel.count('settingSwitch(')==1 and 'DisplayOptionsGateway.COUNT' in panel
 assert 'optionExplanation(previewCard,displayLabels[i]+"說明",displayHelp[i]);' in panel
 assert '部分開啟' not in panel and '細項' not in panel
 for old in ['toggleSeedPreview','toggleSeedCollection','toggleAdultState','toggleAdultDecoration','toggleGiftPreview','togglePlanterDisplay','toggleExpeditionDisplay','toggleNectarStock']:assert old not in source
 assert 'generation!=displayGenerations[group]' in source and 'revision!=pageRevision' in source
 panels.append(panel)
assert panels[0]==panels[1]
print('PASS phone/MuMu identical four-switch panel, collapsed explanations and stale-callback guards')
gateway=repo/'android-app/src/dev/ody/pikmincontrol'
group_source=(gateway/'DisplayOptionsGateway.java').read_text(encoding='utf8')
def array(name):
 block=group_source.split('String[][] '+name+'={',1)[1].split('};',1)[0]
 return re.findall(r'"([^"\n]+)"',block)
legacy=['SeedPreviewGateway','SeedCollectionGateway','PlanterDisplayGateway','AdultStateGateway','AdultDecorationGateway','GiftPreviewGateway','ExpeditionDisplayGateway','NectarStockGateway']
controls=[];reports=[]
for name in legacy:
 source=(gateway/(name+'.java')).read_text(encoding='utf8')
 controls.append(re.search(r'CONTROL="/data/local/tmp/pikmin-([^"\n]+)\.txt"',source).group(1))
 reports.append(re.search(r'REPORT="/data/user/0/com.nianticlabs.pikmin/files/([^"\n]+)"',source).group(1))
assert array('MEMBERS')==controls and array('REPORTS')==reports,'Group paths must match the existing production control/report contracts'
print('PASS all eight group controls/reports match independent legacy production gateways')
java=args.java
bash=next((str(p) for p in [Path(os.environ.get('LOCALAPPDATA',''))/'Android/tools/git/bin/bash.exe',Path('C:/Program Files/Git/bin/bash.exe')] if p.is_file()),None) if os.name=='nt' else shutil.which('bash')
assert bash
members=[['seed-preview','seed-collection','planter-display'],['adult-state-preview','adult-decoration-preview'],['gift-preview','expedition-display'],['nectar-stock-preview']]
keys=['seedlings','pikmin','expeditions','stock'];commands={};count=0
for group in range(4):
 for mode in ['on','off','migrate']:
  commands[group,mode]=subprocess.check_output([java,'-cp',args.classpath,'dev.ody.pikmincontrol.DisplayOptionsStateTest','--command',str(group),mode],encoding='utf8')
def path(p):
 s=str(p.resolve()).replace('\\','/')
 return '/'+s[0].lower()+s[2:] if os.name=='nt' else s
def run(root,g,mode,prefix=''):
 # Host fixture only: ownership is tested by product source checks; do not require host root.
 script='chown(){ return 0; }; '+prefix+commands[g,mode].replace('/data/local/tmp/',path(root)+'/')
 return subprocess.run([bash,'-c',script],capture_output=True,text=True,encoding='utf8',timeout=20)
def file(root,m):return root/('pikmin-'+m+'.txt')
def master(root,g):return file(root,'display-group-'+keys[g])
def seed(root,g,values):
 for m,v in zip(members[g],values):file(root,m).write_text(v+'\n')
def check(root,g,mode):
 global count
 assert master(root,g).read_text().strip()==mode
 assert all(file(root,m).read_text().strip()==mode for m in members[g]);count+=1
with tempfile.TemporaryDirectory(prefix='pcc-display-groups-') as tmp:
 base=Path(tmp)
 for g,ms in enumerate(members):
  for j,bits in enumerate(itertools.product(['off','on'],repeat=len(ms))):
   root=base/f'g{g}c{j}';root.mkdir();seed(root,g,bits)
   expected='on' if 'on' in bits else 'off'
   r=run(root,g,'migrate');assert r.returncode==0,(g,bits,r.stderr);check(root,g,expected)
   before={p.name:p.stat().st_mtime_ns for p in root.iterdir()};r=run(root,g,'migrate');assert r.returncode==0
   assert before=={p.name:p.stat().st_mtime_ns for p in root.iterdir()};count+=1
   for mode in ['on','off']:
    r=run(root,g,mode);assert r.returncode==0,r.stderr;check(root,g,mode)
  root=base/f'missing{g}';root.mkdir();r=run(root,g,'migrate');assert r.returncode==0;check(root,g,'off')
  # Every member rename may fail after intent commit; next read must repair OFF, never OR back ON.
  for fail in ms:
   root=base/f'retry{g}-{fail}';root.mkdir();seed(root,g,['on']*len(ms))
   prefix='mv(){ case "${@: -1}" in */pikmin-'+fail+'.txt) return 9 ;; *) command mv "$@" ;; esac; }; '
   r=run(root,g,'off',prefix);assert r.returncode!=0
   assert master(root,g).read_text().strip()=='off'
   r=run(root,g,'migrate');assert r.returncode==0,r.stderr;check(root,g,'off')
  for invalid in ['junk','directory','master-junk']:
   root=base/f'bad{g}-{invalid}';root.mkdir();seed(root,g,['on']*len(ms))
   bad=master(root,g) if invalid=='master-junk' else file(root,ms[-1])
   if invalid=='directory':bad.unlink();bad.mkdir()
   else:bad.write_text('on\noff\n' if invalid=='junk' else 'unknown\n')
   before={p.name:p.read_bytes() for p in root.iterdir() if p.is_file()}
   for mode in ['off','migrate']:
    r=run(root,g,mode);assert r.returncode!=0
    assert before=={p.name:p.read_bytes() for p in root.iterdir() if p.is_file()};count+=1
 # Symlink rejection: execute on hosts with native symlink support; Windows may lack privilege.
 symlinks=0
 for broken in [False,True]:
  root=base/('symlink'+str(broken));root.mkdir();target=root/'target';target.write_text('on\n')
  if broken:target.unlink()
  try:file(root,members[0][0]).symlink_to(target)
  except OSError:
   assert os.name=='nt';print('WINDOWS LIMIT: native symlink privilege unavailable; Linux CI must execute these cases');break
  r=run(root,0,'on');assert r.returncode!=0 and not master(root,0).exists();symlinks+=1
 if os.name!='nt':assert symlinks==2
print('PASS production display-group shell',count,'legacy combinations/idempotence/ON-OFF/interrupted fan-out/preflight assertions;',symlinks,'native symlink cases; no device or game writes')
