package com.example.forecast.domain

/**
 * Explicit fail-closed status codes for the On-Device Time-Series Forecasting Engine (Section 3.3 & 11.2).
 * Never fabricates neutral probabilities (e.g. 0.5/0.3/0.2) when data, features, or models are invalid.
 */
enum class ForecastStatus(val badgeVi: String, val isUsableForAdvisory: Boolean) {
    READY_VALIDATED("ĐÃ XÁC MINH & HIỆU CHUẨN (READY_VALIDATED)", true),
    UNCALIBRATED("CHƯA HIỆU CHUẨN ĐỦ MẪU (UNCALIBRATED)", false),
    INSUFFICIENT_DATA("THIẾU CHUỖI NẾN ĐÓNG (INSUFFICIENT_DATA)", false),
    FEATURE_INVALID("ĐẶC TRƯNG LỖI / NAN / INF (FEATURE_INVALID)", false),
    MODEL_MISSING("THIẾU GÓI MODEL (MODEL_MISSING)", false),
    MODEL_INCOMPATIBLE("MODEL KHÔNG TƯƠNG THÍCH SCHEMA/SHA256 (MODEL_INCOMPATIBLE)", false),
    INFERENCE_ERROR("LỖI SUY LUẬN RUNTIME (INFERENCE_ERROR)", false),
    OUT_OF_DISTRIBUTION("NGOÀI PHÂN PHỐI HUẤN LUYỆN (OUT_OF_DISTRIBUTION)", false),
    STALE("DỰ BÁO ĐÃ HẾT HẠN (STALE)", false)
}

enum class ForecastOperatingMode(val labelVi: String) {
    SHADOW("SHADOW (Chỉ ghi nhận & đối chiếu thực nghiệm, không chặn lệnh)"),
    ADVISORY_TEST_ONLY("ADVISORY (Chỉ lọc giảm rủi ro trong TEST, không tự mở lệnh)")
}
