# ASTRA TRADER — TECHNICAL REFERENCE NOTES (`docs/references/FREQTRADE_AND_HUMMINGBOT_REFERENCE.md`)

## 1. Nguồn Tham khảo & Chính sách Bản quyền (License Hygiene)

Tài liệu này tóm tắt các nguyên lý thiết kế sàn giao dịch và quản trị trạng thái lệnh từ hai dự án mã nguồn mở được người dùng cung cấp làm tài liệu tham khảo ban đầu:
1. **Freqtrade (`freqtrade-2026.10`, License: GPLv3)**:
   - **Quy tắc tuân thủ:** Không sao chép bất kỳ mã nguồn Python nào của Freqtrade vào Astra Trader. Chỉ tham khảo quy ước xử lý `Binance USDM Futures` API (`fapi/v1`, `fapi/v1/algoOrder`, `reduceOnly`, `closePosition`, `leverage_tiers`).
2. **Hummingbot (`hummingbot-2.17.0`, License: Apache-2.0)**:
   - **Quy tắc tuân thủ:** Không sao chép mã nguồn của Hummingbot. Chỉ tham khảo mô hình máy trạng thái lệnh (`OrderIntentStatus`), đồng bộ đồng hồ (`serverTime` offset), và xử lý `User Data Stream` (`listenKey` keepalive mỗi 30 phút).

Các file nén gốc (`freqtrade-2026.10_073b7d8b.zip` — `48 MB`, `hummingbot-2.17.0_e50d20be.zip` — `3.9 MB`, `Freqtrade_01_Binance_Exchange_Reference_7b65302c.zip` — `66 KB`) đã được gỡ bỏ khỏi gói source chính để không làm phình kho mã nguồn và tuyệt đối không đóng gói vào APK.

---

## 2. Các Quy tắc Kỹ thuật Đã Áp dụng Native bằng Kotlin trong Astra Trader

1. **Binance USDM Futures Precision & Filters (`BinanceAdapter.kt` & `QuantMathEngine.kt`)**:
   - Mọi giá đặt lệnh (`price`, `stopPrice`, `triggerPrice`) phải làm tròn theo `tickSize` (`PRICE_FILTER`).
   - Mọi khối lượng (`quantity`) phải làm tròn xuống (`RoundingMode.DOWN`) theo `stepSize` (`LOT_SIZE`) và kiểm tra `minQty` + `MIN_NOTIONAL` (`notional >= minNotional`, mặc định `>= 5.0 USDT`, `100.0 USDT` cho `BTCUSDT`, `20.0 USDT` cho `ETHUSDT`).
2. **Conditional / Algo Orders (`STOP_MARKET`, `TAKE_PROFIT_MARKET`)**:
   - Hỗ trợ cả hai endpoint `/fapi/v1/algoOrder` (`algoType=CONDITIONAL`, `triggerPrice`, `workingType=MARK_PRICE`) và `/fapi/v1/order` (`stopPrice`, `closePosition=true` / `reduceOnly=true`).
   - Khi thực hiện **Panic Close** hoặc đóng vị thế, phải hủy cả lệnh thường (`DELETE /fapi/v1/allOpenOrders`) lẫn lệnh điều kiện (`DELETE /fapi/v1/algoOpenOrders`) và xác minh lại danh sách lệnh mở = 0.
3. **Position Mode (`One-Way` vs `Hedge Mode`)**:
   - Đọc `/fapi/v1/positionSide/dual` (`dualSidePosition`).
   - Trong `One-Way Mode` (`dualSidePosition = false`): `positionSide = BOTH`, lệnh đóng dùng `reduceOnly = true`.
   - Trong `Hedge Mode` (`dualSidePosition = true`): `positionSide = LONG` hoặc `SHORT`, không gửi `reduceOnly=true` khi dùng `closePosition=true`.
4. **Rate Limit, Backoff & Clock Drift (`BinanceAdapter.kt`)**:
   - Tự động đồng bộ độ lệch thời gian (`serverTimeOffsetMs`) qua `/fapi/v1/time` và tự động đồng bộ lại nếu gặp mã lỗi `-1021` (`Timestamp for this request is outside of the recvWindow`).
   - Xử lý HTTP `429` (Rate Limit) và `418` (IP Auto-Ban) bằng exponential backoff + tôn trọng header `Retry-After`.
