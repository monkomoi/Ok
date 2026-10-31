# ASTRA TRADER V5.1 — APK & BUILD INTEGRITY AUDIT (`APK_AUDIT_REPORT.md`)

**Date:** 2026-10-30  
**Application ID:** `com.aistudio.astratrader.qmzxvp`  
**Namespace:** `com.example`  
**Target / Compile SDK:** `36` (`minSdk = 24`, compatible with Samsung Galaxy S7 Edge LineageOS Android 11 / API 30 `arm64-v8a`)

---

## 1. Build & Packaging Verification

1. **Gradle & Kotlin Compilation (`compile_applet`)**:
   - Compiles cleanly with zero errors across main and unit-test source sets.
   - Room KSP schema processor generates verified DAO and `AstraDatabase_Impl` implementations for version `7` (21 entities, 6 migrations).
2. **On-Device Forecast Bundle Packaging (`app/src/main/assets/forecast_bundle/`)**:
   - All 10 verified artifacts (`manifest.json`, `model.tflite`, `weights_compact.json`, `feature_schema.json`, `scaler.json`, `calibration.json`, `golden_feature_vectors.json`, `golden_outputs.json`, `evaluation_summary.json`, `SHA256SUMS`) are packaged under `assets/forecast_bundle/`.
   - Total `forecast_bundle/` asset footprint is `~620 KB` (`model.tflite` = `235 KB`), well within the S7 Edge `<= 8 MB` per-model and `<= 25 MB` total bundle budget.
   - Zero heavy native JNI `.so` bloat (`tensorflow-lite-select-tf-ops` is intentionally excluded), keeping the APK lightweight and 100% compatible with `arm64-v8a` on Samsung Galaxy S7 Edge.
3. **Manifest & Permissions Audit (`AndroidManifest.xml`)**:
   - Least-privilege permissions declared:
     - `android.permission.INTERNET`
     - `android.permission.ACCESS_NETWORK_STATE`
     - `android.permission.FOREGROUND_SERVICE`
     - `android.permission.FOREGROUND_SERVICE_DATA_SYNC`
     - `android.permission.POST_NOTIFICATIONS`
     - `android.permission.RECEIVE_BOOT_COMPLETED`
     - `android.permission.WAKE_LOCK`
   - `android:allowBackup="false"` and explicit `data_extraction_rules.xml` / `backup_rules.xml` prevent cloud backup extraction of encrypted vault preferences or SQLite journals.
4. **Secret & Signing Audit**:
   - Zero hardcoded Binance API keys, Binance secrets, Telegram bot tokens, or AI provider keys in source code, resources, or assets.
   - Runtime credential resolution strictly uses `SecretVault` (`AndroidKeyStore` AES-256-GCM) with all `BuildConfig.GEMINI_API_KEY` fallbacks removed (`P0-09`).
   - Signing configurations in `app/build.gradle.kts` remain untouched per platform security policy.
5. **R8 / ProGuard Configuration (`app/proguard-rules.pro`)**:
   - Preserves Room entities (`com.example.data.local.**`), domain models (`com.example.domain.**`, `com.example.forecast.**`), kotlinx.serialization generated serializers, and OkHttp/TLS internals.
