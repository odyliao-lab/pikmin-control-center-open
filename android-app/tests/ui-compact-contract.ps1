$ErrorActionPreference = 'Stop'
$app = Split-Path $PSScriptRoot -Parent
$main = Get-Content -Raw -LiteralPath (Join-Path $app 'src/dev/ody/pikmincontrol/MainActivity.java')
$count = 0
function Require([bool]$ok, [string]$label) {
    if (!$ok) { throw "FAIL: $label" }
    $script:count++
    Write-Output "PASS: $label"
}
Require (!$main.Contains('dispatchProgress')) 'no setup-page batch progress widget'
Require ($main.Contains('root.addView(safetyBar,') -and $main.Contains('safetyBar.addView(batchStop)') -and $main.Contains('safetyBar.addView(flowerFarmStop)')) 'both stops outside scrolling content'
Require ($main.Contains('if (recovery) confirmStopAll();')) 'interrupted work has actual cleanup recovery path'
Require ($main.Contains('WorkStatus.compact(this, GpsWorkLock.BATCH') -and $main.Contains('FarmUi ui = farmPresentation()')) 'both work pages use lifecycle summaries'
Require ($main.Contains('WorkStatus.summary(this, GpsWorkLock.BATCH') -and $main.Contains('flowerFarmHistory.setText(history)')) 'full lifecycle and counts retained in history'
Require (!$main.Contains('pairedButtons(shortcuts, secondaryButton("批次探險", v -> openExpedition(0))') -and $main.Contains('Button batchTab = expeditionTab') -and $main.Contains('Button farmTab = expeditionTab')) 'work actions only on expedition page, not duplicate overview shortcuts'
Require ($main.Contains('nearby.addView(gardenState("日常自動化"))') -and $main.Contains('overviewNearby = gardenAutomationRow') -and $main.Contains('overviewText = gardenAutomationRow') -and $main.Contains('overviewReturn = gardenAutomationRow') -and $main.Contains('overviewPlanter = gardenAutomationRow')) 'four independent automatic feature summaries retained in compact daily rows'
Require ($main.Contains('原生往返 ≤ 5 分鐘 · 不限距離')) 'time-only safety rule remains visible'
Require ($main.Contains('rewardsCard.addView(returnStatus)') -and $main.Contains('nurseryCard.addView(planterStatus)')) 'reward and nursery warnings outside disclosures'
Require ($main.Contains('PlanterSteps.LABELS.length') -and $main.Contains('NearbyKinds.DEFAULT')) 'all nursery filters and nearby defaults retained'
Require ($main.Contains('flowerFarmPoints.setMinLines(4)') -and $main.Contains('expanded ? 12 : 4')) 'compact input can expand without losing text'
Require ($main.Contains('pairedButtons(flowerFarmEditActions, flowerFarmExpand, flowerFarmClear)') -and $main.Contains('flowerFarmClear.setContentDescription("清除座標")')) 'clear beside expand below GPS input with accessible label'
Require ($main.Contains('UiSummary.startup(report)')) 'startup problem summary remains visible'
Require ($main.Contains('setButtonEnabled(scanButton, !scanning && !busy)')) 'work locks disable setup scan'
Require ($main.Contains('if (!subheading.isEmpty()) copy.addView(subtitle(subheading))')) 'no empty subtitle height'
Require (!$main.Contains('setEllipsize')) 'no truncation of safety text to force compactness'
$systemPage = [regex]::Match($main, '(?s)private void systemPage\(\).*?private void dispatchPage').Value
Require ($systemPage -match '(?s)heading\("系統", ""\);\s*updatesCard\(\);.*addCard\(appearanceBox\);.*addCard\(bootBox\);.*sectionLabel\("裝置與環境"\);\s*LinearLayout diagnostics = cardBox\(\);' -and ([regex]::Matches($systemPage, 'updatesCard\(\);')).Count -eq 1) 'one update card is first, followed by preferences, GPS and device environment'
Require ($systemPage.Contains('secondaryButton("新手機部署與修復"') -and $systemPage.Contains('secondaryButton("診斷與進階工具"')) 'deployment and debug actions are centralized in device environment'
Write-Output "PASS: $count compact UI source contracts (not device validation)"
Require ($main.Contains('tab("派遣", RETURN, "nav_return")')) 'return tab renamed without changing tab identity'
Require ($main.Contains('openDiagnostics()') -and !$main.Contains('collapsibleSection("小批次測試", testBox)') -and !$main.Contains('collapsibleSection("進階診斷", debug)')) 'developer batch controls moved out of everyday page'
Require ($main.Contains('final boolean readMetrics = false;')) 'old expanded state cannot reactivate hidden metrics polling'
Require ($main.Contains('flowerFarmMode = spinner') -and !$main.Contains('flowerRouteStart')) 'one primary farm start with saved mode selection'
Require ($main.Contains('checkpointButton("接續未完成批次"') -and $main.Contains('WorkCheckpoint cp=CheckpointStore.read(this,owner);') -and $main.Contains('pending=cp.remaining()>0&&!cp.uncertain;')) 'resume button requires an actual incomplete checkpoint with a known outcome'
Require ($main.Contains('if(afterStopped==null)FeedingGateway.stop(this);else FeedingGateway.pauseForInstall(this);')) 'temporary installer stop preserves desired feeding switches'
Require ($main.Contains('coreAdvancedTools=advanced;') -and !$main.Contains('disclosure(outer,"進階診斷與回退",advanced)')) 'core advanced controls centralized while recovery action remains inline'
Write-Output "PASS: $count compact and centralized UI source contracts (not device validation)"
$restore = Get-Content -Raw -LiteralPath (Join-Path $app 'src/dev/ody/pikmincontrol/SettingsRestoreJob.java')
Require ($restore.Contains('GpsWorkLock.acquire(c,"settings-restore")') -and $restore.Contains('GpsWorkLock.release(c,"settings-restore")')) 'settings restore uses an owned lease with final release'
$feeding = Get-Content -Raw -LiteralPath (Join-Path $app 'src/dev/ody/pikmincontrol/FeedingGateway.java')
Require (!$feeding.Contains('synchronized(GpsWorkLock.class)')) 'feeding restore does not hold the UI GPS monitor during root I/O'
