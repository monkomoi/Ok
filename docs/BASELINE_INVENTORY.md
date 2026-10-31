# ASTRA TRADER — PHASE 0 BASELINE INVENTORY (`docs/BASELINE_INVENTORY.md`)

**Ngày kiểm kê:** 2026-10-30  
**Mục tiêu thiết bị:** Samsung Galaxy S7 Edge (`arm64-v8a`, `4 GB RAM`, `64 GB ROM`, `LineageOS Android 11 / API 30`)

---

## 1. Thông tin Định danh & Phiên bản Hệ thống (Baseline)

| Hạng mục | Giá trị thực tế trong Source đầu vào |
|---|---|
| **`applicationId`** | `com.aistudio.astratrader.v51qnt` |
| **Kotlin Namespace / Package** | `com.example` |
| **`versionCode` / `versionName`** | `1` / `"1.0"` |
| **`minSdk` / `targetSdk` / `compileSdk`** | `24` / `36` / `36 (minorApiLevel = 1)` |
| **Android Gradle Plugin (AGP)** | `9.1.1` |
| **Kotlin Compiler / KSP** | `2.2.10` / `2.3.5` |
| **Gradle Runtime** | `9.3.1` (`gradle-wrapper.properties` có sẵn; thiếu `gradlew`, `gradlew.bat`, `gradle-wrapper.jar` ở baseline) |
| **JDK Runtime** | `Eclipse Adoptium 21.0.13+9-LTS` (`sourceCompatibility = JavaVersion.VERSION_11`) |
| **Compose BOM / Room / Coroutines / OkHttp** | `2024.09.00` / `2.7.0` / `1.10.2` / `4.10.0` |
| **Python Runtime (Off-Device Pipeline)** | `Python 3.11.2` (Standard Library + `urllib.request`) |
| **SHA-256 Debug APK Baseline (`.build-outputs/app-debug.apk`)** | `2f7a6e56ee536dc0a733680de5921e55c95152148ee322997a743e56d6b13b57` |

---

## 2. Kích thước Thư mục & Top 30 File Lớn Nhất (Baseline)

### 2.1. Kích thước theo thư mục gốc
- `app/`: `99 MB` (bao gồm `app/build` cache)
- `public/`: `53 MB` (chứa các file ZIP tham khảo Freqtrade/Hummingbot)
- `.build-outputs/`: `18 MB` (`app-debug.apk`)
- `ml/`: `114 KB` (pipeline huấn luyện & kiểm thử Python)
- `gradle/`: `7.5 KB`

### 2.2. Top 30 file lớn nhất (ngoài `.gradle/` và `build/`)
1. `public/assets/aistudio/freqtrade-2026.10_073b7d8b.zip` — `48 MB` (`SHA-256: 073b7d8bd935218ba71ba4085a0fc7714a1c8e9a7b1d5fca076caee676bcff90`)
2. `.build-outputs/app-debug.apk` — `18 MB` (`SHA-256: 2f7a6e56ee536dc0a733680de5921e55c95152148ee322997a743e56d6b13b57`)
3. `public/assets/aistudio/hummingbot-2.17.0_e50d20be.zip` — `3.9 MB` (`SHA-256: e50d20beb0834a76a6b02ecd412f9be1b679ee7481b6f0e81020b8a4238638bc`)
4. `app/src/main/assets/forecast_bundle/golden_feature_vectors.json` — `304 KB`
5. `app/src/main/java/com/example/engine/AstraTradingCoordinator.kt` — `182 KB`
6. `app/src/main/java/com/example/network/BinanceAdapter.kt` — `166 KB`
7. `public/assets/aistudio/Astra_Trader_Master_Spec_v5_1_9ab25775.md` — `135 KB`
8. `public/assets/aistudio/ASTRA_TRADER_V5_1_FULL_REMEDIATION_UPGRADE_RUNBOOK_4e174a06.md` — `98 KB`
9. `app/src/main/assets/forecast_bundle/weights_compact.json` — `90 KB`
10. `app/src/test/java/com/example/ExampleRobolectricTest.kt` — `77 KB`
11. `app/src/main/java/com/example/ui/screens/AiApiCheckerCard.kt` — `75 KB`
12. `public/assets/aistudio/Freqtrade_01_Binance_Exchange_Reference_7b65302c.zip` — `66 KB` (`SHA-256: 7b65302cf7854a0bfdd6a33248e2397b4511ae5df5622f9db4e776da9222c677`)
13. `app/src/main/res/font/inter.ttf` — `66 KB`
14. `app/src/main/java/com/example/quant/QuantMathEngine.kt` — `61 KB`
15. `app/src/main/res/font/jetbrains_mono.ttf` — `57 KB`
16. `public/assets/aistudio/ASTRA_TRADER_REMEDIATION_MASTER_TASK_469c6657.md` — `55 KB`
17. `app/src/test/java/com/example/ExampleUnitTest.kt` — `54 KB`
18. `public/assets/aistudio/ASTRA_TRADER_FORECASTING_ON_DEVICE_RUNBOOK_S7_EDGE_4bee9648.md` — `51 KB`
19. `app/src/main/java/com/example/ui/screens/OverviewTabScreen.kt` — `50 KB`
20. `app/src/main/java/com/example/domain/DomainModels.kt` — `48 KB`
21. `app/src/main/java/com/example/ui/AstraViewModel.kt` — `43 KB`
22. `public/assets/aistudio/Astra_Trader_Full_Audit_Remediation_Prompt_79653495.md` — `41 KB`
23. `public/assets/aistudio/ASTRA_TRADER_GEHIHI_BUILD_REMEDIATION_TASK_25db1cb9.md` — `40 KB`
24. `app/src/main/java/com/example/ui/screens/ApiIntegrationTabScreen.kt` — `37 KB`
25. `app/src/main/assets/forecast_bundle/model.tflite` — `37 KB`
26. `app/src/main/java/com/example/ai/AiIntelligenceGateway.kt` — `36 KB`
27. `public/assets/aistudio/aiai_5b5f22f2.py` — `33 KB`
28. `app/src/main/res/font/space_grotesk.ttf` — `32 KB`
29. `app/src/main/java/com/example/ai/AiApiCheckerEngine.kt` — `32 KB`
30. `app/src/main/java/com/example/ui/screens/StrategyRiskTabScreen.kt` — `30 KB`

---

## 3. Kiểm kê Kiến trúc, Packages, Dependencies & Trạng thái Thực tế

### 3.1. Android Packages (`app/src/main/java/com/example/`)
- `ai/`: `AiApiCheckerEngine.kt` (hỗ trợ 9 AI providers qua OkHttp thật), `AiIntelligenceGateway.kt` (advisory JSON parser + fail-closed validation).
- `data/local/`: `Entities.kt`, `AstraDao.kt`, `AstraDatabase.kt` (Room v7 với `MIGRATION_1_6` .. `MIGRATION_6_7`).
- `domain/`: `DomainModels.kt`.
- `engine/`: `AstraTradingCoordinator.kt` (`globalCapitalMutex`, Panic Close 6-step FSM, Reconcile, Risk Gate, Execution Gate).
- `forecast/`:
  - `data/`: `MarketSequenceRepository.kt`, `ForecastFeatureSchema.kt`, `ForecastFeaturePipeline.kt`, `ForecastScaler.kt`
  - `domain/`: `ForecastModels.kt`, `ForecastRepository.kt`
  - `engine/`: `ForecastCalibrationEngine.kt`, `ForecastDriftMonitor.kt`, `ForecastEnsemble.kt`, `ForecastQualityGate.kt`, `ForecastCoordinator.kt`
  - `runtime/`: `ForecastModelRuntime.kt`, `ForecastModelLoader.kt`, `ForecastModelRegistry.kt`, `LiteRtForecastRuntime.kt`
  - `service/`: `ForecastScheduler.kt`
- `network/`: `BinanceAdapter.kt` (OkHttp REST + WebSocket cho Binance USDM Futures Testnet/Live).
- `quant/`: `QuantMathEngine.kt`, `NewsEventGateEngine.kt`.
- `security/`: `SecretVault.kt` (AES-256-GCM qua Android Keystore).
- `service/`: `TradingForegroundService.kt`, `BootRecoveryReceiver.kt`.
- `telegram/`: `TelegramBotController.kt` (long-polling `getUpdates` + whitelist + command idempotency).
- `ui/`: `AstraViewModel.kt`, `screens/` (`OverviewTabScreen.kt`, `ForecastOverviewCards.kt`, `StrategyRiskTabScreen.kt`, `ApiIntegrationTabScreen.kt`, `AiApiCheckerCard.kt`, `HistoryAnalyticsTabScreen.kt`, `SystemLogsTabScreen.kt`).

### 3.2. Phát hiện Kỹ thuật Nghiêm trọng tại Baseline (Cần sửa trong Phase 1–7)
1. **Dữ liệu Huấn luyện (`ml/forecasting/src/fetch_klines.py`) — `MOCK / SYNTHETIC`**:
   - Baseline chỉ sinh chuỗi `DOGEUSDT` giả lập bằng `sin/cos/drift`. Chưa tải dữ liệu nến thật (`BTCUSDT`, `ETHUSDT`, `SOLUSDT`, `DOGEUSDT`) từ Binance Public API (`https://data-api.binance.vision/api/v3/klines`).
   - `aggregate_higher_timeframe` gom mỗi 4/16 nến từ phần tử đầu tiên mà không kiểm tra căn biên UTC (`00:00, 04:00, 08:00, 12:00, 16:00, 20:00 UTC`).
2. **Huấn luyện Mạng Neural & Walk-Forward (`train_gru.py`, `train_tcn.py`, `export_litert.py`) — `PARTIAL`**:
   - `train_gru.py` và `train_tcn.py` khởi tạo trọng số core cố định (`init_gru_weights`, `init_tcn_weights`) rồi chỉ cập nhật linear head (`W_cls`, `w_ret`, `w_vol`), chưa huấn luyện end-to-end (BPTT / analytical backprop qua encoder).
   - `export_litert.py` tạo 4 walk-forward folds nhưng chỉ lấy `folds[-1]` để huấn luyện và đánh giá.
3. **Lỗi Backtest Equity (`ml/forecasting/src/evaluate.py` dòng 79–80) — `BUG P0`**:
   - Câu lệnh `if trades_net_returns: equity *= (1.0 + trades_net_returns[-1] * 0.5)` nằm ngoài khối `if/elif` vào lệnh, khiến mọi nến không giao dịch sau lệnh đầu tiên vẫn bị nhân dồn lợi nhuận của lệnh trước đó.
4. **Runtime Inference & Bundle (`model.tflite` vs `LiteRtForecastRuntime.kt`) — `AMBIGUOUS`**:
   - `export_litert.py` tạo `model.tflite` bằng cách ghép header 16-byte `TFL3` vào chuỗi JSON, còn `LiteRtForecastRuntime.kt` đọc `weights_compact.json` để chạy tính toán ma trận bằng Kotlin.
   - `golden_feature_vectors.json` (`304 KB`) và `evaluation_summary.json` đang nằm trong `app/src/main/assets/forecast_bundle/` làm tăng kích thước APK không cần thiết.
   - `ForecastModelLoader.kt` dùng `runBlocking` và tạo 2 instance `LiteRtForecastRuntime` khi load.
5. **Outcome Resolution (`ForecastCoordinator.kt` dòng 299–332) — `STUB / NOT CONNECTED`**:
   - `evaluatePreviousPredictionOutcomeIfEligibleLocked` gán `val prevPred = null` nên không bao giờ gọi `repository.recordRealizedOutcomeAndRefreshMetrics(...)` khi nến đóng mới xuất hiện!
6. **Thermal / RAM Defaults (`ForecastCoordinator.kt` dòng 436–437)**:
   - Dùng giá trị giả `33.5f` (°C) và `1450L` (MB) khi `deviceHealth == null` thay vì trạng thái `UNKNOWN` (`null` / `-1`).
7. **Dependencies & Archives dư thừa**:
   - `app/build.gradle.kts` khai báo `alias(libs.plugins.google.services)` và `implementation(platform(libs.firebase.bom))` nhưng toàn bộ app không sử dụng Firebase.
   - `public/assets/aistudio/` chứa `freqtrade-2026.10_073b7d8b.zip` (`48 MB`) và `hummingbot-2.17.0_e50d20be.zip` (`3.9 MB`).

---

## 4. Sơ đồ Luồng Thực tế tại Baseline

```
Market Data (BinanceAdapter REST/WS)
   │  [CONNECTED - Real REST/WS Klines, Depth, Funding, MarkPrice]
   ▼
Validation (MarketSequenceRepository)
   │  [PARTIAL - Checks closed/monotonic/OHLC, but 4h aggregation lacked strict UTC 4h boundary check]
   ▼
Features (ForecastFeaturePipeline)
   │  [CONNECTED - 26 causal features, Python/Kotlin parity < 1e-5f]
   ▼
Forecast Runtime (LiteRtForecastRuntime -> CompactKotlinForecastRuntime)
   │  [PARTIAL - Kotlin matrix runtime works deterministically, but named LiteRt & bundled fake TFL3 file]
   ▼
Calibration / Quality Gate (ForecastCalibrationEngine + ForecastQualityGate + ForecastDriftMonitor)
   │  [CONNECTED - Temperature scaling, OOD gate, cost threshold gate]
   ▼
Strategy Candidate (QuantMathEngine + AstraTradingCoordinator)
   │  [CONNECTED - Multi-Factor Regime + Forecast Advisory Multiplier in TEST/SHADOW]
   ▼
Risk Gate (AstraTradingCoordinator.validatePreTradeRiskGate)
   │  [CONNECTED - Kill-switch, drawdown, stale feed, leverage & margin checks]
   ▼
Order Intent (Room order_intents + OrderIntentStatus FSM)
   │  [CONNECTED - Write-ahead intent before network submit]
   ▼
Exchange Execution (BinanceAdapter TESTNET / Safety Gate)
   │  [CONNECTED - Hard-blocked from LIVE when safetyDebugLockActive=true]
   ▼
Reconciliation (AstraTradingCoordinator.performStartupReconciliation)
   │  [CONNECTED - Reconciles positions, open orders, SL/TP algo orders]
   ▼
Outcome Feedback (ForecastCoordinator.evaluatePreviousPredictionOutcomeIfEligibleLocked)
      [NOT CONNECTED / STUB at Baseline - `prevPred = null` stub; must be wired to resolve 15m/1h/4h outcomes idempotently]
```
