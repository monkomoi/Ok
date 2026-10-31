# ASTRA TRADER V5.1 — MASTER REMEDIATION & UPGRADE TASK

**Mục đích:** Tài liệu giao việc cho AI lập trình/engineering agent để kiểm tra, sửa lỗi và nâng cấp mã nguồn Astra Trader hiện tại. Đây là yêu cầu triển khai trực tiếp trên repository, không phải yêu cầu chỉ viết thêm một bản kế hoạch.

**Nguồn đánh giá:** ZIP `apparchitect-30-oct-2026-19-20-55.zip`  
**Ngày rà soát:** 2026-10-30  
**Phạm vi chính:** Android/Kotlin, Binance USDⓈ-M Futures, AI Gateway, Quant Engine, Scanner, Risk/Execution, Room Database, Telegram và Foreground Service.

---

# 0. CHỈ THỊ BẮT BUỘC CHO AI LẬP TRÌNH

Bạn đang tiếp nhận một dự án giao dịch tự động có khả năng gửi lệnh Futures bằng tiền thật. Hãy xử lý nó như một hệ thống tài chính có rủi ro cao, không phải một ứng dụng CRUD thông thường.

## 0.1. Nhiệm vụ

1. Đọc toàn bộ source code, `ARCHITECTURE.md`, `AUDIT_REPORT.md`, `IMPLEMENTATION_REPORT.md`, `REMEDIATION_PLAN.md`, `TEST_REPORT.md`, `CHANGELOG.md` và `Astra_Trader_Master_Spec_v5_1_*.md`.
2. Đối chiếu từng tuyên bố trong tài liệu với implementation thực tế. Không mặc định tài liệu cũ là đúng.
3. Xác minh lại các lỗi được liệt kê trong tài liệu này. Nếu một nhận định không còn đúng vì source đã thay đổi, hãy chỉ ra bằng chứng và cập nhật báo cáo.
4. Sửa lỗi tận gốc trong source code. Không chỉ thêm cờ trạng thái, thông báo UI, comment hoặc test giả để làm cho báo cáo trông đẹp hơn.
5. Bổ sung test hồi quy và kiểm thử tích hợp cho từng lỗi đã sửa.
6. Chạy build/test thực tế trong môi trường có thể chạy Gradle. Không tuyên bố PASS khi chưa chạy lệnh tương ứng.
7. Cập nhật báo cáo và changelog dựa trên kết quả thực tế.

## 0.2. Quy tắc an toàn không được vi phạm

- **Mặc định khóa LIVE trong suốt quá trình sửa và kiểm thử.** Không dùng API key thật, không gửi lệnh thật và không tự ý bật LIVE.
- Không thử nghiệm bằng vốn thật. Dùng MockWebServer, fake exchange adapter hoặc Binance Testnet đã được cấu hình riêng.
- Không retry mù các lệnh có trạng thái thực thi chưa rõ (`UNKNOWN_EXECUTION_STATUS`). Trước khi retry phải truy vấn bằng `clientOrderId`/`clientAlgoId` và đối soát trạng thái trên sàn.
- Không suy đoán vị thế, giá, khối lượng, số dư hoặc trạng thái SL/TP khi API trả lỗi, timeout hoặc dữ liệu đã cũ.
- Không xóa dữ liệu vị thế cục bộ chỉ vì một request thất bại hoặc một phản hồi thiếu dữ liệu.
- Không đánh dấu `CLOSED_CONFIRMED`, `OPEN_PROTECTED`, `SKEPTIC_PASS`, `ACCOUNT_VERIFIED` hay trạng thái tương đương nếu chưa có bằng chứng tương ứng từ dữ liệu đáng tin cậy.
- Không để AI quyết định trực tiếp khối lượng, bỏ qua Risk Engine, sửa giới hạn rủi ro hoặc gửi lệnh. AI chỉ được cung cấp phân tích/ứng viên; các luật an toàn xác định bằng code luôn có quyền phủ quyết.
- Không âm thầm thay đổi chiến lược, mức risk, giới hạn đòn bẩy, vốn, chế độ margin, quy tắc SL/TP hoặc chính sách LIVE đã được người dùng cấu hình. Mọi thay đổi ảnh hưởng hành vi giao dịch phải được nêu rõ và có test.
- Không dùng dữ liệu hardcode hoặc dữ liệu mô phỏng làm dữ liệu thị trường LIVE. Dữ liệu mô phỏng phải được gắn nhãn rõ và chỉ được phép ở TEST/demo.

## 0.3. Cách làm việc

Thực hiện lần lượt theo các giai đoạn P0 → P1 → P2. Không nhảy sang tối ưu giao diện hoặc thêm tính năng mới khi còn lỗi an toàn P0.

Với mỗi lỗi, bắt buộc cung cấp:

- File/class/function bị ảnh hưởng.
- Hành vi sai hiện tại và điều kiện kích hoạt.
- Nguyên nhân gốc.
- Giải pháp thiết kế.
- Thay đổi code cụ thể.
- Test tái hiện lỗi trước sửa và test xác nhận sau sửa.
- Log/build/test evidence thực tế.
- Rủi ro còn lại hoặc giới hạn chưa giải quyết.

Nếu thiếu thông tin, hãy kiểm tra repository và thiết kế giải pháp an toàn nhất. Không dừng lại chỉ để hỏi lại những điều có thể suy ra từ source/spec.

---

# 1. KẾT QUẢ RÀ SOÁT HIỆN TẠI

Các điểm dưới đây là những vấn đề đã được phát hiện khi đọc source trong ZIP. Hãy xác minh lại trên repository hiện tại trước khi sửa, vì source có thể đã được cập nhật sau lần đóng gói.

| ID | Mức độ | Khu vực | Vấn đề đã phát hiện |
|---|---|---|---|
| P0-01 | CRITICAL | Panic Close | Nếu truy vấn vị thế trên sàn trước khi đóng thất bại, code có thể dùng khối lượng cục bộ để gửi lệnh đóng. |
| P0-02 | CRITICAL | Panic Close | Nếu truy vấn vị thế sau khi đóng thất bại, code bỏ qua xác minh nhưng vẫn tiếp tục cleanup, ghi Fill và xóa vị thế cục bộ. |
| P0-03 | CRITICAL | Panic Close | Kết quả cancel SL/TP không được kiểm tra đầy đủ; một số bản ghi local bị đổi thành `CANCELED` dù request cancel có thể thất bại. |
| P0-04 | CRITICAL | Panic Close / Accounting | Khi `avgPrice` không hợp lệ, giá Mark Price có thể được dùng làm giá thoát; phí giao dịch đang được ước tính bằng tỷ lệ cố định thay vì lấy dữ liệu thực. |
| P0-05 | CRITICAL | AI Skeptic | `independentSkepticPassed` được khởi tạo `true` khi Analyst hợp lệ và không thiếu dữ liệu. Nếu không có nhà cung cấp Skeptic riêng, hệ thống có thể đi tiếp dù Skeptic chưa chạy. |
| P0-06 | CRITICAL | Reconciliation | Logic đối soát vị thế chỉ tìm lệnh `ASTRA_SL_` trong `acc.openOrders`; không xác minh đầy đủ cả Algo SL và Algo TP, nhưng vẫn có thể đánh dấu `OPEN_PROTECTED` khi chỉ tìm thấy SL. |
| P0-07 | CRITICAL | Runtime | Có hàm đăng ký callback và ghi nhận WebSocket message nhưng không thấy nơi gọi `registerRuntimeCallbacks()` hoặc `recordStreamMessageReceived()`; cũng không thấy nơi gọi `connectMarketStream()`/`connectUserDataStream()` trong luồng ứng dụng. |
| P1-01 | HIGH | Scanner | Scanner chỉ lấy tối đa 6 mã từ danh sách top 30; giá, spread, relative volume và regime của phần lớn symbol được điền bằng dữ liệu hardcode/default khi không có snapshot đúng symbol. |
| P1-02 | HIGH | Scanner | `candidateDirection` có thể thành LONG chỉ vì minNotional phù hợp với vốn; khả năng đáp ứng minNotional không phải tín hiệu LONG. |
| P1-03 | HIGH | Calibration | Lịch sử mỗi Fill được ghép với `rankingScore` của lần đánh giá hiện tại, không phải xác suất dự báo đã lưu tại thời điểm giao dịch đó. Brier Score/calibration vì thế không có dữ liệu dự báo lịch sử đúng cặp. |
| P1-04 | HIGH | Telegram | Có hàm xử lý incoming update và gửi notification, nhưng chưa thấy transport polling `getUpdates` hoặc webhook được kết nối vào runtime để nhận lệnh thật. |
| P1-05 | HIGH | Runtime | Keepalive callback có thể null; code vẫn cập nhật telemetry như thể listenKey đã được gia hạn thành công. |
| P1-06 | HIGH | Build/Tests | ZIP có `gradle-wrapper.properties` nhưng thiếu `gradlew`, `gradlew.bat` và `gradle-wrapper.jar`, khiến không thể tái lập lệnh `./gradlew` từ archive nguyên trạng. |
| P1-07 | HIGH | Test Report | `TEST_REPORT.md` tuyên bố 17 Unit + 9 Robolectric = 26 test. Source trong ZIP có 14 `@Test` ở Unit, 10 `@Test` ở Robolectric và 1 `@Test` ở androidTest, tổng cộng 25 annotation. Cần xác minh lại báo cáo và cách đếm. |
| P2-01 | MEDIUM | Data Integrity | Rà soát lại quan hệ, index, uniqueness, transaction và migration của Room. Không được mặc định dữ liệu liên bảng nhất quán chỉ vì DAO đã hoạt động. |
| P2-02 | MEDIUM | Observability | Rà soát tính đầy đủ của correlation ID, state transition log, exception handling, metrics và cảnh báo; tránh nuốt exception mà không để lại dấu vết. |

---

# 2. GIAI ĐOẠN P0 — AN TOÀN VỐN VÀ TÍNH ĐÚNG ĐẮN CỦA GIAO DỊCH

Đây là nhóm bắt buộc sửa trước mọi nâng cấp khác. Không được mở LIVE khi còn bất kỳ lỗi P0 nào.

## P0-01. Thiết kế lại Panic Close theo nguyên tắc xác minh trước, hành động sau

**File trọng tâm:**

- `app/src/main/java/com/example/engine/AstraTradingCoordinator.kt`
- `app/src/main/java/com/example/network/BinanceAdapter.kt`
- `app/src/main/java/com/example/domain/DomainModels.kt`
- `app/src/main/java/com/example/data/local/Entities.kt`

**Hành vi cần sửa:** Trong `executePanicCloseStateMachine()`, khi `fetchAccountAndPositionsSnapshot()` thất bại, code hiện có thể fallback sang `localSignedQty`. Không được gửi lệnh đóng dựa trên quantity cũ nếu không xác minh được quantity thực trên sàn.

### Cách triển khai

1. Chia rõ ba trạng thái của truy vấn vị thế:
   - `FETCH_FAILED`: API lỗi, timeout, lỗi xác thực hoặc response không hợp lệ.
   - `VERIFIED_ZERO`: request thành công, account snapshot hợp lệ và symbol không có vị thế mở.
   - `VERIFIED_NON_ZERO`: request thành công và có positionAmt khác 0.
2. Không dùng `localSignedQty` thay thế dữ liệu sàn trong trường hợp `FETCH_FAILED`.
3. Với `FETCH_FAILED`:
   - Không gửi lệnh MARKET.
   - Không hủy hoặc sửa trạng thái SL/TP dựa trên giả định.
   - Giữ lại PositionEntity và OrderIntent đang có.
   - Đưa bot sang `RECONCILIATION_REQUIRED` hoặc trạng thái tương đương.
   - Ghi risk event CRITICAL, lưu endpoint/error code/correlation ID và phát cảnh báo Telegram nếu transport khả dụng.
4. Với `VERIFIED_ZERO`:
   - Không gửi lệnh đóng thêm.
   - Đối soát các lệnh bảo vệ còn mở.
   - Chỉ dọn dữ liệu cục bộ sau khi xác minh vị thế trên sàn bằng 0 và xử lý xong lệnh mồ côi.
5. Với `VERIFIED_NON_ZERO`:
   - Dùng signed quantity và positionSide lấy từ sàn làm nguồn sự thật.
   - Kiểm tra nhất quán giữa positionSide trên sàn và bản ghi local; nếu khác, chuyển sang xử lý reconciliation thay vì tự đoán.
   - Tính close side chính xác cho One-way/Hedge Mode.
   - Sử dụng `reduceOnly` phù hợp với chế độ tài khoản. Trong Hedge Mode phải xác minh đúng `positionSide` (`LONG`/`SHORT`) và quy tắc API tương ứng.
6. Lưu OrderIntent trước khi gửi lệnh. Dùng client ID ổn định, idempotent, có giới hạn độ dài/ký tự theo quy định Binance.
7. Nếu request submit timeout hoặc trả 5XX, truy vấn theo client ID trước khi gửi lại. Tuyệt đối không gửi lệnh đóng thứ hai theo kiểu retry mù.
8. Xử lý `PARTIALLY_FILLED`:
   - Đối soát positionAmt còn lại trên sàn.
   - Chỉ gửi lệnh đóng residual sau khi xác minh lệnh cũ đã kết thúc và residual quantity thực tế.
   - Có giới hạn số vòng, thời gian và điều kiện dừng; không lặp vô hạn.

### Điều kiện nghiệm thu

- API snapshot thất bại: không có lệnh đóng nào được gửi; vị thế local không bị xóa.
- Snapshot xác nhận vị thế bằng 0: không gửi lệnh đóng thừa.
- Snapshot có vị thế: chỉ gửi đúng side/positionSide/quantity lấy từ exchange.
- Timeout submit: không tạo lệnh trùng khi order đầu đã được Binance nhận.
- Partial fill: hệ thống xử lý residual dựa trên snapshot mới, không dựa trên phép trừ local đơn thuần.

## P0-02. Bắt buộc xác minh vị thế bằng 0 sau lệnh đóng

**File trọng tâm:** `AstraTradingCoordinator.kt`, hàm `executePanicCloseStateMachine()`.

**Hành vi cần sửa:** `postPositionCheck` hiện chỉ được xử lý khi request thành công. Nếu request thất bại, code vẫn tiếp tục cleanup, ghi Fill và có thể xóa PositionEntity. Đây là hành vi không an toàn.

### Cách triển khai

1. Sau khi lệnh đóng được xác nhận `FILLED`, bắt buộc gọi endpoint account/position để lấy trạng thái mới.
2. Nếu truy vấn thất bại hoặc response không hợp lệ:
   - Giữ PositionEntity.
   - Đặt state `PANIC_CLOSE_PENDING_RECONCILIATION` hoặc `PANIC_CLOSE_FAILED_UNVERIFIED`.
   - Không ghi trạng thái `CLOSED_CONFIRMED`.
   - Không xóa vị thế local.
   - Có thể ghi nhận order fill thô đã xác minh, nhưng không đánh dấu toàn bộ giao dịch đã đóng và không ghi PnL cuối cùng cho đến khi dữ liệu đầy đủ.
3. Nếu positionAmt còn khác 0: chuyển về trạng thái residual và xử lý theo P0-01.
4. Nếu positionAmt bằng 0: tiếp tục bước cleanup Algo/standard protective orders.
5. Sau cleanup, gọi truy vấn cuối cùng thêm một lần để xác nhận đồng thời:
   - Không còn vị thế thuộc symbol/positionSide đó.
   - Không còn lệnh bảo vệ bot-owned liên quan còn hoạt động.
   - Tất cả request cancel đã được xác nhận terminal hoặc query lại để biết trạng thái cuối.
6. Chỉ sau khi đủ các xác nhận trên mới xóa PositionEntity và đặt `CLOSED_CONFIRMED`.
7. Nếu exchange API không cho phép lấy được một nguồn xác minh, giữ trạng thái pending và thông báo rõ “chưa thể xác nhận”, không giả lập thành công.

### Điều kiện nghiệm thu

- Test mô phỏng post-close account API timeout phải chứng minh PositionEntity không bị xóa.
- Test final reconciliation thất bại phải chứng minh `confirmedClosed == false`.
- Chỉ một trường hợp vị thế bằng 0 và cleanup hoàn tất mới được phép tạo `CLOSED_CONFIRMED`.

## P0-03. Xác minh thực tế kết quả hủy SL/TP

**File trọng tâm:** `AstraTradingCoordinator.kt`, `BinanceAdapter.kt`.

### Vấn đề

Một số đoạn cleanup gọi cancel nhưng không kiểm tra kết quả. Một số OrderRecord local được cập nhật `CANCELED` ngay cả khi request cancel thất bại. Với Algo Order, phải sử dụng đúng endpoint Algo API, không được lẫn với standard order API.

### Cách triển khai

1. Tạo abstraction rõ ràng cho các thao tác:
   - Query standard order.
   - Query open standard orders.
   - Cancel standard order.
   - Query Algo order theo clientAlgoId/algoId.
   - Query open Algo orders.
   - Cancel Algo order.
2. Mỗi hàm cancel phải trả về kết quả có trạng thái đã xác minh, không chỉ boolean dựa trên HTTP 2xx.
3. Sau cancel, truy vấn lại order hoặc open-order list để xác nhận trạng thái cuối (`CANCELED`, `EXPIRED`, `FILLED` hoặc terminal state hợp lệ).
4. Chỉ cập nhật local `CANCELED` khi kết quả sàn đã được xác minh.
5. Nếu cancel thất bại:
   - Giữ local order ở trạng thái `CANCEL_PENDING`/`UNKNOWN`.
   - Không báo cleanup thành công.
   - Không xóa PositionEntity.
   - Ghi lại error category và retry có kiểm soát bằng query-first.
6. Lọc chính xác theo symbol, positionSide, bot ownership và tiền tố client ID. Không được hủy lệnh manual của người dùng.
7. Không bỏ sót Algo orders khi dọn lệnh mồ côi.

### Điều kiện nghiệm thu

- Cancel Algo thất bại: local record không được đánh dấu CANCELED.
- Cancel standard thành công nhưng query sau đó chưa rõ: trạng thái local phải là UNKNOWN/PENDING, không phải CANCELED.
- Order manual không có ownership tag không bị hủy.
- Không còn Algo order bot-owned chưa xác minh khi bot báo cleanup hoàn tất.

## P0-04. Không ghi PnL giả hoặc phí ước lượng thành PnL thực

**File trọng tâm:** `AstraTradingCoordinator.kt`, `BinanceAdapter.kt`, `Entities.kt`, `AstraDao.kt`.

### Vấn đề

Panic Close hiện có thể dùng Mark Price làm giá thoát khi avgPrice không hợp lệ và tính commission theo tỷ lệ cố định. Cách này chỉ là ước tính, không phải realized PnL thực tế.

### Cách triển khai

1. Lấy giá khớp trung bình và executed quantity từ order/trade records đã xác minh.
2. Lấy commission thực từ dữ liệu fills/trade history/income history phù hợp của Binance Futures.
3. Tách bạch các thành phần:
   - Gross realized PnL.
   - Commission.
   - Funding fee/credit.
   - Các khoản phí/điều chỉnh khác nếu có.
   - Net realized PnL.
4. Không thay avgPrice bằng Mark Price rồi ghi như fill thật. Mark Price chỉ được dùng cho estimate và phải mang nhãn `ESTIMATED`, không dùng làm giá khớp kế toán.
5. Nếu chưa thể truy vấn được phí hoặc trade details, lưu giao dịch ở trạng thái `ACCOUNTING_PENDING`/`PNL_UNVERIFIED`; cập nhật sau khi lấy được dữ liệu.
6. Dùng idempotency key/trade ID để không ghi trùng fills khi reconciliation chạy lại.
7. Xử lý multiple fills cho một order và partial close.

### Điều kiện nghiệm thu

- Thiếu avgPrice thật: không ghi PnL thực dựa trên Mark Price.
- Thiếu commission thật: PnL phải thể hiện trạng thái chưa hoàn tất hoặc estimate được phân biệt rõ.
- Reconciliation chạy nhiều lần không tạo fill trùng.

## P0-05. Independent Skeptic phải thực sự độc lập và mặc định FAIL-CLOSED

**File trọng tâm:** `AstraTradingCoordinator.kt`, `AiIntelligenceGateway.kt`, `AiApiCheckerEngine.kt`.

**Vấn đề:** `independentSkepticPassed` đang được khởi tạo theo điều kiện Analyst PASS và thiếuData rỗng. Nếu không tìm thấy provider riêng, biến có thể vẫn là `true` dù Skeptic không được gọi.

### Cách triển khai

1. Khởi tạo mặc định:
   - `skepticExecuted = false`.
   - `skepticPassed = false`.
   - `skepticProviderId = null`.
2. Chỉ set `skepticPassed = true` khi tất cả điều kiện sau cùng đúng:
   - Có provider Skeptic đã bật.
   - Provider ID khác Analyst provider ID.
   - Có secret riêng được lưu trong slot/provider mapping riêng.
   - Không có fallback dùng key của Analyst.
   - Request Skeptic thực sự được gửi và nhận response hợp lệ.
   - JSON parse/schema validation thành công.
   - Verdict đúng enum cho phép và là PASS theo contract hiện tại.
   - `missingDataFlags` rỗng.
   - Confidence penalty/risk constraints đạt ngưỡng.
   - Response gắn với đúng symbol, signal ID và snapshot/correlation ID đang được đánh giá.
3. Không cho phép Analyst tự xác nhận thay cho Skeptic.
4. Nếu không cấu hình provider Skeptic, provider timeout, lỗi auth, JSON sai schema hoặc verdict không đạt: chặn thực thi tự động. Có thể cho phép người dùng chuyển sang manual review, nhưng không được ghi AI Skeptic PASS.
5. Tạo trạng thái rõ ràng: `NOT_CONFIGURED`, `REQUESTED`, `PASSED`, `VETOED`, `INVALID_RESPONSE`, `TIMEOUT`, `AUTH_FAILED`.
6. Bảo đảm không có provider alias/model alias vô tình trỏ về cùng một endpoint/model khiến “independent” chỉ là hình thức. Ghi rõ giới hạn nếu không thể chứng minh độc lập thực sự.

### Điều kiện nghiệm thu

- Không có Skeptic provider: execution gate BLOCK.
- Skeptic timeout: BLOCK.
- Skeptic trả malformed JSON: BLOCK.
- Skeptic trả VETO/CAUTION theo chính sách hiện tại: không được coi là PASS.
- Chỉ một phản hồi hợp lệ từ provider riêng mới có thể đạt PASS.

## P0-06. Reconciliation phải xác minh cả Algo SL và Algo TP

**File trọng tâm:** `AstraTradingCoordinator.kt`, `BinanceAdapter.kt`.

**Vấn đề:** `runFullReconciliation()` hiện tìm `ASTRA_SL_` trong `acc.openOrders`, không thực hiện xác minh đầy đủ cả Algo SL và Algo TP. Tuy nhiên, lệnh bảo vệ đang được gửi qua Algo Order API; không thể dựa vào standard openOrders để khẳng định cả cặp bảo vệ đang hoạt động.

### Cách triển khai

1. Khi có remote position, truy vấn riêng:
   - Open Algo orders theo symbol.
   - Open standard orders theo symbol.
2. Tìm SL và TP bằng client ID, algo ID, symbol, side, positionSide và trigger price.
3. Xác minh từng lệnh:
   - Có trên sàn.
   - Đúng symbol/positionSide/side.
   - Đúng loại lệnh và trigger price đã làm tròn theo tickSize.
   - Trạng thái active/NEW phù hợp.
   - Không phải lệnh manual hoặc lệnh của bot khác.
4. Chỉ đặt `OPEN_PROTECTED` khi đồng thời xác minh được **cả SL và TP** theo đúng hợp đồng hiện tại.
5. Nếu chỉ có SL, chỉ có TP, cả hai mất, query thất bại hoặc trạng thái UNKNOWN: không đánh dấu OPEN_PROTECTED. Dùng trạng thái `PROTECTION_DEGRADED`/`PROTECTION_UNKNOWN` và khóa mở vị thế mới.
6. Với vị thế không có bản ghi local, không tự động nhận quyền sở hữu nếu không chứng minh được lệnh bảo vệ thuộc Astra Trader. Phân loại manual/external và dừng bot theo chính sách.
7. Khi remote position bằng 0, chỉ xóa vị thế local sau snapshot xác thực thành công. Dọn Algo và standard orders bằng đúng endpoint; kiểm tra kết quả từng lệnh.

### Điều kiện nghiệm thu

- Có SL nhưng không có TP: không được `OPEN_PROTECTED`.
- Có TP nhưng không có SL: không được `OPEN_PROTECTED`.
- Cả hai Algo orders active, đúng thông số: mới đạt `OPEN_PROTECTED`.
- Algo query lỗi: trạng thái UNKNOWN, không kết luận bảo vệ đầy đủ.

## P0-07. Bảo vệ vị thế ngay sau khi Entry khớp

**File trọng tâm:** `AstraTradingCoordinator.kt`, `BinanceAdapter.kt`.

Luồng hiện tại đã có thiết kế đặt SL và TP sau Entry, nhưng cần hoàn thiện quy trình phục hồi khi chỉ một trong hai lệnh bảo vệ được đặt thành công hoặc trạng thái không rõ.

### Cách triển khai

1. Ghi lại Entry order và trạng thái fill thực tế trước khi đặt lệnh bảo vệ.
2. Đặt SL và TP bằng client IDs riêng biệt, có idempotency và ownership tag.
3. Query/verify từng protective order trên sàn thay vì chỉ tin response submit.
4. Nếu cả hai thành công: mới ghi `OPEN_PROTECTED`.
5. Nếu một lệnh thất bại:
   - Khóa ngay mọi Entry mới.
   - Kiểm tra xem lệnh còn lại có thật sự hoạt động không.
   - Thử khôi phục lệnh thiếu theo policy có giới hạn.
   - Nếu không thể bảo vệ đủ: thực thi quy trình thoát an toàn dựa trên quantity đã xác minh từ sàn, hoặc chuyển sang manual intervention theo chính sách được phê duyệt.
   - Không để vị thế không có bảo vệ mà hệ thống vẫn báo bình thường.
6. Nếu response submit không rõ trạng thái, query theo client ID trước khi tạo lệnh mới để tránh duplicate.
7. Xử lý riêng tình huống giá đã vượt qua trigger price trước khi đặt SL/TP; không tự ý bỏ bảo vệ mà không có phương án an toàn.

### Điều kiện nghiệm thu

- SL thành công, TP thất bại: không được báo OPEN_PROTECTED; có incident CRITICAL và có quy trình phục hồi.
- TP thành công, SL thất bại: tương tự.
- Submit timeout nhưng lệnh thực tế đã tồn tại: query nhận diện được, không đặt trùng.

---

# 3. GIAI ĐOẠN P1 — RUNTIME, MARKET DATA, SCANNER VÀ CHẤT LƯỢNG QUYẾT ĐỊNH

## P1-01. Nối hoàn chỉnh TradingForegroundService với runtime thực

**File trọng tâm:**

- `service/TradingForegroundService.kt`
- `network/BinanceAdapter.kt`
- `engine/AstraTradingCoordinator.kt`
- `ui/AstraViewModel.kt`
- `service/BootRecoveryReceiver.kt`

### Vấn đề đã phát hiện

- Có định nghĩa `registerRuntimeCallbacks()` nhưng không thấy nơi gọi.
- Có `recordStreamMessageReceived()` nhưng không thấy nơi gọi.
- Có `connectMarketStream()` và `connectUserDataStream()` nhưng không thấy nơi kết nối vào lifecycle runtime.
- `lastStreamMessageUtcMs` không được cập nhật từ listener. Vì vậy stale detection hiện không phản ánh message thực tế.
- `keepAliveCallback?.invoke()` có thể không chạy gì, nhưng telemetry vẫn cập nhật thời gian như thể listenKey đã gia hạn thành công.
- Exception ở heartbeat/keepalive đang bị bắt và bỏ qua mà không có log đủ thông tin.

### Kiến trúc cần triển khai

1. Tạo một runtime owner rõ ràng, ví dụ `TradingRuntimeController` hoặc `TradingRuntimeManager`, chịu trách nhiệm vòng đời adapter, coordinator, stream và service.
2. Service phải khởi tạo runtime dependencies một cách xác định, không phụ thuộc Activity còn sống.
3. Khi service start:
   - Kiểm tra cấu hình và trạng thái bot.
   - Kiểm tra credential/Vault.
   - Đồng bộ clock.
   - Xác minh account/reconciliation trước khi cho phép bất kỳ Entry nào.
   - Lấy exchange metadata thật.
   - Tạo hoặc gia hạn listenKey khi cần.
   - Kết nối Market Stream và User Data Stream.
   - Đăng ký callback heartbeat, reconcile, keepalive và reconnect.
4. Trong mỗi WebSocket listener:
   - Gọi hàm báo message nhận được.
   - Parse event theo schema rõ ràng.
   - Cập nhật snapshot/Room thông qua repository/coordinator thread-safe.
   - Không để exception parse làm chết toàn bộ runtime.
5. ListenKey keepalive chỉ cập nhật telemetry thành công khi API trả kết quả thành công và key được xác nhận còn hợp lệ.
6. Khi reconnect:
   - Đóng stream cũ.
   - Dùng exponential backoff có jitter và giới hạn.
   - Kết nối lại đúng stream.
   - Đối soát REST sau reconnect trước khi cho Entry mới.
7. Thêm NetworkCallback để phát hiện mất/kết nối lại mạng.
8. Phân biệt trạng thái `SERVICE_RUNNING`, `STREAM_CONNECTED`, `USER_STREAM_CONNECTED`, `RECONCILING`, `TRADING_READY`, `PAUSED`, `DEGRADED`.
9. Heartbeat không được tự suy ra “trading ready” chỉ từ việc service process còn chạy.
10. Ghi log rõ các lỗi callback, listener, API và reconnect. Không dùng empty catch cho lỗi có ảnh hưởng đến trạng thái tài chính.
11. Đảm bảo `onDestroy()` giải phóng WebSocket, WakeLock, coroutine và callback. `onStartCommand()` gọi nhiều lần không tạo nhiều runtime instance.
12. Kiểm tra Boot Recovery: chỉ khôi phục service khi điều kiện Android cho phép, user đã cấp quyền cần thiết và state local không yêu cầu manual review.

### Giới hạn Android cần ghi rõ

Không được quảng cáo “24/7 tuyệt đối”. Android, OEM battery management, force-stop, mất mạng, reboot, permission, foreground-service restrictions và tiến trình hệ thống đều có thể làm gián đoạn app. Mục tiêu là tự phục hồi có kiểm soát và báo trạng thái trung thực, không cam kết uptime tuyệt đối.

### Điều kiện nghiệm thu

- Start service phải tạo đúng một runtime loop.
- Có message WebSocket thật/fake thì timestamp stream phải cập nhật.
- Không nhận message quá ngưỡng stale thì runtime chuyển DEGRADED và reconnect đúng.
- Không có listenKey hoặc callback null thì không được báo keepalive success.
- Mất mạng rồi có mạng lại: reconnect và REST reconciliation diễn ra đúng thứ tự.
- Không Entry mới trước khi reconciliation hậu reconnect hoàn tất.

## P1-02. Viết lại Multi-Symbol Scanner bằng dữ liệu thật cho từng symbol

**File trọng tâm:** `engine/AstraTradingCoordinator.kt`, `network/BinanceAdapter.kt`, `quant/QuantMathEngine.kt`.

### Vấn đề đã phát hiện

`runMultiSymbolCandidateScanner()` hiện:

- Lấy top 30 ticker nhưng chỉ xử lý tối đa 6 mã.
- Với symbol không phải symbol của snapshot hiện tại, dùng giá hardcode hoặc default.
- Spread/relativeVolume/regime được điền bằng giá trị default.
- Score gần như dựa trên minNotional feasibility và spread mặc định.
- Có thể gán LONG chỉ vì `minNotionalFeasible == true`.

Đây không phải scanner breakout/momentum/volume-spike đủ dữ liệu.

### Cách triển khai

1. Xây dựng universe hợp lệ từ `exchangeInfo`:
   - Chỉ USDT perpetual futures đang TRADING.
   - Loại symbol không đủ thanh khoản, không hỗ trợ loại lệnh cần thiết hoặc có trạng thái bất thường.
   - Loại stablecoin pairs/leveraged tokens nếu strategy không cho phép.
2. Dùng 24h quote volume để pre-filter 20–50 cặp, nhưng không coi volume 24h là tín hiệu vào lệnh.
3. Với mỗi symbol, lấy đầy đủ dữ liệu cần thiết:
   - Kline 15m/1h theo lookback đủ tính indicator.
   - Mark Price.
   - Best bid/ask hoặc bookTicker để tính spread.
   - Volume hiện tại so với baseline để tính Relative Volume.
   - Dữ liệu cần thiết cho funding/market regime nếu strategy dùng chúng.
4. Tính features riêng từng symbol. Không được tái sử dụng `_latestFeatureSnapshot` của symbol khác.
5. Dùng concurrency có giới hạn, timeout, rate-limit budget và cache TTL. Không bắn đồng thời vô hạn request gây Binance 429/418.
6. Mỗi feature phải có timestamp, source, validity và maximum age. Snapshot quá cũ hoặc thiếu field thì candidate bị loại hoặc chuyển `DATA_INSUFFICIENT`.
7. Nếu Binance API lỗi:
   - Không fallback sang giá hardcode trong LIVE.
   - Giữ trạng thái scanner degraded.
   - Có thể dùng cache gần nhất chỉ khi còn TTL và ghi rõ `STALE/CACHED`; không được giả làm real-time.
8. `candidateDirection` chỉ được xác định từ điều kiện tín hiệu thật: breakout up/down, EMA alignment, momentum, relative volume, spread, regime và strategy rules.
9. MinNotional feasibility chỉ là điều kiện khả thi của lệnh, không phải direction/alpha.
10. Tạo scoring minh bạch, có normalize từng yếu tố và giải thích lý do candidate được chọn/bị loại.
11. Tách rõ các bước:
    - Universe filter.
    - Data acquisition.
    - Feature calculation.
    - Signal detection.
    - Capital feasibility.
    - Risk screening.
    - Ranking.
12. Chỉ đưa top candidate cho `scanAndEvaluateSymbol()` sau khi đủ dữ liệu thật.

### Điều kiện nghiệm thu

- Không có đường code LIVE nào dùng giá hardcode hoặc default làm dữ liệu thị trường.
- Symbol thiếu candle/order book/price hợp lệ không thể trở thành TRADE_CANDIDATE.
- Candidate LONG/SHORT chỉ xuất hiện khi điều kiện chiến lược tương ứng đạt.
- Unit test có dữ liệu thật giả lập cho từng symbol, dữ liệu stale, thiếu trường, API 429, timeout và minNotional infeasible.
- Test chứng minh snapshot DOGE không bị dùng cho BTC/ETH hoặc symbol khác.

## P1-03. Sửa hoàn toàn Probability Calibration

**File trọng tâm:** `engine/AstraTradingCoordinator.kt`, `quant/QuantMathEngine.kt`, `data/local/Entities.kt`, `AstraDao.kt`.

### Vấn đề đã phát hiện

Lịch sử `FillEntity` đang được map với `rankingScore` hiện tại. Mỗi fill cũ do đó nhận cùng xác suất dự báo của lần phân tích hiện tại. Dữ liệu này không phù hợp để tính Brier Score/ECE.

### Cách triển khai

1. Lưu xác suất dự báo gốc của từng signal/decision tại thời điểm phát sinh.
2. Duy trì liên kết bền vững:
   - `signalId`.
   - `decisionId`.
   - `orderIntentId`.
   - `clientOrderId`.
   - `tradeId`/fill IDs.
   - `predictedWinProbability`.
   - snapshot/features version.
   - timestamp dự báo.
3. Khi giao dịch đóng, tính outcome từ net realized PnL thực đã xác minh, sau phí/funding theo định nghĩa thống nhất.
4. Gom partial fills theo trade/position lifecycle để không đếm một giao dịch nhiều lần như nhiều sample độc lập.
5. Loại record thiếu predicted probability, thiếu outcome hoặc có mapping mơ hồ.
6. Không lấy ranking score hiện tại để thay probability lịch sử.
7. Tính Brier Score, reliability bins/ECE từ đúng cặp `(predictedProbabilityAtDecision, actualOutcome)`.
8. Hiển thị cỡ mẫu, khoảng thời gian dữ liệu, tỷ lệ sample bị loại và trạng thái calibration.
9. Chỉ cho phép calibration hợp lệ khi đạt điều kiện cỡ mẫu và ngưỡng chất lượng được spec quy định. Khi chưa đạt, trả về trạng thái `INSUFFICIENT_SAMPLE`/`INVALID_CALIBRATION` và chặn LIVE tự động nếu policy yêu cầu.
10. Có migration cho schema mới; không tự điền xác suất quá khứ bằng giá trị giả để làm đủ N.

### Điều kiện nghiệm thu

- Mỗi sample calibration phải truy vết được tới decision dự báo thực.
- Thay đổi xác suất hiện tại không làm thay đổi kết quả calibration quá khứ.
- Duplicate fills không làm tăng N giả.
- Dataset thiếu probability phải bị loại và báo rõ.

## P1-04. Hoàn thiện Telegram inbound transport

**File trọng tâm:** `telegram/TelegramBotController.kt`, `TradingForegroundService.kt`, `AstraViewModel.kt`, `AstraDao.kt`.

### Vấn đề đã phát hiện

Có `processIncomingUpdate()` và `sendVietnameseNotification()`, nhưng chưa thấy lớp transport thực hiện polling Telegram `getUpdates` hoặc webhook được runtime gọi. Command parser riêng không có nghĩa bot đang nhận lệnh thực tế.

### Cách triển khai

1. Chọn một cơ chế inbound duy nhất cho phiên bản Android:
   - Long polling `getUpdates` trong runtime có kiểm soát, hoặc
   - Webhook qua backend đáng tin cậy. Không tạo webhook giả trực tiếp trên Android nếu app không có public endpoint ổn định.
2. Với long polling:
   - Lưu `offset` bền vững.
   - Xử lý `update_id` idempotent.
   - Chỉ tăng offset sau khi update đã được lưu/đánh dấu an toàn.
   - Xử lý timeout, 429, retry-after và reconnect.
3. Giữ nguyên allowlist `user_id` và `chat_id`.
4. Không để người không được phép biết bot có tồn tại hay trạng thái hiện tại.
5. Kiểm tra command grammar nghiêm ngặt; không chấp nhận command suffix/extra parameters không được định nghĩa.
6. Panic vẫn phải có xác nhận hai bước và nonce hết hạn; đảm bảo nonce an toàn khi có nhiều request đồng thời hoặc process restart.
7. Không cho phép bật LIVE, nhập API key hoặc thay đổi SecretVault qua Telegram.
8. Phản hồi người dùng sau khi command được thực thi/xác minh, không báo thành công ngay khi mới nhận yêu cầu.
9. Log inbound update đã sanitize, không ghi bot token hoặc secret.

### Điều kiện nghiệm thu

- Polling nhận update thật từ MockWebServer/mock Telegram API.
- Duplicate update không thực thi hai lần.
- Unauthorized user/chat không thể gọi command.
- Timeout/polling lỗi không làm runtime chết.
- `/panic` chưa xác nhận hoặc nonce hết hạn không thực hiện đóng.

## P1-05. Sửa tính trung thực của runtime telemetry và error handling

**File trọng tâm:** `TradingForegroundService.kt`, `BinanceAdapter.kt`, `AstraTradingCoordinator.kt`.

1. Không cập nhật `lastListenKeyKeepAliveUtcMs` nếu callback null hoặc API thất bại.
2. Không coi `serviceActive` là `marketDataHealthy`.
3. Thêm `lastMarketMessageAt`, `lastUserEventAt`, `lastSuccessfulReconcileAt`, `lastListenKeyRenewalAt` độc lập.
4. Phân biệt network connected, REST authenticated, market WS connected và user data WS connected.
5. Không nuốt exception quan trọng bằng `catch (_: Exception) {}`. Có thể giữ runtime sống bằng SupervisorJob, nhưng phải log lỗi có category, stack trace đã sanitize, component và correlation ID.
6. Tránh log API key, signature, listenKey, full request URL có token, prompt có secret hoặc dữ liệu cá nhân không cần thiết.
7. Thêm cảnh báo khi stream stale, auth fail, data stale, reconcile fail, clock drift hoặc service restart loop.
8. Có state recovery sau process death: đọc database và remote exchange trước khi tiếp tục Entry.

---

# 4. GIAI ĐOẠN P2 — ĐỘ TIN CẬY, BẢO TRÌ VÀ TỐI ƯU HỆ THỐNG

## P2-01. Chuẩn hóa build và khả năng tái lập

**File trọng tâm:** root Gradle config, `gradle/wrapper/`, CI configuration.

1. Bổ sung Gradle Wrapper đầy đủ (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) hoặc cung cấp phương án build chuẩn được tài liệu hóa.
2. Pin phiên bản Gradle/JDK/Android Gradle Plugin/Kotlin/Compose dependencies rõ ràng.
3. Xác minh tương thích giữa Gradle 9.3.1 đang khai báo, Android Gradle Plugin và plugin hiện dùng. Không tự nâng version chỉ để “mới hơn”.
4. Thêm CI pipeline chạy:
   - `./gradlew clean`
   - `./gradlew testDebugUnitTest`
   - `./gradlew testDebugUnitTest` với Robolectric/MockWebServer theo cấu hình thực tế.
   - `./gradlew assembleDebug`
   - Lint/static analysis nếu dự án hỗ trợ.
5. Ghi lại JDK, Gradle, Android SDK và command chính xác trong TEST_REPORT.
6. Test report phải được tạo từ kết quả chạy thực tế, không viết số test bằng tay.

## P2-02. Sửa độ chính xác của báo cáo kiểm thử

Trong ZIP hiện tại, số lượng annotation được rà bằng source là:

- `ExampleUnitTest.kt`: 14 `@Test`.
- `ExampleRobolectricTest.kt`: 10 `@Test`.
- `ExampleInstrumentedTest.kt`: 1 `@Test`.
- Tổng: 25 annotation trong các file trên.

Trong khi `TEST_REPORT.md` tuyên bố 17 Unit Tests + 9 Robolectric Tests = 26. Hãy:

1. Đếm test tự động từ source/test runner thực tế.
2. Phân loại Unit, Robolectric, Instrumented, Integration.
3. Ghi test được phát hiện, test được chạy, test pass, fail, ignored/skipped riêng biệt.
4. Nếu một test method có parameterized cases, ghi số method và số case riêng.
5. Không ghi “100% PASS” nếu một suite không chạy.
6. Lưu kết quả lệnh Gradle thực tế hoặc CI run ID.

## P2-03. Mở rộng test theo failure scenario

Bổ sung test cho toàn bộ kịch bản dưới đây bằng fake adapter/MockWebServer/Room in-memory DB. Không dùng API thật hoặc vốn thật.

### Panic Close

- Pre-close account snapshot timeout.
- Pre-close snapshot thành công nhưng symbol không tồn tại.
- Pre-close snapshot có quantity khác local.
- One-way mode và Hedge Mode.
- Submit timeout nhưng order đã tồn tại trên sàn.
- Submit 5XX và không có order.
- `PARTIALLY_FILLED`.
- Post-close snapshot timeout.
- Post-close snapshot vẫn còn residual position.
- Cancel SL thất bại.
- Cancel TP thất bại.
- Query Algo order thất bại.
- Final reconciliation timeout.
- App process chết giữa từng stage và khôi phục lại.
- Hai thao tác Panic cùng lúc.
- Panic cạnh tranh với Entry hoặc Reconciliation.

### Protection / Reconciliation

- Có cả SL và TP Algo active.
- Chỉ có SL.
- Chỉ có TP.
- Cả hai không tồn tại.
- SL/TP ở trạng thái CANCELED/EXPIRED/FILLED.
- Query Algo API timeout.
- Vị thế manual không thuộc bot.
- Vị thế local stale nhưng remote zero.
- Remote position còn nhưng local record mất.
- Algo orders còn lại sau position close.

### Skeptic / AI

- Không có Skeptic provider.
- Provider dùng chung ID với Analyst.
- Provider có key riêng nhưng auth fail.
- Timeout.
- JSON malformed.
- Schema thiếu field.
- Symbol/correlation ID không khớp.
- Verdict PASS/VETO/CAUTION.
- Provider trả confidence penalty vượt ngưỡng.
- Primary provider và Skeptic provider trả cùng một cấu hình model không độc lập.

### Scanner / Market Data

- Symbol thiếu candles.
- Symbol stale.
- Symbol thiếu order book.
- Spread quá cao.
- Relative volume thấp.
- Breakout giả.
- Breakout up/down.
- API 429/418/5XX.
- Cache hết TTL.
- ExchangeInfo filter chưa tải.
- MinNotional không đáp ứng.
- Symbol không phải USDT perpetual hoặc không ở trạng thái TRADING.
- Không được phát sinh giá/default synthetic trong LIVE.

### Runtime / Telegram

- Service start lặp nhiều lần.
- Process restart.
- Mất mạng và hồi mạng.
- Market stream stale.
- User data stream stale.
- ListenKey hết hạn/renew lỗi.
- Callback không đăng ký.
- Telegram duplicate update.
- Telegram offset restart.
- Unauthorized chat/user.
- Telegram 429/timeout.

### Calibration / Accounting

- Dự báo được lưu đúng thời điểm.
- Một trade có nhiều fills.
- Một trade đóng nhiều phần.
- Fill trùng.
- Outcome thiếu.
- Commission thiếu.
- Funding âm/dương.
- Không được map current rankingScore vào lịch sử.

## P2-04. Rà soát Room Database và toàn vẹn dữ liệu

1. Kiểm tra các entity có cần Foreign Key thực sự hay không. Nếu có, khai báo rõ và xác định `onDelete` policy.
2. Bổ sung index cho các trường truy vấn thường xuyên: symbol, status, createdAt, updatedAt, clientOrderId, signalId, correlationId.
3. Bảo đảm unique constraint cho `clientOrderId`, `clientAlgoId`, trade/fill identity và update ID.
4. Dùng transaction cho các thao tác ghi nhiều bảng liên quan đến một transition giao dịch.
5. Có migration test từ từng version database được hỗ trợ.
6. Không xóa hoặc overwrite record tài chính khi migration thất bại.
7. Có chiến lược backup/restore phù hợp, nhưng tuyệt đối không sao lưu plain-text API secrets.
8. Xác minh khi process chết giữa transaction, database vẫn ở trạng thái nhất quán.

## P2-05. Chuẩn hóa state machine và kiến trúc module

1. Tách state của Bot, Order, Position, Protective Orders, Account và Runtime. Không dùng một enum tổng để biểu diễn nhiều chiều trạng thái.
2. Tập trung các transition hợp lệ vào state machine/domain service; cấm UI hoặc callback tự sửa trạng thái tài chính tùy tiện.
3. Mọi transition phải có:
   - Old state.
   - New state.
   - Reason code.
   - Timestamp.
   - Correlation ID.
   - Evidence/source.
4. Dùng typed result/sealed class cho API outcome; tránh boolean chung chung làm mất thông tin lỗi.
5. Tách Repository khỏi Compose UI và Android Service.
6. Tách Market Data, Signal Engine, Risk Engine, Execution Engine, Reconciliation và Accounting để mỗi module có thể test độc lập.
7. Giữ `GlobalCapitalMutex` hoặc cơ chế đồng bộ tương đương bao phủ các thao tác có thể thay đổi exposure; kiểm tra deadlock và không giữ lock khi chờ request không giới hạn.
8. Đảm bảo idempotency xuyên suốt Entry, protective orders, cancel, close, fills và Telegram command.

## P2-06. Nâng cấp Observability và audit trail

1. Mỗi chu kỳ scanner có `scanId`; mỗi candidate có `candidateId`; mỗi trade có correlation chain.
2. Dùng structured logs thay vì chuỗi tự do khó truy vấn.
3. Ghi latency, HTTP status, Binance error code, retry count, request weight và network state.
4. Bổ sung metrics:
   - WebSocket uptime/staleness.
   - ListenKey renewal success/failure.
   - Scanner coverage và rejected reasons.
   - AI latency/error/token estimate.
   - Order submit-to-fill latency.
   - Protection verification latency.
   - Reconciliation mismatch count.
   - Unknown order/position count.
5. Có dashboard/log screen phân biệt “không có tín hiệu” với “không đủ dữ liệu”, “API lỗi”, “scanner chưa chạy” và “bot đang bị khóa”.
6. Không dùng màu xanh hoặc nhãn SUCCESS khi dữ liệu chỉ là cache, estimated hoặc unverified.
7. Sanitize secrets ở mọi log, report export và Telegram payload.

---

# 5. CHUẨN HÓA AI GATEWAY VÀ QUẢN LÝ NHÀ CUNG CẤP

Đây là nâng cấp quan trọng nhưng không được ưu tiên cao hơn lỗi P0 về vốn.

1. Tạo interface thống nhất cho provider: capability profile, auth scheme, endpoint, model, timeout, max output, JSON mode, streaming support và rate-limit behavior.
2. Không giả định mọi OpenAI-compatible provider hỗ trợ cùng một payload hoặc response schema.
3. Phân biệt:
   - API key hợp lệ.
   - Endpoint hoạt động.
   - Model tồn tại.
   - Model có quyền truy cập.
   - Model hỗ trợ JSON mode/tool use/streaming.
   - Provider còn quota.
4. AI API Checker phải thử request thực tế theo capability profile, không chỉ kiểm tra HTTP 200.
5. API failover chỉ chuyển provider khi policy cho phép và payload được chuyển đổi tương thích.
6. Không chuyển request sang provider khác mà không lưu provider/model thực tế đã phản hồi.
7. Không cho phép failover làm mất vai trò Skeptic độc lập.
8. Có circuit breaker, cooldown, retry-after và quota budget; tránh liên tục gọi provider đang lỗi.
9. Token optimization chỉ được thực hiện khi không làm mất dữ liệu đầu vào cần thiết hoặc khiến model suy luận từ dữ liệu rút gọn sai lệch.
10. Ưu tiên rule/code deterministic cho các tác vụ số học, risk và order validation; không gửi việc tính toán dễ kiểm chứng sang LLM.
11. Lưu prompt template version, model ID, provider ID, latency, token estimates và schema validation status để audit.
12. Không log secret, full authorization header hoặc nội dung nhạy cảm.

---

# 6. QUY TRÌNH TRIỂN KHAI THEO THỨ TỰ

| Thứ tự | Giai đoạn | Phạm vi | Điều kiện để chuyển bước |
|---|---|---|---|
| 0 | Baseline | Kiểm kê source, xác minh build, tạo test baseline | Có kết quả build/test thực tế hoặc ghi rõ blocker môi trường |
| 1 | P0 Safety | Panic Close, cancel/verify SL/TP, Skeptic fail-closed, reconciliation, accounting | Tất cả P0 tests PASS; không còn đường code báo trạng thái an toàn khi thiếu xác minh |
| 2 | P1 Runtime | WebSocket, listenKey, Foreground Service, reconnect, Telegram inbound | Runtime integration tests PASS; telemetry phản ánh trạng thái thật |
| 3 | P1 Scanner | Market data theo từng symbol, feature calculation, scoring, data freshness | Không dùng synthetic/default market values trong LIVE; scanner tests PASS |
| 4 | P1 Calibration | Lưu predicted probability và outcome đúng cặp | Dataset calibration có lineage và test lịch sử PASS |
| 5 | P2 Reliability | Gradle reproducibility, DB integrity, state machine, logs, CI | Build/test tự động tái lập được |
| 6 | Testnet soak | Chạy mô phỏng end-to-end trên Binance Testnet | Hoạt động ổn định trong thời gian kiểm thử đã định, không có lỗi P0, mọi order/position được reconcile |
| 7 | Manual release review | Đánh giá checklist và rủi ro còn lại | Người vận hành chủ động phê duyệt; không tự bật LIVE |

Không được coi việc compile thành công là bằng chứng hệ thống giao dịch hoạt động đúng. Cũng không được coi test unit pass là bằng chứng runtime 24/7 đã hoạt động.

---

# 7. CHECKLIST ĐIỀU KIỆN AN TOÀN TRƯỚC KHI CHO PHÉP LIVE

Tất cả mục dưới đây phải có bằng chứng kiểm thử hoặc xác minh rõ ràng. Bất kỳ mục CRITICAL nào chưa đạt đều phải khóa LIVE.

- [ ] Không còn Panic Close dùng quantity local khi exchange snapshot thất bại.
- [ ] Không xóa PositionEntity khi post-close/final reconciliation chưa xác minh.
- [ ] Cancel Algo/standard orders được query xác nhận; không cập nhật local status giả.
- [ ] PnL/commission/funding phân biệt verified với estimated.
- [ ] Skeptic thiếu provider hoặc gặp lỗi luôn BLOCK.
- [ ] Reconciliation xác minh cả Algo SL và Algo TP.
- [ ] Vị thế chỉ được `OPEN_PROTECTED` khi cả SL và TP đang active và khớp chính xác.
- [ ] Nếu protection failure, Entry mới bị khóa và có recovery/incident path.
- [ ] WebSocket Market/User Data được kết nối trong runtime thật.
- [ ] Stream event được ghi timestamp; stale detection và reconnect hoạt động.
- [ ] ListenKey keepalive chỉ báo thành công sau API success.
- [ ] Service restart/reboot recovery phải reconciliation trước khi Entry.
- [ ] Scanner dùng dữ liệu thật, đúng symbol, còn hạn; không có hardcode fallback trong LIVE.
- [ ] Hướng LONG/SHORT đến từ signal logic, không đến từ minNotional feasibility.
- [ ] Calibration ghép xác suất dự báo lịch sử với outcome tương ứng.
- [ ] Unknown order status không retry mù.
- [ ] Tất cả order mutations có idempotent identifiers và audit trail.
- [ ] Risk limits, daily loss, drawdown, max exposure và circuit breaker có test.
- [ ] Telegram authorization và dedup hoạt động; Telegram không thể bật LIVE hoặc thay secret.
- [ ] SecretVault fail-closed; không có plain-text secret trong DB/log/export.
- [ ] Build và test được tái lập từ repository sạch.
- [ ] Test report phản ánh đúng số suite/test thực sự chạy.
- [ ] Có test end-to-end trên Testnet cho Entry → SL/TP → reconciliation → partial close → Panic Close.
- [ ] Có hướng dẫn dừng bot và xử lý vị thế khi app/mạng/API gặp sự cố.

---

# 8. QUY ĐỊNH VỀ TEST VÀ BẰNG CHỨNG

## 8.1. Trước khi sửa

- Chạy build/test baseline nếu môi trường cho phép.
- Lưu kết quả lỗi ban đầu.
- Tạo test tái hiện từng lỗi P0/P1 trước khi thay đổi implementation.

## 8.2. Sau mỗi thay đổi

- Chạy test gần nhất với module vừa sửa.
- Chạy toàn bộ unit/Robolectric suite định kỳ.
- Chạy compile/assemble.
- Chạy static analysis/lint nếu có.
- Kiểm tra diff để loại bỏ code thừa, debug bypass, hardcoded test key và cờ giả lập.

## 8.3. Báo cáo test

Mỗi lần báo PASS phải kèm:

- Lệnh thực tế đã chạy.
- Môi trường (JDK, Gradle, Android SDK).
- Tổng test discovered.
- Tổng test executed.
- PASS/FAIL/SKIPPED.
- Các suite không thể chạy và lý do.
- Log ngắn chứng minh build task hoàn tất.

Không được dùng các cách sau:

- Viết test chỉ assert giá trị hardcode mà không gọi logic cần kiểm tra.
- Mock mọi dependency rồi kết luận runtime tích hợp hoạt động.
- Bỏ qua test fail hoặc xóa test để đạt 100% PASS mà không giải thích.
- Cập nhật TEST_REPORT bằng số liệu cũ.
- Khai báo “Binance Testnet verified” khi chưa gửi và reconcile các request trên Testnet.

---

# 9. YÊU CẦU BÁO CÁO SAU KHI HOÀN THÀNH

Sau khi sửa xong, cập nhật hoặc tạo các tài liệu sau:

1. `AUDIT_REPORT.md`: các lỗi đã xác minh, lỗi đã sửa, lỗi còn tồn tại.
2. `IMPLEMENTATION_REPORT.md`: thay đổi kiến trúc và code theo từng module.
3. `TEST_REPORT.md`: test thực chạy, số liệu đúng, lệnh chạy và kết quả.
4. `CHANGELOG.md`: thay đổi theo phiên bản/ngày.
5. `REMEDIATION_PLAN.md`: đánh dấu task hoàn tất/chưa hoàn tất và liên kết commit/file.
6. `KNOWN_LIMITATIONS.md` nếu còn giới hạn về Android uptime, Telegram transport, Binance endpoint hoặc provider capability.

Báo cáo cuối cùng phải có bảng:

| ID | Mức độ | Nguyên nhân gốc | File đã sửa | Thay đổi | Test chứng minh | Trạng thái |
|---|---|---|---|---|---|---|

Mỗi task chỉ được đánh dấu `DONE` khi:

- Code đã được sửa.
- Có test phù hợp.
- Test đã chạy và PASS.
- Không còn đường code phụ bỏ qua fix.
- Tài liệu đã cập nhật.

Các trạng thái cho phép:

- `DONE`: đã triển khai và test PASS.
- `PARTIAL`: đã triển khai một phần, còn giới hạn rõ ràng.
- `BLOCKED`: chưa thể triển khai, nêu blocker và bằng chứng.
- `NOT_STARTED`: chưa xử lý.
- `NEEDS_REVIEW`: cần người vận hành/phụ trách xác nhận.

---

# 10. YÊU CẦU ĐẦU RA CHO ENGINEERING AGENT

**Hãy bắt đầu đọc repository và thực hiện việc sửa trực tiếp. Không trả lại một bản kế hoạch chung chung thay cho implementation.**

Ưu tiên tuyệt đối:

1. Bảo đảm hệ thống không tự tạo thêm rủi ro khi không biết trạng thái thực tế trên Binance.
2. Bảo đảm vị thế chỉ được coi là có bảo vệ khi xác minh đủ cả SL và TP.
3. Bảo đảm Panic Close chỉ báo thành công khi đã xác nhận vị thế bằng 0 và cleanup hoàn tất.
4. Bảo đảm AI Skeptic thực sự chạy độc lập hoặc chặn giao dịch.
5. Bảo đảm runtime, WebSocket, listenKey, scanner và Telegram được nối thành luồng hoạt động thật, không chỉ tồn tại dưới dạng class/function riêng lẻ.
6. Bảo đảm mọi dữ liệu scanner/calibration/accounting đều có nguồn gốc và timestamp có thể truy vết.
7. Bảo đảm build/test có thể tái lập và báo cáo phản ánh đúng thực tế.

**Không bật LIVE. Không sử dụng vốn thật. Không tuyên bố hệ thống an toàn chỉ dựa trên compile hoặc unit tests.**

Kết quả cần bàn giao là repository đã được sửa, test mới, báo cáo cập nhật và danh sách rõ ràng những việc chưa thể hoàn tất. Nếu không thể thực hiện một hạng mục, phải nêu chính xác blocker, file liên quan và phương án thay thế an toàn.
