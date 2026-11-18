$ErrorActionPreference = 'Stop'
$app = Split-Path $PSScriptRoot -Parent
$src = Join-Path $app 'src/dev/ody/pikmincontrol'
$controller = Get-Content (Join-Path $src 'UpdateController.java') -Raw -Encoding UTF8
$installer = Get-Content (Join-Path $src 'UpdateInstaller.java') -Raw -Encoding UTF8
$store = Get-Content (Join-Path $src 'UpdateStore.java') -Raw -Encoding UTF8
$manifest = Get-Content (Join-Path $app 'AndroidManifest.xml') -Raw -Encoding UTF8
$build = Get-Content (Join-Path $app 'build.ps1') -Raw -Encoding UTF8
$flow = Get-Content (Join-Path $src 'PhoneUpdateFlow.java') -Raw -Encoding UTF8
$main = Get-Content (Join-Path $src 'MainActivity.java') -Raw -Encoding UTF8
$preparation = Get-Content (Join-Path $src 'UpdatePreparation.java') -Raw -Encoding UTF8
$checks = @(
    @($store.Contains('getBoolean("automatic",true)'), 'automatic checks default on'),
    @($store.Contains('"pikmin_updates"'), 'update preferences isolated'),
    @($controller.Contains('if(!manual&&!UpdateCheckPolicy.due('), 'manual bypasses automatic schedule'),
    @(($controller.Contains('info.code<previous.code') -and $controller.Contains('info.code==previous.code&&!info.sha256.equals(previous.sha256)')), 'metadata rollback and same-code replacement blocked'),
    @($controller.Contains('MessageDigest.isEqual(a,b)'), 'installed signer compared'),
    @($controller.Contains('info.code<=current.getLongVersionCode()'), 'APK downgrade refused'),
    @(($controller -match 'putString\("download_sha",info.sha256\).*\.commit\(\)'), 'download completion durably committed'),
    @($installer.Contains('UpdateController.verify(c,UpdateController.apk(c),info)'), 'APK verified again before installation'),
    @(($installer.Contains('synchronized(GpsWorkLock.class)') -and $installer.Contains('GpsWorkLock.requireInstallIdle(c)')), 'installer shares GPS exclusion monitor'),
    @(($installer.Contains('canRequestPackageInstalls()') -and $installer.Contains('USER_ACTION_NOT_REQUIRED') -and
        $manifest.Contains('android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION')), 'system source permission and documented self-update opt-in'),
    @($installer.Contains('abandonSession(id)'), 'pending session can be canceled'),
    @(($manifest -match 'UpdateInstallActivity" android:exported="false"'), 'installer callback not exported'),
    @($manifest.Contains('android:usesCleartextTraffic="false"'), 'cleartext traffic disabled'),
    @((!$build.Contains('-genkeypair') -and $build.Contains('Signing certificate differs')), 'build cannot silently create replacement signing key'),
    @(($main.Contains('primaryButton("立即更新",v->continuePhoneUpdate())') -and !$main.Contains('updateDownload=')), 'single main update action replaces download/install split'),
    @(($main.Contains('UpdateInfo consent=displayedUpdate') -and $main.Contains('PhoneUpdateFlow.start(this,consent)') -and
        $main.Contains('updateConsent.setVisibility') -and !$main.Contains('.setTitle("更新至 "+info.version')), 'one visible consent bound to the displayed version'),
    @(($controller.IndexOf('bindPartial(c,part,info)') -lt $controller.IndexOf('UpdateClient.downloadResuming(info,part') -and
        $controller.Contains('info.code+":"+info.size+":"+info.sha256') -and
        $controller.IndexOf('verify(c,part,info)') -lt $controller.IndexOf('part.renameTo(apk(c))')), 'partial bytes bound to release and verified before admission'),
    @(($main.Contains('if(updateVisible)PhoneUpdateFlow.requestAdvance(getApplicationContext())') -and $main.Contains('updateVisible=false;') -and $flow.Contains('queued.compareAndSet(false,true)') -and $flow.Contains('static final java.util.concurrent.ExecutorService worker')), 'foreground admission and process worker survive Activity recreation'),
    @($main.Contains('disclosure(box,"進階核心維修（一般更新不需操作）",recovery)'), 'manual core workflow is progressively disclosed'),
    @(($store.Contains('PhoneUpdateFlow.manifest(c)') -and $controller.Contains('UpdateInstaller.pending(c)||PhoneUpdateFlow.active(c)')), 'consented manifest pinned; checks cannot replace active target'),
    @(($installer.IndexOf('PhoneUpdateFlow.apkIntent(c)') -gt $installer.IndexOf('UpdatePreparation.requireReady(c)') -and $installer.IndexOf('PhoneUpdateFlow.apkIntent(c)') -lt $installer.IndexOf('installer.createSession(params)')), 'APK intent follows preparation and precedes session creation'),
    @($installer.Contains('PhoneUpdateFlow.apkEnded(c);UpdatePreparation.installedOrCancelled(c)'), 'Android cancellation ends consent before old-version restore'),
    @(($preparation.IndexOf('if(PhoneUpdateFlow.holdRestore(c)||PhoneUpdateFlow.restoreExhausted(c))return false;', $preparation.IndexOf('static boolean restore(')) -gt 0), 'paired flow holds restoration until core proof'),
    @(($flow.Contains('target.sha256.equals(UpdateClient.digest(new java.io.File(c.getApplicationInfo().sourceDir)))') -and $flow.Contains('CoreUpdater.readPair(c,"core.properties")')), 'installed APK digest and bundled core match consent'),
    @(($flow.IndexOf('stage(c,"core-intent"') -lt $flow.IndexOf('CoreInstallWizard.closeGameAndInstall(c)')), 'core intent is durable before lower transaction'),
    @((!$flow.Contains('root("reboot")') -and !$flow.Contains('pm install') -and !$flow.Contains('startActivity(')), 'coordinator cannot reboot, silently install APK or launch game'),
    @(($flow.Contains('SettingsRestoreJob.restoreForPid(c,pid)') -and $flow.Contains('if(attempts>=24)throw waiting')), 'fresh restoration and bounded review; no success on install alone'),
    @((!$preparation.Contains('NearbyWorkState.discard') -and !$preparation.Contains('WorkStatus.recovered')), 'unified preparation retains pending work and owner evidence')
)
foreach ($check in $checks) { if (!$check[0]) { throw "FAIL: $($check[1])" }; Write-Output "PASS: $($check[1])" }
Write-Output "PASS: $($checks.Count) updater source contracts (not full system-installer verification)"
