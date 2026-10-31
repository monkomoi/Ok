# ASTRA TRADER V5.1 — KNOWN LIMITATIONS & ENVIRONMENT CONSTRAINTS (`KNOWN_LIMITATIONS.md`)

Tài liệu này ghi nhận trung thực các giới hạn kỹ thuật của môi trường thực thi hiện tại và phạm vi thiết kế của Astra Trader V5.1 theo đúng yêu cầu tại Mục 5.2 (P1-07) và Mục 6.5 (P2-05) của `ASTRA_TRADER_REMEDIATION_MASTER_TASK.md`.

---

## 1. Giới Hạn Môi Trường Build & Kiểm Thử (Build Container Constraints)

1. **Sử dụng `gradle` thay vì `./gradlew`:**
   - Trong container cloud build hiện tại, tệp thực thi `./gradlew` không được cài đặt; toàn bộ tác vụ biên dịch và chạy kiểm thử đơn vị / Robolectric được thực thi trực tiếp qua hệ thống Gradle cài sẵn (`gradle :app:testDebugUnitTest`, `compile_applet`).
2. **Không có thiết bị vật lý Samsung Galaxy S7 Edge (Exynos 8890) hoặc Android Emulator trực tiếp trong container:**
   - Môi trường chạy tác vụ kiểm thử không gắn kèm thiết bị vật lý hay `adb`. Do đó, các bài kiểm thử giao diện màn hình hẹp 360dp (`qualifiers = "w360dp-h640dp-xxhdpi"`), kiểm thử `SecretVault`, `Room` in-memory Database, và `AstraTradingCoordinator` được xác minh tự động bằng **Robolectric (SDK 36)** trên JVM cục bộ.
3. **Không gọi lệnh giao dịch thật lên Binance Mainnet trong quá trình kiểm thử:**
   - Theo quy tắc an toàn tuyệt đối (Section 1.2), bộ kiểm thử không sử dụng tiền thật hoặc khóa giao dịch thật để đặt/hủy lệnh trên sàn. Các kịch bản khớp lệnh, trượt giá, lỗi mạng `503`, lỗi hủy lệnh `500`, và hạch toán `/fapi/v1/userTrades` được kiểm증 qua `OkHttpClient` interceptor / `MockWebServer` trả về payload JSON chuẩn của Binance USDⓈ-M Futures.

---

## 2. Giới Hạn Kỹ Thuật Android OS & Mạng Di Động

1. **Giới hạn gắn kết Android Network (`OutboundNetworkIdentity`):**
   - Địa chỉ IPv4/IPv6 hiển thị trong công cụ Chẩn đoán IP Outbound là địa chỉ IP nguồn quan sát được tại thời điểm truy vấn dịch vụ kiểm tra HTTPS bên thứ ba trên cùng `android.net.Network` đang hoạt động.
   - Nếu thiết bị sử dụng VPN chia luồng (split-tunneling), proxy hệ thống hoặc CGNAT của nhà mạng di động, máy chủ Binance (`fapi.binance.com`) có thể nhìn thấy một địa chỉ IP cổng ra khác. Ứng dụng luôn hiển thị cảnh báo giới hạn kỹ thuật này và chỉ coi phản hồi thực tế từ Binance (`-2015` kèm `request ip` hoặc `200 OK`) là bằng chứng xác thực cuối cùng.
2. **Quản lý tiến trình nền Android 8.0+ (Android O) & WakeLock có thời hạn:**
   - `TradingForegroundService` sử dụng `PARTIAL_WAKE_LOCK` có thời hạn tối đa 10 phút mỗi chu kỳ để tránh tiêu hao pin vĩnh viễn trên thiết bị 4GB RAM. Nếu hệ điều hành hoặc phần mềm tiết kiệm pin của hãng (OEM Battery Saver) cưỡng bức dừng ứng dụng khi chưa cấp quyền *Ignore Battery Optimizations*, tiến trình nền có thể bị tạm hoãn cho tới khi `BootRecoveryReceiver` hoặc người dùng mở lại ứng dụng và chạy đối soát (`runFullReconciliation`).

---

## 3. Phạm Vi Thiết Kế Chiến Lược V1 (`V1_BASELINE`)

1. **Giới hạn tối đa 1 vị thế mở tại một thời điểm (`INV-09`):**
   - Phiên bản V5.1 (`V1_BASELINE`) chỉ cho phép tối đa 1 vị thế mở trên toàn tài khoản tại một thời điểm để bảo vệ ngân sách rủi ro cho tài khoản vốn nhỏ (~150.000 VNĐ / ~5.89 USDT tham chiếu).
2. **Khóa chế độ LIVE khi chưa đủ mẫu hiệu chuẩn xác suất (`N < 30`):**
   - Trong chế độ `LIVE`, cổng thực thi (`evaluateUnifiedExecutionGate` - Gate 15) yêu cầu bắt buộc mô hình xác suất thắng phải được hiệu chuẩn từ tối thiểu `N >= 30` vòng đời vị thế đã đóng thật và xác minh (`FillPnlStatus.VERIFIED`) kèm `Brier Score <= 0.30`. Khi `N < 30`, bot chỉ cho phép hoạt động ở chế độ `TEST` / `DEMO` hoặc hiển thị điểm xếp hạng (`rankingScore`) để quan sát.
