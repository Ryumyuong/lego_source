package com.main.lego

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.main.lego.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
// PDFBox - 언더스코어 패키지는 코드에서 직접 참조
import kr.dogfoot.hwplib.`object`.HWPFile
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlTable
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlType
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.Paragraph
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.text.HWPCharNormal
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.text.HWPCharType
import kr.dogfoot.hwplib.reader.HWPReader
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.regex.Pattern



class MainActivity : AppCompatActivity() {

    companion object {
        const val CREATE_FILE_REQUEST_CODE = 43
        const val READ_REQUEST_CODE = 42
        private val recognizedText4 = StringBuilder()
        private val recognizedText5 = StringBuilder()
    }

    lateinit var binding: ActivityMainBinding
    private var acost = 0
    private var bCost = 0.0
    private var bValue = 0
    private var card = 0
    private var cost = 0
    private var value = 0
    private var baby = "0"
    private var korea = "X"

    private var aiIncome = 0
    private var aiTargetDebt = 0
    private var aiProperty = 0
    private var aiCreditorCount = 0
    private var aiDefermentMonths = 0
    private var aiSogumwonMonthly = 0

    // AI 데이터 추출
    private var aiDataReady = false
    private var loadingDialog: android.app.ProgressDialog? = null

    private var aiRecentDebt = 0           // 6개월 이내 채무
    private var aiMajorCreditor = ""       // 과반 채권사명
    private var aiMajorCreditorDebt = 0    // 과반 채권사 채무
    private var aiNonAffiliatedDebt = 0    // 미협약 채무
    private var aiHasBusinessHistory = false // 사업자 이력
    private var aiBusinessStartYear = 0    // 개업 년도
    private var aiBusinessEndYear = 0      // 폐업 년도
    private var aiHasRecoveryPlan = false    // 변제계획안 존재 (개인회생 진행 중)

    // 여러 파일 처리
    private var hwpText = ""
    private var pdfText = ""

    private var userPdfPassword = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        // 다크모드 비활성화
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AffiliateList.initialize(this)

        // 최상단 타이틀 제거
        supportActionBar?.hide()

        binding.buttonSelectFile.setOnClickListener {
            // 데이터 리셋
            resetAllData()
            pendingUriList.clear()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/x-hwp", "application/haansofthwp", "application/vnd.hancom.hwp",
                    "application/pdf"
                ))
            }
            startActivityForResult(Intent.createChooser(intent, "파일 선택"), READ_REQUEST_CODE)
        }

        binding.copy.setOnClickListener {
            val text = buildResultText()
            if (text.isNotBlank()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("결과", text)
                clipboard.setPrimaryClip(clip)
                showToast("복사되었습니다")
            } else {
                showToast("복사할 결과가 없습니다")
            }
        }
    }

    private val pendingUriList = ArrayList<Uri>()

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == READ_REQUEST_CODE && resultCode == RESULT_OK && resultData != null) {
            resultData.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    pendingUriList.add(clipData.getItemAt(i).uri)
                }
            } ?: resultData.data?.let { pendingUriList.add(it) }

            if (pendingUriList.size > 1) {
                startProcessing(ArrayList(pendingUriList))
            } else {
                val fileName = getFileName(pendingUriList[0]) ?: "파일"
                android.app.AlertDialog.Builder(this)
                    .setTitle("$fileName 선택됨")
                    .setMessage("추가할 파일이 있나요?")
                    .setPositiveButton("분석 시작") { _, _ ->
                        startProcessing(ArrayList(pendingUriList))
                    }
                    .setNeutralButton("파일 추가") { _, _ ->
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/x-hwp", "application/haansofthwp", "application/vnd.hancom.hwp",
                                "application/pdf"
                            ))
                        }
                        startActivityForResult(Intent.createChooser(intent, "파일 선택"), READ_REQUEST_CODE)
                    }
                    .setCancelable(false)
                    .show()
            }
        } else if (requestCode == CREATE_FILE_REQUEST_CODE && resultData?.data != null) {
            saveResultToFileAndShare(resultData.data!!)
        }
    }

    private fun startProcessing(uriList: ArrayList<Uri>) {
        pendingUriList.clear()
        // PDF가 포함되어 있는지 확인
        val hasPdf = uriList.any { (getFileName(it) ?: "").lowercase().endsWith(".pdf") }

        if (hasPdf) {
            val input = android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "PDF 비밀번호"
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("PDF 비밀번호")
                .setMessage("비밀번호가 없으면 빈칸으로 진행")
                .setView(input)
                .setPositiveButton("분석 시작") { _, _ ->
                    userPdfPassword = input.text.toString()
                    processMultipleFiles(uriList)
                }
                .setCancelable(false)
                .show()
        } else {
            userPdfPassword = ""
            processMultipleFiles(uriList)
        }
    }

    private fun processMultipleFiles(uriList: ArrayList<Uri>) {
        hwpText = ""
        pdfText = ""
        val pdfTexts = ArrayList<String>()
        val pdfUris = ArrayList<Pair<Uri, String>>()
        showToast("${uriList.size}개 파일 처리 중...")

        // 1단계: HWP 먼저 처리 (주민번호 추출용)
        for (uri in uriList) {
            val fileName = getFileName(uri) ?: continue
            val lowerName = fileName.lowercase()
            when {
                lowerName.endsWith(".hwp") -> {
                    hwpText = extractHwpText(uri)
                    Log.d("FILE_PROCESS", "HWP 파일 처리: $fileName")
                }
                lowerName.endsWith(".pdf") -> {
                    pdfUris.add(Pair(uri, fileName))
                }
                lowerName.endsWith(".xlsx") -> {
                    readExcelFile(uri)
                    return
                }
            }
        }

        // 2단계: PDF 비밀번호 (사용자 입력)
        val pdfPassword = userPdfPassword
        if (pdfPassword.isNotEmpty()) {
            Log.d("FILE_PROCESS", "PDF 비밀번호 적용")
        }

        // 3단계: PDF 처리 (비밀번호 적용)
        for ((uri, fileName) in pdfUris) {
            val text = extractPdfText(uri, pdfPassword)
            if (text.isNotEmpty()) {
                pdfTexts.add("=== PDF: $fileName ===\n$text")
                Log.d("FILE_PROCESS", "PDF 파일 처리: $fileName (${text.length}자)")
            }
        }

        pdfText = pdfTexts.joinToString("\n\n")

        val combinedText = buildString {
            if (hwpText.isNotEmpty()) append("=== HWP 파일 내용 ===\n$hwpText\n\n")
            if (pdfText.isNotEmpty()) append("=== PDF 파일 내용 ===\n$pdfText\n\n")
        }

        if (combinedText.isNotEmpty()) {
            Log.d("FILE_PROCESS", "통합 텍스트 길이: ${combinedText.length}")
            extractDataWithAI(combinedText)
            if (hwpText.isNotEmpty()) parseHwpData(hwpText)
            showToast("파일 처리 완료")
        } else {
            showToast("처리할 파일이 없습니다")
        }
    }

    // ============= HWP 텍스트 추출 =============
    private fun extractHwpText(uri: Uri): String {
        val sb = StringBuilder()
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val hwpFile = HWPReader.fromInputStream(inputStream)
                hwpFile?.bodyText?.sectionList?.forEach { section ->
                    section.paragraphs.forEach { para ->
                        para.controlList?.forEach { control ->
                            if (control.type == ControlType.Table) {
                                val table = control as ControlTable
                                table.rowList.forEach { row ->
                                    val rowText = StringBuilder()
                                    row.cellList.forEach { cell ->
                                        val cellText = StringBuilder()
                                        cell.paragraphList?.paragraphs?.forEach { cellPara ->
                                            cellPara.text?.getNormalString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                                                cellText.append(it)
                                            }
                                        }
                                        if (cellText.isNotEmpty()) {
                                            if (rowText.isNotEmpty()) rowText.append("\t")
                                            rowText.append(cellText)
                                        }
                                    }
                                    if (rowText.isNotEmpty()) sb.append(rowText).append("\n")
                                }
                            }
                        }
                        para.text?.getNormalString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            sb.append(it).append("\n")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HWP_EXTRACT", "HWP 추출 실패: ${e.message}")
        }
        return sb.toString()
    }

    // ============= PDF 텍스트 추출 =============
    private fun extractPdfText(uri: Uri, password: String = ""): String {
        val sb = StringBuilder()
        try {
            // 1차: 비밀번호 없이 시도
            var document: com.tom_roush.pdfbox.pdmodel.PDDocument? = null
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputStream)
                    Log.d("PDF_EXTRACT", "비밀번호 없이 열기 성공")
                }
            } catch (e: Exception) {
                Log.d("PDF_EXTRACT", "비밀번호 없이 실패: ${e.message}")
                // 2차: 비밀번호로 재시도
                if (password.isNotEmpty()) {
                    try {
                        contentResolver.openInputStream(uri)?.use { retryStream ->
                            document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(retryStream, password)
                            Log.d("PDF_EXTRACT", "비밀번호로 열기 성공")
                        }
                    } catch (e2: Exception) {
                        Log.e("PDF_EXTRACT", "비밀번호로도 실패: ${e2.message}")
                    }
                }
            }

            if (document != null) {
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                val text = stripper.getText(document!!)
                sb.append(text)
                document!!.close()
                Log.d("PDF_EXTRACT", "PDF 텍스트 추출 완료: ${text.length}자")
            }
        } catch (e: Exception) {
            Log.e("PDF_EXTRACT", "PDF 추출 실패: ${e.message}")
            e.printStackTrace()
        }
        return sb.toString()
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) result = cursor.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }

    // ============= 기존 HWP 읽기 (단일 파일) =============
    private fun readHwpFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: run {
                showToast("파일을 열 수 없습니다.")
                return
            }
            val tempFile = File(cacheDir, "temp.hwp")
            FileOutputStream(tempFile).use { fos ->
                val buffer = ByteArray(1024)
                var length: Int
                while (inputStream.read(buffer).also { length = it } > 0) {
                    fos.write(buffer, 0, length)
                }
            }
            inputStream.close()

            val hwpFile = HWPReader.fromFile(tempFile.absolutePath) ?: run {
                showToast("HWP 파일을 읽을 수 없습니다.")
                return
            }
            val tableText = extractTableData(hwpFile)
            processHwpText(tableText.toString())
            tempFile.delete()
        } catch (e: Exception) {
            showToast("HWP 파일 읽기 오류: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun extractTableData(hwpFile: HWPFile): StringBuilder {
        val sb = StringBuilder()
        try {
            var sectionCount = 0
            for (section in hwpFile.bodyText.sectionList) {
                sectionCount++
                var paraCount = 0
                for (paragraph in section.paragraphs) {
                    paraCount++
                    val paraText = extractParagraphText(paragraph)
                    if (paraText.isNotBlank()) {
                        sb.append(paraText.trim()).append("\n")
                        Log.d("HWP_EXTRACT", "문단 $paraCount: ${paraText.trim()}")
                    }
                    paragraph.controlList?.forEach { control ->
                        if (control != null) {
                            Log.d("HWP_EXTRACT", "컨트롤 타입: ${control.type}")
                            if (control.type == ControlType.Table) {
                                val table = control as ControlTable
                                sb.append("[표 시작]\n")
                                for (row in table.rowList) {
                                    val rowText = StringBuilder()
                                    for (cell in row.cellList) {
                                        val cellText = StringBuilder()
                                        for (cellPara in cell.paragraphList) {
                                            val text = extractParagraphText(cellPara)
                                            if (text.isNotBlank()) cellText.append(text.trim()).append(" ")
                                        }
                                        rowText.append(cellText.toString().trim()).append("\t")
                                    }
                                    val rowStr = rowText.toString().trim()
                                    if (rowStr.isNotEmpty()) {
                                        sb.append(rowStr).append("\n")
                                        Log.d("HWP_EXTRACT", "표 행: $rowStr")
                                    }
                                }
                                sb.append("[표 끝]\n\n")
                            }
                        }
                    }
                }
                Log.d("HWP_EXTRACT", "섹션 $sectionCount 완료, 문단 수: $paraCount")
            }
            Log.d("HWP_EXTRACT", "총 섹션 수: $sectionCount")
        } catch (e: Exception) {
            sb.append("[오류: ${e.message}]\n")
            Log.e("HWP_EXTRACT", "추출 오류", e)
        }
        return sb
    }

    private fun extractParagraphText(paragraph: Paragraph?): String {
        if (paragraph?.text == null) return ""
        return try {
            val paraText = paragraph.text
            val text = StringBuilder()
            for (hwpChar in paraText.charList) {
                if (hwpChar.type == HWPCharType.Normal) {
                    text.append((hwpChar as HWPCharNormal).code.toChar())
                }
            }
            text.toString()
        } catch (e: Exception) { "" }
    }

    private fun processHwpText(text: String) {
        if (text.isEmpty()) {
            showToast("HWP 파일에서 텍스트를 찾을 수 없습니다.")
            return
        }
        Log.d("HWP_DATA", "추출된 텍스트:\n$text")
        extractDataWithAI(text)
        parseHwpData(text)
        showToast("HWP 파일 읽기 완료")
    }

    // ============= 핵심 파싱 로직 (AI값 기반) =============
    private fun parseHwpData(text: String) {
        val lines = text.split("\n")

        // AI에서 가져올 값 (기본값)
        // ============= AI에서 가져올 값 =============
        var income = aiIncome
        var targetDebt = aiTargetDebt
        var netProperty = aiProperty

        // ★ AI 추출 값 직접 사용 (로직에서 중복 계산하지 않음)
        val recentDebtFromAI = aiRecentDebt           // 6개월 이내 채무 (AI)
        val majorCreditorFromAI = aiMajorCreditor      // 과반 채권사명 (AI)
        val majorCreditorDebtFromAI = aiMajorCreditorDebt // 과반 채권사 채무 (AI)
        var hasBusinessHistory = aiHasBusinessHistory   // 사업자 이력 (AI)
        var businessStartYear = aiBusinessStartYear    // 개업 년도 (AI)
        var businessEndYear = aiBusinessEndYear        // 폐업 년도 (AI)

        // 보조 정보 (텍스트에서 파싱)
        var name = ""
        var region = ""
        var minorChildren = 0
        var collegeChildren = 0
        var parentCount = 0
        var hasSpouse = false
        var isDivorced = false
        var delinquentDays = 0
        var hasDischarge = false
        var dischargeYear = 0
        var dischargeMonth = 0
        var hasShinbokwiHistory = false
        var shinbokwiDebt = 0  // 신복위 채무 금액 (만원)
        var isBusinessOwner = false
        var hasBusinessLoan = false
        var hasGambling = false
        var hasStock = false
        var hasCrypto = false
        var carValue = 0
        var carTotalSise = 0
        var carTotalLoan = 0
        var carMonthlyPayment = 0
        var carCount = 0
        var spouseSecret = false  // 배우자 모르게 진행
        var familySecret = false  // 가족 모르게
        var hasAuction = false    // 경매 여부
        var hasCivilCase = false   // 민사/소송 여부
        var civilAmount = 0        // 민사 소송금액 (만원)
        var hasUsedCarInstallment = false  // 중고차 할부
        var hasHealthInsuranceDebt = false  // 건강보험 체납
        var jeonseNoJilgwon = false  // 전세대출 질권설정x → 대상채무 포함
        var excludedSeqNumbers = mutableSetOf<Int>()  // 대출과목에서 제외할 순번
        var includedSeqNumbers = mutableSetOf<Int>()  // 강제 포함할 순번 (순번N 신용대출)
        var hasOngoingProcess = false  // 다른 채무조정 진행 중
        var hasPaymentOrder = false  // 지급명령 받음
        var hasWorkoutExpired = false  // 워크아웃 실효
        var hasPersonalRecovery = false  // 개인회생 기록
        var personalRecoveryYear = 0
        var personalRecoveryMonth = 0
        var wantsCarSale = false  // 차량 처분 의사
        var nonAffiliatedDebt = aiNonAffiliatedDebt
        var studentLoanTotal = 0   // 학자금 합계 (천원)
        var tableDebtTotal = 0     // 표 전체 합계 (천원, 제외항목 포함)
        val specialNotesList = ArrayList<String>()

        // ★ 6개월 비율: AI 추출값 기반으로 계산
        var recentDebtRatio = if (targetDebt > 0 && recentDebtFromAI > 0) recentDebtFromAI.toDouble() / targetDebt * 100 else 0.0
        // ★ 과반 비율: AI 추출값 기반으로 계산
        var majorCreditorRatio = if (targetDebt > 0 && majorCreditorDebtFromAI > 0) majorCreditorDebtFromAI.toDouble() / targetDebt * 100 else 0.0

        var lastCreditDate = ""

        // ============= 보조 정보 추출 =============
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val lineNoSpace = line.replace(" ", "")

            // 이름 추출
            if (name.isEmpty() && line.length in 2..20) {
                // 필드 레이블은 이름이 아님
                val isFieldLabel = lineNoSpace.contains("소득") || lineNoSpace.contains("연락") || lineNoSpace.contains("주민") ||
                        lineNoSpace.contains("결혼") || lineNoSpace.contains("지역") || lineNoSpace.contains("재직") ||
                        lineNoSpace.contains("재산") || lineNoSpace.contains("차량") || lineNoSpace.contains("연봉") ||
                        lineNoSpace.contains("보험") || lineNoSpace.contains("채무") || lineNoSpace.contains("대출") ||
                        lineNoSpace.contains("특이") || lineNoSpace.contains("장애") || lineNoSpace.contains("부모") ||
                        lineNoSpace.contains("배우자") || lineNoSpace.contains("사대") || lineNoSpace.contains("카드") ||
                        lineNoSpace.contains("요약") || lineNoSpace.contains("계획")
                if (!isFieldLabel) {
                    // 괄호 앞까지만 추출 (예: "김수지(동승,당근)" → "김수지")
                    val cleanLine = line.split("(", "（")[0].trim()
                    val nameParts = cleanLine.split("\\s+".toRegex())
                    if (nameParts.isNotEmpty() && nameParts[0].matches("^[가-힣]{2,5}$".toRegex())) {
                        name = nameParts[0]
                    } else if (cleanLine.matches("^[가-힣]+$".toRegex()) && cleanLine.length <= 5) {
                        name = cleanLine
                    }
                }
            }

            // 지역
            if (lineNoSpace.contains("지역") && line.contains(":")) {
                region = line.substringAfter(":").trim()
            }

            // 재직/직업
            if (lineNoSpace.contains("재직") && line.contains(":")) {
                val job = line.substringAfter(":").trim()
                if (job.contains("사업자") || job.contains("개인사업") || job.contains("자영업") || job.contains("음식점")) {
                    isBusinessOwner = true
                }
            }

            // ============= 전역 감지 (줄 위치 무관, 2줄 이어쓰기 대응) =============

            // ★ 사업자 이력: AI가 추출하지만, 로직에서도 보조 감지 (AI값이 없을 때 폴백)
            if (!hasBusinessHistory) {
                if (lineNoSpace.contains("자영업") || lineNoSpace.contains("개인사업") ||
                    lineNoSpace.contains("폐업") || lineNoSpace.contains("사업자") && lineNoSpace.contains("개시") ||
                    lineNoSpace.contains("사업자") && (lineNoSpace.contains("년") || lineNoSpace.contains("매출"))) {

                    if (!specialNotesList.any { it.contains("사업자") || it.contains("자영업") || it.contains("폐업") }) {
                        specialNotesList.add("사업자이력")
                    }
                    // 개업/폐업 년도 파싱 (AI 값이 없을 때만)
                    if (businessStartYear == 0) {
                        val bizYearPattern = Pattern.compile("(\\d{2,4})년\\s*(?:\\d{1,2}월\\s*)?개업")
                        val bizYearMatcher = bizYearPattern.matcher(line)
                        if (bizYearMatcher.find()) {
                            val y = bizYearMatcher.group(1)!!.toInt()
                            businessStartYear = if (y < 100) 2000 + y else y
                        }
                    }
                    if (businessEndYear == 0) {
                        val bizEndPattern = Pattern.compile("(\\d{2,4})년\\s*(?:\\d{1,2}월\\s*)?폐업")
                        val bizEndMatcher = bizEndPattern.matcher(line)
                        if (bizEndMatcher.find()) {
                            val y = bizEndMatcher.group(1)!!.toInt()
                            businessEndYear = if (y < 100) 2000 + y else y
                        }
                        if (businessStartYear > 0 && businessEndYear == 0 && !lineNoSpace.contains("폐업")) {
                            businessEndYear = java.time.LocalDate.now().year
                        }
                    }

                    if (businessStartYear in 2019..2024 || (businessStartYear < 2019 && businessEndYear >= 2019)) {
                        hasBusinessHistory = true
                    }

                    Log.d("HWP_PARSE", "사업자 이력 보조 감지: $line (개업=$businessStartYear, 폐업=$businessEndYear)")
                }
            }

            // 도박/주식/코인 (변제율 조건) - 사용처 줄 외에도 감지
            if (lineNoSpace.contains("도박")) { hasGambling = true }
            if (lineNoSpace.contains("주식") || lineNoSpace.contains("전액주식")) { hasStock = true }
            if (lineNoSpace.contains("코인") || lineNoSpace.contains("비트코인") || lineNoSpace.contains("가상화폐")) { hasCrypto = true }

            // 배우자 모르게 진행 (필수일 때만 단기 불가 조건)
            if (lineNoSpace.contains("배우자") && lineNoSpace.contains("모르게")) {
                // "필수x", "필수 x", "필수아님", "필수없" 등이면 필수 아님 → 무시
                val isNotMandatory = lineNoSpace.contains("필수x") || lineNoSpace.contains("필수X") ||
                        lineNoSpace.contains("필수아님") || lineNoSpace.contains("필수없") ||
                        line.contains("필수 x") || line.contains("필수 X") || line.contains("필수 아님")
                if (!isNotMandatory) {
                    spouseSecret = true
                    Log.d("HWP_PARSE", "배우자 모르게 진행 감지 (필수)")
                } else {
                    Log.d("HWP_PARSE", "배우자 모르게 감지 but 필수 아님 → 무시")
                }
            }
            // 가족 모르게 (미혼이면 단기 불가 아님, 특이사항만)
            if (lineNoSpace.contains("가족") && lineNoSpace.contains("모르게") && !lineNoSpace.contains("배우자")) {
                familySecret = true
                Log.d("HWP_PARSE", "가족 모르게 감지")
            }

            // 경매/압류 (단기 불가 조건)
            if (lineNoSpace.contains("경매") || lineNoSpace.contains("압류") || lineNoSpace.contains("강제집행")) {
                hasAuction = true
                Log.d("HWP_PARSE", "경매/압류 감지: $line")
            }

            // 민사/소송 감지 (대상채무 제외, 따로변제)
            if (lineNoSpace.contains("민사") || lineNoSpace.contains("소송")) {
                hasCivilCase = true
                // 금액 파싱
                val civilAmountM = Pattern.compile("(\\d+)만").matcher(lineNoSpace)
                if (civilAmountM.find()) {
                    civilAmount = civilAmountM.group(1)!!.toInt()
                }
                Log.d("HWP_PARSE", "민사/소송 감지: $line, 금액=${civilAmount}만")
            }

            // 중고차 할부 감지 (따로 납부)
            if (lineNoSpace.contains("중고차") && lineNoSpace.contains("할부")) {
                hasUsedCarInstallment = true
                Log.d("HWP_PARSE", "중고차 할부 감지: $line")
            }

            // 건강보험 체납 감지 (따로 변제)
            if (lineNoSpace.contains("건강보험") && (lineNoSpace.contains("체납") || lineNoSpace.contains("밀린") || lineNoSpace.contains("미납"))) {
                hasHealthInsuranceDebt = true
                Log.d("HWP_PARSE", "건강보험 체납 감지: $line")
            }

            // 전세대출 질권설정x 감지 → 대상채무 포함
            if (lineNoSpace.contains("전세대출") && (lineNoSpace.contains("질권설정x") || lineNoSpace.contains("질권설정X") ||
                        lineNoSpace.contains("질권x") || lineNoSpace.contains("질권X") ||
                        line.contains("질권설정 x") || line.contains("질권 x"))) {
                jeonseNoJilgwon = true
                Log.d("HWP_PARSE", "전세대출 질권설정x 감지 → 대상채무 포함: $line")
            }

            // "순번N. 차량담보" 패턴 → 해당 순번 대상채무 제외 (오타 "차랑" 대응)
            val seqExcludeM = Pattern.compile("순번(\\d+)[.．]\\s*(?:차량|차랑)담보").matcher(lineNoSpace)
            if (seqExcludeM.find()) {
                val seqNum = seqExcludeM.group(1)!!.toInt()
                excludedSeqNumbers.add(seqNum)
                Log.d("HWP_PARSE", "순번 제외 감지: 순번$seqNum 차량담보")
            }

            // "순번N 신용대출" 패턴 → 해당 순번은 (290)이어도 대상채무에 포함
            val seqIncludeM = Pattern.compile("순번(\\d+)[.．]?\\s*신용대출").matcher(lineNoSpace)
            if (seqIncludeM.find()) {
                val seqNum = seqIncludeM.group(1)!!.toInt()
                includedSeqNumbers.add(seqNum)
                Log.d("HWP_PARSE", "순번 강제 포함: 순번$seqNum 신용대출")
            }

            // 연체 감지 (줄 위치 무관) - "연체없음", "연체x" 등은 제외
            if (line.contains("연체") && !lineNoSpace.contains("연체없음") && !lineNoSpace.contains("연체없") &&
                !lineNoSpace.contains("연체x") && !lineNoSpace.contains("연체X") &&
                !lineNoSpace.contains("연체안") && !lineNoSpace.contains("미연체")) {
                if (lineNoSpace.contains("오늘부터") || lineNoSpace.contains("막연체") ||
                    lineNoSpace.contains("이제연체") || lineNoSpace.contains("방금연체")) {
                    if (delinquentDays < 1) delinquentDays = 1
                }
                var m = Pattern.compile("(\\d+)개월").matcher(line)
                if (m.find()) delinquentDays = maxOf(delinquentDays, m.group(1)!!.toInt() * 30)
                m = Pattern.compile("(\\d+)일").matcher(line)
                if (m.find()) delinquentDays = maxOf(delinquentDays, m.group(1)!!.toInt())
                // N년 연체 파싱 (날짜 패턴 "N년 N월"과 구분)
                val yearDelinqM = Pattern.compile("(\\d+)년\\s*(?:연체|넘게|이상|째|정도|쯤)").matcher(line)
                if (yearDelinqM.find()) delinquentDays = maxOf(delinquentDays, yearDelinqM.group(1)!!.toInt() * 365)
                // "N년부터 연체" 패턴 (예: "21년부터 연체", "2021년부터 연체")
                val fromYearDelinqM = Pattern.compile("(\\d{2,4})년\\s*부터\\s*연체").matcher(line)
                if (fromYearDelinqM.find()) {
                    var dYear = fromYearDelinqM.group(1)!!.toInt()
                    if (dYear < 100) dYear += 2000
                    val delinqStart = Calendar.getInstance().apply { set(dYear, 0, 1) }
                    val now = Calendar.getInstance()
                    val diffDays = ((now.timeInMillis - delinqStart.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
                    if (diffDays > 0) {
                        delinquentDays = maxOf(delinquentDays, diffDays)
                        Log.d("HWP_PARSE", "N년부터 연체: ${dYear}년부터 → ${diffDays}일")
                    }
                }
                // "N년 N월부터 연체" 또는 "N월 N일부터 연체" 패턴 → 날짜 기반 계산
                val delinqDateM = Pattern.compile("(\\d{2,4})년\\s*(\\d{1,2})월").matcher(line)
                if (delinqDateM.find()) {
                    var dYear = delinqDateM.group(1)!!.toInt()
                    if (dYear < 100) dYear += 2000
                    val dMonth = delinqDateM.group(2)!!.toInt()
                    val delinqStart = Calendar.getInstance().apply { set(dYear, dMonth - 1, 1) }
                    val now = Calendar.getInstance()
                    val diffDays = ((now.timeInMillis - delinqStart.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
                    if (diffDays > 0) {
                        delinquentDays = maxOf(delinquentDays, diffDays)
                        Log.d("HWP_PARSE", "연체 날짜 기반: ${dYear}년 ${dMonth}월부터 → ${diffDays}일")
                    }
                }
                // "N월 N일부터 연체" (올해 기준)
                val delinqDateM2 = Pattern.compile("(\\d{1,2})월\\s*(\\d{1,2})일.*(?:부터|연체)").matcher(line)
                if (delinqDateM2.find() && !delinqDateM.find(0)) {
                    val dMonth = delinqDateM2.group(1)!!.toInt()
                    val dDay = delinqDateM2.group(2)!!.toInt()
                    val now = Calendar.getInstance()
                    val delinqStart = Calendar.getInstance().apply { set(now.get(Calendar.YEAR), dMonth - 1, dDay) }
                    if (delinqStart.after(now)) delinqStart.add(Calendar.YEAR, -1)
                    val diffDays = ((now.timeInMillis - delinqStart.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
                    if (diffDays > 0) {
                        delinquentDays = maxOf(delinquentDays, diffDays)
                        Log.d("HWP_PARSE", "연체 월일 기반: ${dMonth}월 ${dDay}일부터 → ${diffDays}일")
                    }
                }
                if (delinquentDays == 0 && (lineNoSpace.contains("연체중") || lineNoSpace.contains("연체"))) {
                    // 한글 기간 파싱
                    if (lineNoSpace.contains("일주일")) delinquentDays = 7
                    else if (lineNoSpace.contains("이주일") || lineNoSpace.contains("2주일") || lineNoSpace.contains("2주")) delinquentDays = 14
                    else if (lineNoSpace.contains("삼주일") || lineNoSpace.contains("3주일") || lineNoSpace.contains("3주")) delinquentDays = 21
                    else delinquentDays = 30
                }
            }

            // 신복위 이력 감지 (줄 위치 무관)
            if ((lineNoSpace.contains("신복위") || lineNoSpace.contains("신용회복") || lineNoSpace.contains("신속채무조정")) &&
                !lineNoSpace.contains("상담") && !lineNoSpace.contains("문의") && !lineNoSpace.contains("알아보")) hasShinbokwiHistory = true

            // 신복(위) 채무 금액 파싱 (요약: "신복 6693만", "신복위 8887만")
            if ((lineNoSpace.startsWith("신복") || lineNoSpace.contains("신복위")) && lineNoSpace.contains("만")) {
                val shinbokM = Pattern.compile("신복(?:위)?\\s*(\\d+)만").matcher(lineNoSpace)
                if (shinbokM.find()) {
                    shinbokwiDebt = shinbokM.group(1)!!.toInt()
                    hasShinbokwiHistory = true
                    Log.d("HWP_PARSE", "신복위 채무 금액: ${shinbokwiDebt}만")
                }
            }

            // 다른 채무조정 진행 중 감지 (신속/회생/워크아웃 등 진행중)
            if (lineNoSpace.contains("진행중") || lineNoSpace.contains("진행중")) {
                if (lineNoSpace.contains("신속") || lineNoSpace.contains("회생") || lineNoSpace.contains("워크아웃") ||
                    lineNoSpace.contains("신복위") || lineNoSpace.contains("채무조정")) {
                    hasOngoingProcess = true
                    Log.d("HWP_PARSE", "다른 단계 진행 중 감지: $line")
                }
            }

            // 지급명령 받음 감지 ("안받음"은 제외)
            if (lineNoSpace.contains("지급명령") && !lineNoSpace.contains("안받음") && !lineNoSpace.contains("안받")) {
                hasPaymentOrder = true
                Log.d("HWP_PARSE", "지급명령 받음 감지: $line")
            }

            // 유예기간 감지 (상환내역서 텍스트에서 직접 파싱)
            if (lineNoSpace.contains("유예기간") || lineNoSpace.contains("거치기간")) {
                val deferM = Pattern.compile("(\\d+)\\s*개?월").matcher(line)
                if (deferM.find()) {
                    val months = deferM.group(1)!!.toInt()
                    if (months > aiDefermentMonths) {
                        aiDefermentMonths = months
                        Log.d("HWP_PARSE", "유예기간 감지: ${months}개월")
                    }
                }
            }

            // 실효 감지 → 장기연체자 (줄 위치 무관)
            // "워크아웃 실효", "신복위 실효" 등 (개인회생 면책과 같은 줄에 있을 수 있음)
            if (lineNoSpace.contains("실효")) {
                delinquentDays = maxOf(delinquentDays, 1095)
                if (lineNoSpace.contains("워크아웃") || lineNoSpace.contains("워크") || lineNoSpace.contains("신복위")) {
                    hasWorkoutExpired = true
                }
                Log.d("HWP_PARSE", "실효 감지 → 장기연체자: $line, 워크아웃실효=$hasWorkoutExpired")
            }

            // 면책 감지 (줄 위치 무관)
            if (lineNoSpace.contains("면책")) {
                hasDischarge = true
                var m = Pattern.compile("(\\d{2})년\\s*(\\d{1,2})월?").matcher(line)
                if (m.find()) {
                    dischargeYear = 2000 + m.group(1)!!.toInt()
                    if (m.group(2) != null) dischargeMonth = m.group(2)!!.toInt()
                } else {
                    m = Pattern.compile("(20\\d{2})\\.?(\\d{1,2})?").matcher(line)
                    if (m.find()) {
                        dischargeYear = m.group(1)!!.toInt()
                        if (m.group(2) != null) dischargeMonth = m.group(2)!!.toInt()
                    }
                }
                // "21. 6월" 또는 "21.6월" 형식 대응
                if (dischargeYear == 0) {
                    m = Pattern.compile("(\\d{2})[.．]\\s*(\\d{1,2})월?").matcher(line)
                    if (m.find()) {
                        dischargeYear = 2000 + m.group(1)!!.toInt()
                        if (m.group(2) != null) dischargeMonth = m.group(2)!!.toInt()
                    }
                }
            }

            // 개인회생 감지 (면책이 안 적혀있어도 개인회생이 있고 진행중이 아니면 면책)
            if (lineNoSpace.contains("개인회생")) {
                hasPersonalRecovery = true
                var m = Pattern.compile("(\\d{2})년\\s*(\\d{1,2})월?").matcher(line)
                if (m.find()) {
                    personalRecoveryYear = 2000 + m.group(1)!!.toInt()
                    if (m.group(2) != null) personalRecoveryMonth = m.group(2)!!.toInt()
                } else {
                    m = Pattern.compile("(20\\d{2})\\.?(\\d{1,2})?").matcher(line)
                    if (m.find()) {
                        personalRecoveryYear = m.group(1)!!.toInt()
                        if (m.group(2) != null) personalRecoveryMonth = m.group(2)!!.toInt()
                    }
                }
                Log.d("HWP_PARSE", "개인회생 감지: $line (${personalRecoveryYear}년 ${personalRecoveryMonth}월)")
            }

            // 차량 처분 의사 (줄 위치 무관)
            if (lineNoSpace.contains("차량처분") || lineNoSpace.contains("차량을처분") || lineNoSpace.contains("차처분") ||
                (lineNoSpace.contains("차량") && lineNoSpace.contains("처분"))) {
                wantsCarSale = true
                Log.d("HWP_PARSE", "차량 처분 의사 감지: $line")
            }

            // 결혼/자녀
            if (lineNoSpace.contains("결혼여부") || lineNoSpace.contains("결혼")) {
                if (lineNoSpace.contains("기혼")) hasSpouse = true
                if (lineNoSpace.contains("이혼")) isDivorced = true
                if ((lineNoSpace.contains("비양육") || lineNoSpace.contains("전처") || lineNoSpace.contains("전남편") || lineNoSpace.contains("양육중")) &&
                    !lineNoSpace.contains("양육비")) {
                    minorChildren = 0
                } else if (lineNoSpace.contains("미성년")) {
                    val m = Pattern.compile("미성년\\s*(\\d+)").matcher(line)
                    if (m.find()) minorChildren = m.group(1)!!.toInt()
                }
                // 태아도 미성년자녀로 포함
                if (lineNoSpace.contains("태아") || lineNoSpace.contains("임신")) {
                    val m = Pattern.compile("태아\\s*(\\d+)").matcher(line)
                    if (m.find()) {
                        minorChildren += m.group(1)!!.toInt()
                    } else {
                        minorChildren += 1
                    }
                    Log.d("HWP_PARSE", "태아 감지: 미성년 $minorChildren 명으로 포함")
                }
                if (line.contains("대학생")) {
                    val m = Pattern.compile("대학생\\s*(\\d+)").matcher(line)
                    if (m.find()) collegeChildren = m.group(1)!!.toInt()
                }
            }

            // 60세 이상 부모
            if (lineNoSpace.contains("60세") || lineNoSpace.contains("만60세")) {
                var count = 0
                val afterColon = if (line.contains(":")) line.substringAfter(":") else line
                if ((afterColon.contains("부") && !afterColon.contains("부별세") && !afterColon.contains("부-별세")) ||
                    (afterColon.contains("모") && !afterColon.contains("모별세") && !afterColon.contains("모-별세"))) {
                    if (afterColon.contains("부") && !afterColon.contains("부별세") && !afterColon.contains("부-별세") &&
                        !afterColon.contains("부 별세") && !afterColon.contains("부x") && !afterColon.contains("부X")) count++
                    if (afterColon.contains("모") && !afterColon.contains("모별세") && !afterColon.contains("모-별세") &&
                        !afterColon.contains("모 별세") && !afterColon.contains("모x") && !afterColon.contains("모X") &&
                        !afterColon.contains("모르게")) count++
                } else if (afterColon.contains("별세") && afterColon.contains("2분")) {
                    count = 0
                } else if (afterColon.trim().equals("x", true)) {
                    count = 0
                }
                if (count > 0) parentCount = count
            }

            // 대출사용처 (전역 감지로 이동됨 - 여기서는 추가 키워드만)
            // 도박/주식/코인은 위 전역 감지에서 처리

            // 특이사항
            if (lineNoSpace.contains("특이사항") || lineNoSpace.contains("특이:")) {
                if (line.contains(":")) {
                    val content = line.substringAfter(":").trim()
                    if (content.isNotEmpty() && !content.equals("x", true)) {
                        content.split("[,，/]".toRegex()).forEach { part ->
                            val trimmed = part.trim()
                            // 자동 감지 항목은 중복 방지
                            val isAutoDetected = trimmed.equals("x", true) ||
                                    trimmed.contains("모르게") || trimmed.contains("경매") || trimmed.contains("압류") ||
                                    trimmed.contains("도박") || trimmed.contains("주식") || trimmed.contains("코인") ||
                                    trimmed.contains("민사") || trimmed.contains("소송") || trimmed.contains("지급명령") ||
                                    trimmed.contains("건강보험")
                            if (trimmed.isNotEmpty() && !isAutoDetected) specialNotesList.add(trimmed)
                        }
                    }
                }
            }

            // 채무조정 이력 (채무조정: 줄에서 연도 추출)
            if (lineNoSpace.contains("채무조정") && line.contains(":")) {
                val content = line.substringAfter(":").trim()
                if (!content.equals("x", true) && content.isNotEmpty()) {
                    if (content.contains("면책")) {
                        hasDischarge = true
                        var m = Pattern.compile("(\\d{2})년").matcher(content)
                        if (m.find()) dischargeYear = 2000 + m.group(1)!!.toInt()
                        else { m = Pattern.compile("(20\\d{2})").matcher(content); if (m.find()) dischargeYear = m.group(1)!!.toInt() }
                    }
                    // 실효 감지 → 장기연체자 (1095일)
                    if (content.contains("실효")) {
                        delinquentDays = maxOf(delinquentDays, 1095)
                        if (content.contains("워크아웃") || content.contains("워크") || content.contains("신복위")) {
                            hasWorkoutExpired = true
                        }
                        Log.d("HWP_PARSE", "실효 감지 → 장기연체자: $content, 워크아웃실효=$hasWorkoutExpired")
                    }
                    // 일수 기입 → 연체기간 설정
                    val daysMatcher = Pattern.compile("(\\d+)일").matcher(content)
                    if (daysMatcher.find()) {
                        val days = daysMatcher.group(1)!!.toInt()
                        delinquentDays = maxOf(delinquentDays, days)
                        Log.d("HWP_PARSE", "채무조정 연체일수 감지: ${days}일")
                    }
                }
            }

            // 차량 파싱 (재산은 AI가 처리하지만, 차량 대수/처분 판단용)
            var isCarLine = lineNoSpace.contains("차량") || (line.contains("자동차") && !lineNoSpace.contains("자동차금융")) ||
                    (Pattern.compile("\\d{2}년식").matcher(lineNoSpace).find() &&
                            (lineNoSpace.contains("시세") || lineNoSpace.contains("본인명의") || lineNoSpace.contains("배우자명의")))

            if (isCarLine && (lineNoSpace.contains("차량담보") || lineNoSpace.contains("차량할부"))) {
                val additionalLoan = extractAmount(line)
                if (additionalLoan > 0) carTotalLoan += additionalLoan
                isCarLine = false
            }

            if (isCarLine) {
                if (lineNoSpace.contains("장기렌트") || lineNoSpace.contains("렌트")) {
                    val m = Pattern.compile("월\\s*(\\d+)만").matcher(line)
                    if (m.find()) carMonthlyPayment = m.group(1)!!.toInt()
                    continue
                }
                if (line.contains(": x") || line.contains(":x") || line.endsWith(": x")) continue

                var carSise = extractAmountAfterKeyword(line, "시세")
                if (carSise == 0) carSise = extractAmount(line)
                var carLoan = 0
                if (lineNoSpace.contains("담보") || lineNoSpace.contains("할부")) {
                    carLoan = maxOf(extractAmountAfterKeyword(line, "담보"), extractAmountAfterKeyword(line, "할부"))
                }
                if (lineNoSpace.contains("배우자명의") || lineNoSpace.contains("배우자")) {
                    carSise /= 2; carLoan /= 2
                }
                carTotalSise += carSise; carTotalLoan += carLoan
                val monthlyM = Pattern.compile("월\\s*(\\d+)만").matcher(line)
                if (monthlyM.find()) carMonthlyPayment += monthlyM.group(1)!!.toInt()
                carCount++
                Log.d("HWP_PARSE", "차량 파싱: 시세=$carSise, 담보=$carLoan, 누적시세=$carTotalSise, 누적담보=$carTotalLoan")
            }

            // 6개월 이내 채무 파싱
            var loanYear = 0; var loanMonth = 0; var loanDay = 0
            val dateMatcher = Pattern.compile("(\\d{4})[.\\-](\\d{1,2})[.\\-](\\d{1,2})").matcher(line)
            val dateMatcher2 = Pattern.compile("(\\d{2})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일?").matcher(line)
            val dateMatcher3 = Pattern.compile("(\\d{2})년\\s*(\\d{1,2})월").matcher(line)
            val dateMatcher4 = Pattern.compile("^(\\d{2})년\\s+").matcher(line)

            if (dateMatcher.find()) {
                loanYear = dateMatcher.group(1)!!.toInt(); loanMonth = dateMatcher.group(2)!!.toInt(); loanDay = dateMatcher.group(3)!!.toInt()
            } else if (dateMatcher2.find()) {
                loanYear = 2000 + dateMatcher2.group(1)!!.toInt(); loanMonth = dateMatcher2.group(2)!!.toInt(); loanDay = dateMatcher2.group(3)!!.toInt()
            } else if (dateMatcher3.find()) {
                loanYear = 2000 + dateMatcher3.group(1)!!.toInt(); loanMonth = dateMatcher3.group(2)!!.toInt(); loanDay = 15
            } else if (dateMatcher4.find()) {
                loanYear = 2000 + dateMatcher4.group(1)!!.toInt(); loanMonth = 1; loanDay = 1
            }

            val hasFinancialKeyword = line.contains("은행") || line.contains("캐피탈") || line.contains("카드") ||
                    line.contains("금융") || line.contains("저축") || line.contains("보증") ||
                    line.contains("공사") || line.contains("재단") || line.contains("농협") ||
                    line.contains("신협") || line.contains("새마을") || line.contains("생명") ||
                    line.contains("화재") || line.contains("공단") || line.contains("대부") ||
                    line.contains("피에프씨") || line.contains("PFC") || line.contains("테크놀로지") ||
                    line.contains("머니무브") || line.contains("사금융") || line.contains("일수")

            if (loanYear > 0 && hasFinancialKeyword) {
                var debtAmount = 0
                if (line.contains("만")) {
                    debtAmount = extractAmount(line) * 10
                } else {
                    val commaM = Pattern.compile("([\\d,]+)$").matcher(line.trim())
                    if (commaM.find()) {
                        val numStr = commaM.group(1)!!.replace(",", "")
                        if (numStr.isNotEmpty()) debtAmount = numStr.toInt()
                    }
                }

                if (debtAmount > 0) {
                    // 학자금 합산 (비율 계산용)
                    if (lineNoSpace.contains("학자금") || lineNoSpace.contains("(150)") || lineNoSpace.contains("장학재단")) {
                        studentLoanTotal += debtAmount
                    }

                    // 표 전체 합산 (학자금 비율 계산용)
                    tableDebtTotal += debtAmount

                    // 사업자대출 감지
                    if (lineNoSpace.contains("개인사업자대출") || lineNoSpace.contains("운전자금") ||
                        lineNoSpace.contains("사업자대출") || lineNoSpace.contains("(1051)")) {
                        hasBusinessLoan = true
                    }
                    if ((lineNoSpace.contains("개인사업자대출") || lineNoSpace.contains("운전자금")) && loanYear in 2019..2024) {
                        hasBusinessHistory = true
                    }
                }
            }
        }

        // 미협약 비율 계산 (차감 전 대상채무 기준)
        // 방어: nonAffiliatedDebt가 targetDebt보다 크면 targetDebt로 제한
        if (nonAffiliatedDebt > targetDebt) nonAffiliatedDebt = targetDebt

        val originalTargetDebt = targetDebt  // ★ 과반 계산용 원래 대상채무 저장

        // 미협약 처리: 재산보다 적으면 따로 변제(차감), 많으면 대상채무에 포함
        // 단, 차감 후 재산초과가 되면 다시 대상채무에 포함
        val nonAffiliatedOver20: Boolean
        if (nonAffiliatedDebt > 0 && nonAffiliatedDebt <= netProperty) {
            // 미협약 ≤ 재산 → 따로 변제 시도
            val debtAfterDeduction = targetDebt - nonAffiliatedDebt
            if (debtAfterDeduction > 0 && debtAfterDeduction >= netProperty) {
                // 차감 후에도 재산초과 아님 → 따로 변제
                specialNotesList.add("미협약 ${nonAffiliatedDebt}만 따로 변제")
                targetDebt = debtAfterDeduction
                nonAffiliatedOver20 = false
                Log.d("HWP_CALC", "미협약 ${nonAffiliatedDebt}만 ≤ 재산 ${netProperty}만 → 따로 변제")
            } else {
                // 차감하면 재산초과 됨 → 대상채무에 포함 유지
                val nonAffiliatedRatio = if (targetDebt > 0) nonAffiliatedDebt.toDouble() / targetDebt * 100 else 0.0
                nonAffiliatedOver20 = nonAffiliatedRatio >= 20
                if (nonAffiliatedOver20) {
                    specialNotesList.add("미협약 ${String.format("%.0f", nonAffiliatedRatio)}% (신복위 불가)")
                } else {
                    specialNotesList.add("미협약 ${nonAffiliatedDebt}만 포함 (${String.format("%.0f", nonAffiliatedRatio)}%)")
                }
                Log.d("HWP_CALC", "미협약 차감 시 재산초과 → 대상채무에 포함 (${String.format("%.1f", nonAffiliatedRatio)}%)")
            }
        } else if (nonAffiliatedDebt > 0) {
            // 미협약 > 재산 → 대상채무에 포함 (차감 안 함)
            val nonAffiliatedRatio = if (targetDebt > 0) nonAffiliatedDebt.toDouble() / targetDebt * 100 else 0.0
            nonAffiliatedOver20 = nonAffiliatedRatio >= 20
            if (nonAffiliatedOver20) {
                specialNotesList.add("미협약 ${String.format("%.0f", nonAffiliatedRatio)}% (신복위 불가)")
            } else {
                specialNotesList.add("미협약 ${nonAffiliatedDebt}만 포함 (${String.format("%.0f", nonAffiliatedRatio)}%)")
            }
            Log.d("HWP_CALC", "미협약 ${nonAffiliatedDebt}만 > 재산 ${netProperty}만 → 대상 포함 (${String.format("%.1f", nonAffiliatedRatio)}%)")
        } else {
            nonAffiliatedOver20 = false
        }

        // ★ 6개월 비율: AI 값 기반 (로직에서 재계산하지 않음)
        // 미협약 처리로 targetDebt가 변경되었을 수 있으므로 재계산
        recentDebtRatio = if (targetDebt > 0 && recentDebtFromAI > 0) recentDebtFromAI.toDouble() / targetDebt * 100 else 0.0
        Log.d("HWP_CALC", "6개월 비율 (AI): ${recentDebtFromAI}만 / ${targetDebt}만 = ${String.format("%.1f", recentDebtRatio)}%")
        Log.d("HWP_CALC", "AI값: 소득=$income, 대상채무=$targetDebt, 재산=$netProperty")

        // 개인회생이 있고 회생 진행중이 아니면 면책으로 처리
        // "개인회생 진행중" 또는 "회생 진행중"이 명시적으로 있어야 회생 진행중
        // "워크진행중"은 워크아웃 진행중이므로 개인회생 면책과 무관
        val isRecoveryOngoing = hasPersonalRecovery && lines.any {
            val ns = it.replace(" ", "")
            (ns.contains("개인회생진행") || ns.contains("회생진행중") || ns.contains("개인회생진행중"))
        }
        if (hasPersonalRecovery && !isRecoveryOngoing && !hasDischarge) {
            hasDischarge = true
            if (personalRecoveryYear > 0) {
                dischargeYear = personalRecoveryYear
                dischargeMonth = personalRecoveryMonth
            }
            Log.d("HWP_CALC", "개인회생 → 면책 처리 (회생 진행중 아님): ${dischargeYear}년 ${dischargeMonth}월")
        }

        // 변제율 결정
        var repaymentRate = 90
        var rateReason = ""
        // 100%: 6개월 신규비율 80% 이상
        if (recentDebtRatio >= 80) {
            repaymentRate = 100; rateReason = "6개월비율 80%+"
        }
        // 95%: 투기성 사용 OR 6개월 신규비율 80% 직전 (70~80 미만)
        else if (hasGambling || hasStock || hasCrypto) {
            repaymentRate = 95
            rateReason = when { hasGambling -> "도박"; hasStock -> "주식"; else -> "코인" }
        } else if (recentDebtRatio >= 70) {
            repaymentRate = 95; rateReason = "6개월비율 고위험"
        }

        // 최저생계비 (2026년)
        val livingCostTable = intArrayOf(0, 154, 252, 322, 390, 453, 513)
        val householdForHoeseng = minOf(1 + minorChildren, 6)
        val livingCostHoeseng = livingCostTable[householdForHoeseng]
        val householdForShinbok = minOf(1 + minorChildren, 6)
        val livingCostShinbok = livingCostTable[householdForShinbok]

        // 단기(회생) 계산
        var shortTermBlocked = false
        var shortTermBlockReason = ""
        val currentYear = java.time.LocalDate.now().year
        val currentMonth = java.time.LocalDate.now().monthValue

        // 면책 5년 이내 판단 (년월 비교)
        val dischargeWithin5Years = hasDischarge && dischargeYear > 0 && (
                (currentYear - dischargeYear) < 5 ||
                        ((currentYear - dischargeYear) == 5 && dischargeMonth > 0 && currentMonth < dischargeMonth) ||
                        ((currentYear - dischargeYear) == 5 && dischargeMonth == 0)
                )

        if (dischargeWithin5Years) { shortTermBlocked = true; shortTermBlockReason = "면책 5년 이내" }
        if (netProperty > targetDebt && targetDebt > 0) { shortTermBlocked = true; if (shortTermBlockReason.isNotEmpty()) shortTermBlockReason += ", "; shortTermBlockReason += "재산초과" }
        // 배우자 모르게는 단기불가 아님, 단기 진단 옆에 (배우자 모르게) 표시
        // if (spouseSecret) { shortTermBlocked = true; ... }
        // 경매/압류는 특이사항에만 표시, 단기불가 조건 아님

        // 차량 처분 의사 + 재산초과로 단기 불가일 때 → 차량 처분 기준으로 단기 재계산
        var shortTermAfterCarSale = ""
        var shortTermCarSaleApplied = false
        if (wantsCarSale && shortTermBlocked && shortTermBlockReason == "재산초과" && carTotalLoan > 0) {
            val propertyAfterCarSale = netProperty - carValue
            val debtAfterCarSale = if (carTotalLoan > carTotalSise) {
                targetDebt + (carTotalLoan - carTotalSise)
            } else targetDebt
            Log.d("HWP_CALC", "차량처분 단기 검토: wantsCarSale=$wantsCarSale, carTotalLoan=$carTotalLoan, carTotalSise=$carTotalSise, carValue=$carValue, 재산후=$propertyAfterCarSale, 대상후=$debtAfterCarSale")

            if (propertyAfterCarSale <= debtAfterCarSale || debtAfterCarSale <= 0) {
                // 차량 처분하면 재산초과 해소 → 단기 가능
                val stMonthly = income - livingCostHoeseng
                val stMonthlyAdj = if (stMonthly <= 0 && income > livingCostTable[1]) income - livingCostTable[1] else stMonthly
                if (stMonthlyAdj > 0) {
                    var stMonths = Math.round(debtAfterCarSale.toDouble() / stMonthlyAdj).toInt()
                    if (stMonths > 60) stMonths = 60
                    if (stMonths < 1) stMonths = 1
                    val roundedSt = ((stMonthlyAdj + 2) / 5) * 5
                    val minYears = Math.ceil(stMonths / 12.0).toInt()
                    val maxYears = Math.ceil(60 / 12.0).toInt()
                    shortTermAfterCarSale = "${roundedSt}만 ${minYears}~${maxYears}년납"
                    shortTermCarSaleApplied = true
                    Log.d("HWP_CALC", "차량 처분시 단기: 대상=$debtAfterCarSale, 재산=$propertyAfterCarSale, 월=$roundedSt, ${minYears}~${maxYears}년")
                } else if (income <= livingCostTable[1] && debtAfterCarSale > propertyAfterCarSale) {
                    // 소득 < 1인 생계비 → (대상-재산)÷36
                    val stByDebt = Math.ceil((debtAfterCarSale - propertyAfterCarSale).toDouble() / 36).toInt()
                    val roundedSt = ((stByDebt + 2) / 5) * 5
                    shortTermAfterCarSale = "${roundedSt}만 3~5년납"
                    shortTermCarSaleApplied = true
                    Log.d("HWP_CALC", "차량 처분시 단기(소득<1인생계비): ($debtAfterCarSale-$propertyAfterCarSale)÷36=$stByDebt → ${roundedSt}만")
                }
            }
        }

        var shortTermMonthly = income - livingCostHoeseng
        // 가구수 단계적으로 내려가며 적용 (3인→2인→1인)
        var shortTermHousehold = householdForHoeseng
        while (shortTermMonthly <= 0 && shortTermHousehold > 1) {
            shortTermHousehold--
            shortTermMonthly = income - livingCostTable[shortTermHousehold]
        }
        if (shortTermHousehold != householdForHoeseng) {
            Log.d("HWP_CALC", "단기 생계비 조정: ${householdForHoeseng}인(${livingCostTable[householdForHoeseng]}) → ${shortTermHousehold}인(${livingCostTable[shortTermHousehold]}), 월변제금=$shortTermMonthly")
        }

        var shortTermMonths = 0
        var shortTermResult = ""

        if (!shortTermBlocked && shortTermMonthly > 0) {
            shortTermMonths = Math.round(targetDebt.toDouble() / shortTermMonthly).toInt()
            if (shortTermMonths > 60) shortTermMonths = 60
            if (shortTermMonths < 1) shortTermMonths = 1
            val roundedShortTerm = ((shortTermMonthly + 2) / 5) * 5
            shortTermResult = "${roundedShortTerm}만 / ${shortTermMonths}개월납"
        } else if (shortTermBlocked) {
            shortTermResult = "단기 불가 ($shortTermBlockReason)"
        } else {
            shortTermResult = "단기 불가 (소득부족)"
            shortTermBlocked = true
        }

        // 공제기준가액
        var baseExemption = 7500
        val regionLower = region.lowercase()

// 인천 과밀억제권역 제외 지역
        val incheonExcluded = listOf("강화", "옹진", "대곡동", "불로동", "마전동", "금곡동", "오류동",
            "왕길동", "당하동", "원당동", "송도동", "영종동", "용유동", "청라",
            "논현동", "남촌동", "고잔동")
// 시흥 과밀억제권역 제외 지역
        val siheungExcluded = listOf("정왕동", "검오동")
// 남양주 과밀억제권역 해당 지역 (이것만 포함)
        val namyangjuIncluded = listOf("호평동", "평내동", "금곡동", "일패동", "이패동", "삼패동",
            "가운동", "수석동", "지금동", "도농동")

        val isIncheonOvercrowded = regionLower.contains("인천") && incheonExcluded.none { regionLower.contains(it) }
        val isSiheungOvercrowded = regionLower.contains("시흥") && siheungExcluded.none { regionLower.contains(it) }
        val isNamyangjuOvercrowded = regionLower.contains("남양주") && namyangjuIncluded.any { regionLower.contains(it) }

        baseExemption = when {
            regionLower.contains("서울") -> 16500
            regionLower.contains("용인") || regionLower.contains("화성") || regionLower.contains("세종") ||
                    regionLower.contains("김포") || regionLower.contains("고양") || regionLower.contains("과천") ||
                    regionLower.contains("성남") || regionLower.contains("하남") || regionLower.contains("광명") ||
                    regionLower.contains("의정부") || regionLower.contains("구리") ||
                    regionLower.contains("수원") || regionLower.contains("안양") || regionLower.contains("의왕") ||
                    regionLower.contains("부천") || regionLower.contains("군포") ||
                    isIncheonOvercrowded || isSiheungOvercrowded || isNamyangjuOvercrowded -> 14500
            regionLower.contains("인천") -> 8500
            regionLower.contains("부산") || regionLower.contains("대구") || regionLower.contains("광주") ||
                    regionLower.contains("대전") || regionLower.contains("울산") || regionLower.contains("안산") ||
                    regionLower.contains("파주") || regionLower.contains("이천") || regionLower.contains("평택") -> 8500
            else -> 7500
        }

        val householdForExemption = 1 + minorChildren  // 배우자 제외
        val exemptionAmount = when {
            householdForExemption <= 2 -> (baseExemption * 0.8).toInt()
            householdForExemption >= 4 -> (baseExemption * 1.2).toInt()
            else -> baseExemption
        }
        Log.d("HWP_CALC", "공제기준가액: ${exemptionAmount}만 (지역: $region, 가구: ${householdForExemption}인)")

        var netPropertyAfterExemption = netProperty - exemptionAmount
        if (netPropertyAfterExemption < 0) netPropertyAfterExemption = 0
        val longTermPropertyExcess = netPropertyAfterExemption > targetDebt && targetDebt > 0
        Log.d("HWP_CALC", "장기 재산초과 판단: ($netProperty - $exemptionAmount) = $netPropertyAfterExemption > $targetDebt → $longTermPropertyExcess")

        // 장기(신복위) 보수 계산
        val availableIncome = income - livingCostShinbok
        val yearlyIncomeCalc = income * 12
        var longTermYears = 0
        var longTermMonthly = 0
        val totalPayment = (targetDebt * repaymentRate / 100.0).toInt()
        val parentDeduction = if (parentCount > 0) 50 else 0

        Log.d("HWP_CALC", "장기 계산: 소득=$income, 생계비=$livingCostShinbok, 부모=${parentCount}명(${parentDeduction}만), 가용소득=${availableIncome - parentDeduction}")

        if (targetDebt > 0) {
            val step1 = income - livingCostShinbok - parentDeduction  // 1단계: 소득-생계비-부모
            val step2 = income - livingCostShinbok                    // 2단계: 부모 제외

            longTermMonthly = when {
                // 1단계: 소득-생계비-부모 ≥ 40
                step1 >= 40 -> {
                    Log.d("HWP_CALC", "장기 1단계: 소득-생계비-부모=$step1")
                    step1
                }
                // 2단계: 부모 제외, 소득-생계비 ≥ 40
                step2 >= 40 -> {
                    Log.d("HWP_CALC", "장기 2단계: 부모 제외, 소득-생계비=$step2")
                    step2
                }
                // 3단계: 40만 고정
                else -> {
                    Log.d("HWP_CALC", "장기 3단계: 최소 40만 적용")
                    40
                }
            }

            // 4단계: 3단계(40만)에서 기간이 10년 초과하면 → 총변제금÷120
            if (longTermMonthly == 40) {
                val step3Years = Math.ceil(totalPayment.toDouble() / 40 / 12.0).toInt()
                if (step3Years > 10) {
                    longTermMonthly = Math.ceil(totalPayment.toDouble() / 120).toInt()
                    Log.d("HWP_CALC", "장기 4단계: 40만×${step3Years}년 > 10년 → 총변제금÷120=$longTermMonthly")
                }
            }

            // 최소변제금: 총변제금 × 1.5%
            val minRepayment = (targetDebt * 0.015).toInt()
            if (longTermMonthly < minRepayment) {
                longTermMonthly = minRepayment
                Log.d("HWP_CALC", "장기 최소변제금 적용: 대상채무${targetDebt}×1.5%=$minRepayment")
            }
        }

        var roundedLongTermMonthly = ((longTermMonthly + 2) / 5) * 5

        // 보수 년수: 총변제금 ÷ 월변제금 ÷ 12 (내림, 최소 1년, 최대 10년)
        if (roundedLongTermMonthly > 0) {
            longTermYears = Math.floor(totalPayment.toDouble() / roundedLongTermMonthly / 12.0).toInt().coerceIn(1, 10)
        }
        Log.d("HWP_CALC", "장기 보수: 총변제금=$totalPayment, 월변제금=$roundedLongTermMonthly, ${longTermYears}년")

        // 소득 기반 변제금으로 1년 이내 완납 가능 → 100% 변제, 최소 2년
        if (roundedLongTermMonthly > 0 && totalPayment < roundedLongTermMonthly * 12) {
            roundedLongTermMonthly = ((Math.ceil(targetDebt.toDouble() / 24).toInt() + 2) / 5) * 5
            longTermYears = 2
            Log.d("HWP_CALC", "장기 1년 미만 → 100% 변제: ${roundedLongTermMonthly}만 / 2년납")
        }

        // 보수 월변제금이 대상채무 3% 초과시 → 1.5%~3% 사이로 재조정
        val debtThreePercent = Math.ceil(targetDebt * 0.03).toInt()
        val debtOnePointFivePercent = Math.ceil(targetDebt * 0.015).toInt()
        if (roundedLongTermMonthly > debtThreePercent && targetDebt > 0 && longTermMonthly <= debtThreePercent) {
            var adjustedMonthly = 0
            var adjustedYears = 0
            for (y in 1..10) {
                val monthly = Math.ceil(totalPayment.toDouble() / (y * 12)).toInt()
                val rounded = ((monthly + 2) / 5) * 5
                if (rounded in debtOnePointFivePercent..debtThreePercent) {
                    adjustedMonthly = rounded
                    adjustedYears = y
                    break  // 가장 짧은 년수(높은 변제금) 선택
                }
            }
            if (adjustedMonthly > 0) {
                roundedLongTermMonthly = adjustedMonthly
                longTermYears = adjustedYears
                Log.d("HWP_CALC", "장기 보수 재조정: 3%=${debtThreePercent}만 초과 → ${roundedLongTermMonthly}만 / ${longTermYears}년납 (1.5%=${debtOnePointFivePercent}~3%=${debtThreePercent})")
            }
        }

        // 방생 판단은 공격 계산 후에

        // 장기(신복위) 공격 계산: 보수 월변제금 × 2/3
        var aggressiveYears = 0
        var roundedAggressiveMonthly = 0

        if (targetDebt > 0 && roundedLongTermMonthly > 0) {
            val aggressiveMonthly = Math.ceil(roundedLongTermMonthly * 2.0 / 3.0).toInt()
            roundedAggressiveMonthly = ((aggressiveMonthly + 2) / 5) * 5
            if (roundedAggressiveMonthly < 40) roundedAggressiveMonthly = roundedLongTermMonthly

            // 공격 년수: 보수와 동일
            aggressiveYears = longTermYears
        }
        acost = roundedLongTermMonthly

        // 방생 판단: 소득 - 보수 월변제금 ≤ 30만이면 생활 불가
        val canSurvive = income - roundedLongTermMonthly > 30

        // 새출발기금 - ★ 사업자 이력은 AI 값 사용
        val canApplySae = hasBusinessHistory && delinquentDays < 90
        Log.d("HWP_CALC", "새새 조건: 사업이력=$hasBusinessHistory(AI), 연체=${delinquentDays}일, 개업=$businessStartYear, 폐업=$businessEndYear")
        var saeTotalPayment = 0; var saeMonthlyConservative = 0; var saeMonthlyAggressive = 0
        if (canApplySae && targetDebt > 0) {
            var netDebtAfterProperty = targetDebt - netProperty
            if (netDebtAfterProperty < 0) netDebtAfterProperty = 0
            saeTotalPayment = targetDebt - (netDebtAfterProperty * 0.35).toInt()
            saeTotalPayment = ((saeTotalPayment + 2) / 5) * 5
            // 새새 기간: 변제금 산정이 대상채무*1.5%보다 높으면 5년(60개월), 작으면 8년(96개월)
            val saeThreshold = (targetDebt * 0.015).toInt()
            val saeMonths = if (Math.ceil(saeTotalPayment.toDouble() / 60).toInt() > saeThreshold) 60 else 96
            saeMonthlyConservative = ((Math.ceil(saeTotalPayment.toDouble() / 72).toInt() + 2) / 5) * 5
            if (saeMonthlyConservative < 40) saeMonthlyConservative = 40
            // 공격: 10년(120개월), 5만 단위 반올림, 최소 40만
            saeMonthlyAggressive = ((Math.ceil(saeTotalPayment.toDouble() / 120).toInt() + 2) / 5) * 5
            if (saeMonthlyAggressive < 40) saeMonthlyAggressive = 40
            // 공격×120 < 총변제금이면 공격=보수
            if (saeMonthlyAggressive.toLong() * 120 < saeTotalPayment) saeMonthlyAggressive = saeMonthlyConservative
            // 재산 없으면 무조건 새새
            Log.d("HWP_CALC", "새새: 총변제=${saeTotalPayment}만, threshold=${saeThreshold}만, 기간=${saeMonths}개월, 보수=${saeMonthlyConservative}만, 재산=${netProperty}")
        }

        // 특이사항
        val familyInfo = StringBuilder()
        familyInfo.append(if (isDivorced) "이혼" else if (hasSpouse || minorChildren > 0 || collegeChildren > 0) "기혼" else "미혼")
        if (minorChildren > 0) familyInfo.append(" 미성년$minorChildren")
        if (collegeChildren > 0) familyInfo.append(" 대학생$collegeChildren")
        if (parentCount > 0) familyInfo.append(" 60세부모$parentCount")
        specialNotesList.add(0, familyInfo.toString())

        // 과반 채권사
        if (originalTargetDebt > 0 && majorCreditorFromAI.isNotEmpty() && majorCreditorRatio > 50) {
            specialNotesList.add("$majorCreditorFromAI 과반 (${String.format("%.0f", majorCreditorRatio)}%)")
            Log.d("HWP_CALC", "과반 채권사 (AI): $majorCreditorFromAI ${majorCreditorDebtFromAI}만 / ${originalTargetDebt}만 = ${String.format("%.1f", majorCreditorRatio)}%")
        }
        if (majorCreditorRatio >= 50 && repaymentRate < 95) { repaymentRate = 95; rateReason = "과반" }

        specialNotesList.add("6개월 이내 ${String.format("%.0f", recentDebtRatio)}%")
        if (carValue >= 1000 || carMonthlyPayment >= 50) specialNotesList.add("차량 처분 필요")
        if (carCount >= 2) specialNotesList.add("차량 ${carCount}대 보유")
        if (hasGambling) specialNotesList.add("도박")
        if (hasStock) specialNotesList.add("주식")
        if (hasCrypto) specialNotesList.add("코인")
        if (spouseSecret) specialNotesList.add("배우자 모르게")
        if (familySecret) specialNotesList.add("가족 모르게")
        if (hasAuction) specialNotesList.add("경매/압류")
        if (delinquentDays >= 90) specialNotesList.add("장기연체자")
        if (hasCivilCase) specialNotesList.add("민사 소송금 따로 변제")
        if (hasUsedCarInstallment) specialNotesList.add("중고차 할부 따로 납부")
        if (hasHealthInsuranceDebt) specialNotesList.add("건강보험 체납 따로 변제")
        if (hasShinbokwiHistory) specialNotesList.add("신복위 이력")
        if (hasOngoingProcess) specialNotesList.add("다른 단계 진행 중")
        if (hasPaymentOrder) specialNotesList.add("지급명령 받음")
        if (dischargeWithin5Years) specialNotesList.add("면책 5년 이내 (${dischargeYear}년)")
        if (hasDischarge && dischargeYear > 0) {
            val availableYear = dischargeYear + 5
            if (currentYear < availableYear) specialNotesList.add("면책 ${dischargeYear}년 → ${availableYear}년 이후 회생")
        }
        // 변제율은 특이사항에 표시하지 않음

        // 진단 코드
        var diagnosis = ""
        var diagnosisNote = ""
        val delinquentPrefix = when { delinquentDays < 30 -> "신"; delinquentDays < 90 -> "프"; else -> "회" }

        // 유예 가능 여부: 상환내역서 유예기간 12개월 미만이면 유예 가능 (6개월x2회)
        val canDeferment = aiDefermentMonths < 12
        if (aiDefermentMonths > 0) {
            Log.d("HWP_CALC", "유예기간: ${aiDefermentMonths}개월 → 유예 ${if (canDeferment) "가능" else "불가"}")
            if (aiDefermentMonths >= 12) specialNotesList.add("유예기간 ${aiDefermentMonths}개월 (유예불가)")
        }

        val hasYuwoCond = canDeferment && ((netProperty > targetDebt && targetDebt > 0) || recentDebtRatio >= 30 || (dischargeWithin5Years))

        var isBangsaeng = false
        var bangsaengReason = ""
        if (netProperty > targetDebt && targetDebt > 0 && shortTermBlocked && longTermPropertyExcess && !canApplySae) {
            isBangsaeng = true; bangsaengReason = "재산초과"
        }
        if (!isBangsaeng && targetDebt > 0 && !canSurvive) {
            // 소득부족이지만 새새 가능하면 방생 아님
            if (canApplySae && saeTotalPayment > 0) {
                Log.d("HWP_CALC", "소득부족이지만 새새 가능 → 방생 해제")
            } else if (hasPaymentOrder) {
                Log.d("HWP_CALC", "소득부족이지만 지급명령 받음 → 장기 진행 가능 → 방생 해제")
            } else if (!shortTermBlocked) {
                Log.d("HWP_CALC", "소득부족이지만 단기 가능 → 방생 해제")
            } else {
                isBangsaeng = true; bangsaengReason = "소득부족"
                Log.d("HWP_CALC", "방생: 소득=$income - 공격월변제=$roundedAggressiveMonthly = ${income - roundedAggressiveMonthly} ≤ 30")
            }
        }

        var shortTermTotal = if (!shortTermBlocked && shortTermMonthly > 0) ((shortTermMonthly + 2) / 5) * 5 * shortTermMonths else 0
        // 소득 < 1인 생계비일 때 단기 총액
        if (!shortTermBlocked && income <= livingCostTable[1] && targetDebt > netProperty) {
            val stByDebt = Math.ceil((targetDebt - netProperty).toDouble() / 36).toInt()
            val roundedSt = ((stByDebt + 2) / 5) * 5
            shortTermTotal = roundedSt * 36
        }
        // 차량 처분 시 단기 총액
        if (shortTermCarSaleApplied) {
            val debtAfterCar = if (carTotalLoan > carTotalSise) targetDebt + (carTotalLoan - carTotalSise) else targetDebt
            val propAfterCar = netProperty - carValue
            val stMonthly = income - livingCostHoeseng
            val stAdj = if (stMonthly <= 0 && income > livingCostTable[1]) income - livingCostTable[1] else stMonthly
            if (stAdj > 0) {
                var stMonths = Math.round(debtAfterCar.toDouble() / stAdj).toInt().coerceIn(1, 60)
                shortTermTotal = ((stAdj + 2) / 5) * 5 * stMonths
            } else if (income <= livingCostTable[1] && debtAfterCar > propAfterCar) {
                val stByDebt = Math.ceil((debtAfterCar - propAfterCar).toDouble() / 36).toInt()
                val roundedSt = ((stByDebt + 2) / 5) * 5
                shortTermTotal = roundedSt * 36
            }
        }
        val longTermTotal = roundedLongTermMonthly * longTermYears * 12
        val aggressiveTotal = roundedAggressiveMonthly * aggressiveYears * 12

        // 차량 처분 시 장기 총액 계산
        var carSaleLongTermTotal = longTermTotal
        if (shortTermCarSaleApplied && carTotalLoan > 0) {
            val csDebt = if (carTotalLoan > carTotalSise) targetDebt + (carTotalLoan - carTotalSise) else targetDebt
            val csTotalPayment = (csDebt * repaymentRate / 100.0).toInt()
            val csStep1 = income - livingCostShinbok - parentDeduction
            val csStep2 = income - livingCostShinbok
            var csMonthly = when {
                csStep1 >= 40 -> csStep1
                csStep2 >= 40 -> csStep2
                else -> 40
            }
            if (csMonthly == 40) {
                val csStep3Years = Math.ceil(csTotalPayment.toDouble() / 40 / 12.0).toInt()
                if (csStep3Years > 10) {
                    csMonthly = Math.ceil(csTotalPayment.toDouble() / 120).toInt()
                }
            }
            val csMinRepayment = (csDebt * 0.015).toInt()
            if (csMonthly < csMinRepayment) csMonthly = csMinRepayment
            val csRounded = ((csMonthly + 2) / 5) * 5
            val csYears = if (csRounded > 0) Math.floor(csTotalPayment.toDouble() / csRounded / 12.0).toInt().coerceIn(1, 10) else 10
            carSaleLongTermTotal = csRounded * csYears * 12
            Log.d("HWP_CALC", "차량처분 장기총액: ${csRounded}만 × ${csYears}년 × 12 = $carSaleLongTermTotal")
        }

        // 진단에 사용할 장기 총액 (차량 처분 시에는 처분 기준)
        val effectiveLongTermTotal = if (shortTermCarSaleApplied) carSaleLongTermTotal else longTermTotal

        // 차량/부동산 처분시 장기 가능 여부
        var canLongTermAfterCarSale = false
        if (longTermPropertyExcess && (carValue > 0 || carTotalLoan > carTotalSise)) {
            val propertyAfterCarSale = netProperty - carValue
            var debtAfterCarSale = targetDebt
            if (carTotalLoan > carTotalSise) debtAfterCarSale += (carTotalLoan - carTotalSise)
            var netAfterExemption = propertyAfterCarSale - exemptionAmount
            if (netAfterExemption < 0) netAfterExemption = 0
            if (netAfterExemption <= debtAfterCarSale) canLongTermAfterCarSale = true
            Log.d("HWP_CALC", "차량 처분시: 재산=$propertyAfterCarSale, 공제후=$netAfterExemption, 대상채무=$debtAfterCarSale → $canLongTermAfterCarSale")
        }

        var canLongTermAfterPropertySale = false
        if (longTermPropertyExcess && netProperty > carTotalSise) {
            val propertyWithoutCar = netProperty - carTotalSise
            val propertyAfterRealEstateSale = netProperty - propertyWithoutCar
            var netAfterExemptionProperty = propertyAfterRealEstateSale - exemptionAmount
            if (netAfterExemptionProperty < 0) netAfterExemptionProperty = 0
            if (netAfterExemptionProperty <= targetDebt) canLongTermAfterPropertySale = true
            Log.d("HWP_CALC", "부동산 처분시: 재산=$propertyAfterRealEstateSale, 공제후=$netAfterExemptionProperty, 대상채무=$targetDebt → $canLongTermAfterPropertySale")
        }

        // 차량 처분시 단기 가능이면 단기 가능으로 판단
        val effectiveShortTermBlocked = shortTermBlocked && !shortTermCarSaleApplied

        val diagnosisAfterCarSale = when {
            !canLongTermAfterCarSale -> ""
            delinquentDays >= 90 -> if (effectiveShortTermBlocked) "워유워" else "회워"
            delinquentDays >= 30 -> if (effectiveShortTermBlocked) "프유워" else "프회워"
            else -> if (effectiveShortTermBlocked) "신유워" else "신회워"
        }

        val diagnosisAfterPropertySale = when {
            !canLongTermAfterPropertySale -> ""
            delinquentDays >= 90 -> if (effectiveShortTermBlocked) "워유워" else "회워"
            delinquentDays >= 30 -> if (effectiveShortTermBlocked) "프유워" else "프회워"
            else -> if (effectiveShortTermBlocked) "신유워" else "신회워"
        }

        // 학자금 비율 체크 (표 내 채무 기준)
        val studentLoanMan = (studentLoanTotal + 5) / 10
        val tableDebtMan = (tableDebtTotal + 5) / 10
        val studentLoanRatio = if (tableDebtMan > 0) studentLoanMan.toDouble() / tableDebtMan * 100 else 0.0
        if (studentLoanMan > 0) {
            if (studentLoanRatio >= 50) specialNotesList.add("학자금 많음")
            Log.d("HWP_CALC", "학자금: ${studentLoanMan}만 / 표전체${tableDebtMan}만 = ${String.format("%.1f", studentLoanRatio)}%")
        }

        val creditorCount = if (aiCreditorCount > 0) aiCreditorCount else 0
        Log.d("HWP_CALC", "채권사 수: $creditorCount (AI=$aiCreditorCount)")
        if (creditorCount == 1) specialNotesList.add("채권사 1건")

        if (studentLoanRatio >= 50) {
            diagnosis = "단순 진행"
        } else if (targetDebt in 1..2000) {
            diagnosis = "방생"; diagnosisNote = "(소액)"
        } else if (creditorCount == 1) {
            diagnosis = "단순유리"; diagnosisNote = "(채권사 1건, 개인회생 안내)"
        } else if (nonAffiliatedOver20) {
            diagnosis = if (!effectiveShortTermBlocked) "단순유리" else "방생"
            diagnosisNote = "[장기] 미협약 초과"
        } else if (isBangsaeng) {
            diagnosis = "방생"
            if (bangsaengReason.isNotEmpty()) diagnosisNote = "($bangsaengReason)"
            if (canLongTermAfterCarSale) diagnosisNote += " (차량 처분시 $diagnosisAfterCarSale 가능)"
            else if (canLongTermAfterPropertySale) diagnosisNote += " (부동산 처분시 $diagnosisAfterPropertySale 가능)"
        } else if (canApplySae && saeTotalPayment > 0 && netProperty <= 0) {
            // 새새 가능 + 재산 없으면 무조건 새새
            diagnosis = "새새"
        } else if (canApplySae && saeTotalPayment > 0) {
            val shinbokTotal = roundedLongTermMonthly * longTermYears * 12
            if (!effectiveShortTermBlocked && shortTermTotal > 0 && shortTermTotal <= saeTotalPayment && shortTermTotal <= effectiveLongTermTotal) {
                diagnosis = "단순유리"
            } else if (saeTotalPayment <= shinbokTotal) {
                diagnosis = "새새"
            } else if (recentDebtRatio >= 30) {
                diagnosis = "새새"
            } else {
                diagnosis = delinquentPrefix + if (hasYuwoCond) "유워" else "회워"
            }
        } else if (hasShinbokwiHistory) {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked -> "회워"
                else -> "워유워"
            }
        } else if (hasOngoingProcess) {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked && shortTermTotal > 0 && shortTermTotal <= effectiveLongTermTotal -> "단순유리"
                !effectiveShortTermBlocked -> "회워"
                else -> "워유워"
            }
        } else if (!canDeferment) {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked && shortTermTotal > 0 && shortTermTotal <= effectiveLongTermTotal -> "단순유리"
                !effectiveShortTermBlocked -> "회워"
                else -> "워유워"
            }
        } else if (delinquentDays >= 90) {
            diagnosis = when {
                hasWorkoutExpired -> "단순워크"
                !effectiveShortTermBlocked && shortTermTotal > 0 && shortTermTotal <= effectiveLongTermTotal -> "단순유리"
                !effectiveShortTermBlocked && !longTermPropertyExcess -> "회워"
                else -> "워유워"
            }
        } else if (delinquentDays >= 30) {
            diagnosis = when {
                !effectiveShortTermBlocked && shortTermTotal > 0 && shortTermTotal <= effectiveLongTermTotal -> "단순유리"
                recentDebtRatio >= 30 && !effectiveShortTermBlocked -> "프회워"
                targetDebt <= 4000 && !effectiveShortTermBlocked -> "프유워"
                !effectiveShortTermBlocked -> "프회워"
                else -> "프유워"
            }
        } else {
            diagnosis = when {
                !effectiveShortTermBlocked && shortTermTotal > 0 && shortTermTotal <= effectiveLongTermTotal -> "단순유리"
                recentDebtRatio >= 30 && !effectiveShortTermBlocked -> "신회워"
                targetDebt <= 4000 && !effectiveShortTermBlocked -> "신유워"
                !effectiveShortTermBlocked -> "신회워"
                else -> "신유워"
            }
        }

        // ============= 10개월 내 면책 5년 해소 시 회워 계열로 변경 =============
        // 면책 5년 이내로 단기불가인데, 면책+5년이 10개월 이내에 도래하면 회워 계열로
        if (dischargeWithin5Years && dischargeYear > 0) {
            val tenMonthsLater = Calendar.getInstance().apply { add(Calendar.MONTH, 10) }
            val dischargeEndCal = Calendar.getInstance().apply {
                set(dischargeYear + 5, if (dischargeMonth > 0) dischargeMonth - 1 else 0, 1)
            }
            if (!dischargeEndCal.after(tenMonthsLater)) {
                val afterDate = "${dischargeYear + 5}.${String.format("%02d", if (dischargeMonth > 0) dischargeMonth else 1)}"
                specialNotesList.add("면책 5년 해소 ${afterDate} (10개월 이내)")
                if (!hasOngoingProcess && diagnosis.endsWith("유워")) {
                    diagnosis = when (diagnosis) {
                        "신유워" -> "신회워"
                        "프유워" -> "프회워"
                        "워유워" -> "회워"
                        else -> diagnosis
                    }
                }
            }

            if (aiHasRecoveryPlan) {
                shortTermBlocked = true
                if (shortTermBlockReason.isNotEmpty()) shortTermBlockReason += ", "
                shortTermBlockReason += "회생 진행 중"
                hasPersonalRecovery = true
                specialNotesList.add("개인회생 진행 중 (변제계획안)")
            }
        }


        if (recentDebtRatio >= 30 && lastCreditDate.isNotEmpty()) {
            // 실제 +6개월 날짜 계산
            try {
                val parts = lastCreditDate.split(".")
                if (parts.size >= 3) {
                    val cal = Calendar.getInstance()
                    cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                    cal.add(Calendar.MONTH, 6)
                    val afterDate = "${cal.get(Calendar.YEAR)}.${String.format("%02d", cal.get(Calendar.MONTH) + 1)}.${String.format("%02d", cal.get(Calendar.DAY_OF_MONTH))}"
                    // 6개월 이후 시점 진단 계산
                    var afterDiag = when {
                        delinquentDays >= 90 -> if (effectiveShortTermBlocked) "워유워" else "회워"
                        delinquentDays >= 30 -> if (effectiveShortTermBlocked) "프유워" else "프회워"
                        !effectiveShortTermBlocked -> "신회워"
                        else -> "신유워"
                    }
                    // 변경 후
                    diagnosis = afterDiag
                    diagnosisNote = "${afterDate} 이후 가능"
                }
            } catch (e: Exception) {
                diagnosisNote = "$lastCreditDate +6개월 이후"
            }
        }

        // UI 업데이트
        binding.name.text = "[이름] $name"
        binding.card.text = "[소득] ${income}만"
        if (shortTermCarSaleApplied && carTotalLoan > carTotalSise) {
            val debtAfterCarSale = targetDebt + (carTotalLoan - carTotalSise)
            binding.dat.text = "[대상] ${formatToEok(debtAfterCarSale)} (차량 공매 가정)"
        } else {
            binding.dat.text = "[대상] ${formatToEok(targetDebt)}"
        }
        binding.money.text = "[재산] ${formatToEok(netProperty)}"

        val specialNotesText = StringBuilder()
        if (specialNotesList.isNotEmpty()) {
            specialNotesText.append("[특이] ")
            specialNotesList.take(10).forEachIndexed { index, note ->
                if (index > 0) specialNotesText.append("\n")
                specialNotesText.append(note)
            }
        }
        binding.use.text = specialNotesText.toString()
        val spouseSecretSuffix = if (spouseSecret) " (배우자 모르게)" else ""
        if (shortTermCarSaleApplied) {
            binding.test1.text = "[단기] $shortTermAfterCarSale (차량 처분 시)$spouseSecretSuffix"
        } else {
            binding.test1.text = "[단기] $shortTermResult$spouseSecretSuffix"
        }

        val longTermText = StringBuilder()
        if (nonAffiliatedOver20) {
            longTermText.append("[장기] 미협약 초과")
        } else if (wantsCarSale && carTotalLoan > 0) {
            // 차량 처분 시: 대상채무에 부족분 포함하여 장기 재계산
            var newTargetDebt = targetDebt
            if (carTotalLoan > carTotalSise) newTargetDebt = targetDebt + (carTotalLoan - carTotalSise)
            val dispTotalPayment = (newTargetDebt * repaymentRate / 100.0).toInt()
            val dispStep1 = income - livingCostShinbok - parentDeduction
            val dispStep2 = income - livingCostShinbok
            var dispLongTermMonthly = when {
                dispStep1 >= 40 -> dispStep1
                dispStep2 >= 40 -> dispStep2
                else -> 40
            }
            if (dispLongTermMonthly == 40) {
                val dispStep3Years = Math.ceil(dispTotalPayment.toDouble() / 40 / 12.0).toInt()
                if (dispStep3Years > 10) {
                    dispLongTermMonthly = Math.ceil(dispTotalPayment.toDouble() / 120).toInt()
                }
            }
            val dispMinRepayment = (newTargetDebt * 0.015).toInt()
            if (dispLongTermMonthly < dispMinRepayment) dispLongTermMonthly = dispMinRepayment
            var dispRoundedLongTerm = ((dispLongTermMonthly + 2) / 5) * 5
            val dispLongTermYears = if (dispRoundedLongTerm > 0) Math.floor(dispTotalPayment.toDouble() / dispRoundedLongTerm / 12.0).toInt().coerceIn(1, 10) else 10

            val dispAggressiveMonthly = Math.ceil(dispRoundedLongTerm * 2.0 / 3.0).toInt()
            var dispRoundedAggressive = ((dispAggressiveMonthly + 2) / 5) * 5
            if (dispRoundedAggressive < 40) dispRoundedAggressive = dispRoundedLongTerm
            val dispAggressiveYears = Math.floor(dispTotalPayment.toDouble() / dispRoundedAggressive / 12.0).toInt().coerceIn(1, 10)

            longTermText.append("[장기 보수] ${dispRoundedLongTerm}만 / ${dispLongTermYears}년납 (차량 처분시)")
            if (dispRoundedAggressive != dispRoundedLongTerm) {
                longTermText.append("\n[장기 공격] ${dispRoundedAggressive}만 / ${dispAggressiveYears}년납 (차량 처분시)")
            }
            Log.d("HWP_CALC", "차량 처분시 장기: 대상=$newTargetDebt, 총변제=$dispTotalPayment, 보수=${dispRoundedLongTerm}만/${dispLongTermYears}년, 공격=${dispRoundedAggressive}만/${dispAggressiveYears}년")
        } else if (longTermPropertyExcess) {
            if (canLongTermAfterCarSale || canLongTermAfterPropertySale) {
                var newTargetDebt = targetDebt
                if (canLongTermAfterCarSale && carTotalLoan > carTotalSise) newTargetDebt = targetDebt + (carTotalLoan - carTotalSise)
                val dispTotalPayment = (newTargetDebt * repaymentRate / 100.0).toInt()
                val dispStep1 = income - livingCostShinbok - parentDeduction
                val dispStep2 = income - livingCostShinbok
                var dispLongTermMonthly = when {
                    dispStep1 >= 40 -> dispStep1
                    dispStep2 >= 40 -> dispStep2
                    else -> 40
                }
                if (dispLongTermMonthly == 40) {
                    val dispStep3Years = Math.ceil(dispTotalPayment.toDouble() / 40 / 12.0).toInt()
                    if (dispStep3Years > 10) {
                        dispLongTermMonthly = Math.ceil(dispTotalPayment.toDouble() / 120).toInt()
                    }
                }
                val dispMinRepayment = (newTargetDebt * 0.015).toInt()
                if (dispLongTermMonthly < dispMinRepayment) dispLongTermMonthly = dispMinRepayment
                var dispRoundedLongTerm = ((dispLongTermMonthly + 2) / 5) * 5
                val dispLongTermYears = if (dispRoundedLongTerm > 0) Math.floor(dispTotalPayment.toDouble() / dispRoundedLongTerm / 12.0).toInt().coerceIn(1, 10) else 10

                val dispAggressiveMonthly = Math.ceil(dispRoundedLongTerm * 2.0 / 3.0).toInt()
                var dispRoundedAggressive = ((dispAggressiveMonthly + 2) / 5) * 5
                if (dispRoundedAggressive < 40) dispRoundedAggressive = dispRoundedLongTerm
                val dispAggressiveYears = Math.floor(dispTotalPayment.toDouble() / dispRoundedAggressive / 12.0).toInt().coerceIn(1, 10)

                val dispType = if (canLongTermAfterCarSale) "차량" else "부동산"
                longTermText.append("[장기 보수] ${dispRoundedLongTerm}만 / ${dispLongTermYears}년납 (${dispType} 처분시)")
                if (dispRoundedAggressive != dispRoundedLongTerm) {
                    longTermText.append("\n[장기 공격] ${dispRoundedAggressive}만 / ${dispAggressiveYears}년납 (${dispType} 처분시)")
                }
            } else {
                longTermText.append("[장기 보수] 장기 불가 (재산초과)\n")
                longTermText.append("[장기 공격] 장기 불가 (재산초과)")
            }
        } else {
            longTermText.append("[장기 보수] ${roundedLongTermMonthly}만 / ${longTermYears}년납")
            if (roundedAggressiveMonthly != roundedLongTermMonthly) {
                longTermText.append("\n[장기 공격] ${roundedAggressiveMonthly}만 / ${aggressiveYears}년납")
            }
        }
        if (canApplySae && saeTotalPayment > 0) {
            val saeThreshold = (targetDebt * 0.015).toInt()
            val saeConservativeYears = if (saeMonthlyConservative > saeThreshold) 5 else 8
            longTermText.append("\n[새새 보수] ${saeMonthlyConservative}만 / ${saeConservativeYears}년납")
            if (saeMonthlyAggressive != saeMonthlyConservative) {
                longTermText.append("\n[새새 공격] ${saeMonthlyAggressive}만 / 10년납")
            }
        }
        binding.test2.text = longTermText.toString()

        var finalDiagnosis = if (diagnosisNote.isNotEmpty()) "$diagnosis $diagnosisNote" else diagnosis
        if (shortTermCarSaleApplied) finalDiagnosis = "차량 처분 시 $finalDiagnosis"
        binding.testing.text = "[진단] $finalDiagnosis"
        binding.half.text = ""

        Log.d("HWP_CALC", "이름: $name, 소득: ${income}만, 대상: ${targetDebt}만, 재산: ${netProperty}만, 6개월비율: ${String.format("%.1f", recentDebtRatio)}%, 진단: $diagnosis")
    }

    // ============= 유틸리티 =============
    private fun extractAmountAfterKeyword(text: String, keyword: String): Int {
        if (!text.contains(keyword)) return 0
        return extractAmount(text.substring(text.indexOf(keyword)))
    }

    private fun formatToEok(amountInMan: Int): String {
        return if (amountInMan >= 10000) {
            val eok = amountInMan / 10000
            val man = amountInMan % 10000
            if (man > 0) "${eok}억${man}만" else "${eok}억"
        } else "${amountInMan}만"
    }

    private fun extractAmount(text: String): Int {
        try {
            // 4억5천
            var m = Pattern.compile("(\\d+)억\\s*(\\d+)천").matcher(text)
            if (m.find()) return m.group(1)!!.toInt() * 10000 + m.group(2)!!.toInt() * 1000
            // 1억 630만
            m = Pattern.compile("(\\d+)억\\s*(\\d+)만").matcher(text)
            if (m.find()) return m.group(1)!!.toInt() * 10000 + m.group(2)!!.toInt()
            // 3억
            m = Pattern.compile("(\\d+)억").matcher(text)
            if (m.find()) {
                var total = m.group(1)!!.toInt() * 10000
                val mm = Pattern.compile("(\\d+)만").matcher(text)
                if (mm.find()) total += mm.group(1)!!.toInt()
                return total
            }
            // 9965만
            m = Pattern.compile("(\\d+)만").matcher(text)
            if (m.find()) return m.group(1)!!.toInt()
            // 콤마 숫자
            m = Pattern.compile("([\\d,]+)$").matcher(text.trim())
            if (m.find()) {
                val numStr = m.group(1)!!.replace(",", "")
                if (numStr.isNotEmpty()) return numStr.toInt()
            }
        } catch (e: Exception) {
            Log.e("HWP_PARSE", "금액 추출 오류: $text", e)
        }
        return 0
    }

    fun processTextResult(visionText: com.google.mlkit.vision.text.Text) {
        showToast("이 기능은 비활성화되었습니다.")
    }

    private fun processImage(bitmap: Bitmap) {
        val fromBitmap = InputImage.fromBitmap(bitmap, 0)
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        client.process(fromBitmap)
            .addOnSuccessListener { visionText -> processTextResult(visionText) }
            .addOnFailureListener { e -> showToast("Text recognition failed: ${e.message}") }
    }

    private fun readExcelFile(uri: Uri) {
        throw UnsupportedOperationException("Method not decompiled")
    }

    private fun getCellValue(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue ?: ""
            CellType.NUMERIC -> cell.numericCellValue.toString()
            else -> ""
        }
    }

    private fun saveResultToFileAndShare(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(buildResultText().toByteArray(Charsets.UTF_8))
            }
            showToast("파일로 저장되었습니다.")
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("저장에 실패하였습니다.")
        }
    }

    private fun buildResultText(): String {
        return buildString {
            append(binding.name.text).append("\n\n")
            append(binding.card.text).append('\n')
            append(binding.dat.text).append('\n')
            append(binding.money.text).append("\n\n")
            append(binding.use.text).append('\n')
            append(binding.half.text).append("\n\n")
            append(binding.test1.text).append('\n')
            append(binding.test2.text).append("\n\n")
            append(binding.testing.text)
        }
    }

    private fun resetAllData() {
        // 멤버 변수 초기화
        acost = 0; bCost = 0.0; bValue = 0; card = 0; cost = 0; value = 0
        baby = "0"; korea = "X"

        // AI 데이터 초기화
        aiIncome = 0; aiTargetDebt = 0; aiProperty = 0
        aiCreditorCount = 0; aiDefermentMonths = 0; aiSogumwonMonthly = 0
        aiDataReady = false
        aiRecentDebt = 0; aiMajorCreditor = ""; aiMajorCreditorDebt = 0
        aiNonAffiliatedDebt = 0; aiHasBusinessHistory = false
        aiBusinessStartYear = 0; aiBusinessEndYear = 0; aiHasRecoveryPlan = false

        // 파일 텍스트 초기화
        hwpText = ""; pdfText = ""
        userPdfPassword = ""

        // companion object 초기화
        recognizedText4.clear(); recognizedText5.clear()

        // UI 초기화
        binding.name.text = ""; binding.card.text = ""; binding.dat.text = ""
        binding.money.text = ""; binding.use.text = ""; binding.test1.text = ""
        binding.test2.text = ""; binding.half.text = ""; binding.testing.text = ""

        Log.d("DATA_RESET", "모든 데이터 초기화 완료")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // AI 데이터 추출
    private fun extractDataWithAI(text: String) {
        runOnUiThread {
            loadingDialog = android.app.ProgressDialog(this).apply {
                setMessage("AI 데이터 분석 중...")
                setCancelable(false)
                show()
            }
        }
        AiDataExtractor.extract(text, object : AiDataExtractor.OnExtractListener {
            override fun onSuccess(result: AiDataExtractor.ExtractResult) {
                aiIncome = result.income
                aiTargetDebt = result.targetDebt
                aiProperty = result.property
                aiCreditorCount = result.creditorCount
                aiDefermentMonths = result.defermentMonths
                aiSogumwonMonthly = result.sogumwonMonthly
                aiRecentDebt = result.recentDebt
                aiMajorCreditor = result.majorCreditor
                aiMajorCreditorDebt = result.majorCreditorDebt
                aiNonAffiliatedDebt = result.nonAffiliatedDebt
                aiHasBusinessHistory = result.hasBusinessHistory
                aiBusinessStartYear = result.businessStartYear
                aiBusinessEndYear = result.businessEndYear
                aiHasRecoveryPlan = result.hasRecoveryPlan

                // 소금원 소득이 HWP 소득보다 높으면 소금원 사용
                if (aiSogumwonMonthly > aiIncome) {
                    Log.d("AI_EXTRACT", "소금원 소득($aiSogumwonMonthly) > HWP 소득($aiIncome) → 소금원 적용")
                    aiIncome = aiSogumwonMonthly
                }

                runOnUiThread {
                    loadingDialog?.dismiss()
                    Log.d("AI_EXTRACT", "AI 값 적용하여 재계산 시작")
                    if (hwpText.isNotEmpty()) parseHwpData(hwpText)
                    showToast("AI 데이터 반영 완료")
                }
            }
            override fun onError(error: String) {
                runOnUiThread {
                    loadingDialog?.dismiss()
                    showToast("AI 추출 실패: $error")
                }
            }
        })
    }
}