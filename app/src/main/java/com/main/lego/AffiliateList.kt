package com.main.lego

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
    fun isAffiliated(creditorName: String): Boolean {
        val keywords = affiliateKeywords

        // 디버그: 초기화 확인
        android.util.Log.d("AFFILIATE", "isAffiliated 호출: '$creditorName', 키워드 수: ${keywords?.size ?: 0}")

        if (keywords == null) {
            android.util.Log.e("AFFILIATE", "키워드가 초기화되지 않음!")
            return false
        }
        val normalizedName = creditorName.replace(Regex("[\\s\\(\\)]"), "")

        // 1. 정확히 일치하는지 확인
        if (keywords.contains(normalizedName)) return true
        if (keywords.contains(creditorName)) return true

        // 2. 채권자명이 키워드를 포함하는지 확인 (긴 키워드부터)
        val sortedKeywords = keywords.filter { it.length >= 3 }.sortedByDescending { it.length }
        for (keyword in sortedKeywords) {
            if (normalizedName.contains(keyword) || creditorName.contains(keyword)) {
                return true
            }
        }

        // 3. 키워드가 채권자명을 포함하는지 확인
        for (keyword in sortedKeywords) {
            if (keyword.contains(normalizedName) && normalizedName.length >= 3) {
                return true
            }
        }

        return false
    }
}