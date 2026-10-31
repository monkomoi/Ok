# ASTRA TRADER V5.1 — FULL REMEDIATION, HARDENING & UPGRADE RUNBOOK

**Loại tài liệu:** Master task giao trực tiếp cho AI lập trình / coding agent  
**Mục tiêu:** Đọc mã nguồn hiện tại, xác minh toàn bộ vấn đề, sửa tận gốc, bổ sung tính năng còn thiếu, kiểm thử hồi quy, đóng gói APK Release và bàn giao bằng chứng.  
**Nguồn đối chiếu ban đầu:**
- `apparchitect-30-oct-2026-19-20-55.zip`
- `Astra Trader.apk` (APK người dùng trích xuất từ điện thoại)
- `ASTRA_TRADER_REMEDIATION_MASTER_TASK.md` (bản remediation trước)

**Ngày lập runbook:** 2026-10-30  
**Mức độ rủi ro dự án:** CAO — ứng dụng có khả năng gửi lệnh Binance USDⓈ-M Futures bằng tài khoản thật.

> Đây là lệnh triển khai trên repository, không phải yêu cầu viết thêm một bản kế hoạch. Hãy sửa mã nguồn thật, chạy test thật, build thật và nộp bằng chứng. Không được đánh dấu hoàn thành chỉ vì đã thêm class, comment, nút UI hoặc test giả.

---

# 0. CHỈ THỊ BẮT BUỘC CHO CODING AGENT

## 0.1. Nhiệm vụ chính

Bạn được giao tiếp quản Astra Trader V5.1. Hãy thực hiện đầy đủ các công việc sau:

1. Đọc toàn bộ repository hiện tại, không chỉ các file được nhắc trong tài liệu.
2. Đọc các tài liệu `README.md`, `ARCHITECTURE.md`, `AUDIT_REPORT.md`, `REMEDIATION_PLAN.md`, `IMPLEMENTATION_REPORT.md`, `TEST_REPORT.md`, `CHANGELOG.md` và Master Spec nếu có.
3. Đối chiếu source với APK đã cung cấp. Xác định APK được build từ commit/source nào nếu có thể; không mặc định APK và ZIP cùng phiên bản.
4. Xác minh từng vấn đề trong runbook này. Nếu vấn đề đã được sửa trong source mới, chỉ ra commit/file/test chứng minh và không sửa lại một cách máy móc.
5. Sửa lỗi tận gốc theo thứ tự ưu tiên P0 → P1 → P2.
6. Bổ sung các module còn thiếu, nhưng chỉ sau khi nền tảng an toàn P0 đã đạt.
7. Viết test hồi quy cho từng lỗi; viết integration test cho luồng xuyên module và fault-injection test cho lỗi mạng/sàn/Android.
8. Chạy build, test, lint, kiểm thử APK và kiểm thử thiết bị thực tế khi môi trường cho phép.
9. Tạo APK Release mới, xác minh cấu hình bảo mật và đối chiếu nội dung APK với source.
10. Cập nhật tài liệu kiến trúc, changelog, test report và release notes dựa trên kết quả có thật.

## 0.2. Tuyệt đối không được làm

- Không dùng API key/secret thật của người dùng trong Unit Test, log, prompt AI, issue tracker hoặc bản build chia sẻ.
- Không tự ý bật LIVE, không gửi lệnh bằng tiền thật và không chuyển tài khoản người dùng sang chế độ giao dịch thật.
- Không dùng lệnh sàn thật để kiểm tra quyền giao dịch. Dùng MockWebServer/fake adapter/Binance Futures Testnet.
- Không retry mù lệnh có trạng thái chưa rõ. Với `UNKNOWN_EXECUTION_STATUS`, phải truy vấn theo client ID và đối soát account/position trước khi quyết định.
- Không dùng dữ liệu giả, dữ liệu mặc định hoặc dữ liệu cache hết hạn để cho phép đặt lệnh LIVE.
- Không tự suy diễn số dư, vị thế, giá khớp, phí, funding, margin, SL/TP hoặc trạng thái lệnh khi API lỗi/timeout.
- Không xóa Position, Order, Fill, Cashflow hoặc lịch sử tài chính khi chỉ một request thất bại.
- Không đánh dấu `OPEN_PROTECTED`, `CLOSED_CONFIRMED`, `SKEPTIC_PASS`, `ACCOUNT_VERIFIED`, `CANCELLED` hoặc `LIVE_READY` nếu chưa có bằng chứng tương ứng.
- Không để AI thay thế Risk Engine, sửa giới hạn rủi ro, tạo quantity cuối cùng, bỏ qua Exchange Filters hoặc gửi lệnh trực tiếp.
- Không thay đổi chiến lược, giới hạn vốn, risk per trade, leverage cap, margin mode, stop strategy hoặc cách tính PnL mà không ghi rõ thay đổi và bổ sung test.
- Không xóa dữ liệu database để làm migration/build/test chạy qua.
- Không giảm chất lượng bảo mật để tiện debug.
- Không dùng `catch (_: Exception) {}` để che lỗi trong các luồng giao dịch, đồng bộ, bảo mật hoặc runtime.

## 0.3. Quy tắc Fail-Closed

Mặc định phải từ chối mở vị thế mới khi có bất kỳ điều kiện nào sau:

- Không xác minh được tài khoản và equity từ đúng Binance Futures account.
- Clock drift chưa được kiểm tra hoặc vượt giới hạn cho phép.
- Market data không đủ, không mới, không đúng symbol hoặc không cùng nguồn thời gian.
- Exchange Info/filter chưa được tải thật hoặc đã hết hạn.
- Có OrderIntent ở trạng thái `PERSISTED`, `SUBMITTED`, `PARTIALLY_FILLED`, `UNKNOWN` chưa được đối soát.
- Có vị thế hoặc lệnh ngoài dự kiến chưa phân loại quyền sở hữu.
- Thiếu SL bảo vệ hoặc không thể xác minh SL đang hoạt động.
- AI Skeptic chưa chạy, không hợp lệ, trả lời mơ hồ hoặc chưa đạt ngưỡng chính sách.
- Risk Engine chưa duyệt hoặc EV/Calibration không hợp lệ cho mode đang chạy.
- Database đang migration/lỗi, SecretVault mất khả năng giải mã hoặc trạng thái runtime không đồng bộ.
- Android OS báo thiết bị quá nhiệt, thiếu RAM, mất mạng hoặc service không còn chạy đúng như trạng thái UI.

Khi Fail-Closed, phải dừng mở lệnh mới, giữ dữ liệu cần đối soát, ghi log đã khử bí mật và hiển thị lý do rõ ràng. Không được vô hiệu hóa đường thoát khẩn cấp khỏi vị thế hiện hữu; Emergency Exit phải có policy riêng, chỉ giảm/đóng rủi ro, không thể mở/increase exposure.

## 0.4. Mẫu xử lý bắt buộc cho mỗi vấn đề

Với từng ID công việc, ghi:

1. **Evidence:** file, class, function, dòng code và hành vi đang tồn tại.
2. **Reproduction:** điều kiện/lệnh/test tái hiện.
3. **Root cause:** nguyên nhân gốc, không chỉ triệu chứng.
4. **Design:** giải pháp và các state transition liên quan.
5. **Implementation:** thay đổi source thực tế.
6. **Tests:** test trước/sau, unit/integration/fault-injection.
7. **Evidence:** log lệnh build/test, kết quả và artifact.
8. **Residual risk:** giới hạn còn lại, giả định và việc cần người dùng thực hiện.

Không được bỏ qua bước nào chỉ vì việc sửa có vẻ đơn giản.

---

# 1. BASELINE ĐÃ QUAN SÁT TRONG GÓI HIỆN TẠI

Các thông tin dưới đây là baseline từ ZIP/APK đã gửi, không phải cam kết rằng repository mới nhất vẫn giống hệt. Hãy xác minh lại trước khi sửa.

## 1.1. Android / build baseline

| Thuộc tính | Giá trị quan sát được |
|---|---|
| Android package | `com.aistudio.astratrader.v51qnt` |
| Gradle namespace | `com.example` |
| Version name/code | `1.0` / `1` |
| minSdk | 24 |
| targetSdk | 36 |
| compileSdk | 36, minor API 1 trong cấu hình source |
| Ngôn ngữ | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Database | Room, schema version 4 |
| Network | OkHttp |
| Async | Kotlin Coroutines |
| APK được kiểm tra | Khoảng 18,20 MB |
| DEX | 12 file DEX trong APK |
| Project classes | Khoảng 651 class thuộc package ứng dụng trong kiểm tra APK trước |
| APK Manifest | Được quan sát có `debuggable=true` trong bản APK đã gửi; phải xác minh lại bằng công cụ độc lập |

## 1.2. Cấu trúc module chính trong source

- `engine/AstraTradingCoordinator.kt`
- `network/BinanceAdapter.kt`
- `network/NetworkConnectivityMonitor.kt`
- `ai/AiIntelligenceGateway.kt`
- `ai/AiApiCheckerEngine.kt`
- `quant/QuantMathEngine.kt`
- `quant/NewsEventGateEngine.kt`
- `security/SecretVault.kt`
- `telegram/TelegramBotController.kt`
- `service/TradingForegroundService.kt`
- `service/BootRecoveryReceiver.kt`
- `data/local/Entities.kt`
- `data/local/AstraDao.kt`
- `data/local/AstraDatabase.kt`
- `ui/AstraViewModel.kt` và các Compose screens.

## 1.3. Tài liệu và kiểm thử hiện có

- ZIP có các tài liệu audit, remediation, implementation, changelog và test report.
- ZIP ban đầu chỉ có `gradle/wrapper/gradle-wrapper.properties`; không thấy `gradlew`, `gradlew.bat`, `gradle-wrapper.jar`.
- Source có 14 annotation `@Test` trong `ExampleUnitTest.kt`, 10 trong `ExampleRobolectricTest.kt`, 1 trong `ExampleInstrumentedTest.kt`: tổng 25 annotation.
- `TEST_REPORT.md` lại tuyên bố 17 Unit + 9 Robolectric = 26 test.
- Build file không khai báo MockWebServer dependency; trong source test hiện tại cũng không tìm thấy tham chiếu `MockWebServer`, dù báo cáo mô tả test dùng MockWebServer.
- `AstraDatabase` đang dùng `exportSchema = false` và `.fallbackToDestructiveMigration(true)`.
- `PositionEntity` đang dùng `symbol` làm primary key.
- `runMultiSymbolCandidateScanner()` có nhánh fallback ticker/price/spread/RVOL hardcode và hiện chỉ phân tích sâu tối đa 6 mã.
- `fetchSymbolMarketPulse()` có nhánh dùng giá trị mặc định cho funding/index/bookTicker rồi vẫn có thể trả về kết quả thành công.
- `TradingForegroundService` có callback registration API nhưng trong source không tìm thấy call site đăng ký; cũng không tìm thấy call site của `recordStreamMessageReceived()`.
- `connectMarketStream()` và `connectUserDataStream()` có implementation nhưng cần xác minh toàn bộ call graph; trong source ban đầu không thấy được nối vào service runtime.
- `TelegramBotController` có xử lý một update đầu vào, nhưng không thấy transport polling `getUpdates` hoặc webhook; ViewModel có `simulateTelegramCommand()` dùng update ID giả.
- `NewsEventGateEngine.defaultCuratedFeed()` trả về các bản tin mẫu có URL `example.com`/địa chỉ mẫu và timestamp tương đối với thời điểm gọi; cần cách ly khỏi LIVE.
- Source dùng `BuildConfig.GEMINI_API_KEY` ở nhiều vị trí. `.env.example` có placeholder, nhưng README chỉ cách đưa khóa vào `.env`; nếu khóa thật được inject vào BuildConfig thì nó có thể bị trích xuất từ APK. Chưa kết luận APK hiện có chứa khóa thật vì `.env` thực tế không nằm trong ZIP.
- `BinanceAdapter` chưa thấy hàm đổi leverage hoặc margin type qua REST; chỉ parse margin/leverage từ account position snapshot.
- `AstraTradingCoordinator` đang mở entry với `positionSide = "BOTH"`.
- `verifyProtectiveOrderRecord()` kiểm tra trường trong response object; bản thân hàm không gọi API để xác minh độc lập lệnh đang mở trên sàn.
- Entry path có fallback `avgPrice` sang `sizing.entryPrice` khi giá khớp trả về không hợp lệ.
- `executePanicCloseStateMachine()` đang chọn `activePositions.first()`, đối soát vị thế theo symbol mà chưa lọc positionSide, và có nhánh fallback local quantity khi snapshot lỗi/không tìm thấy symbol.
- Panic Close có thể tiếp tục khi post-close snapshot thất bại; cancellation result chưa được kiểm tra trước khi cập nhật local status; Stage “FINAL_EXCHANGE_RECONCILIATION” hiện cần xác minh vì source không thể hiện một snapshot cuối mới sau cleanup.
- Panic Close đang tính commission với tỷ lệ hardcode `0.0005` và có fallback exit price sang Mark Price.
- `evaluateUnifiedExecutionGate()` có fallback tìm signal theo symbol khi không tìm thấy signalId chính xác; kiểm tra lineage chưa xác minh đầy đủ `sizingId`.
- `requestAiCandidateReview()` khởi tạo `independentSkepticPassed` thành biểu thức có thể true trước khi Skeptic thật sự chạy; cần sửa thành mặc định false.
- `TradingForegroundService` có các catch rỗng và có thể ghi nhận telemetry keepalive thành công ngay cả khi callback null.
- `BootRecoveryReceiver` đánh dấu yêu cầu reconciliation nhưng cần xác định chính sách tự khởi động service và trạng thái sau reboot.
- `TradingForegroundService` khai báo `foregroundServiceType="dataSync"`; cần kiểm tra giới hạn foreground service mới nhất theo target SDK/Android version và chính sách phân phối. Không được mặc định loại service này có thể chạy vô hạn 24/7.

---

# 2. MA TRẬN ƯU TIÊN

| Mức | Ý nghĩa | Quy tắc |
|---|---|---|
| P0 / BLOCKER | Có thể gây mở lệnh sai, đóng nhầm, mất dữ liệu tài chính, vị thế không được bảo vệ hoặc lộ secret | Phải sửa và test trước mọi LIVE |
| P1 / HIGH | Có thể gây tín hiệu sai, mất đồng bộ, runtime không đáng tin, báo cáo sai hoặc tính năng tuyên bố nhưng chưa hoạt động | Phải sửa trước production candidate |
| P2 / MEDIUM | Độ bền, hiệu năng, UX, khả năng vận hành và mở rộng | Sửa trước khi công bố ổn định |
| P3 / OPTIONAL | Tính năng nâng cao không bắt buộc cho V1 | Chỉ làm sau khi P0–P2 đạt |

**Không dùng phần trăm hoàn thiện tự đánh giá để thay thế nghiệm thu.** Mỗi hạng mục phải có trạng thái `NOT_STARTED`, `IN_PROGRESS`, `IMPLEMENTED_NOT_VERIFIED`, `VERIFIED_TESTS_PASS`, `BLOCKED` hoặc `DEFERRED_WITH_REASON`.

---

# 3. P0 — BLOCKERS: AN TOÀN VỐN, DỮ LIỆU VÀ QUYỀN THỰC THI

## P0-01. Viết lại Panic Close theo nguyên tắc xác minh trước, hành động sau

**File trọng tâm:** `AstraTradingCoordinator.kt`, `BinanceAdapter.kt`, `DomainModels.kt`, `Entities.kt`, `AstraDao.kt`.

### Vấn đề cần sửa

- Snapshot trước đóng thất bại có thể fallback sang `localSignedQty` rồi tiếp tục gửi lệnh.
- Snapshot thành công nhưng không tìm thấy symbol có thể bị hiểu như quantity local vẫn còn.
- Snapshot sau đóng thất bại không chặn cleanup/ghi Fill/xóa local position.
- Cancel SL/TP có thể thất bại nhưng local order vẫn được đánh dấu `CANCELED`.
- Không có xác nhận cuối cùng sau cleanup.
- Chỉ xử lý `activePositions.first()` dù database có thể có nhiều position.
- Vị thế đang được chọn/đối soát theo symbol; chưa đủ an toàn khi có LONG và SHORT cùng symbol.
- Giá thoát và phí có thể là estimate nhưng được ghi như dữ liệu thực.

### Cách triển khai

1. Tách kết quả đọc position thành các trạng thái rõ ràng:
   - `FETCH_FAILED`: request lỗi/timeout/401/403/429/5XX/schema invalid.
   - `SNAPSHOT_VALID_NO_POSITION`: request thành công, snapshot đầy đủ, đúng account/mode và không có position tương ứng.
   - `SNAPSHOT_VALID_POSITION`: position tồn tại với symbol + positionSide cụ thể.
   - `SNAPSHOT_INCONSISTENT`: duplicate/contradictory/malformed position rows hoặc dữ liệu không đủ.
2. Không sử dụng local quantity thay thế exchange quantity khi `FETCH_FAILED` hoặc `SNAPSHOT_INCONSISTENT`.
3. Nếu `SNAPSHOT_VALID_NO_POSITION`, tuyệt đối không gửi lệnh đóng. Chuyển sang đối soát lệnh bảo vệ và lệnh chờ của bot.
4. Nếu `SNAPSHOT_VALID_POSITION`, lấy quantity, side và positionSide từ exchange. Kiểm tra khớp account mode và ownership trước khi tạo lệnh.
5. Emergency Close chỉ được phép làm giảm rủi ro:
   - `reduceOnly=true` khi phù hợp One-way Mode.
   - Trong Hedge Mode phải dùng đúng `positionSide` và quy tắc API tương ứng; không gửi `reduceOnly` nếu Binance cấm tham số đó ở chế độ này.
   - Không bao giờ tạo exposure ngược hoặc mở vị thế mới.
6. Ghi `OrderIntent` trước request. Client ID phải ổn định, duy nhất theo `symbol + positionSide + position lifecycle/openedAt + purpose`, hợp lệ theo giới hạn Binance.
7. Khi submit timeout/5XX/connection reset, chuyển `UNKNOWN_EXECUTION_STATUS`, query theo client ID và account snapshot trước mọi lần gửi lại.
8. Với `PARTIALLY_FILLED`, lấy exchange position snapshot mới. Tính residual từ quantity thực tế sau khi xác minh order cũ đã terminal. Không dùng phép trừ local đơn thuần.
9. Nếu snapshot sau đóng thất bại: giữ Position, giữ OrderIntent, giữ trạng thái pending reconciliation; không ghi `CLOSED_CONFIRMED`, không xóa position, không chốt PnL.
10. Hủy protective orders bằng đúng loại endpoint (standard vs Algo). Sau mỗi cancel, query lại theo ID hoặc open-order list để xác minh trạng thái cuối.
11. Phân biệt `CANCEL_PENDING`, `CANCEL_UNKNOWN`, `CANCELED_CONFIRMED`, `FILLED`, `EXPIRED`.
12. Sau cleanup, thực hiện **một final account + position + open standard orders + open Algo orders snapshot mới**. Chỉ xác nhận đóng nếu:
    - Position tương ứng bằng 0.
    - Không còn lệnh mở bot-owned có thể tạo/mở lại exposure.
    - Không còn protective order bot-owned mồ côi.
    - Các cancel quan trọng đã được xác nhận terminal.
13. Định nghĩa rõ semantics của Panic:
    - Nếu đây là “Panic Close All Bot-Owned Positions”, phải xử lý toàn bộ bot-owned positions theo symbol + positionSide và trả báo cáo từng position.
    - Không tự động đóng vị thế MANUAL/EXTERNAL trừ khi có lựa chọn và xác nhận riêng.
    - Nếu chỉ đóng một vị thế, đổi tên/UI/notification để không tuyên bố đóng toàn bộ.
14. Nếu có nhiều vị thế, xử lý tuần tự có kiểm soát và tiếp tục báo cáo các position chưa đóng; một lỗi không được che giấu các position còn lại.
15. Chỉ ghi `CLOSED_CONFIRMED` sau khi final snapshot đạt.

### Test bắt buộc

- Pre-snapshot timeout → không submit; local position được giữ.
- Snapshot hợp lệ, không có position → không submit lệnh close.
- Hai vị thế LONG/SHORT cùng symbol → chỉ tác động đúng `positionSide`.
- Ba symbol khác nhau → Panic Close All xử lý cả ba hoặc report rõ position nào chưa xong.
- API submit timeout nhưng order được Binance nhận → query cùng client ID, không duplicate.
- API submit timeout và order không tồn tại → chỉ retry sau khi query xác nhận không tồn tại và policy cho phép.
- Partial fill → chỉ xử lý residual sau snapshot mới.
- Post-close snapshot timeout → không xóa position, không xác nhận closed.
- Cancel SL thất bại → local status không phải CANCELED.
- Cancel Algo thất bại → giữ `CANCEL_UNKNOWN`.
- Cancel standard và Algo phải dùng endpoint tương ứng.
- Final snapshot còn open Algo order → không xác nhận cleanup hoàn tất.
- Position external/manual → không bị đóng bởi Panic bot-owned.
- Panic chạy lại sau crash → tiếp tục idempotent, không duplicate close.

## P0-02. Sửa mô hình Position cho One-way/Hedge Mode

**File trọng tâm:** `Entities.kt`, `AstraDao.kt`, `AstraDatabase.kt`, `DomainModels.kt`, `AstraTradingCoordinator.kt`.

### Vấn đề

`PositionEntity` đang dùng `symbol` làm primary key. Binance Hedge Mode có thể đồng thời có LONG và SHORT trên cùng symbol. Hai dòng này sẽ đè nhau nếu chỉ dùng symbol làm key. `deletePosition(symbol)` cũng xóa theo symbol, không phân biệt positionSide. Panic Close và reconciliation đang tìm position theo symbol trước tiên.

### Cách triển khai

1. Chuyển primary key sang `positionKey` ổn định, được tạo từ `symbol + positionSide` (cộng account identifier nếu cần hỗ trợ nhiều account).
2. Tạo unique index phù hợp cho `symbol, positionSide, accountId`.
3. Tất cả DAO phải nhận đủ khóa: `getPosition(symbol, positionSide)`, `deletePosition(positionKey)`.
4. `PositionEntity` phải lưu rõ:
   - account/environment identity.
   - symbol.
   - positionSide `BOTH/LONG/SHORT`.
   - signed quantity hoặc quantity với quy ước rõ ràng.
   - ownership `BOT_OWNED/MANUAL_EXTERNAL/UNKNOWN`.
   - exchange snapshot timestamp.
   - entry/mark/liquidation price khi có dữ liệu đáng tin.
   - protective order IDs theo từng side.
5. Tạo migration không mất dữ liệu từ schema 4. Dòng cũ có symbol unique được chuyển thành `BOTH` nếu không có bằng chứng khác; ghi audit event về giả định migration.
6. Nếu chưa có đủ test cho Hedge Mode, V1 phải **chủ động phát hiện tài khoản Hedge Mode và chặn LIVE**, không được giả vờ hỗ trợ.
7. Không tuyên bố Hedge Mode hỗ trợ hoàn chỉnh cho đến khi Entry, Position, SL/TP, reconciliation, Panic Close, accounting và UI đều dùng positionSide xuyên suốt.

### Test bắt buộc

- LONG và SHORT cùng symbol tồn tại độc lập trong Room.
- Upsert LONG không ghi đè SHORT.
- Delete LONG không xóa SHORT.
- Reconcile khớp đúng từng positionSide.
- One-way account không bị đổi semantics.
- Hedge account mà app chưa hỗ trợ phải bị chặn LIVE với lý do rõ.

## P0-03. Loại bỏ destructive database migration và bảo vệ lịch sử tiền thật

**File trọng tâm:** `AstraDatabase.kt`, `Entities.kt`, `AstraDao.kt`.

### Vấn đề

`Room.databaseBuilder(...).fallbackToDestructiveMigration(true)` có thể xóa database/tables khi thiếu migration path. Với app giao dịch, điều này có thể làm mất OrderIntent, Orders, Positions, Fills, Funding, Cashflows và Risk Events.

### Cách triển khai

1. Bỏ `fallbackToDestructiveMigration(true)` khỏi production.
2. Bật `exportSchema = true`; đưa schema JSON vào version control.
3. Kiểm tra mọi cặp migration version liền kề và đường nâng cấp từ version cũ nhất còn hỗ trợ tới version hiện tại.
4. Viết migration test bằng `MigrationTestHelper` cho tất cả version.
5. Migration phải bảo toàn toàn bộ dữ liệu tài chính và khóa unique.
6. Khi thiếu migration, app phải dừng thao tác giao dịch, báo lỗi phục hồi và giữ file database; không tự xóa.
7. Có backup/restore an toàn:
   - Export mặc định không chứa secrets.
   - Data export có schema version, checksum và timestamp.
   - Import phải validate và không ghi đè database hiện tại nếu chưa xác nhận.
   - Secrets không được export plaintext; không tự export Android Keystore key.
8. Có hướng dẫn khôi phục khi Keystore invalidated hoặc người dùng đổi thiết bị.
9. Kiểm tra database integrity (`PRAGMA integrity_check`) ở luồng diagnostics, không chạy nặng trên main thread.

### Test bắt buộc

- Migrate v1 → latest, v2 → latest, v3 → latest, v4 → latest.
- Bảo toàn đầy đủ Fills, Orders, OrderIntents, Positions, Cashflows và settings.
- Thiếu migration phải fail-safe nhưng không xóa DB.
- DB corrupt phải chuyển trạng thái `DATA_RECOVERY_REQUIRED`, không mở LIVE.
- Unique key/trade ledger không bị duplicate sau migration.

## P0-04. Sửa Execution Gate: lineage phải khớp chính xác

**File trọng tâm:** `AstraTradingCoordinator.kt`, `Entities.kt`, `AstraDao.kt`.

### Vấn đề

Trong `evaluateUnifiedExecutionGate()`:
- Khi không tìm thấy `signalId` chính xác, code có thể fallback tìm một signal cùng symbol.
- Điều kiện lineage cần kiểm tra đầy đủ `signalId`, `decisionId`, `sizingId`, `riskApprovalId`; source hiện chưa xác minh `sizingId` chặt chẽ.
- Gate chỉ chặn `Skeptic == REJECT`; policy với `CAUTION` cần rõ ràng.
- Freshness cần được đánh giá lại tại thời điểm thực thi, không chỉ dựa trên một cờ cũ.

### Cách triển khai

1. Lấy signal bằng exact `signalId`; không fallback theo symbol.
2. Kiểm tra exact equality của tất cả lineage IDs:
   - signalId.
   - decisionId.
   - sizingId.
   - riskApprovalId.
3. Lưu Risk Approval như một entity/bản ghi có nguồn, thời gian, tham số đầu vào và decision ID; không chỉ lưu string ID không có bản ghi nguồn.
4. Kiểm tra signal thuộc đúng symbol/direction/strategy/expiry và cùng snapshot lineage với sizing.
5. Chụp một `ExecutionSnapshot` immutable tại thời điểm gate; mọi input phải có timestamp, source và data-quality status.
6. Freshness phải so sánh `now - asOf` theo ngưỡng cụ thể cho candle, mark price, bookTicker, account và Exchange Info.
7. Skeptic policy:
   - `PASS` chỉ khi agent riêng thực sự chạy, response hợp lệ và verdict đúng.
   - `CAUTION` không được tự động tương đương PASS. Quy định rõ có được phép paper trade hay không; mặc định không đủ điều kiện LIVE.
   - `VETO`, `UNKNOWN`, timeout, schema invalid, missing provider đều block.
8. Gate kiểm tra account position mode, leverage, margin type, open orders và manual/external positions.
9. Kiểm tra exposure/notional tổng, open position count và pending entry orders từ exchange, không chỉ local database.
10. Tách `GateDecision` thành danh sách `GateCheckResult` có mã, nguồn dữ liệu, timestamp, expected/actual và severity; không hardcode tất cả boolean output thành true khi chỉ return approved.
11. Gate phải được chạy lại ngay trước khi submit, sau khi mọi request AI hoàn tất và trước khi tạo OrderIntent.
12. Dùng transaction/DB lock cho tạo intent để hai coroutine không cùng vượt qua gate.

### Test bắt buộc

- Signal ID sai nhưng symbol đúng → block.
- Decision ID sai → block.
- Sizing ID thiếu/sai → block.
- Risk approval ID sai → block.
- Signal hết hạn giữa AI review và execution → block.
- Snapshot cũ dù cờ stale trước đó false → block.
- Skeptic chưa chạy/timeout/schema invalid/CAUTION/VETO → policy đúng.
- External/manual position đang mở → block entry mới.
- Hai execution request đồng thời → chỉ một OrderIntent được tạo.

## P0-05. Sửa Independent Skeptic mặc định FAIL-CLOSED

**File trọng tâm:** `AstraTradingCoordinator.kt`, `AiIntelligenceGateway.kt`, `QuantMathEngine.kt`.

### Vấn đề

`independentSkepticPassed` đang có thể được khởi tạo true trước khi provider Skeptic chạy. Nếu không có provider riêng, Analyst có thể vô tình được coi là đã vượt qua Skeptic.

### Cách triển khai

1. Khởi tạo `skepticExecuted = false`, `skepticPassed = false`.
2. Chỉ chọn provider có role Skeptic và secret slot riêng; không chọn cùng provider làm Analyst và Skeptic.
3. Không fallback `providers.firstOrNull()` khiến provider SKEPTIC/BACKUP vô tình làm Analyst nếu role không đúng.
4. Phân biệt `ANALYST_UNAVAILABLE`, `SKEPTIC_UNAVAILABLE`, `SKEPTIC_TIMEOUT`, `SKEPTIC_SCHEMA_INVALID`, `SKEPTIC_VETO`, `SKEPTIC_CAUTION`, `SKEPTIC_PASS`.
5. Chỉ `SKEPTIC_PASS` hợp lệ mới có thể góp phần tạo signal VALIDATED.
6. Lưu providerId/modelId/latency/prompt hash/result schema/status cho từng lần chạy.
7. Chống reuse report cũ: Skeptic report phải gắn với đúng signalId, decisionId, snapshotHash và chưa hết hạn.
8. Nếu muốn có fallback rule-based skeptic khi AI không sẵn sàng, phải định danh riêng là `RULE_ONLY`, không gắn nhãn Independent AI Skeptic PASS và không cho LIVE nếu policy yêu cầu AI skeptic.

### Test bắt buộc

- Không có provider Skeptic → không PASS.
- Provider Skeptic trùng Analyst → không được coi độc lập.
- Timeout/HTTP error/JSON invalid → không PASS.
- Report cũ thuộc snapshot khác → không PASS.
- Skeptic CAUTION/VETO → xử lý đúng policy.
- PASS chỉ khi provider riêng đã chạy và toàn bộ schema/data checks đạt.

## P0-06. Entry phải bảo vệ vị thế thật ngay sau khi khớp

**File trọng tâm:** `AstraTradingCoordinator.kt`, `BinanceAdapter.kt`, `QuantMathEngine.kt`.

### Vấn đề

- `avgPrice` bằng 0 có thể fallback sang giá dự kiến `sizing.entryPrice`.
- Entry có thể `PARTIALLY_FILLED` nhưng cần kiểm soát chính xác fill quantity và trạng thái protective orders.
- `verifyProtectiveOrderRecord()` chỉ validate dữ liệu trong response object, không tự query xác minh lệnh còn mở trên exchange.
- Nếu SL/TP không xác minh được, code ghi `PROTECTION_FAILURE` nhưng chưa thực hiện một quy trình fail-safe đảm bảo vị thế không bị bỏ trống bảo vệ.

### Cách triển khai

1. Với response Entry, yêu cầu `executedQty > 0`, status hợp lệ và dữ liệu fill đủ dùng.
2. Nếu `avgPrice <= 0` hoặc response thiếu, query order/trade history để lấy fill thật; không dùng intended entry price làm fill thực.
3. Lưu từng execution fill nếu API trả nhiều fill, không chỉ một average fill.
4. Recalculate SL/TP bằng actual weighted average fill và actual executed quantity.
5. Với partial fill:
   - Đối soát positionAmt thực tế.
   - Đặt protection cho exposure thực tế.
   - Theo dõi phần order entry còn lại; cancel/resolve phần chưa khớp theo policy.
6. Sau submit SL/TP, query lại bằng clientAlgoId/algoId hoặc open Algo Orders để xác minh độc lập.
7. Xác minh đủ: symbol, side, positionSide, order type, algoType, trigger price, workingType, closePosition/reduceOnly, status, ownership và quantity semantics.
8. Nếu SL chưa được xác minh:
   - Không được đánh dấu `OPEN_PROTECTED`.
   - Không tiếp tục mở lệnh mới.
   - Thực hiện fail-safe theo policy: thử đặt lại SL có giới hạn và query-first; nếu không thể bảo vệ thì emergency reduce-only close sau khi xác minh exchange position.
9. Nếu TP lỗi nhưng SL đã được xác minh, vẫn phải báo `PROTECTION_DEGRADED`, không tuyên bố bảo vệ hoàn chỉnh; có thể tiếp tục giữ vị thế chỉ khi policy cho phép rõ ràng.
10. Nếu cả SL/TP thất bại hoặc trạng thái không rõ, khóa hệ thống và kích hoạt quy trình thoát/đối soát an toàn.
11. Không gửi lệnh bù trùng khi request protective order timeout; query Algo order bằng clientAlgoId trước.
12. Lưu order ID, algo ID, client IDs và actual trigger price trong database.

### Test bắt buộc

- Entry FILLED có avgPrice hợp lệ.
- Entry FILLED nhưng avgPrice = 0 → query fill; không dùng estimated entry.
- Entry PARTIALLY_FILLED → bảo vệ đúng position quantity.
- SL ack 200 nhưng query open orders không thấy → không `OPEN_PROTECTED`.
- SL reject, TP success → policy đúng, không để vị thế unprotected.
- TP reject, SL verified → trạng thái degraded và cảnh báo.
- SL/TP request timeout nhưng exchange đã nhận → không duplicate.
- SL invalid trigger / -2021 → xử lý theo mark/contract price thực, không tạo trạng thái protected giả.

## P0-07. Không để Market Pulse trả dữ liệu giả dưới cờ Success

**File trọng tâm:** `BinanceAdapter.kt`, `DomainModels.kt`, `AstraTradingCoordinator.kt`.

### Vấn đề

`fetchSymbolMarketPulse()` có thể dùng default như funding rate `0.0001`, indexPrice = markPrice, bid/ask = markPrice và bid/ask quantity = 1 khi endpoint bookTicker thiếu/lỗi, trong khi kết quả tổng có thể vẫn `isSuccess=true`.

### Cách triển khai

1. Tách `PremiumIndexResult` và `BookTickerResult` rõ ràng.
2. Nếu markPrice, indexPrice hoặc fundingRate thiếu/parse fail/không hợp lệ thì trả lỗi dữ liệu.
3. Nếu bookTicker thất bại, trả `PARTIAL_DATA` chứ không trả success đầy đủ; không dùng markPrice thay bid/ask để tạo spread = 0.
4. Không đặt default funding rate dương trong LIVE. Thiếu funding data phải là null/unknown.
5. Không gán bidQty/askQty bằng 1 khi response thiếu.
6. Gắn từng trường với source timestamp, receive timestamp, age và quality flag.
7. Chỉ cho phép snapshot LIVE khi mọi feature thiết yếu đều đủ và freshness đạt.
8. Demo data phải đi qua interface riêng và gắn `DEMO_SIMULATED`; không thể bị convert thành `BINANCE_PUBLIC_REST`.

### Test bắt buộc

- Premium index fail → request fail.
- BookTicker 4XX/5XX/timeout → `PARTIAL_DATA`, spread/OBI không hợp lệ.
- Missing/zero/negative bid/ask → reject snapshot.
- Funding field absent → funding unknown, không được dùng 0.0001 mặc định.
- Stale timestamp → data stale.
- Không có dữ liệu thật → không có Gate path nào cho LIVE.

## P0-08. Tách rõ TESTNET, MAINNET READ-ONLY và MAINNET EXECUTION

**File trọng tâm:** `AstraTradingCoordinator.kt`, `BinanceAdapter.kt`, `AppSettingsEntity`, UI.

### Vấn đề

`resolveApiEndpointMode()` có thể trả mode MAINNET/LIVE khi `environmentMode=TEST` nhưng `binanceEndpointTarget=MAINNET`. Điều này có thể hợp lý cho read-only diagnostics, nhưng phải bảo đảm mọi mutation endpoint bị khóa tuyệt đối. Panic Close hiện gọi Trade API theo mode endpoint được resolve, vì vậy cần định nghĩa rõ semantics để tránh tình huống UI hiển thị TEST nhưng request close đi tới MAINNET.

### Cách triển khai

1. Tách ba cấu hình độc lập:
   - `MarketDataEndpoint`: MAINNET_PUBLIC / TESTNET_PUBLIC.
   - `AccountReadEndpoint`: MAINNET / TESTNET.
   - `OrderExecutionEndpoint`: DISABLED / TESTNET / MAINNET.
2. Không dùng một enum `EnvironmentMode` vừa thể hiện endpoint vừa thể hiện quyền trade.
3. Tạo `ExecutionCapability` không thể tự tạo từ UI string; chỉ được cung cấp bởi policy module sau checklist.
4. Mọi mutation endpoint phải kiểm tra capability tại adapter boundary, không chỉ kiểm tra ở ViewModel/Coordinator.
5. `TEST` mode không được có khả năng gửi mutation tới MAINNET, ngoại trừ một API riêng cho Emergency Exit đã được thiết kế và xác nhận.
6. Emergency Exit là capability riêng:
   - Chỉ reduce/close exposure đang tồn tại và đã xác minh.
   - Chỉ bot-owned position, trừ khi người dùng chọn manual position rõ ràng.
   - Phải có UI xác nhận riêng, hiển thị rõ `BINANCE MAINNET`.
   - Không tạo exposure mới, không thay SL/TP để tăng rủi ro.
   - Khi account snapshot không xác minh được thì không submit.
7. Mỗi request mutation phải log endpoint class, mode, capability ID, order intent ID; không log secret/signature.
8. Bật server-side/account API restrictions phù hợp; người dùng phải tự xác nhận API key không có withdrawal permission và nên bật IP allowlist.

### Test bắt buộc

- TEST + MAINNET read-only → mọi POST/PUT/DELETE trading endpoint bị chặn.
- TESTNET mode → chỉ đúng host testnet.
- LIVE không đủ checklist → adapter từ chối mutation ngay cả khi được gọi trực tiếp.
- Panic trong TEST → không vô tình đi tới MAINNET.
- Emergency Exit trên Mainnet → chỉ reduce-only/close đúng bot-owned position được xác minh.
- Redirect → không chuyển API Key/signature sang host khác.

## P0-09. Không nhúng API secret vào BuildConfig/APK

**File trọng tâm:** `app/build.gradle.kts`, `.env.example`, `README.md`, `AiIntelligenceGateway.kt`, `AiApiCheckerEngine.kt`, `AstraViewModel.kt`.

### Vấn đề

Source dùng `BuildConfig.GEMINI_API_KEY`. Secrets Gradle Plugin có thể inject giá trị `.env` vào BuildConfig. Nếu người dùng đưa API key thật vào build, khóa có khả năng bị lấy ra từ APK/DEX.

### Cách triển khai

1. Loại bỏ việc dùng compile-time `BuildConfig.GEMINI_API_KEY` cho credential thật.
2. Không dùng `.env` để embed secret client-side vào APK Release.
3. Tất cả provider keys do người dùng nhập phải đi vào SecretVault hoặc secure credential store đã được kiểm tra.
4. Nếu kiến trúc cần key server-side, chuyển request qua backend có auth/rate limit; không nhúng service secret vào app.
5. `.env.example` chỉ giữ placeholder không có giá trị sử dụng.
6. Tạo script scan APK/DEX/resources/assets/source bundle để tìm:
   - `AIza` pattern / Gemini-like keys.
   - `sk-`, `sk-ant-`, Bearer token patterns.
   - Binance key/secret patterns.
   - Telegram bot token pattern.
   - Private URLs, signing passwords, debug tokens.
7. Không đưa secret vào log, crash report, AI prompt, diagnostic JSON hoặc clipboard ngoài ý muốn.
8. Nếu APK/source cũ từng chứa khóa thật, đánh dấu compromised và yêu cầu owner rotate/revoke khóa đó.
9. Nếu có lý do đặc biệt phải dùng BuildConfig cho public non-secret config, document rõ public/secret distinction.

### Test bắt buộc

- Release APK không chứa test key hoặc real key fixture.
- Build với placeholder và build không `.env` đều hoạt động trong trạng thái `UNCONFIGURED`.
- API key chỉ có trong Vault sau khi người dùng nhập.
- Log/diagnostic output không chứa secret.

## P0-10. Set và xác minh leverage/margin mode trước khi Entry

**File trọng tâm:** `BinanceAdapter.kt`, `AstraTradingCoordinator.kt`, `QuantMathEngine.kt`, `AppSettingsEntity`.

### Vấn đề

Source có parse `leverage` và `marginType` trong account position snapshot, Risk Engine tính sizing dựa trên leverage setting, nhưng không thấy endpoint đặt leverage/margin type trước Entry. Entry path hiện truyền `positionSide="BOTH"`. Vì vậy leverage cap trong app có thể không trùng leverage thật đang cấu hình ở Binance.

### Cách triển khai

1. Xác minh từ tài liệu Binance Futures USDⓈ-M mới nhất các endpoint/parameter cần thiết cho:
   - Query position mode (One-way/Hedge).
   - Query leverage bracket/risk limits nếu cần.
   - Change leverage theo symbol.
   - Change margin type (ISOLATED/CROSSED) nếu app hỗ trợ.
2. Trước Entry, lấy trạng thái account hiện tại. Nếu leverage thực khác intended cap:
   - Thực hiện đổi bằng endpoint đúng khi policy cho phép; hoặc
   - Từ chối Entry và yêu cầu người dùng cấu hình thủ công.
3. Query lại trạng thái sau khi đổi. Không dựa vào HTTP 200 đơn lẻ.
4. Xác minh `positionSide` mode. Nếu app V1 chỉ hỗ trợ One-way, query dualSidePosition và chặn LIVE với Hedge Mode.
5. Không tự đổi margin mode khi có position/open order nếu Binance không cho phép hoặc việc đổi có thể ảnh hưởng vị thế.
6. Position Sizing phải dùng leverage/margin mode đã xác minh, không chỉ giá trị UI.
7. Kiểm tra maxNotional, leverage brackets, maintenance margin và liquidation distance.
8. Ghi actual leverage/margin mode vào OrderIntent, Position và audit event.

### Test bắt buộc

- Actual leverage bằng requested → proceed.
- Actual leverage vượt cap và không đổi được → block.
- Đổi leverage thành công nhưng query xác minh fail → block.
- Hedge Mode nhưng app chưa hỗ trợ → block.
- Margin type không hợp lệ/không thể đổi khi có position → không submit Entry.

## P0-11. Bảo vệ vị thế khi protective order lỗi

**File trọng tâm:** `AstraTradingCoordinator.kt`, `BinanceAdapter.kt`.

### Vấn đề

Nếu SL/TP không xác minh được, source lưu position vào `PROTECTION_FAILURE` hoặc `PROTECTION_UNKNOWN` và ghi risk event, nhưng không thấy quy trình đảm bảo vị thế có SL thật hoặc được đóng an toàn ngay lập tức.

### Cách triển khai

1. Định nghĩa policy cho từng trường hợp:
   - SL confirmed + TP confirmed → `OPEN_PROTECTED`.
   - SL confirmed + TP fail → `OPEN_PROTECTED_DEGRADED` chỉ khi policy rõ; cảnh báo và không mở thêm position.
   - SL fail/unknown → emergency protection retry/query-first; nếu không bảo vệ được thì emergency exit.
   - Cả hai fail/unknown → emergency exit.
2. Không gọi Panic Close cũ trước khi P0-01 hoàn thành.
3. Nếu SL submit timeout, query theo clientAlgoId trước khi gửi lại.
4. Thời gian không có SL phải được đo và log; đặt timeout tối đa theo risk policy.
5. Có kill switch `NO_NEW_ENTRY` ngay khi protection degraded.
6. Không để TP tồn tại sau khi position đã đóng, nhưng cũng không hủy SL đang bảo vệ position khi chưa xác minh position flat.

### Test bắt buộc

- SL success + TP success.
- SL failure + TP success.
- SL unknown + TP success.
- SL success + TP failure.
- Cả hai fail.
- Cancel protection thất bại sau close.
- App crash giữa Entry và protective orders: boot reconciliation phải phát hiện naked exposure.

## P0-12. Không ghi accounting giả

**File trọng tâm:** `AstraTradingCoordinator.kt`, `BinanceAdapter.kt`, `Entities.kt`, `AstraDao.kt`.

### Vấn đề

Panic Close hiện có thể:
- Dùng Mark Price thay avgPrice làm exit price.
- Dùng commission rate hardcode `0.0005`.
- Chỉ ghi một tổng fill với trade ID tự tạo `TRD_symbol_orderId`.
- Bảng Funding Ledger có nhưng adapter chưa thấy endpoint/population rõ ràng cho actual funding income.

### Cách triển khai

1. Tạo ledger cho từng execution fill bằng exchange trade ID thật, symbol và order ID.
2. Lấy actual entry/exit trade records; ghi commission thực và commission asset.
3. Lấy realized PnL/funding/commission/income từ Binance income/trade history endpoints phù hợp sau khi xác minh API docs mới nhất.
4. Không dùng một phí cố định để báo cáo như số thực. Estimate phải có field `isEstimated=true`, source, fee tier/time và timestamp.
5. Tách:
   - Gross realized PnL.
   - Entry commission.
   - Exit commission.
   - Funding paid/received.
   - Other exchange adjustments.
   - Net realized PnL.
6. Không giả định commission asset luôn USDT; lưu asset gốc và quy đổi có nguồn nếu dashboard cần USDT.
7. Xử lý multiple fills, partial fills, partial closes và fee rebates.
8. Dùng composite key/idempotency để cùng trade không ghi hai lần.
9. Nếu chưa có dữ liệu đầy đủ, đặt `ACCOUNTING_PENDING` và cập nhật khi API query thành công.
10. Daily Snapshot phải tái dựng từ ledger có kiểm tra, không cộng lại những record duplicate.

### Test bắt buộc

- Multiple fills một order.
- Entry partial + exit partial.
- Commission asset khác USDT.
- Funding paid và received.
- Duplicate sync không duplicate ledger.
- API income history timeout → PnL pending, không giả thành zero.
- Mark Price không bị ghi thành execution price.

## P0-13. Bảo vệ Execution khỏi nhiều trigger và crash giữa chừng

**File trọng tâm:** `AstraTradingCoordinator.kt`, `AstraDao.kt`, `AstraDatabase.kt`, `TradingForegroundService.kt`, `AstraViewModel.kt`.

### Vấn đề

`Mutex` trong process không đủ để bảo vệ các đường gọi từ UI, service, receiver, Telegram và process restart. OrderIntent có persistence nhưng cần transaction/lock toàn cục và state recovery rõ ràng.

### Cách triển khai

1. Tạo execution lock trong database với unique active lock, expiry và owner/process instance ID.
2. Tạo OrderIntent trong transaction atomically cùng việc claim lock.
3. Chỉ một worker được phép thực hiện mutation cho mỗi account/environment.
4. Sau process death, lock hết hạn không có nghĩa được retry ngay; phải reconciliation các OrderIntent trước.
5. State machine phải persisted, không chỉ nằm trong StateFlow/memory.
6. Service, UI và Telegram chỉ gửi command vào một ExecutionCoordinator duy nhất.
7. Không cho nhiều ViewModel/service tạo nhiều `AstraTradingCoordinator` độc lập nếu chúng chia sẻ account state.
8. Dùng idempotency key cho Entry, Stop, TP, Cancel và Panic Close.
9. Thực hiện reconciliation sau app start, reboot, websocket reconnect và khi phát hiện account update bất thường.

### Test bắt buộc

- Double tap Execute.
- UI và service trigger cùng lúc.
- Telegram và UI trigger cùng lúc.
- App process chết sau persist OrderIntent nhưng trước POST.
- App process chết sau Binance nhận lệnh nhưng trước local update.
- Restart khi OrderIntent `UNKNOWN`.

---

# 4. P1 — HIGH: DỮ LIỆU, SCANNER, AI, NETWORK VÀ RUNTIME

## P1-01. Viết lại Multi-Symbol Scanner bằng dữ liệu thật theo từng symbol

**File:** `AstraTradingCoordinator.kt`, `BinanceAdapter.kt`, `DomainModels.kt`.

### Vấn đề đã quan sát

- Nhánh ticker API lỗi có danh sách symbol/quoteVolume hardcode.
- Giá từng symbol có thể lấy hardcode.
- Spread mặc định `0.0004`.
- RVOL mặc định dựa trên minNotional feasibility.
- Regime mặc định `BREAKOUT_CANDIDATE`.
- Direction có thể là LONG chỉ vì minNotional phù hợp với vốn.
- Chỉ lấy tối đa 6 mã trong top 30.
- AI validator có `validSymbols` hardcode chỉ 6 symbol.

### Cách triển khai

1. Tạo universe từ Exchange Info thật, chỉ lấy symbol đang TRADING, quote asset phù hợp và contract type phù hợp.
2. Lọc stable/leveraged tokens, delisted/paused contracts, symbols có filter bất thường, volume thấp và dữ liệu thiếu.
3. Sắp xếp theo quote volume 24h thật, nhưng không dùng volume làm tín hiệu hướng giá.
4. Với mỗi symbol ứng viên, lấy actual Klines, mark/index/funding, bookTicker và metadata. Giới hạn concurrency và API weight.
5. Tính feature độc lập theo từng symbol. Không reuse snapshot của symbol trước.
6. Nếu thiếu bất kỳ dữ liệu quan trọng: `REJECTED_INSUFFICIENT_DATA`, không thay bằng giá trị mặc định.
7. Tách feasibility filter khỏi alpha score:
   - MinNotional feasibility chỉ cho biết có thể giao dịch theo vốn/filters.
   - Direction LONG/SHORT phải đến từ strategy signal thực.
8. Tính scanner score từ feature đã chuẩn hóa, gồm liquidity, spread, volatility suitability, RVOL, regime, signal quality và expected net edge.
9. Tránh thiên lệch do scale khác nhau; chuẩn hóa features theo symbol/universe và lưu feature version.
10. Dùng Exchange Info thật cho minQty/stepSize/tickSize/MARKET_LOT_SIZE/minNotional/maxQty/percent price constraints.
11. Dynamic allowed symbols của AI phải đến từ exchange metadata + scanner allowlist, không hardcode danh sách 6 coin.
12. Nếu scanner không lấy đủ dữ liệu thì trả `NO_CANDIDATES_DATA_UNAVAILABLE`, không hiển thị top 5 giả.
13. Có configurable scan universe size, concurrency, rate budget và cooldown.
14. Mỗi candidate phải lưu `dataSource`, `snapshotHash`, `asOf`, `qualityFlags`, `featureVersion`.

### Test

- Ticker API timeout → zero live candidates, không fallback giả.
- Một symbol có dữ liệu đầy đủ, một symbol thiếu bookTicker → chỉ symbol đủ điều kiện được xếp hạng.
- MinNotional pass không tự tạo LONG.
- Candidate SHORT được xếp hạng đúng.
- Dynamic symbol ngoài danh sách cố định có thể qua AI schema khi exchange metadata xác nhận.
- Hết hạn Exchange Info → scanner không sử dụng filter cũ như mới.

## P1-02. Tạo market data pipeline thống nhất

**File:** `BinanceAdapter.kt`, `NetworkConnectivityMonitor.kt`, `TradingForegroundService.kt`, `AstraTradingCoordinator.kt`.

### Cần nâng cấp

1. Tạo `MarketDataRepository` duy nhất cho REST snapshot, WebSocket stream và cache.
2. Track event time, receive time, exchange server time và local monotonic time.
3. Phân biệt:
   - `CONNECTED`.
   - `SUBSCRIBED`.
   - `FIRST_DATA_RECEIVED`.
   - `STALE`.
   - `RECONNECTING`.
   - `RESYNC_REQUIRED`.
4. `lastStreamMessageUtcMs` phải được cập nhật mỗi lần nhận event hợp lệ; đồng bộ với service telemetry.
5. Khi WebSocket reconnect, phải REST resync account/open orders/positions và market state trước khi resume strategy.
6. Dùng combined streams cho nhiều symbol nếu cần; không disconnect stream của symbol khác chỉ vì đổi symbol.
7. Nếu sử dụng order book depth, implement snapshot + diff update sequence đúng quy định Binance; không cộng order book update thiếu sequence.
8. Nếu chỉ dùng `bookTicker`, đặt tên feature chính xác là top-of-book imbalance, không gọi là full depth OBI.
9. Phân biệt mark price stream, bookTicker stream, user data stream; mỗi luồng có stale threshold riêng.
10. Không thực hiện strategy từ stream đang reconnect hoặc dữ liệu out-of-order.
11. Reconnect exponential backoff + jitter phải thực sự được dùng trong runtime, có reset khi healthy và giới hạn số lần.
12. Subscribe/unsubscribe lifecycle được quản lý tập trung, không tạo duplicate socket.

### Test

- Wi-Fi → 4G → mất mạng → có mạng lại.
- WebSocket onFailure/onClosed.
- Không có message đầu tiên.
- Out-of-order timestamp.
- Reconnect thành công nhưng REST reconciliation lỗi.
- User stream listenKey expired.
- Hai symbol subscribe song song.

## P1-03. Hoàn thiện Foreground Service và Background Runtime thực sự

**File:** `TradingForegroundService.kt`, `BootRecoveryReceiver.kt`, `AstraViewModel.kt`, `BinanceAdapter.kt`, DI/Coordinator.

### Vấn đề đã quan sát

- `registerRuntimeCallbacks()` có định nghĩa nhưng không thấy call site.
- `recordStreamMessageReceived()` có định nghĩa nhưng không thấy call site.
- Market/User Data WebSocket có code nhưng chưa thấy nối vào lifecycle service.
- `keepAliveCallback` null vẫn có thể cập nhật `lastListenKeyKeepAliveUtcMs` như đã thành công.
- Empty catch blocks làm mất bằng chứng lỗi.
- Reconnect delay helper cần xác minh có được sử dụng.
- Service loop hiện có thể chỉ là monitoring heartbeat, chưa chắc gọi scanner → strategy → risk → execution.
- Boot receiver đánh dấu reconciliation nhưng không tự start runtime; cần xác định policy.

### Cách triển khai

1. Xây dựng `AstraRuntimeController`/application container có lifecycle rõ:
   - create dependencies.
   - restore state.
   - verify credentials availability.
   - start/stop market stream.
   - start/stop user data stream.
   - create/keepalive listenKey.
   - register heartbeat, reconcile, keepalive, reconnect callbacks.
   - start strategy scheduler theo policy.
2. Chỉ cập nhật heartbeat success khi callback thực sự trả success. Null callback phải `CALLBACK_NOT_REGISTERED`, không phải success.
3. `recordStreamMessageReceived()` phải được gọi từ listener khi nhận event parse hợp lệ.
4. ListenKey keepalive phải phân biệt CREATED/RENEWED/FAILED/UNKNOWN; cập nhật telemetry chỉ sau HTTP response và validation thành công.
5. `onRuntimeHeartbeatTick` không được reconnect mỗi 15 giây khi chưa có stream đầu tiên; có startup grace period và explicit connection state.
6. Reconnect callback phải áp dụng `computeReconnectDelayMs()` thật, không tạo socket liên tục.
7. Sau reconnect, bắt buộc reconciliation trước resume.
8. Không swallow exception. Ghi structured event có correlation ID, sanitized message, category và retryability.
9. Service không tự mở lệnh chỉ vì được start; phải load config, check state, check permission và user arming.
10. Chống duplicate service/controller/strategy loop cả trong process và persistent database lock.
11. Boot recovery chỉ đặt trạng thái `RECONCILIATION_REQUIRED`; không bật LIVE và không gửi order. Quyết định auto-start service phải phù hợp Android restrictions và user preference.
12. Khi stop service, disconnect socket, cancel jobs, release WakeLock và cập nhật telemetry nhất quán.
13. Có trạng thái service runtime tách khỏi trạng thái bot: `SERVICE_STOPPED`, `SERVICE_STARTING`, `SERVICE_HEALTHY`, `SERVICE_DEGRADED`, `SERVICE_FAILED`.
14. Hiển thị “đang hoạt động” chỉ khi heartbeat/runtime callback thật sự healthy, không chỉ khi Service instance tồn tại.
15. Kiểm tra tài liệu Android chính thức mới nhất cho targetSdk 36 và Android 14/15/16 về foreground service types, dataSync time limits, background start restrictions, notification permission và Play policy. Không đổi sang `specialUse`/type khác một cách tùy tiện; chỉ khai báo type hợp lệ với mục đích thật.
16. Không tuyên bố Android consumer device có thể bảo đảm uptime 24/7. Thiết kế an toàn khi Android kill process, Doze, OEM battery manager, mất sóng, hết pin hoặc force-stop. Nếu yêu cầu uptime tài chính cao, đề xuất runtime worker trên VPS/server và Android làm dashboard/controller; không dùng root hack để lách OS.

### Test bắt buộc

- Callback chưa đăng ký → telemetry degraded.
- ListenKey callback fail/null → không cập nhật thời điểm success.
- Không có first stream message → reconnect theo startup timeout.
- Stream stale → backoff thực tế.
- Reconnect nhưng reconcile fail → bot vẫn block new entries.
- Reboot → LIVE vẫn disarm và trạng thái cần reconcile.
- Force stop/manual stop → UI hiển thị đúng.
- Notification permission denied → UX và policy xử lý rõ.
- Doze, battery saver, thermal throttle và app process death.

## P1-04. Hoàn thiện Telegram inbound transport và bảo mật command

**File:** `TelegramBotController.kt`, `AstraViewModel.kt`, `AstraDao.kt`, Runtime.

### Vấn đề đã quan sát

- `processIncomingUpdate()` xử lý một update nhưng chưa thấy polling `getUpdates` hoặc webhook transport.
- `simulateTelegramCommand()` dùng `System.currentTimeMillis()` làm fake update ID và fallback whitelist sang ID đầu vào khi whitelist trống. Đây chỉ nên là simulator, không được nhầm với incoming thật.
- `processIncomingUpdate()` lưu update là `PROCESSED` trước khi command execute; nếu process chết giữa chừng, lệnh bị đánh dấu đã xử lý nhưng chưa chạy.
- Panic nonce chỉ nằm trong RAM, không bind rõ với user/chat và mất khi process restart.

### Cách triển khai

1. Với Android client, ưu tiên long polling `getUpdates` nếu phù hợp; webhook đòi hỏi backend public endpoint. Không bịa transport.
2. Chỉ có một polling worker active; persist `offset/update_id` và tránh hai poller cùng Bot Token.
3. Thêm state machine update: `RECEIVED → AUTHORIZED → IN_PROGRESS → COMPLETED/FAILED/RETRYABLE`.
4. Ghi update reservation atomic; action phải idempotent. Khi app restart, recover các `IN_PROGRESS` theo command-specific policy.
5. Không sử dụng whitelist fallback tự động. Whitelist rỗng phải từ chối lệnh nhạy cảm.
6. Bind Panic nonce vào `chatId`, `userId`, action, account/environment và expiration; tạo bằng SecureRandom; one-time use; không log nonce.
7. Nếu restart, nonce invalid và phải bắt đầu xác nhận lại.
8. Chặn replay, duplicate, flood và command injection; giới hạn tốc độ và kích thước message.
9. Parse đúng Telegram response JSON `ok`, `result`, `error_code`; không chỉ nhìn HTTP 200.
10. Lệnh `/resume` chỉ chạy read-only reconciliation và yêu cầu UI/user xác nhận resume; không tự bật LIVE.
11. `/panic` phải hiển thị rõ account/mode, symbol/position scope, estimated slippage và yêu cầu xác nhận; không được bỏ qua P0 Panic safeguards.
12. Lệnh `/status` không tiết lộ thông tin nhạy cảm cho user ngoài whitelist.
13. `simulateTelegramCommand()` phải nằm trong debug/test-only interface hoặc được gắn nhãn SIMULATION; không dùng chung status với incoming thật.
14. Thêm lệnh /help đầy đủ, command permissions map và audit trail.
15. Nếu không triển khai incoming Telegram ở V1, ẩn/tắt UI command test khỏi production và ghi rõ Telegram chỉ gửi notification.

### Test bắt buộc

- Whitelist blank → command nhạy cảm bị từ chối.
- User đúng nhưng chat sai; chat đúng nhưng user sai.
- Duplicate update ID.
- Crash sau persist trước command execution.
- Crash sau command execution trước acknowledge.
- Panic nonce hết hạn, sai user, sai chat, replay.
- Telegram API HTTP 200 nhưng JSON `ok=false`.
- Polling conflict/429/network timeout.

## P1-05. Loại bỏ dữ liệu tin tức mẫu khỏi LIVE và tích hợp nguồn tin thật

**File:** `NewsEventGateEngine.kt`, `AstraTradingCoordinator.kt`, repositories mới.

### Vấn đề đã quan sát

`defaultCuratedFeed()` cung cấp tin tức mẫu với URL `example.com`, timestamp relative với thời điểm hiện tại và event status có thể được xem như confirmed. `scanAndEvaluateSymbol()` đưa feed này vào pipeline. Đây không phải live news feed.

### Cách triển khai

1. Chuyển `defaultCuratedFeed()` thành test fixture chỉ dùng trong test/debug.
2. Production LIVE không được inject dữ liệu mẫu hoặc làm giả timestamp mới.
3. Tạo `NewsSource` interface và provider adapter thật, có API/source/license rõ ràng.
4. Lưu metadata: source, canonical URL, publishedAt, fetchedAt, event time, verification state, confidence, content hash, language, category.
5. Ưu tiên nguồn official/exchange/regulator, sau đó wire services; social feeds phải unverified tier.
6. Deduplicate theo event identity, không chỉ gần giống title/URL.
7. Phân biệt article publish time với event occurrence time.
8. Chống tin cũ, update correction, false repost, duplicate, fake URLs và prompt injection trong nội dung.
9. AI chỉ tóm tắt/đánh giá dữ liệu; không thực thi chỉ thị nằm trong nội dung tin.
10. Khi không có news provider thật: `NEWS_UNAVAILABLE`; policy có thể giảm size/block giao dịch quanh volatility event, nhưng tuyệt đối không dùng tin giả thay thế.
11. Gắn `dataSource=LIVE_NEWS/DEMO_NEWS` và không cho DEMO_NEWS vào LIVE Execution Gate.

### Test

- Mọi test fixture bị loại khỏi production build/Live path.
- Tin cũ, tin tương lai, timestamp timezone sai.
- Duplicate repost.
- Correction/updated article.
- News API timeout/429.
- URL độc hại/prompt injection.
- Không có nguồn news → trạng thái unknown, không synthetic feed.

## P1-06. Sửa Calibration, Backtest và Probability Pipeline

**File:** `QuantMathEngine.kt`, `AstraTradingCoordinator.kt`, `Entities.kt`, `AstraDao.kt`.

### Vấn đề đã quan sát

Lịch sử Fill cần lưu prediction thực tại thời điểm ra quyết định. Logic calibration hiện có nguy cơ dùng `rankingScore` hiện tại ghép với kết quả cũ. Brier Score và ECE không có ý nghĩa nếu prediction/outcome không cùng giao dịch.

### Cách triển khai

1. Lưu prediction snapshot bất biến theo mỗi trade/signal:
   - predicted probability.
   - raw score.
   - calibrated probability.
   - model/strategy version.
   - signal/decision/sizing IDs.
   - timestamp và feature hash.
2. Khi trade đóng, ghép outcome với đúng prediction ID.
3. Không dùng ranking score hiện tại để cập nhật kết quả lịch sử.
4. Phân biệt Brier Score, Log Loss, ECE và calibration curve; định nghĩa positive class rõ ràng.
5. Tách train/calibration/evaluation time windows để không leak tương lai.
6. Dùng walk-forward theo thời gian, không shuffle ngẫu nhiên dữ liệu chuỗi thời gian.
7. Có minimum sample size, confidence interval và fallback `UNCALIBRATED`.
8. Không cho LIVE dựa trên calibration có sample nhỏ hoặc outcome chưa hoàn tất.
9. Backtest phải tính:
   - Maker/taker fees theo tình huống thực tế.
   - Spread và slippage theo thanh khoản.
   - Funding theo từng funding timestamp.
   - Partial fills, latency, rejected orders.
   - Mark price/trigger price khác last trade.
   - Exchange filters, minNotional, tick/step size.
   - Liquidation/maintenance margin nếu model cần.
   - Delisted symbols/survivorship bias.
10. Tách in-sample, validation, out-of-sample; có benchmark và baseline strategy.
11. Báo cáo drawdown, exposure, turnover, trade count, win rate, expectancy, profit factor, Sharpe/Sortino nếu phù hợp, max losing streak, fee/funding ratio.
12. Không dùng một backtest ngắn để suy ra chiến lược có lợi nhuận chắc chắn.

### Test

- Prediction cũ không bị thay bởi score hiện tại.
- Duplicate close result không tạo sample duplicate.
- Future data không lọt vào feature input.
- Backtest vào lệnh đúng thời điểm sau khi signal phát sinh.
- Fees/funding/slippage thay đổi kết quả đúng.
- Sample nhỏ → uncalibrated.

## P1-07. Rà soát hoàn chỉnh Quant/Strategy Engine

**File:** `QuantMathEngine.kt`, `AstraTradingCoordinator.kt`.

### Công việc

1. Kiểm tra mọi phép tính tiền, giá, quantity và PnL dùng `BigDecimal`; không dùng Float/Double cho giá trị tài chính cuối.
2. Kiểm tra rounding theo direction:
   - Quantity theo step size và rounding down.
   - Price theo tick size và hướng an toàn của SL/TP.
   - Notional và minQty sau khi rounding.
3. Xác minh EMA/ATR/RSI/relative volume dùng nến đã đóng khi strategy yêu cầu; không lookahead nến đang hình thành.
4. Kiểm tra index bounds, missing candles, duplicate candles, timestamp gaps và outliers.
5. Xác minh formula ATR, returns, EMA warmup, volatility regime và breakout range.
6. Tách regime detection khỏi direction signal; `BREAKOUT_CANDIDATE` không đồng nghĩa LONG.
7. Thêm kiểm định sensitivity: tham số thay đổi nhỏ có làm signal flip bất thường không.
8. Thêm market regime `UNKNOWN` khi dữ liệu không đủ; không ép thành một regime hợp lệ.
9. Kiểm tra expected value net cost, probability, risk/reward, fee, slippage và funding cùng đơn vị.
10. Risk model phải tính risk theo khoảng Entry→SL và actual quantity, không chỉ leverage/notional.
11. Cần kiểm tra max leverage, max notional, maintenance margin, liquidation distance, margin balance và available balance.
12. Có limit tổng exposure theo account và theo correlated asset group; không chỉ max một position local.
13. Giới hạn gap/slippage khi market gap qua SL; không hứa SL bảo đảm giá thoát.
14. Kiểm tra funding đang gần settlement; không dùng funding estimate cố định nếu feed không có.
15. Kiểm tra fee rate theo account VIP level/discount nếu có; nếu không lấy được, dùng conservative upper bound và label estimate.
16. Kiểm thử cực trị: zero, negative, huge BigDecimal, scale overflow, division by zero, null/malformed values.

## P1-08. Hoàn thiện Binance Adapter, API Limits và Exchange Contract

**File:** `BinanceAdapter.kt`, transport modules.

### Cần bổ sung/kiểm tra

1. Kiểm tra endpoint/parameter theo Binance USDⓈ-M Futures official docs mới nhất trước khi coding.
2. Tạo typed request/response DTO cho endpoint, validate schema kỹ.
3. Dùng centralized signed request builder: canonical encoding, timestamp, recvWindow, HMAC, redaction.
4. Không log full signed URL, signature, API secret hoặc X-MBX-APIKEY.
5. Xử lý HTTP 418/429, `Retry-After`, Binance error code, IP ban và API weight headers.
6. Có global rate limiter theo IP/API key và endpoint weight; share limiter giữa scanner, account sync, execution, diagnostics.
7. Ưu tiên lệnh risk-reducing trong queue khi API weight gần chạm giới hạn, nhưng không retry mù.
8. Không để scanner poll API liên tục làm cạn weight dành cho reconciliation/close.
9. Clock sync lấy nhiều sample, dùng midpoint/offset estimate, track uncertainty/age; periodic resync và fail closed khi lệch.
10. Không dùng `System.currentTimeMillis()` đơn độc để đo timeout/duration; dùng monotonic clock cho khoảng thời gian.
11. Parse mọi response theo required/optional field schema; thiếu field trọng yếu phải fail.
12. Với account position, parse `positionSide`, `marginType`, leverage, isolated wallet, entry price, break-even price, liquidation price nếu API cung cấp.
13. Kiểm tra account permissions/canTrade; không suy ra quyền chỉ từ một endpoint thành công.
14. Kiểm tra open standard orders và Algo orders tách biệt.
15. Hỗ trợ query historical orders/fills/income/funding khi cần accounting.
16. Không tự fallback endpoint sang kiểu lệnh khác trong production nếu thay đổi semantics mà chưa xác minh; mock/test compatibility phải tách biệt.
17. Thêm contract tests dùng sample response từ tài liệu chính thức được version hóa.
18. Mọi mutation method phải kiểm tra `ExecutionCapability` ở adapter boundary.
19. Tạo test cho malformed JSON, HTTP 200 nhưng payload error, rate-limit, 418, 5XX, timeout và connection reset.

## P1-09. Kiểm tra AI Gateway, Provider Compatibility và Failover

**File:** `AiIntelligenceGateway.kt`, `AiApiCheckerEngine.kt`, `ProviderConfigEntity`.

### Cần rà soát/nâng cấp

1. Phân tách adapter theo protocol: Gemini, Anthropic, OpenAI-compatible và local endpoint.
2. Không khẳng định “hỗ trợ mọi provider” chỉ vì có OpenAI-compatible. Provider có API riêng phải có adapter/contract test hoặc đánh dấu unsupported.
3. Tạo capability registry theo provider/model:
   - chat/completion format.
   - JSON mode/schema support.
   - system prompt support.
   - max output/input tokens.
   - tool calling nếu sử dụng.
   - context length.
   - timeout/rate limit.
4. Kiểm tra model ID bằng API thật, không suy từ tên provider.
5. API checker phải phân biệt network/auth/model-not-found/quota/rate-limit/permission/invalid-json/timeout.
6. Không dùng key provider A sang provider B; mỗi key phải nằm đúng secretSlot.
7. Provider failover phải giữ role: Analyst fallback chỉ sang Analyst-compatible; Skeptic fallback chỉ sang Skeptic-compatible.
8. Không biến fallback không chạy thành PASS.
9. Thêm provider circuit breaker, cooldown, quota accounting và bounded retry.
10. Đối với 429/5XX, tôn trọng Retry-After và không gửi prompt lặp vô hạn.
11. Thêm request budget/token budget theo ngày và theo provider.
12. Cache context/prompt khi hợp lệ, nhưng không reuse prediction trên snapshot khác.
13. Giới hạn output tokens, validate JSON schema và reject extra dangerous values.
14. Sanitize prompt input; content từ news/provider không phải instruction cho hệ thống.
15. Thêm `modelVersion`, `providerEndpoint`, `latency`, token usage thật nếu provider trả và `costEstimate`.
16. Bảo vệ custom base URL:
    - HTTPS mặc định.
    - Không follow redirect.
    - Validate host/scheme/port.
    - Chặn loopback/private network mặc định, trừ chế độ local endpoint được người dùng xác nhận rõ.
    - Không gửi API key tới URL chưa được xác nhận.
17. Nếu không có AI Provider khỏe, hệ thống có thể tiếp tục quan sát/scan nhưng không tự mở LIVE nếu policy yêu cầu AI.

## P1-10. Local AI thực sự hay chỉ là API endpoint local?

**File:** Gradle dependencies, `AiIntelligenceGateway.kt`, architecture.

### Vấn đề cần xác minh

Source có logic nhận diện local-host URL, nhưng Gradle dependency hiện tại không cho thấy runtime LLM on-device như LiteRT-LM, llama.cpp, MLC hoặc tương đương. Không được tuyên bố đã tích hợp local LLM nếu thực tế chỉ gửi HTTP tới `localhost`/LAN.

### Cách triển khai nếu Local AI là yêu cầu

1. Xác định yêu cầu inference thật: Android in-process hay local service trên cùng device/cloud phone.
2. Chọn runtime được hỗ trợ chính thức, kiểm tra license, ABI, Android version và memory footprint.
3. Tạo `LocalInferenceEngine` riêng, không nhét code model vào `AiIntelligenceGateway` bằng một URL giả.
4. Có model registry: name, version, SHA-256, quantization, size, context length, tokenizer, license.
5. Download model qua nguồn tin cậy, verify checksum, atomic install và rollback.
6. Không tải model từ URL tùy ý mà không checksum/signature.
7. Có memory budget, CPU thread count, thermal governor, cancellation, timeout và unload policy.
8. Nếu model không load được, không tự chuyển sang API trả phí không giới hạn; phải theo quota/failover policy.
9. Đo latency, tokens/s, peak RAM, CPU, battery/thermal trên đúng cloud phone/điện thoại.
10. Nếu dùng local HTTP server ngoài app, ghi rõ đó là `REMOTE_LOCAL_ENDPOINT`, không gọi là on-device inference.

### Nếu Local AI không nằm trong V1

- Ẩn tính năng/local model claims khỏi UI.
- Ghi rõ chưa tích hợp runtime local.
- Giữ adapter interface để có thể thêm sau.

## P1-11. Data integrity, indexes, retention và accounting ledger

**File:** `Entities.kt`, `AstraDao.kt`, `AstraDatabase.kt`.

### Cần làm

1. Rà soát primary keys/unique keys:
   - Position phải có symbol + positionSide/account.
   - Fill phải có exchange trade identity duy nhất, có thể composite symbol + tradeId/account.
   - Funding ledger phải có income/funding transaction ID thật.
   - OrderIntent clientOrderId unique theo account/environment.
2. Bổ sung indexes cho các truy vấn thường dùng: symbol, status, updatedAt, createdAt, order client ID, signal lineage.
3. Thêm Foreign Keys nếu phù hợp và không làm migration mất dữ liệu; cân nhắc nullable history/retention nhưng phải giữ audit.
4. Tạo transaction cho các nhóm ghi đồng bộ: fill + position + order + accounting snapshot.
5. Không dùng `REPLACE` vô thức cho records quan trọng nếu nó có thể ghi đè history; chọn IGNORE/UPDATE có điều kiện hoặc append-only.
6. Giữ ledger append-only; correction bằng adjustment record, không sửa/xóa giao dịch gốc âm thầm.
7. Thêm pagination cho Fill/System Events/Market Candles; tránh load toàn bộ lịch sử.
8. Thêm retention/pruning cho market candles/features/AI logs theo policy; không prune financial ledger.
9. Có database migration test, integrity check và export schema.
10. Đồng bộ timezone UTC và dateKey; UI localize khi hiển thị.

## P1-12. Bảo mật SecretVault và API configuration

**File:** `SecretVault.kt`, `AiIntelligenceGateway.kt`, `BinanceAdapter.kt`, `AstraViewModel.kt`.

### Cần kiểm tra

1. AES-GCM key phải nằm trong Android Keystore; không fallback production sang SharedPreferences/plaintext/JVM key.
2. Keystore invalidated → clear/disarm credentials safely, báo cần nhập lại; không tự tạo khóa khác rồi tuyên bố giải mã được ciphertext cũ.
3. `verifyVaultSlotIntegrity()` hiện cho phép `expectedHash == null` trả true. Hãy phân biệt LEGACY_UNVERIFIED với VERIFIED; không đánh dấu integrity confirmed nếu thiếu hash. Nếu cần migrate legacy, phải re-encrypt và lưu checksum theo flow an toàn.
4. Dữ liệu integrity metadata phải được lưu/commit nguyên tử cùng encrypted payload.
5. Không lưu raw API key trong Room, BuildConfig, logs, clipboard, diagnostic JSON hoặc crash breadcrumbs.
6. Không đưa API Key/Secret vào AI prompt hay Telegram.
7. Validate key format chỉ là bước sanity check, không được coi là verify permission.
8. Cấu hình API Binance phải nhắc người dùng tắt withdrawal permission và bật IP allowlist nếu phù hợp; không tự khẳng định app xác minh được quyền này nếu endpoint không cung cấp.
9. Có chức năng revoke/remove key, rotate key, kiểm tra vault health và clear safely.
10. Rà soát debug logs, OkHttp interceptors, exception messages và HTTP URL query signatures.
11. Local provider URL cần cảnh báo khi gửi secret tới host LAN/loopback.
12. Cân nhắc yêu cầu thiết bị khóa màn hình/biometric cho hành động thay đổi secret hoặc arm LIVE; không biến thành blocker không cần thiết nhưng phải đánh giá threat model.

## P1-13. Error Handling, Retry, Circuit Breaker và Rate Limiter

**File:** `BinanceAdapter.kt`, AI Gateway, runtime, Telegram.

1. Chuẩn hóa typed error categories: auth, permission, validation, rate-limit, IP-ban, network, timeout, unknown execution, schema, stale data, server error.
2. Phân biệt retryable/read-only với mutation.
3. Read-only GET có thể retry bounded với jitter khi an toàn.
4. Mutation POST/DELETE phải query-first và idempotency-aware.
5. Global circuit breaker cho Binance mainnet mutations; một lỗi auth/clock/reconciliation nghiêm trọng phải disarm entry.
6. API weight budget ưu tiên account/close/reconcile hơn scanner/diagnostics.
7. Không chạy retry song song nhiều coroutine khi rate limit.
8. Tôn trọng Retry-After; ban 418 thì dừng request đến thời hạn được xác định, không tìm cách né ban bằng proxy/IP hopping.
9. Backoff phải có cap, jitter và maximum elapsed time.
10. Log `requestId`, `correlationId`, endpoint template, http code, latency, retry count, error category; không log secret/signature/raw private payload.

## P1-14. Hoàn thiện app lifecycle, permission và device health

**File:** `MainActivity.kt`, `AstraViewModel.kt`, service, Manifest.

1. Kiểm tra runtime notification permission trên Android 13+ và hướng dẫn khi bị từ chối.
2. Xác minh startForeground đúng thời điểm và không bắt đầu service sai context.
3. UI phải phân biệt Autostart setting của OEM với permission trong app; không hứa app tự khởi chạy nếu hệ điều hành chặn.
4. Boot recovery không tự bật LIVE.
5. Khi người dùng Force Stop, app phải ghi rõ sẽ không thể tự chạy lại cho đến khi người dùng mở lại (nếu phù hợp Android behavior hiện hành).
6. Kiểm tra Android Doze, App Standby, Battery Saver, Data Saver, OEM auto-start, low memory, thermal throttling.
7. Device health dùng dữ liệu thật; không nhầm battery temperature với CPU/SoC temperature.
8. Không giữ WakeLock vô hạn; ghi rõ thời gian acquire/release và lý do.
9. Nếu thiết bị quá nhiệt/thiếu RAM, dừng mở lệnh mới, không kill tiến trình đang cố bảo vệ vị thế mà không có kế hoạch xử lý.
10. Thêm state dashboard rõ: service, websocket, user stream, last heartbeat, last data, reconciliation, clock sync, API weight, thermal/RAM.

## P1-15. Structured logging, audit trail và observability

1. Thay các catch rỗng trong network/service/secret/receiver bằng logging có kiểm soát.
2. Log theo JSON structured event hoặc data class chuẩn.
3. Gắn correlation ID xuyên Signal → AI Analyst → Skeptic → Risk → OrderIntent → Exchange Order → Fill → Position → Reconciliation.
4. Lưu state transition history, ai/operator nào kích hoạt, lý do, endpoint mode và timestamp.
5. Redact API key, signature, bot token, user private info và payload nhạy cảm.
6. Tách user-facing message tiếng Việt khỏi machine error code.
7. Có alert cho unknown order, missing stop loss, account desync, websocket stale, listenKey failure, DB corruption, thermal limit và API ban.
8. Diagnostic export phải sanitize mặc định; preview nội dung trước khi chia sẻ.
9. Log rotation/retention để không làm đầy storage.
10. Không coi telemetry tự báo là bằng chứng exchange state; hiển thị source/timestamp.

---

# 5. P2 — STABILITY, PERFORMANCE, UX VÀ MAINTAINABILITY

## P2-01. Build system có thể tái lập

1. Bổ sung Gradle Wrapper đầy đủ (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`) và pin đúng Gradle version tương thích AGP/Kotlin/KSP.
2. Không tự thay version dependencies hàng loạt nếu chưa test compatibility.
3. Có dependency verification/checksum hoặc lock versions nếu phù hợp.
4. Bật CI build cho Debug và Release.
5. Thêm `lint`, `test`, `assembleRelease`, schema verification vào pipeline.
6. Build không phụ thuộc file local bí mật ngoài placeholder config.
7. Ghi build metadata: git commit, version, build variant, build timestamp, dependency versions.
8. Tạo reproducible build khi khả thi.

## P2-02. R8, Resource Shrinking và kích thước APK

1. Tạo Release với `isMinifyEnabled=true` và `isShrinkResources=true` sau khi có ProGuard/R8 rules chính xác.
2. Source hiện dùng `material-icons-extended`; APK có lượng Material Icons lớn. Đánh giá chuyển sang chỉ giữ icon thực dùng hoặc dùng vector drawable phù hợp.
3. Không đưa `androidx.compose.ui.tooling`, `ui-test-manifest`, Robolectric, test fixtures vào Release.
4. Duy trì rules cần thiết cho Room/KSP, reflection, JSON parsing và native runtime nếu được thêm.
5. So sánh size per DEX, resources, native libraries trước/sau.
6. Không đặt mục tiêu size bằng mọi giá; không bỏ chức năng hoặc test để giảm MB.
7. Lập báo cáo size regression theo build.

## P2-03. Release signing và phân phối

1. Release keystore phải do owner kiểm soát; không commit vào Git/ZIP.
2. Không dùng debug signing cho Release.
3. Kiểm tra signature, certificate SHA-256 và khả năng update đúng package.
4. Tăng versionCode mỗi release.
5. APK Release `debuggable=false`.
6. Không bao gồm test-only activities, debug menu, fake market/news data hoặc simulator command trong production.
7. Kiểm tra targetSdk/minSdk và manifest merged cuối cùng.
8. Có release checklist, checksum SHA-256 của APK/AAB và release notes.
9. Nếu ký APK/AAB qua CI, dùng secure secrets store và không in environment variables.

## P2-04. UI/UX cho vận hành thực tế

1. UI phải hiển thị rõ mode: TESTNET, MAINNET READ-ONLY, LIVE, EMERGENCY EXIT.
2. Không dùng màu xanh/trạng thái “Running” nếu engine/streams không healthy.
3. Hiển thị equity chỉ khi verified; estimated capital phải có nhãn estimate và không trộn với exchange equity.
4. Hiển thị từng trạng thái: Binance REST, market WS, user WS, AI, Skeptic, DB, Foreground Service, Telegram.
5. Có confirm dialog cho LIVE, Panic, API key removal, change leverage/margin.
6. Panic dialog hiển thị rõ phạm vi position và account endpoint.
7. Khi một hạng mục chưa có implementation, UI ghi “Chưa hỗ trợ” thay vì nút có vẻ hoạt động.
8. Bố cục phải dùng tốt màn nhỏ, text scale lớn, dark theme và accessibility.
9. History/analytics phải phân biệt actual vs estimated PnL.
10. Không hiển thị fake scanner candidates hoặc fake news.

## P2-05. Hiệu năng và tài nguyên

1. Benchmark REST/WS concurrency, JSON parsing, indicator calculation, AI latency và Room queries.
2. Không chạy heavy CPU/LLM inference trên Main thread.
3. Thêm cancellation khi symbol đổi, app stop hoặc user tắt bot.
4. Giới hạn in-memory candles/features; dùng rolling windows.
5. Thêm pagination và database pruning.
6. Đo peak RAM và thermal throttle trên thiết bị mục tiêu.
7. Tránh tạo OkHttp client/socket liên tục.
8. Không pin CPU hoặc giữ WakeLock kéo dài để né Android power management.
9. Cần có benchmark cloud phone riêng nếu mục tiêu chạy trên cloud phone.
10. Nếu tài nguyên không đủ, ưu tiên Risk/Execution/Recovery hơn Scanner/AI/News.

## P2-06. Code architecture và maintainability

1. Tách `AstraTradingCoordinator` khổng lồ thành module/service theo trách nhiệm:
   - `AccountStateRepository`
   - `MarketDataRepository`
   - `ScannerEngine`
   - `SignalEngine`
   - `AiReviewOrchestrator`
   - `RiskEngine`
   - `ExecutionCoordinator`
   - `OrderProtectionManager`
   - `ReconciliationService`
   - `EmergencyExitCoordinator`
   - `AccountingLedger`
   - `RuntimeController`
2. Không tạo abstraction vô nghĩa; tách khi có contract/test rõ.
3. Dùng dependency injection hoặc application container duy nhất để tránh multiple adapter/coordinator instances.
4. Tách domain model khỏi Room entity và network DTO.
5. Dùng sealed class/enum cho state/error thay vì string rải rác, có serialization migration strategy.
6. Định nghĩa state transition centrally và test toàn bộ transitions.
7. Giảm hardcoded magic numbers; đưa thresholds vào typed configuration có validation/version.
8. Có code formatting/static analysis.
9. Xóa code dead/test-only khỏi production path sau khi chứng minh không còn dùng.
10. Giữ backward compatibility cho database và settings config.

---

# 6. KIỂM TRA CÁC THÀNH PHẦN CÒN THIẾU / CHƯA ĐỦ BẰNG CHỨNG

Không được tự động kết luận các mục sau đã có chỉ vì thấy class hoặc tên hàm tương ứng. Phải truy vết call graph và kiểm thử.

| Thành phần | Điều cần chứng minh |
|---|---|
| Binance REST | Endpoint thật, request signing, validation, error handling, account mapping |
| Binance WebSocket Market | Có caller runtime, subscribe thật, parse, stale tracking, reconnect |
| Binance User Data Stream | Có listenKey, WebSocket, event consumer, order/position update |
| Scanner | Mỗi symbol có feature riêng từ dữ liệu thật; không fake fallback |
| Analyst | Provider role đúng, model đúng, JSON schema đúng, request gắn snapshot |
| Independent Skeptic | Model/provider riêng thực sự chạy; không lấy kết quả Analyst thay thế |
| News Engine | Có nguồn tin thật; fixture chỉ ở test; timestamp/source được xác minh |
| Local AI | Có runtime inference thật hoặc ghi rõ chỉ là endpoint HTTP local |
| Risk Engine | Tính quantity/SL/TP theo exchange filters, fees, slippage, funding, margin thật |
| Leverage/Margin | Đã query/set/verify actual account mode và leverage |
| Entry Execution | OrderIntent persisted, idempotent, fill verified, partial handling |
| Stop Loss | Algo order đã query lại từ exchange và còn active |
| Take Profit | Algo order đã query lại từ exchange và còn active |
| Panic Close | Đóng đúng mọi position thuộc phạm vi, không fallback local khi API fail |
| Reconciliation | Đồng bộ account, position, standard orders, algo orders và unknown intents |
| Accounting | Actual trade fills, commission, funding, cashflow, realized/unrealized PnL |
| Telegram | Notification thật và inbound transport thật được phân biệt |
| Foreground Service | Callback thật, loop thật, websocket lifecycle, keepalive thật |
| Boot Recovery | Khóa LIVE, reconcile trước resume, không tự mở lệnh |
| SecretVault | No plaintext, no BuildConfig secret, KeyStore fail-closed |
| Database | Migration không mất data, unique/transaction/integrity |
| Release APK | debuggable=false, đúng source, không secret/debug-only module |

---

# 7. KIẾN TRÚC ĐÍCH CẦN HƯỚNG TỚI

Không nhất thiết phải viết lại toàn bộ ứng dụng. Hãy giữ lại logic tốt đã có, nhưng đưa các trách nhiệm về đúng module và đảm bảo một đường thực thi duy nhất.

```text
Android UI / Telegram Command / Runtime Trigger
                    |
                    v
             Command Router
                    |
                    v
           Runtime Controller
                    |
          +---------+---------+
          |                   |
          v                   v
   Market Data Layer     Account/Exchange State
          |                   |
          +---------+---------+
                    |
                    v
              Data Quality Gate
                    |
                    v
              Scanner / Signal
                    |
                    v
             AI Analyst (optional by policy)
                    |
                    v
          Independent Skeptic (separate)
                    |
                    v
              Risk Engine
                    |
                    v
          Unified Execution Gate
                    |
                    v
            Persistent OrderIntent
                    |
                    v
            Execution Coordinator
                    |
             +------+------+
             |             |
             v             v
          Binance Entry   Server-side SL/TP
             |             |
             +------+------+
                    |
                    v
       User Data Stream + REST Reconciliation
                    |
                    v
        Position State + Accounting Ledger
                    |
                    v
       Audit / Monitoring / User Notification
```

## 7.1. Nguồn sự thật (source of truth)

- Exchange account/position/order state là nguồn sự thật cuối cho trạng thái trên sàn.
- Room là persistent local record/audit/cache, không được coi là sự thật cao hơn exchange.
- Market data phải có source, timestamp và quality.
- AI output chỉ là recommendation/evidence, không phải authorization.
- Risk Engine là deterministic policy authority cho size/SL/limits.
- Execution Gate là cổng duy nhất mở lệnh.
- Panic/Emergency Exit là cổng riêng chỉ giảm rủi ro.

## 7.2. State model tối thiểu

Tách rõ các state sau, không gom thành một string chung:

- App runtime: `STOPPED`, `STARTING`, `HEALTHY`, `DEGRADED`, `FAILED`.
- Connectivity: `OFFLINE`, `REST_OK`, `MARKET_WS_OK`, `USER_WS_OK`, `STALE`, `RECONNECTING`.
- Account: `UNCONFIGURED`, `UNVERIFIED`, `VERIFIED_READ_ONLY`, `VERIFIED_TRADING`, `RECONCILIATION_REQUIRED`.
- Trading mode: `TESTNET`, `MAINNET_READ_ONLY`, `LIVE_ARMED`, `LIVE_DISARMED`.
- Order: `PERSISTED`, `SUBMITTING`, `ACKNOWLEDGED`, `NEW`, `PARTIALLY_FILLED`, `FILLED`, `CANCEL_PENDING`, `CANCELED_CONFIRMED`, `REJECTED`, `EXPIRED`, `UNKNOWN`.
- Position: `OPEN_UNPROTECTED`, `OPEN_PROTECTED`, `PROTECTION_DEGRADED`, `EXIT_PENDING`, `CLOSE_PENDING_RECONCILIATION`, `CLOSED_CONFIRMED`, `EXTERNAL_UNKNOWN`.
- Accounting: `PENDING`, `PARTIAL`, `VERIFIED`, `ESTIMATED`.

Mỗi state transition phải có precondition, owner, timestamp, event ID và test.

---

# 8. TEST PLAN BẮT BUỘC

## 8.1. Unit Tests

Tối thiểu bao gồm:

- BigDecimal sizing/rounding/tick/step/minNotional.
- Fee, slippage, funding, EV và PnL.
- EMA/ATR/returns/RVOL/regime/breakout.
- Calibration/Brier/ECE với prediction-history đúng ID.
- AI JSON schema/role validation/provider isolation.
- State machine transition.
- Binance signature/canonical query/response parser.
- Data quality/freshness.
- Scanner ranking và direction separation.
- Panic close selection by symbol + positionSide.

## 8.2. Integration Tests

Bắt buộc có fake server/MockWebServer dependency khai báo thật:

- Binance account snapshot.
- Exchange Info and filters.
- Ticker universe.
- Premium index + bookTicker partial failure.
- Market kline.
- User Data Stream event parser.
- Entry submit timeout/idempotency.
- Algo SL/TP submit/query/cancel.
- Panic Close complete state machine.
- Reconciliation after restart.
- Rate limit 429/418, 5XX, timeout.
- AI Analyst + Skeptic provider isolation.
- Telegram getUpdates and command idempotency.

## 8.3. Database Tests

- All schema migrations.
- Position LONG/SHORT same symbol.
- Unique trade/fill identity.
- Transaction atomicity.
- Database reopen/process restart.
- Corrupt database handling.
- No destructive fallback.

## 8.4. Fault-Injection Tests

Mô phỏng:

- Wi-Fi off.
- 4G on/off.
- DNS failure.
- TLS failure.
- HTTP timeout.
- Binance 429/418.
- Binance 5XX.
- API key revoked.
- Keystore invalidated.
- Clock drift.
- WebSocket disconnect.
- ListenKey expiration.
- App killed at every state transition.
- Device reboot.
- Low RAM/thermal throttle.
- Notification permission denied.
- Android background execution restriction.

## 8.5. Device Tests

Trên ít nhất một thiết bị Android thật phù hợp:

1. Clean install.
2. Update từ version cũ có database.
3. TESTNET read-only diagnostics.
4. Market stream 1 symbol.
5. Market stream multi-symbol nếu feature đã triển khai.
6. User stream event.
7. Stop/start service.
8. Wi-Fi ↔ mobile data switching.
9. Battery Saver/Doze.
10. Reboot and recovery.
11. Force Stop behavior.
12. App update giữ database và Vault.
13. Uninstall/reinstall behavior được document.
14. Nhiệt độ/thiếu RAM.
15. Từ chối notification permission.

## 8.6. Binance Testnet Soak Test

Không dùng Mainnet order thật. Xây dựng kịch bản Testnet có log:

- Đặt Entry.
- Xác minh Fill.
- Đặt SL/TP.
- Hủy/sửa SL/TP.
- Partial fill khi có thể mô phỏng.
- Timeout/unknown execution qua mock.
- Panic Close.
- Reconciliation.
- App restart khi có position/test order.
- Rate limit và websocket reconnect.

Soak test phải có thời lượng, số lượng event, số lần reconnect và tiêu chí pass cụ thể trong báo cáo. Không tự tuyên bố “24/7 stable” chỉ từ một lần chạy ngắn.

---

# 9. RELEASE BUILD VÀ APK AUDIT

Sau khi P0/P1 đạt, tạo một Release candidate mới.

## 9.1. Build requirements

1. Gradle Wrapper hoạt động trên máy sạch.
2. Build Debug và Release thành công.
3. `testDebugUnitTest` thành công.
4. `testReleaseUnitTest` hoặc task tương ứng thành công nếu project hỗ trợ.
5. Android lint không có blocker; warning còn lại phải được phân loại.
6. `assembleRelease` hoặc `bundleRelease` thành công.
7. Release signing dùng keystore owner-provided ngoài repository.
8. `debuggable=false` trong APK Release.
9. Không có debug-only Activity, test manifest, fake data/provider hoặc secrets.
10. R8/resource shrinking chỉ bật sau khi test giữ đúng Room/JSON/Compose behavior.

## 9.2. APK inspection requirements

Dùng `apkanalyzer`, `aapt2`, `jadx` hoặc Android Studio APK Analyzer:

- Package/versionCode/versionName/minSdk/targetSdk.
- Merged Manifest.
- Permissions, exported components, services, receivers, FGS type.
- DEX class inventory.
- Resource inventory.
- Native `.so` ABI/size nếu có.
- Dependencies và duplicate classes.
- Debuggable/test-only components.
- Secret scan.
- Signing certificate.
- DEX count/size and APK size.

Đối chiếu class/module hiện diện với source. Không được kết luận runtime behavior chỉ từ class tồn tại.

## 9.3. Release output

Phải nộp:

- `app-release.apk` hoặc AAB.
- SHA-256 checksum.
- APK inspection report.
- Manifest report.
- DEX/class inventory.
- Dependency/size report.
- Test/build logs.
- Release notes.

---

# 10. THỨ TỰ TRIỂN KHAI

Không làm tất cả trong một commit khổng lồ. Chia commit/phase có thể review và rollback.

## Phase 0 — Baseline và backup

- Xác định source root/branch/commit.
- Tạo backup/branch an toàn.
- Đọc toàn bộ source và report.
- Tạo call graph cho Entry, SL/TP, Panic, reconciliation, Telegram, runtime.
- Tạo inventory APK/source.
- Chạy build/test baseline nếu môi trường cho phép.
- Nộp `BASELINE_AUDIT.md` và danh sách blocker.

**Không thay đổi risk strategy trong phase này.**

## Phase 1 — P0 Data & Capital Safety

Thực hiện theo thứ tự:

1. DB destructive migration removal + schema migration.
2. Position key symbol + positionSide.
3. Panic Close fail-closed + final reconciliation.
4. Verify/cancel protective orders.
5. Entry fill/SL/TP protection.
6. Execution Gate exact lineage.
7. Skeptic fail-closed.
8. Market data no-fake-success.
9. Endpoint/capability separation.
10. Leverage/margin/position mode validation.
11. Accounting no estimates as actual.
12. Persistent execution lock.
13. BuildConfig secret removal.

Sau mỗi hạng mục: test hồi quy trước khi chuyển việc tiếp.

## Phase 2 — P1 Data/AI/Runtime

1. Scanner thật.
2. Market data repository + WebSocket lifecycle.
3. Foreground runtime callback wiring.
4. Telegram inbound transport và idempotency.
5. Live news provider / bỏ mock feed khỏi LIVE.
6. Calibration/backtest history.
7. Binance rate limit/clock/error handling.
8. AI provider capability/failover/security.
9. Local AI implementation hoặc gỡ claim chưa đúng.
10. Device health/Android lifecycle.
11. Structured logging/observability.

## Phase 3 — P2 Production Quality

1. Gradle wrapper/CI.
2. R8/resource shrinking.
3. Release signing.
4. UI state truthfulness.
5. Performance/RAM/thermal tests.
6. Refactor coordinator sau khi tests bảo vệ behavior.
7. Documentation and operational runbook.

## Phase 4 — Testnet soak and Release Candidate

- Chạy full test suite.
- Chạy fault injection.
- Chạy Testnet end-to-end.
- Chạy soak test.
- Audit APK.
- Review toàn bộ P0/P1 evidence.
- Chỉ tạo production Release candidate sau khi gate pass.

---

# 11. LIVE ACTIVATION GATE — KHÔNG ĐƯỢC BỎ QUA

Chỉ cân nhắc LIVE khi mọi mục sau đều đạt và người dùng tự xác nhận:

- [ ] P0 không còn blocker.
- [ ] Không còn unresolved/unknown OrderIntent.
- [ ] Database migration/backup/recovery đạt.
- [ ] Binance account, position mode, leverage, margin mode được xác minh.
- [ ] API key chỉ có quyền trade cần thiết; withdrawal permission tắt theo cấu hình user.
- [ ] API key không bị nhúng trong APK/log/source artifact.
- [ ] Exchange filters thật, mới và đúng symbol.
- [ ] Market data thật, mới, không synthetic.
- [ ] Clock sync đạt.
- [ ] WebSocket market/user stream healthy hoặc policy REST-only được xác nhận phù hợp.
- [ ] Reconciliation hoàn tất và khớp Exchange.
- [ ] SL server-side được query xác minh active sau Entry.
- [ ] Panic Close đã test đủ các lỗi mạng và partial fill.
- [ ] Accounting actual/estimated được tách rõ.
- [ ] AI/Skeptic role/policy đạt nếu LIVE strategy yêu cầu AI.
- [ ] Risk limits được người dùng xem và xác nhận.
- [ ] Foreground runtime/notification/Android lifecycle đã test trên thiết bị mục tiêu.
- [ ] Testnet end-to-end và soak test đạt.
- [ ] APK Release debuggable=false, signed đúng, không debug/test/secret.
- [ ] Người dùng trực tiếp arm LIVE trên thiết bị và đọc rõ cảnh báo rủi ro.

**Không tự bật LIVE sau khi checklist pass.** Agent chỉ báo đủ điều kiện và chờ người dùng tự thao tác trong ứng dụng.

---

# 12. BẮT BUỘC BÀN GIAO

Tạo/cập nhật các file sau trong repository:

1. `BASELINE_AUDIT.md` — trạng thái trước sửa, evidence và danh sách blocker.
2. `REMEDIATION_TRACKER.md` — bảng ID, priority, status, evidence, commit, test.
3. `ARCHITECTURE.md` — kiến trúc sau sửa và data flow.
4. `SECURITY_AUDIT.md` — threat model, SecretVault, API keys, network, release.
5. `DATABASE_MIGRATION_REPORT.md` — schema, migrations, backup/restore tests.
6. `TEST_REPORT.md` — test thực chạy, số test, command, environment, pass/fail.
7. `TESTNET_SOAK_REPORT.md` — kịch bản, thời gian, số event, lỗi, reconnect, kết quả.
8. `APK_AUDIT_REPORT.md` — manifest, DEX, dependencies, secrets scan, size, signature.
9. `CHANGELOG.md` — thay đổi có thật.
10. `RELEASE_NOTES.md` — chức năng đã có, chưa có, giới hạn và known issues.
11. `OPERATIONS_RUNBOOK.md` — start/stop, permissions, recovery, alerts, backup, API rotation, emergency procedures.
12. APK/AAB Release candidate + SHA-256 checksum.

## 12.1. Format báo cáo mỗi hạng mục

| ID | Priority | Status | Source files changed | Root cause | Test added | Test result | Evidence/commit | Residual risk |
|---|---|---|---|---|---|---|---|---|

Không ghi `PASS` nếu test chưa chạy. Dùng `IMPLEMENTED_NOT_VERIFIED` khi code đã sửa nhưng chưa build/test thành công.

## 12.2. Báo cáo cuối phải có

- Tổng số blocker P0 ban đầu / đã sửa / còn lại.
- Tổng số P1/P2 ban đầu / đã sửa / còn lại.
- Build/test commands chính xác.
- Số test discovered/passed/failed/skipped; giải thích chênh lệch với report cũ.
- Các lỗi test còn lại.
- APK path, size, SHA-256, package, version, debug flag, signature.
- Những phần chưa triển khai và lý do.
- Những giả định được dùng.
- Những việc cần người dùng làm (ví dụ nhập API key, bật notification, cấu hình API key permission, chọn Testnet).
- Xác nhận LIVE vẫn disarmed.

---

# 13. LỆNH BẮT ĐẦU THỰC THI

Bắt đầu ngay trên source repository được cung cấp.

**Bước đầu tiên:** kiểm tra source đang mở có đúng bản ZIP/APK baseline không; tạo backup/branch; đọc toàn bộ code; chạy baseline build/test nếu môi trường cho phép; lập `BASELINE_AUDIT.md`.

Sau đó triển khai theo Phase 1, không dừng ở việc viết kế hoạch. Với mỗi commit, chạy test tương ứng và cập nhật `REMEDIATION_TRACKER.md`.

Nếu môi trường không có Android SDK/Gradle/network/Testnet:

- Không giả lập kết quả build/test.
- Hoàn thành phần code và test có thể chạy.
- Ghi chính xác lệnh chưa chạy, nguyên nhân môi trường và các file/log cần thiết để chạy ở môi trường đầy đủ.
- Không đánh dấu hạng mục `VERIFIED_TESTS_PASS`.

Nếu phát hiện lỗi mới chưa có trong tài liệu:

1. Tạo ID mới theo severity.
2. Ghi reproduction và root cause.
3. Sửa nếu thuộc phạm vi và an toàn.
4. Bổ sung regression test.
5. Cập nhật tracker/report.

**Nguyên tắc kết thúc:** Không tối ưu dung lượng, thêm UI, thêm AI model hay thêm chiến lược trước khi mọi lỗi P0 được đóng. Không được lấy “APK build thành công” làm bằng chứng rằng bot giao dịch an toàn. Không được lấy “test pass” làm bằng chứng rằng hệ thống đã ổn định trên Android thật. Chỉ kết luận đúng với những gì đã kiểm chứng.
