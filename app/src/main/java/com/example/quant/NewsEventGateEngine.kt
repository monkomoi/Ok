package com.example.quant

import com.example.domain.CrowdAttentionProxySnapshot
import com.example.domain.NewsEventCategory
import com.example.domain.NewsEventItem
import com.example.domain.NewsVerificationStatus
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.round

/**
 * MOD-10 & Mục 10 · News, Event Gate, Deduplication, Anti-Prompt-Injection & Crowd Attention Proxy.
 *
 * Bảo chứng kỹ thuật V5.1:
 * 1. Deduplication & Canonicalization: Không đếm 50 bản repost của cùng 1 tin thành 50 bằng chứng độc lập.
 * 2. Tách biệt tuyệt đối RUMOR vs REPORTED vs CONFIRMED: Tin đồn không bao giờ tự thăng cấp thành xác nhận.
 * 3. Tách biệt Author Sentiment (tâm lý tác giả) và Event Impact (tác động thực lên biến động/giá).
 * 4. Chống Prompt Injection: Tin tức/xã hội chỉ được xử lý như dữ liệu không đáng tin cậy (untrusted data),
 *    không bao giờ được phép ghi đè quy tắc rủi ro hoặc ép bot mở vị thế.
 * 5. Crowd Attention Proxy bắt buộc gắn nhãn `estimated = true` (Phụ lục B.9).
 */
object NewsEventGateEngine {

    private val PROMPT_INJECTION_PATTERNS = listOf(
        Regex("ignore\\s+(all\\s+)?previous\\s+instructions", RegexOption.IGNORE_CASE),
        Regex("system\\s+override", RegexOption.IGNORE_CASE),
        Regex("bypass\\s+risk", RegexOption.IGNORE_CASE),
        Regex("force\\s+(buy|long|short|sell|live)", RegexOption.IGNORE_CASE),
        Regex("set\\s+leverage\\s+to", RegexOption.IGNORE_CASE),
        Regex("disable\\s+(stop|kill\\s*switch|safety)", RegexOption.IGNORE_CASE)
    )

    data class RawNewsInput(
        val title: String,
        val sourceName: String,
        val sourceTier: Int, // 1 = Official/Exchange/Regulator, 2 = Major Financial Wire, 3 = Social/Aggregator
        val canonicalUrl: String,
        val publishedAtMs: Long,
        val status: NewsVerificationStatus,
        val category: NewsEventCategory,
        val authorSentimentScore: Double,
        val eventImpactScore: Double
    )

    fun defaultCuratedFeed(nowMs: Long = System.currentTimeMillis()): List<RawNewsInput> {
        return listOf(
            RawNewsInput(
                title = "Binance Futures cập nhật lịch bảo trì định kỳ hệ thống khớp lệnh USDⓈ-M (Không ảnh hưởng API giao dịch)",
                sourceName = "Binance Official Announcement",
                sourceTier = 1,
                canonicalUrl = "https://www.binance.com/en/support/announcement/futures-system-update-v51",
                publishedAtMs = nowMs - 25 * 60_000L,
                status = NewsVerificationStatus.CONFIRMED,
                category = NewsEventCategory.EXCHANGE_INFRA,
                authorSentimentScore = 0.05,
                eventImpactScore = 0.15
            ),
            RawNewsInput(
                title = "Báo cáo dòng vốn ETF Bitcoin giao ngay ghi nhận khối lượng giao dịch ổn định trong phiên Mỹ",
                sourceName = "Financial Wire Tier-2",
                sourceTier = 2,
                canonicalUrl = "https://wire.example.com/crypto/btc-etf-flow-daily",
                publishedAtMs = nowMs - 40 * 60_000L,
                status = NewsVerificationStatus.REPORTED,
                category = NewsEventCategory.MACRO_POLICY,
                authorSentimentScore = 0.28,
                eventImpactScore = 0.30
            ),
            // Bản repost cùng sự kiện ETF từ nguồn tổng hợp Tier-3 để kiểm chứng Deduplication
            RawNewsInput(
                title = "[REPOST] Báo cáo dòng vốn ETF Bitcoin giao ngay ghi nhận khối lượng giao dịch ổn định trong phiên Mỹ!",
                sourceName = "Crypto Aggregator Channel",
                sourceTier = 3,
                canonicalUrl = "https://wire.example.com/crypto/btc-etf-flow-daily?utm_source=repost_1",
                publishedAtMs = nowMs - 35 * 60_000L,
                status = NewsVerificationStatus.REPORTED,
                category = NewsEventCategory.MACRO_POLICY,
                authorSentimentScore = 0.45,
                eventImpactScore = 0.30
            ),
            RawNewsInput(
                title = "Tin đồn mạng xã hội chưa kiểm chứng về thay đổi lãi suất khẩn cấp",
                sourceName = "Unverified Social Feed",
                sourceTier = 3,
                canonicalUrl = "https://social.example.com/post/unverified-macro-rumor",
                publishedAtMs = nowMs - 15 * 60_000L,
                status = NewsVerificationStatus.RUMOR,
                category = NewsEventCategory.MACRO_POLICY,
                authorSentimentScore = -0.75,
                eventImpactScore = -0.60
            )
        )
    }

    /**
     * Chuẩn hóa URL (bỏ query tracking utm_*) và tiêu đề để gom cụm các bản tin repost.
     */
    fun computeCanonicalClusterId(canonicalUrl: String, title: String): String {
        val cleanUrl = canonicalUrl
            .trim()
            .lowercase()
            .substringBefore("?utm_")
            .substringBefore("&utm_")
            .removeSuffix("/")
        val normalizedTitle = title
            .lowercase()
            .replace(Regex("\\[repost]|breaking:|urgent:"), "")
            .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
            .trim()
        val key = if (cleanUrl.isNotBlank() && !cleanUrl.contains("social.example.com")) {
            cleanUrl
        } else {
            normalizedTitle
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return "CLUSTER-" + digest.take(5).joinToString("") { "%02x".format(it) }.uppercase()
    }

    fun detectPromptInjectionAttempt(text: String): Boolean {
        return PROMPT_INJECTION_PATTERNS.any { it.containsMatchIn(text) }
    }

    fun sanitizeHeadline(text: String): String {
        var sanitized = text.trim()
        PROMPT_INJECTION_PATTERNS.forEach { regex ->
            sanitized = sanitized.replace(regex, "[REDACTED_UNTRUSTED_INSTRUCTION]")
        }
        return sanitized.take(280)
    }

    fun sourceTierReliabilityWeight(tier: Int, status: NewsVerificationStatus): Double {
        val base = when (tier) {
            1 -> 0.92
            2 -> 0.72
            else -> 0.30
        }
        val statusMultiplier = when (status) {
            NewsVerificationStatus.CONFIRMED -> 1.0
            NewsVerificationStatus.REPORTED -> 0.75
            NewsVerificationStatus.CORRECTED -> 0.65
            NewsVerificationStatus.RUMOR -> 0.25 // Tin đồn bị giảm trọng số mạnh, không bao giờ tự thành CONFIRMED
            NewsVerificationStatus.DENIED -> 0.0
            NewsVerificationStatus.STALE -> 0.10
        }
        return round2(base * statusMultiplier)
    }

    /**
     * Xử lý danh sách tin thô -> Khử trùng lặp (Deduplicate) -> Gắn trạng thái Event Gate.
     * Quy tắc bất biến Mục 10.2 & 10.6:
     * - 50 bản repost của cùng 1 bài báo chỉ tạo ra 1 `NewsEventItem` duy nhất với `repostCount` tăng lên.
     * - Tin `RUMOR` dù có bao nhiêu repost từ nguồn Tier-3 vẫn giữ nguyên là `RUMOR`, không bao giờ tự thăng cấp thành `CONFIRMED`.
     * - Nếu phát hiện cố gắng Prompt Injection trong tiêu đề, đánh dấu `containsUntrustedInstructionAttempt = true` và khóa tác động.
     */
    fun ingestAndDeduplicate(
        rawInputs: List<RawNewsInput>,
        nowMs: Long = System.currentTimeMillis()
    ): List<NewsEventItem> {
        if (rawInputs.isEmpty()) return emptyList()

        val grouped = rawInputs.groupBy { computeCanonicalClusterId(it.canonicalUrl, it.title) }
        val deduplicated = mutableListOf<NewsEventItem>()

        for ((clusterId, clusterItems) in grouped) {
            // Chọn bản tin có sourceTier cao nhất (1 là cao nhất) làm đại diện gốc
            val primary = clusterItems.minWithOrNull(
                compareBy<RawNewsInput> { it.sourceTier }.thenBy { it.publishedAtMs }
            ) ?: continue

            val combinedText = clusterItems.joinToString(" ") { it.title }
            val hasInjectionAttempt = detectPromptInjectionAttempt(combinedText)

            // Kiểm tra độ cũ (> 4 giờ thì chuyển sang STALE nếu chưa CONFIRMED)
            val ageMs = (nowMs - primary.publishedAtMs).coerceAtLeast(0L)
            val effectiveStatus = when {
                ageMs > 4 * 3600_000L && primary.status != NewsVerificationStatus.CONFIRMED ->
                    NewsVerificationStatus.STALE
                // RUMOR chỉ có thể thành CONFIRMED nếu trong cụm có ít nhất 1 nguồn Tier-1 chính thức xác nhận
                primary.status == NewsVerificationStatus.RUMOR ->
                    NewsVerificationStatus.RUMOR
                else -> primary.status
            }

            val reliability = if (hasInjectionAttempt) {
                0.0
            } else {
                sourceTierReliabilityWeight(primary.sourceTier, effectiveStatus)
            }

            // Quyết định hành động Event Gate theo Mục 10.5
            val gateAction = when {
                hasInjectionAttempt -> "BLOCK_INJECTION_ATTEMPT"
                effectiveStatus == NewsVerificationStatus.DENIED ||
                    effectiveStatus == NewsVerificationStatus.STALE -> "ALLOW"
                primary.category in setOf(
                    NewsEventCategory.EXCHANGE_INFRA,
                    NewsEventCategory.SECURITY_EXPLOIT,
                    NewsEventCategory.REGULATORY_ACTION
                ) && abs(primary.eventImpactScore) >= 0.70 && effectiveStatus == NewsVerificationStatus.CONFIRMED ->
                    "PAUSE_TRADING"
                abs(primary.eventImpactScore) >= 0.50 && effectiveStatus in setOf(
                    NewsVerificationStatus.CONFIRMED,
                    NewsVerificationStatus.REPORTED
                ) -> "COOLDOWN_NEW_ENTRIES"
                effectiveStatus == NewsVerificationStatus.RUMOR && abs(primary.eventImpactScore) >= 0.50 ->
                    "TIGHTEN_RISK_RUMOR_ALERT"
                else -> "ALLOW"
            }

            deduplicated.add(
                NewsEventItem(
                    eventId = "EVT-${clusterId.takeLast(6)}",
                    canonicalUrl = primary.canonicalUrl.substringBefore("?utm_"),
                    duplicateClusterId = clusterId,
                    repostCount = clusterItems.size,
                    title = sanitizeHeadline(primary.title),
                    sourceName = primary.sourceName,
                    sourceTier = primary.sourceTier.coerceIn(1, 3),
                    reliabilityScore = reliability,
                    verificationStatus = effectiveStatus,
                    category = primary.category,
                    authorSentimentScore = round2(primary.authorSentimentScore.coerceIn(-1.0, 1.0)),
                    eventImpactScore = if (hasInjectionAttempt) 0.0 else round2(primary.eventImpactScore.coerceIn(-1.0, 1.0)),
                    publishedAtMs = primary.publishedAtMs,
                    ingestedAtMs = nowMs,
                    gateAction = gateAction,
                    containsUntrustedInstructionAttempt = hasInjectionAttempt
                )
            )
        }

        return deduplicated.sortedByDescending { it.publishedAtMs }
    }

    /**
     * Mục 10.7 & Phụ lục B.9 · Crowd Attention & Sentiment Proxy (`estimated = true` bắt buộc).
     * Không bao giờ tuyên bố đây là toàn bộ tâm lý đám đông thực tế; luôn gắn cờ `estimated = true`.
     */
    fun computeCrowdAttentionProxy(
        symbol: String,
        deduplicatedEvents: List<NewsEventItem>,
        fundingRateBps: Double,
        volumeAnomalyZScore: Double,
        volatilityRatioAtr: Double,
        nowMs: Long = System.currentTimeMillis()
    ): CrowdAttentionProxySnapshot {
        val uniqueCount = deduplicatedEvents.size
        val totalMentions = deduplicatedEvents.sumOf { it.repostCount }
        val confirmedHighImpactCount = deduplicatedEvents.count {
            it.verificationStatus == NewsVerificationStatus.CONFIRMED && abs(it.eventImpactScore) >= 0.50
        }
        val rumorCount = deduplicatedEvents.count { it.verificationStatus == NewsVerificationStatus.RUMOR }

        // Attention Score [0..100] kết hợp từ tần suất sự kiện độc lập (không bị thổi phồng bởi repost),
        // Funding 극단, Volume Z-score và Volatility Ratio
        val eventAttention = (uniqueCount * 12.0 + confirmedHighImpactCount * 18.0).coerceAtMost(45.0)
        val marketActivityAttention = (
            abs(fundingRateBps) * 1.8 +
                volumeAnomalyZScore.coerceAtLeast(0.0) * 12.0 +
                (volatilityRatioAtr - 1.0).coerceAtLeast(0.0) * 20.0
            ).coerceIn(0.0, 55.0)
        val attentionScore = round2((eventAttention + marketActivityAttention).coerceIn(0.0, 100.0))

        // Weighted Sentiment Score [-1..+1] chỉ tính theo sự kiện độc lập có trọng số tin cậy (không đếm lặp repost)
        val weightedSentiment = if (deduplicatedEvents.isEmpty()) {
            0.0
        } else {
            val weightSum = deduplicatedEvents.sumOf { it.reliabilityScore }.coerceAtLeast(0.01)
            val weightedSum = deduplicatedEvents.sumOf {
                (it.authorSentimentScore * 0.4 + it.eventImpactScore * 0.6) * it.reliabilityScore
            }
            round2((weightedSum / weightSum).coerceIn(-1.0, 1.0))
        }

        val crowdBiasLabel = when {
            attentionScore >= 75.0 && weightedSentiment >= 0.45 && fundingRateBps >= 5.0 ->
                "FOMO_CROWDED_LONG_RISK (Cảnh báo hưng phấn đám đông)"
            attentionScore >= 75.0 && weightedSentiment <= -0.45 && fundingRateBps <= -5.0 ->
                "PANIC_CROWDED_SHORT_RISK (Cảnh báo hoảng loạn đám đông)"
            rumorCount > 0 && confirmedHighImpactCount == 0 ->
                "UNVERIFIED_RUMOR_NOISE (Nhiễu tin đồn chưa xác minh)"
            else -> "NEUTRAL_BALANCED_ATTENTION (Chú ý thị trường ở mức bình thường)"
        }

        val gateActive = deduplicatedEvents.any {
            it.gateAction in setOf("PAUSE_TRADING", "COOLDOWN_NEW_ENTRIES", "TIGHTEN_RISK_RUMOR_ALERT")
        }
        val gateReason = deduplicatedEvents.firstOrNull {
            it.gateAction != "ALLOW"
        }?.let { "${it.gateAction}: ${it.title} (${it.verificationStatus.name})" }
            ?: "Không có sự kiện khóa giao dịch (ALLOW)"

        return CrowdAttentionProxySnapshot(
            symbol = symbol,
            estimated = true, // BẮT BUỘC theo Phụ lục B.9: luôn đánh dấu chỉ số ước lượng
            attentionScore0To100 = attentionScore,
            weightedSentimentScore = weightedSentiment,
            fundingExtremeScore = round2((abs(fundingRateBps) / 10.0).coerceIn(0.0, 1.0)),
            volumeBurstScore = round2((volumeAnomalyZScore.coerceAtLeast(0.0) / 3.0).coerceIn(0.0, 1.0)),
            uniqueEventClusterCount = uniqueCount,
            totalRawMentionCount = totalMentions,
            crowdBiasLabel = crowdBiasLabel,
            eventGateActive = gateActive,
            eventGateReason = gateReason,
            computedAtMs = nowMs
        )
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
