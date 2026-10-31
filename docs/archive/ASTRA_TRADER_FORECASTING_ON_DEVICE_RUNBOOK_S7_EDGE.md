# ASTRA TRADER — FORECASTING ENGINE IMPLEMENTATION RUNBOOK

**Mục tiêu:** Bổ sung dự báo chuỗi thời gian bằng GRU/LSTM/TCN và nghiên cứu Transformer, tích hợp vào Astra Trader hiện tại, tối ưu cho **Samsung Galaxy S7 Edge, RAM 4 GB, ROM 64 GB, LineageOS Android 11**.

**Đối tượng thực hiện:** AI coding agent / Android engineer / ML engineer có quyền đọc và sửa toàn bộ source Astra Trader.

**Nguyên tắc:** Đọc mã nguồn trước, kiểm chứng từng giả định, triển khai từng giai đoạn, tự viết test, chạy test và nộp bằng chứng. Không chỉ tạo tài liệu hoặc skeleton class. Không được bật giao dịch LIVE trong quá trình phát triển.

---

## 0. Kết luận kỹ thuật cần tuân thủ

Astra Trader hiện có nền tảng định lượng và dữ liệu thị trường, nhưng **chưa có một Forecasting Engine bằng mạng neural được tích hợp hoàn chỉnh**. Trong source ZIP đã kiểm tra:

- `QuantMathEngine.kt` có tính toán Log Return, EMA20/EMA50, ATR14, rolling standard deviation, EWMA volatility, robust MAD Z-score, volume/range và market regime.
- Snapshot chính đang dùng dữ liệu nến 15m và 1h.
- `ret5m` và `ret1m` hiện được ước tính bằng cách chia `ret15m`, không phải return tính từ nến 5m/1m thực tế. Tuyệt đối không dùng các giá trị ước tính này làm dữ liệu lịch sử thật để huấn luyện mô hình 5m/1m.
- `app/build.gradle.kts` hiện chưa có LiteRT/TensorFlow Lite/ONNX Runtime dependency.
- Chưa tìm thấy module train model, model inference runtime, model registry, forecast entity hoặc forecast evaluation pipeline trong source ZIP được kiểm tra.
- Room hiện ở version 4 theo `ARCHITECTURE.md`; mọi bổ sung schema phải có migration đầy đủ, không được xóa dữ liệu hiện hữu.

**Điểm đến phù hợp với S7 Edge 4/64:**

1. Huấn luyện mô hình trên PC/VPS/cloud; điện thoại chỉ làm feature computation, inference, calibration nhẹ và lưu kết quả.
2. Bắt đầu bằng baseline thống kê/ML nhỏ, sau đó benchmark **GRU nhỏ và TCN nhỏ**. Có thể thêm LSTM nhỏ làm challenger để so sánh.
3. Transformer chỉ là mô hình challenger nghiên cứu trước. Chỉ đưa lên điện thoại nếu mô hình nhỏ, quantize được, hỗ trợ operator của runtime, có lợi thế ngoài mẫu rõ ràng và vượt qua benchmark trên chính S7 Edge.
4. Dùng LiteRT/TensorFlow Lite runtime theo CPU trước. Không mặc định thiết bị có NPU hoặc GPU delegate chạy nhanh. Không dùng NNAPI làm phụ thuộc bắt buộc.
5. Forecast chỉ cung cấp xác suất/phân phối lợi nhuận và độ bất định cho Strategy Engine. Nó **không được đặt lệnh, sửa SL/TP, bỏ qua Risk Engine hoặc tự mở khóa LIVE**.

Không hứa hẹn mô hình sẽ dự báo chính xác hoặc có lợi nhuận. Mọi mô hình phải vượt baseline, tính đủ phí/slippage/funding và được kiểm chứng bằng walk-forward trước khi được phép ảnh hưởng tới chiến lược.

---

## 1. Thông tin thiết bị và giới hạn triển khai

### 1.1 Thiết bị mục tiêu

- Thiết bị: Samsung Galaxy S7 Edge.
- RAM vật lý: 4 GB theo cấu hình người dùng cung cấp.
- Bộ nhớ trong: 64 GB theo cấu hình người dùng cung cấp.
- ROM: LineageOS.
- Android: 11 (API 30 theo thông tin người dùng cung cấp).
- Dòng S7 Edge có nhiều biến thể chipset, ví dụ Snapdragon 820 hoặc Exynos 8890; không được hardcode giả định chipset/ABI/GPU. Phải đọc thông tin thực trên thiết bị trước khi chọn delegate và ABI.

### 1.2 Lệnh kiểm tra thiết bị qua ADB

```bash
adb devices
adb shell getprop ro.product.model
adb shell getprop ro.product.device
adb shell getprop ro.board.platform
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell cat /proc/meminfo
adb shell dumpsys meminfo com.aistudio.astratrader.v51qnt
adb shell dumpsys thermalservice
```

Nếu `dumpsys thermalservice` không khả dụng trên ROM, phải ghi rõ và dùng phương pháp quan sát nhiệt độ/thermal status khác. Không được suy đoán nhiệt độ từ phần trăm CPU.

### 1.3 Ngân sách tài nguyên mục tiêu ban đầu

Đây là **ngưỡng kỹ thuật để benchmark**, không phải thông số chắc chắn của mọi bản S7 Edge:

| Chỉ tiêu | Mục tiêu ban đầu | Cách xử lý nếu không đạt |
|---|---:|---|
| File model chính INT8 | Ưu tiên <= 5 MB; hard target <= 10 MB | Giảm hidden size, sequence length hoặc số model |
| RAM tăng thêm khi load + inference | Mục tiêu <= 100 MB; cảnh báo nếu > 150 MB | Giảm tensor, không load nhiều model cùng lúc |
| Inference P95 cho 1 symbol | Mục tiêu <= 500 ms trên thiết bị thật | Giảm sequence/features hoặc chuyển model nặng sang server |
| Batch 5 symbols | Mục tiêu <= 3 giây sau nến đóng | Giảm symbol chạy forecast mỗi chu kỳ |
| CPU threads | Bắt đầu 1 thread; benchmark 2 threads | Chọn cấu hình nhanh nhưng không làm nghẽn trading/network |
| Tải liên tục | Test tối thiểu 2 giờ | Hạ tần suất, bỏ delegate gây nóng, hoặc tắt forecast |
| Model đồng thời trong RAM | 1 model chính | Không giữ GRU + LSTM + Transformer cùng lúc |
| Dữ liệu lịch sử trên máy | Giữ gọn, có TTL | Không tải toàn bộ tick history về điện thoại |

Ưu tiên trading safety, network, database và nhiệt độ hệ thống hơn việc chạy forecast. Khi RAM thấp, thermal state xấu, app bị pressure hoặc inference lỗi: **bỏ qua forecast**, không làm treo Coordinator và không làm chậm bảo vệ vị thế.

### 1.4 Runtime Android

Tại thời điểm soạn runbook, tài liệu Google AI Edge liệt kê LiteRT v2.3.2 với `Interpreter` CPU-only và minimum SDK 23; Android 11/API 30 đáp ứng yêu cầu nền tảng này. Project hiện có `minSdk = 24`, vì vậy không cần hạ minSdk. Kiểm tra lại Maven artifact/API thực tế khi build; không sao chép dependency/import cũ một cách mù quáng.

Khuyến nghị:

- Dùng LiteRT CPU runtime trước, pin phiên bản dependency đã build/test thành công.
- Ưu tiên đường inference CPU ổn định; benchmark 1 và 2 threads.
- Không thêm GPU delegate/NNAPI vào cấu hình bắt buộc. Chỉ thử như tùy chọn sau khi có test tương thích và đo được tốc độ/nhiệt độ trên đúng máy.
- Không dùng NNAPI làm phương án mặc định; tài liệu TensorFlow hiện đánh dấu NNAPI/Hexagon delegate là deprecated.
- Không thêm LiteRT-LM. Đây là dự báo chuỗi thời gian dạng tensor, không phải bài toán LLM.

Tài liệu kỹ thuật:

- LiteRT Android: https://developers.google.com/edge/litert/android
- LiteRT overview: https://developers.google.com/edge/litert/overview
- XNNPACK: https://github.com/tensorflow/tensorflow/tree/master/tensorflow/lite/delegates/xnnpack
- NNAPI delegate status: https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/g3doc/android/delegates/nnapi.md

---

## 2. Phạm vi mô hình phải xây dựng

Không triển khai tất cả kiến trúc một lúc. Phải có baseline, challenger và cổng promotion.

### 2.1 Các mô hình theo thứ tự ưu tiên

| Thứ tự | Mô hình | Vai trò | Nơi chạy đề xuất |
|---|---|---|---|
| 0 | Naive / EMA / Logistic Regression / EWMA volatility | Baseline bắt buộc | Android hoặc Python |
| 1 | GRU nhỏ | Neural baseline gọn, học phụ thuộc thời gian | Android CPU nếu benchmark đạt |
| 2 | TCN nhỏ | Challenger thường trực, convolution theo thời gian | Android CPU nếu benchmark đạt |
| 3 | LSTM nhỏ | Challenger so sánh với GRU/TCN | Android CPU nếu benchmark đạt |
| 4 | Tiny Transformer / PatchTST nhỏ | Nghiên cứu quan hệ xa và đa đặc trưng | PC/VPS trước; S7 chỉ khi qua toàn bộ cổng |
| 5 | Ensemble | Kết hợp các mô hình đã được kiểm chứng | Android, chỉ giữ thành phần nhẹ |

Không có quy định Transformer luôn tốt hơn LSTM. Mô hình chỉ được chọn khi cải thiện đáng kể trên dữ liệu ngoài mẫu, có độ hiệu chuẩn tốt và tạo kết quả giao dịch sau phí tốt hơn baseline.

### 2.2 Cấu hình khởi đầu đề nghị

Đây là cấu hình khởi tạo để benchmark, không phải cấu hình tối ưu đã được chứng minh:

**GRU v1**

- 1 recurrent layer.
- Hidden size 32; challenger hidden size 48 hoặc 64 chỉ khi cần.
- Input sequence: 64 hoặc 96 nến đã đóng.
- Feature count: khoảng 24-32 feature đã chuẩn hóa.
- Batch inference: 1 symbol/lần.
- Output heads: ba lớp Up/Down/Flat, expected return và quantiles (P10/P50/P90).
- Dropout chỉ dùng trong training; inference phải ở evaluation mode.

**TCN v1**

- 2-4 causal convolution blocks.
- Dilation tăng theo 1, 2, 4, 8 nếu phù hợp.
- Channel 16 hoặc 32.
- Kernel size 3 hoặc 5.
- Causal padding; tuyệt đối không để tương lai chảy vào quá khứ.
- Output schema giống GRU.

**LSTM v1**

- 1 layer, hidden size 16-32 trước.
- Sequence 64-96.
- Chỉ giữ nếu kết quả walk-forward tốt hơn hoặc bổ sung thông tin cho ensemble.

**Tiny Transformer challenger**

- Chỉ nghiên cứu offline đầu tiên.
- Thử context 64/96, `d_model` 32, 2 attention heads, 1-2 encoder blocks, feed-forward 64.
- Không dùng context dài hàng nghìn bước trên S7.
- Không thêm attention operator/custom op nếu runtime target không hỗ trợ ổn định.
- Nếu quantization INT8 làm hỏng xác suất/độ chính xác hoặc latency/nhiệt không đạt thì không deploy lên điện thoại.

### 2.3 Không huấn luyện neural network nặng trên S7 Edge

S7 Edge làm inference, không làm training chính. Lý do: CPU/RAM cũ, nhiệt độ và pin; training gây giành CPU với WebSocket, database, Android service và risk loop.

Cho phép trên điện thoại:

- Tính feature.
- Inference một model nhỏ.
- Cập nhật calibration nhẹ khi đã đủ mẫu và có outcome xác nhận.
- Tính thống kê hiệu suất, drift và model health.

Không cho phép trên điện thoại:

- Backpropagation liên tục.
- Train Transformer/LSTM từ đầu.
- Hyperparameter search.
- Refit scaler từ dữ liệu live một cách tự phát.
- Tự sửa file model hoặc tự đưa model mới vào LIVE mà chưa qua cổng validation.

---

## 3. Forecasting phải dự báo cái gì?

Không chỉ dự báo “giá sẽ lên hay xuống”. Mục tiêu phải gắn với lợi nhuận có thể giao dịch.

### 3.1 Forecast horizons

Giai đoạn đầu tập trung:

- 15m ahead.
- 1h ahead.
- 4h ahead.

Giai đoạn sau mới xem xét 5m. Muốn dự báo 5m phải lấy nến 5m thật, xây dataset 5m thật và validation riêng. Không được suy ra dữ liệu 5m bằng cách chia return 15m.

Không cần dự báo 1m trong bản đầu vì nhiễu, spread, latency và slippage có thể lớn hơn lợi thế mô hình.

### 3.2 Target/label

Dùng return tương lai, không chỉ giá tuyệt đối:

```text
future_log_return(h) = ln(close[t + h] / close[t])
```

Tạo nhãn ba lớp có vùng trung tính dựa trên chi phí:

```text
cost_threshold = round_trip_fee
               + estimated_slippage
               + funding_cost_for_horizon
               + safety_buffer

UP   if future_return > +cost_threshold
DOWN if future_return < -cost_threshold
FLAT otherwise
```

`cost_threshold` phải tính theo cấu hình sàn và tình trạng thanh khoản tại thời điểm tạo dự báo, không hardcode một mức phí dùng cho tất cả symbol. Có thể bổ sung ngưỡng volatility-aware, nhưng phải được backtest và version hóa.

### 3.3 Output contract bắt buộc

Tạo domain model rõ ràng, ví dụ:

```kotlin
data class ForecastResult(
    val symbol: String,
    val horizon: String,
    val inputAsOfMs: Long,
    val generatedAtMs: Long,
    val validUntilMs: Long,
    val modelId: String,
    val modelVersion: String,
    val featureSchemaVersion: String,
    val pUp: Float,
    val pDown: Float,
    val pFlat: Float,
    val expectedReturnPct: Float?,
    val q10ReturnPct: Float?,
    val q50ReturnPct: Float?,
    val q90ReturnPct: Float?,
    val predictedVolatilityPct: Float?,
    val uncertainty: Float?,
    val outOfDistributionScore: Float?,
    val calibrated: Boolean,
    val status: ForecastStatus,
    val rejectionReason: String?
)
```

Tên/kiểu field có thể chỉnh theo convention dự án, nhưng phải giữ ý nghĩa tương đương. Phải validate:

- `pUp`, `pDown`, `pFlat` hữu hạn, nằm trong [0,1].
- Tổng xác suất xấp xỉ 1 trong tolerance được định nghĩa.
- Không có NaN/Infinity.
- Timestamp đầu vào không nằm sau thời điểm tạo dự báo.
- `validUntilMs` không quá hạn.
- Quantile phải có thứ tự `q10 <= q50 <= q90`.
- `expectedReturn`/quantile phải có đơn vị rõ ràng (fraction hay percent); không trộn lẫn.
- Chỉ đánh dấu calibrated khi calibration data và model version thỏa tiêu chí.

Trạng thái gợi ý: `VALID`, `STALE`, `INSUFFICIENT_DATA`, `FEATURE_INVALID`, `MODEL_MISSING`, `MODEL_INCOMPATIBLE`, `INFERENCE_ERROR`, `OUT_OF_DISTRIBUTION`, `UNCALIBRATED`.

Không trả xác suất giả như `0.5/0.3/0.2` khi inference lỗi. Khi không dự báo được phải trả `UNAVAILABLE` kèm nguyên nhân.

---

## 4. Feature Engineering — dữ liệu đầu vào

### 4.1 Feature set v1

Chọn 24-32 đặc trưng đầu tiên. Không nhồi hàng trăm indicator vào model nhỏ.

**Giá và candle shape**

- Log return 1 candle, 2 candle, 4 candle, 8 candle.
- High-low range / close.
- Body / range.
- Upper wick / range.
- Lower wick / range.
- Gap giữa close hiện tại và EMA20/EMA50, chuẩn hóa theo ATR.

**Trend / momentum / volatility**

- EMA slope 20/50.
- RSI 14.
- MACD histogram (nếu được tính và test đúng).
- ATR%.
- Rolling return volatility.
- EWMA volatility.
- Bollinger bandwidth / price-to-band distance.
- Robust return Z-score.

**Volume / flow**

- Log volume.
- Relative volume dựa trên rolling median/mean quá khứ.
- Taker buy volume ratio từ dữ liệu thực có sẵn.
- Volume delta/CVD nếu nguồn có dữ liệu hợp lệ.
- Không được gọi volume tổng là “buy volume” hoặc “sell volume”.

**Microstructure**

- Spread %.
- Top-of-book imbalance.
- Microprice deviation.
- Order book depth imbalance chỉ khi snapshot có timestamp và depth được thu thập đầy đủ.
- Các feature order book phải được đồng bộ với thời điểm candle; dữ liệu quá cũ phải được đánh dấu missing.

**Derivatives / market context**

- Funding rate và percentile/z-score lịch sử.
- Open interest level/change nếu endpoint có dữ liệu đúng.
- Long/short ratio nếu nguồn và định nghĩa đáng tin cậy.
- BTC market return/regime để làm context cho altcoin.

### 4.2 Không dùng mọi feature ngay từ đầu

Chia feature thành:

- `CORE`: OHLCV, return, trend, volatility, volume. Mô hình phải chạy được với nhóm này.
- `OPTIONAL_MARKET_MICROSTRUCTURE`: spread, order book, microprice, CVD.
- `OPTIONAL_DERIVATIVES`: funding, OI, Long/Short ratio.
- `OPTIONAL_EXTERNAL`: news/social sentiment, chỉ thêm ở phase sau.

Mỗi feature phải có nguồn, timestamp, đơn vị, cách tính, cửa sổ lookback, rule xử lý missing và version. Không thay missing bằng số 0 một cách vô thức. Dùng missing mask hoặc từ chối forecast nếu feature bắt buộc thiếu.

### 4.3 Feature consistency giữa Python và Kotlin

Đây là yêu cầu bắt buộc. Sai một scaler hoặc thứ tự feature là model có thể đưa dự báo vô nghĩa dù inference chạy thành công.

- Một `feature_schema.json` là nguồn sự thật: tên, thứ tự, dtype, unit, transform, missing policy, clipping range.
- Python training pipeline và Kotlin runtime phải tuân thủ cùng schema.
- Scaler mean/std/median/clip thresholds được tính chỉ trên train set và lưu cùng model bundle.
- Kotlin không được tự fit lại scaler trên live data.
- Tạo `golden_feature_vectors.json` gồm raw candles -> expected feature array.
- Viết parity tests: Python và Kotlin tạo feature phải khớp trong tolerance số học được định nghĩa.
- Tăng `featureSchemaVersion` khi thay thứ tự hoặc phép tính feature.

### 4.4 Data correctness

- Chỉ dùng candle đã đóng (`isClosed == true`) cho training và inference chính thức.
- Loại duplicate candle, kiểm tra gap, out-of-order, invalid OHLC, volume âm và timestamp sai.
- Dùng UTC milliseconds nhất quán.
- Join dữ liệu order book/funding/OI theo kiểu `as-of`: chỉ lấy dữ liệu đã tồn tại tại thời điểm forecast. Không lấy snapshot tương lai.
- Mỗi input có `asOfMs`, `source`, `freshnessMs`, `isEstimated`, `qualityFlags`.
- Nếu dữ liệu quan trọng stale hoặc là hardcode/default giả lập, không được phát forecast hợp lệ.
- Mỗi symbol có quality gate riêng; không cho BTC có dữ liệu tốt che lấp altcoin thiếu dữ liệu.

---

## 5. Data pipeline và training ngoài thiết bị

Tạo thư mục độc lập khỏi Android app, ví dụ:

```text
ml/
  forecasting/
    README.md
    configs/
      feature_schema.json
      gru_v1.yaml
      tcn_v1.yaml
      lstm_v1.yaml
      tiny_transformer_v1.yaml
    data/
      README.md
    src/
      fetch_klines.py
      validate_dataset.py
      build_features.py
      build_labels.py
      split_walk_forward.py
      train_baselines.py
      train_gru.py
      train_tcn.py
      train_lstm.py
      train_transformer.py
      calibrate.py
      evaluate.py
      export_litert.py
      compare_float_int8.py
    tests/
      test_no_leakage.py
      test_feature_parity.py
      test_label_generation.py
      test_export_parity.py
    artifacts/
      .gitkeep
```

Không commit raw market data dung lượng lớn, API secret hoặc file model thử nghiệm cũ vào Android APK.

### 5.1 Historical data collection

- Dùng nguồn dữ liệu có quyền truy cập hợp lệ và lưu provenance.
- Thu thập đủ nhiều market regimes: bull, bear, range, high volatility, crash, low liquidity.
- Ưu tiên lịch sử tối thiểu 1-2 năm nếu nguồn dữ liệu sẵn có; nếu thiếu phải ghi rõ giới hạn và không tuyên bố khả năng khái quát qua nhiều chu kỳ.
- Thu thập các symbol đang/đã niêm yết để giảm survivorship bias nếu có dữ liệu; ghi ngày niêm yết/delist.
- Paginate API; kiểm tra số nến kỳ vọng; deduplicate theo `(symbol, interval, openTime)`.
- Lưu raw dataset bất biến; feature dataset phải tái tạo được.
- Không dùng dữ liệu private trader hoặc thông tin cá nhân không được phép.

### 5.2 Split dữ liệu đúng cách

Cấm random train/test split đối với chuỗi thời gian.

Dùng:

- Chronological train/validation/test.
- Walk-forward rolling evaluation.
- Purge/embargo ít nhất bằng horizon lớn nhất quanh ranh giới fold để tránh target overlap.
- Fit scaler/calibrator chỉ trên train/validation thích hợp; tuyệt đối không fit bằng test.
- Test cuối cùng phải giữ nguyên đến khi chốt model.
- Lưu `train_start`, `train_end`, `validation_range`, `test_range`, symbols, data checksum và code commit.

### 5.3 Baselines bắt buộc

So sánh neural models với:

- Predict flat/no-change.
- Persistence / last return.
- EMA trend baseline.
- Logistic regression trên cùng feature set.
- Một tree model nhỏ như LightGBM/XGBoost nếu môi trường training hỗ trợ.

Neural model không được giữ chỉ vì training loss đẹp. Nó phải cải thiện out-of-sample so với baseline.

### 5.4 Metrics

**Forecast classification:**

- Log Loss.
- Brier Score.
- Expected Calibration Error (ECE).
- Balanced Accuracy.
- Precision/Recall/F1 theo class.
- PR-AUC khi class mất cân bằng.
- Confusion matrix theo từng horizon/regime/symbol.

**Forecast regression/distribution:**

- MAE/RMSE hoặc MAE/MASE cho expected return.
- Pinball loss cho quantiles.
- Quantile coverage.
- Directional accuracy chỉ là metric phụ, không phải tiêu chí duy nhất.

**Trading simulation:**

- Net expectancy sau taker/maker fee phù hợp.
- Slippage, spread, funding, latency và fill assumptions.
- Profit Factor.
- Maximum Drawdown.
- Sharpe/Sortino với cách annualize được mô tả.
- Turnover, trade count, exposure, tail loss.
- Hiệu suất theo regime, từng symbol, long/short và từng horizon.

Dùng block bootstrap hoặc phương pháp thống kê phù hợp để đánh giá độ bất định của chênh lệch hiệu suất. Không chỉ báo cáo điểm số tốt nhất.

### 5.5 Chống overfit và leakage

Phải tự kiểm tra các trường hợp:

- Scaler fit toàn bộ dataset.
- Label dùng high/low của nến tương lai nhưng feature cùng timestamp vô tình chứa thông tin đó.
- Nến chưa đóng được sử dụng như dữ liệu đã đóng.
- Join funding/open interest/order book từ timestamp tương lai.
- Dữ liệu bị duplicate hoặc gap.
- Test set đã được dùng để chọn hyperparameter.
- Một trade kéo dài qua nhiều label khiến samples overlap.
- Chọn symbol theo danh sách đang tồn tại gây survivorship bias.
- Backtest không trừ phí, funding, spread hoặc slippage.

Tạo test synthetic để cố tình đưa feature tương lai vào; kiểm tra leakage detector phải phát hiện hoặc fail.

---

## 6. Model export, quantization và versioning

### 6.1 Model bundle

Mỗi model phát hành phải đi kèm manifest, scaler, schema và checksum. Cấu trúc gợi ý:

```text
forecast_bundle/
  manifest.json
  model.tflite
  feature_schema.json
  scaler.json
  calibration.json
  golden_inputs.bin
  golden_outputs.json
  SHA256SUMS
```

`manifest.json` phải có tối thiểu:

- `modelId`, `modelVersion`, `createdAt`, `trainingDataRange`.
- `architecture`, `inputShape`, `outputSchema`.
- `timeframe`, `lookback`, `forecastHorizons`.
- `featureSchemaVersion`, ordered feature names.
- `scalerVersion`, `calibrationVersion`.
- `quantization`: float32/float16/int8, input/output quantization params.
- `runtimeRequirement`, allowed operators, model checksum.
- Evaluation metrics và baseline comparison.
- `minimumAppVersion`, `minimumRuntimeVersion`.
- `promotionStatus`: EXPERIMENTAL / SHADOW / APPROVED / ROLLED_BACK.

Không ghi API key, secret hoặc dữ liệu nhạy cảm vào bundle.

### 6.2 Quantization

- Train và đánh giá float model trước.
- Thử post-training full integer INT8 quantization với representative dataset đại diện nhiều regime.
- So sánh float vs INT8 trên cùng golden set.
- Kiểm tra calibration, confusion matrix, quantile coverage và simulated trading metrics sau quantization.
- Nếu INT8 gây giảm chất lượng vượt tolerance, thử float16 hoặc model nhỏ hơn; không cố ép INT8 bằng mọi giá.
- Model chỉ được release nếu converter chỉ dùng built-in ops runtime hỗ trợ. Tránh Select TF Ops/custom op để không phải mang thêm runtime lớn lên S7.
- Thử load/inference trên ABI thật của thiết bị.

### 6.3 Cập nhật model an toàn

- Chỉ nhận model qua HTTPS, kiểm tra hostname/certificate và checksum/signature.
- Model update phải atomic: tải vào file tạm, verify, smoke-test, rồi đổi active pointer.
- Giữ model trước đó để rollback.
- Không chạy model vừa tải trước khi xác minh checksum, schema, version, input/output shape và golden test.
- Không cho remote config bật LIVE hoặc thay đổi risk limits.
- Không nhận file model không giới hạn kích thước; đặt hard cap.
- Nếu offline, tiếp tục dùng model stable cũ hoặc status `MODEL_UNAVAILABLE`; không tải model bằng đường dẫn không tin cậy.

---

## 7. Android integration vào Astra Trader

### 7.1 Package layout đề nghị

Thêm package theo convention source hiện tại (`com.example`):

```text
app/src/main/java/com/example/forecast/
  domain/
    ForecastModels.kt
    ForecastStatus.kt
    ForecastRepository.kt
  data/
    MarketSequenceRepository.kt
    ForecastFeaturePipeline.kt
    ForecastFeatureSchema.kt
    ForecastScaler.kt
  runtime/
    ForecastModelRuntime.kt
    LiteRtForecastRuntime.kt
    ForecastModelLoader.kt
    ForecastModelRegistry.kt
  engine/
    ForecastCoordinator.kt
    ForecastEnsemble.kt
    ForecastCalibrationEngine.kt
    ForecastQualityGate.kt
    ForecastDriftMonitor.kt
  service/
    ForecastScheduler.kt
```

Tên có thể điều chỉnh, nhưng phải tách domain, data, runtime, orchestration. Không nhét toàn bộ inference và feature calculation vào `AstraTradingCoordinator.kt`.

### 7.2 Runtime abstraction

Tạo interface để có thể test bằng fake model và thay runtime:

```kotlin
interface ForecastModelRuntime : AutoCloseable {
    val modelId: String
    val modelVersion: String
    suspend fun load(modelBundle: ForecastModelBundle): ModelLoadResult
    suspend fun predict(input: ForecastTensorInput): RawForecastOutput
    override fun close()
}
```

Implement LiteRT runtime riêng. `Interpreter`/runtime instance không được gọi đồng thời nếu không thread-safe. Dùng single-thread dispatcher hoặc Mutex. Preallocate input/output buffers nếu API cho phép. Reuse arrays/ByteBuffers; tránh cấp phát tensor lớn mỗi candle.

### 7.3 Tích hợp dependency

Trong `gradle/libs.versions.toml`, quản lý phiên bản LiteRT tại một nơi. Trong `app/build.gradle.kts`, thêm runtime đã chọn. Sau khi thêm:

1. Clean/sync Gradle.
2. Build debug.
3. Build release.
4. Kiểm tra ABI/native libs.
5. Kiểm tra APK size/dex/native libs.
6. Cài lên S7 Edge thật.
7. Chạy golden inference.

Không thêm GPU runtime hoặc thư viện PyTorch training vào APK. Toàn bộ Python/TensorFlow training tool chỉ nằm ngoài Android project.

### 7.4 Forecast coordinator

`ForecastCoordinator` nên:

1. Nhận `symbol` + timeframe sau khi có candle mới đã đóng.
2. Kiểm tra data quality và đủ lookback.
3. Lấy sequence feature đúng thứ tự/version.
4. Normalize bằng scaler đóng gói cùng model.
5. Kiểm tra shape, NaN, range và missing mask.
6. Gọi đúng một active model.
7. Post-process logits/quantiles.
8. Áp calibration tương ứng model version + horizon.
9. Tính OOD/uncertainty và disagreement nếu có ensemble.
10. Ghi `ForecastResult` vào repository/database.
11. Phát StateFlow/event cho Strategy Engine.
12. Ghi latency, memory, model version, feature hash và status.

Forecast phải event-driven theo candle close; không inference liên tục theo từng tick. Không chạy hai forecast trùng symbol+horizon+inputAsOf. Dùng cache có TTL.

### 7.5 Không chặn luồng giao dịch

- Forecast chạy trên dispatcher/background worker riêng, ưu tiên thấp.
- Không chạy inference trong main/UI thread.
- Không giữ `globalCapitalMutex` trong lúc inference.
- Không giữ network/database transaction trong lúc model chạy.
- Forecast timeout hoặc crash không được dừng WebSocket/Risk/SL management.
- Nếu strategy bắt buộc model forecast nhưng forecast unavailable/stale/OOD, trả `NO_TRADE` cho strategy đó.
- Các chiến lược baseline không dùng neural model chỉ được tiếp tục nếu đã được phê duyệt riêng; tuyệt đối không âm thầm thay model lỗi bằng xác suất đoán.

### 7.6 Kết nối với `AstraTradingCoordinator`

Không cho neural model đặt lệnh. Luồng cần là:

```text
Closed Candle / Market Snapshot
        ↓
Data Quality Gate
        ↓
Feature Pipeline + Schema Check
        ↓
Forecast Coordinator
        ↓
ForecastResult (pUp/pDown/pFlat, returns, quantiles, uncertainty)
        ↓
Strategy Engine (kết hợp Quant + Forecast + Market Regime)
        ↓
Independent Skeptic / AI Review nếu cấu hình yêu cầu
        ↓
Risk Engine (nguồn quyết định cuối cùng về risk)
        ↓
Execution Gate
        ↓
Order Execution
```

Bất biến:

- Forecast không sửa risk limit.
- Forecast không sửa leverage cap.
- Forecast không tắt circuit breaker.
- Forecast không thay thế kiểm tra spread, exchange filters, account state, position, SL/TP.
- Forecast không mở khóa LIVE.
- Forecast không được dùng lại sau `validUntilMs`.
- Không cộng xác suất từ AI LLM vào xác suất model như thể chúng độc lập nếu chưa có thiết kế/calibration chống double counting.

---

## 8. Ensemble và calibration

### 8.1 Không average bừa

Nếu có GRU, TCN, LSTM:

- Lưu out-of-fold prediction cho từng model.
- Đánh giá tương quan lỗi và calibration.
- Chỉ dùng stacking/weighted ensemble nếu meta-model được train trên out-of-fold predictions, không dùng prediction in-sample.
- Trọng số phải version hóa và kiểm nghiệm ngoài mẫu.
- Tính model disagreement. Nếu disagreement cao, tăng uncertainty hoặc abstain.
- Khi một model unavailable, không tự renormalize trọng số thiếu mà không kiểm tra đó có phải cấu hình ensemble được duyệt.

### 8.2 Probability calibration

- Calibrate trên validation riêng: temperature scaling, isotonic hoặc Platt phù hợp với data.
- Calibration phải riêng theo horizon; cân nhắc regime khi đủ mẫu.
- Không dùng realized win rate thô làm xác suất cho một model khác.
- Không trộn calibration của model version A vào model version B.
- Khi thiếu sample hoặc Brier/ECE xấu, đánh dấu `UNCALIBRATED` và không cho probability đóng vai trò hard trigger.
- Đo calibration sau quantization, không giả định giữ nguyên.

### 8.3 Out-of-distribution (OOD) và uncertainty

Bản đầu dùng cách đơn giản, có thể kiểm thử:

- Feature range/robust z-score monitoring.
- Missing feature ratio.
- Mahalanobis distance hoặc một OOD detector nhỏ nếu có train data đủ.
- Ensemble disagreement.
- Prediction entropy.
- Market regime chưa có trong training coverage.

Nếu OOD cao, forecast không được đưa ra quyết định mạnh; trả `OUT_OF_DISTRIBUTION` hoặc tăng uncertainty. Không biến confidence thành một con số trang trí.

---

## 9. Strategy integration: dùng forecast thế nào cho đúng

Forecast output không phải lệnh BUY/SELL. Strategy Engine phải đánh giá:

- Expected move so với round-trip cost.
- Quantile downside / tail risk.
- Time horizon có khớp chiến lược không.
- Current regime có nằm trong training coverage không.
- Forecast còn hiệu lực không.
- Tín hiệu có đồng thuận hay mâu thuẫn với EMA/ATR/volume/order flow.
- Forecast disagreement và model uncertainty.
- Liquidity, spread, order book quality.
- Position exposure hiện có và risk budget.

Một signal mẫu phải thể hiện rõ:

```text
symbol=BTCUSDT
horizon=15m
p_up=...
p_down=...
p_flat=...
expected_net_return=...
q10/q50/q90=...
uncertainty=...
model_version=...
feature_schema_version=...
valid_until=...
status=VALID/UNCALIBRATED/OOD/STALE
```

Không sử dụng threshold ví dụ 68% như giá trị cố định. Threshold phải được lựa chọn từ validation, theo net expected value, class calibration, phí và risk profile. Nếu không có edge sau cost thì kết quả đúng là `NO_TRADE`.

---

## 10. Room database và lưu lịch sử forecast

Database hiện tại được mô tả là Room v4. Khi thêm bảng phải tạo migration mới (ví dụ v4 → v5), export schema và migration test. Không dùng destructive migration.

Bảng gợi ý:

**`forecast_predictions`**

- predictionId (PK)
- symbol, timeframe, horizon
- inputAsOfMs, generatedAtMs, validUntilMs
- modelId, modelVersion, featureSchemaVersion
- pUp, pDown, pFlat
- expectedReturn, q10, q50, q90, predictedVolatility
- uncertainty, oodScore, calibrated, status, rejectionReason
- inputSnapshotHash
- latencyMs, createdAtMs

**`forecast_models`**

- modelId + modelVersion
- architecture, runtime, schemaVersion, checksum
- fileSizeBytes, quantization
- trainingRange, evaluationSummary
- status, installedAtMs, lastUsedAtMs

**`forecast_outcomes`**

- predictionId (FK hoặc reference đã kiểm tra)
- realizedReturn theo horizon
- realizedNetReturn sau cost nếu mô phỏng execution
- realizedClass
- evaluatedAtMs
- dataQualityStatus

**`forecast_metrics`** (nếu cần)

- modelId/version/horizon/regime/time window
- sampleCount, Brier, ECE, LogLoss, MAE, quantile coverage
- evaluatedAtMs

Yêu cầu:

- Có index theo `(symbol, horizon, generatedAtMs)` và model/version.
- TTL/retention policy để database không phình.
- Không lưu toàn bộ tensor/sequence trong Room nếu không có lý do; lưu hash và snapshot reference.
- Lưu forecast outcome chỉ khi horizon đã hoàn tất và dữ liệu thực có chất lượng.
- Migration test phải giữ nguyên app settings, API config, orders, positions, fills, funding ledger và lịch sử cũ.

---

## 11. UI/UX cần thêm

Thêm một phần Forecast trong màn hình Overview hoặc Analytics; không tạo quá nhiều tab mới.

Hiển thị tối thiểu:

- Symbol + horizon.
- Up/Down/Flat probabilities.
- Expected return và P10/P50/P90.
- Predicted volatility.
- Uncertainty/OOD status.
- Model name/version.
- Input timestamp và thời điểm hết hạn.
- Calibration status + Brier/ECE gần nhất.
- Inference latency và model health.
- Lý do unavailable/rejected.

Dùng màu trung tính khi chưa hiệu chuẩn. Không hiển thị `68%` với badge xanh nếu chưa có evidence/calibration. Không biểu diễn model output như cam kết lợi nhuận.

Thêm màn hình/section diagnostics:

- Model installed / active / rollback version.
- Runtime backend và số thread.
- Model load time / inference p50-p95.
- RAM trước/sau inference.
- Temperature/thermal status nếu lấy được.
- Feature missing ratio, stale data count.
- Last successful forecast.
- Number of forecasts rejected and reasons.

---

## 12. Tối ưu cụ thể cho S7 Edge 4/64

### 12.1 CPU/RAM

- Một model active tại một thời điểm.
- Load model theo nhu cầu và release khi service dừng hoặc memory pressure cao; nếu giữ resident cần chứng minh không tăng OOM.
- Khởi đầu 1 inference thread; benchmark 2. Không tự đặt số thread bằng số core.
- Không batch quá nhiều symbols trong RAM.
- Reuse input/output tensors, buffers và arrays.
- Dùng FloatArray/ByteBuffer theo runtime yêu cầu; không tạo object BigDecimal trong vòng tính tensor nóng nếu không cần.
- Feature calculations có thể giữ BigDecimal ở phần accounting; tensor preprocessing dùng numeric float/double đã kiểm soát sai số.
- Tính model cho shortlist top N symbol sau coarse scanner, không chạy trên mọi symbol.
- Throttling khi app có memory pressure hoặc thermal status bất lợi.
- Không để inference giữ wake lock kéo dài.

### 12.2 Storage 64 GB

- Không bundling nhiều model lớn vào APK.
- Giới hạn model cache (đề nghị 25 MB ban đầu cho mọi phiên bản model được lưu, điều chỉnh theo benchmark).
- Giữ active + 1 rollback model.
- Dọn model thử nghiệm không còn reference.
- Giới hạn lịch sử prediction/outcome và log bằng TTL.
- Không tải tick-by-tick lịch sử vô hạn.

### 12.3 Battery và thermal

- Chỉ infer sau candle close hoặc event được chọn.
- Không chạy training/backtest nặng khi trading service đang LIVE.
- Không poll API thêm chỉ để phục vụ forecast nếu API budget không đủ.
- Tận dụng dữ liệu hiện có từ Binance Adapter; không mở nhiều WebSocket duplicate.
- Nếu nhiệt tăng qua threshold cấu hình hoặc service báo thermal severity: giảm forecast rate, chuyển sang model nhẹ hơn hoặc tắt forecast.
- Không hạ tần suất SL/position reconciliation để dành CPU cho AI.

### 12.4 ABI và native libraries

- Đọc ABI thật từ `ro.product.cpu.abilist`.
- Xác nhận APK chứa native runtime cho ABI của máy.
- Không ép `abiFilters` nếu chưa kiểm tra update compatibility.
- Nếu universal APK quá lớn, cân nhắc split APK sau khi xác nhận quy trình cài đặt của người dùng hỗ trợ split. Không làm mất khả năng cập nhật bản cũ.

---

## 13. Kiểm thử bắt buộc

### 13.1 Unit tests

- Feature formula correctness: EMA, RSI, MACD, ATR, RVOL, candle ratios, log returns.
- Sequence order: oldest → newest; không đảo trục.
- Scaling: đúng mean/std/clip từ manifest.
- Missing mask: không nhầm missing với zero thật.
- Label generation: horizon/fee/funding/slippage đúng.
- Probability constraints: sum-to-one, range, finite.
- Quantile monotonicity.
- Expiration/staleness.
- Calibration version isolation.
- Ensemble weight and disagreement.
- OOD threshold behavior.

### 13.2 Golden parity tests

Với cùng raw OHLCV/orderbook sample:

1. Python tạo feature vector.
2. Kotlin tạo feature vector.
3. So sánh theo feature index với tolerance đã định.
4. Đưa cùng tensor vào Python float model và Android LiteRT.
5. So sánh output float/int8 trong tolerance.
6. Test cả input bình thường, missing, high volatility, volume spike, gap và flat market.

### 13.3 Integration tests

- Market data → closed candle → feature → inference → forecast entity → Strategy Engine.
- Model absent/corrupt/incompatible.
- App offline.
- Update model bị gián đoạn.
- Rollback.
- Room migration v4 → v5.
- Android service restart.
- Concurrent scanner events không chạy inference duplicate.

### 13.4 Fault injection

Thử:

- NaN/Infinity trong feature.
- Một candle bị thiếu/duplicate/out-of-order.
- Funding/OI endpoint lỗi.
- Order book stale.
- RAM thấp.
- Runtime exception/timeout.
- File model hỏng/checksum sai.
- Unsupported operator.
- Probability sum sai.
- Model có version feature schema không khớp.
- DB full hoặc migration fail.

Kết quả bắt buộc: Forecast chuyển `UNAVAILABLE/REJECTED`, không treo service, không mở lệnh, không ảnh hưởng SL/TP/reconciliation.

### 13.5 Test thiết bị thật trên S7 Edge

Phải cài trên thiết bị người dùng, không chỉ emulator:

- Cold start model.
- Warm inference.
- 1 / 2 thread benchmark.
- 1 symbol, 5 symbols, watchlist tối đa.
- 2 giờ chạy liên tục.
- Chạy cùng Foreground Service + Binance WebSocket + Room + UI.
- Mất mạng, kết nối lại.
- Screen off.
- Low battery.
- App process restart.
- Thermal stress.
- Memory pressure và app background.

Ghi log có timestamp, model version, latency p50/p95, PSS, CPU, thermal status, battery delta nếu đo được. Không công bố số benchmark nếu chưa chạy trên đúng máy.

---

## 14. Cổng đánh giá và promote model

Một model chỉ được promote qua các trạng thái:

`EXPERIMENTAL → OFFLINE_VALIDATED → DEVICE_VALIDATED → SHADOW → APPROVED_FOR_STRATEGY`

Không có đường nhảy cóc.

### 14.1 OFFLINE_VALIDATED

- Không có data leakage đã biết.
- Walk-forward hoàn chỉnh.
- Có baseline comparison.
- Brier/LogLoss/ECE, quantile coverage và net backtest được báo cáo.
- Kết quả trên test chưa dùng để tune.
- Có breakdown theo regime/symbol/horizon.
- Quantized model được test nếu phát hành INT8.

### 14.2 DEVICE_VALIDATED

- Load/inference trên S7 Edge thành công.
- Native ABI/runtime tương thích.
- P95, RAM, app stability, thermal đạt target đã định.
- Golden parity đạt tolerance.
- Không làm chậm trading risk loop.

### 14.3 SHADOW

- Chạy song song nhưng không ảnh hưởng lệnh.
- Ghi forecast và sau đó đo outcome.
- Tối thiểu có đủ mẫu theo horizon/regime để tính calibration; số mẫu phải được định nghĩa từ phân tích thống kê, không chọn tùy tiện.
- So sánh model với baseline liên tục.

### 14.4 APPROVED_FOR_STRATEGY

- Có phê duyệt thủ công.
- Risk Engine vẫn là cổng cuối cùng.
- Model version và config được khóa/ghi audit.
- Có rollback tức thì.
- Không tự động bật LIVE.

---

## 15. Những lỗi/điểm phải rà soát lại trong source hiện có

Trước khi code forecasting, agent phải kiểm tra và xử lý các vấn đề nền có thể làm dữ liệu model sai:

1. **Return 1m/5m đang ước tính từ 15m:** thay bằng dữ liệu candle đúng timeframe hoặc đánh dấu không dùng trong model.
2. **Scanner có giá trị fallback/hardcode theo audit trước:** đảm bảo không đưa giá/spread/RVOL giả vào feature tensor. Nếu feature có nguồn giả lập, quality gate phải reject.
3. **Closed candle:** rà mọi đường lấy candle để chắc chắn không dùng candle chưa đóng.
4. **Data alignment:** đồng bộ timestamp OHLCV, order book, funding, OI.
5. **Feature hash/version:** hash phải bao gồm đúng symbol, timeframe, feature schema, dữ liệu đầu vào và timestamp.
6. **Calibration lịch sử:** mỗi prediction/outcome phải giữ forecast probability gốc theo đúng model version/horizon. Không gán ranking score hiện tại vào trade cũ.
7. **Database migration:** bảo toàn dữ liệu hiện tại.
8. **Runtime separation:** forecast không chạy bên trong mutex/global capital lock.
9. **Background reliability:** forecast scheduler phải tôn trọng Foreground Service nhưng không được là điều kiện để service/position protection sống.
10. **APK build:** model runtime chỉ xuất hiện trong Release nếu feature được bật; không mang training dependencies/Python vào APK.

Nếu audit code hiện tại cho thấy một nhận định cũ không còn đúng, phải trích file + method + test chứng minh và cập nhật báo cáo; không sửa theo kết luận lỗi thời.

---

## 16. Lộ trình triển khai từng phase

### PHASE 0 — Codebase verification

- Đọc toàn bộ `QuantMathEngine`, `BinanceAdapter`, `AstraTradingCoordinator`, Room entities/DAO/database, `TradingForegroundService`, Gradle và Manifest.
- Tạo `FORECASTING_GAP_ANALYSIS.md` với file/method/line, thành phần tồn tại, thiếu, nguy cơ.
- Chốt schema feature và data source thật.
- Kiểm tra thiết bị S7 Edge bằng ADB, xác định chipset/ABI/free RAM.
- Chưa thêm model hoặc đổi execution logic.

**Exit criteria:** Gap report có bằng chứng; không có feature nào dựa trên hardcode/fallback mà không được đánh dấu.

### PHASE 1 — Data and baseline

- Sửa đường dữ liệu timeframe 1m/5m/15m/1h nếu được dùng; bắt đầu với 15m thật.
- Tạo feature schema + Python/Kotlin parity.
- Tạo historical dataset validator và label builder.
- Tạo naive/EMA/logistic baseline.
- Tạo walk-forward + cost-aware backtest.

**Exit criteria:** Dataset có checksum, timestamp, gap audit, no-leakage test, metric baseline.

### PHASE 2 — Train GRU/TCN/LSTM challengers

- Huấn luyện GRU v1, TCN v1, LSTM v1 trên PC/VPS.
- Tối ưu chỉ trên train/validation; giữ test cuối cùng riêng.
- So sánh theo horizon/regime/symbol.
- Chỉ giữ model tốt hơn baseline, không giữ model vì tên kiến trúc.
- Tạo manifest và float model artifact.

**Exit criteria:** Model có báo cáo metrics, baseline comparison, data lineage và reproducible training config.

### PHASE 3 — Export and Android inference

- Export LiteRT model, quantize, golden test.
- Thêm runtime abstraction, loader, manifest validator.
- Thêm FeaturePipeline/Scaler Kotlin.
- Tạo ForecastCoordinator và output contract.
- Thêm Room v4→v5 migration.
- Kết nối Strategy Engine ở chế độ shadow, không gửi lệnh.

**Exit criteria:** Android golden inference khớp trong tolerance; migration pass; forecast error không ảnh hưởng service/trading.

### PHASE 4 — S7 Edge optimization

- Cài APK lên S7 Edge thật.
- Benchmark model, 1/2 threads, memory, CPU, thermal, battery.
- Giảm lookback/feature/model khi không đạt.
- Xác nhận 2 giờ chạy cùng trading runtime.
- Không bật GPU/NNAPI nếu chưa benchmark.

**Exit criteria:** Device report có số đo thật, không có crash/OOM, latency/RAM/thermal đạt ngưỡng đã chốt.

### PHASE 5 — Transformer research and shadow evaluation

- Train Tiny Transformer/PatchTST offline.
- So sánh cùng dataset/splits/baselines với GRU/TCN/LSTM.
- Thử quantization; kiểm tra unsupported ops.
- Chỉ deploy S7 nếu thực sự cải thiện và vượt device gate.
- Nếu không đạt, giữ Transformer trên server/PC hoặc loại bỏ. Không cố nhét vào app.

**Exit criteria:** Có báo cáo rõ Transformer thắng/thua và lý do; quyết định deploy có bằng chứng.

### PHASE 6 — Strategy promotion

- Chạy shadow đủ mẫu.
- Calibrate theo model/horizon.
- Kiểm tra uncertainty/OOD/disagreement.
- Kiểm nghiệm net trading edge sau mọi cost.
- Phê duyệt thủ công từng model version.

**Exit criteria:** Forecast có thể ảnh hưởng strategy nhưng không có quyền execution; LIVE vẫn khóa riêng.

---

## 17. Tiêu chí hoàn thành toàn bộ task

Agent chỉ được tuyên bố hoàn thành khi bàn giao đủ:

- [ ] `FORECASTING_GAP_ANALYSIS.md`.
- [ ] `feature_schema.json` có version.
- [ ] Python training pipeline có thể chạy lại.
- [ ] Dataset validator và leakage tests.
- [ ] Baseline metrics.
- [ ] GRU/TCN/LSTM evaluation report.
- [ ] Tiny Transformer report (ít nhất nghiên cứu offline; phải ghi rõ nếu không triển khai).
- [ ] Model bundle + manifest + checksum + scaler + calibration.
- [ ] LiteRT Android runtime.
- [ ] Kotlin/Python feature parity tests.
- [ ] Room migration và migration tests.
- [ ] Unit/integration/fault-injection tests.
- [ ] Android Release APK mới.
- [ ] APK Analyzer report: model assets, DEX, native libraries, ABI.
- [ ] S7 Edge real-device benchmark report.
- [ ] Shadow evaluation report.
- [ ] Rollback instructions.
- [ ] Danh sách vấn đề còn mở và rủi ro chưa giải quyết.

Nếu không có S7 Edge vật lý hoặc chưa thể chạy trên thiết bị thật, phải đánh dấu `DEVICE_VALIDATION_PENDING`; không được tự ghi PASS.

---

## 18. Chỉ thị bắt buộc cho AI coding agent

Đọc toàn bộ file này và source Astra Trader. Sau đó thực hiện công việc trực tiếp theo các Phase.

1. Không bắt đầu bằng cách tạo hàng loạt class rỗng. Rà source và lập gap analysis trước.
2. Không giả định RSI/MACD/CVD/OI/forecast đã có nếu chưa truy được implementation và unit test.
3. Không fake dữ liệu để làm model chạy. Dữ liệu thiếu phải được đánh dấu hoặc reject.
4. Không dùng random split cho time series.
5. Không train trên điện thoại S7 Edge.
6. Không đưa Transformer nặng vào APK chỉ vì người dùng nêu tên Transformer.
7. Không sửa `Risk Engine`, Panic Close, SL/TP hay LIVE arming để thuận tiện tích hợp forecast.
8. Không cho forecast gọi Binance order endpoint.
9. Không tự tạo xác suất calibration nếu sample chưa đủ.
10. Không bỏ qua model uncertainty, OOD, stale data và feature schema mismatch.
11. Mọi Room schema change phải có migration test; cấm destructive migration.
12. Mọi dependency mới phải nêu lý do, phiên bản, min SDK, ABI, impact APK/RAM và kiểm tra license.
13. Mọi thay đổi code phải kèm test phù hợp; báo cáo test phải lấy từ lần chạy thật.
14. Nếu build/test không chạy được, ghi chính xác command, error và phần chưa xác minh. Không khai PASS giả.
15. Giữ các model artifacts và training tools ngoài APK trừ model inference bundle cần thiết.
16. Chỉ chạy forecast sau candle close theo scheduler; không tạo loop nóng liên tục.
17. Nếu forecast unavailable, strategy model-dependent phải abstain; không tự phát lệnh theo fallback giả.
18. Trong mọi trường hợp, bảo toàn vốn và khả năng bảo vệ vị thế có ưu tiên cao hơn inference.

### Cách báo cáo sau mỗi phase

```text
PHASE:
STATUS: PASS / PARTIAL / BLOCKED
FILES_CHANGED:
IMPLEMENTATION_SUMMARY:
TESTS_EXECUTED:
TEST_RESULTS:
DEVICE_RESULTS: (nếu có)
PERFORMANCE: latency p50/p95, RAM, CPU, thermal
MODEL_METRICS: (nếu có)
KNOWN_ISSUES:
RISKS:
NEXT_PHASE:
```

Kết thúc phải có một bảng `Implemented / Tested / Not Tested / Deferred / Blocked`. Phân biệt rõ “đã viết code”, “đã build”, “đã test unit”, “đã test integration”, “đã test trên S7 Edge” và “đã shadow”. Không đánh đồng các trạng thái này.

---

## 19. Quyết định kiến trúc cuối cùng

**Phiên bản triển khai đầu tiên cho S7 Edge:**

- Baseline statistical/logistic.
- GRU nhỏ hoặc TCN nhỏ làm neural model đầu tiên; so sánh với LSTM nhỏ.
- LiteRT CPU inference, 1 thread trước.
- Context 64-96 nến 15m đã đóng.
- 24-32 feature CORE/optional đã version hóa.
- Forecast 15m/1h/4h.
- Train trên PC/VPS; chỉ inference trên S7.
- Chạy shadow trước, không ảnh hưởng execution.

**Transformer:** nghiên cứu và train ngoài thiết bị; chỉ deploy bản Tiny đã quantize khi benchmark thực tế chứng minh đáng giá. Không đạt thì giữ server-side/offline, không phải thất bại. Một mô hình nhẹ, được kiểm chứng, chạy ổn định trên phần cứng hiện có có giá trị hơn một Transformer lớn chạy nóng, chậm và thiếu tin cậy.

---

## 20. Tài liệu tham khảo

1. Google AI Edge — LiteRT for Android: https://developers.google.com/edge/litert/android
2. Google AI Edge — LiteRT overview: https://developers.google.com/edge/litert/overview
3. TensorFlow Lite XNNPACK: https://github.com/tensorflow/tensorflow/tree/master/tensorflow/lite/delegates/xnnpack
4. TensorFlow Lite NNAPI delegate status: https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/g3doc/android/delegates/nnapi.md
5. Galaxy S7 Edge chipset/specification variants (reference only; verify the actual handset via ADB): https://www.phonearena.com/phones/Samsung-Galaxy-S7-edge_id9818

---

**END OF RUNBOOK**
