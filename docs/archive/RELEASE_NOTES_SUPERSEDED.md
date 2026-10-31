# ASTRA TRADER V5.1 — RELEASE NOTES (`RELEASE_NOTES.md`)

**Version:** `5.1.0-hardened`  
**Release Date:** 2026-10-30  
**Default Environment:** `TEST` (`safetyDebugLockActive = true`, `liveExplicitlyArmed = false`)

---

## 1. Highlights of V5.1 Hardened Release

1. **Zero-Trust Execution & Capital Safety**:
   - Enforces a 16-condition Unified Execution Gate (`evaluateUnifiedExecutionGate`) with strict `signalId` linkage, fresh market snapshot verification, calibrated net EV check, and Independent Skeptic `PASS` requirement in `LIVE`.
   - Enforces a 3-axis `ExecutionCapability` matrix at the `BinanceAdapter` boundary so read-only Mainnet diagnostics in `TEST` mode can never transmit new orders.
   - Synchronizes symbol margin type (`ISOLATED`) and leverage (`POST /fapi/v1/marginType`, `POST /fapi/v1/leverage`) on the exchange before order entry.
2. **8-Stage Fail-Closed Panic Close & Hedge Mode Support**:
   - Supports composite `(symbol, positionSide)` primary keys in Room v6 (`MIGRATION_5_6`) for full One-Way (`BOTH`) and Hedge Mode (`LONG` / `SHORT`) isolation.
   - Verifies pre-close exchange position and position mode, submits reduce-only close orders only for `BOT_OWNED` positions, verifies post-close zero position and protective order cancellation, and records all individual partial fills from `/fapi/v1/userTrades`.
3. **Hardware-Backed SecretVault & Strict AI Key Isolation**:
   - Eliminates all compile-time `BuildConfig.GEMINI_API_KEY` fallbacks.
   - Isolates each AI provider's API key in its own AES-256-GCM encrypted `SecretVault` slot with fail-closed SHA-256 integrity verification.

---

## 2. Known Operational Constraints & Safety Locks

- **LIVE Mode Disarmed by Default**: The application ships with `environmentMode = "TEST"`, `safetyDebugLockActive = true`, and `liveExplicitlyArmed = false`. No live orders can be placed during testing or inspection.
- **Empirical Calibration Requirement**: `LIVE` mode requires at least `N >= 30` closed verified trades with historical `predictedScoreAtEntry` and Brier score `<= 0.30` before `calibratedProbability` is emitted.
