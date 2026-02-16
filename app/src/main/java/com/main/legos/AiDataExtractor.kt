package com.main.legos

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object AiDataExtractor {
    private const val TAG = "AI_EXTRACT"
    private val API_KEY = BuildConfig.GEMINI_API_KEY
    private val API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$API_KEY"

    // CreditorEntry 제거됨 - 코드에서 직접 파싱
    // nonAffiliatedDebt/creditorCount/majorCreditor/creditors 제거됨 - 코드에서 직접 파싱

    data class ExtractResult(
        val defermentMonths: Int,
        val sogumwonMonthly: Int,
        val hasRecoveryPlan: Boolean  // 변제계획안 PDF 존재 여부 (개인회생 진행 중)
        // taxDebt 제거됨 - 코드에서 textTaxDebt로 직접 파싱
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

[1] PDF 추가 정보 (있는 경우만)

A) 상환내역서 PDF - 유예기간(월) 추출
   "유예기간" 또는 "거치기간" 이라는 단어 뒤의 숫자 (개월 수)
   ★ 반드시 "상환내역서" PDF 문서에서만 추출!
   ★ HWP의 "신용회복", "채무조정", "변제중", "개인회생", "납부" 등은 유예기간이 아님!
   ★ 개인회생 납부 횟수(예: 20회 납부)는 유예기간이 아님! 절대 defermentMonths에 넣지 않음!
   ★ 상환내역서 PDF가 없으면 반드시 0!
   없으면 0

B) 소금원(소득금액증명원) - 사업자 소득금액 추출
   "소득금액" 또는 "사업소득금액"에 해당하는 연간 금액 (원 단위)
   만원으로 변환: 원÷10000 반올림
   연간소득÷12 = 월소득
   없으면 0

C) 합의서 PDF - 개인채무조정에서 제외된 채무내역 처리
   ★ PDF에 "개인채무조정에서 제외된 채무내역" 표가 있는 경우:
   - 각 행의 "제외사유" 컬럼을 확인하여 분류
   - ★ 제외사유에 "보증서 담보대출"이 포함된 경우 → 해당 행의 원금을 대상채무(targetDebt)에 합산!
     예: 제외사유 "개별상환 (보증서 담보대출)", 원금 2,036,167 → 대상채무에 204만 추가
     예: 제외사유 "개별상환 (보증서 담보대출)", 원금 4,396,312 → 대상채무에 440만 추가
   - ★ 그 외 제외사유 (자동차 담보대출 등) → 해당 행의 원금을 담보대출(damboDebt)에 합산!
     예: 제외사유 "개별상환 (자동차 담보대출)", 원금 10,461,832 → 담보대출에 1046만 추가
   - ★ 원금은 원 단위 → ÷10000 반올림 → 만원
   - ★ 해당 채권사의 원금도 참고용으로 확인

[2] 추가 추출 항목

A) 변제계획안 존재 여부 (hasRecoveryPlan)
   - PDF에 "변제계획(안)" 또는 "변제계획안" 또는 "개인회생채권 변제예정액" 이 있으면 true
   - 개인회생 진행 중을 의미 → 단기(회생) 불가 조건

반드시 JSON만 응답:
{"defermentMonths": 숫자, "sogumwonMonthly": 숫자, "hasRecoveryPlan": true/false}

텍스트:
$truncatedText"""

        Thread {
            try {
                val part = JSONObject().put("text", prompt)
                val parts = JSONArray().put(part)
                val content = JSONObject().put("parts", parts)
                val contents = JSONArray().put(content)
                val generationConfig = JSONObject().put("temperature", 0)
                val requestBody = JSONObject().put("contents", contents).put("generationConfig", generationConfig)

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

                    // 채권사 관련 제거됨 - 코드에서 직접 파싱

                    val result = ExtractResult(
                        defermentMonths = data.optInt("defermentMonths", 0),
                        sogumwonMonthly = data.optInt("sogumwonMonthly", 0),
                        hasRecoveryPlan = data.optBoolean("hasRecoveryPlan", false)
                    )

                    Log.d(TAG, "AI 추출 완료: " +
                            "유예=${result.defermentMonths}, 소금원=${result.sogumwonMonthly}, " +
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