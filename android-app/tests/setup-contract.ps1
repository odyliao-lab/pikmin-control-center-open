$ErrorActionPreference = 'Stop'
$src = Join-Path (Split-Path $PSScriptRoot -Parent) 'src/dev/ody/pikmincontrol'
$deploy = Get-Content -Raw (Join-Path $src 'SetupDeployment.java')
$ui = Get-Content -Raw (Join-Path $src 'SetupActivity.java')
$lock = Get-Content -Raw (Join-Path $src 'GpsWorkLock.java')
$main = Get-Content -Raw (Join-Path $src 'MainActivity.java')
$count = 0
function Require([bool]$ok,[string]$label){if(!$ok){throw "FAIL: $label"};$script:count++;Write-Output "PASS: $label"}
Require ($main.Contains('new Intent(this, SetupActivity.class)') -and $main.Contains('SetupStore.offer(this)')) 'system entry and optional first-install offer'
Require ($ui.Contains('PhoneUpdateFlow.startSetup(this)') -and $ui.Contains('PhoneUpdateFlow.requestAdvance') -and !$ui.Contains('ACTION_OPEN_DOCUMENT')) 'single-page setup uses durable paired flow; legacy private bundle stays in historical MuMu UI'
Require ($ui.Contains('setNegativeButton') -and $ui.Contains('開始準備') -and $ui.Contains('PhoneUpdateFlow.startSetup(this)')) 'explicit setup consent before shared installation'
Require ($ui.Contains('new Intent(this,MainActivity.class).putExtra("open_updates",true)') -and $main.Contains('getBooleanExtra("open_updates", false)')) 'recovery link targets system update page'
Require ($main.Contains('renderCoreRecovery(recovery,status,text)') -and $main.Contains('coreStatus.setText(shown);outer.removeAllViews();') -and $main.Contains('renderCoreRecovery(outer,status,resultText)')) 'recovery actions refresh outer container after installer and rollback outcomes without page navigation'
Require ($main.Contains('new CorePanelState(') -and $main.Contains('if(!model.primary.isEmpty()&&manualCore)') -and $main.Contains('if(model.cancel&&manualCore)') -and $main.Contains('if(model.rollback&&manualCore)') -and $main.Contains('boolean manualCore=!PhoneUpdateFlow.active(this)||PhoneUpdateFlow.phase(this).equals("error");')) 'state policy controls manual recovery; unified update owns actions until error/exit'
Require (!$main.Contains('secondaryButton("核對核心版本／重開結果"') -and !$main.Contains('secondaryButton("檢查升級卡關原因"') -and !$main.Contains('primaryButton("接續升級本版核心"')) 'duplicate debugging and successor buttons removed'
Require ($main.Contains('generation!=coreRenderGeneration') -and $main.Contains('token!=corePollGeneration') -and $main.Contains('revision!=pageRevision')) 'stale render and polling results rejected'
Require ($main.Contains('stopAll(Runnable afterStopped,Runnable afterFailure)') -and $main.Contains('if(afterFailure!=null)runOnUiThread(afterFailure)')) 'stop failures restore recovery navigation'
Require ($deploy.Contains('GET_SIGNING_CERTIFICATES') -and $deploy.Contains('9080c5d8')) 'pinned signing identity before component verification'
Require ($deploy.IndexOf('putBoolean("install_review",true)') -lt $deploy.IndexOf('if(core)installOne')) 'durable intent before root install'
Require ($deploy.Contains('requireGameIdle();') -and $deploy.Contains('pidof com.nianticlabs.pikmin')) 'game and automation must be idle'
Require ($lock.Contains('SetupDeployment.pending(context)') -and $lock.Contains('SetupDeployment.pending(c)')) 'GPS and APK installation share deployment exclusion'
Require ($deploy.Contains('test ! -e /data/adb/modules/') -and !$deploy.Contains('pm install')) 'existing modules preserved and no silent APK install'
Require ($deploy.Contains('sha256sum') -and $deploy.Contains('before_boot') -and $deploy.Contains('modules_update/')) 'reboot and module evidence checked before release'
Require (!$ui.Contains('StartExpedition') -and !$ui.Contains('setMode(') -and !$deploy.Contains('remove-modules')) 'wizard does not dispatch or wipe modules'
Require ($deploy.Contains('if(running)throw') -and $deploy.Contains('missing>0')) 'partial install is reviewed not replayed'
foreach($file in @('GpsModuleGateway.java','JoystickStartup.java','EnvironmentGateway.java','DiagnosticsExporter.java')) {
    Require ((Get-Content -Raw (Join-Path $src $file)).Contains('JoystickAdapter.PACKAGE')) "shared provider: $file"
}
Write-Output "PASS: $count setup source contracts; NOT clean-device installation validation"

$observer=Get-Content -Raw (Join-Path $src 'PhoneConnectionService.java')
$flow=Get-Content -Raw (Join-Path $src 'PhoneUpdateFlow.java')
Require ($observer.Contains('START_NOT_STICKY') -and $observer.Contains('120_000') -and $observer.Contains('key.equals(PhoneUpdateFlow.observationKey(this))')) 'bounded consent-bound nonsticky observation'
Require (!$observer.Contains('closeGameAndInstall') -and !$observer.Contains('CoreUpdater.root(') -and !$observer.Contains('startActivity(') -and $flow.Contains('if(observationOnly && next!=PhoneUpdatePolicy.Next.REVIEW')) 'background observer cannot install reboot or launch game'
Require ($ui.Contains('PhoneConnectionService.start(this)') -and $ui.Contains('open(game)') -and $main.Contains('PhoneConnectionService.start(this)') -and $main.Contains('startActivity(game)')) 'setup and update share explicit foreground handoff'
Require (!$ui.Contains('Pikmin152') -and !$ui.Contains(' / 6') -and $ui.Contains('snapshot.summary()')) 'obsolete six-step/version prose removed; environment comes from current probe'
