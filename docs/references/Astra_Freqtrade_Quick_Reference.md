# Freqtrade → Astra Trader: phần đáng học

Nguồn: Freqtrade 2026.10. Đây là bản chắt lọc cho Astra Android/Kotlin, Binance USDⓈ-M Futures, một vị thế, Breakout/Momentum, vốn nhỏ. **Chỉ tham khảo thiết kế, không port Python/CCXT.**

## 6 thứ nên học

1. **Kết nối & clock:** `freqtrade/exchange/exchange.py` – `_init_ccxt()` (413–468), `check_time_offset()` (536–565), `validate_trading_mode_and_margin_mode()` (1001–1033); `exchange/binance.py` – `additional_exchange_init()` (123–160). Học cách tách khởi tạo client, kiểm tra cấu hình Futures/Margin và đo lệch giờ. Astra cần chẩn đoán riêng credential loaded, môi trường Futures, thời gian, quyền đọc và mã lỗi; không log key/secret. CCXT là chi tiết Python của Freqtrade, không cần đưa vào Kotlin.

2. **Số dư/vị thế thật:** `exchange.py` – `get_balances()` (1959–1979), `fetch_positions()` (1980–2005), `fetch_orders()` (2058–2076); `wallets.py` – `update()` (245–265). Học cách lấy trạng thái sàn. Astra tuyệt đối không dùng vốn khai báo/cache làm Equity sàn. Khi khởi động, reconnect, user stream mất hoặc có trạng thái lạ: khóa entry, lấy snapshot mới và đối soát Binance ↔ Room DB ↔ RAM.

3. **Order lifecycle:** `exchange.py` – `create_order()` (1522–1596), `fetch_order()` (1786–1814); `freqtradebot.py` – `execute_entry()` (1066–1264), `update_trade_state()` (2515–2568); `persistence/trade_model.py` – `update_orders()` (324–340). Học chuẩn hóa lệnh, cập nhật trạng thái, phân biệt gửi lệnh với khớp lệnh. Astra phải persist OrderIntent + client ID trước khi gửi; timeout/5XX/unknown thì truy vấn trạng thái trước khi retry; quản lý partial fill, không gửi entry trùng.

4. **Quantity & risk sizing:** `exchange.py` – `amount_to_precision()` / `price_to_precision()` (1122–1141); `wallets.py` – `validate_stake_amount()` (408–458); `binance.py` – `load_leverage_tiers()` (388–398). Học precision, giới hạn sàn, stake validation và leverage tier. Astra dùng Decimal, làm tròn quantity xuống stepSize, kiểm tra minQty/minNotional/filters/margin/fees. Nếu quantity tối thiểu vượt risk budget thì REJECT, không tăng size/leverage để lách.

5. **Protective orders:** `exchange.py` – `create_stoploss()` (1650–1751), `fetch_stoploss_order()` (1815–1839); `freqtradebot.py` – `execute_trade_exit()` (2258–2383). Học rằng stop là order thật phải theo dõi, không phải con số trên UI. Astra phải đặt stop phía sàn sau fill và xác minh đúng symbol/side/quantity/trigger; khi thay stop, giữ stop cũ tới khi stop mới được xác nhận nếu API cho phép; position=0 thì xác minh rồi mới dọn lệnh của bot. Kiểm tra endpoint conditional/algo theo Binance Futures docs hiện hành, không chép endpoint cũ.

6. **PnL & circuit breaker:** `persistence/trade_model.py` – `update_trade()` (910–966), `calculate_profit()` (1157–1207), `recalc_trade_from_orders()` (1267–1347); `plugins/protections/stoploss_guard.py` – `_stoploss_guard()` (44–88); `max_drawdown_protection.py` – `_max_drawdown()` (46–112). Học dựng PnL từ fills, phí/funding và cooldown sau stop-loss/drawdown. Astra giữ policy riêng trong Spec: risk/trade ≤0,5%, daily loss 1,5%, 2 lệnh thua liên tiếp thì dừng đánh giá, drawdown 5%, tối đa 1 vị thế, leverage TIER_MICRO ≤2x. Không copy tham số Freqtrade.

## Bỏ qua

Không lấy nguyên `freqtradebot.py`, CCXT/asyncio, FreqAI/RL/Hyperopt, DCA/grid, multi-position, multi-exchange, default strategy/stake/leverage. Không coi backtest là bằng chứng có lợi nhuận. Freqtrade là bot Python; Astra là app Android nên chỉ lấy nguyên tắc, thiết kế lại bằng Kotlin theo Spec v5.1.

## Prompt cho AI Studio

Đối chiếu 6 nhóm trên với mã nguồn Astra và Master Spec v5.1. Chỉ audit, chưa sửa code. Xuất bảng: module | IMPLEMENTED/PARTIAL/NOT_IMPLEMENTED | bằng chứng file/class/function | lỗi/rủi ro | thay đổi tối thiểu | test cần có. Đặc biệt kiểm tra Binance auth hiện tại, nguồn Equity/availableBalance, clock, Futures mode, unknown order, partial fill, stop xác minh và reconciliation. Không suy tính năng từ UI; không bịa endpoint; không log secret; không cho AI bypass RiskEngine/ExecutionCoordinator. Không tìm thấy bằng chứng thì ghi NOT_FOUND. Giữ LIVE khóa cho tới khi P0 tests và đối soát đạt.
