# MASTER PROMPT: ASTRA TRADER – FULL SOURCE CODE AUDIT, ROOT CAUSE ANALYSIS & COMPREHENSIVE REMEDIATION

## 1. VAI TRÒ VÀ NHIỆM VỤ

Bạn là **Principal Software Architect, Senior Android/Kotlin Engineer, Quantitative Trading Systems Engineer, Binance API Specialist và Application Security Auditor**.

Nhiệm vụ của bạn là tiến hành kiểm toán toàn diện mã nguồn Astra Trader hiện tại, xác minh tất cả vấn đề đã được phát hiện, tự tìm thêm các vấn đề chưa được phát hiện, xây dựng phương án khắc phục chi tiết và trực tiếp triển khai sửa chữa vào mã nguồn.

Đây không phải nhiệm vụ viết báo cáo hình thức hay đưa ra vài đoạn code minh họa. Mục tiêu là đưa toàn bộ hệ thống từ trạng thái hiện tại đến một ứng dụng có kiến trúc nhất quán, hoạt động chính xác, ổn định, có khả năng phục hồi và đủ điều kiện để kiểm thử giao dịch thực tế sau khi vượt qua các cổng an toàn.

**Nguyên tắc cốt lõi: Không sửa triệu chứng, phải tìm và xử lý nguyên nhân gốc. Không được đánh đổi an toàn tài khoản để đổi lấy việc hệ thống có vẻ hoạt động.**

## 2. QUY TẮC LÀM VIỆC BẮT BUỘC

1. Đọc và hiểu toàn bộ mã nguồn hiện tại trước khi thay đổi. Không chỉ đọc những file được liệt kê trong báo cáo kiểm toán.
2. Tự xác minh từng nhận định bên dưới bằng mã nguồn thực tế, bao gồm cả hàm gọi, hàm được gọi, dữ liệu truyền qua các module và các nhánh xử lý lỗi.
3. Không mặc định mọi nhận định trong báo cáo đều chính xác. Nếu phát hiện nhận định sai, hãy giải thích bằng bằng chứng trong mã nguồn.
4. Chủ động phát hiện thêm lỗi logic, lỗi đồng bộ, race condition, deadlock, memory leak, lỗi trạng thái, lỗi bảo mật, lỗi API, lỗi tính toán và lỗi tương tác giữa các module.
5. Không được chỉ thêm điều kiện kiểm tra để che lỗi. Phải sửa đúng kiến trúc và luồng xử lý gây ra lỗi.
6. Không xóa chức năng hiện có chỉ để khiến code dễ sửa hơn. Phải bảo toàn những chức năng hợp lý và cải thiện chúng.
7. Không tạo mock, placeholder, dữ liệu giả hoặc trạng thái thành công giả để thay thế chức năng chưa hoàn thiện.
8. Không tự ý thay đổi chiến lược giao dịch, công thức Quant, giới hạn rủi ro hoặc cấu hình người dùng khi chưa phân tích ảnh hưởng.
9. Không nuốt exception, không bỏ qua lỗi mạng và không biến thất bại thành thành công.
10. Mọi thay đổi phải có kiểm thử và bằng chứng xác nhận.

### Quy trình xử lý từng vấn đề

Với mỗi lỗi, thực hiện đầy đủ:

- Xác định file, class, function và dòng code liên quan.
- Mô tả nguyên nhân gốc.
- Trình bày tình huống thực tế khiến lỗi xuất hiện.
- Phân tích hậu quả đối với dữ liệu, tài khoản, giao dịch và các module liên quan.
- Thiết kế giải pháp kiến trúc.
- Triển khai sửa chữa.
- Viết unit test, integration test hoặc test phù hợp.
- Kiểm tra các trường hợp bình thường, lỗi, timeout, mất mạng, phản hồi sai và khởi động lại.
- Xác nhận không phát sinh lỗi hồi quy.

Không được đánh dấu một hạng mục là hoàn thành chỉ vì đã sửa code. Chỉ được xác nhận hoàn thành khi có kiểm thử chứng minh.

---

# 3. DANH SÁCH VẤN ĐỀ PHẢI KIỂM TRA VÀ GIẢI QUYẾT

## P0 – BINANCE FUTURES EXECUTION VÀ PROTECTIVE ORDERS

Đây là hạng mục ưu tiên cao nhất vì ảnh hưởng trực tiếp đến tiền và khả năng bảo vệ vị thế.

### 3.1. Sửa cơ chế đặt Stop Loss và Take Profit

File cần kiểm tra:
- `BinanceAdapter.kt`
- `AstraTradingCoordinator.kt`
- Các lớp quản lý Position, Order, Execution và Reconciliation.

Vấn đề được ghi nhận:

- `submitFuturesOrder()` đang gửi các lệnh qua `POST /fapi/v1/order`.
- `submitProtectiveStopMarketOrder()` sử dụng cùng luồng gửi lệnh với loại `STOP_MARKET`.
- API Algo Order của Binance USDⓈ-M Futures cần được sử dụng cho các lệnh điều kiện theo tài liệu API hiện hành.
- Take Profit hiện có vẻ chỉ được tính và lưu trong `PositionEntity`, chưa được gửi lên sàn.

Yêu cầu:

**A. Xây dựng Binance Algo Order Adapter hoàn chỉnh**

Kiểm tra tài liệu Binance USDⓈ-M Futures chính thức mới nhất và triển khai đúng:
- Tạo Algo Order.
- Đặt Stop Loss.
- Đặt Take Profit.
- Truy vấn trạng thái Algo Order.
- Hủy Algo Order.
- Tra cứu danh sách Algo Order đang mở.
- Đối chiếu Algo Order với vị thế thực tế.
- Xử lý sự kiện Algo Order qua User Data Stream.
- Xử lý các mã lỗi API.
- Hỗ trợ clientAlgoId để chống gửi lặp.

Không sử dụng endpoint cũ cho các loại lệnh mà Binance đã chuyển sang Algo Service.

Phải kiểm tra đúng:
- Endpoint.
- HTTP method.
- Tham số bắt buộc.
- Tên trường JSON.
- Chữ ký HMAC.
- Timestamp và recvWindow.
- Position Side.
- Reduce Only và Close Position.
- Trigger Price.
- Working Type.
- Price Protection.
- Client Order ID và Algo Order ID.
- Quy tắc tương thích giữa Hedge Mode và One-Way Mode.

Không tự ý ghép các tham số không tương thích với nhau. Không hardcode giả định rằng tất cả tài khoản đều sử dụng cùng một Position Mode.

**B. Quy trình bảo vệ vị thế**

Sau khi lệnh mở vị thế thực sự khớp:

1. Xác định khối lượng thực tế đã khớp.
2. Xác định entry price thực tế.
3. Tính lại SL/TP dựa trên vị thế thực tế và chiến lược đã phê duyệt.
4. Đặt protective orders lên Binance.
5. Xác minh Binance đã chấp nhận các lệnh.
6. Truy vấn lại trạng thái lệnh bảo vệ.
7. Đối chiếu side, symbol, quantity, trigger price và position side.
8. Chỉ đánh dấu vị thế `PROTECTED` khi đã xác nhận đầy đủ.

Phải xử lý tình huống:

- Lệnh mở vị thế thành công nhưng đặt SL thất bại.
- SL thành công nhưng TP thất bại.
- Một trong hai lệnh chỉ được tạo một phần.
- Timeout sau khi gửi lệnh nhưng không nhận được phản hồi.
- Binance nhận lệnh nhưng ứng dụng bị mất kết nối trước khi nhận xác nhận.
- Lệnh bảo vệ bị Binance từ chối do filter.
- Vị thế đã bị đóng trong lúc đang tạo protective orders.
- Có nhiều vị thế cùng symbol trong Hedge Mode.
- Ứng dụng bị kill ngay giữa quá trình bảo vệ.

Nếu không thể xác minh trạng thái bảo vệ, vị thế phải được đánh dấu `PROTECTION_UNKNOWN` hoặc trạng thái tương đương. Không được tuyên bố vị thế đã được bảo vệ.

Thiết kế quy trình xử lý thất bại có kiểm soát, bao gồm cảnh báo khẩn cấp, thử lại có giới hạn và cơ chế giảm thiểu rủi ro. Nếu phải đóng khẩn cấp, chỉ công nhận thành công sau khi xác minh vị thế thực tế đã đóng.

### 3.2. Sửa Panic Close

File cần kiểm tra: `AstraTradingCoordinator.kt`

Đặc biệt kiểm tra `executePanicCloseVerifiedPosition()`.

Các vấn đề cần xác minh:

- Có thể ghi nhận đóng vị thế dù không có API Key.
- Có thể coi lệnh đóng thành công mà không xác minh `FILLED`.
- Không kiểm tra đầy đủ `executedQty`.
- Có thể xóa vị thế khỏi Room trước khi xác minh trạng thái thực tế.
- Có thể đánh dấu các lệnh bảo vệ `CANCELED` trong Database mà không gửi yêu cầu hủy thật lên Binance.

Thiết kế lại Panic Close thành một state machine có tính nhất quán.

Luồng đề xuất:

`PANIC_TRIGGERED`
→ `FETCH_EXCHANGE_POSITION`
→ `CLOSE_ORDER_SUBMITTED`
→ `CLOSE_ORDER_RECONCILIATION`
→ `POSITION_VERIFICATION`
→ `CANCEL_REMAINING_PROTECTIVE_ORDERS`
→ `FINAL_EXCHANGE_RECONCILIATION`
→ `CLOSED_CONFIRMED`

Các yêu cầu:

- Không có API Key hoặc mất quyền truy cập sàn: không được giả lập thành công.
- Không coi HTTP 200 là bằng chứng vị thế đã đóng.
- Phân biệt FILLED, PARTIALLY_FILLED, NEW, CANCELED, EXPIRED, REJECTED và trạng thái chưa xác định.
- Xử lý partial close bằng cách chỉ tiếp tục đối với phần vị thế còn lại.
- Sử dụng quantity thực tế và quy tắc làm tròn của Binance.
- Hỗ trợ Hedge Mode và One-Way Mode.
- Kiểm tra vị thế bằng API đọc trạng thái sàn sau lệnh đóng.
- Hủy các protective orders còn tồn tại trên sàn.
- Xác minh việc hủy lệnh.
- Không xóa dữ liệu vị thế nếu chưa xác nhận đóng.
- Không ghi PnL hoàn tất trước khi có đủ dữ liệu giao dịch thực tế.
- Nếu mất mạng giữa các bước, phải tiếp tục reconciliation thay vì tự suy đoán kết quả.

Panic Close phải an toàn khi được gọi nhiều lần. Không tạo nhiều lệnh đóng trùng lặp do callback, retry hoặc race condition.

Thiết kế cơ chế idempotency và concurrency control phù hợp.

---

## P0 – EXECUTION GATE VÀ RISK ENGINE

### 3.3. Hoàn thiện điều kiện trước khi thực thi giao dịch

File cần kiểm tra:
- `AstraTradingCoordinator.kt`
- Quant Engine.
- Risk Engine.
- Signal Repository.
- AI Review.
- Execution Gate.

Vấn đề được ghi nhận:

`executeApprovedCandidateIfEligible()` có vẻ đang dựa nhiều vào `_latestSizingDecision.approved`, nhưng chưa truy vấn lại đầy đủ trạng thái Signal, expiry, Skeptic và EV.

Yêu cầu thiết kế một Execution Gate duy nhất, không để nhiều đường gọi trực tiếp bypass điều kiện giao dịch.

Các điều kiện bắt buộc:

1. Signal tồn tại và thuộc đúng symbol.
2. Signal chưa hết hạn.
3. Signal đang ở trạng thái hợp lệ.
4. Không bị Skeptic từ chối.
5. Có đủ dữ liệu đầu vào.
6. AI review không ở trạng thái lỗi hoặc chưa hoàn thành theo policy.
7. EV hợp lệ và đạt ngưỡng chiến lược.
8. Position Sizing được phê duyệt.
9. Risk Engine xác nhận đủ điều kiện.
10. Exchange Metadata đã được tải và xác minh.
11. Account State còn hợp lệ.
12. Không có giao dịch xung đột.
13. Không vượt giới hạn vị thế, exposure và drawdown.
14. Giá thị trường chưa vượt quá ngưỡng trượt giá cho phép.
15. API connectivity và đồng bộ thời gian đạt yêu cầu.
16. Trading Mode cho phép thực thi.

Phải kiểm tra lại những điều kiện quan trọng ngay sát thời điểm gửi lệnh, không chỉ kiểm tra ở giai đoạn Scan.

Không cho phép sử dụng `_latestSizingDecision` cũ của một tín hiệu khác.

Phải liên kết Signal ID, Decision ID, Sizing ID, Risk Approval ID và OrderIntent ID để tránh nhầm dữ liệu giữa các lần phân tích.

### 3.4. Hiệu chỉnh Expected Value và xác suất thắng

Vấn đề được ghi nhận:

- `calibratedWinProbability` đang được truyền `null`.
- EV chưa được hiệu chuẩn từ kết quả thực tế.
- Kết quả AI có thể chưa được tích hợp đúng vào quá trình đánh giá xác suất.

Hãy kiểm tra toàn bộ công thức hiện tại.

Yêu cầu:

- Phân biệt predicted probability, calibrated probability và realized win rate.
- Không lấy confidence của LLM làm xác suất thắng.
- Không tự gán xác suất thắng mặc định khi thiếu dữ liệu.
- Không coi một số lượng ít giao dịch là mẫu đủ tin cậy.
- Xây dựng cơ chế thu thập kết quả giao dịch và hiệu chuẩn theo thời gian.
- Có thể xem xét Platt Scaling, Isotonic Regression hoặc phương pháp phù hợp với lượng dữ liệu thực tế.
- Có kiểm tra calibration error, Brier Score và reliability.
- Phân nhóm theo chiến lược, timeframe và regime khi đủ dữ liệu.
- Tách dữ liệu huấn luyện và kiểm định để tránh data leakage.
- Chặn giao dịch khi xác suất chưa đủ độ tin cậy theo policy.
- Không tự thay đổi các ngưỡng chiến lược đang được người dùng cấu hình.

Phân tích toàn bộ EV Formula, bao gồm fee, slippage, funding fee nếu có, spread và các chi phí giao dịch thực tế.

Đảm bảo EV phản ánh lợi nhuận kỳ vọng sau chi phí chứ không chỉ dựa trên chênh lệch Entry và TP.

---

## P1 – BACKGROUND RUNTIME VÀ HỆ THỐNG 24/7

### 3.5. Hoàn thiện TradingForegroundService

File cần kiểm tra:
- `TradingForegroundService.kt`
- `AstraViewModel.kt`
- `AstraTradingCoordinator.kt`
- Các WebSocket Manager.
- Các Worker và Repository.

Vấn đề được ghi nhận:

- Foreground Service hiện có thể chỉ tạo notification và giữ WakeLock tối đa 10 phút.
- `connectMarketStream()` chưa thấy được gọi từ luồng chính.
- `connectUserDataStream()` chưa thấy được gọi.
- `startOrKeepAliveListenKey()` chưa được tích hợp đầy đủ.
- Chưa có vòng đời quản lý giao dịch độc lập trong Service.
- Quét thị trường chủ yếu xuất phát từ ViewModel hoặc thao tác của người dùng.

Hãy xây dựng kiến trúc Background Runtime thực sự.

Yêu cầu:

**A. Service Lifecycle**

- Start, Stop, Restart.
- Trạng thái khởi tạo, hoạt động, tạm ngưng, phục hồi và lỗi.
- Không tạo nhiều instance thực thi giao dịch.
- Notification phản ánh trạng thái thực tế.
- Hỗ trợ Android lifecycle và các giới hạn nền phù hợp với phiên bản Android được hỗ trợ.
- Kiểm tra quyền notification, foreground service type và chính sách hệ điều hành.
- Không lạm dụng WakeLock.
- Không giả định Foreground Service đảm bảo tuyệt đối chạy 24/7.

**B. Trading Runtime**

Service phải có khả năng độc lập với UI để quản lý:

- Market Data.
- User Data Stream.
- Scanner.
- Signal Pipeline.
- Risk Monitoring.
- Position Monitoring.
- Protective Order Monitoring.
- Reconciliation.
- AI Job Scheduling.
- Emergency Handling.

ViewModel chỉ chịu trách nhiệm trình bày và giao tiếp với UI, không phải nơi duy nhất duy trì hoạt động trading.

**C. WebSocket Lifecycle**

Xây dựng:

- Kết nối ban đầu.
- Ping/Pong hoặc heartbeat thích hợp.
- Phát hiện mất kết nối.
- Reconnect có exponential backoff và jitter.
- Giới hạn retry.
- Phục hồi subscription.
- Phục hồi User Data Stream.
- Gia hạn Listen Key theo tài liệu API hiện hành nếu sử dụng cơ chế Listen Key.
- Phát hiện dữ liệu bị cũ.
- Phát hiện sequence gap nếu loại stream hỗ trợ.
- Resync dữ liệu từ REST sau khi reconnect.
- Không sử dụng dữ liệu thị trường cũ để mở vị thế.

**D. Khôi phục sau sự cố**

Phải xử lý được:

- Activity bị đóng.
- App bị hệ điều hành kill.
- Process bị crash.
- Điện thoại mất mạng.
- Điện thoại chuyển Wi-Fi sang Mobile Data.
- WebSocket bị ngắt.
- Android Doze.
- Thiếu RAM.
- Thiếu dung lượng.
- Thiết bị reboot.
- Service bị khởi động lại.

Sau khi phục hồi, phải đọc trạng thái từ Binance trước khi tiếp tục giao dịch.

Room Database chỉ là nguồn dữ liệu nội bộ, không được mặc định là nguồn sự thật cuối cùng về trạng thái vị thế trên sàn.

---

## P1 – EXCHANGE METADATA VÀ BINANCE FILTERS

### 3.6. Sửa Market Lot Size và cơ chế fail-closed

File cần kiểm tra: `BinanceAdapter.kt`.

Vấn đề được ghi nhận:

`parseExchangeInfoJson()` có xử lý PRICE_FILTER, LOT_SIZE, MIN_NOTIONAL nhưng chưa xử lý đầy đủ MARKET_LOT_SIZE.

Ngoài ra:

- `runFullReconciliation()` gọi `fetchExchangeMetadata()` nhưng cần kiểm tra kết quả có thực sự được sử dụng hay không.
- `updateLiveChecklist()` có khả năng đánh dấu `exchangeMetadataLoaded = true` ngay cả khi tải dữ liệu thất bại.
- `defaultSymbolFilter()` có thể sử dụng giá trị mặc định khi metadata không có.

Yêu cầu:

- Parse đầy đủ các filter được Binance trả về.
- MARKET_LOT_SIZE phải được xử lý riêng.
- LOT_SIZE không được thay thế MARKET_LOT_SIZE một cách tùy tiện.
- PRICE_FILTER.
- MIN_NOTIONAL hoặc NOTIONAL tùy loại filter được trả về.
- PERCENT_PRICE và các filter khác nếu áp dụng.
- Điều chỉnh precision và quantity theo đúng stepSize.
- Kiểm tra minQty/maxQty.
- Kiểm tra minimum notional.
- Kiểm tra tickSize.
- Xử lý filter không tồn tại hoặc thay đổi.
- Cache metadata có thời hạn và kiểm soát phiên bản.
- Cập nhật khi symbol thay đổi trạng thái.

Nếu không tải được metadata thực, phải khóa mở lệnh LIVE.

Không được đánh dấu checklist thành công khi chỉ có dữ liệu mặc định.

Đảm bảo mọi kiểm tra quantity và price được thực hiện bằng kiểu số chính xác thích hợp, tránh sai lệch do Float/Double.

---

## P1 – AI MULTI-PROVIDER GATEWAY

### 3.7. Sửa nguy cơ dùng nhầm API Key giữa các nhà cung cấp

File cần kiểm tra: `AiIntelligenceGateway.kt`, `SecretVault.kt`.

Vấn đề được ghi nhận:

Có logic fallback:

`provider.secretSlot ?: SLOT_AI_PRIMARY_KEY`

Cách làm này có nguy cơ gửi API Key của một nhà cung cấp đến endpoint của nhà cung cấp khác.

Yêu cầu:

- Provider nào chỉ sử dụng API Key của provider đó.
- Không fallback chéo API Key giữa các hãng.
- Mỗi provider có credential slot riêng.
- Có quản lý nhiều key trong cùng provider nếu cần.
- Có kiểm tra key hợp lệ trước khi gửi request.
- Không ghi API Key vào log, exception hoặc analytics.
- Không đưa credential vào prompt.
- Không đưa credential vào dữ liệu truyền giữa các agent.
- Không hiển thị key đầy đủ trong UI.
- Không tự động đổi baseUrl trong khi giữ nguyên credential.
- Kiểm soát redirect để tránh làm lộ Authorization Header.
- Xử lý endpoint tùy chỉnh một cách an toàn.

Khi thiếu key của provider đang chọn, phải trả lỗi rõ ràng và yêu cầu lựa chọn provider khác.

### 3.8. Hoàn thiện AI Provider và Model Compatibility

Mục tiêu là hỗ trợ đa dạng API và mô hình, bao gồm các API tương thích OpenAI nhưng không giới hạn ở OpenAI.

Phải kiểm tra:

- Provider Registry.
- Model Registry.
- Base URL.
- API Key.
- Authentication Scheme.
- Request Format.
- Response Format.
- Streaming Support.
- Tool Calling.
- JSON Mode.
- Token Usage.
- Context Window.
- Rate Limits.
- Timeout.
- Retry Policy.
- Error Mapping.

Không giả định mọi API tương thích OpenAI đều hỗ trợ đầy đủ cùng một tính năng.

Cần xây dựng Capability Detection và Adapter riêng khi cần.

Fallback phải dựa trên khả năng thực sự của provider và model, không phải chỉ dựa vào việc request thành công.

### 3.9. Hoàn thiện Analyst và Skeptic

Kiểm tra liệu kiến trúc hiện tại có thực sự tạo ra phản biện độc lập hay chỉ sử dụng một model để tạo cả nhận định lẫn Skeptic Result.

Yêu cầu thiết kế:

`Market Scanner`
→ `Data Validation`
→ `Analyst`
→ `Independent Skeptic`
→ `Evidence Comparison`
→ `Strategy Evaluation`
→ `Risk Engine`
→ `Execution Gate`

Yêu cầu:

- Analyst và Skeptic phải có nhiệm vụ và output schema độc lập.
- Skeptic không được chỉ diễn giải lại nội dung Analyst.
- Skeptic phải tìm kiếm bằng chứng phản bác.
- Có thể sử dụng provider/model khác nhau tùy cấu hình.
- AI Timeout không được giữ giao dịch ở trạng thái chờ vô hạn.
- AI lỗi không đồng nghĩa tín hiệu được phê duyệt.
- Kết quả AI phải có timestamp, model, provider, prompt version và dữ liệu nguồn.
- Chống dữ liệu cũ, prompt injection và kết quả không đúng schema.
- Kiểm tra consistency trước khi đưa dữ liệu tới Risk Engine.

AI chỉ cung cấp nhận định. Quyền thực thi cuối cùng thuộc Execution Gate và Risk Engine dạng deterministic.

---

## P1 – SECRET VAULT VÀ APPLICATION SECURITY

### 3.10. Hoàn thiện SecretVault

Kiểm tra nhánh fallback khi Android Keystore không khả dụng.

Vấn đề được ghi nhận:

- Có nhánh lưu khóa AES trong SharedPreferences cùng nơi chứa ciphertext.
- Điều này làm suy yếu khả năng bảo vệ dữ liệu.

Yêu cầu:

- Ưu tiên Android Keystore.
- Không lưu master encryption key dạng plaintext cạnh ciphertext.
- Xây dựng cơ chế key management an toàn.
- Không silently fallback sang cơ chế yếu hơn.
- Có xử lý khóa bị invalidated.
- Có quy trình migration dữ liệu cũ.
- Có quy trình khôi phục khi ứng dụng nâng cấp.
- Xóa credential khi người dùng yêu cầu.
- Không để dữ liệu nhạy cảm xuất hiện trong backup không an toàn.
- Không ghi credential vào Logcat.
- Kiểm tra toàn bộ đường đi của Binance API Key và AI API Key.

Nếu Keystore không khả dụng, phải thông báo và vô hiệu hóa chức năng cần bảo mật thay vì tự chuyển sang cơ chế yếu hơn mà người dùng không biết.

---

# 4. KIỂM TRA TOÀN BỘ HỆ THỐNG NGOÀI CÁC VẤN ĐỀ ĐÃ NÊU

Không giới hạn phạm vi kiểm toán trong danh sách trên.

Phải tự động rà soát và phân tích toàn bộ các nhóm sau:

## 4.1. Kiến trúc và giao tiếp module

- Dependency direction.
- Circular dependency.
- Module coupling.
- Interface boundaries.
- Dependency Injection.
- Singleton lifecycle.
- Coroutine scope.
- Dispatcher usage.
- Cancellation propagation.
- Flow/StateFlow consistency.
- Thread safety.
- Room transaction.
- Database migration.
- Data ownership.
- State synchronization.

Đảm bảo UI không điều khiển trực tiếp các hoạt động giao dịch quan trọng.

## 4.2. Trading State Machine

Kiểm tra toàn bộ trạng thái:

- Signal.
- AI Review.
- Risk Approval.
- Order Intent.
- Exchange Order.
- Position.
- Protective Orders.
- Close Process.
- Reconciliation.

Phải xác định đầy đủ:

- Trạng thái khởi đầu.
- Trạng thái kết thúc.
- Chuyển trạng thái hợp lệ.
- Chuyển trạng thái không hợp lệ.
- Điều kiện chuyển trạng thái.
- Timeout.
- Retry.
- Recovery.
- Duplicate Events.
- Out-of-order Events.
- Concurrent Updates.

Không cho phép hai module tự ý ghi đè trạng thái của nhau mà không có cơ chế đồng bộ.

## 4.3. Database và tính nhất quán dữ liệu

Kiểm tra:

- Primary Key.
- Foreign Key.
- Unique Constraint.
- Transaction Boundary.
- Migration.
- Duplicate Records.
- Orphan Records.
- Data Corruption.
- State Recovery.
- Event Persistence.

Đảm bảo dữ liệu tài khoản, lệnh và vị thế không bị mất hoặc ghi nhận sai khi ứng dụng crash giữa giao dịch.

## 4.4. Quantitative Engine

Kiểm tra tính đúng đắn của toàn bộ thuật toán hiện tại:

- Indicator Calculation.
- EMA/SMA.
- RSI.
- ATR.
- MACD.
- Bollinger Bands.
- Volume Analysis.
- Breakout Detection.
- Momentum.
- Volume Spike.
- Order Book Analysis.
- OBI.
- CVD.
- Microprice.
- Volatility.
- Market Regime Detection.
- Position Sizing.
- Expected Value.
- Risk/Reward.
- Stop Loss.
- Take Profit.

Không chỉ kiểm tra code có chạy hay không. Phải kiểm tra:

- Công thức toán học.
- Đơn vị dữ liệu.
- Timeframe.
- Warm-up Period.
- Missing Candles.
- Duplicate Data.
- Outlier.
- NaN/Infinity.
- Look-ahead Bias.
- Data Leakage.
- Historical Data Consistency.
- Precision.

Nếu có thuật toán chưa thực sự được sử dụng trong pipeline, phải chỉ rõ và không tuyên bố rằng nó đang vận hành.

## 4.5. Risk Engine

Rà soát:

- Per-trade Risk.
- Maximum Exposure.
- Maximum Position Count.
- Daily Loss Limit.
- Drawdown Limit.
- Leverage.
- Margin.
- Liquidation Distance.
- Correlated Positions.
- Funding Fee.
- Slippage.
- Spread.
- Commission.
- Consecutive Loss.
- Cooldown.
- Emergency Stop.
- Account Equity.
- Available Balance.

Các phép tính phải dựa trên dữ liệu tài khoản và vị thế thực tế.

Phải có một cơ chế Global Capital Mutex hoặc cơ chế tương đương để tránh nhiều module đồng thời sử dụng cùng nguồn vốn vượt giới hạn.

## 4.6. Binance API và Connectivity

Kiểm tra toàn bộ:

- REST API.
- WebSocket.
- WebSocket API nếu có.
- User Data Stream.
- Signature.
- Timestamp.
- recvWindow.
- Clock Drift.
- Rate Limit.
- Request Weight.
- HTTP 418.
- HTTP 429.
- HTTP 403.
- Retry.
- Backoff.
- Idempotency.
- Network Timeout.
- Partial Response.

Không retry vô điều kiện các request giao dịch có khả năng đã được Binance nhận.

Phải phân biệt rõ:

- Request chưa được gửi.
- Request đã gửi nhưng chưa biết kết quả.
- Request bị từ chối.
- Request đã được chấp nhận.
- Lệnh đã khớp.
- Lệnh chỉ khớp một phần.

## 4.7. Hiệu năng và độ ổn định Android

Kiểm tra:

- RAM Consumption.
- CPU Consumption.
- Thread Usage.
- Coroutine Leaks.
- WebSocket Leaks.
- Database I/O.
- Main Thread Blocking.
- Memory Pressure.
- Excessive Recomposition.
- Battery Usage.
- Background Restrictions.
- ANR.
- Crash Recovery.

Đặc biệt chú ý khi chạy đồng thời:

- Scanner.
- 30 hoặc nhiều hơn các market streams theo cấu hình.
- AI Local.
- AI Cloud.
- Risk Engine.
- Database.
- Trading Runtime.

Không tạo thêm thread, polling hoặc coroutine vô hạn nếu không cần thiết.

## 4.8. UI và khả năng quan sát

Đảm bảo UI phản ánh đúng trạng thái thực tế.

Không được:

- Hiển thị Connected khi WebSocket chưa kết nối.
- Hiển thị Protected khi Binance chưa xác nhận SL/TP.
- Hiển thị Closed khi vị thế chưa được xác minh.
- Hiển thị AI Approved khi AI trả lỗi.
- Hiển thị LIVE Ready khi checklist còn thiếu điều kiện.

Cần có log sự kiện có cấu trúc, correlation ID và khả năng truy vết toàn bộ một giao dịch từ Scanner tới Binance.

---

# 5. KIẾN TRÚC MỤC TIÊU SAU KHI SỬA

Hãy đối chiếu và hoàn thiện kiến trúc theo luồng logic sau, nhưng không máy móc tạo thêm module nếu module hiện tại đã thực hiện đúng trách nhiệm.

## 5.1. Market Data Pipeline

`Binance Market Data`
→ `WebSocket Manager`
→ `Data Validation`
→ `Market Data Repository`
→ `Quant Engine`
→ `Market Scanner`

## 5.2. Decision Pipeline

`Market Scanner`
→ `Signal Generator`
→ `Signal Validation`
→ `AI Analyst`
→ `Independent Skeptic`
→ `Evidence Evaluator`
→ `Strategy Engine`
→ `Expected Value`
→ `Risk Engine`
→ `Execution Gate`

## 5.3. Execution Pipeline

`Approved Trade Candidate`
→ `Capital Mutex`
→ `Final Risk Validation`
→ `Order Intent Persistence`
→ `Binance Execution Adapter`
→ `Order Status Verification`
→ `Position Detection`
→ `Protective Algo Orders`
→ `Protection Verification`
→ `Position Monitoring`

## 5.4. Lifecycle và Recovery Pipeline

`Foreground Service`
→ `Trading Runtime`
→ `Connection Manager`
→ `Market Stream`
→ `User Data Stream`
→ `Scanner`
→ `Position Monitor`
→ `Reconciliation Scheduler`

Khi khởi động lại:

`Application Recovery`
→ `Load Local State`
→ `Fetch Exchange Account State`
→ `Fetch Open Orders`
→ `Fetch Algo Orders`
→ `Fetch Positions`
→ `Reconciliation`
→ `Resolve State Differences`
→ `Resume Eligible Operations`

Không tự động mở vị thế mới trong quá trình recovery.

---

# 6. KIỂM THỬ VÀ TIÊU CHUẨN NGHIỆM THU

Đây là phần bắt buộc. Không được chỉ viết code rồi kết luận hoàn thành.

## 6.1. Binance Execution Tests

Dùng mock server hoặc test environment phù hợp để kiểm tra:

1. Mở vị thế thành công.
2. Mở vị thế thất bại.
3. Partial Fill.
4. Timeout sau khi gửi order.
5. Duplicate Order Request.
6. Đặt SL thành công.
7. Đặt SL thất bại.
8. Đặt TP thành công.
9. Đặt TP thất bại.
10. SL thành công nhưng TP thất bại.
11. SL/TP bị hủy từ bên ngoài.
12. Trigger SL.
13. Trigger TP.
14. Position đã đóng trước khi đặt protection.
15. Binance trả lỗi filter.
16. Binance trả HTTP 429.
17. Binance trả HTTP 418.
18. Binance trả HTTP 403.
19. Mất mạng khi gửi lệnh.
20. App crash trong quá trình giao dịch.

## 6.2. Panic Close Tests

Kiểm tra:

- Không có API Key.
- API Key sai quyền.
- Binance không kết nối.
- Lệnh đóng bị từ chối.
- Lệnh đóng khớp một phần.
- Lệnh đóng chưa khớp.
- Timeout sau khi gửi.
- Vị thế đã đóng trước đó.
- Có nhiều vị thế cùng symbol.
- Hedge Mode.
- One-Way Mode.
- Hủy protective orders thất bại.
- Reconciliation thất bại.

Đặc biệt phải có test đảm bảo:

**Không thể ghi nhận CLOSED_CONFIRMED khi vị thế trên Binance chưa được xác minh đã đóng.**

## 6.3. Execution Gate Tests

Kiểm tra tất cả điều kiện:

- Signal hợp lệ.
- Signal hết hạn.
- Signal bị từ chối.
- Skeptic từ chối.
- EV không đạt.
- EV thiếu dữ liệu.
- Risk không duyệt.
- Metadata không tải được.
- Tài khoản thiếu vốn.
- Có giao dịch xung đột.
- Sizing Decision cũ.
- Signal ID không khớp.
- Giá đã biến động quá ngưỡng.

Mọi trường hợp thiếu điều kiện phải chặn giao dịch.

## 6.4. Background Runtime Tests

Kiểm tra:

- Service start/stop.
- UI đóng nhưng service tiếp tục.
- App process restart.
- WebSocket disconnect/reconnect.
- Internet unavailable.
- Network switch.
- Device reboot.
- Android Doze.
- Database recovery.
- Duplicate Service Start.
- Concurrent Runtime.

## 6.5. AI Gateway Tests

Kiểm tra:

- Mỗi provider sử dụng đúng API Key.
- Key của provider A không được gửi đến provider B.
- Model không tồn tại.
- API Key sai.
- Rate Limit.
- Quota Exhausted.
- Context Overflow.
- Timeout.
- Malformed JSON.
- Unsupported Tool Calling.
- Provider Down.
- Fallback sang provider khác.
- Fallback hết provider.

Không để lỗi của AI dẫn đến giao dịch được phê duyệt mặc định.

## 6.6. Kiểm thử tích hợp

Tạo các kịch bản mô phỏng toàn pipeline:

`Market Data → Scanner → Analyst → Skeptic → Risk → Execution → Binance Mock → Protection → Reconciliation`

Phải kiểm chứng dữ liệu giữa các module đồng nhất và không bị mất context.

---

# 7. QUY TẮC TRIỂN KHAI VÀ QUẢN LÝ THAY ĐỔI

### 7.1. Không viết lại toàn bộ ứng dụng một cách tùy tiện

- Giữ nguyên kiến trúc tốt.
- Tận dụng các class và interface hiện có.
- Refactor có kiểm soát.
- Chỉ tạo module mới khi thực sự cần thiết.
- Không nhân bản cùng một chức năng ở nhiều nơi.
- Không tạo nhiều nguồn sự thật cho cùng một trạng thái.

### 7.2. Không để lại code giả

Không chấp nhận:

- TODO thay cho chức năng bắt buộc.
- Hàm trả về `true` giả.
- Hardcoded success.
- Fake order status.
- Mock data trong LIVE.
- Catch exception rồi bỏ qua.
- Fallback yếu về bảo mật.
- Tự động bỏ qua điều kiện Risk.
- Chuyển lỗi thành trạng thái COMPLETED.

Mỗi chức năng phải có logic xử lý thật, có đầu vào, đầu ra và điều kiện thất bại rõ ràng.

### 7.3. Tương thích và migration

- Bảo đảm tương thích dữ liệu Room hiện tại.
- Không xóa dữ liệu lịch sử.
- Có migration rõ ràng.
- Không phá vỡ cấu hình người dùng.
- Không làm mất API Key đã lưu.
- Không tự ý reset cài đặt.
- Không làm mất các chiến lược giao dịch hiện có.

### 7.4. Quản lý cấu hình

Tách rõ:

- DEMO.
- BACKTEST.
- TESTNET.
- LIVE.

Mỗi môi trường phải có cấu hình và endpoint riêng.

LIVE phải có nhiều lớp xác nhận. Không cho phép tự động chuyển từ DEMO hoặc TESTNET sang LIVE.

### 7.5. An toàn khi kiểm thử

Không sử dụng API Key giao dịch thật để kiểm thử.

Không gửi lệnh Futures thật.

Không được tự ý truy cập, đọc hoặc gửi thông tin tài khoản người dùng tới bên thứ ba.

Sử dụng Mock Exchange, Mock Server hoặc Binance Testnet khi phù hợp. Phân biệt rõ kết quả mô phỏng với kết quả từ sàn thật.

---

# 8. QUY TRÌNH THỰC HIỆN

Triển khai theo các giai đoạn dưới đây.

## Giai đoạn 1 – Source Code Reconnaissance

- Đọc toàn bộ dự án.
- Lập sơ đồ kiến trúc thực tế.
- Truy vết các luồng dữ liệu.
- Tạo danh sách vấn đề được xác minh.
- Phát hiện lỗi bổ sung.
- Phân loại Critical, High, Medium, Low.
- Xác định dependency giữa các lỗi.

Đầu ra: `AUDIT_REPORT.md`

## Giai đoạn 2 – Root Cause & Solution Design

Với từng vấn đề:

- Root Cause.
- Evidence.
- Impact.
- Proposed Solution.
- Alternative Solutions.
- Trade-offs.
- Dependencies.
- Files to Modify.
- Test Plan.
- Acceptance Criteria.

Đầu ra: `REMEDIATION_PLAN.md`

Không bắt đầu sửa một cách thiếu kiểm soát khi chưa hiểu ảnh hưởng liên module.

## Giai đoạn 3 – Implementation

Thực hiện sửa từng nhóm ưu tiên:

1. Binance Algo Order và Protection.
2. Panic Close và Reconciliation.
3. Execution Gate và Risk Engine.
4. Background Runtime và WebSocket.
5. Exchange Metadata.
6. AI Gateway và Agent Pipeline.
7. SecretVault và Security.
8. Các lỗi kiến trúc, Quant, Database, UI và hiệu năng phát hiện thêm.

Sau mỗi nhóm:

- Build.
- Run tests.
- Phân tích lỗi.
- Sửa lỗi hồi quy.
- Cập nhật báo cáo.

## Giai đoạn 4 – Full Integration

- Kiểm tra toàn bộ module.
- Kiểm tra state transitions.
- Kiểm tra concurrency.
- Kiểm tra database.
- Kiểm tra API.
- Kiểm tra background lifecycle.
- Kiểm tra recovery.
- Kiểm tra performance.
- Kiểm tra security.

## Giai đoạn 5 – Final Verification

Chỉ kết luận một chức năng hoàn thành khi có bằng chứng:

- Source code đã sửa.
- Build thành công.
- Test tương ứng PASS.
- Có xử lý lỗi và recovery.
- Không có lỗi hồi quy đã biết.
- Các interface liên quan hoạt động đúng.

---

# 9. BÁO CÁO KẾT QUẢ BẮT BUỘC

Sau khi hoàn thành, tạo các tài liệu sau:

### `AUDIT_REPORT.md`

Toàn bộ phát hiện sau khi kiểm toán, phân loại mức độ nghiêm trọng, bằng chứng và nguyên nhân.

### `REMEDIATION_PLAN.md`

Giải pháp chi tiết cho từng vấn đề, kiến trúc mới, dependency và thứ tự triển khai.

### `IMPLEMENTATION_REPORT.md`

Những gì đã sửa thực tế:

| Vấn đề | File | Thay đổi | Trạng thái | Bằng chứng |
|---|---|---|---|---|

Phải phân biệt rõ đã sửa, đang sửa, chưa sửa và không thể sửa do thiếu điều kiện.

### `TEST_REPORT.md`

Ghi rõ:

- Build Result.
- Unit Test.
- Integration Test.
- Regression Test.
- Security Test.
- Binance Mock Test.
- Background Runtime Test.
- Known Issues.

Không được báo PASS nếu chưa chạy.

### `ARCHITECTURE.md`

Mô tả kiến trúc sau khi sửa, bao gồm:

- Module Responsibilities.
- Data Flow.
- Trading Pipeline.
- State Machine.
- Error Handling.
- Recovery.
- Security.
- Background Lifecycle.

### `CHANGELOG.md`

Ghi lại các thay đổi có ảnh hưởng đến hành vi, API, database và cấu hình.

---

# 10. TIÊU CHUẨN HOÀN THÀNH CUỐI CÙNG

Astra Trader chỉ được xem là đạt yêu cầu khi:

1. Có thể phân biệt chính xác trạng thái nội bộ và trạng thái thực tế trên Binance.
2. Không thể mở vị thế LIVE khi các điều kiện bảo vệ chưa đạt.
3. SL và TP thực sự được tạo, xác minh và quản lý đúng trên Binance.
4. Panic Close không thể báo thành công giả.
5. Partial Fill và timeout được xử lý chính xác.
6. Reconciliation có thể phát hiện và sửa sai lệch giữa Database và sàn.
7. Execution Gate không thể bị bypass bởi các module khác.
8. Risk Engine luôn có quyền từ chối giao dịch.
9. WebSocket và Background Runtime có cơ chế phục hồi.
10. AI Gateway không làm lộ hoặc sử dụng nhầm API Key.
11. Analyst và Skeptic có quy trình đánh giá độc lập.
12. Quant và EV sử dụng dữ liệu, công thức và đơn vị chính xác.
13. Database không ghi nhận trạng thái giao dịch giả.
14. UI phản ánh đúng trạng thái hệ thống.
15. Không có lỗi Critical hoặc High chưa được xử lý.
16. Build và các nhóm kiểm thử bắt buộc đều PASS.
17. Có tài liệu kỹ thuật và báo cáo bằng chứng đầy đủ.

Nếu một tiêu chí chưa đạt, phải ghi rõ nguyên nhân và không được tuyên bố hệ thống đã sẵn sàng LIVE.

---

# 11. NGUYÊN TẮC TỰ PHẢN BIỆN

Trong toàn bộ quá trình, bạn phải liên tục tự đặt câu hỏi:

- Giải pháp này có thực sự xử lý nguyên nhân gốc không?
- Có tạo thêm race condition không?
- Có làm thay đổi hành vi của module khác không?
- Có tình huống nào khiến Database và Binance không đồng nhất không?
- Khi mất mạng đúng thời điểm gửi lệnh, hệ thống sẽ làm gì?
- Khi phản hồi đến trễ hoặc sai thứ tự thì sao?
- Khi AI trả dữ liệu sai nhưng JSON vẫn hợp lệ thì sao?
- Khi app bị kill giữa hai bước giao dịch thì sao?
- Có thể phát sinh giao dịch trùng không?
- Có thể đóng nhầm vị thế không?
- Có thể gửi nhầm API Key không?
- Có thể báo thành công trong khi thao tác thực tế thất bại không?
- Có cách nào bypass Risk Engine hoặc Execution Gate không?
- Có giả định nào chưa được xác minh bằng tài liệu hoặc kiểm thử không?

Mỗi khi phát hiện một giải pháp có điểm yếu, phải tự phản biện và điều chỉnh trước khi triển khai.

**Không bảo vệ phương án do chính mình đề xuất. Hãy bảo vệ tính đúng đắn của hệ thống.**

# 12. CHỈ THỊ CUỐI CÙNG

Hãy bắt đầu bằng việc đọc và kiểm toán toàn bộ mã nguồn hiện tại.

Không hỏi lại những thông tin đã có trong dự án. Không chỉ trả lời bằng một bản danh sách lỗi. Không dừng lại sau khi tạo kế hoạch.

Hãy tự xác minh, phân tích, thiết kế, triển khai sửa chữa, kiểm thử và cập nhật tài liệu theo từng giai đoạn.

Trong trường hợp có nhiều giải pháp:

- So sánh ưu nhược điểm.
- Đánh giá độ ổn định.
- Đánh giá độ an toàn.
- Đánh giá hiệu năng và tài nguyên Android.
- Đánh giá khả năng bảo trì.
- Chọn phương án phù hợp nhất với kiến trúc Astra Trader.

Ưu tiên tính chính xác, khả năng phục hồi và an toàn vốn trước tốc độ phát triển tính năng.

**Mục tiêu cuối cùng không phải làm cho Astra Trader trông như một bot giao dịch hoàn chỉnh. Mục tiêu là khiến từng thành phần thực sự làm đúng nhiệm vụ, phối hợp chính xác với nhau và không tạo ra những trạng thái thành công giả.**

Bắt đầu kiểm toán toàn diện ngay bây giờ.
