# ASTRA TRADER V5.1 — MASTER REMEDIATION PLAN (`REMEDIATION_PLAN.md`)

**Tài liệu tham chiếu:** `ASTRA_TRADER_REMEDIATION_MASTER_TASK.md`
**Chiến lược triển khai:** Fail-Closed · Zero-Fabrication · Idempotent Order Execution · Atomic Room Persistence

---

## 1. Lộ Trình Thực Thi Theo Giai Đoạn (Phases P0 -> P1 -> P2)

### Giai Đoạn P0 — An Toàn Lệnh, Vị Thế, Hạch Toán & Phản Biện AI Độc Lập
1. **P0-01 (`AstraTradingCoordinator.executePanicCloseStateMachine` - Stage 2):**
   - Tách kết quả `fetchAccountAndPositionsSnapshot(mode)` thành 3 trạng thái: `FETCH_FAILED`, `VERIFIED_ZERO`, `VERIFIED_NON_ZERO`.
   - Loại bỏ hoàn toàn nhánh fallback sang `localSignedQty`.
   - Kiểm tra xung đột `positionSide` hoặc đa vị thế Hedge Mode trước khi đóng.
   - Bổ sung kiểm tra `queryOrderByClientId` trước khi gửi lệnh nếu `closeClientId` đã tồn tại trong Room.
2. **P0-02 (`AstraTradingCoordinator.executePanicCloseStateMachine` - Stage 5 & Stage 8):**
   - Bắt buộc `postPositionCheck.isSuccess && postPositionCheck.data != null` và `remAmt == BigDecimal.ZERO` mới được đi tiếp sang Stage 6–8.
   - Nếu truy vấn xác minh sau đóng thất bại, giữ nguyên `PositionEntity(state = "PANIC_CLOSE_FAILED")` và chuyển về `BotState.RECONCILIATION_REQUIRED`.
3. **P0-03 (`BinanceAdapter.cancelAndVerifyOrder` & `AstraTradingCoordinator`):**
   - Xây dựng hàm `cancelAndVerifyOrder(mode, symbol, clientOrderId, isAlgoOrder)` xác minh trạng thái `CANCELED`/`EXPIRED`/`FILLED` hoặc truy vấn lại khi gặp `-2011`.
   - Áp dụng tại `moveStopToNetBreakevenIfEligible`, `executePanicCloseStateMachine` (Stage 6 & 7), và `runFullReconciliation`.
4. **P0-04 (`BinanceAdapter.fetchUserTradesForOrder` & `FillEntity`):**
   - Bổ sung truy vấn `GET /fapi/v1/userTrades` theo `symbol` và `orderId` để lấy giá khớp bình quân gia quyền, tổng khối lượng, `realizedPnl` và `commission` thực tế (`FillPnlStatus.VERIFIED`).
   - Không bao giờ dùng `markPrice` thay cho giá khớp hoặc tự gán phí `0.0005` làm dữ liệu đã xác minh (`ACCOUNTING_PENDING` / `PNL_UNVERIFIED`).
5. **P0-05 (`AstraTradingCoordinator.requestAiCandidateReview`):**
   - Đặt mặc định `var independentSkepticPassed = false`.
   - Chỉ cho phép `independentSkepticPassed = true` khi có một provider Skeptic độc lập (khác `providerId`, khác `secretSlot`, có khóa riêng trong `SecretVault`) chạy thành công và trả về `SkepticVerdict.PASS`.
6. **P0-06 (`AstraTradingCoordinator.runFullReconciliation`):**
   - Kiểm tra cả `ASTRA_SL_` và `ASTRA_TP_` trên `openOrders + openAlgoOrders` và cờ `openOrdersVerified && openAlgoOrdersVerified`.
   - Phân tách rõ 5 trạng thái vị thế: `OPEN_PROTECTED`, `PROTECTION_DEGRADED`, `PROTECTION_FAILURE`, `PROTECTION_UNKNOWN`, `ORPHAN_UNPROTECTED`.
7. **P0-07 (`AstraTradingCoordinator.executeApprovedCandidateIfEligible`):**
   - Thực hiện `queryOrderByClientId` / `queryAlgoOrderByClientId` khi gặp `UNKNOWN_EXECUTION_STATUS`.
   - Ghi nhận nguyên tử `PositionEntity` và danh sách `OrderRecordEntity` thông qua `@Transaction persistPositionAndOrdersAtomically`.

### Giai Đoạn P1 — Runtime 24/7, Scanner Đa Symbol, Hiệu Chuẩn Xác Suất & Telegram Polling
1. **P1-01 & P1-05 (`TradingForegroundService.kt`, `BinanceAdapter.kt`, `AstraViewModel.kt`):**
   - Cập nhật `TradingForegroundService.recordStreamMessageReceived` mỗi khi WebSocket nhận gói tin.
   - Chỉ cập nhật `lastListenKeyKeepAliveUtcMs` khi `onKeepAliveVerified` thực thi và trả về `true`.
   - Ghi nhận ngoại lệ runtime vào `SystemEventEntity` thay vì bỏ trống khối `catch`.
2. **P1-02 (`AstraTradingCoordinator.runMultiSymbolCandidateScanner`):**
   - Chặn hoàn toàn dữ liệu fallback trong chế độ `LIVE`.
   - Bổ sung `scannerSnapshotCache` (TTL 60 giây) và suy diễn `candidateDirection` từ các tín hiệu định lượng thực tế (`breakoutUp`, `breakoutDown`, `EMA20/EMA50`, `RVOL`).
3. **P1-03 (`AstraTradingCoordinator.scanAndEvaluateSymbol`):**
   - Lưu trữ `predictedScoreAtEntry` và `calibratedProbAtEntry` xuyên suốt vòng đời `SignalEntity -> OrderIntentEntity -> PositionEntity -> FillEntity`.
   - Hiệu chuẩn xác suất trên tập `getVerifiedFillsOnce()` gom theo `positionLifecycleId` và loại bỏ mọi lệnh thiếu `predictedScoreAtEntry`.
4. **P1-04 (`TelegramBotController.kt`):**
   - Bổ sung `pollUpdatesOnce` (`GET /bot<token>/getUpdates`), `parseAndProcessGetUpdatesJson`, chặn HTTP redirect (`followRedirects(false)`), chống replay `update_id`, và kiểm tra cú pháp lệnh chặt chẽ.

### Giai Đoạn P2 — Tối Ưu Hóa Room Schema v5, Kiểm Thử & Đồng Bộ Hóa Tài Liệu
1. **P2-04 (`Entities.kt`, `AstraDao.kt`, `AstraDatabase.kt`):**
   - Bổ sung `@Index` cho 13 bảng Room và nâng cấp schema lên version `5` với `MIGRATION_4_5`.
   - Bổ sung `@Transaction` cho các thao tác cập nhật đa bảng.
2. **P2-02, P2-03 & P1-06 (`ExampleUnitTest.kt`, `ExampleRobolectricTest.kt`):**
   - Mở rộng bộ kiểm thử tự động bao phủ toàn bộ các kịch bản lỗi mạng, vị thế bằng 0, hủy lệnh thất bại, hạch toán `userTrades`, AI Skeptic fail-closed, đối soát thiếu TP, hiệu chuẩn lịch sử và Telegram `getUpdates`.
