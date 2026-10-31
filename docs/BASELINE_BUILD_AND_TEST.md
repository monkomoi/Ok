# ASTRA TRADER — PHASE 0 BASELINE BUILD & TEST LOG (`docs/BASELINE_BUILD_AND_TEST.md`)

**Ngày thực hiện:** 2026-10-30  
**Môi trường thực thi:** Linux 4.19.0-gvisor amd64 · JDK 21.0.13 (Temurin) · Gradle 9.3.1 · Python 3.11.2

---

## 1. Kiểm tra Công cụ Build & Wrapper tại Baseline

| Kiểm tra | Trạng thái Baseline | Ghi chú |
|---|---|---|
| `gradle/wrapper/gradle-wrapper.properties` | **CÓ** (`gradle-9.3.1-bin.zip`) | Khai báo đúng phiên bản Gradle `9.3.1` |
| `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` | **THIẾU Ở BASELINE** | Sẽ được bổ sung bằng `gradle wrapper` trong Phase 7 |
| Lệnh `gradle` hệ thống | **HOẠT ĐỘNG** (`Gradle 9.3.1`) | Tương thích trực tiếp với AGP `9.1.1` và Kotlin `2.2.10` |
| Python environment | **HOẠT ĐỘNG** (`Python 3.11.2`) | Có `urllib.request`, `unittest`, `json`, `math`, `hashlib`, `struct` |
| Kết nối Binance Public Data API | **HOẠT ĐỘNG** | `https://data-api.binance.vision/api/v3/klines` trả về HTTP 200 |

---

## 2. Đếm Test bằng Test Discovery Thực tế (Baseline)

Không lấy số từ report cũ; đếm trực tiếp từ mã nguồn và test runner:

1. **Python Unit Tests (`ml/forecasting/tests/`)**:
   - Lệnh: `python3 -m unittest discover -s ml/forecasting/tests -p "test_*.py" -v`
   - Số lượng test thực tế: **8 tests** (trong 4 file: `test_no_leakage.py`, `test_feature_parity.py`, `test_label_generation.py`, `test_export_parity.py`).
2. **Android JVM Unit Tests (`app/src/test/java/com/example/ExampleUnitTest.kt`)**:
   - Đếm `@Test`: **20 tests**.
3. **Android Robolectric Integration & UI Tests (`app/src/test/java/com/example/ExampleRobolectricTest.kt`)**:
   - Đếm `@Test`: **14 tests**.
- **Tổng số test thực tế tại Baseline:** **42 tests** (8 Python + 34 Android JUnit/Robolectric).

---

## 3. Kết quả Chạy Test & Build Nguyên bản tại Baseline

### 3.1. Python Forecasting Pipeline Tests
- **Lệnh:** `python3 -m unittest discover -s ml/forecasting/tests -p "test_*.py" -v`
- **Kết quả:** `Ran 8 tests in 0.42s — OK` (8/8 PASS).
- **Nhận xét kỹ thuật:** Tuy 8 test đều PASS nhưng bộ test baseline đang chạy trên dữ liệu synthetic của `fetch_klines.py` và chưa có bài test cho lỗi nhân dồn equity trong `evaluate.py`, chưa có test căn biên UTC 4h và chưa có test huấn luyện end-to-end đa fold.

### 3.2. Android Unit & Robolectric Tests
- **Lệnh:** `gradle :app:testDebugUnitTest`
- **Thời gian chạy:** `1m 55s` (`33 actionable tasks: 6 executed, 27 up-to-date`)
- **Kết quả:** `BUILD SUCCESSFUL` — **34/34 tests PASS** (`ExampleUnitTest`: 20 PASS, `ExampleRobolectricTest`: 14 PASS).
- **Cảnh báo trình biên dịch ghi nhận trong log nguyên bản:**
  - `w: file:///app/applet/app/src/main/java/com/example/forecast/engine/ForecastCoordinator.kt:328:13 Condition is always 'false'.` (Bằng chứng trực tiếp cho thấy `evaluatePreviousPredictionOutcomeIfEligibleLocked` có `prevPred = null` là một stub chưa hoàn chỉnh!).
  - `w: file:///app/applet/app/src/main/java/com/example/ui/screens/AiApiCheckerCard.kt:907:56 'val Icons.Filled.Chat: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.Chat.`

### 3.3. Debug APK Build (`compile_applet` / `assembleDebug`)
- **Kết quả:** `Build succeeded - the applet is compiled` (`.build-outputs/app-debug.apk`, kích thước `18 MB`).
