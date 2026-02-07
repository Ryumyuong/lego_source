package com.main.lego

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object AiDataExtractor {
    private const val TAG = "AI_EXTRACT"
    private const val API_KEY = "AIzaSyDn3b79UFzoG_QgxASi0dpHwkePBAvqXiw"
    private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$API_KEY"

    data class ExtractResult(
        val income: Int,
        val targetDebt: Int,
        val property: Int,
        val nonAffiliatedDebt: Int,
        val creditorCount: Int,
        val defermentMonths: Int,
        val sogumwonMonthly: Int,
        val recentDebt: Int,           // 6개월 이내 채무 (만원)
        val majorCreditor: String,     // 과반 채권사명
        val majorCreditorDebt: Int,    // 과반 채권사 채무 (만원)
        val hasBusinessHistory: Boolean, // 사업자 이력 (2019~2024)
        val businessStartYear: Int,    // 개업 년도
        val businessEndYear: Int,      // 폐업 년도 (0이면 현재 영업중)
        val hasRecoveryPlan: Boolean   // 변제계획안 PDF 존재 여부 (개인회생 진행 중)
    )

    interface OnExtractListener {
        fun onSuccess(result: ExtractResult)
        fun onError(error: String)
    }

    fun extract(text: String, listener: OnExtractListener?) {
        // 텍스트 길이 제한 (API 요청 크기 초과 방지)
        val truncatedText = if (text.length > 30000) {
            Log.w(TAG, "텍스트 길이 초과: ${text.length}자 → 30000자로 제한")
            text.take(30000)
        } else text

        val prompt = """다음 텍스트에서 정보를 추출하세요. 결과는 만원 단위 정수입니다.

[1] 월소득
- "연봉/월" 형태: 괄호 실수령액과 월 값 중 높은 값
  예: "연 6600만(470만) / 월 260만" → 470 (470 > 260)
  예: "연 3000만(227만) / 월 229만" → 229 (229 > 227)
- 월 범위(예: 800~900만)가 있으면 최대값
- 3.3% 공제 후 금액 그대로
- 연봉만 있으면 ÷12
- "소득없음"이라도 "월 생활비 OO만 받고있음", "용돈 OO만" 등 실질 수입이 있으면 그 금액 사용
  예: "사업자 소득없음 / 월 생활비 200만 받고잇음" → 200
- 완전히 소득 없으면 → 0
- ★ 배우자소득은 항상 합산하지 않음 (본인소득만 사용)

[2] 대상채무
★ 채무현황 표가 있으면 표에서 직접 계산!
★★★ 중요: 표 아래에 "순번N 신용대출", "순번N. 차량담보" 등 보정 텍스트가 있으면 반드시 우선 적용!
  - "순번N 신용대출" → 해당 순번이 (290) 등 제외 코드여도 강제 포함!
  - "순번N. 차량담보" → 해당 순번은 강제 제외!
  예: 표에 순번3이 기타담보(290)이지만 아래에 "순번3 신용대출"이 있으면 → 순번3은 포함!

- 포함: 신용대출(100), 현금서비스(0041), 카드론대출(0037), 카드이용금액, 지급보증담보(240), 대손상각채권(4071), 운전자금(1051), 소상공인(1201), 운전자금(일반)(1051)
- ★★★ (240) 코드는 무조건 포함! "지급보증(보증서) 담보대출(240)"도 코드가 240이므로 반드시 포함!
  예: "지급보증(보증서) 담보대출(240) 고려신용정보 590" → 590 포함!
- ★ 제외 리스트에 없는 코드는 모두 포함! (제외만 확실히 걸러내기)
- ★ 제외 코드 목록 (이 코드만 제외!): (200),(220),(230),(270),(290),(400),(500),(3011),(3021),(510)
  → (240)은 이 목록에 없으므로 절대 제외하지 않음!
  → ★ 예외1: 대출과목에 "전세대출 (질권설정x)"가 있으면 (270) 전세대출은 제외하지 않고 대상채무에 포함!
  → ★★★ 예외2: "순번N 신용대출" 보정이 있으면 해당 순번은 (290)이든 어떤 코드든 무조건 포함!
    예: 순번3이 기타담보(290)이고 "순번3 신용대출"이 있으면 → 순번3 금액 반드시 포함!
- ★ 개인채무(차용증) 제외
- ★ 미협약채권 판단은 앱에서 별도 처리 (nonAffiliatedDebt는 항상 0으로 반환)
- ★ 민사 소송금 제외
- ★ "지급보증(3021)" 코드 또는 대출과목에 "(지급보증)"이라고 별도 표시된 항목만 제외!
  예: "전북신용보증재단 4500만(지급보증)" → "(지급보증)" 표시 있으므로 제외
  ★ 주의: 코드가 "지급보증담보(240)"인 것은 포함! (240은 포함 리스트)
  ★ "지급보증(보증서) 담보대출(240)"도 코드가 240이므로 포함!
- ★ 대출과목에 "순번N. 차량담보" 표시가 있으면 해당 순번은 표에서 제외! (실제로는 차량담보)
- ★ 차량란의 대출 금액과 채무현황 표의 항목 금액이 일치하면 해당 항목은 차량담보대출로 간주하여 대상채무에서 제외!
  예: 차량 "대출 4800만" + 표에 "한국캐피탈 기타(대출채권)(1891) 48,002" → 48,002천원 ≈ 4800만 → 차량담보이므로 제외
  ★ 금액 비교 시 천원 단위 오차(±100천원, 즉 ±10만원) 허용
- ★ "개인", "개인간", "사채", "지인", "사내대출" 항목은 대상채무에서 제외! (따로 변제)
  예: "장인어른 5000만" → 개인 → 제외
  예: "사내대출 5500만" → 사내대출 → 제외
- ★ "개인채무 근저당"도 대상채무에서 제외! (재산에서만 차감)
- ★ 대출과목에 "개인 7300만" 등으로 적혀있으면 이는 개인 간 차용이므로 대상채무 합산하지 않음!

- ★ 전세대출/전세자금대출도 기본적으로 제외! (담보대출이므로)
- ★ 예외: "질권설정x", "질권설정X", "질권x" = 질권 없음 → 이 경우에만 전세대출을 대상채무에 포함!
- ★ "질권설정0", "질권설정O", "질권설정o" = 질권 있음 → 전세대출 제외! (0은 숫자 영이 아니라 O의 의미)
- ★ 질권 표시가 없으면 기본 제외



★ 대출과목에 "+" 표시된 항목은 표에 없는 추가 채무 → 표 합산에 더하기!
  예: "+ 국민행복기금 1202만 / 프라미스대부 79만 / 새도약기금 80만" → 1202+79+80 = 1361만 추가
  ("+"가 없는 신용/카드/담보/총액 요약은 무시)
  ★ 단, "+" 항목이라도 "자동차담보", "차량담보", "담보대출" 등이 명시되어 있으면 제외!
  예: "+ 20년 엔에이치투자증권 400만 (자동차담보대출)" → 자동차담보이므로 제외

★ 단위 주의:
- 채무현황 표의 쉼표숫자(23,435)는 천원 단위
- 카드이용금액은 "만" 단위 (예: "150만" → 1500천원으로 환산하여 합산)
- 최종 합계(천원) ÷ 10 반올림 → 만원

★ 계산 검증 (반드시 수행):
1단계: 표의 각 항목을 순번별로 나열하고, 포함/제외 판단 근거를 명시
  예: 순번1 (240) → 포함 (240은 제외 목록에 없음)
  예: 순번2 (100) → 포함 (신용대출)
  예: 순번3 (290) → 원래 제외이지만 "순번3 신용대출" 보정 → 포함!
  예: 순번4 (0037) → 포함 (카드론)
2단계: 포함 항목의 금액을 하나씩 더하기 (한번에 합산하지 말고 누적)
3단계: 카드이용금액 있으면 ×10하여 천원으로 환산 후 합산
4단계: 최종 합계 ÷ 10 반올림 → 만원

표가 없을때만 "대출과목" 요약 사용:
  신용 + 카드 + 신복위(신복) 합산
  ★ 반드시 제외: 담보
  ★ 학자금(한국장학재단)은 대상채무에 포함!
  ★ "총액"이 있으면 총액 - 담보로 검증
  ★ "신복", "신복위" 모두 동일하게 대상채무에 포함!
  예: 신용=1809만, 카드=2200만, 담보=0만, 총액=4009만 → 4009만
  예: 채무현황 표가 비어있고 대출과목에 "신복위=5354만"만 있으면 → 5354만
  예: 채무현황 표가 비어있고 대출과목에 "신복 8887만"만 있으면 → 8887만

[3] 재산
다음 항목을 각각 계산한 후 합산:

A) 부동산 (지역줄 또는 재산줄)
   시세(공시지가) - 대출 - 세입자보증금
   ★ 결과가 마이너스면 0 처리!
   ★ 부동산이 여러 개면 각각 개별로 계산 후 합산! (같은 지역이라도 별도 줄이면 별도 부동산!)
   ★ 개인채무 근저당도 대출로 차감
   ★ 분양권: 분양가 - 중도금대출 - 계약금
   예: "시세 2억 > 대출 1억797만" → 20000-10797 = 9203만
   예: "공시지가 1억2000만 > 대출 8000만" → 12000-8000 = 4000만
   예: "공시지가 1500만 > 개인채무 근저당 3500만" → 1500-3500 = 마이너스 → 0
   예: "분양권 4억6000만 > 중도금대출 1억8712만 > 계약금 4000만" → 46000-18712-4000 = 23288만
   예: 재산줄에 "성남(건물 시세7억 > 대출8000만 > 세입자2.5억)" + "성남(건물 시세7억 > 세입자2.5억)" → 각각 계산 후 합산!
   ★ 분양권: 납입한 금액이 재산!
  - 분양가가 있으면: 계약금 + 납입한 중도금 - 대출 (마이너스면 0)
  - 분양가 없이 계약금만 있으면: 계약금이 재산
  - 중도금 시행사 대납이면 중도금 = 0
  예: "분양권 4억6000만 > 중도금대출 1억8712만 > 계약금 4000만" → 4000 + (18712 중도금 납입) - 18712(대출) = 4000만
  예: "분양권 계약 > 계약금 8500만, 중도금 시행사대납" → 8500만 (계약금이 재산)

B) 거주지 보증금 (지역줄)
   전세금 - 전세대출
   예: "전세금 1억5500 > 대출 1억" → 15500-10000 = 5500만
   ★ 대출과목에 "전세대출 (질권설정x)"가 있으면 전세대출을 빼지 않음! → 전세금 전액이 재산
   예: "전세 1억4000 > 대출 1억" + 질권설정x → 재산 = 14000만 (대출 안 뺌)
   
   ★ "질권설정x", "질권X" = 질권 없음 → 대출 안 뺌 → 전세금 전액이 재산
    ★ "질권설정0", "질권설정O" = 질권 있음 → 전세금 - 전세대출 = 재산
    ★ 질권 표시 없으면 기본적으로 전세금 - 전세대출
    예: "전세 1억4000만 – 대출 1억2600만 (질권설정0)" → 14000-12600 = 1400만

C) 차량
   시세 - 할부/담보 (마이너스면 0)

D) 예금 (재산줄)
   "예금 340만" → 340 그대로

합산규칙:
- ★ "배우자명의"는 계산 후 ÷2 (절반만 산정)
  예: "배우자명의 아파트 시세 1억4천 > 배우자명의대출 8500만" → (14000-8500)÷2 = 2750만
  예: "배우자명의 차량 시세 1000만" → 1000÷2 = 500만
  예: "배우자명의 월세 > 보증금 3500만" → 3500÷2 = 1750만
- 배우자 모르게 진행이어도 배우자명의 재산은 ÷2 포함 (동일 처리)
- ★ 보험내역은 재산에 포함하지 않음!
- 재산x면 0 (단 지역줄 보증금은 별도 합산)
- 금액은 전부 만원 단위! 변환 금지!

★ 채권사 수 (creditorCount):
- 담보대출, 학자금 제외한 채권사(기관) 수를 카운트
- 같은 기관에서 여러 건이면 1건으로 카운트
- 카드 이용금액도 채권사 1건으로 카운트
- 예: 농협카드만 있으면 1, 농협카드+신한은행이면 2

[4] PDF 추가 정보 (있는 경우만)

A) 변제계획안 PDF - 대상채무 추출
   ★ PDF가 "변제계획(안)" 또는 "변제계획안"인 경우:
   - "개인회생채권 변제예정액표"의 "총계" 행의 개인회생채권액(원금) = 변제계획안 대상채무
   - 원 단위 → ÷10000 반올림 → 만원
   예: 총계 132,314,623원 → 13231만
   ★ 별제권부 채권(별제권 행사로 변제 예상되는 부분)은 이미 총계에서 제외되어 있으므로 별도 처리 불필요
   ★ 확정채권액(원금) + 미확정채권액(원금) = 총계
   ★★★ 최종 대상채무 = 변제계획안 총계 + HWP 채무현황 표의 추가 채무!
   - HWP에 변제계획안에 없는 채권사/채무가 있으면 합산!
   - 단, HWP 대상채무 제외 규칙(담보, 전세, 개인 등)은 동일하게 적용
   예: 변제계획안 총계 13231만 + HWP 추가 신용대출 500만 → 대상채무 13731만

B) 상환내역서 PDF - 유예기간(월) 추출
   "유예기간" 또는 "거치기간" 이라는 단어 뒤의 숫자 (개월 수)
   ★ 반드시 "상환내역서" PDF 문서에서만 추출!
   ★ HWP의 "신용회복", "채무조정", "변제중" 등은 유예기간이 아님!
   없으면 0

C) 소금원(소득금액증명원) - 사업자 소득금액 추출
   "소득금액" 또는 "사업소득금액"에 해당하는 연간 금액 (원 단위)
   만원으로 변환: 원÷10000 반올림
   연간소득÷12 = 월소득
   없으면 0

[5] 추가 추출 항목

A) 6개월 이내 채무 (recentDebt)
   채무현황 표에서 대출일자가 오늘 기준 6개월 이내인 대상채무 항목의 합산 (만원)
   ★ 제외 항목(담보, 전세 등)은 합산하지 않음
   ★ 미협약 채권도 합산하지 않음
   없으면 0

B) 과반 채권사 (majorCreditor, majorCreditorDebt)
   채무현황 표에서 담보대출·학자금을 제외한 대상채무 중, 동일 채권사(기관)의 합계가 대상채무의 50% 초과인 채권사
   ★ 같은 기관에서 여러 건이면 합산하여 판단
   - majorCreditor: 해당 채권사명 (없으면 빈문자열 "")
   - majorCreditorDebt: 해당 채권사 채무 합계 (만원, 없으면 0)

C) 사업자 이력 (hasBusinessHistory, businessStartYear, businessEndYear)
   - hasBusinessHistory: 2019~2024년 사이에 사업자 이력이 있으면 true
     (개업이 2019년 이전이라도 폐업이 2019년 이후면 true)
   - businessStartYear: 개업 년도 (2자리면 2000 더하기, 없으면 0)
   - businessEndYear: 폐업 년도 (없고 현재 영업중이면 현재 년도, 사업이력 없으면 0)

D) 변제계획안 존재 여부 (hasRecoveryPlan)
   - PDF에 "변제계획(안)" 또는 "변제계획안" 또는 "개인회생채권 변제예정액" 이 있으면 true
   - 개인회생 진행 중을 의미 → 단기(회생) 불가 조건

반드시 JSON만 응답:
{"income": 숫자, "targetDebt": 숫자, "property": 숫자, "nonAffiliatedDebt": 0, "creditorCount": 숫자, "defermentMonths": 숫자, "sogumwonMonthly": 숫자, "recentDebt": 숫자, "majorCreditor": "문자열", "majorCreditorDebt": 숫자, "hasBusinessHistory": true/false, "businessStartYear": 숫자, "businessEndYear": 숫자, "hasRecoveryPlan": true/false}

텍스트:
$truncatedText"""

        Thread {
            try {
                val part = JSONObject().put("text", prompt)
                val parts = JSONArray().put(part)
                val content = JSONObject().put("parts", parts)
                val contents = JSONArray().put(content)
                val requestBody = JSONObject().put("contents", contents)

                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 60000
                conn.readTimeout = 60000

                conn.outputStream.use { os ->
                    os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val response = BufferedReader(
                        InputStreamReader(conn.inputStream, Charsets.UTF_8)
                    ).use { it.readText() }

                    val jsonResponse = JSONObject(response)
                    var aiText = ""

                    try {
                        val candidates = jsonResponse.getJSONArray("candidates")
                        val firstCandidate = candidates.getJSONObject(0)

                        if (firstCandidate.has("content") && firstCandidate.getJSONObject("content").has("parts")) {
                            val responseParts = firstCandidate.getJSONObject("content").getJSONArray("parts")
                            for (i in 0 until responseParts.length()) {
                                val p = responseParts.getJSONObject(i)
                                if (p.has("text")) aiText = p.getString("text")
                            }
                        } else if (firstCandidate.has("content") && firstCandidate.getJSONObject("content").has("text")) {
                            aiText = firstCandidate.getJSONObject("content").getString("text")
                        } else {
                            val reason = firstCandidate.optString("finishReason", "UNKNOWN")
                            Log.e(TAG, "AI 응답 구조 이상: finishReason=$reason")
                            listener?.onError("AI 응답 구조 이상")
                            return@Thread
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "AI 응답 파싱 실패: ${e.message}, response=$response")
                        listener?.onError("AI 응답 파싱 실패")
                        return@Thread
                    }

                    Log.d(TAG, "AI 응답: $aiText")

                    val jsonStr = aiText
                        .replace(Regex("```json\\s*"), "")
                        .replace(Regex("```\\s*"), "")
                        .trim()

                    // JSON 객체 추출 (설명 텍스트가 포함된 경우 대응)
                    val jsonStart = jsonStr.indexOf("{")
                    val jsonEnd = jsonStr.lastIndexOf("}")
                    if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
                        Log.e(TAG, "JSON 파싱 실패 - 유효한 JSON 없음: $jsonStr")
                        listener?.onError("AI 응답 형식 오류 (재시도 해주세요)")
                        return@Thread
                    }
                    val cleanJson = jsonStr.substring(jsonStart, jsonEnd + 1)
                    val data = JSONObject(cleanJson)

                    val result = ExtractResult(
                        income = data.optInt("income", 0),
                        targetDebt = data.optInt("targetDebt", 0),
                        property = data.optInt("property", 0),
                        nonAffiliatedDebt = data.optInt("nonAffiliatedDebt", 0),
                        creditorCount = data.optInt("creditorCount", 0),
                        defermentMonths = data.optInt("defermentMonths", 0),
                        sogumwonMonthly = data.optInt("sogumwonMonthly", 0),
                        recentDebt = data.optInt("recentDebt", 0),
                        majorCreditor = data.optString("majorCreditor", ""),
                        majorCreditorDebt = data.optInt("majorCreditorDebt", 0),
                        hasBusinessHistory = data.optBoolean("hasBusinessHistory", false),
                        businessStartYear = data.optInt("businessStartYear", 0),
                        businessEndYear = data.optInt("businessEndYear", 0),
                        hasRecoveryPlan = data.optBoolean("hasRecoveryPlan", false)
                    )

                    Log.d(TAG, "AI 추출 완료: 소득=${result.income}, 대상채무=${result.targetDebt}, 재산=${result.property}, " +
                            "미협약=${result.nonAffiliatedDebt}, 유예=${result.defermentMonths}, 소금원=${result.sogumwonMonthly}, " +
                            "6개월채무=${result.recentDebt}, 과반=${result.majorCreditor}(${result.majorCreditorDebt}만), " +
                            "사업이력=${result.hasBusinessHistory}(${result.businessStartYear}~${result.businessEndYear}), " +
                            "변제계획안=${result.hasRecoveryPlan}")

                    listener?.onSuccess(result)
                } else {
                    val errorStream = conn.errorStream?.let {
                        BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
                    } ?: "응답 없음"
                    Log.e(TAG, "API 오류: $responseCode, $errorStream")
                    // 에러 상세 내용 파싱
                    val errorDetail = try {
                        val errJson = JSONObject(errorStream)
                        errJson.optJSONObject("error")?.optString("message", "") ?: ""
                    } catch (e: Exception) { "" }
                    listener?.onError("API 오류 $responseCode: $errorDetail")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI 추출 실패: ${e.message}")
                e.printStackTrace()
                listener?.onError("AI 추출 실패: ${e.message}")
            }
        }.start()
    }
}