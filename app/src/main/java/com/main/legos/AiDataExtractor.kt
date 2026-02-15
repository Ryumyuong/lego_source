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

    data class CreditorEntry(val name: String, val amount: Int, val isCard: Boolean = false) // 채권사명, 금액(만원), 카드이용금액여부

    data class ExtractResult(
        val income: Int,
        val targetDebt: Int,
        val property: Int,
        val othersProperty: Int,       // 타인명의 부동산 재산 (만원)
        val nonAffiliatedDebt: Int,
        val creditorCount: Int,
        val defermentMonths: Int,
        val sogumwonMonthly: Int,
        val majorCreditor: String,     // 과반 채권사명
        val majorCreditorDebt: Int,    // 과반 채권사 채무 (만원)
        val hasBusinessHistory: Boolean, // 사업자 이력 (2020.04~2025.06)
        val businessStartYear: Int,    // 개업 년도
        val businessStartMonth: Int,   // 개업 월 (1~12, 모르면 0)
        val businessEndYear: Int,      // 폐업 년도 (0이면 현재 영업중)
        val hasRecoveryPlan: Boolean,  // 변제계획안 PDF 존재 여부 (개인회생 진행 중)
        val damboDebt: Int,            // 대상채무에서 제외된 담보대출 합계 (만원, 차담보/전세 제외)
        val taxDebt: Int,              // 국세/지방세/세금 채무 (만원, 단기에만 포함)
        val creditors: List<CreditorEntry> // 대상채무 채권사 목록 (미협약 판단용)
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

★★★ 채무현황 표의 모든 항목을 정확히 추출하되, 표에 없는 채권사를 새로 만들어내지 않음!
★★★ 카드이용금액이 "x"이면 카드 채권사 0건! 카드사를 추가하지 않음!

[1] 월소득
★★★ 핵심 규칙: "연봉" 줄의 값만 사용! 다른 곳에서 소득을 추정/계산하지 않음!
★★★ "월 소득 x", "소득 x", "연봉 x", "소득없음", "x" → 반드시 0! 사업자여도 소득이 x이면 0!
★★★ 월세, 보증금, 생계비 등은 소득이 아님! 소득으로 추정 금지!
★★★ PDF(소금원 등)의 소득금액은 income에 넣지 않음! sogumwonMonthly에만 넣음! income은 반드시 HWP "연봉" 줄에서만!
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

- 포함: 신용대출(100), 카드론대출(0037), 카드이용금액, 지급보증담보(240), 대손상각채권(4071), 운전자금(1051), 소상공인(1201), 운전자금(일반)(1051), 채권인수전문기관보유(999), 지급보증대지급금(1391)
  ★ (999) 채권인수전문기관 보유 = 한국자산관리공사 등 채권인수 → 반드시 포함!
- ★ 현금서비스(0041) 처리 규칙 (같은 카드사 기준으로 비교!):
  - 카드이용금액 >= 해당 카드사의 표 현금서비스 금액 → 표의 현금서비스 제외, 카드이용금액만 합산
  - 카드이용금액 < 해당 카드사의 표 현금서비스 금액 → 표의 현금서비스 + 카드이용금액 둘 다 합산!
  예: 삼성카드 현금서비스(0041) 500만, 삼성카드 카드이용금액 800만 → 800>=500 → 카드이용금액 800만만 합산
  예: KB국민카드 현금서비스(0041) 300만, KB국민카드 카드이용금액 100만 → 100<300 → 현금서비스 300만 + 카드이용금액 100만 = 400만 합산
- ★★★ (240) 코드는 무조건 포함! "지급보증(보증서) 담보대출(240)"도 코드가 240이므로 반드시 포함!
  예: "지급보증(보증서) 담보대출(240) 고려신용정보 590" → 590 포함!
- ★ 제외 리스트에 없는 코드는 모두 포함! (제외만 확실히 걸러내기)
- ★★★ (999) 채권인수전문기관 보유 → 반드시 포함! 제외 금지! (한국자산관리공사 등)
- ★★★ (1391) 지급보증대지급금 → 반드시 포함! 제외 금지!
- ★ 제외 코드 목록 (이 코드만 제외!): (200),(230),(270),(290),(400),(500),(3011),(510),(1061),(1421),(0041)
  → ★★★ (999)는 이 목록에 없으므로 절대 제외하지 않음! 반드시 포함!
  → ★★★ (270) 전세대출 예외: 지역줄이나 대출과목에 "질권설정x", "질권설정X", "질권 x" 등 질권 없음이 있으면 → (270) 전세대출도 대상채무에 포함!
  → (0041) 현금서비스는 위 규칙에 따라 카드이용금액과 비교하여 포함/제외 판단!
  → (220) 주택담보대출(220)은 담보대출이므로 제외! 융자담보(220)도 담보대출이므로 제외!
  → (1421) 할부금융 = 차량할부 담보대출이므로 제외!
  → (1891) 기타(대출채권)은 담보가 아닌 경우도 있으므로 제외하지 않음! 대상채무에 포함!
  → (240)은 이 목록에 없으므로 절대 제외하지 않음!
  → ★★★ 예외2: "순번N 신용대출" 보정이 있으면 해당 순번은 (290)이든 어떤 코드든 무조건 포함!
    예: 순번3이 기타담보(290)이고 "순번3 신용대출"이 있으면 → 순번3 금액 반드시 포함!
- ★ 개인채무(차용증) 제외
- ★ 미협약채권 판단은 앱에서 별도 처리 (nonAffiliatedDebt는 항상 0으로 반환)
- ★ 민사 소송금 제외
- ★ 특이사항의 연대보증(타인 보증), 고소/소송금은 대상채무/creditors에 포함하지 않음! (별도 처리)
- ★ 국세/지방세/세금은 대상채무(targetDebt)에 포함하지 않음! → taxDebt로 별도 추출 (단기에만 사용)
- ★ 지급보증은 전부 대상채무에 포함! (3021 코드, "(지급보증)" 표시 모두 포함)
  예: "전북신용보증재단 4500만(지급보증)" → 포함!
  예: "지급보증(보증서) 담보대출(240)" → 포함!
  예: "지급보증(3021)" → 포함!
- ★ 융자담보(220)는 담보대출이므로 대상채무에서 제외!
  예: "융자담보(220)" → 담보대출 → 제외!
★★★ 보증채무 중복 제거 (반드시 적용!):
- 운전자금과 지급보증이 같은 날짜이면 → 동일한 1개 채무! 둘 중 높은 금액만 계산!
- 금액이 같든 다르든, 날짜가 같으면 1건! (운전자금 20,000 + 지급보증 17,000 → 20,000만 계산!)
- targetDebt에 1건만 합산하고, creditors에도 1건만 포함! (높은 금액 쪽 채권사 기준)
  예: 순번1 농협은행 운전자금(1051) 2025.06.12 20,000 + 순번2 경남신용보증재단 지급보증(3021) 2025.06.12 17,000
  → 날짜(2025.06.12) 동일 + 운전자금/지급보증 → 높은 금액 2000만 1건만 계산! (3700만으로 중복 합산 금지!)
  → creditors에 농협은행 2000만만 포함 (경남신용보증재단은 보증이므로 제외)
- ★ 기관이 다르더라도 날짜가 같고 하나가 지급보증이면 동일 대출의 보증!
★★★ [대출과목 섹션] 반드시 먼저 읽고 표 처리에 반영! ★★★
대출과목은 채무현황 표의 각 항목이 어떤 유형(약관, 담보, 중도금 등)인지 알려주는 핵심 정보!
표의 코드만으로는 구분 불가한 항목(퇴직금담보, 약관대출 등)을 대출과목에서 확인해야 함!
★ 대출과목을 먼저 읽고, 표의 각 항목을 처리할 때 대출과목 정보를 반드시 대조!

★ 대출과목 표기 형식 (3가지 패턴):
  (1) "기관명N,M(유형)" → 해당 기관의 N번째, M번째 행이 해당 유형
    예: "삼성생명1,2(약관)" → 삼성생명의 표 1번째,2번째 행 = 약관대출 → 대상채무 제외!
    예: "씨케이저축1,2(중도금)" → 씨케이저축의 표 1번째,2번째 행 = 중도금대출
  (2) "기관명 금액(유형)" → 해당 기관의 해당 금액 항목이 해당 유형
    예: "농협4억1942만(배우자명의집담보)" → 농협 4억1942만 항목 = 배우자명의집담보 → 제외!
    예: "농협1억600만(어머니토지담보)" → 농협 1억600만 항목 = 어머니토지담보 → 제외!
    예: "에이원대부(차담보)" → 에이원대부 항목 = 차량담보 → 제외!
    예: "현대캐피탈(차량담보)" → 현대캐피탈 항목 = 차량담보 → 제외!
  (3) "기관명 금액,금액,금액(유형)" → 해당 기관의 복수 금액 항목들이 해당 유형
    예: "농협은행900만,900만,3000만(퇴직금담보)" → 농협은행의 900만, 900만, 3000만 = 퇴직금담보 → 전부 제외!

★ 대출과목 유형별 대상채무 제외 규칙:
  - "약관" 포함 → 보험약관대출 → 제외! (보험해약환급금으로 상계)
    예: "삼성생명1,2(약관)" → 삼성생명 1,2번째 행 제외!
    예: "삼성화재1100만(보험약관대출)" → 제외!
    예: "im라이프2900만(약관대출)" → 제외!
  - "담보" 포함 → 담보대출 → 제외! (모든 담보 유형 포함)
    집담보, 배우자명의집담보, 토지담보, 어머니토지담보, 부동산담보,
    퇴직금담보, 차담보, 차량담보, 중도금담보 등
    ★ 차량담보는 항상 제외! (차량 처분 판단은 앱에서 별도 처리)
  - "리스" 포함 → 리스(차리스 등) → 제외! (담보와 동일 취급)
    예: "bmw파이낸셜(차리스)" → 제외!
  - "후순위" 포함 → 후순위대출 → 제외! (담보와 동일 취급)
    예: "유노스(후순위)" → 제외!
  - "전세", "전세대출" → 전세대출 → 기본 제외!
    예: "우리은행(전세대출)" → 우리은행 항목 제외
    ★★★ 예외: 질권설정 없음 → 전세대출을 대상채무에 포함!
    ★ "질권설정x", "질권설정X", "질권x", "질권설정 x", "질권 x" = 질권 없음 → 전세대출 대상채무 포함!
    ★ "질권설정0", "질권설정O", "질권설정o" = 질권 있음 → 전세대출 제외!
    ★ 질권 표시 없으면 기본 제외
    ★★★ 질권설정 정보는 대출과목뿐 아니라 지역줄에도 있을 수 있음! 반드시 지역줄도 확인!
    예: "인천(본인명의 전세 3억1500만 – 대출 2억 / 질권설정 x)" → 질권 없음 → 표의 전세대출(270) 2억을 대상채무에 포함!
    예: "서울(전세 2억 – 대출 1억5000 / 질권설정0)" → 질권 있음 → 전세대출 제외
  - "개인", "개인간", "사채", "지인", "사내대출" → 개인 간 채무 → 제외!
    예: "장인어른 5000만" → 개인 → 제외
    예: "사내대출 5500만" → 제외
    예: "개인 7300만" → 개인 간 차용 → 제외
  - "개인채무 근저당" → 대상채무 제외 (재산에서만 차감)
  - "신복", "신복위", "신용회복위원회" → PDF 합의서에서 별도 처리 → 대상채무/creditors 모두 제외!
    예: "신복 400만", "신복위 8887만", "신용회복위원회 1589만" → 전부 제외
- ★ 대출과목에 "순번N. 차량담보" 표시가 있으면 해당 순번은 표에서 제외!



★ 대출과목에 "+" 표시된 항목은 표에 없는 추가 채무 → 표 합산에 더하기!
  예: "+ 국민행복기금 1202만 / 프라미스대부 79만 / 새도약기금 80만" → 1202+79+80 = 1361만 추가
  (신용/카드/담보/신복/신복위/총액 요약은 "+" 유무와 관계없이 항상 무시! 이것은 개별 채권사가 아닌 카테고리 요약이므로 절대 대상채무에 포함하지 않음)
  ★ 단, "+" 항목이라도 "자동차담보", "차량담보", "담보대출" 등이 명시되어 있으면 제외!
  예: "+ 20년 엔에이치투자증권 400만 (자동차담보대출)" → 자동차담보이므로 제외

★★★ 단위 주의 (매우 중요! 단위를 혼동하면 결과가 10배 오차 발생!) ★★★
- 채무현황 표의 쉼표숫자는 천원(千원) 단위! (예: 23,435 = 23,435천원 = 약 2344만원)
  → 표의 숫자를 만원으로 바로 쓰면 안 됨! 반드시 ÷10 해야 만원!
  → 예: 표에 66,666 → 66,666천원 = 6667만원 (66666만원이 아님!)
  → 예: 표에 787,559 → 787,559천원 = 78756만원 (787559만원이 아님!)
- 카드이용금액은 만원(万원) 단위! (예: "173만" = 173만원 = 1,730천원)
  → 카드이용금액을 천원으로 환산: ×10 (예: 173만 → 1730천원)
- ★ 표(천원)와 카드이용금액(만원)을 합산할 때: 카드이용금액×10 하여 천원으로 통일 후 합산!
- 최종 합계(천원) ÷ 10 반올림 → 만원

★★★ 카드이용금액은 반드시 대상채무(targetDebt)에 합산! ★★★
- 카드이용금액은 표 아래에 별도로 기재되어 있음 (예: "삼성카드 1000만, KB국민카드 200만")
- 이 금액들을 표의 포함 항목 합계에 반드시 더해야 함!
- targetDebt = 표 포함항목 합계 + 카드이용금액 합계
- 카드이용금액을 빠뜨리면 대상채무가 크게 줄어들어 잘못된 결과가 됨!
  예: 표 합계 5458만 + 카드이용금액 2113만 = targetDebt 7571만 (카드 빠뜨리면 5458만으로 오류!)

★ 계산 검증 (반드시 수행):
1단계: 표의 각 항목을 순번별로 나열하고, 포함/제외 판단 근거를 명시
  예: 순번1 (240) → 포함 (240은 제외 목록에 없음)
  예: 순번2 (100) → 포함 (신용대출)
  예: 순번3 (290) → 원래 제외이지만 "순번3 신용대출" 보정 → 포함!
  예: 순번4 (0037) → 포함 (카드론)
2단계: 포함 항목의 금액(천원)을 하나씩 더하기 (한번에 합산하지 말고 누적)
  ★ 표의 숫자는 천원 단위! 예: 23,435 → 23,435천원
3단계: ★★★ 카드이용금액 반드시 합산! 카드이용금액은 만원 단위이므로 ×10하여 천원으로 환산!
  예: 현대카드 173만 → 173×10 = 1,730천원 추가
  예: 농협카드 273만 → 273×10 = 2,730천원 추가
4단계: 최종 합계(천원) ÷ 10 반올림 → 만원
  ★ 이때 표 숫자를 만원으로 착각하지 않도록 주의! 표 66,666은 6667만원!

표가 없을때만 "대출과목" 요약 사용:
  신용 + 카드 합산 (신복/신복위 제외!)
  ★ 반드시 제외: 담보, 신복, 신복위
  ★ 학자금(한국장학재단)은 대상채무에 포함!
  ★ "총액"이 있으면 총액 - 담보 - 신복(신복위)로 검증
  ★ "신복", "신복위", "신용회복위원회"는 반드시 대상채무에서 제외! (PDF 합의서에서 별도 처리)
  예: 신용=1809만, 카드=2200만, 담보=0만, 총액=4009만 → 4009만
  예: 신용=610만, 신복위=1589만, 담보=0만 → 610만 (신복위 제외!)
  예: 대출과목에 "신복 8887만"만 있으면 → 0만 (신복위만 있으므로 대상채무 없음)

[3] 재산
다음 항목을 각각 계산한 후 합산:

A) 부동산 (지역줄 또는 재산줄)
   시세(공시지가) - 대출 - 세입자보증금
   ★ 결과가 마이너스면 0 처리!
   ★ 부동산이 여러 개면 각각 개별로 계산 후 합산! (같은 지역이라도 별도 줄이면 별도 부동산!)
   ★ 개인채무 근저당도 대출로 차감
   예: "시세 2억 > 대출 1억797만" → 20000-10797 = 9203만
   예: "공시지가 1억2000만 > 대출 8000만" → 12000-8000 = 4000만
   예: "공시지가 1500만 > 개인채무 근저당 3500만" → 1500-3500 = 마이너스 → 0
   예: 재산줄에 "성남(건물 시세7억 > 대출8000만 > 세입자2.5억)" + "성남(건물 시세7억 > 세입자2.5억)" → 각각 계산 후 합산!
   ★ 분양권 재산 계산 (2가지 경우):
  (1) 분양가가 있을때: 분양가 - 중도금대출 - 계약금 (마이너스면 0)
    예: "분양권 4억6000만 > 중도금대출 1억8712만 > 계약금 4000만" → 46000-18712-4000 = 23288만
  (2) 분양가가 없을때: 계약금 - 대출 (마이너스면 0)
    예: "분양권 계약 > 계약금 5200만, 중도금 2억6354만(씨케이저축대출)" → 5200 - 26354 = 마이너스 → 0만
    예: "분양권 계약 > 계약금 8500만" → 8500 - 0 = 8500만
    예: "분양권 계약 > 계약금 8500만, 중도금 시행사대납" → 8500 - 0 = 8500만

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

★ 타인명의 재산 (othersProperty):
- 배우자명의, 부모명의, 형제명의 등 본인명의가 아닌 부동산/보증금의 재산 합계 (만원)
- 배우자명의는 ÷2 적용 후 금액
- 본인명의 재산만 있으면 0
- 예: property=10800 중 배우자명의 아파트 2750만 → othersProperty=2750

★ 채권사 수 (creditorCount):
- 담보대출, 학자금 제외한 채권사(기관) 수를 카운트
- 같은 기관에서 여러 건이면 1건으로 카운트
- 카드 이용금액도 채권사 1건으로 카운트
- 예: 농협카드만 있으면 1, 농협카드+신한은행이면 2

[4] PDF 추가 정보 (있는 경우만)

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
   - ★ 해당 채권사도 creditors 목록에 추가! (보증서 담보대출로 대상채무에 포함된 경우만)

[5] 추가 추출 항목

A) 과반 채권사 (majorCreditor, majorCreditorDebt)
   채무현황 표에서 담보대출·학자금을 제외한 대상채무 중, 동일 채권사(기관)의 합계가 대상채무의 50% 초과인 채권사
   ★ 같은 기관에서 여러 건이면 합산하여 판단
   - majorCreditor: 해당 채권사명 (없으면 빈문자열 "")
   - majorCreditorDebt: 해당 채권사 채무 합계 (만원, 없으면 0)

C) 사업자 이력 (hasBusinessHistory, businessStartYear, businessEndYear)
   - hasBusinessHistory: "개인사업자", "사업자", "자영업", "개업/폐업" 등 사업자등록 이력이 있을 때만 true
     ★ 프리랜서, 대리운전, 배달, 일용직, 아르바이트 등은 사업자가 아님! → false
     ★ "개업 XX년 / 폐업 XX년" 또는 "개인사업자 - XX년 개업" 등 명시적 사업자 이력만 인정
   - 사업 기간이 2020년 4월 ~ 2025년 6월과 겹치면 true
     (개업이 2020년 4월 이전이어도 폐업이 2020년 4월 이후면 true)
     (개업이 2025년 7월 이후면 false)
     예: 개업 2018년, 폐업 2021년 → true (2020.04~2025.06 기간과 겹침)
     예: 개업 2025년 12월 → false (기간 밖)
     예: 개업 2019년, 폐업 2020년 3월 → false (기간 전에 폐업)
     예: 프리(대리운전) → 사업자 아님 → false
   - businessStartYear: 개업 년도 (2자리면 2000 더하기, 사업이력 없으면 0)
   - businessStartMonth: 개업 월 (1~12, 모르면 0) 예: "25년 12월 개업" → 12
   - businessEndYear: 폐업 년도 (없고 현재 영업중이면 현재 년도, 사업이력 없으면 0)

D) 변제계획안 존재 여부 (hasRecoveryPlan)
   - PDF에 "변제계획(안)" 또는 "변제계획안" 또는 "개인회생채권 변제예정액" 이 있으면 true
   - 개인회생 진행 중을 의미 → 단기(회생) 불가 조건

E) 대상채무 채권사 목록 (creditors)
   ★ 채무현황 표에서 대상채무에 포함된 채권사를 정확히 추출! 표에 없는 채권사는 추가하지 않음!
   ★ 카드이용금액이 "x"이면 카드사 creditors 없음!
   - 대상채무에 포함된 각 채권사(기관)의 이름과 금액(만원)을 배열로 추출
   - 같은 채권사에 여러 건이면 합산하여 1건으로
   - 담보대출, 학자금, 개인채무 등 대상채무에서 제외된 항목은 포함하지 않음
   - ★ 신복, 신복위, 신용회복위원회 채무는 creditors에 포함하지 않음! (PDF 합의서에서 별도 처리)
   - ★ 특이사항의 연대보증, 고소/소송, 개인 간 채무는 creditors에 포함하지 않음!
   - ★ 국세/지방세/세금은 creditors에 포함하지 않음! (taxDebt로 별도 추출)
   - 카드이용금액이 있으면 해당 카드사명과 금액도 포함하되 isCard: true로 표시
   예: [{"name":"KB국민카드","amount":1200,"isCard":true},{"name":"한빛자산관리대부","amount":500,"isCard":false}]

F) 담보대출 합계 (damboDebt)
   - 대상채무에서 제외된 담보대출 코드 (200),(230),(290),(220) 항목의 금액 합계 (만원)
   - ★ 차량담보(차담보)로 판단된 항목은 제외! (차량담보는 별도 처리)
   - ★ 전세대출(270)은 제외! (별도 항목)
   - ★ (400),(500),(3011),(510) 등 기타 제외 코드도 제외!
   - 없으면 0
   예: 순번2 부동산담보(200) 80,000 → 8000만, 순번5 기타담보(290) 20,000 → 2000만 → damboDebt=10000

G) 세금 채무 (taxDebt)
   - 대출과목 "+" 항목 중 국세/지방세/세금에 해당하는 금액 합계 (만원)
   - targetDebt에는 포함하지 않고 taxDebt로만 추출! (단기(회생)에서만 사용)
   - 없으면 0
   예: "+ 국세 1200만" → taxDebt=1200

반드시 JSON만 응답:
{"income": 숫자, "targetDebt": 숫자, "property": 숫자, "othersProperty": 숫자, "nonAffiliatedDebt": 0, "creditorCount": 숫자, "defermentMonths": 숫자, "sogumwonMonthly": 숫자, "majorCreditor": "문자열", "majorCreditorDebt": 숫자, "hasBusinessHistory": true/false, "businessStartYear": 숫자, "businessStartMonth": 숫자, "businessEndYear": 숫자, "hasRecoveryPlan": true/false, "damboDebt": 숫자, "taxDebt": 숫자, "creditors": [{"name":"채권사명","amount":숫자,"isCard":true/false}]}

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

                    // 채권사 목록 파싱
                    val creditorList = ArrayList<CreditorEntry>()
                    try {
                        val creditorsArray = data.optJSONArray("creditors")
                        if (creditorsArray != null) {
                            for (i in 0 until creditorsArray.length()) {
                                val obj = creditorsArray.getJSONObject(i)
                                val cName = obj.optString("name", "")
                                val cAmount = obj.optInt("amount", 0)
                                val cIsCard = obj.optBoolean("isCard", false)
                                if (cName.isNotEmpty() && cAmount > 0) {
                                    creditorList.add(CreditorEntry(cName, cAmount, cIsCard))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "채권사 목록 파싱 실패: ${e.message}")
                    }
                    Log.d(TAG, "채권사 목록: ${creditorList.size}건 - ${creditorList.joinToString { "${it.name}(${it.amount}만)" }}")

                    val result = ExtractResult(
                        income = data.optInt("income", 0),
                        targetDebt = data.optInt("targetDebt", 0),
                        property = data.optInt("property", 0),
                        othersProperty = data.optInt("othersProperty", 0),
                        nonAffiliatedDebt = data.optInt("nonAffiliatedDebt", 0),
                        creditorCount = data.optInt("creditorCount", 0),
                        defermentMonths = data.optInt("defermentMonths", 0),
                        sogumwonMonthly = data.optInt("sogumwonMonthly", 0),
                        majorCreditor = data.optString("majorCreditor", ""),
                        majorCreditorDebt = data.optInt("majorCreditorDebt", 0),
                        hasBusinessHistory = data.optBoolean("hasBusinessHistory", false),
                        businessStartYear = data.optInt("businessStartYear", 0),
                        businessStartMonth = data.optInt("businessStartMonth", 0),
                        businessEndYear = data.optInt("businessEndYear", 0),
                        hasRecoveryPlan = data.optBoolean("hasRecoveryPlan", false),
                        damboDebt = data.optInt("damboDebt", 0),
                        taxDebt = data.optInt("taxDebt", 0),
                        creditors = creditorList
                    )

                    Log.d(TAG, "AI 추출 완료: 소득=${result.income}, 대상채무=${result.targetDebt}, 재산=${result.property}, " +
                            "미협약=${result.nonAffiliatedDebt}, 유예=${result.defermentMonths}, 소금원=${result.sogumwonMonthly}, " +
                            "과반=${result.majorCreditor}(${result.majorCreditorDebt}만), " +
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