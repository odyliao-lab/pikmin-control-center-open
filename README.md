# Pikmin Control Center 公開原始碼

這是 Pikmin 控制中心的獨立原始碼快照，包含 Android 手機版、MuMu 版，以及定位擷取、自動化和 Health Connect 相容的原生程式碼。手機版程式版本為 0.33.2，核心程式版本為 1.21.1。遊戲和角色名稱屬於各自的權利人。本專案為非官方開發。

## 本機建置與測試

使用 Windows、PowerShell 7、Git for Windows（含 Bash）、JDK 17、Python 3、Android SDK Platform 35、Build Tools 35.0.0、NDK r27d（27.3.13750724）與 CMake 3.22.1。請先設定 `JAVA_HOME` 與 `ANDROID_HOME`。手機建置所需的 AndroidX 及其他程式庫會從公開 Maven 儲存庫取得，並依鎖定檔驗證 SHA-256。

```powershell
$native = @{
    NdkPath = "$env:ANDROID_HOME/ndk/27.3.13750724"
    CmakePath = "$env:ANDROID_HOME/cmake/3.22.1/bin/cmake.exe"
    NinjaPath = "$env:ANDROID_HOME/cmake/3.22.1/bin/ninja.exe"
}

pwsh -NoProfile -File android-app/tests/run-unit-tests.ps1
pwsh -NoProfile -File scripts/test-auto-petals.ps1
& ./android-app/build.ps1
& ./scripts/build-unified-native.ps1 @native
& ./scripts/build-mumu.ps1 -SdkPath $env:ANDROID_HOME -JavaHome $env:JAVA_HOME @native
& ./native/automation/zygisk-module/tests/run-host-tests.ps1 -NdkPath $native.NdkPath -BuildOnly
```

手機建置產生 `android-app/pikmin-control-center-unsigned.apk`，ARM64 核心另輸出至 `.tools/unified-native/`。MuMu 的未簽署 APK、x86_64 載入器與 ARM64 核心在 `.tools/build/mumu/`。這些產物供本機編譯驗證。手機 APK 需要正式簽章及配套核心才能作為更新包使用。MuMu 的畫面自動判讀需要額外的畫面辨識範本，此快照省略該資料。裝置安裝、核心載入和遊戲操作需另外驗證。

目前正式安裝包可從 [控制中心發布頁](https://github.com/odyliao-lab/pikmin-control-center-releases/releases) 下載。

## 授權

本專案原創程式碼採用 [MIT 授權](LICENSE)。第三方程式碼保留各自的授權與聲明。

- Magisk、And64InlineHook、xDL 和 Zygisk-Il2CppDumper 的來源與條款見 [GPS 原生元件聲明](native/gps-copy/THIRD_PARTY_NOTICES.md)、[自動化原生元件聲明](native/automation/THIRD_PARTY_NOTICES.md) 與 [MuMu 原生元件聲明](targets/mumu/native/bridge/LICENSE.reference)。
- [Dobby](magisk/cpp/vendor/dobby/LICENSE)、[LSPlant](magisk/cpp/vendor/lsplant/LICENSE)、[DexBuilder](magisk/cpp/vendor/lsplant/external/dex_builder/LICENSE) 與 [parallel-hashmap](magisk/cpp/vendor/lsplant/external/dex_builder/external/parallel_hashmap/LICENSE) 的授權隨各元件保留。
- AndroidX Health Connect 等建置依賴的聲明見 [Health Connect notices](android-app/assets/health-connect-notices.txt)。
