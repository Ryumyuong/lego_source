package com.main.legos

import android.content.Context

object AffiliateList {
    private var affiliateKeywords: Set<String>? = null

    /**
     * assets/agreement_institutions.csv 파일에서 협약기관 목록을 로드합니다.
     * 앱 시작 시 한 번 호출해야 합니다.
     */
    fun initialize(context: Context) {
        if (affiliateKeywords != null) return // 이미 로드됨

        val keywords = mutableSetOf<String>()

        try {
            context.assets.open("agreement_institutions.csv").bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line -> // 첫 줄(헤더) 제외
                    val columns = line.split(",")
                    if (columns.isNotEmpty()) {
                        val name = columns[0].trim()
                        if (name.isNotEmpty()) {
                            // 원본 이름 추가
                            keywords.add(name)

                            // 괄호 및 법인 형태 제거한 핵심 키워드도 추가
                            val simplified = name
                                .replace(Regex("\\(주\\)|\\(유\\)|주식회사|유한회사|유한책임회사|사단법인|재단법인"), "")
                                .replace(Regex("제[일이삼사오육칠팔구십백천]+차"), "") // 유동화 차수 제거
                                .replace(Regex("유동화전문"), "")
                                .replace(Regex("[\\(\\)\\s]"), "")
                                .trim()

                            if (simplified.isNotEmpty() && simplified.length >= 2) {
                                keywords.add(simplified)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        affiliateKeywords = keywords
    }

    /**
     * 채권자명이 협약기관에 해당하는지 판단합니다.
     * @param creditorName 채권자명 (예: "한빛자산관리대부", "KB국민카드")
     * @return 협약기관이면 true
     */
    private val englishToKorean = mapOf(
        'A' to "에이", 'B' to "비", 'C' to "씨", 'D' to "디", 'E' to "이",
        'F' to "에프", 'G' to "지", 'H' to "에이치", 'I' to "아이", 'J' to "제이",
        'K' to "케이", 'L' to "엘", 'M' to "엠", 'N' to "엔", 'O' to "오",
        'P' to "피", 'Q' to "큐", 'R' to "알", 'S' to "에스", 'T' to "티",
        'U' to "유", 'V' to "브이", 'W' to "더블유", 'X' to "엑스", 'Y' to "와이", 'Z' to "지"
    )

    // ㅐ와 ㅔ를 동일하게 처리 (한글 음절 분해 → ㅐ(중성1)를 ㅔ(중성5)로 통일 → 재조합)
    private fun normalizeAeE(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            if (ch in '\uAC00'..'\uD7A3') {
                val offset = ch - '\uAC00'
                val final = offset % 28
                val medial = (offset / 28) % 21
                val initial = offset / 28 / 21
                val normalizedMedial = if (medial == 1) 5 else medial // ㅐ → ㅔ
                sb.append((0xAC00 + (initial * 21 + normalizedMedial) * 28 + final).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun convertEnglishToKorean(name: String): String {
        val sb = StringBuilder()
        for (ch in name) {
            // 전각 영문자(Ａ-Ｚ, ａ-ｚ)를 반각(A-Z, a-z)으로 변환
            val normalized = when (ch) {
                in '\uFF21'..'\uFF3A' -> (ch - 0xFEE0).toChar() // Ａ~Ｚ → A~Z
                in '\uFF41'..'\uFF5A' -> (ch - 0xFEE0).toChar() // ａ~ｚ → a~z
                in '\uFF10'..'\uFF19' -> (ch - 0xFEE0).toChar() // ０~９ → 0~9
                else -> ch
            }
            val upper = normalized.uppercaseChar()
            if (englishToKorean.containsKey(upper)) {
                sb.append(englishToKorean[upper])
            } else {
                sb.append(normalized)
            }
        }
        return sb.toString()
    }

    fun isAffiliated(creditorName: String): Boolean {
        val keywords = affiliateKeywords

        // 디버그: 초기화 확인
        android.util.Log.d("AFFILIATE", "isAffiliated 호출: '$creditorName', 키워드 수: ${keywords?.size ?: 0}")

        if (keywords == null) {
            android.util.Log.e("AFFILIATE", "키워드가 초기화되지 않음!")
            return false
        }
        val normalizedName = creditorName
            .replace(Regex("\\[.*?\\]"), "") // 지점명 제거: [본점], [신장림역] 등
            .replace(Regex("[\\s\\(\\)]"), "")
            .map { ch -> when (ch) {
                in '\uFF10'..'\uFF19' -> (ch - 0xFEE0).toChar() // ０~９ → 0~9
                else -> ch
            }}.joinToString("")
        // 영문자를 한글 발음으로 변환 (예: KB국민카드 → 케이비국민카드)
        val koreanName = convertEnglishToKorean(normalizedName)
        android.util.Log.d("AFFILIATE", "영문→한글 변환: '$normalizedName' → '$koreanName'")

        // ㅐ/ㅔ 정규화 적용
        val normName = normalizeAeE(normalizedName)
        val normKorean = normalizeAeE(koreanName)

        // 0. 특정 키워드 포함 시 무조건 협약채권
        if (normName.contains("신용보") || normKorean.contains("신용보")) return true
        if (normName.contains("신용정보") || normKorean.contains("신용정보")) return true
        if (normName.contains("통신") || normKorean.contains("통신") ||
            normName.contains("텔레콤") || normKorean.contains("텔레콤") ||
            normName.contains("유플러스") || normKorean.contains("유플러스") ||
            normName.contains("모바일") || normKorean.contains("모바일")) return true
        if (normName.contains("새출발기금") || normKorean.contains("새출발기금")) return true
        if (normName.contains("신용회복위원회") || normKorean.contains("신용회복위원회")) return true
        if (normName.contains("신복위") || normKorean.contains("신복위")) return true
        if (normName.contains("신복") || normKorean.contains("신복")) return true
        if (normName.contains("캠코") || normKorean.contains("캠코")) return true
        if (normName.contains("신협") || normKorean.contains("신협")) return true
        if (normName.contains("농협") || normKorean.contains("농협")) return true
        if (normName.contains("수협") || normKorean.contains("수협")) return true
        if (normName.contains("상호") || normKorean.contains("상호")) return true

        // 1. 정확히 일치하는지 확인
        if (keywords.any { normalizeAeE(it) == normName }) return true
        if (keywords.any { normalizeAeE(it) == normalizeAeE(creditorName) }) return true
        if (keywords.any { normalizeAeE(it) == normKorean }) return true

        // 2. 채권자명이 키워드를 포함하는지 확인 (긴 키워드부터)
        val sortedKeywords = keywords.filter { it.length >= 3 }.sortedByDescending { it.length }
        for (keyword in sortedKeywords) {
            val normKeyword = normalizeAeE(keyword)
            if (normName.contains(normKeyword) || normalizeAeE(creditorName).contains(normKeyword) || normKorean.contains(normKeyword)) {
                return true
            }
        }

        // 3. 키워드가 채권자명을 포함하는지 확인 (2글자 이상: 농협, 신한, 우리, 하나 등)
        for (keyword in sortedKeywords) {
            val normKeyword = normalizeAeE(keyword)
            if ((normKeyword.contains(normName) && normName.length >= 2) ||
                (normKeyword.contains(normKorean) && normKorean.length >= 2)) {
                return true
            }
        }

        return false
    }
}