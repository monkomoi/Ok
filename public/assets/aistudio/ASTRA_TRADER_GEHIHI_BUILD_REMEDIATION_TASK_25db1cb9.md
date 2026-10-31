# ASTRA TRADER — GEHIHI BUILD & ENGINEERING TASK

**Mục tiêu:** yêu cầu Gehihi tiếp quản source hiện tại, sửa lỗi, hoàn thiện Forecasting, tối ưu Android, dọn dependency/tài nguyên không cần thiết, build và kiểm thử một bản APK native ổn định cho **Samsung Galaxy S7 Edge — LineageOS Android 11 — RAM 4 GB — ROM 64 GB**.

**Source đầu vào:** ZIP mới nhất người dùng cung cấp, có `ml/forecasting/`, các module `com.example.forecast.*`, `FORECASTING_GAP_ANALYSIS.md`, các report và `public/assets/aistudio/`. Không lấy source hoặc report của phiên bản cũ làm chuẩn thay cho source đầu vào.

---

## 0. CHỈ THỊ TỔNG QUÁT

Bạn phải làm việc như một **Senior Android/Kotlin Engineer + Quant/ML Engineer + QA/SRE**. Nhiệm vụ là sửa và build ứng dụng thật, không phải chỉ viết thêm tài liệu hoặc tạo file placeholder.

### 0.1. Quy tắc bắt buộc

1. Đọc source, Gradle, Manifest, tài liệu và code liên quan trước khi sửa.
2. Tạo bản sao/branch làm việc. Không ghi đè bản gốc và không xóa dữ liệu người dùng.
3. Không mặc định report cũ đúng. Mọi tuyên bố phải có bằng chứng từ source, build log, test log hoặc thiết bị.
4. Không tạo TODO, stub, fake implementation, mock data hoặc hàm trả giá trị mặc định rồi tuyên bố hoàn thành.
5. Chức năng chưa triển khai/thiếu API/thiếu phần cứng phải ghi `NOT IMPLEMENTED` hoặc `BLOCKED`, không giả vờ đã chạy.
6. Synthetic data chỉ dùng unit test/demo có gắn nhãn, không dùng để chứng minh dự báo thị trường thật.
7. Không bật LIVE, không gửi lệnh tiền thật, không sử dụng API key thật trong quá trình sửa/build/test.
8. Mọi thay đổi giao dịch phải kiểm thử Testnet/Mock Exchange, fault injection và safety gate.
9. Không xóa dependency/module/thuật toán/tài liệu/test chỉ để giảm APK. Phải xác minh usage, giá trị, tác động và build sau khi bỏ.
10. Không thêm Chaquopy/Python Interpreter vào APK chỉ vì source có file `.py`. Python dùng cho thu thập dữ liệu, training và evaluation trên PC/VPS; Android native dùng Kotlin và runtime inference phù hợp.
11. Không ép mọi mô hình vào S7 Edge. Ưu tiên một model nhỏ, tốt ngoài mẫu và ổn định.
12. Mỗi Phase phải có thay đổi source, test, log, tiêu chí PASS/FAIL, file đã sửa và vấn đề còn lại.
13. Không dừng ở kế hoạch. Hãy thực thi, build, test và bàn giao kết quả.
14. Nếu hướng kỹ thuật không khả thi, nêu bằng chứng, chọn giải pháp thay thế và triển khai trong khả năng, không trì hoãn vì câu hỏi không thiết yếu.

Ưu tiên: `Correctness > Safety > Reliability > Reproducibility > Performance > APK size > Feature count`.

### 0.2. Mục tiêu build

- Android native Kotlin/Jetpack Compose.
- Chạy được Android 11/API 30 trên S7 Edge; không nâng `minSdk` vượt khả năng thiết bị.
- Forecast inference local; không bắt buộc Internet sau khi dữ liệu/model cần thiết đã sẵn sàng.
- Python training pipeline chạy ngoài điện thoại.
- CPU là backend inference bắt buộc; GPU/delegate chỉ là tùy chọn sau benchmark.
- Có Debug và Release build đúng cấu hình.
- Mặc định `SHADOW`; không tự chuyển LIVE.

---

# PHASE 0 — BASELINE VÀ KIỂM KÊ

## 1. Kiểm kê source

Tạo `docs/BASELINE_INVENTORY.md`, ghi:

- SHA-256 ZIP đầu vào.
- `applicationId`, package, versionName/versionCode, minSdk/targetSdk/compileSdk.
- Kotlin, AGP, Gradle, JDK, Compose, Room versions.
- Android packages, Python pipeline, tests, assets và dependencies.
- Kích thước thư mục, top 30 file lớn nhất.
- Model/scaler/schema/calibration/manifest/checksum đang có.
- Exchange/API/provider được dùng.
- Thành phần nào có code thật, stub, fake, dead code hoặc chưa nối runtime.

Dựng sơ đồ luồng thực tế:

`Market Data -> Validation -> Features -> Forecast -> Calibration/Quality Gate -> Strategy -> Risk -> Order Intent -> Exchange -> Reconciliation -> Outcome Feedback`.

Đánh dấu mỗi cạnh `CONNECTED`, `PARTIAL`, `NOT CONNECTED` hoặc `MOCK`.

## 2. Baseline build/test

Trước khi sửa:

- Kiểm tra có `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` và `gradle-wrapper.properties` không.
- Kiểm tra Python version, dependency lock/requirements và lệnh test.
- Đếm test bằng test discovery thực tế, không lấy số trong report.
- Chạy test hiện có, lưu log nguyên bản.
- Chạy Debug và Release build nếu môi trường cho phép.
- Ghi rõ lệnh không chạy được và nguyên nhân.
- Nếu thiếu wrapper, bổ sung wrapper tương thích AGP/Kotlin hiện tại; không tùy tiện nâng đồng loạt các version.

Tạo `docs/BASELINE_BUILD_AND_TEST.md` gồm lệnh, kết quả, thời gian, log/artifact và lỗi. Chỉ sửa code trước khi baseline xong nếu lỗi ngăn cản việc kiểm kê.

---

# PHASE 1 — DỌN SOURCE, ASSETS VÀ DEPENDENCIES CÓ KIỂM SOÁT

## 3. Chuyển Freqtrade và Hummingbot ra khỏi gói source chính

Trong `public/assets/aistudio/` hiện có archive lớn:

- `freqtrade-2026.10_*.zip`.
- `hummingbot-2.17.0_*.zip`.

Thực hiện:

1. Xác minh không có code runtime/build script nào đọc hoặc phụ thuộc hai ZIP này.
2. Gỡ chúng khỏi source package/repository chính và tuyệt đối không đưa vào `app/src/main/assets/`.
3. Nếu còn cần tham khảo, chuyển sang gói `optional-references/` riêng hoặc lưu trữ bên ngoài source chính. Build app không được phụ thuộc vào chúng.
4. Ghi nguồn, license và mục đích tham khảo nếu giữ lại; không sao chép code dự án khác vào Astra khi chưa kiểm tra license.
5. Kiểm tra `Freqtrade_01_Binance_Exchange_Reference_*.zip` và các ghi chú Freqtrade liên quan: chỉ giữ trong khu vực reference nếu có giá trị, không bundle trong APK.
6. Chuyển hóa ghi chú kỹ thuật thực sự hữu ích thành tài liệu ngắn trong `docs/references/` thay vì giữ nguyên archive cồng kềnh.

Kết quả: source chính gọn hơn, APK không chứa ZIP tham khảo, build độc lập với Freqtrade/Hummingbot.

## 4. Phân loại mọi artifact còn lại: GIỮ / DI CHUYỂN / TỐI ƯU / XÓA

Tạo `docs/ARTIFACT_DISPOSITION.md`; từng mục phải có usage, lý do, tác động và test sau thay đổi. Không xóa đồng loạt.

### 4.1. Python AI API Checker

Đối chiếu `public/assets/aistudio/aiai_*.py` với Kotlin `AiApiCheckerEngine.kt`:

- Giữ Python checker trong `tools/` nếu hữu ích cho developer kiểm tra provider trên PC.
- Không đóng gói Python checker vào APK nếu app đã có Kotlin checker.
- So sánh provider support, auth, model discovery, latency, timeout, streaming, error parsing và endpoint tùy chỉnh.
- Kotlin là implementation native trong app; Python là developer tool độc lập.
- Tránh hai implementation lệch behavior. Tạo test vectors dùng chung nếu khả thi.
- Nếu Python tool trùng chức năng và không có use case độc lập, chuyển khỏi source chính hoặc xóa sau khi ghi nhận.

### 4.2. Markdown/spec/runbook/report

- Giữ Master Spec, Remediation Runbook, Forecasting Runbook, Architecture, Security và Operations nếu chúng còn là tài liệu chuẩn.
- Chuyển khỏi `public/assets/aistudio/` sang `docs/` hoặc `docs/archive/`.
- Không đóng gói prompt/report/audit lịch sử vào APK nếu app không đọc chúng.
- Chọn một tài liệu chuẩn hiện hành; tài liệu cũ ghi `SUPERSEDED` và link bản mới.
- Viết lại README thành hướng dẫn build/install/test/run thực tế, không để template Google AI Studio.
- Đồng bộ mọi số liệu report với source/test thực tế.

### 4.3. Forecast bundle trong `app/src/main/assets/forecast_bundle/`

Quyết định theo runtime production:

- `model.tflite`: chỉ giữ khi là FlatBuffer LiteRT/TFLite hợp lệ, runtime load thật và inference thành công.
- `weights_compact.json`: chỉ giữ nếu chọn custom Kotlin runtime; nếu chọn LiteRT thì chỉ giữ khi có fallback được kiểm thử thật.
- `feature_schema.json`, `scaler.json`, `calibration.json`, `manifest.json`, `SHA256SUMS`: giữ các file thực sự cần để inference tái lập được.
- `golden_feature_vectors.json`, `golden_outputs.json`: chuyển ưu tiên sang test assets; chỉ giữ một bộ nhỏ trong production khi có lý do rõ ràng.
- `evaluation_summary.json`: chuyển sang `docs/reports/` hoặc diagnostics tải theo yêu cầu nếu UI/runtime không dùng.
- Không xóa schema/scaler/calibration đang được runtime dùng.
- Không giữ file TFLite giả chỉ có header `TFL3`.

### 4.4. Material Icons Extended

Không xóa theo cảm tính:

1. Quét toàn bộ imports và usage.
2. Kiểm tra R8 có loại được class/icon không tham chiếu không.
3. Build Release trước/sau, đo DEX, APK và startup.
4. Nếu thực sự gây phình lớn và chỉ dùng ít icon, thay bằng subset/vector drawable phù hợp.
5. Giữ icon đang dùng, chụp/kiểm tra tất cả màn hình sau thay đổi.

### 4.5. Firebase, Google Services, Secrets Gradle Plugin

Không xóa chỉ vì chưa thấy ngay:

- Search imports, plugins, Manifest metadata, `google-services.json`, Firebase initialization, analytics/crash reporting/remote config và secret properties.
- Nếu có feature production dùng thật: giữ và kiểm thử.
- Nếu dependency/plugin khai báo nhưng không có callsite/config/use case: bỏ cùng cấu hình liên quan, rồi build/test.
- Không để API secret, Telegram token, signing key hoặc exchange key trong source, assets, BuildConfig, log hay APK.
- Không commit secret thật; chỉ để template rỗng `.env.example`.

### 4.6. Tests và fixtures

- Không xóa Unit/Robolectric/Instrumented tests để làm APK nhỏ.
- Test source/fixtures đặt đúng source set, không đóng gói production.
- Xóa fixture trùng/chết chỉ sau khi chứng minh không test nào dùng.
- Mỗi lỗi sửa phải có regression test.

### 4.7. Deliverables

- `docs/ARTIFACT_DISPOSITION.md`.
- `docs/DEPENDENCY_AUDIT.md`.
- Dung lượng trước/sau.
- Danh sách file giữ/di chuyển/xóa và lý do.
- Build thành công sau từng nhóm thay đổi.

---

# PHASE 2 — SỬA FORECASTING PIPELINE CHO ĐÚNG

## 5. Thay dữ liệu giả bằng dữ liệu thị trường thật

`ml/forecasting/src/fetch_klines.py` ở baseline tạo synthetic DOGEUSDT bằng sin/cos/drift. Dataset đó chỉ được dùng test, không được dùng kết luận model dự báo thị trường thật.

Tạo data provider interface, ví dụ:

- `KlineDataProvider`.
- `BinancePublicKlineProvider`.
- `HistoricalFileProvider`.
- `SyntheticTestProvider` chỉ trong test.

Bộ thu thập phải:

1. Dùng Binance public Klines endpoint phù hợp, không yêu cầu API key nếu không cần.
2. Hỗ trợ symbol cấu hình được; tối thiểu BTCUSDT, ETHUSDT và một số altcoin có thanh khoản. Không hardcode một DOGE series làm đại diện.
3. Hỗ trợ 15m, 1h, 4h; chỉ thêm 1m/5m khi data contract, rate limit và storage được thiết kế.
4. Phân trang để tải lịch sử; không giả định một request lấy được toàn bộ.
5. Lưu open/close time, OHLCV, quote volume, trade count và taker-buy volume nếu nguồn có; lưu symbol, interval, source, fetch time.
6. Timestamp UTC epoch; đổi timezone chỉ tại UI.
7. Retry exponential backoff + jitter; xử lý 429/418/5xx và `Retry-After`.
8. Incremental update/checkpoint, không tải lại toàn bộ mỗi lần.
9. Không lấp nến thiếu bằng số ngẫu nhiên; đánh dấu gap và xử lý theo policy.
10. Lưu raw data trước feature engineering để tái lập pipeline.
11. Ghi dataset version, nguồn và checksum.
12. Test pagination, duplicate, timestamp boundary, retry và missing candles.

### Dataset validation

`validate_dataset.py` phải kiểm tra:

- Timestamp tăng đơn điệu, không trùng.
- Đúng interval và UTC boundary.
- OHLC hợp lệ: high >= max(open, close), low <= min(open, close), high >= low.
- Volume không âm; dữ liệu vô lý phải flag, không sửa im lặng.
- Gap, duplicate, missing symbol/interval.
- Nến cuối chưa đóng không được coi là nến hoàn chỉnh.
- Đủ sample theo symbol/interval.
- Missing/invalid rate trong ngưỡng.

Validation fail phải dừng hoặc loại đoạn dữ liệu có log lý do; không sinh report PASS giả.

## 6. Đồng bộ timeframe và feature

- 15m/1h/4h phải UTC-aligned.
- 4h bắt đầu/đóng đúng mốc 00:00, 04:00, 08:00, 12:00, 16:00, 20:00 UTC.
- Không gom mỗi 16 nến từ phần tử đầu tiên nếu phần tử đầu không căn UTC.
- Không dùng candle chưa đóng cho feature/label yêu cầu nến đóng.
- Không ước lượng return 1m/5m từ return 15m. Nếu chưa có 1m/5m thì bỏ feature hoặc đánh dấu unavailable.
- Mỗi feature phải có timestamp/source/availability/staleness semantics.

### OBI, Funding, OI và dữ liệu không liên tục

- Không fill zero chỉ vì live runtime không có lịch sử.
- Phân biệt actual zero và missing bằng nullable/missing mask.
- Snapshot cuối không được giả làm lịch sử đầy đủ.
- Chỉ dùng feature training khi runtime tạo được feature tương đương.
- Feature chưa có nguồn ổn định thì loại khỏi schema v1 và ghi roadmap.

### Python/Kotlin feature parity

Đồng nhất feature names/order, formula, lookback, warmup, missing policy, scaler, timestamp semantics và precision tolerance. Viết golden parity tests cho nhiều symbol/regime, dữ liệu bình thường và dữ liệu có gap/missing.

## 7. Sửa labels

Cho từng horizon 15m/1h/4h:

- `UP`, `DOWN`, `SIDEWAYS` bằng threshold rõ ràng hoặc volatility-scaled threshold.
- Có thể dự báo return/volatility distribution nếu có đánh giá đúng.
- Không dùng tương lai vượt horizon để tạo label.
- Xác định entry assumption: close hiện tại hay open nến kế tiếp.
- Phí/spread/slippage/funding phải được tính nhất quán trong strategy evaluation.
- Tránh overlap giữa train/test bằng purge/embargo phù hợp horizon.
- Threshold được chọn trên validation, không nhìn holdout.

## 8. Training thật, baseline và Walk-forward

### Baselines bắt buộc

- No-change/majority baseline.
- Logistic Regression hoặc linear model.
- Một tree baseline nhẹ nếu dependency phù hợp (LightGBM/XGBoost trên PC/VPS).

Neural model chỉ được chọn khi thắng baseline OOS qua nhiều fold, ổn định qua regime và sau chi phí.

### Neural models

Ưu tiên:

1. GRU nhỏ.
2. TCN nhỏ.
3. LSTM nếu chứng minh có thêm giá trị.
4. Tiny Transformer chỉ offline/research.

Không train đủ bốn model chỉ để có đủ file. Mỗi model phải báo architecture, params, lookback, feature count, horizons, loss, optimizer, LR schedule, batch size, early stopping, seeds, curves, best checkpoint, thời gian train và RAM/GPU cần thiết.

**End-to-end training là bắt buộc nếu gọi là GRU/LSTM/TCN/Transformer đã huấn luyện.** Không dùng encoder random/fixed rồi chỉ cập nhật head mà báo cáo như training toàn mạng. Nếu dùng frozen encoder, phải gọi đúng tên và nêu nguồn pretrained.

### Walk-forward

- Thực sự train/evaluate mọi fold, không chỉ dùng `folds[-1]`.
- Scaler/imputer/feature selection/calibration chỉ fit trên train/validation tương ứng.
- Chronological split; purge/embargo cho label overlap.
- Lưu OOS predictions theo timestamp/symbol/fold/model.
- Có holdout cuối hoàn toàn không dùng để tuning/chọn model.
- Report date range, sample count, symbols và loại bỏ dữ liệu của từng fold.

## 9. Metrics

### Classification

Balanced Accuracy, per-class Precision/Recall/F1, Macro-F1, MCC, confusion matrix, Brier, Log Loss, ECE và reliability diagram.

### Regression/distribution

MAE/RMSE/Huber phù hợp target; directional accuracy không được dùng một mình; quantile loss/coverage nếu dự báo khoảng.

### Strategy

Net return sau fee/slippage/funding, Profit Factor, Sharpe/Sortino, Max Drawdown, Calmar, turnover, trade count, holding time, exposure, long/short breakdown, kết quả theo symbol/timeframe/regime, benchmark buy-and-hold/no-trade và confidence interval nếu đủ sample.

Báo cả fold tệ nhất, không chỉ trung bình. Backtest đơn lẻ không chứng minh lợi thế.

## 10. Sửa Backtest

Audit `ml/forecasting/src/evaluate.py`:

- Equity chỉ đổi khi có event giao dịch thật: entry, partial fill, fee, funding, exit.
- Không nhân equity với return giao dịch cũ ở sample không có trade mới.
- Tách realized/unrealized PnL.
- Mô phỏng spread/slippage/fee/funding và fill policy.
- Không giả định fill tại chính giá tạo signal khi không khả thi.
- Nếu cùng candle chạm SL và TP, dùng intrabar data hoặc quy tắc conservative.
- Position sizing, leverage, notional, margin và liquidation phải nhất quán với Binance Futures.
- Unit tests: zero/one/multiple trades, win/loss, partial fill, funding +/- , SL và TP cùng candle.

## 11. Calibration và uncertainty

- Fit calibration trên validation/OOS theo đúng quy trình, không fit holdout/test.
- Lưu model/calibration version và date range.
- Brier/ECE online chỉ cập nhật sau khi horizon kết thúc và outcome có thật.
- Không áp ranking score mới cho giao dịch lịch sử.
- Probability sum phải bằng 1 trong tolerance.
- Tách uncertainty khỏi confidence.
- Missing/OOD/drift/calibration stale => `UNTRUSTED` hoặc `NO_SIGNAL`.

---

# PHASE 3 — CHỌN RUNTIME VÀ TÍCH HỢP ANDROID

## 12. Chọn duy nhất một runtime production

Baseline có `model.tflite` mang magic `TFL3`, nhưng phải xác minh FlatBuffer thật; audit `LiteRtForecastRuntime.kt` xem nó load TFLite hay tự tính từ `weights_compact.json`. Không giữ hai luồng inference mập mờ.

### Phương án A — LiteRT thật (ưu tiên nếu export và benchmark đạt)

- Training PC/VPS xuất FlatBuffer LiteRT/TFLite thật.
- Kotlin gọi LiteRT runtime chính thức trên CPU.
- Đọc tài liệu chính thức mới nhất và dùng API được khuyến nghị; không sử dụng API deprecated nếu tài liệu runtime hiện tại yêu cầu API mới.
- Xác minh model bằng runtime thật trong Python và Android; không chỉ đọc magic header.
- Khai báo tensor shape, dtype, quantization scale/zero-point, tensor names và output parsing.
- So sánh nhiều vector Python/Android trong tolerance đã định nghĩa.
- Bắt đầu với CPU; chỉ bật GPU/NNAPI delegate nếu S7 hỗ trợ ổn định và benchmark chứng minh có lợi.
- Xác minh ABI/native libraries của thiết bị.

Tài liệu chính thức cần đọc:

- https://developer.android.com/agents/skills/device-ai/litert/litert-runtime/skill
- https://ai.google.dev/edge/litert/android

### Phương án B — Custom Kotlin runtime

Chỉ chọn khi LiteRT không tương thích hoặc custom runtime chứng minh nhanh/nhẹ/đúng hơn:

- Đổi `LiteRtForecastRuntime.kt` thành tên đúng bản chất, ví dụ `CompactKotlinForecastRuntime.kt`.
- Không xuất file `.tflite` giả.
- Định nghĩa weights format có version/schema/checksum.
- Validate dimensions, range, NaN/Infinity.
- Viết parity test với Python reference.
- Benchmark memory/allocation/P95.
- Chỉ giữ `weights_compact.json` nếu runtime dùng thật.

### Chọn phương án

Không giả định LiteRT luôn nhanh hơn trên chipset cũ. Benchmark trên S7 Edge. Chỉ một runtime production; runtime còn lại có thể ở branch research, không load đồng thời.

## 13. Không nhúng Python vào APK nếu không cần

- Python trong `ml/forecasting/` dùng data ingestion, feature engineering, training, evaluation, calibration và export trên PC/VPS.
- Android dùng Kotlin cho market data, feature pipeline, model loader, inference, ensemble, risk và execution.
- Không thêm Chaquopy chỉ để chạy `train_*.py`.
- Không train Transformer/LSTM/GRU trên S7.
- Chỉ cân nhắc Python runtime nhúng nếu có use case Python-only không chuyển được sang Kotlin/native và có benchmark RAM/ABI/APK size chứng minh.

## 14. Sửa Model Loader và threading

Audit `ForecastModelLoader.kt`, `LiteRtForecastRuntime.kt`, `ForecastCoordinator.kt`, `ForecastScheduler.kt`, `AstraViewModel.kt`.

1. Không đọc file/hash lớn/parse JSON/load model/warmup trên Main Thread.
2. Không dùng `runBlocking` trong UI path.
3. IO cho file/network; Default hoặc executor riêng cho CPU inference.
4. Không tạo hai runtime chỉ để self-test rồi bỏ một runtime; tái sử dụng nếu thread-safety cho phép.
5. Có single owner cho interpreter/runtime.
6. Timeout, cancellation, close/dispose đúng lifecycle.
7. Không giữ runtime cũ sau khi swap bundle.
8. Load fail => `MODEL_UNAVAILABLE`, không fallback ngầm sang weights giả/xác suất mặc định.
9. Cache cần `generatedAt`, `validUntil`, symbol, timeframe, horizon, model version và data watermark.
10. Cache hết hạn => `STALE`/`NO_SIGNAL`.

## 15. Model bundle và rollback

Bundle phải có model ID/version/format, createdAt, feature schema/order, scaler version, calibration version, symbols, intervals, horizons, tensor input/output semantics, minimum runtime, SHA-256, dataset range/hash, training code commit và report reference.

Loader xác minh tất cả trước khi activate. Mismatch => không activate.

Update/rollback:

- Chỉ tải từ nguồn xác thực; không thực thi code tải về.
- Download vào temporary file, verify signature/hash/schema, smoke test rồi atomic promote.
- Giữ model tốt cuối cùng làm rollback.
- Rollback nếu load/inference fail, drift/quality gate fail hoặc crash tăng.
- Không thay model giữa inference request.

---

# PHASE 4 — NỐI FORECAST VỚI ASTRA

## 16. Forecast Outcome Tracking

Audit `ForecastRepository`, DAO, `ForecastCoordinator`, `AstraTradingCoordinator` và scheduler. Không chấp nhận hàm record outcome/update metrics chỉ tồn tại nhưng không có caller hoặc vẫn là stub.

Triển khai:

1. Mỗi forecast có ID duy nhất.
2. Lưu symbol, forecast time, candle watermark, horizon, model/schema version, probabilities, predicted return/volatility, calibration version và status.
3. Khi horizon kết thúc, lấy actual price theo label definition.
4. Resolve outcome đúng một lần; idempotent khi retry/restart.
5. Lưu realized direction/return và timestamp.
6. Cập nhật rolling Brier/Log Loss/ECE/reliability/directional metrics theo model/symbol/horizon/regime.
7. Không cập nhật prediction lịch sử bằng ranking score mới.
8. Không xóa forecast chưa resolve trong cleanup.
9. Xử lý missing candle/delist/data gap/market outage.
10. Regression tests cho horizon 15m/1h/4h.

## 17. Feature parity train/live

- Dùng schema có version.
- So sánh Kotlin và Python pipeline.
- Missing OBI/Funding/OI không tự biến thành zero nếu training không xử lý như vậy.
- Feature từ WebSocket phải có timestamp và age.
- Input stale => không forecast.
- Thiếu lookback => `INSUFFICIENT_HISTORY`.

## 18. Forecast không được tự mở lệnh

Giữ luồng:

`Forecast -> Strategy Candidate -> Independent Validation -> Risk Gate -> Execution Gate -> Order Intent -> Exchange`.

Forecast chỉ trả xác suất/ước lượng và quality status. Strategy tạo candidate. Risk Engine quyết định rủi ro. Execution Gate kiểm tra data freshness, spread, slippage, exchange filters, position limits và circuit breaker. Forecast confidence thấp/OOD/stale/drift => không tạo lệnh. AI/Forecast không được bypass SL/TP, risk limit hay kill switch.

## 19. Re-check trading safety cũ

Audit và regression-test:

- Panic Close state machine.
- Query vị thế sàn trước/sau close.
- Cancel/verify SL và TP Algo Orders.
- Reconcile sau restart.
- Order intent idempotency.
- Partial fills, duplicate events, timeout trạng thái không rõ.
- Binance timestamp drift, recvWindow, 418/429/5xx.
- ListenKey lifecycle/User Data Stream reconnect.
- Hedge/One-way mode, reduceOnly/closePosition semantics.
- Tick size, step size, min notional/precision.
- Leverage, isolated/cross margin, liquidation estimate.
- Funding fee và realized/unrealized PnL.
- Global capital mutex.
- API permission và secret handling.

Nếu lỗi đã sửa, có regression test tốt thì giữ, không viết lại mù quáng.

---

# PHASE 5 — S7 EDGE PERFORMANCE VÀ STABILITY

## 20. Nhận diện phần cứng thật

Không đoán variant S7 Edge theo tên. Lấy từ thiết bị:

```bash
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.product.board
adb shell getprop ro.hardware
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
adb shell cat /proc/meminfo
adb shell df -h /data
```

Ghi `docs/S7_EDGE_DEVICE_PROFILE.md`: Android/API, ABI, chipset nếu xác định chắc, RAM idle/working, storage trống, nhiệt độ khả dụng, LineageOS build và Google Play Services có/không. Loại bỏ serial/ID riêng tư. Không giả định có GMS hoặc GPU delegate.

## 21. Benchmark trên thiết bị thật

Đo APK/installed size, cold/warm start, model load, warmup, inference P50/P95/max từng horizon, Java/Kotlin heap, native memory, CPU, GC, nhiệt độ, battery drain theo điều kiện thử, network reconnect, forecast freshness và behavior khi screen off/background/reboot.

Mục tiêu ban đầu:

- Không ANR/crash trong soak test 2 giờ.
- Model load/inference không chạy trên Main Thread.
- Inference P95 mục tiêu <= 2 giây trên CPU; không đạt thì giảm model/sequence/features và giải trình.
- Một nến đóng chỉ inference một lần, retry phải idempotent.
- RAM tăng thêm do model/inference mục tiêu <= 250 MB; vượt thì phải tối ưu hoặc giảm model.
- Không chạy nhiều model nặng đồng thời.
- Không inference liên tục nếu candle không đổi.
- Báo nhiệt độ/pin theo số đo và điều kiện cụ thể; không tuyên bố đạt nếu chưa đo.

Có thể điều chỉnh ngưỡng theo benchmark thật nhưng phải giải thích trade-off, không gian lận.

## 22. Thermal/RAM/cache

- Audit RAM Guard và Thermal Guard.
- Không dùng default giả như 33.5°C hoặc 1450 MB khi API không đọc được; dùng `UNKNOWN`.
- Phân biệt battery temp với CPU/SoC thermal zone.
- Cache có expiry; stale không được coi là forecast mới.
- Không inference nếu data không đổi.
- Hạn chế allocation/parse weights nhiều lần.
- Không backtest/train trên thiết bị.
- Giảm tải khi RAM/nhiệt vượt ngưỡng đo được.

## 23. Foreground Service/LineageOS

- Notification channel/permission và foreground service type đúng target SDK.
- Notification thể hiện service/status.
- Start/stop/restart idempotent.
- Boot receiver chỉ khôi phục trạng thái an toàn, không mở LIVE.
- Kiểm tra autostart/battery optimization trên LineageOS; không giả định ROM tự khởi động.
- Hướng dẫn bật quyền cần thiết, không vượt bảo vệ OS.
- Xử lý Doze, mất mạng, airplane mode, process kill, reboot.
- Sau phục hồi, reconcile exchange orders/positions trước khi resume.
- State không rõ hoặc lệch sàn => không execution.

---

# PHASE 6 — DATABASE, ARCHITECTURE, UI VÀ SECURITY

## 24. Room Database

- Rà Room schema v7, Entity/DAO/migrations.
- Migration bảo toàn dữ liệu; không destructive migration production.
- Test migration từ mọi version hỗ trợ.
- Index query forecast/outcome/order/event.
- Foreign key/unique constraint chỉ thêm khi có migration/backfill kiểm chứng.
- Transaction cho order intent/fills/positions/forecast outcomes.
- Retention policy không xóa audit/accounting cần thiết.
- Đo WAL/database size/query latency.

## 25. Maintainability

- Giữ boundaries domain/data/network/quant/ai/forecast/engine/service/ui/security.
- Không đưa trading logic vào Composable/ViewModel.
- Interface cho Exchange/AI Provider/data provider/model runtime/clock.
- Inject clock để test expiry/time drift.
- Typed Result/Error; không nuốt exception.
- Correlation ID từ market snapshot đến forecast/strategy/order/exchange order.
- Tránh singleton gây race/lifecycle leak.
- Không đổi applicationId nếu không cần, tránh mất dữ liệu cài đặt hiện tại.
- Nếu package path `com.example` khác applicationId thì audit consistency trước khi sửa.

## 26. UI/Operations

- Giao diện gọn 4–5 khu vực chính.
- Hiện rõ `LIVE`, `TESTNET`, `SHADOW`, `PAUSED`, `DRAINING`, `PANIC`, `DATA_STALE`, `MODEL_UNAVAILABLE`.
- Forecast có model version, timestamp, horizon, data freshness, confidence/calibration status.
- Missing data phải nói rõ lý do, không hiện số 0 giả.
- Forecast probability và realized performance tách riêng.
- Brier/ECE chỉ hiện khi đủ outcomes và phải có sample count.
- Diagnostics nhẹ: RAM, model load, inference latency, WebSocket, last candle, DB.
- Tránh refresh UI quá thường xuyên.

## 27. Security

- Audit `SecretVault.kt`, Android Keystore, key invalidation.
- Không log secret/signed request.
- Không lưu API key plaintext trong Room/SharedPreferences.
- Kiểm soát exported components, deep links, backup/data extraction.
- Release `debuggable=false`, không có debug backdoor mở LIVE.
- TLS validation đúng, không trust-all certificates.
- Redirect không được làm lộ chữ ký/API key sang host khác.
- Validate Telegram command, whitelist user/chat, replay protection/idempotency.
- Redact logs khi export.
- Dependency vulnerability/license review.

---

# PHASE 7 — GRADLE, RELEASE VÀ APK OPTIMIZATION

## 28. Gradle Wrapper/build reproducibility

- Bổ sung `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` nếu thiếu.
- Wrapper version phải tương thích AGP/Kotlin/JDK.
- Không nâng AGP/Kotlin/Gradle cùng lúc thiếu test.
- Pin dependency versions; bỏ dynamic `+` versions.
- Build từ clean checkout bằng wrapper và ghi JDK/lệnh.

## 29. R8/resource shrinking

- Kiểm tra AGP version trước khi chỉnh DSL.
- Bật minification/optimization cho Release theo đúng DSL version hiện tại.
- Với legacy DSL thường cần `isMinifyEnabled = true` và `isShrinkResources = true`; AGP mới có DSL khác, phải dùng docs đúng version.
- Tham khảo: https://developer.android.com/topic/performance/app-optimization/enable-app-optimization
- Keep rules tối thiểu; không dùng `-keep class ** { *; }` rộng toàn app.
- Test reflection, serialization, Room, network, model loader/runtime sau R8.
- Lưu mapping file/build version.
- Debug không cần minify nếu cản trở debug.

## 30. ABI/assets/package

- Đọc ABI S7 Edge trước khi chọn universal hay ABI-specific APK.
- Không bundle native libs ABI không cần nếu có phương án phân phối phù hợp.
- Phân biệt APK size, installed size, DEX uncompressed size và assets size.
- Không để dataset lịch sử, ZIP reference, report, training checkpoint hoặc Python training code trong `src/main/assets`.

## 31. Release checklist

- `assembleDebug` PASS.
- `testDebugUnitTest` PASS.
- Robolectric PASS.
- `connectedDebugAndroidTest` PASS nếu có device/emulator.
- `assembleRelease` PASS.
- Merged Manifest: debuggable=false, permissions/services/receivers hợp lệ.
- APK Analyzer: kiểm tra DEX/native libs/resources/assets.
- Không có secrets, training data/code, reference ZIP hoặc fake model.
- Install/update trên S7 Edge.
- Smoke test UI/DB/API config/model load/SHADOW/service.
- SHA-256 APK, version, commit/build metadata và signing cert fingerprint; không bàn giao private key.

---

# PHASE 8 — TEST HỆ THỐNG VÀ TESTNET

## 32. Test matrix

### Data

Real valid data; empty; duplicate; missing; unclosed candle; wrong UTC; delisted symbol; API 429/5xx/timeout; restart giữa tải.

### Model

Valid bundle; checksum mismatch; schema mismatch; tensor shape mismatch; corrupt model; scaler mismatch; NaN/Infinity; OOD; load timeout; inference exception; calibration missing/expired.

### Android

Cold/warm start; background; screen off; Doze; process kill; reboot; low RAM; thermal high; storage low; network reconnect; notification denied; autostart disabled.

### Trading Safety

Exchange state unknown; position query fail; order submit timeout không rõ kết quả; duplicate event; partial fill; SL/TP create fail; cancel fail; panic query fail; position vẫn còn sau close; DB unavailable; clock drift; rate limit.

Khi state không chắc chắn, chuyển safe state, không đoán trạng thái sàn.

## 33. Testnet và Shadow soak

- Chỉ Binance Testnet/test environment; không dùng API key có quyền withdrawal.
- Bắt đầu bằng Forecast Shadow, không đặt lệnh.
- Đối chiếu forecast với outcome thật sau horizon.
- Sau đó mới test order flow trên Testnet.
- Soak test tối thiểu 24h nếu thiết bị/kết nối cho phép; nếu không ghi chính xác thời gian thực chạy.
- Theo dõi CPU/RAM/temp/battery/network/DB/forecast freshness/reconciliation.
- Không tuyên bố 24/7 nếu chưa thử process kill/reboot/recovery.

---

# PHASE 9 — TÍNH NĂNG NÂNG CAO (CHỈ SAU KHI P0–P8 ĐẠT)

Không làm nhóm này trước blocker. Không được thêm nhiều module để che chất lượng dữ liệu kém.

## 34. Roadmap

### A. Market Microstructure

CVD/Volume Delta, multi-level Order Book Imbalance, Microprice, Absorption, Liquidity Zones, Open Interest change, Funding/Mark-Index basis, Liquidation Data. Chỉ thêm nếu có nguồn thật, timestamp đồng bộ và kiểm tra stale/missing.

### B. Market Regime

HMM hoặc classifier nhẹ cho trend/range/high volatility; Change Point Detection; GARCH/volatility clustering nếu chứng minh giá trị hơn baseline.

### C. Crowd Psychology/Social Sentiment

News/event sentiment, FOMO/FUD/panic/overconfidence proxies, social sentiment từ nguồn API/quyền sử dụng hợp lệ, bot/spam/duplicate filtering. Không khẳng định hiểu toàn bộ tâm lý thị trường từ ít bài đăng.

### D. Smart Trader Intelligence

- Dùng leaderboard/lịch sử công khai hoặc dữ liệu người dùng có quyền truy cập.
- Đánh giá net PnL, drawdown, profit factor, consistency, sample size và hiệu suất theo regime.
- Phân biệt maker/arbitrage/market maker/directional trader khi có dữ liệu.
- Chống survivorship bias; lưu cả trader không còn hoạt động và lệnh thua nếu nguồn cho phép.
- Không copy lệnh trực tiếp; học đặc trưng và điều kiện giao dịch.
- Không đưa dữ liệu riêng tư/nhận dạng cá nhân vào model.

### E. Whale/On-chain

Exchange inflow/outflow, large transactions và wallet/entity labels từ nguồn đáng tin. Phân biệt transfer nội bộ, bridge, custody và giao dịch thật; không gọi mọi giao dịch lớn là cá voi mua/bán.

### F. Adaptive Learning

Drift monitor, Champion/Challenger, Shadow evaluation, promotion gate và rollback. Không online-update trọng số model đang điều khiển LIVE.

---

# PHASE 10 — BÀN GIAO VÀ BẰNG CHỨNG

## 35. Artifact phải bàn giao

1. `README.md` — hướng dẫn build/run/install/test thực tế.
2. `docs/BASELINE_INVENTORY.md`.
3. `docs/ARTIFACT_DISPOSITION.md`.
4. `docs/DEPENDENCY_AUDIT.md`.
5. `docs/FORECASTING_DATA_REPORT.md`.
6. `docs/FORECASTING_TRAINING_REPORT.md`.
7. `docs/FORECASTING_OOS_EVALUATION.md`.
8. `docs/MODEL_EXPORT_AND_RUNTIME_REPORT.md`.
9. `docs/S7_EDGE_BENCHMARK.md`.
10. `docs/SECURITY_REVIEW.md`.
11. `docs/TEST_AND_BUILD_REPORT.md`.
12. `docs/KNOWN_LIMITATIONS.md`.
13. `docs/CHANGELOG.md`.
14. Source ZIP sạch.
15. Debug APK và Release APK nếu Release đủ điều kiện.
16. SHA-256 của ZIP/APK và build metadata.

Không đưa secret, private keystore hoặc API key vào gói bàn giao.

## 36. Status từng issue

Mỗi issue phải có ID, severity P0–P3, file/function, root cause, fix, files changed, tests, command, log path, status và residual risk.

Status hợp lệ:

- `FIXED`: code đã sửa nhưng chưa đủ chứng minh toàn bộ runtime.
- `VERIFIED`: test/build/device evidence thành công.
- `PARTIAL`: mới sửa một phần.
- `BLOCKED`: thiếu điều kiện ngoài khả năng.
- `NOT FIXED`: chưa xử lý.

Không ghi `FIXED` nếu chỉ sửa code mà chưa chạy test liên quan. Không ghi `VERIFIED` khi không có log/bằng chứng.

## 37. Gate hoàn thành

### Không được tuyên bố hoàn thành nếu

- Training/evaluation dùng synthetic data để kết luận thị trường thật.
- Backtest equity sai.
- Chỉ dùng một fold nhưng report nói nhiều folds.
- TFLite không load được bằng LiteRT runtime thật nhưng vẫn quảng cáo LiteRT.
- Feature train/live không parity.
- Forecast outcome không resolve hoặc metrics không cập nhật.
- Forecast stale vẫn có thể đến Strategy.
- Release APK còn debuggable hoặc chứa secrets.
- Build không tái lập từ source sạch.
- Service chưa test trên S7 nhưng report nói chạy 24/7.
- LIVE được mở trong quá trình sửa.

### Hoàn thành giai đoạn build khi

- Source build được từ clean checkout bằng Gradle Wrapper.
- Unit/integration tests liên quan PASS.
- APK cài được trên S7 Edge Android 11.
- Runtime load model hợp lệ, inference output đúng schema.
- Shadow forecast chạy được các horizon thực sự hỗ trợ.
- Không crash/ANR trong soak test có thời gian ghi rõ.
- App thể hiện đúng trạng thái khi thiếu data/model/network.
- LIVE vẫn bị khóa.

### Chỉ xem xét Testnet khi

- Blocker P0 về data/model/outcome/execution/safety đã VERIFIED.
- Fault injection Testnet cho order, SL/TP, reconcile, Panic Close PASS.
- Không còn lỗi có thể gây duplicate order, mất SL/TP hoặc hiểu sai vị thế.

Tài liệu này **không cấp quyền bật LIVE**. Người dùng phải xem xét độc lập trước mọi quyết định dùng vốn thật.

---

# LỆNH THỰC THI CUỐI CÙNG CHO GEHIHI

Bắt đầu ngay bằng việc đọc toàn bộ source đầu vào và hoàn thành Phase 0. Sau đó thực hiện tuần tự Phase 1–8. Không chờ người dùng xác nhận giữa các Phase, trừ thao tác phá hủy dữ liệu hoặc cần secret/quyền truy cập chưa được cấp.

Ưu tiên sửa lỗi đúng bản chất. Không thêm feature để che lỗi cũ. Không xóa code hữu ích chỉ để giảm APK. Không giữ artifact giả để báo cáo có LiteRT/INT8/Transformer. Không dùng mock chứng minh production.

Với mọi mục có thể cắt giảm, hãy chứng minh nó không được dùng hoặc không đem lại giá trị trước khi bỏ. Nếu còn giá trị, giữ và tối ưu. Nếu chỉ là reference, chuyển khỏi runtime/APK. Nếu là developer tool, chuyển vào `tools/`. Nếu là test fixture, giữ trong test source set. Nếu artifact sai định dạng, thay bằng artifact hợp lệ hoặc loại bỏ.

Kết thúc bằng source sạch, APK cài được trên S7 Edge, benchmark trên thiết bị thật, report test, known limitations và hash của artifacts.

**Không bật LIVE. Không gửi lệnh tiền thật. Không tuyên bố hoàn thành khi chưa có bằng chứng.**
