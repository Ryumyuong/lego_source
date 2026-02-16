package com.main.legos

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.main.legos.databinding.ActivityMainBinding
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
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern



class MainActivity : AppCompatActivity() {

    companion object {
        const val CREATE_FILE_REQUEST_CODE = 43
        const val READ_REQUEST_CODE = 42
        const val BATCH_REQUEST_CODE = 44
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

    // aiTargetDebt 제거됨 - 코드에서 직접 파싱
    // aiProperty 제거됨 - 코드에서 parsedProperty로 직접 파싱
    // aiCreditorCount/aiMajorCreditor/aiNonAffiliatedDebt 제거됨 - 코드에서 직접 파싱
    private var aiDefermentMonths = 0
    private var aiSogumwonMonthly = 0

    // AI 데이터 추출
    private var aiDataReady = false
    private var loadingDialog: android.app.ProgressDialog? = null

    // aiHasBusinessHistory/aiBusinessStart/End 제거됨 - 코드에서 직접 파싱
    private var aiHasRecoveryPlan = false    // 변제계획안 존재 (개인회생 진행 중)
    // aiOthersProperty 제거됨 - parsedOthersProperty로 대체
    private var pdfAgreementDebt = 0           // 합의서 PDF 대상채무 (만원)
    private var pdfAgreementProcess = ""       // 합의서 PDF 진행중 제도 (신속/프리/워크)
    private var pdfApplicationDate = ""        // 상환내역서 PDF 신청일자 (YYYY.MM.DD)
    private var hasPdfFile = false             // PDF 파일 존재 여부
    // aiDamboDebt 제거됨 - parsedDamboTotal로 대체
    // aiTaxDebt 제거됨 - 코드에서 textTaxDebt로 직접 파싱
    private var pdfExcludedGuaranteeDebt = 0  // 합의서 제외 보증서담보대출 채무 (만원)
    private var pdfExcludedOtherDebt = 0      // 합의서 제외 기타 채무 (만원)
    private var aiExtractCancelled = false    // AI 추출 중단 여부

    // 여러 파일 처리
    private var hwpText = ""
    private var pdfText = ""


    // 배치 진단
    private var batchMode = false
    private var batchUriList = ArrayList<Uri>()
    private var batchIndex = 0
    private var batchResults = ArrayList<String>()
    private var batchDialog: android.app.ProgressDialog? = null

    // 배치 파일 그룹 (같은 이름의 HWP+PDF를 묶음)
    private data class BatchFileGroup(
        val baseName: String,
        val hwpUri: Uri,
        val pdfUris: List<Uri>
    )
    private var batchGroups = ArrayList<BatchFileGroup>()

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

        binding.buttonBatchDiagnosis.setOnClickListener {
            resetAllData()
            batchMode = false
            batchUriList.clear()
            batchGroups.clear()
            batchResults.clear()
            batchIndex = 0
            openBatchFilePicker()
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
        } else if (requestCode == BATCH_REQUEST_CODE && resultCode == RESULT_OK && resultData != null) {
            // 선택된 URI 수집
            resultData.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    batchUriList.add(clipData.getItemAt(i).uri)
                }
            } ?: resultData.data?.let { batchUriList.add(it) }

            // HWP/PDF 아닌 파일 제거
            batchUriList = ArrayList(batchUriList.filter { uri ->
                val name = getFileName(uri)?.lowercase() ?: ""
                name.endsWith(".hwp") || name.endsWith(".pdf")
            })

            // 현재 누적 현황 보여주며 추가/시작 선택
            showBatchFileDialog()
        } else if (requestCode == CREATE_FILE_REQUEST_CODE && resultData?.data != null) {
            saveResultToFileAndShare(resultData.data!!)
        }
    }

    private fun startProcessing(uriList: ArrayList<Uri>) {
        pendingUriList.clear()
        processMultipleFiles(uriList)
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
                    hasPdfFile = true
                }
                lowerName.endsWith(".xlsx") -> {
                    readExcelFile(uri)
                    return
                }
            }
        }

        // 2단계: PDF 처리
        // 변제계획안/소금원 PDF만 AI 통합 텍스트에 포함, 상환내역서 등 기타 PDF는 이미지 OCR로 유예기간 추출
        val ocrPdfUris = ArrayList<Pair<Uri, String>>() // 이미지 OCR 대상 PDF
        for ((uri, fileName) in pdfUris) {
            val lowerFileName = fileName.lowercase()
            val isRelevantPdf = lowerFileName.contains("변제계획") || lowerFileName.contains("소금원") ||
                lowerFileName.contains("소득금액") || lowerFileName.contains("변제예정")
            if (isRelevantPdf) {
                val text = extractPdfText(uri)
                if (text.isNotEmpty()) {
                    pdfTexts.add("=== PDF: $fileName ===\n$text")
                    Log.d("FILE_PROCESS", "PDF 파일 처리 (AI포함): $fileName (${text.length}자)")
                }
            } else {
                // 상환내역서 등: 이미지 OCR로 유예기간 추출
                ocrPdfUris.add(Pair(uri, fileName))
            }
        }

        if (ocrPdfUris.isNotEmpty()) {
            // OCR 완료 후 나머지 처리 진행
            extractDataFromPdfImages(ocrPdfUris) { ocrResult ->
                if (ocrResult.defermentMonths > 0) {
                    hwpText += "\n유예기간 ${ocrResult.defermentMonths}개월"
                    Log.d("FILE_PROCESS", "PDF OCR 유예기간 추출: ${ocrResult.defermentMonths}개월")
                }
                if (ocrResult.agreementDebt > 0) {
                    pdfAgreementDebt = ocrResult.agreementDebt
                    Log.d("FILE_PROCESS", "PDF OCR 합의서 대상채무 추출: ${ocrResult.agreementDebt}만")
                }
                if (ocrResult.processName.isNotEmpty()) {
                    pdfAgreementProcess = ocrResult.processName
                    Log.d("FILE_PROCESS", "PDF OCR 진행중 제도: ${ocrResult.processName}")
                }
                if (ocrResult.applicationDate.isNotEmpty()) {
                    pdfApplicationDate = ocrResult.applicationDate
                    Log.d("FILE_PROCESS", "PDF OCR 신청일자 추출: ${ocrResult.applicationDate}")
                }
                if (ocrResult.excludedGuaranteeDebt > 0) {
                    pdfExcludedGuaranteeDebt = ocrResult.excludedGuaranteeDebt
                    Log.d("FILE_PROCESS", "PDF 합의서 제외 보증서담보대출: ${ocrResult.excludedGuaranteeDebt}만")
                }
                if (ocrResult.excludedOtherDebt > 0) {
                    pdfExcludedOtherDebt = ocrResult.excludedOtherDebt
                    Log.d("FILE_PROCESS", "PDF 합의서 제외 기타: ${ocrResult.excludedOtherDebt}만")
                }
                finishFileProcessing(pdfTexts)
            }
        } else {
            finishFileProcessing(pdfTexts)
        }
    }

    private fun finishFileProcessing(pdfTexts: ArrayList<String>) {
        pdfText = pdfTexts.joinToString("\n\n")

        val combinedText = buildString {
            if (hwpText.isNotEmpty()) append("=== HWP 파일 내용 ===\n$hwpText\n\n")
            if (pdfText.isNotEmpty()) append("=== PDF 파일 내용 ===\n$pdfText\n\n")
        }

        if (combinedText.isNotEmpty()) {
            Log.d("FILE_PROCESS", "통합 텍스트 길이: ${combinedText.length}")
            extractDataWithAI(combinedText)
        } else if (hwpText.isNotEmpty()) {
            parseHwpData(hwpText)
            showToast("파일 처리 완료 (AI 없이)")
        } else {
            showToast("처리할 파일이 없습니다")
        }
    }

    data class PdfOcrResult(val defermentMonths: Int, val agreementDebt: Int, val processName: String, val applicationDate: String = "", val excludedGuaranteeDebt: Int = 0, val excludedOtherDebt: Int = 0)

    private fun extractDataFromPdfImages(
        pdfUris: List<Pair<Uri, String>>,
        onComplete: (PdfOcrResult) -> Unit
    ) {
        var maxDefermentMonths = 0
        var agreementDebt = 0
        var detectedProcess = ""
        var detectedApplicationDate = ""
        var excludedGuaranteeDebt = 0
        var excludedOtherDebt = 0
        var remaining = pdfUris.size

        for ((uri, fileName) in pdfUris) {
            try {
                val tempFile = File.createTempFile("pdf_", ".pdf", cacheDir)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                val fd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fd)

                val pageCount = renderer.pageCount
                if (pageCount > 0) {
                    val isAgreement = fileName.lowercase().contains("합의")

                    if (isAgreement) {
                        // ===== 합의서: Gemini AI 비전으로 처리 =====
                        val bitmaps = mutableListOf<Bitmap>()
                        for (pageIdx in 0 until pageCount) {
                            val page = renderer.openPage(pageIdx)
                            val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            bitmaps.add(bitmap)
                            Log.d("FILE_PROCESS", "합의서 PDF→이미지: $fileName p${pageIdx + 1}/${pageCount} (${bitmap.width}x${bitmap.height})")
                        }
                        renderer.close()
                        fd.close()
                        tempFile.delete()

                        // Gemini AI 비전 호출 (백그라운드 스레드)
                        Thread {
                            try {
                                val result = callGeminiVisionForAgreement(bitmaps, fileName)
                                if (result.debt > 0) agreementDebt = result.debt
                                if (result.process.isNotEmpty()) detectedProcess = result.process
                                if (result.deferment > 0 && result.deferment > maxDefermentMonths) maxDefermentMonths = result.deferment
                                if (result.excludedGuarantee > 0) excludedGuaranteeDebt = result.excludedGuarantee
                                if (result.excludedOther > 0) excludedOtherDebt = result.excludedOther
                            } catch (e: Exception) {
                                Log.e("FILE_PROCESS", "합의서 Gemini AI 실패 ($fileName): ${e.message}")
                            }
                            runOnUiThread {
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, agreementDebt, detectedProcess, detectedApplicationDate, excludedGuaranteeDebt, excludedOtherDebt))
                            }
                        }.start()
                    } else {
                        // ===== 비합의서: 상환내역서는 Gemini Vision, 기타는 ML Kit OCR =====
                        val isRepayment = fileName.lowercase().contains("상환")
                        val page = renderer.openPage(0)
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        renderer.close()
                        fd.close()
                        tempFile.delete()

                        Log.d("FILE_PROCESS", "PDF→이미지 변환: $fileName p1/${pageCount} (${bitmap.width}x${bitmap.height})")

                        if (isRepayment) {
                            // ===== 상환내역서: Gemini Vision으로 신청일자 + 유예기간 추출 =====
                            Thread {
                                try {
                                    val result = callGeminiVisionForRepayment(bitmap, fileName)
                                    if (result.first.isNotEmpty()) detectedApplicationDate = result.first
                                    if (result.second > 0 && result.second > maxDefermentMonths) maxDefermentMonths = result.second
                                } catch (e: Exception) {
                                    Log.e("FILE_PROCESS", "상환내역서 Gemini AI 실패 ($fileName): ${e.message}")
                                }
                                runOnUiThread {
                                    remaining--
                                    if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, agreementDebt, detectedProcess, detectedApplicationDate, excludedGuaranteeDebt, excludedOtherDebt))
                                }
                            }.start()
                        } else {
                            // ===== 기타 PDF: ML Kit OCR로 유예기간 추출 =====
                            val image = InputImage.fromBitmap(bitmap, 0)
                            val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                            client.process(image)
                                .addOnSuccessListener { visionText ->
                                    val ocrText = visionText.text
                                    Log.d("FILE_PROCESS", "PDF OCR p1 ($fileName): ${ocrText.take(500)}")
                                    val ocrNoSpace = ocrText.replace(" ", "")

                                    // 유예기간 추출
                                    if (ocrNoSpace.contains("유예기간") || ocrNoSpace.contains("거치기간")) {
                                        val monthMatch = Regex("(?:유예기간|거치기간)\\s*(\\d+)\\s*개?월").find(ocrText)
                                            ?: Regex("(?:유예기간|거치기간)[^\\d]{0,10}(\\d+)\\s*개?월").find(ocrText)
                                        if (monthMatch != null) {
                                            val months = monthMatch.groupValues[1].toInt()
                                            if (months > maxDefermentMonths) maxDefermentMonths = months
                                            Log.d("FILE_PROCESS", "PDF OCR 유예기간 감지: ${months}개월 ($fileName)")
                                        }
                                    }

                                    // 신청일자 추출
                                    if (ocrNoSpace.contains("신청일자") && detectedApplicationDate.isEmpty()) {
                                        val appDateMatch = Regex("신청일자\\s*(\\d{4})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})").find(ocrText)
                                        if (appDateMatch != null) {
                                            detectedApplicationDate = "${appDateMatch.groupValues[1]}.${appDateMatch.groupValues[2].padStart(2, '0')}.${appDateMatch.groupValues[3].padStart(2, '0')}"
                                            Log.d("FILE_PROCESS", "PDF OCR 신청일자 감지: $detectedApplicationDate ($fileName)")
                                        }
                                    }

                                    remaining--
                                    if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, agreementDebt, detectedProcess, detectedApplicationDate))
                                }
                                .addOnFailureListener { e ->
                                    Log.e("FILE_PROCESS", "PDF OCR 실패 ($fileName): ${e.message}")
                                    remaining--
                                    if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, agreementDebt, detectedProcess, detectedApplicationDate))
                                }
                        }
                    }
                } else {
                    Log.d("FILE_PROCESS", "PDF 페이지 없음: $fileName")
                    renderer.close()
                    fd.close()
                    tempFile.delete()
                    remaining--
                    if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, agreementDebt, detectedProcess, detectedApplicationDate))
                }
            } catch (e: Exception) {
                Log.e("FILE_PROCESS", "PDF 이미지 변환 실패 ($fileName): ${e.message}")
                remaining--
                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, agreementDebt, detectedProcess, detectedApplicationDate))
            }
        }
    }

    data class AgreementResult(val debt: Int, val process: String, val deferment: Int, val excludedGuarantee: Int = 0, val excludedOther: Int = 0)

    /**
     * 합의서 PDF 이미지를 Gemini AI 비전으로 분석하여 대상채무, 제도 타입, 유예기간, 제외채무 추출
     */
    private fun callGeminiVisionForAgreement(bitmaps: List<Bitmap>, fileName: String): AgreementResult {
        val apiKey = BuildConfig.GEMINI_API_KEY
        val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        val prompt = """이 PDF 이미지는 채무조정 합의서입니다. 다음 정보를 추출하세요.

[1] 제도 타입 (문서 제목/헤더에서 판단! 우선순위 중요!)
★ 첫 페이지 상단의 제목, "제 목:", 문서 헤더, 굵은 글씨 제목 등에서 판단
★★★ 우선순위: 신속 > 사전 > 개인채무조정 (먼저 해당되면 그것으로!)
- "신속채무조정" 또는 "신속" 글자가 포함 → 무조건 "신" (다른 키워드 무시!)
  예: "개인채무조정(신속채무조정(추가형)/특례) 확정 통지" → "신"
  예: "신속채무조정 합의서" → "신"
- "사전채무조정" 또는 "사전" 글자가 포함 → "프"
- "신속"/"사전" 글자가 없고 "개인채무조정" 포함 → "워"
  예: "개인채무조정 확정 통지" → "워"
  예: "개인채무조정 확정 통지서" → "워"
  예: "개인채무조정 합의서" → "워"
- 위에 해당 안되지만 "채무조정" 또는 "합의서" 제목이 있으면 → "워"
- 해당없으면 → ""

[2] 합계 전 원금 (대상채무)
- 표에서 "합계" 행의 "전" (조정 전) 열의 "원금" 값을 찾으세요
- 단위는 원(won)입니다
- 표 구조: 각 채권자별로 전(원금, 이자 등), 후(원금, 이자 등) 열이 있음
- "합계" 행에서 "전" 쪽의 "원금" 컬럼 값이 필요합니다
- 콤마 포맷 숫자를 원 단위 정수로 반환

[3] 유예기간 (거치기간)
- "유예기간" 또는 "거치기간" 다음에 나오는 개월 수
- 없으면 0

[4] 개인채무조정에서 제외된 채무내역 (표가 있는 경우만)
- "제외된 채무내역" 또는 "개인채무조정에서 제외된 채무내역" 표에서:
  - 제외사유가 "보증서 담보대출" 또는 "보증서담보대출"인 행의 원금 합계 (원 단위)
  - 그 외 제외사유인 행의 원금 합계 (원 단위)
- 표가 없으면 둘 다 0

반드시 JSON만 응답:
{"agreementDebt": 숫자(원단위), "processName": "문자열", "defermentMonths": 숫자, "excludedGuaranteeDebt": 숫자(원단위), "excludedOtherDebt": 숫자(원단위)}"""

        // parts 배열 구성: 텍스트 프롬프트 + 이미지들
        val parts = JSONArray()
        parts.put(JSONObject().put("text", prompt))

        for ((idx, bitmap) in bitmaps.withIndex()) {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val imageBytes = baos.toByteArray()
            val base64Str = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)

            val inlineData = JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", base64Str)
            parts.put(JSONObject().put("inlineData", inlineData))
            Log.d("FILE_PROCESS", "합의서 Gemini: 이미지 ${idx + 1}/${bitmaps.size} 추가 (${imageBytes.size} bytes)")
        }

        val content = JSONObject().put("parts", parts)
        val contents = JSONArray().put(content)
        val requestBody = JSONObject().put("contents", contents)

        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 120000
        conn.readTimeout = 120000

        conn.outputStream.use { os ->
            os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorBody = conn.errorStream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: "no error body"
            Log.e("FILE_PROCESS", "합의서 Gemini API 오류 ($responseCode): $errorBody")
            return AgreementResult(0, "", 0)
        }

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
            }
        } catch (e: Exception) {
            Log.e("FILE_PROCESS", "합의서 Gemini 응답 파싱 실패: ${e.message}")
            return AgreementResult(0, "", 0)
        }

        Log.d("FILE_PROCESS", "합의서 Gemini AI 응답: $aiText")

        // JSON 추출
        val jsonStr = aiText
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()
        val jsonStart = jsonStr.indexOf("{")
        val jsonEnd = jsonStr.lastIndexOf("}")
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            Log.e("FILE_PROCESS", "합의서 Gemini JSON 파싱 실패: $jsonStr")
            return AgreementResult(0, "", 0)
        }

        val data = JSONObject(jsonStr.substring(jsonStart, jsonEnd + 1))
        val debtWon = data.optLong("agreementDebt", 0)
        val processName = data.optString("processName", "")
        val defermentMonths = data.optInt("defermentMonths", 0)
        val excludedGuaranteeWon = data.optLong("excludedGuaranteeDebt", 0)
        val excludedOtherWon = data.optLong("excludedOtherDebt", 0)

        val debtMan = if (debtWon > 0) (debtWon / 10000).toInt() else 0
        val excludedGuaranteeMan = if (excludedGuaranteeWon > 0) (excludedGuaranteeWon / 10000).toInt() else 0
        val excludedOtherMan = if (excludedOtherWon > 0) (excludedOtherWon / 10000).toInt() else 0
        Log.d("FILE_PROCESS", "합의서 Gemini 결과: 대상채무=${debtMan}만(${debtWon}원), 제도=$processName, 유예=${defermentMonths}개월, 제외보증서=${excludedGuaranteeMan}만, 제외기타=${excludedOtherMan}만 ($fileName)")

        return AgreementResult(debtMan, processName, defermentMonths, excludedGuaranteeMan, excludedOtherMan)
    }

    /**
     * 상환내역서 PDF 이미지를 Gemini AI 비전으로 분석하여 신청일자, 유예기간 추출
     * @return Pair(신청일자 YYYY.MM.DD, 유예기간 개월)
     */
    private fun callGeminiVisionForRepayment(bitmap: Bitmap, fileName: String): Pair<String, Int> {
        val apiKey = BuildConfig.GEMINI_API_KEY
        val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        val prompt = """이 PDF 이미지는 채무조정 상환내역서입니다. 다음 정보를 추출하세요.

[1] 신청일자
- 표에서 "신청일자" 라벨에 해당하는 값을 찾으세요
- 테이블 구조에서 라벨과 값이 다른 셀에 있을 수 있습니다
- YYYY.MM.DD 형식으로 응답 (예: 2024.08.14)
- 없으면 빈 문자열 ""

[2] 유예기간 (거치기간)
- "유예기간" 또는 "거치기간" 다음에 나오는 개월 수
- 없으면 0

반드시 JSON만 응답:
{"applicationDate": "YYYY.MM.DD", "defermentMonths": 숫자}"""

        val parts = JSONArray()
        parts.put(JSONObject().put("text", prompt))

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val imageBytes = baos.toByteArray()
        val base64Str = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)

        val inlineData = JSONObject()
            .put("mimeType", "image/jpeg")
            .put("data", base64Str)
        parts.put(JSONObject().put("inlineData", inlineData))
        Log.d("FILE_PROCESS", "상환내역서 Gemini: 이미지 추가 (${imageBytes.size} bytes)")

        val content = JSONObject().put("parts", parts)
        val contents = JSONArray().put(content)
        val requestBody = JSONObject().put("contents", contents)

        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 120000
        conn.readTimeout = 120000

        conn.outputStream.use { os ->
            os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorBody = conn.errorStream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: "no error body"
            Log.e("FILE_PROCESS", "상환내역서 Gemini API 오류 ($responseCode): $errorBody")
            return Pair("", 0)
        }

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
            }
        } catch (e: Exception) {
            Log.e("FILE_PROCESS", "상환내역서 Gemini 응답 파싱 실패: ${e.message}")
            return Pair("", 0)
        }

        Log.d("FILE_PROCESS", "상환내역서 Gemini AI 응답: $aiText")

        val jsonStr = aiText
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()
        val jsonStart = jsonStr.indexOf("{")
        val jsonEnd = jsonStr.lastIndexOf("}")
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            Log.e("FILE_PROCESS", "상환내역서 Gemini JSON 파싱 실패: $jsonStr")
            return Pair("", 0)
        }

        val data = JSONObject(jsonStr.substring(jsonStart, jsonEnd + 1))
        val applicationDate = data.optString("applicationDate", "")
        val defermentMonths = data.optInt("defermentMonths", 0)

        Log.d("FILE_PROCESS", "상환내역서 Gemini 결과: 신청일자=$applicationDate, 유예=${defermentMonths}개월 ($fileName)")

        return Pair(applicationDate, defermentMonths)
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
            Log.e("HWP_EXTRACT", "HWP 추출 실패 (hwplib): ${e.message}, fallback 시도")
            // hwplib 파싱 실패 시 OLE2 구조에서 직접 텍스트 추출 시도
            try {
                val fallbackText = extractHwpTextFallback(uri)
                if (fallbackText.isNotEmpty()) {
                    sb.append(fallbackText)
                    Log.d("HWP_EXTRACT", "Fallback 추출 성공: ${fallbackText.length}자")
                }
            } catch (fe: Exception) {
                Log.e("HWP_EXTRACT", "Fallback도 실패: ${fe.message}")
            }
        }
        return sb.toString()
    }

    // HWP OLE2 구조에서 직접 텍스트 추출 (hwplib 파싱 실패 시 fallback)
    private fun extractHwpTextFallback(uri: Uri): String {
        val sb = StringBuilder()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val fs = POIFSFileSystem(inputStream)
            val root = fs.root

            // BodyText 디렉토리에서 Section 스트림 읽기
            val bodyText = try { root.getEntry("BodyText") } catch (e: Exception) { null }
            if (bodyText != null && bodyText is org.apache.poi.poifs.filesystem.DirectoryEntry) {
                val sectionEntries = bodyText.entries.asSequence()
                    .filter { it.name.startsWith("Section") }
                    .sortedBy { it.name }
                    .toList()

                for (entry in sectionEntries) {
                    if (entry is org.apache.poi.poifs.filesystem.DocumentEntry) {
                        try {
                            val docStream = org.apache.poi.poifs.filesystem.DocumentInputStream(entry)
                            val compressed = docStream.readAllBytes()
                            docStream.close()

                            // HWP BodyText 섹션은 zlib 압축됨
                            val decompressed = try {
                                val inflater = java.util.zip.Inflater(true)
                                inflater.setInput(compressed)
                                val buffer = ByteArray(compressed.size * 10)
                                val len = inflater.inflate(buffer)
                                inflater.end()
                                buffer.copyOf(len)
                            } catch (e: Exception) {
                                // 압축되지 않은 경우 원본 사용
                                compressed
                            }

                            // 레코드에서 텍스트 추출
                            extractTextFromRecords(decompressed, sb)
                        } catch (e: Exception) {
                            Log.w("HWP_EXTRACT", "Section 읽기 실패: ${entry.name}, ${e.message}")
                        }
                    }
                }
            }
            fs.close()
        }
        return sb.toString()
    }

    // HWP 레코드 스트림에서 텍스트(PARA_TEXT 태그) 추출
    private fun extractTextFromRecords(data: ByteArray, sb: StringBuilder) {
        var offset = 0
        while (offset + 4 <= data.size) {
            // 레코드 헤더: 4바이트 (tagID:10bit, level:10bit, size:12bit)
            val header = (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    ((data[offset + 2].toInt() and 0xFF) shl 16) or
                    ((data[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4

            val tagID = header and 0x3FF
            var size = (header shr 20) and 0xFFF

            // 확장 크기 (size == 0xFFF인 경우 다음 4바이트가 실제 크기)
            if (size == 0xFFF) {
                if (offset + 4 > data.size) break
                size = (data[offset].toInt() and 0xFF) or
                        ((data[offset + 1].toInt() and 0xFF) shl 8) or
                        ((data[offset + 2].toInt() and 0xFF) shl 16) or
                        ((data[offset + 3].toInt() and 0xFF) shl 24)
                offset += 4
            }

            if (offset + size > data.size) break

            // HWPTAG_PARA_TEXT = 67 (HWPTAG_BEGIN + 51 = 16 + 51 = 67)
            if (tagID == 67) {
                val textSb = StringBuilder()
                var i = 0
                while (i + 1 < size) {
                    val ch = (data[offset + i].toInt() and 0xFF) or
                            ((data[offset + i + 1].toInt() and 0xFF) shl 8)
                    i += 2

                    when {
                        ch == 0 -> {} // NULL
                        ch == 10 || ch == 13 -> textSb.append("\n") // 줄바꿈
                        ch == 9 -> textSb.append("\t") // 탭
                        ch < 32 -> {
                            // HWP 인라인 컨트롤 문자 (1~31 중 일부는 확장 문자로 추가 바이트 소비)
                            when (ch) {
                                1, 2, 3, 11, 12, 14, 15, 16, 17, 18, 21, 22, 23 -> {
                                    i += 12 // 인라인 확장 문자: 추가 12바이트 건너뛰기
                                }
                            }
                        }
                        else -> textSb.append(ch.toChar())
                    }
                }
                val lineText = textSb.toString().trim()
                if (lineText.isNotEmpty()) {
                    sb.append(lineText).append("\n")
                }
            }

            offset += size
        }
    }

    // ============= PDF 텍스트 추출 =============
    private fun extractPdfText(uri: Uri): String {
        val sb = StringBuilder()
        try {
            var document: com.tom_roush.pdfbox.pdmodel.PDDocument? = null
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputStream)
                    Log.d("PDF_EXTRACT", "PDF 열기 성공")
                }
            } catch (e: Exception) {
                Log.e("PDF_EXTRACT", "PDF 열기 실패: ${e.message}")
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
        var income = 0  // 코드에서 직접 파싱 (AI 사용 안함)
        var targetDebt = 0  // 코드에서 직접 파싱 (AI 사용 안함)
        var netProperty = 0  // 코드에서 직접 파싱 (AI 사용 안함)

        // ★ AI 추출 값 직접 사용 (로직에서 중복 계산하지 않음)
        var taxDebt = 0                                // 국세/세금 채무 (코드파싱, 단기에만 포함)
        var majorCreditorName = ""                     // 과반 채권사명 (코드파싱)
        var majorCreditorDebtVal = 0                   // 과반 채권사 채무 (코드파싱, 만원)
        var hasBusinessHistory = false   // 사업자 이력 (코드 파싱)
        var businessStartYear = 0      // 개업 년도
        var businessStartMonth = 0     // 개업 월
        var businessEndYear = 0        // 폐업 년도

        // 보조 정보 (텍스트에서 파싱)
        var name = ""
        var region = ""
        var minorChildren = 0
        var collegeChildren = 0
        var parentCount = 0
        var hasSpouse = false
        var isDivorced = false
        var delinquentDays = 0
        var actualDelinquentDays = 0  // 실제 연체일수 (다른 단계 진행으로 인한 1095일 제외)
        var hasDischarge = false
        var isBankruptcyDischarge = false  // 파산 면책 여부
        var dischargeYear = 0
        var dischargeMonth = 0
        var hasShinbokwiHistory = false
        var isBusinessOwner = false
        var isFreelancer = false
        var isNonProfit = false
        var isCorporateBusiness = false
        var hasBusinessLoan = false
        var hasGambling = false
        var hasStock = false
        var hasCrypto = false
        var carValue = 0
        var carTotalSise = 0
        var carTotalLoan = 0
        var carMonthlyPayment = 0
        var carCount = 0
        // 외제차 브랜드 키워드
        val foreignCarBrands = listOf("벤츠", "메르세데스", "BMW", "비엠더블유", "아우디", "렉서스", "포르쉐",
            "볼보", "미니쿠퍼", "테슬라", "폭스바겐", "토요타", "혼다", "닛산", "인피니티",
            "재규어", "랜드로버", "링컨", "캐딜락", "지프", "외제", "수입차")
        // 개별 차량 정보: [시세, 대출, 월납부, 배우자(1/0), 외제(1/0)]
        val carInfoList = ArrayList<IntArray>()
        var spouseSecret = false  // 배우자 모르게 진행
        var familySecret = false  // 가족 모르게
        var hasAuction = false    // 경매 여부
        var hasSeizure = false    // 압류 여부
        var hasCivilCase = false   // 민사/소송 여부
        var civilAmount = 0        // 민사 소송금액 (만원)
        var hasUsedCarInstallment = false  // 중고차 할부
        var hasHealthInsuranceDebt = false  // 건강보험 체납
        var hasInsurancePolicyLoan = false  // 보험약관대출
        var hasHfcMortgage = false  // 한국주택금융공사 집담보
        var hasOthersRealEstate = false  // 타인명의 부동산 (배우자/부모/형제 등)
        var hasOwnRealEstate = false     // 본인/공동명의 부동산
        var savingsDeposit = 0  // 예적금 금액 (만원)
        var parsedProperty = 0  // 코드에서 파싱한 재산 합계 (만원)
        var parsedOthersProperty = 0  // 코드에서 파싱한 타인명의 재산 (만원)
        var isPropertyX = false  // "재산 x" → 재산 없음
        var jeonseNoJilgwon = false  // 전세대출 질권설정x → 대상채무 포함
        var excludedSeqNumbers = mutableSetOf<Int>()  // 대출과목에서 제외할 순번
        var includedSeqNumbers = mutableSetOf<Int>()  // 강제 포함할 순번 (순번N 신용대출)
        var hasOngoingProcess = false  // 다른 채무조정 진행 중
        var ongoingProcessName = ""   // 진행중인 제도명 (회/신/워)
        var isIncomeEstimated = false  // 소득 예상
        var estimatedIncomeParsed = 0  // HWP에서 파싱한 예정/예상 소득 (만원)
        var isIncomeX = false  // "월 소득 x" → 소득 없음 강제
        var parsedIncome = 0   // HWP 텍스트에서 직접 파싱한 소득 (만원)
        var parsedAnnualSalary = 0  // 연봉 (만원) → 실수령액 계산용
        var hasPaymentOrder = false  // 지급명령 받음
        var hasWorkoutExpired = false  // 워크아웃 실효
        var hasPersonalRecovery = false  // 개인회생 기록
        var isDismissed = false  // 폐지/기각/취하 (장기연체자)
        var personalRecoveryYear = 0
        var personalRecoveryMonth = 0
        var wantsCarSale = false  // 차량 처분 의사
        var childSupportAmount = 0  // 양육비 (만원)
        var nonAffiliatedDebt = 0  // 미협약 채무 (코드파싱)
        var studentLoanTotal = 0   // 학자금 합계 (천원)
        var tableDebtTotal = 0     // 표 전체 합계 (천원, 제외항목 포함)
        // 대출과목 파싱 (표 없을 때 대상채무 fallback)
        var inLoanCategorySection = false // 대출과목 섹션 진입 여부 (신복위 파싱 제외용)
        var textShinbokDebt = 0  // 대출과목에서 파싱한 신복위 채무 (만원, PDF 없을 때 사용)
        var textTaxDebt = 0      // 대출과목에서 파싱한 국세/세금 채무 (만원)
        val loanCategoryExcludedCreditors = mutableSetOf<Pair<String, String>>()  // 대출과목에서 담보로 표시된 (채권사명, 대출유형)
        val carDamboAmountsChun = mutableSetOf<Int>()  // 차량담보 금액 (천원 단위, 배우자 나누기 전 원본)
        val retirementDamboAmountsChun = mutableMapOf<String, MutableList<Int>>()  // 퇴직금담보 금액 (채권사 → 천원 리스트)
        val specialNotesList = ArrayList<String>()
        val recentDebtEntries = ArrayList<Pair<Calendar, Int>>() // (대출일, 금액_천원)
        var parsedDamboTotal = 0 // 표에서 파싱한 담보대출 합계 (만원)
        var totalParsedDebt = 0  // 표에서 파싱한 모든 채무 합계 (만원, 담보 포함)
        val cashAdvanceByCard = mutableMapOf<String, Int>() // 현금서비스(0041) 카드사별 금액 (만원)
        var parsedCardUsageTotal = 0 // 카드이용금액 합계 (만원)
        var inCardUsageSection = false // 카드이용금액 섹션 진입 여부
        val debtDateAmountSeen = mutableMapOf<String, Boolean>() // "날짜_금액" → isGuarantee (보증채무 중복 제거용)
        val businessLoanDates = mutableMapOf<String, Int>() // 운전자금/개인사업자대출 날짜 → 금액(천원) (지급보증 중복 제거용)
        var recentDebtRatio = 0.0
        var postApplicationDebtMan = 0  // 신청일자 이후 추가채무 합계 (만원)
        var jiguBojungExcludedMan = 0  // 지급보증 중복 제외 금액 (만원)
        val parsedCreditors = mutableMapOf<String, Int>() // 코드파싱 채권사별 대상채무 합계 (만원, 담보/학자금 제외)
        val parsedCardUsageCreditors = mutableMapOf<String, Int>() // 카드이용금액 채권사별 금액 (만원)
        var nonAffiliatedNames = mutableListOf<String>()  // 미협약 채권사명 (코드파싱)
        var parsedCreditorCount = 0  // 채권사 수 (코드파싱)
        // ★ 과반 비율: 대상채무 확정 후 계산
        var majorCreditorRatio = 0.0
        var belowDebtSection = false  // [채무현황] 아래쪽 여부 (재산 파싱은 위쪽에서만)
        var hasSeenSimplifiedFormat = false  // 간이형식 감지 여부 (날짜 없는 채무 라인 fallback용)

        // ============= 대출과목 사전 스캔 (채무현황보다 아래에 있으므로 먼저 파싱) =============
        var preScanLoanCategory = false
        for (preLine in lines) {
            val preNoSpace = preLine.trim().replace(" ", "")
            if (preNoSpace.contains("대출과목") || preNoSpace.contains("[대출과목]")) preScanLoanCategory = true
            if (preScanLoanCategory) {
                if (preNoSpace.contains("요약사항") || preNoSpace.contains("최저납부") || preNoSpace.contains("요약]") ||
                    (preNoSpace.startsWith("1.") || preNoSpace.startsWith("1．"))) preScanLoanCategory = false
                val damboMatches = Regex("([가-힣A-Za-zＡ-Ｚａ-ｚ]+)([^()]*)\\(\\s*(전세대출|전세|차담보|차량담보|담보|담보대출|퇴직금담보|퇴직금|중도금|약관)\\s*\\)").findAll(preLine.trim())
                for (match in damboMatches) {
                    val creditorName = match.groupValues[1].replace(" ", "")
                    val middleText = match.groupValues[2]
                    val damboType = match.groupValues[3]
                    loanCategoryExcludedCreditors.add(Pair(creditorName, damboType))
                    // 퇴직금담보: 금액이 명시된 경우 금액 매칭용으로 저장 (같은 채권사에 신용+퇴직금담보 혼재 대응)
                    if (damboType.contains("퇴직금")) {
                        val amounts = Regex("(\\d[\\d,]*)만").findAll(middleText)
                        for (amt in amounts) {
                            val manWon = amt.groupValues[1].replace(",", "").toInt()
                            retirementDamboAmountsChun.getOrPut(creditorName) { mutableListOf() }.add(manWon * 10) // 만원→천원
                        }
                    }
                }
            }
        }
        if (loanCategoryExcludedCreditors.isNotEmpty()) {
            Log.d("HWP_PARSE", "대출과목 사전 스캔 담보 채권사: ${loanCategoryExcludedCreditors.joinToString(", ") { "${it.first}(${it.second})" }}")
        }

        // ============= 보조 정보 추출 =============
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val lineNoSpace = line.replace(" ", "")

            // [채무현황] 섹션 감지
            if (lineNoSpace.contains("채무현황")) belowDebtSection = true

            // 이름 추출
            if (name.isEmpty() && lineNoSpace.contains("이름") && line.contains(":")) {
                val afterColon = line.substringAfter(":").trim().split("(", "（")[0].trim()
                if (afterColon.matches("^[가-힣]{2,5}$".toRegex())) {
                    name = afterColon
                }
            } else if (name.isEmpty() && line.length in 2..20) {
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

            // 부동산 소유 형태 감지 (타인명의 vs 본인/공동명의)
            val isRealEstateLine = !belowDebtSection &&
                    (lineNoSpace.contains("시세") || lineNoSpace.contains("공시지가") ||
                    lineNoSpace.contains("아파트") || lineNoSpace.contains("건물") ||
                    lineNoSpace.contains("분양권") || lineNoSpace.contains("전세") ||
                    lineNoSpace.contains("보증금")) &&
                    !lineNoSpace.contains("차량") && !lineNoSpace.contains("자동차") &&
                    !Pattern.compile("\\d{2}년식").matcher(lineNoSpace).find()
            if (isRealEstateLine) {
                val isOthersPropertyLine = lineNoSpace.contains("상대방") || lineNoSpace.contains("타인")
                // "명의" 포함 but 본인/공동이 아니면 타인명의, 상대방/타인 맥락도 타인
                val isOthersName = (lineNoSpace.contains("명의") &&
                    !lineNoSpace.contains("본인명의") && !lineNoSpace.contains("공동명의")) || isOthersPropertyLine
                if (isOthersName) {
                    hasOthersRealEstate = true
                } else if (lineNoSpace.contains("본인명의") && lineNoSpace.contains("전세")) {
                    val hasJilgwon = lineNoSpace.contains("질권") && !lineNoSpace.matches(Regex(".*질권설?정?[0xX없].*"))
                    if (hasJilgwon) hasOwnRealEstate = true
                } else if (!lineNoSpace.contains("전세") && !lineNoSpace.contains("월세")) {
                    hasOwnRealEstate = true
                }

                // ★ 재산 금액 파싱: ">" 또는 "–" 또는 "/" 로 구분된 항목에서 시세-대출-세입자 계산
                val isBaeuja = lineNoSpace.contains("배우자명의") || lineNoSpace.contains("배우자")
                val hasPropertyValue = lineNoSpace.contains("시세") || lineNoSpace.contains("공시지가")
                val isJeonseResidence = (lineNoSpace.contains("전세") || lineNoSpace.contains("월세")) && !hasPropertyValue
                val isBunyangWithPrice = lineNoSpace.contains("분양권") && (lineNoSpace.contains("분양가") || lineNoSpace.contains("시세"))
                val isBunyangNoPrice = lineNoSpace.contains("분양권") && !isBunyangWithPrice

                // ">" 또는 "–" 또는 "/" 로 분리
                val parts = line.split(Regex("[>–/]")).map { it.trim() }
                var grossValue = 0  // 시세/전세금/보증금/공시지가/분양가/계약금
                var deductions = 0  // 대출/세입자/근저당/중도금/전세보증금

                for (part in parts) {
                    val partNoSpace = part.replace(" ", "")
                    val amount = extractAmount(part)
                    if (amount <= 0) continue

                    when {
                        // 시세/공시지가/분양가 → 총 가치
                        partNoSpace.contains("시세") || partNoSpace.contains("공시지가") || partNoSpace.contains("분양가") -> {
                            grossValue += amount
                        }
                        // 전세금/보증금 → 시세가 없는 전세 거주 라인에서만 총 가치
                        isJeonseResidence && (partNoSpace.contains("전세") || partNoSpace.contains("보증금")) -> {
                            grossValue += amount
                        }
                        // 전세금/보증금 → 시세가 있는 소유 부동산의 세입자 보증금 (차감)
                        hasPropertyValue && (partNoSpace.contains("전세") || partNoSpace.contains("보증금")) -> {
                            deductions += amount
                        }
                        // 보증금 (월세 > 보증금 3000만 형태)
                        partNoSpace.contains("보증금") && grossValue == 0 -> {
                            grossValue += amount
                        }
                        // 분양권 계약금 (분양가 없을 때 = 총 가치)
                        isBunyangNoPrice && partNoSpace.contains("계약금") -> {
                            grossValue += amount
                        }
                        // 분양권 계약금 (분양가 있을 때 = 차감)
                        isBunyangWithPrice && partNoSpace.contains("계약금") -> {
                            deductions += amount
                        }
                        // 대출/세입자/근저당/중도금 → 차감
                        partNoSpace.contains("대출") || partNoSpace.contains("세입자") ||
                        partNoSpace.contains("근저당") || partNoSpace.contains("중도금") ||
                        partNoSpace.contains("융자") -> {
                            // 질권설정x인 전세대출은 차감하지 않음
                            if (isJeonseResidence && jeonseNoJilgwon && partNoSpace.contains("대출")) {
                                Log.d("HWP_PARSE", "전세대출 질권설정x → 대출 차감 안함: $part")
                            } else {
                                deductions += amount
                            }
                        }
                    }
                }

                if (grossValue > 0) {
                    var netValue = grossValue - deductions
                    if (netValue < 0) netValue = 0
                    if (isBaeuja) netValue /= 2  // 배우자명의 ÷2
                    // 지역 라인의 배우자/타인명의 재산은 거주지 정보이므로 재산에서 제외
                    val isRegionOthers = lineNoSpace.startsWith("지역") && (isBaeuja || isOthersName)
                    if (!isRegionOthers) {
                        parsedProperty += netValue
                        if (isOthersName || isBaeuja) parsedOthersProperty += netValue
                    }
                    Log.d("HWP_PARSE", "재산 파싱: 총${grossValue}만 - 차감${deductions}만 = ${netValue}만${if (isBaeuja) " (배우자÷2)" else ""}${if (isOthersName) " (타인명의)" else ""}${if (isRegionOthers) " (지역라인 배우자→제외)" else ""} ($line)")
                }
            }

            // "재산 : x" or "재산 x" 감지
            if (lineNoSpace.contains("재산") && line.contains(":")) {
                val afterColon = line.substringAfter(":").trim().replace(" ", "")
                if (afterColon == "x" || afterColon == "X" || afterColon == "없음" || afterColon == "0") {
                    isPropertyX = true
                    Log.d("HWP_PARSE", "재산 없음(x) 감지: $line")
                }
            }

            // 재직/직업
            if (lineNoSpace.contains("재직") && line.contains(":")) {
                val job = line.substringAfter(":").trim()
                if (job.contains("사업자") || job.contains("개인사업") || job.contains("자영업") || job.contains("음식점")) {
                    isBusinessOwner = true
                    if (!job.contains("폐업")) {
                        businessEndYear = 0
                    }
                    // 재직줄에서 개업/폐업 년도 파싱: "개인사업자 - 18년 개업 / 21년 폐업"
                    if (businessStartYear == 0) {
                        val bizM = Pattern.compile("(\\d{2,4})년\\s*(?:(\\d{1,2})월\\s*)?개업").matcher(job)
                        if (bizM.find()) {
                            val y = bizM.group(1)!!.toInt()
                            businessStartYear = if (y < 30) 2000 + y else if (y < 100) 1900 + y else y
                            val m = bizM.group(2)
                            if (m != null) businessStartMonth = m.toInt()
                        }
                    }
                    if (businessEndYear == 0 && job.contains("폐업")) {
                        val bizE = Pattern.compile("(\\d{2,4})년\\s*(?:\\d{1,2}월\\s*)?폐업").matcher(job)
                        if (bizE.find()) {
                            val y = bizE.group(1)!!.toInt()
                            businessEndYear = if (y < 30) 2000 + y else if (y < 100) 1900 + y else y
                        }
                    }
                    Log.d("HWP_PARSE", "재직줄 사업자 감지: $job (개업=$businessStartYear, 폐업=$businessEndYear)")
                }
                if (job.contains("프리랜서") || job.contains("3.3") || job.contains("보험설계사")) {
                    isFreelancer = true
                }
                if (job.contains("비영리") || job.contains("노동조합") || job.contains("종교") || job.contains("교회") || job.contains("사찰") || job.contains("재단법인") || job.contains("사단법인") || job.contains("협회") || job.contains("복지")) {
                    isNonProfit = true
                }
                if (job.contains("법인사업") || job.contains("법인대표") || job.contains("법인운영")) {
                    isCorporateBusiness = true
                }
            }

            // ============= 전역 감지 (줄 위치 무관, 2줄 이어쓰기 대응) =============

            // ★ 사업자 이력: 코드에서 직접 파싱
            if (!hasBusinessHistory) {
                if ((lineNoSpace.contains("자영업") || (lineNoSpace.contains("개인사업") && !lineNoSpace.contains("사업자대출")) ||
                    lineNoSpace.contains("폐업") || lineNoSpace.contains("사업자") && lineNoSpace.contains("개시") ||
                    lineNoSpace.contains("사업자") && (lineNoSpace.contains("년") || lineNoSpace.contains("매출"))) && !lineNoSpace.contains("사업자대출")) {

                    if (!specialNotesList.any { it.contains("사업자") || it.contains("자영업") || it.contains("폐업") }) {
                        // 년도 정보는 아래에서 최종 취합 시 추가
                        specialNotesList.add("사업자이력")
                    }
                    // 개업/폐업 년도/월 파싱 (AI 값이 없을 때만)
                    if (businessStartYear == 0) {
                        val bizYearPattern = Pattern.compile("(\\d{2,4})년\\s*(?:(\\d{1,2})월\\s*)?개업")
                        val bizYearMatcher = bizYearPattern.matcher(line)
                        if (bizYearMatcher.find()) {
                            val y = bizYearMatcher.group(1)!!.toInt()
                            businessStartYear = if (y < 30) 2000 + y else if (y < 100) 1900 + y else y
                            val monthStr = bizYearMatcher.group(2)
                            if (monthStr != null && businessStartMonth == 0) {
                                businessStartMonth = monthStr.toInt()
                            }
                        }
                    }
                    if (businessEndYear == 0) {
                        val bizEndPattern = Pattern.compile("(\\d{2,4})년\\s*(?:\\d{1,2}월\\s*)?폐업")
                        val bizEndMatcher = bizEndPattern.matcher(line)
                        if (bizEndMatcher.find()) {
                            val y = bizEndMatcher.group(1)!!.toInt()
                            businessEndYear = if (y < 30) 2000 + y else if (y < 100) 1900 + y else y
                        }
                        // 폐업 년도가 없으면 0 유지 (현재 운영 중으로 간주)
                    }

                    // 사업 기간이 2020.04 ~ 2025.06과 겹치면 사업자이력 인정
                    if (businessStartYear > 0 && businessStartYear <= 2025 && (businessEndYear == 0 || businessEndYear >= 2020)) {
                        hasBusinessHistory = true
                    }
                    // 사업자/자영업/개인사업 키워드 감지 시 isBusinessOwner도 설정 (재직: 줄과 분리된 경우 대응)
                    if (lineNoSpace.contains("사업자") || lineNoSpace.contains("개인사업") || lineNoSpace.contains("자영업")) {
                        isBusinessOwner = true
                    }
                    Log.d("HWP_PARSE", "사업자 이력 보조 감지: $line (개업=$businessStartYear, 폐업=$businessEndYear)")
                }
            }

            // ★ 비영리 단체 감지 (전역 - hasBusinessHistory와 무관하게 항상 감지)
            if (!isNonProfit && (lineNoSpace.contains("비영리") || lineNoSpace.contains("노동조합") || lineNoSpace.contains("종교") || lineNoSpace.contains("교회") || lineNoSpace.contains("사찰") || lineNoSpace.contains("재단법인") || lineNoSpace.contains("사단법인") || lineNoSpace.contains("협회") || lineNoSpace.contains("복지"))) {
                isNonProfit = true
                Log.d("HWP_PARSE", "비영리 단체 감지: $line")
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

            // 경매 감지 (장기 불가 조건) - 상대방/타인 재산 경매는 제외
            val isOthersContext = lineNoSpace.contains("상대방") || lineNoSpace.contains("타인") || lineNoSpace.contains("상대측")
            if (lineNoSpace.contains("경매") && !isOthersContext) {
                hasAuction = true
                Log.d("HWP_PARSE", "경매 감지: $line")
            } else if (lineNoSpace.contains("경매") && isOthersContext) {
                Log.d("HWP_PARSE", "경매 감지 제외 (상대방/타인 재산): $line")
            }
            // 압류/강제집행 감지 (회워, 회생 불가시 방생) - 상대방/타인 재산 가압류는 제외
            if ((lineNoSpace.contains("압류") || lineNoSpace.contains("강제집행")) && !isOthersContext) {
                hasSeizure = true
                Log.d("HWP_PARSE", "압류 감지: $line")
            } else if ((lineNoSpace.contains("압류") || lineNoSpace.contains("강제집행")) && isOthersContext) {
                Log.d("HWP_PARSE", "압류 감지 제외 (상대방/타인 재산): $line")
            }

            // "연봉" / "월소득" 줄에서 소득 파싱
            if ((lineNoSpace.contains("연봉") || lineNoSpace.contains("월소득")) && line.contains(":")) {
                val afterColon = line.substringAfter(":").trim()
                val afterColonNoSpace = afterColon.replace(" ", "")
                if (afterColon == "x" || afterColonNoSpace.endsWith("x") || afterColonNoSpace.contains("소득x") || afterColonNoSpace.contains("월소득x") || afterColonNoSpace == "없음" || afterColonNoSpace == "0") {
                    isIncomeX = true
                    Log.d("HWP_PARSE", "소득 없음(x) 감지: $line")
                    // "소득없음"이지만 "생활비 200만" 등 실질 수입이 있으면 그 금액 사용
                    val livingMatch = Regex("(?:생활비|용돈)\\s*(\\d+)만").find(afterColon)
                    if (livingMatch != null) {
                        parsedIncome = livingMatch.groupValues[1].toInt()
                        isIncomeX = false  // 실질 수입 있으므로 취소
                        Log.d("HWP_PARSE", "소득없음이지만 실질수입 감지: ${parsedIncome}만 ($line)")
                    }
                } else if (parsedIncome == 0) {
                    // "+" 로 연결된 복수 소득 합산 (예: "월 30만 + 국민연금 29만 + 기초연금 34만")
                    if (afterColon.contains("+") || afterColon.contains("＋")) {
                        val plusAmounts = Regex("(\\d+)\\s*만").findAll(afterColon).toList()
                        if (plusAmounts.size >= 2) {
                            parsedIncome = plusAmounts.sumOf { it.groupValues[1].toInt() }
                            Log.d("HWP_PARSE", "소득 파싱(합산): ${parsedIncome}만 후보=${plusAmounts.map { it.groupValues[1] }} ($line)")
                        }
                    }

                    if (parsedIncome == 0) {
                    // 순수익/소득 비교 추출 (예: "전년도매출 8346만 / 소득 : 1518만 > 월 순수익 : 200만")
                    val netProfitMatch = Regex("순수익\\s*:?\\s*(\\d+)\\s*만").find(afterColon)
                    val netProfit = netProfitMatch?.groupValues?.get(1)?.toInt() ?: 0
                    // "소득 : NNN만" 또는 "소득NNN만" (값 내부의 연간소득 → /12)
                    val sodukInValueMatch = Regex("소득\\s*:?\\s*(\\d+)\\s*만").find(afterColon)
                    val sodukMonthly = if (sodukInValueMatch != null) sodukInValueMatch.groupValues[1].toInt() / 12 else 0
                    if (netProfit > 0 || sodukMonthly > 0) {
                        parsedIncome = maxOf(netProfit, sodukMonthly)
                        Log.d("HWP_PARSE", "소득 파싱(순수익/소득비교): 순수익=${netProfit}만 소득월=${sodukMonthly}만 → ${parsedIncome}만 ($line)")
                    }
                    }

                    if (parsedIncome == 0) {
                    // 모든 "NNN만" 숫자 추출 → 월/괄호/연/범위 등에서 최적값 결정
                    val allAmounts = mutableListOf<Int>()

                    // 괄호 안 실수령액: "(470만)" → 470
                    Regex("\\((\\d+)만\\)").findAll(afterColon).forEach { allAmounts.add(it.groupValues[1].toInt()) }

                    // "월 229만" or "월229만"
                    Regex("월\\s*(\\d+)만").findAll(afterColon).forEach { allAmounts.add(it.groupValues[1].toInt()) }

                    // 범위: "800~900만" → 최대값 900
                    val rangeMatch = Regex("(\\d+)\\s*[~\\-](\\d+)만").find(afterColon)
                    if (rangeMatch != null) {
                        allAmounts.add(maxOf(rangeMatch.groupValues[1].toInt(), rangeMatch.groupValues[2].toInt()))
                    }

                    if (allAmounts.isNotEmpty()) {
                        parsedIncome = allAmounts.max()
                        Log.d("HWP_PARSE", "소득 파싱(최대값): ${parsedIncome}만 후보=${allAmounts} ($line)")
                    } else {
                        // "연 3000만" (월 없을 때 ÷12)
                        val yearlyMatch = Regex("연\\s*(\\d+)만").find(afterColon)
                        if (yearlyMatch != null) {
                            val yearly = yearlyMatch.groupValues[1].toInt()
                            parsedIncome = yearly / 12
                            Log.d("HWP_PARSE", "소득 파싱(연÷12): 연${yearly}만 → 월${parsedIncome}만 ($line)")
                        } else {
                            // 단순 "260만" or 소득금액증명원 → 연÷12
                            val simpleMatch = Regex("(\\d+)만").find(afterColon)
                            if (simpleMatch != null) {
                                val amount = simpleMatch.groupValues[1].toInt()
                                if (afterColonNoSpace.contains("소득금액증명원") || afterColonNoSpace.contains("소금원")) {
                                    parsedIncome = amount / 12
                                    Log.d("HWP_PARSE", "소득 파싱(소금원÷12): 연${amount}만 → 월${parsedIncome}만 ($line)")
                                } else {
                                    parsedIncome = amount
                                    Log.d("HWP_PARSE", "소득 파싱(단순): ${parsedIncome}만 ($line)")
                                }
                            }
                        }
                    }
                }
                // 연봉 추출 (실수령액 계산용)
                if (afterColonNoSpace.contains("연봉")) {
                    // 값에 "연봉 NNN만" 패턴 (예: "평균소득 310만원 연봉 4800만원")
                    val yeonbongPart = afterColon.substringAfter("연봉").trim()
                    val yb = extractAmount(yeonbongPart)
                    if (yb > 0) {
                        parsedAnnualSalary = yb
                        Log.d("HWP_PARSE", "연봉 파싱(값): ${parsedAnnualSalary}만 ($line)")
                    }
                } else if (lineNoSpace.startsWith("연봉") &&
                    !afterColonNoSpace.contains("소득금액증명원") && !afterColonNoSpace.contains("소금원") &&
                    !afterColonNoSpace.contains("매출")) {
                    // 필드명이 "연봉"이고 소득금액증명원이 아닌 경우 (예: "연봉 : 4800만")
                    val yb = extractAmount(afterColon)
                    if (yb >= 1200) {  // 연봉 1200만 이상만 (월 100만 미만은 연봉이 아님)
                        parsedAnnualSalary = yb
                        Log.d("HWP_PARSE", "연봉 파싱(필드): ${parsedAnnualSalary}만 ($line)")
                    }
                }
                }
            }

            // 소득 예상/예정 감지 + 금액 추출
            if (lineNoSpace.contains("예상") || lineNoSpace.contains("예정")) {
                if (lineNoSpace.contains("소득") || lineNoSpace.contains("연봉") || lineNoSpace.contains("월급") || lineNoSpace.contains("월")) {
                    isIncomeEstimated = true
                    // "월 350만 예정", "월 350만 예상" 등에서 금액 추출
                    val incomeMatch = Regex("월\\s*(\\d+)만\\s*(?:예정|예상)").find(line)
                        ?: Regex("(\\d+)만\\s*(?:예정|예상)").find(line)
                    if (incomeMatch != null) {
                        val parsed = incomeMatch.groupValues[1].toInt()
                        if (parsed > 0) {
                            estimatedIncomeParsed = parsed
                            Log.d("HWP_PARSE", "소득 예정/예상 금액 감지: ${parsed}만 ($line)")
                        }
                    } else {
                        Log.d("HWP_PARSE", "소득 예정/예상 감지 (금액 없음): $line")
                    }
                }
            }

            // 한국주택금융공사 집담보 감지
            if (lineNoSpace.contains("한국주택금융공사")) {
                hasHfcMortgage = true
                Log.d("HWP_PARSE", "한국주택금융공사 집담보 감지: $line")
            }

            // 예적금 감지
            if (savingsDeposit == 0 && (lineNoSpace.contains("예적금") || lineNoSpace.contains("예금") || lineNoSpace.contains("적금"))) {
                val savingsAmount = when {
                    lineNoSpace.contains("예적금") -> extractAmountAfterKeyword(lineNoSpace, "예적금")
                    lineNoSpace.contains("예금") -> extractAmountAfterKeyword(lineNoSpace, "예금")
                    else -> extractAmountAfterKeyword(lineNoSpace, "적금")
                }
                if (savingsAmount > 0) {
                    savingsDeposit = savingsAmount
                    Log.d("HWP_PARSE", "예적금 감지: ${savingsDeposit}만 ($line)")
                }
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

            // 보험약관대출 감지 (대상채무 제외)
            if (lineNoSpace.contains("보험약관대출") || lineNoSpace.contains("약관대출")) {
                hasInsurancePolicyLoan = true
                Log.d("HWP_PARSE", "보험약관대출 감지: $line")
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
                var m = Pattern.compile("(\\d+)\\s*(?:개월|달)").matcher(line)
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
                // "N년 N월 N일부터 연체" 또는 "N년 N월부터 연체" 패턴 → 날짜 기반 계산 (일 없으면 1일)
                val delinqDateM = Pattern.compile("(\\d{2,4})년\\s*(\\d{1,2})월(?:\\s*(\\d{1,2})일)?").matcher(line)
                if (delinqDateM.find()) {
                    var dYear = delinqDateM.group(1)!!.toInt()
                    if (dYear < 100) dYear += 2000
                    val dMonth = delinqDateM.group(2)!!.toInt()
                    val dDay = delinqDateM.group(3)?.toIntOrNull() ?: 1
                    val delinqStart = Calendar.getInstance().apply { set(dYear, dMonth - 1, dDay) }
                    val now = Calendar.getInstance()
                    val diffDays = ((now.timeInMillis - delinqStart.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
                    if (diffDays > 0) {
                        delinquentDays = maxOf(delinquentDays, diffDays)
                        Log.d("HWP_PARSE", "연체 날짜 기반: ${dYear}년 ${dMonth}월 ${dDay}일부터 → ${diffDays}일")
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
                // 실제 연체일수 동기화 (다른 단계 진행으로 인한 1095일과 구분)
                actualDelinquentDays = maxOf(actualDelinquentDays, delinquentDays)
            }

            // 신복위 이력 감지 (줄 위치 무관)
            if ((lineNoSpace.contains("신복위") || lineNoSpace.contains("신용회복") || lineNoSpace.contains("신속채무조정")) &&
                !lineNoSpace.contains("상담") && !lineNoSpace.contains("문의") && !lineNoSpace.contains("알아보")) hasShinbokwiHistory = true

            // 다른 채무조정 진행 중 감지 (PDF 없으면 텍스트에서 제도명도 판단)
            if (lineNoSpace.contains("진행중") || lineNoSpace.contains("진행중")) {
                if (lineNoSpace.contains("신속") || lineNoSpace.contains("회생") || lineNoSpace.contains("워크아웃") ||
                    lineNoSpace.contains("신복위") || lineNoSpace.contains("채무조정")) {
                    hasOngoingProcess = true
                    delinquentDays = maxOf(delinquentDays, 1095)
                    // PDF 없을 때 텍스트에서 제도명 판단
                    if (ongoingProcessName.isEmpty() && pdfAgreementProcess.isEmpty()) {
                        ongoingProcessName = when {
                            lineNoSpace.contains("신속") -> "신"
                            lineNoSpace.contains("프리") || lineNoSpace.contains("사전") -> "프"
                            lineNoSpace.contains("워크아웃") || lineNoSpace.contains("개인워크") -> "워"
                            lineNoSpace.contains("회생") -> "회"
                            else -> ""
                        }
                    }
                    Log.d("HWP_PARSE", "다른 단계 진행 중 감지 → 장기연체자: $line (제도=$ongoingProcessName)")
                }
            }

            // 지급명령 받음 감지 ("안받음"은 제외)
            if (lineNoSpace.contains("지급명령") && !lineNoSpace.contains("안받음") && !lineNoSpace.contains("안받")) {
                hasPaymentOrder = true
                Log.d("HWP_PARSE", "지급명령 받음 감지: $line")
            }

            // 유예기간 감지 (상환내역서 텍스트에서 직접 파싱)
            if (lineNoSpace.contains("유예기간") || lineNoSpace.contains("거치기간")) {
                if (lineNoSpace.contains("별도")) {
                    // "유예기간 별도" → 유예기간 0개월
                    aiDefermentMonths = 0
                    Log.d("HWP_PARSE", "유예기간 별도 → 0개월")
                } else {
                    val deferM = Pattern.compile("(\\d+)\\s*개?월").matcher(line)
                    if (deferM.find()) {
                        val months = deferM.group(1)!!.toInt()
                        if (months > aiDefermentMonths) {
                            aiDefermentMonths = months
                            Log.d("HWP_PARSE", "유예기간 감지: ${months}개월")
                        }
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

            // 폐지/기각/취하 감지 → 장기연체자 (줄 위치 무관)
            if (lineNoSpace.contains("폐지") || lineNoSpace.contains("기각") || lineNoSpace.contains("취하")) {
                delinquentDays = maxOf(delinquentDays, 1095)
                isDismissed = true
                Log.d("HWP_PARSE", "폐지/기각/취하 감지 → 장기연체자: $line")
            }

            // 면책 감지 (줄 위치 무관, "면채" 오타도 포함)
            if (lineNoSpace.contains("면책") || lineNoSpace.contains("면채")) {
                hasDischarge = true
                if (lineNoSpace.contains("파산")) isBankruptcyDischarge = true
                // 면책이 포함된 구간에서만 년도 추출 (폐지 년도와 혼동 방지)
                val dischargeSegment = line.split(",", "，", "/", "／").find { it.contains("면책") || it.contains("면채") } ?: line
                var m = Pattern.compile("(\\d{2})년도?\\s*(\\d{1,2})월?").matcher(dischargeSegment)
                if (m.find()) {
                    dischargeYear = 2000 + m.group(1)!!.toInt()
                    if (m.group(2) != null) dischargeMonth = m.group(2)!!.toInt()
                } else {
                    m = Pattern.compile("(20\\d{2})\\.?(\\d{1,2})?").matcher(dischargeSegment)
                    if (m.find()) {
                        dischargeYear = m.group(1)!!.toInt()
                        if (m.group(2) != null) dischargeMonth = m.group(2)!!.toInt()
                    }
                }
                // "21. 6월" 또는 "21.6월" 형식 대응
                if (dischargeYear == 0) {
                    m = Pattern.compile("(\\d{2})[.．]\\s*(\\d{1,2})월?").matcher(dischargeSegment)
                    if (m.find()) {
                        dischargeYear = 2000 + m.group(1)!!.toInt()
                        if (m.group(2) != null) dischargeMonth = m.group(2)!!.toInt()
                    }
                }
            }

            // 개인회생 감지 (면책이 안 적혀있어도 개인회생이 있고 진행중이 아니면 면책)
            if (lineNoSpace.contains("개인회생")) {
                hasPersonalRecovery = true
                // 폐지/기각/취하가 같은 줄에 있으면 면책 아님
                if (lineNoSpace.contains("폐지") || lineNoSpace.contains("기각") || lineNoSpace.contains("취하")) {
                    isDismissed = true
                    Log.d("HWP_PARSE", "개인회생 폐지/기각/취하 감지: $line")
                }
                var m = Pattern.compile("(\\d{2})년도?\\s*(\\d{1,2})월?").matcher(line)
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
                Log.d("HWP_PARSE", "개인회생 감지: $line (${personalRecoveryYear}년 ${personalRecoveryMonth}월, 폐지/기각/취하=$isDismissed)")
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
                // 미성년 자녀 수 파싱
                if (lineNoSpace.contains("미성년")) {
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
                // 비양육/전배우자/배우자가 양육중이면 미성년 제외 (1인가구), 본인 양육중이면 포함
                // 단, 양육비를 주고 있으면 미성년 가구원에 포함 (본인 부담이므로)
                val savedMinorChildren = minorChildren
                if (lineNoSpace.contains("비양육") || lineNoSpace.contains("전처") || lineNoSpace.contains("전남편") ||
                    (lineNoSpace.contains("배우자") && lineNoSpace.contains("양육"))) {
                    minorChildren = 0
                    Log.d("HWP_PARSE", "비양육/전배우자/배우자 양육 감지: 미성년 0으로 설정")
                }
                // 양육비 파싱
                if (lineNoSpace.contains("양육비")) {
                    val m = Pattern.compile("양육비\\s*(\\d+)").matcher(lineNoSpace)
                    if (m.find()) {
                        childSupportAmount = m.group(1)!!.toInt()
                    } else {
                        val m2 = Pattern.compile("(\\d+)만").matcher(lineNoSpace.substringAfter("양육비"))
                        if (m2.find()) childSupportAmount = m2.group(1)!!.toInt()
                    }
                    // 양육비를 주고 있으면 미성년 가구원 복원 (비용 부담 = 가구원 포함)
                    if (childSupportAmount > 0 && savedMinorChildren > 0) {
                        minorChildren = savedMinorChildren
                        Log.d("HWP_PARSE", "양육비 ${childSupportAmount}만 지급 중 → 미성년 ${minorChildren}명 복원")
                    }
                    Log.d("HWP_PARSE", "양육비 감지: ${childSupportAmount}만")
                }
                if (line.contains("대학생")) {
                    val m = Pattern.compile("대학생\\s*(\\d+)").matcher(line)
                    if (m.find()) collegeChildren = m.group(1)!!.toInt()
                }
            }

            // 60세 이상 부모
            if (lineNoSpace.contains("60세") || lineNoSpace.contains("만60세")) {
                var count = 0
                // "60세여부:1명" or "60세:2명" → 숫자 직접 추출
                val numMatch60 = Pattern.compile("60세[^:]*:(\\d+)").matcher(lineNoSpace)
                if (numMatch60.find()) {
                    count = numMatch60.group(1)!!.toInt()
                } else {
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
                } // else (숫자 직접 추출 아닌 경우)
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
                    if (content.contains("면책") || content.contains("면채")) {
                        hasDischarge = true
                        if (content.contains("파산")) isBankruptcyDischarge = true
                        // 면책이 포함된 구간에서만 년도 추출 (폐지 년도와 혼동 방지)
                        val dischargeSegment = content.split(",", "，", "/", "／").find { it.contains("면책") || it.contains("면채") } ?: content
                        var m = Pattern.compile("(\\d{2})년").matcher(dischargeSegment)
                        if (m.find()) dischargeYear = 2000 + m.group(1)!!.toInt()
                        else { m = Pattern.compile("(20\\d{2})").matcher(dischargeSegment); if (m.find()) dischargeYear = m.group(1)!!.toInt() }
                    }
                    // 실효 감지 → 장기연체자 (1095일)
                    if (content.contains("실효")) {
                        delinquentDays = maxOf(delinquentDays, 1095)
                        if (content.contains("워크아웃") || content.contains("워크") || content.contains("신복위")) {
                            hasWorkoutExpired = true
                        }
                        Log.d("HWP_PARSE", "실효 감지 → 장기연체자: $content, 워크아웃실효=$hasWorkoutExpired")
                    }
                    // 폐지/기각/취하 감지 → 장기연체자 (1095일)
                    if (content.contains("폐지") || content.contains("기각") || content.contains("취하")) {
                        delinquentDays = maxOf(delinquentDays, 1095)
                        if (content.contains("회생") || hasPersonalRecovery) {
                            isDismissed = true
                        }
                        Log.d("HWP_PARSE", "폐지/기각/취하 감지 → 장기연체자: $content, 회생폐지=$isDismissed")
                    }
                    // 일수 기입 → 연체기간 설정
                    val daysMatcher = Pattern.compile("(\\d+)일").matcher(content)
                    if (daysMatcher.find()) {
                        val days = daysMatcher.group(1)!!.toInt()
                        delinquentDays = maxOf(delinquentDays, days)
                        actualDelinquentDays = maxOf(actualDelinquentDays, days)
                        Log.d("HWP_PARSE", "채무조정 연체일수 감지: ${days}일")
                    }
                    // 제도 진행중이고 결과 없으면 장기연체자 (구간별로 분리 판단)
                    val segments = content.split("/", "／", ",", "，").map { it.trim() }
                    for (seg in segments) {
                        val segProcess = seg.contains("회생") || seg.contains("워크") ||
                            seg.contains("신복") || seg.contains("신속") || seg.contains("진행") ||
                            seg.contains("접수")
                        val segResult = seg.contains("면책") || seg.contains("면채") || seg.contains("실효") ||
                            seg.contains("폐지") || seg.contains("기각") || seg.contains("취하")
                        if (segProcess && !segResult) {
                            delinquentDays = maxOf(delinquentDays, 1095)
                            hasOngoingProcess = true
                            Log.d("HWP_PARSE", "제도 진행중 결과 없음 → 장기연체자: $seg")
                            break
                        }
                    }
                }
            }

            // 차량 파싱 (재산은 AI가 처리하지만, 차량 대수/처분 판단용)
            val wasCarLine: Boolean
            val isDebtEntryLine = Pattern.compile("\\d{2}년\\s*\\d{1,2}월\\s*\\d{1,2}일").matcher(lineNoSpace).find()
            var isCarLine = !isDebtEntryLine && (lineNoSpace.contains("차량") || (line.contains("자동차") && !lineNoSpace.contains("자동차금융") && !lineNoSpace.contains("자동차담보")) ||
                    (Pattern.compile("\\d{2}년").matcher(lineNoSpace).find() &&
                            (lineNoSpace.contains("시세") || lineNoSpace.contains("본인명의") || lineNoSpace.contains("배우자명의"))))

            wasCarLine = isCarLine
            val isMainCarLine = lineNoSpace.contains("시세") || Pattern.compile("\\d{2}년식").matcher(lineNoSpace).find()
            if (isCarLine && (lineNoSpace.contains("차량담보") || lineNoSpace.contains("차량할부")) && !isMainCarLine) {
                val additionalLoan = extractAmount(line)
                if (additionalLoan > 0) {
                    carTotalLoan += additionalLoan
                    carDamboAmountsChun.add(additionalLoan * 10) // 추가 차량담보 금액 수집
                    // 추가 대출은 마지막 차량에 반영
                    if (carInfoList.isNotEmpty()) {
                        carInfoList.last()[1] += additionalLoan
                    }
                }
                // 월납부도 마지막 차량에 반영
                val addMonthlyM = Pattern.compile("월\\s*(\\d+)만").matcher(line)
                if (addMonthlyM.find()) {
                    val addMonthly = addMonthlyM.group(1)!!.toInt()
                    carMonthlyPayment += addMonthly
                    if (carInfoList.isNotEmpty()) {
                        carInfoList.last()[2] += addMonthly
                    }
                }
                isCarLine = false
            }

            if (isCarLine) {
                if (lineNoSpace.contains("장기렌트") || lineNoSpace.contains("렌트")) {
                    Log.d("HWP_PARSE", "장기렌트 감지 (차량 개수/처분 제외): $line")
                    continue
                }
                if (line.contains(": x") || line.contains(":x") || line.endsWith(": x")) continue
                // 차량 이미 처분/없음 관련 텍스트는 차량으로 카운트하지 않음 (진행중은 제외 - 아직 보유)
                if ((lineNoSpace.contains("공매") && !lineNoSpace.contains("진행")) || lineNoSpace.contains("처분후") || lineNoSpace.contains("잔존") ||
                    lineNoSpace.contains("잔채무") || lineNoSpace.contains("없음") || lineNoSpace.contains("폐차")) continue

                // 시세 추출: 구분자(–, -, /, 담보, 할부, 월) 이전까지만 파싱
                var carSise = 0
                val siseIdx = lineNoSpace.indexOf("시세")
                if (siseIdx >= 0) {
                    val afterSise = lineNoSpace.substring(siseIdx + 2)
                    val separators = listOf("–", "—", "-", "/", "담보", "할부", "월")
                    var endPos = afterSise.length
                    for (sep in separators) {
                        val idx = afterSise.indexOf(sep)
                        if (idx >= 0 && idx < endPos) endPos = idx
                    }
                    val sisePart = afterSise.substring(0, endPos)
                    carSise = extractAmount(sisePart)
                    if (carSise == 0) {
                        // "만" 없이 숫자만 있는 경우 (예: "시세:150")
                        val numM = Pattern.compile("(\\d+)").matcher(sisePart)
                        if (numM.find()) carSise = numM.group(1)!!.toInt()
                    }
                }
                if (carSise == 0) carSise = extractAmount(line)
                var carLoan = 0
                if (lineNoSpace.contains("담보") || lineNoSpace.contains("할부") || lineNoSpace.contains("대출")) {
                    carLoan = maxOf(extractAmountAfterKeyword(lineNoSpace, "담보"), extractAmountAfterKeyword(lineNoSpace, "할부"))
                    carLoan = maxOf(carLoan, extractAmountAfterKeyword(lineNoSpace, "대출"))
                }
                // 차량담보 금액 수집 (배우자 나누기 전 원본, 천원 단위로 저장)
                if (carLoan > 0) {
                    carDamboAmountsChun.add(carLoan * 10)
                    Log.d("HWP_PARSE", "차량담보 금액 수집: ${carLoan}만 = ${carLoan * 10}천원")
                }
                // 시세/대출/월납 모두 없으면 실제 차량 아님 (라벨만 있는 경우 등)
                val monthlyM = Pattern.compile("월\\s*(\\d+)만").matcher(lineNoSpace)
                var carMonthly = 0
                if (monthlyM.find()) carMonthly = monthlyM.group(1)!!.toInt()
                if (carSise == 0 && carLoan == 0 && carMonthly == 0) continue

                val isSpouseCar = lineNoSpace.contains("배우자명의") || lineNoSpace.contains("배우자")
                if (isSpouseCar) {
                    // 배우자 지분율 파싱 (예: "배우자99", "배우자명의99%")
                    var spousePercent = 50 // 기본 50/50
                    val afterBaeuja = lineNoSpace.substringAfter("배우자")
                    val pctM = Pattern.compile("^[명의지분]*(\\d{1,3})").matcher(afterBaeuja)
                    if (pctM.find()) {
                        val numEnd = pctM.end()
                        val isYear = numEnd < afterBaeuja.length && afterBaeuja[numEnd] == '년'
                        if (!isYear) {
                            val pct = pctM.group(1)!!.toInt()
                            if (pct in 1..100) spousePercent = pct
                        }
                    }
                    if (spousePercent > 50) {
                        // 배우자 지분 과반 → 본인 지분 미미하므로 차량 계산 제외
                        Log.d("HWP_PARSE", "배우자 지분 ${spousePercent}% → 차량 계산 제외: $line")
                        continue
                    }
                    carSise /= 2; carLoan /= 2
                }
                // 외제차 여부 판별
                val isForeignCar = foreignCarBrands.any { brand -> lineNoSpace.contains(brand, ignoreCase = true) }

                carTotalSise += carSise; carTotalLoan += carLoan
                carMonthlyPayment += carMonthly
                // 개별 차량 정보 저장: [시세, 대출, 월납부, 배우자(1/0), 외제(1/0)]
                carInfoList.add(intArrayOf(carSise, carLoan, carMonthly, if (isSpouseCar) 1 else 0, if (isForeignCar) 1 else 0))
                carCount++
                Log.d("HWP_PARSE", "차량 파싱: 시세=$carSise, 담보=$carLoan, 월납=$carMonthly, 배우자=$isSpouseCar, 외제=$isForeignCar, 누적시세=$carTotalSise, 누적담보=$carTotalLoan")
            }

            // 차량 라인은 채무 파싱에서 제외 (중복 카운트 방지)
            if (wasCarLine) continue

            // 6개월 이내 채무 파싱
            var loanYear = 0; var loanMonth = 0; var loanDay = 0
            var yearOnlyDate = false
            var isSimplifiedFormat = false
            var dateEndPos = -1
            val dateMatcher = Pattern.compile("(\\d{4})[.\\-](\\d{1,2})[.\\-](\\d{1,2})").matcher(line)
            val dateMatcher2 = Pattern.compile("(\\d{2})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일?").matcher(line)
            val dateMatcher3 = Pattern.compile("(\\d{2})년\\s*(\\d{1,2})월").matcher(line)
            val dateMatcher5 = Pattern.compile("(?<!\\d)(\\d{2})\\.(\\d{1,2})\\.(\\d{1,2})(?!\\d)").matcher(line)
            val dateMatcher6 = Pattern.compile("(?<!\\d)(\\d{2})\\.(\\d{1,2})(?![.\\d])").matcher(line)
            val dateMatcher4 = Pattern.compile("^(\\d{2})년\\s+").matcher(line)

            if (dateMatcher.find()) {
                loanYear = dateMatcher.group(1)!!.toInt(); loanMonth = dateMatcher.group(2)!!.toInt(); loanDay = dateMatcher.group(3)!!.toInt()
            } else if (dateMatcher2.find()) {
                loanYear = 2000 + dateMatcher2.group(1)!!.toInt(); loanMonth = dateMatcher2.group(2)!!.toInt(); loanDay = dateMatcher2.group(3)!!.toInt()
            } else if (dateMatcher3.find()) {
                loanYear = 2000 + dateMatcher3.group(1)!!.toInt(); loanMonth = dateMatcher3.group(2)!!.toInt(); loanDay = 15
            } else if (dateMatcher5.find()) {
                val yr = dateMatcher5.group(1)!!.toInt(); val mo = dateMatcher5.group(2)!!.toInt(); val dy = dateMatcher5.group(3)!!.toInt()
                if (yr in 10..30 && mo in 1..12 && dy in 1..31) {
                    loanYear = 2000 + yr; loanMonth = mo; loanDay = dy
                    isSimplifiedFormat = true; dateEndPos = dateMatcher5.end()
                }
            } else if (dateMatcher6.find()) {
                val yr = dateMatcher6.group(1)!!.toInt(); val mo = dateMatcher6.group(2)!!.toInt()
                if (yr in 10..30 && mo in 1..12) {
                    loanYear = 2000 + yr; loanMonth = mo; loanDay = 15
                    isSimplifiedFormat = true; dateEndPos = dateMatcher6.end()
                }
            } else if (dateMatcher4.find()) {
                loanYear = 2000 + dateMatcher4.group(1)!!.toInt(); loanMonth = 1; loanDay = 1
                yearOnlyDate = true // 연도만 매칭 → 금융기관 키워드 필수
            }

            val hasFinancialKeyword = line.contains("은행") || line.contains("캐피탈") || line.contains("카드") ||
                    line.contains("금융") || line.contains("저축") || line.contains("보증") ||
                    line.contains("공사") || line.contains("재단") || line.contains("농협") ||
                    line.contains("신협") || line.contains("새마을") || line.contains("생명") ||
                    line.contains("화재") || line.contains("공단") || line.contains("대부") ||
                    line.contains("피에프씨") || line.contains("PFC") || line.contains("테크놀로지") ||
                    line.contains("머니무브") || line.contains("사금융") || line.contains("일수") ||
                    line.contains("코프") || isSimplifiedFormat

            // 연도만 매칭("24년 ...") + 금융기관 키워드 없으면 소득/재산 라인이므로 채무 파싱 제외
            if (yearOnlyDate && !hasFinancialKeyword) loanYear = 0

            // 간이형식 감지 시 플래그 설정
            if (isSimplifiedFormat) hasSeenSimplifiedFormat = true

            // 간이형식 fallback: 날짜 없지만 금융기관 키워드 + 금액이 있는 라인 (카드이용/대출과목 섹션 제외)
            if (loanYear == 0 && hasSeenSimplifiedFormat && hasFinancialKeyword && !inCardUsageSection && !inLoanCategorySection) {
                val hasAmountHint = line.contains("만") || line.contains("억") || Pattern.compile("\\d{3,}").matcher(line).find()
                if (hasAmountHint) {
                    loanYear = Calendar.getInstance().get(Calendar.YEAR)
                    loanMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
                    loanDay = 1
                    Log.d("HWP_PARSE", "간이형식 날짜없는 채무: ${loanYear}.${loanMonth} - $line")
                }
            }

            if (loanYear > 0) {
                var debtAmount = 0
                if (line.contains("만") || line.contains("억")) {
                    debtAmount = extractAmount(line) * 10
                } else if (isSimplifiedFormat && dateEndPos > 0) {
                    // 간이형식: 날짜 뒤 숫자 = 만원 단위
                    val afterDate = line.substring(dateEndPos)
                    val amtM = Pattern.compile("(\\d[\\d,]*)").matcher(afterDate)
                    if (amtM.find()) {
                        val numStr = amtM.group(1)!!.replace(",", "")
                        debtAmount = numStr.toInt() * 10  // 만원 → 천원
                    }
                } else {
                    val commaM = Pattern.compile("([\\d,]+)$").matcher(line.trim())
                    if (commaM.find()) {
                        val numStr = commaM.group(1)!!.replace(",", "")
                        if (numStr.isNotEmpty()) debtAmount = numStr.toInt()
                    }
                }

                if (debtAmount > 0) {
                    // 보증기금/보증보험 → 기본 대출의 보증채무이므로 제외 (실채무는 은행/캐피탈 등에서 별도 계산)
                    if (lineNoSpace.contains("보증기금") || lineNoSpace.contains("보증보험")) {
                        Log.d("HWP_PARSE", "보증기금/보증보험 제외: ${(debtAmount+5)/10}만 - $line")
                    } else {
                    // 담보대출 처리: 지급보증 제외한 담보대출은 담보로 계산 (대상채무 제외)
                    // 대출과목에서 담보로 지정된 채권사도 담보 처리
                    val creditorInLine = extractCreditorFromLine(line)
                    val isLoanCategoryDambo = creditorInLine.isNotEmpty() && loanCategoryExcludedCreditors.any { (damboCreditor, damboType) ->
                        val creditorMatch = creditorInLine.contains(damboCreditor) || damboCreditor.contains(creditorInLine)
                        if (!creditorMatch) false
                        else when {
                            // 전세는 같은 채권사에 전세+신용 둘 다 있을 수 있으므로 라인에 "전세" 키워드로 구분
                            damboType.contains("전세") -> lineNoSpace.contains("전세")
                            // 차담보: 차량 섹션의 담보 금액과 표의 금액을 비교하여 특정 대출만 제외
                            damboType.contains("차") -> {
                                if (carDamboAmountsChun.isEmpty()) true // 차량 정보 없으면 채권사명만으로 매칭
                                else carDamboAmountsChun.any { carAmtChun ->
                                    val ratio = if (carAmtChun > 0) Math.abs(debtAmount - carAmtChun).toDouble() / carAmtChun else 1.0
                                    ratio < 0.05 // 5% 이내 오차 허용
                                }
                            }
                            // 퇴직금담보: 같은 채권사에 신용+퇴직금담보 혼재 시 금액으로 구분
                            damboType.contains("퇴직금") -> {
                                val retAmounts = retirementDamboAmountsChun[damboCreditor]
                                if (retAmounts.isNullOrEmpty()) true // 금액 없으면 채권사명만으로 매칭
                                else retAmounts.any { retAmtChun ->
                                    val ratio = if (retAmtChun > 0) Math.abs(debtAmount - retAmtChun).toDouble() / retAmtChun else 1.0
                                    ratio < 0.05
                                }
                            }
                            // 일반 담보 (중도금, 약관 등)
                            else -> true
                        }
                    }
                    val isDamboLoan = ((lineNoSpace.contains("담보") || lineNoSpace.contains("할부금융") || lineNoSpace.contains("리스") || lineNoSpace.contains("후순위") || lineNoSpace.contains("중고차할부") || lineNoSpace.contains("(510)") || lineNoSpace.contains("시설자금")) && !lineNoSpace.contains("지급보증") && !lineNoSpace.contains("(240)") && !lineNoSpace.contains("무담보")) || isLoanCategoryDambo

                    // 운전자금+지급보증 같은 날짜 중복 제거: 둘 중 높은 금액만 계산
                    val isJiguBojung = lineNoSpace.contains("지급보증")
                    val isBusinessLoan = (lineNoSpace.contains("운전자금") || lineNoSpace.contains("개인사업자대출") || lineNoSpace.contains("사업자대출")) && !isJiguBojung
                    val loanDateKey = "${loanYear}.${loanMonth}.${loanDay}"
                    if (isBusinessLoan && loanYear > 0) {
                        businessLoanDates[loanDateKey] = debtAmount
                    }
                    val prevBusinessAmount = businessLoanDates[loanDateKey]
                    if (isJiguBojung && loanYear > 0 && prevBusinessAmount != null && debtAmount <= prevBusinessAmount) {
                        // 지급보증 금액 <= 운전자금 금액 → 이미 높은 금액 계산됨, 지급보증 제외
                        jiguBojungExcludedMan += (debtAmount + 5) / 10
                        Log.d("HWP_PARSE", "지급보증 중복 제외 (운전자금${prevBusinessAmount}천원 >= 지급보증${debtAmount}천원): $loanDateKey - $line")
                    } else if (isJiguBojung && loanYear > 0 && prevBusinessAmount != null && debtAmount > prevBusinessAmount) {
                        // 지급보증 금액 > 운전자금 금액 → 차액만 추가 계산, 운전자금분은 제외
                        val extraAmount = debtAmount - prevBusinessAmount
                        totalParsedDebt += (extraAmount + 5) / 10
                        jiguBojungExcludedMan += (prevBusinessAmount + 5) / 10 // 운전자금 금액분은 중복이므로 제외
                        businessLoanDates[loanDateKey] = debtAmount // 높은 금액으로 갱신
                        Log.d("HWP_PARSE", "지급보증이 더 큼 → 차액만 추가: (${debtAmount}-${prevBusinessAmount})=${extraAmount}천원 - $line")
                    } else {
                        // 보증채무 중복 제거 (담보/비담보 공통)
                        val isGuaranteeDebt = line.contains(" 보증 ") || line.contains(" 보증(") || lineNoSpace.contains("보증채무")
                        val dateAmountKey = "${loanYear}.${loanMonth}.${loanDay}_${debtAmount}"
                        val prevGuarantee = debtDateAmountSeen[dateAmountKey]

                        if (prevGuarantee != null && (isGuaranteeDebt || prevGuarantee)) {
                            Log.d("HWP_PARSE", "보증채무 중복 제외: $dateAmountKey - $line")
                        } else {
                            debtDateAmountSeen[dateAmountKey] = isGuaranteeDebt
                            totalParsedDebt += (debtAmount + 5) / 10

                        // 6개월 이내 채무 수집 (담보 포함 모든 채무)
                        val loanCal = Calendar.getInstance().apply {
                            set(loanYear, loanMonth - 1, if (loanDay > 0) loanDay else 15)
                        }
                        val sixMonthsAgo = Calendar.getInstance().apply { add(Calendar.MONTH, -6) }
                        if (loanCal.after(sixMonthsAgo)) {
                            recentDebtEntries.add(Pair(loanCal, debtAmount))
                            Log.d("HWP_PARSE", "6개월 수집: ${loanYear}.${loanMonth}.${loanDay} ${debtAmount}천원 (${(debtAmount+5)/10}만) - $line")
                        }

                        // 신청일자 이후 추가채무 체크
                        if (pdfApplicationDate.isNotEmpty()) {
                            val parts = pdfApplicationDate.split(".")
                            if (parts.size == 3) {
                                val appCal = Calendar.getInstance().apply {
                                    set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                                }
                                if (loanCal.after(appCal)) {
                                    postApplicationDebtMan += (debtAmount + 5) / 10
                                    Log.d("HWP_PARSE", "신청일자 이후 채무: ${loanYear}.${loanMonth}.${loanDay} ${(debtAmount+5)/10}만 - $line")
                                }
                            }
                        }

                        if (isDamboLoan) {
                            parsedDamboTotal += (debtAmount + 5) / 10
                            Log.d("HWP_PARSE", "담보대출 (대상채무 제외): $line")
                        } else if (hasFinancialKeyword) {
                            // 학자금 합산 (비율 계산용)
                            if (lineNoSpace.contains("학자금") || lineNoSpace.contains("(150)") || lineNoSpace.contains("장학재단")) {
                                studentLoanTotal += debtAmount
                            }

                            // 표 전체 합산 (학자금 비율 계산용)
                            tableDebtTotal += debtAmount

                            // 현금서비스(0041) 추적 (카드이용금액 비교용)
                            if (lineNoSpace.contains("(0041)") || lineNoSpace.contains("현금서비스")) {
                                val cardName = extractCardCompanyName(line)
                                if (cardName.isNotEmpty()) {
                                    val amountMan = (debtAmount + 5) / 10
                                    cashAdvanceByCard[cardName] = (cashAdvanceByCard[cardName] ?: 0) + amountMan
                                    Log.d("HWP_PARSE", "현금서비스 추적: $cardName ${amountMan}만 - $line")
                                }
                            }

                            // 사업자대출 감지
                            if (lineNoSpace.contains("개인사업자대출") || lineNoSpace.contains("운전자금") ||
                                lineNoSpace.contains("사업자대출") || lineNoSpace.contains("(1051)")) {
                                hasBusinessLoan = true
                            }

                            // 채권사명 추출 및 추적 (학자금 제외)
                            val isStudentLoan = lineNoSpace.contains("학자금") || lineNoSpace.contains("(150)") || lineNoSpace.contains("장학재단")
                            if (!isStudentLoan) {
                                val creditorName = extractCreditorFromLine(line)
                                if (creditorName.isNotEmpty()) {
                                    val amountMan = (debtAmount + 5) / 10
                                    parsedCreditors[creditorName] = (parsedCreditors[creditorName] ?: 0) + amountMan
                                    Log.d("HWP_PARSE", "채권사 추적: $creditorName ${amountMan}만 (누적=${parsedCreditors[creditorName]}만)")
                                }
                            }
                        }
                    }
                    }
                    } // else (보증기금 아닌 경우)
                }
            }

            // 대출과목 섹션 감지 (신복위 파싱 제외용)
            if (lineNoSpace.contains("대출과목") || lineNoSpace.contains("[대출과목]")) {
                inLoanCategorySection = true
            }
            if (inLoanCategorySection) {
                if (lineNoSpace.contains("요약사항") || lineNoSpace.contains("최저납부") || lineNoSpace.contains("요약]")) {
                    inLoanCategorySection = false
                }
                // 대출과목에서 담보 표시된 채권사 추출: "우리은행(전세대출)", "에이원대부(차담보)", "농협은행900만(퇴직금담보)" 등
                val damboCreditorMatches = Regex("([가-힣A-Za-zＡ-Ｚａ-ｚ]+)([^()]*)\\(\\s*(전세대출|전세|차담보|차량담보|담보|담보대출|퇴직금담보|퇴직금|중도금|약관)\\s*\\)").findAll(line)
                for (match in damboCreditorMatches) {
                    val creditorName = match.groupValues[1].replace(" ", "")
                    val middleText = match.groupValues[2]
                    val damboType = match.groupValues[3]
                    loanCategoryExcludedCreditors.add(Pair(creditorName, damboType))
                    Log.d("HWP_PARSE", "대출과목 담보 채권사 감지: $creditorName ($damboType) - $line")
                    // 퇴직금담보: 금액이 명시된 경우 금액 매칭용으로 저장
                    if (damboType.contains("퇴직금")) {
                        val amounts = Regex("(\\d[\\d,]*)만").findAll(middleText)
                        for (amt in amounts) {
                            val manWon = amt.groupValues[1].replace(",", "").toInt()
                            retirementDamboAmountsChun.getOrPut(creditorName) { mutableListOf() }.add(manWon * 10)
                        }
                    }
                    // 대출과목 카테고리 담보 금액 추출: "기타(차담보) = 3000만" → parsedDamboTotal에 합산
                    // 실제 채권사가 아닌 카테고리명(기타, 기타대출 등)은 표 매칭 불가 → 직접 금액 추출
                    val isGenericCategory = creditorName in listOf("기타", "기타대출", "담보", "담보대출")
                    if (isGenericCategory) {
                        // 같은 줄에서 금액 추출 (매치 영역 밖 포함)
                        val lineAmount = extractAmount(line)
                        if (lineAmount > 0) {
                            parsedDamboTotal += lineAmount
                            Log.d("HWP_PARSE", "대출과목 카테고리 담보 금액: $creditorName($damboType) = ${lineAmount}만 → parsedDamboTotal=${parsedDamboTotal}만")
                        }
                    }
                }
                // 대출과목에서 신복/신복위 금액 파싱 (PDF 없을 때만)
                if (!hasPdfFile && (lineNoSpace.contains("신복") || lineNoSpace.contains("신용회복")) && textShinbokDebt == 0) {
                    val amountMatcher = Pattern.compile("(\\d[\\d,]*)만").matcher(lineNoSpace)
                    if (amountMatcher.find()) {
                        textShinbokDebt = amountMatcher.group(1)!!.replace(",", "").toInt()
                        Log.d("HWP_PARSE", "대출과목 신복위 채무 감지: ${textShinbokDebt}만 - $line")
                    }
                }
                // 대출과목에서 국세/지방세/세금 파싱
                if ((lineNoSpace.contains("국세") || lineNoSpace.contains("지방세") || lineNoSpace.contains("세금")) && textTaxDebt == 0) {
                    val amountMatcher = Pattern.compile("(\\d[\\d,]*)만").matcher(lineNoSpace)
                    if (amountMatcher.find()) {
                        textTaxDebt = amountMatcher.group(1)!!.replace(",", "").toInt()
                        Log.d("HWP_PARSE", "대출과목 국세/세금 감지: ${textTaxDebt}만 - $line")
                    }
                }
            }

            // 카드이용금액 섹션 파싱
            if (lineNoSpace.contains("카드이용금액") || lineNoSpace.contains("카드이용액") || lineNoSpace.contains("카드사용")) {
                inCardUsageSection = true
                // 같은 줄에 "x"가 있으면 카드이용금액 없음
                if (lineNoSpace.contains("x") || lineNoSpace.contains("없음")) {
                    inCardUsageSection = false
                    Log.d("HWP_PARSE", "카드이용금액 없음(x): $line")
                } else {
                    // 같은 줄에 금액이 있을 수도 있음: "카드이용금액 : 삼성카드 1000만, KB국민카드 200만"
                    val cardAmounts = Regex("([가-힣A-Za-z]+카드)\\s*(\\d+)만").findAll(afterColonOrLine(line))
                    for (match in cardAmounts) {
                        val cardName = match.groupValues[1]
                        val amount = match.groupValues[2].toInt()
                        parsedCardUsageTotal += amount
                        parsedCardUsageCreditors[cardName] = (parsedCardUsageCreditors[cardName] ?: 0) + amount
                        Log.d("HWP_PARSE", "카드이용금액: $cardName ${amount}만 ($line)")
                    }
                }
            } else if (inCardUsageSection) {
                // 카드이용금액 섹션의 다음 줄들
                if (lineNoSpace.isEmpty() || lineNoSpace.startsWith("[") || lineNoSpace.contains("대출과목") || lineNoSpace.contains("요약")) {
                    inCardUsageSection = false // 섹션 종료
                } else {
                    var cardParsed = false
                    // "삼성카드 1000만" or "KB국민카드 200만" 등 추출
                    val cardAmounts = Regex("([가-힣A-Za-z]+카드)\\s*(\\d+)만").findAll(line)
                    for (match in cardAmounts) {
                        val cardName = match.groupValues[1]
                        val amount = match.groupValues[2].toInt()
                        parsedCardUsageTotal += amount
                        parsedCardUsageCreditors[cardName] = (parsedCardUsageCreditors[cardName] ?: 0) + amount
                        Log.d("HWP_PARSE", "카드이용금액: $cardName ${amount}만 ($line)")
                        cardParsed = true
                    }
                    // "신한 370만" 처럼 카드사 약칭만 있는 경우 (카드 글자 없음)
                    if (!cardParsed) {
                        val shortCardNames = mapOf("신한" to "신한카드", "삼성" to "삼성카드", "현대" to "현대카드",
                            "롯데" to "롯데카드", "하나" to "하나카드", "우리" to "우리카드", "국민" to "KB국민카드",
                            "농협" to "농협카드", "비씨" to "BC카드", "씨티" to "씨티카드")
                        val shortMatch = Regex("([가-힣A-Za-z]+)\\s*(\\d+)만").find(line)
                        if (shortMatch != null) {
                            val rawName = shortMatch.groupValues[1].trim()
                            val amount = shortMatch.groupValues[2].toInt()
                            val fullCardName = shortCardNames[rawName] ?: "${rawName}카드"
                            parsedCardUsageTotal += amount
                            parsedCardUsageCreditors[fullCardName] = (parsedCardUsageCreditors[fullCardName] ?: 0) + amount
                            Log.d("HWP_PARSE", "카드이용금액(약칭): $rawName→$fullCardName ${amount}만 ($line)")
                            cardParsed = true
                        }
                    }
                }
            }
        }

        // ★ 채권사 데이터 계산 (코드파싱)
        // 카드이용금액 채권사를 메인 채권사맵에 병합
        for ((cardName, cardAmount) in parsedCardUsageCreditors) {
            parsedCreditors[cardName] = (parsedCreditors[cardName] ?: 0) + cardAmount
        }
        // 대출 유형/카테고리명은 채권사가 아니므로 제외
        val loanTypeNames = setOf("신용대출", "신용", "카드", "카드대출", "담보", "담보대출", "대출", "기타", "기타대출", "총액", "국세", "지방세", "세금", "지급보증", "연대보증", "보증", "고소", "소송", "벌금")
        // 미협약 채무 계산 (카드이용금액 채권사는 미협약 판단 제외)
        for ((name, amount) in parsedCreditors) {
            val nameTrimmed = name.replace(Regex("[\\s]"), "")
            if (loanTypeNames.contains(nameTrimmed)) continue
            if (parsedCardUsageCreditors.containsKey(name)) continue // 카드이용금액은 미협약 판단 제외
            if (!AffiliateList.isAffiliated(name)) {
                nonAffiliatedDebt += amount
                nonAffiliatedNames.add(name)
                Log.d("HWP_CALC", "미협약 채권사: $name ${amount}만")
            } else {
                Log.d("HWP_CALC", "협약 채권사: $name ${amount}만")
            }
        }
        // 과반 채권사 (대상채무 중 가장 큰 채권사)
        val majorExcludedNames = setOf("신복위", "신복", "신용", "카드", "담보", "신용회복위원회")
        for ((name, amount) in parsedCreditors) {
            if (name in majorExcludedNames || loanTypeNames.contains(name)) continue
            if (amount > majorCreditorDebtVal) {
                majorCreditorDebtVal = amount
                majorCreditorName = name
            }
        }
        // 채권사 수 (대출유형명 제외)
        parsedCreditorCount = parsedCreditors.keys.count { !loanTypeNames.contains(it) && it !in majorExcludedNames }
        Log.d("HWP_CALC", "채권사 데이터 (코드파싱): 총${parsedCreditorCount}건, 미협약=${nonAffiliatedDebt}만(${nonAffiliatedNames.size}건), 최대=${majorCreditorName}(${majorCreditorDebtVal}만)")

        // ★ 대상채무 결정: 코드파싱 (표 비담보 + 카드이용금액)
        val parsedNonDamboDebt = totalParsedDebt - parsedDamboTotal  // 표에서 비담보 채무
        // 카드이용금액 vs 현금서비스 비교 → 순 추가분 계산
        var cardNetAddition = 0
        if (parsedCardUsageTotal > 0) {
            val totalCashAdvance = cashAdvanceByCard.values.sum()
            if (parsedCardUsageTotal >= totalCashAdvance) {
                // 카드이용금액 >= 현금서비스 → 현금서비스 제외, 카드이용금액으로 대체
                cardNetAddition = parsedCardUsageTotal - totalCashAdvance
                Log.d("HWP_CALC", "카드이용금액(${parsedCardUsageTotal}만) >= 현금서비스(${totalCashAdvance}만) → 순추가=${cardNetAddition}만")
            } else {
                // 카드이용금액 < 현금서비스 → 카드이용금액 추가 (현금서비스는 이미 포함)
                cardNetAddition = parsedCardUsageTotal
                Log.d("HWP_CALC", "카드이용금액(${parsedCardUsageTotal}만) < 현금서비스(${totalCashAdvance}만) → 추가=${cardNetAddition}만")
            }
        }
        val codeParsedTargetDebt = parsedNonDamboDebt + cardNetAddition
        if (codeParsedTargetDebt > 0) {
            targetDebt = codeParsedTargetDebt
            Log.d("HWP_CALC", "코드파싱 대상채무: 비담보=${parsedNonDamboDebt}만 + 카드=${cardNetAddition}만 = ${targetDebt}만")
        } else {
            Log.d("HWP_CALC", "코드파싱 대상채무 0 → AI값 유지: ${targetDebt}만")
        }

        // 지급보증 중복분 대상채무에서 차감 (코드파싱에선 이미 처리됨)
        if (jiguBojungExcludedMan > 0) {
            Log.d("HWP_CALC", "지급보증 중복 제외: ${jiguBojungExcludedMan}만 (코드파싱에서 이미 처리)")
        }

        // ★ 미협약 채무 차감 (대상채무에서 제외)
        if (nonAffiliatedDebt > targetDebt) nonAffiliatedDebt = targetDebt
        targetDebt -= nonAffiliatedDebt
        if (nonAffiliatedDebt > 0) Log.d("HWP_CALC", "미협약 차감: ${nonAffiliatedDebt}만 → 대상채무=${targetDebt}만")

        // 학자금 계산 (단기/장기 분리용)
        val studentLoanMan = (studentLoanTotal + 5) / 10
        val tableDebtMan = (tableDebtTotal + 5) / 10
        val studentLoanRatio = if (tableDebtMan > 0) studentLoanMan.toDouble() / tableDebtMan * 100 else 0.0
        if (studentLoanMan > 0) {
            if (studentLoanRatio >= 50) specialNotesList.add("학자금 많음")
            Log.d("HWP_CALC", "학자금: ${studentLoanMan}만 / 표전체${tableDebtMan}만 = ${String.format("%.1f", studentLoanRatio)}%")
        }

        // 합의서 PDF 진행중 제도 감지
        if (pdfAgreementProcess.isNotEmpty()) {
            hasOngoingProcess = true
            // AI 응답 정규화: "신속채무조정" 등 전체 이름 → "신"/"프"/"워"
            ongoingProcessName = when {
                pdfAgreementProcess.contains("신속") -> "신"
                pdfAgreementProcess.contains("사전") -> "프"
                pdfAgreementProcess.contains("개인") || pdfAgreementProcess.contains("워크") || pdfAgreementProcess == "워" -> "워"
                pdfAgreementProcess == "신" || pdfAgreementProcess == "프" -> pdfAgreementProcess
                else -> pdfAgreementProcess
            }
            delinquentDays = maxOf(delinquentDays, 1095)
            Log.d("HWP_CALC", "합의서 PDF 제도 적용: $pdfAgreementProcess → $ongoingProcessName 진행중")
        }

        // 합의서 대상채무 (PDF) + 한글파일 채무현황 합산
        // AI는 신복위 채무를 제외하므로 합의서와 중복 없이 합산
        if (pdfAgreementDebt > 0) {
            Log.d("HWP_PARSE", "합의서 대상채무 합산: 한글=${targetDebt}만 + 합의서=${pdfAgreementDebt}만 = ${targetDebt + pdfAgreementDebt}만")
            targetDebt += pdfAgreementDebt
        } else if (!hasPdfFile && textShinbokDebt > 0 && hasOngoingProcess) {
            // PDF 없을 때 대출과목에서 파싱한 신복위 채무 합산
            Log.d("HWP_PARSE", "신복위 채무 합산 (PDF없음): 한글=${targetDebt}만 + 신복=${textShinbokDebt}만 = ${targetDebt + textShinbokDebt}만")
            targetDebt += textShinbokDebt
        }

        // 신청일자 이후 추가채무는 AI targetDebt에 이미 포함되므로 별도 합산하지 않음
        if (postApplicationDebtMan > 0) {
            Log.d("HWP_PARSE", "신청일자 이후 추가채무: ${postApplicationDebtMan}만 (AI targetDebt에 이미 포함)")
        }

        // 합의서 제외 채무 합산
        if (pdfExcludedGuaranteeDebt > 0) {
            targetDebt += pdfExcludedGuaranteeDebt
            Log.d("HWP_PARSE", "합의서 제외 보증서담보대출 대상채무 합산: +${pdfExcludedGuaranteeDebt}만 → ${targetDebt}만")
        }
        if (pdfExcludedOtherDebt > 0) {
            parsedDamboTotal += pdfExcludedOtherDebt
            Log.d("HWP_PARSE", "합의서 제외 기타 담보대출 합산: +${pdfExcludedOtherDebt}만 → ${parsedDamboTotal}만")
        }

        // ★ 재산 결정: 코드파싱 재산 사용 (예적금은 이미 별도 변수)
        // 실제 파싱된 재산이 있으면 "재산 x"보다 우선 (지역 라인 등에서 파싱된 경우)
        if (parsedProperty > 0) {
            netProperty = parsedProperty
            Log.d("HWP_CALC", "코드파싱 재산 사용: ${parsedProperty}만 (타인명의: ${parsedOthersProperty}만)")
        }
        // 차량 시세를 재산에 포함 (시세 - 담보, 음수면 0)
        val netCarValue = maxOf(carTotalSise - carTotalLoan, 0)
        var spouseCarValue = 0
        for (carInfo in carInfoList) {
            if (carInfo[3] == 1) { // 배우자명의
                spouseCarValue += maxOf(carInfo[0] - carInfo[1], 0)
            }
        }
        if (netCarValue > 0) {
            netProperty += netCarValue
            Log.d("HWP_CALC", "차량 재산 포함: 시세=${carTotalSise}만 - 담보=${carTotalLoan}만 = ${netCarValue}만" +
                if (spouseCarValue > 0) " (배우자명의: ${spouseCarValue}만)" else "")
        }
        // 예적금은 재산에 포함 (단기용에는 포함, 장기에서만 제외)
        netProperty += savingsDeposit

        // 단기용 재산: 예적금 포함, 등본 분리 미적용
        val originalNetProperty = netProperty

        // 예적금 제외 (장기 재산에만 적용, 단기는 포함)
        if (savingsDeposit > 0) {
            netProperty = netProperty - savingsDeposit
            if (netProperty < 0) netProperty = 0
            Log.d("HWP_CALC", "예적금 제외(장기): 재산 $originalNetProperty → ${netProperty}만 (예적금 ${savingsDeposit}만)")
        }

        // 등본 분리 시 재산 조정 (타인/배우자명의 재산 + 배우자 차량 제외, 장기만)
        val othersPropertyTotal = parsedOthersProperty + spouseCarValue
        val isRegistrySplit = hasOthersRealEstate && othersPropertyTotal > 0
        if (isRegistrySplit) {
            netProperty = netProperty - othersPropertyTotal
            if (netProperty < 0) netProperty = 0
            Log.d("HWP_CALC", "등본 분리 적용: 재산 → $netProperty (부동산 ${parsedOthersProperty}만 + 차량 ${spouseCarValue}만 제외)")
        }

        // ★ 개별 차량 처분 판단 로직
        var needsCarDisposal = false
        var dispCarSise = 0
        var dispCarLoan = 0
        var dispCarDeficit = 0  // 개별 차량별 부족분(대출>시세) 합계
        var carDisposalReasons = mutableListOf<String>()

        fun shouldDisposeCar(info: IntArray): Boolean {
            // info: [시세, 대출, 월납부, 배우자(1/0), 외제(1/0)]
            return info[0] >= 1000 || info[2] >= 50 || info[4] == 1
        }

        fun getDisposalReason(info: IntArray): String {
            val reasons = mutableListOf<String>()
            if (info[1] > info[0]) {
                // 대출 > 시세 (역초과) → 담보대출 금액 표시
                reasons.add("담보대출${info[1]}만")
            } else if (info[0] >= 1000) {
                reasons.add("시세${info[0]}만")
            }
            if (info[2] >= 50) reasons.add("월납${info[2]}만")
            if (info[4] == 1) reasons.add("외제차")
            return reasons.joinToString("/")
        }

        if (carInfoList.size >= 2) {
            // 다수 차량: 1대 남기고 전부 처분
            val nonConditionCars = carInfoList.filter { !shouldDisposeCar(it) }

            if (nonConditionCars.isNotEmpty()) {
                // 조건 미해당 차량이 있으면 → 월납부 가장 낮은 1대 남기고 나머지 전부 처분
                val sortedNonCondition = nonConditionCars.sortedBy { it[2] }  // 월납부 오름차순
                val keepCar = sortedNonCondition[0]  // 월납부 가장 낮은 차량 보유
                for (info in carInfoList) {
                    if (info === keepCar) continue  // 보유 차량 skip
                    dispCarSise += info[0]
                    dispCarLoan += info[1]
                    if (info[1] > info[0]) dispCarDeficit += info[1] - info[0]  // 개별 부족분
                    val reason = if (shouldDisposeCar(info)) getDisposalReason(info) else "다수차량(월납${info[2]}만)"
                    carDisposalReasons.add(reason)
                }
                needsCarDisposal = true
            } else {
                // 모든 차량 조건 해당 → 전부 처분 (마지막 1대 포함)
                for (info in carInfoList) {
                    dispCarSise += info[0]
                    dispCarLoan += info[1]
                    if (info[1] > info[0]) dispCarDeficit += info[1] - info[0]  // 개별 부족분
                    carDisposalReasons.add(getDisposalReason(info))
                }
                needsCarDisposal = true
            }
        } else if (carInfoList.size == 1) {
            // 단일 차량: 조건 해당이면 처분
            val info = carInfoList[0]
            if (shouldDisposeCar(info)) {
                dispCarSise = info[0]
                dispCarLoan = info[1]
                if (info[1] > info[0]) dispCarDeficit = info[1] - info[0]  // 개별 부족분
                carDisposalReasons.add(getDisposalReason(info))
                needsCarDisposal = true
            }
        }

        // 처분 차량 기준 carValue 설정
        if (needsCarDisposal) {
            carValue = maxOf(0, dispCarSise - dispCarLoan)
            Log.d("HWP_CALC", "차량 처분 판단: needsCarDisposal=$needsCarDisposal, 처분시세=$dispCarSise, 처분대출=$dispCarLoan, carValue=$carValue, 사유=$carDisposalReasons")
        }

        val allCarTotalSise = carTotalSise
        // 합산 변수를 처분 차량 기준으로 교체 → 하류 처분 계산이 자동으로 처분 차량 기준 동작
        if (needsCarDisposal) {
            carTotalSise = dispCarSise
            carTotalLoan = dispCarLoan
        }

        // ★ 6개월 이내 채무: 표에서 파싱한 값만 사용
        val recentDebtMan = recentDebtEntries.sumOf { (it.second + 5) / 10 }
        // 차량 처분 시 개별 차량별 부족분(대출>시세) → 대상채무에 추가
        val carDebtDeficit = dispCarDeficit
        val targetDebtBeforeDisposal = targetDebt
        if ((needsCarDisposal || wantsCarSale) && carDebtDeficit > 0) {
            targetDebt += carDebtDeficit
            Log.d("HWP_CALC", "차량 처분 → 대상채무 추가 (개별부족분=${carDebtDeficit}만): 대상채무=${targetDebt}만")
            carTotalLoan = carTotalSise  // 부족분은 대상채무에 반영됨, 하류 중복 방지
        }

        // 6개월 비율: 6개월 이내 채무 / 전체 채무 (처분 전 대상채무 + 담보대출)
        val totalDebtForRatio = maxOf(totalParsedDebt, targetDebtBeforeDisposal + parsedDamboTotal)
        recentDebtRatio = if (totalDebtForRatio > 0 && recentDebtMan > 0) recentDebtMan.toDouble() / totalDebtForRatio * 100 else 0.0

        // ★ 미협약 비율: 처분 반영된 대상채무 + 미협약 포함 기준
        val originalTargetDebt = targetDebt
        val nonAffiliatedOver20: Boolean
        if (nonAffiliatedDebt > 0) {
            val nonAffiliatedRatio = if (targetDebt > 0) nonAffiliatedDebt.toDouble() / (targetDebt + nonAffiliatedDebt) * 100 else 0.0
            nonAffiliatedOver20 = nonAffiliatedRatio >= 20
            val nonAffNamesStr = if (nonAffiliatedNames.isNotEmpty()) nonAffiliatedNames.joinToString(",") else ""
            if (nonAffiliatedOver20) {
                specialNotesList.add("미협약 ${String.format("%.0f", nonAffiliatedRatio)}% (신복위 불가) $nonAffNamesStr".trim())
            } else {
                specialNotesList.add("미협약 ${nonAffiliatedDebt}만 별도 (${String.format("%.0f", nonAffiliatedRatio)}%) $nonAffNamesStr".trim())
            }
            Log.d("HWP_CALC", "미협약 ${nonAffiliatedDebt}만 대상채무 제외 (비율 ${String.format("%.1f", nonAffiliatedRatio)}%)")
        } else {
            nonAffiliatedOver20 = false
        }

        Log.d("HWP_CALC", "6개월 비율: ${recentDebtMan}만 / ${totalDebtForRatio}만 = ${String.format("%.1f", recentDebtRatio)}%")
        // ★ 소득 결정 우선순위: 1) isIncomeX → 0, 2) 코드파싱 소득 vs 소금원 중 높은 값, 3) 예정/예상 소득
        // 연봉 → 실수령액 계산
        val netSalaryFromYeonbong = if (parsedAnnualSalary > 0) calculateMonthlyNetSalary(parsedAnnualSalary) else 0
        if (netSalaryFromYeonbong > 0) {
            Log.d("HWP_CALC", "연봉 ${parsedAnnualSalary}만 → 실수령액 ${netSalaryFromYeonbong}만")
        }

        if (isIncomeX) {
            income = 0
            Log.d("HWP_CALC", "HWP '소득 x' 감지 → 소득 0으로 강제")
        } else if (netSalaryFromYeonbong > 0) {
            // 연봉 실수령액 우선 사용 (소금원보다 높으면)
            income = maxOf(netSalaryFromYeonbong, aiSogumwonMonthly)
            if (aiSogumwonMonthly > netSalaryFromYeonbong) {
                Log.d("HWP_CALC", "소금원 소득 사용: ${aiSogumwonMonthly}만 > 연봉실수령 ${netSalaryFromYeonbong}만")
            } else {
                Log.d("HWP_CALC", "연봉 실수령액 사용: ${netSalaryFromYeonbong}만 (연봉=${parsedAnnualSalary}만)")
            }
        } else if (parsedIncome > 0 || aiSogumwonMonthly > 0) {
            // 코드파싱 소득과 소금원 소득 중 높은 값 사용
            income = maxOf(parsedIncome, aiSogumwonMonthly)
            if (aiSogumwonMonthly > parsedIncome) {
                Log.d("HWP_CALC", "소금원 소득 사용: ${aiSogumwonMonthly}만 > 파싱 ${parsedIncome}만")
            } else {
                Log.d("HWP_CALC", "코드파싱 소득 사용: ${parsedIncome}만")
            }
        }
        // 소득 0이고 예정/예상 소득이 있으면 적용
        if (income <= 0 && estimatedIncomeParsed > 0) {
            income = estimatedIncomeParsed
            isIncomeEstimated = true
            Log.d("HWP_CALC", "소득 0 → HWP 예정 소득 적용: ${estimatedIncomeParsed}만")
        }
        Log.d("HWP_CALC", "최종 소득: ${income}만 (파싱=${parsedIncome}, 연봉실수령=${netSalaryFromYeonbong}, 소금원=${aiSogumwonMonthly}, 예정=${estimatedIncomeParsed}, isX=${isIncomeX})")

        // 채무 한도 체크 (만원 기준)
        val totalUnsecuredDebt = originalTargetDebt + nonAffiliatedDebt  // 담보가 아닌 채무
        val totalSecuredDebt = parsedDamboTotal      // 담보채무
        val shortTermDebtOverLimit = totalUnsecuredDebt > 100000 || totalSecuredDebt > 150000  // 단순(회생): 무담보10억/담보15억
        val longTermDebtOverLimit = totalUnsecuredDebt > 50000 || totalSecuredDebt > 100000    // 장기(신복위): 무담보5억/담보10억
        if (shortTermDebtOverLimit) Log.d("HWP_CALC", "단순(회생) 채무한도 초과: 무담보=${totalUnsecuredDebt}만, 담보=${totalSecuredDebt}만")
        if (longTermDebtOverLimit) Log.d("HWP_CALC", "장기(신복위) 채무한도 초과: 무담보=${totalUnsecuredDebt}만, 담보=${totalSecuredDebt}만")

        // 개인회생이 있고 회생 진행중이 아니면 면책으로 처리
        // "개인회생 진행중" 또는 "회생 진행중"이 명시적으로 있어야 회생 진행중
        // "워크진행중"은 워크아웃 진행중이므로 개인회생 면책과 무관
        val isRecoveryOngoing = hasPersonalRecovery && lines.any {
            val ns = it.replace(" ", "")
            (ns.contains("개인회생진행") || ns.contains("회생진행중") || ns.contains("개인회생진행중"))
        }
        if (hasPersonalRecovery && !isRecoveryOngoing && !hasDischarge && !isDismissed) {
            hasDischarge = true
            if (personalRecoveryYear > 0) {
                dischargeYear = personalRecoveryYear
                dischargeMonth = personalRecoveryMonth
            }
            Log.d("HWP_CALC", "개인회생 → 면책 처리 (회생 진행중 아님): ${dischargeYear}년 ${dischargeMonth}월")
        }
        // 폐지 후 면책을 받았으면 폐지는 무효 (이후 회생이 성공한 것)
        if (isDismissed && hasDischarge) {
            isDismissed = false
            Log.d("HWP_CALC", "폐지+면책 → 폐지 무효 처리 (면책이 폐지를 대체)")
        }

        // ★ 과반 비율: 미협약 포함 전체 채무 기준으로 계산
        // 대출과목 요약 카테고리(신복위, 신복, 신용, 카드, 담보)는 실제 채권사가 아니므로 과반 판단 제외
        val majorExcluded = setOf("신복위", "신복", "신용", "카드", "담보", "신용회복위원회")
        majorCreditorRatio = if (originalTargetDebt > 0 && majorCreditorDebtVal > 0 && majorCreditorName !in majorExcluded) majorCreditorDebtVal.toDouble() / originalTargetDebt * 100 else 0.0

        // 변제율 결정
        var repaymentRate = 90
        var rateReason = ""
        // 과반 채권사 → 100%
        if (majorCreditorRatio >= 50) {
            repaymentRate = 100; rateReason = "과반"
        }
        // 100%: 6개월 신규비율 80% 이상
        else if (recentDebtRatio >= 80) {
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
        val householdForShinbok = minOf(1 + minorChildren + collegeChildren, 6)
        val livingCostShinbok = livingCostTable[householdForShinbok]

        // 단기(회생) 계산
        var shortTermBlocked = false
        var shortTermBlockReason = ""
        val currentYear = java.time.LocalDate.now().year
        val currentMonth = java.time.LocalDate.now().monthValue

        // 면책 5년 이내 판단 (년월 비교)
        val dischargeWithin5Years = hasDischarge && !isBankruptcyDischarge && dischargeYear > 0 && (
                (currentYear - dischargeYear) < 5 ||
                        ((currentYear - dischargeYear) == 5 && dischargeMonth > 0 && currentMonth < dischargeMonth) ||
                        ((currentYear - dischargeYear) == 5 && dischargeMonth == 0)
                )

        // 세금채무 (코드파싱)
        if (textTaxDebt > 0) {
            taxDebt = textTaxDebt
            Log.d("HWP_CALC", "세금채무: ${taxDebt}만 (코드파싱)")
        }
        // 단기(회생)는 세금 포함
        val shortTermDebt = targetDebt + taxDebt
        if (taxDebt > 0) Log.d("HWP_CALC", "단기 대상채무: $targetDebt + 세금$taxDebt = ${shortTermDebt}만")

        if (dischargeWithin5Years) { shortTermBlocked = true; shortTermBlockReason = "면책 5년 이내" }
        // 재산초과: 재산 > 대상채무 → 단기 불가
        if (originalNetProperty > shortTermDebt && shortTermDebt > 0) {
            shortTermBlocked = true; if (shortTermBlockReason.isNotEmpty()) shortTermBlockReason += ", "
            shortTermBlockReason += "재산초과"
        }
        if (shortTermDebtOverLimit) {
            shortTermBlocked = true; if (shortTermBlockReason.isNotEmpty()) shortTermBlockReason += ", "
            val stLimitDetails = buildList {
                if (totalUnsecuredDebt > 100000) add("무담보${formatToEok(totalUnsecuredDebt)}")
                if (totalSecuredDebt > 150000) add("담보${formatToEok(totalSecuredDebt)}")
            }.joinToString("/")
            shortTermBlockReason += "채무한도초과($stLimitDetails)"
        }
        // 배우자 모르게는 단기불가 아님, 단기 진단 옆에 (배우자 모르게) 표시
        // if (spouseSecret) { shortTermBlocked = true; ... }
        // 경매/압류는 특이사항에만 표시, 단기불가 조건 아님

        // 차량 처분 의사 + 재산초과로 단기 불가일 때 → 차량 처분 기준으로 단기 재계산
        var shortTermAfterCarSale = ""
        var shortTermCarSaleApplied = false
        if ((wantsCarSale || needsCarDisposal) && shortTermBlocked && shortTermBlockReason == "재산초과" && carTotalLoan > 0) {
            val propertyAfterCarSale = originalNetProperty - carValue
            val debtAfterCarSale = shortTermDebt
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

        if (shortTermBlocked) {
            shortTermResult = "단기 불가 ($shortTermBlockReason)"
        } else if (income <= 100) {
            shortTermResult = "단기 불가 (소득부족)"
            shortTermBlocked = true
        } else if (shortTermMonthly <= 40) {
            // 소득 - 최저생계비 ≤ 40: 40만 기준
            shortTermMonthly = 40
            if (originalNetProperty <= 0) {
                // 재산 없음: 대상채무(세금포함) / 40 기준 기간 계산
                shortTermMonths = Math.ceil(shortTermDebt.toDouble() / 40).toInt()
                if (shortTermMonths >= 60) shortTermMonths = 60
                if (shortTermMonths < 1) shortTermMonths = 1
            } else {
                // 재산 있음: 재산 / 40 기준 기간 계산
                shortTermMonths = Math.ceil(originalNetProperty.toDouble() / 40).toInt()
                if (shortTermMonths <= 36) {
                    shortTermMonths = 36
                } else if (shortTermMonths > 60) {
                    val monthly = Math.ceil(originalNetProperty.toDouble() / 60).toInt()
                    shortTermMonthly = monthly
                    shortTermMonths = 60
                }
            }
            val roundedShortTerm = ((shortTermMonthly + 2) / 5) * 5
            shortTermResult = "${roundedShortTerm}만 / ${shortTermMonths}개월납"
            Log.d("HWP_CALC", "단기 40만 기준: 재산=${originalNetProperty}만, 월=${roundedShortTerm}만, ${shortTermMonths}개월")
        } else {
            // 소득 - 최저생계비 > 40: 일반 계산 (세금 포함)
            shortTermMonths = Math.round(shortTermDebt.toDouble() / shortTermMonthly).toInt()
            if (shortTermMonths > 60) shortTermMonths = 60
            if (shortTermMonths < 1) shortTermMonths = 1
            val roundedShortTerm = ((shortTermMonthly + 2) / 5) * 5
            shortTermResult = "${roundedShortTerm}만 / ${shortTermMonths}개월납"
        }
        // 본인명의 집 보유 → 집경매 위험 (접수 가능, 특이사항으로 표시)
        if (hasOwnRealEstate && shortTermDebt > 0) {
            specialNotesList.add("집경매 위험")
        }

        // 학자금대출: 단기는 포함, 장기는 제외 → 단기 계산 후 대상채무에서 차감
        if (studentLoanMan > 0) {
            targetDebt -= studentLoanMan
            if (targetDebt < 0) targetDebt = 0
            Log.d("HWP_CALC", "학자금 제외 (장기용): ${studentLoanMan}만 차감 → 대상채무=${targetDebt}만")
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
        val longTermCarBlocked = carTotalSise >= 1000 || carCount >= 2 || carMonthlyPayment >= 50
        if (longTermCarBlocked) Log.d("HWP_CALC", "장기 차량 불가: 시세=${carTotalSise}만, ${carCount}대, 월납=${carMonthlyPayment}만")
        // 차량에 잔여가치 없으면 (시세-담보<=0) 장기 차량 불가 무시 (특이에만 표시)
        val longTermCarBlockedEffective = longTermCarBlocked && carValue > 0

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

            // 120개월 초과 체크: 총변제금 ÷ 월변제금 > 120이면 → 총변제금÷120
            val totalMonths = if (longTermMonthly > 0) Math.ceil(totalPayment.toDouble() / longTermMonthly).toInt() else 0
            if (totalMonths > 120) {
                val prevMonthly = longTermMonthly
                longTermMonthly = Math.ceil(totalPayment.toDouble() / 120).toInt()
                Log.d("HWP_CALC", "장기 120개월 초과: ${prevMonthly}만×${totalMonths}개월 > 120 → 총변제금÷120=$longTermMonthly")
            }

        }

        var roundedLongTermMonthly = ((longTermMonthly + 2) / 5) * 5

        // 보수 년수: 총변제금 ÷ 월변제금 ÷ 12 (소수점 .35 기준 반올림, 최소 1년, 최대 10년)
        if (roundedLongTermMonthly > 0) {
            val exactYears = totalPayment.toDouble() / roundedLongTermMonthly / 12.0
            longTermYears = if (exactYears - Math.floor(exactYears) >= 0.35) Math.ceil(exactYears).toInt() else Math.floor(exactYears).toInt()
            longTermYears = longTermYears.coerceIn(1, 10)
        }
        Log.d("HWP_CALC", "장기 보수: 총변제금=$totalPayment, 월변제금=$roundedLongTermMonthly, ${longTermYears}년")

        // 프리랜서: 기간 6년 고정, 월변제금 = 총변제금/72, 40만 이하면 40만 기준으로 기간 재계산
        if (isFreelancer && targetDebt > 0 && totalPayment > 0) {
            val freelancerMonthly = Math.ceil(totalPayment.toDouble() / 72).toInt()
            val roundedFreelancer = ((freelancerMonthly + 2) / 5) * 5
            if (roundedFreelancer <= 40) {
                roundedLongTermMonthly = 40
                longTermMonthly = 40
                val exactYears = totalPayment.toDouble() / 40 / 12.0
                longTermYears = if (exactYears - Math.floor(exactYears) >= 0.35) Math.ceil(exactYears).toInt() else Math.floor(exactYears).toInt()
                longTermYears = longTermYears.coerceIn(3, 10)
                Log.d("HWP_CALC", "프리랜서 장기: 변제금 40만 고정 → ${longTermYears}년")
            } else {
                roundedLongTermMonthly = roundedFreelancer
                longTermMonthly = freelancerMonthly
                longTermYears = 6
                Log.d("HWP_CALC", "프리랜서 장기: 기간 6년 고정, 월변제금=${roundedLongTermMonthly}(총변제금÷72)")
            }
        }

        // 소득 기반 변제금으로 1년 이내 완납 가능 → 100% 변제, 최소 3년
        if (roundedLongTermMonthly > 0 && totalPayment < roundedLongTermMonthly * 12) {
            roundedLongTermMonthly = ((Math.ceil(targetDebt.toDouble() / 36).toInt() + 2) / 5) * 5
            longTermYears = 3
            Log.d("HWP_CALC", "장기 1년 미만 → 100% 변제: ${roundedLongTermMonthly}만 / 3년납")
        }

        // 장기 채무 부족: 월변제금 < 40만이면 채무가 너무 적어 장기(신복위) 불가
        val longTermDebtInsufficient = roundedLongTermMonthly in 1 until 40
        if (longTermDebtInsufficient) Log.d("HWP_CALC", "장기 채무 부족: 월변제금=${roundedLongTermMonthly}만 < 40만")

        // 방생 판단은 공격 계산 후에

        // 장기(신복위) 공격 계산: 보수 월변제금 × 2/3
        var aggressiveYears = 0
        var roundedAggressiveMonthly = 0

        if (targetDebt > 0 && roundedLongTermMonthly > 0) {
            val aggressiveMonthly = Math.ceil(roundedLongTermMonthly * 2.0 / 3.0).toInt()
            roundedAggressiveMonthly = ((aggressiveMonthly + 2) / 5) * 5
            if (roundedAggressiveMonthly < 40) roundedAggressiveMonthly = roundedLongTermMonthly

            // 공격 년수: 총변제금 ÷ 공격 월변제금 ÷ 12
            aggressiveYears = Math.ceil(totalPayment.toDouble() / roundedAggressiveMonthly / 12.0).toInt()
            if (aggressiveYears > 10) {
                // 10년 초과 시: 총변제금 ÷ 120 = 공격 월변제금
                roundedAggressiveMonthly = ((Math.ceil(totalPayment.toDouble() / 120).toInt() + 2) / 5) * 5
                aggressiveYears = 10
                Log.d("HWP_CALC", "장기 공격 10년 초과 → 총변제금÷120=${roundedAggressiveMonthly}만/10년")
            }
        }
        acost = roundedLongTermMonthly

        // 장기는 소득부족이어도 최소 40만으로 진행 가능 (소득부족은 단기에만 해당)

        // 새출발기금 - ★ 사업자 이력은 코드 파싱, 프리랜서는 사업자이력이 아님
        // 사업 기간이 2020.04~2025.06과 겹치지 않으면 새출발기금 대상 아님
        if (hasBusinessHistory && businessStartYear > 0) {
            val bizOverlap = businessStartYear <= 2025 && (businessEndYear == 0 || businessEndYear >= 2020)
            if (!bizOverlap) {
                hasBusinessHistory = false
                Log.d("HWP_CALC", "사업자이력 제외: 개업=${businessStartYear}년${if (businessStartMonth > 0) "${businessStartMonth}월" else ""}, 폐업=${businessEndYear}년 (2020.04~2025.06 기간 밖)")
            }
            // 2025년 7월 이후 개업 + 이전 폐업이력 없음 → 가능기간 내 사업이력 없음
            if (hasBusinessHistory && businessStartYear == 2025 && businessStartMonth > 6 && businessEndYear == 0) {
                hasBusinessHistory = false
                Log.d("HWP_CALC", "사업자이력 제외: 개업=${businessStartYear}년${businessStartMonth}월 (2025.06 이후 개업, 이전 사업이력 없음)")
            }
        }
        val saeDebtOverLimit = totalSecuredDebt > 100000 || totalUnsecuredDebt > 50000  // 새새: 담보10억/무담보5억
        val canApplySaeBase = hasBusinessHistory && !(isFreelancer && !isBusinessOwner) && !isNonProfit && !isCorporateBusiness && !saeDebtOverLimit
        val canApplySae = canApplySaeBase && actualDelinquentDays < 90
        Log.d("HWP_CALC", "새새 조건: 사업이력=$hasBusinessHistory, 실제연체=${actualDelinquentDays}일(전체=${delinquentDays}일), 프리랜서=$isFreelancer, 사업자=$isBusinessOwner, 비영리=$isNonProfit, 법인=$isCorporateBusiness, 개업=${businessStartYear}년${if (businessStartMonth > 0) "${businessStartMonth}월" else ""}, 폐업=$businessEndYear, 채무한도초과=$saeDebtOverLimit(담보=${totalSecuredDebt}만,무담보=${totalUnsecuredDebt}만)")
        var saeTotalPayment = 0; var saeMonthly = 0; var saeYears = 0
        if (canApplySaeBase && targetDebt > 0) {
            saeTotalPayment = targetDebt - ((targetDebt - netProperty) * 0.35).toInt()
            if (saeTotalPayment >= targetDebt) saeTotalPayment = targetDebt
            saeTotalPayment = ((saeTotalPayment + 2) / 5) * 5
            // 소득/대상채무 비율로 기간 결정
            val incomeRatio = income.toDouble() / targetDebt * 100
            saeYears = when {
                incomeRatio > 6 -> 5
                incomeRatio > 3 -> 8
                else -> 10
            }
            saeMonthly = ((Math.ceil(saeTotalPayment.toDouble() / (saeYears * 12)).toInt() + 2) / 5) * 5
            if (saeMonthly <= 40) {
                saeMonthly = 40
                val exactYears = saeTotalPayment.toDouble() / 40 / 12.0
                saeYears = if (exactYears - Math.floor(exactYears) >= 0.35) Math.ceil(exactYears).toInt() else Math.floor(exactYears).toInt()
                saeYears = saeYears.coerceIn(1, 10)
                saeTotalPayment = saeMonthly * saeYears * 12
                Log.d("HWP_CALC", "새새: 총변제=${saeTotalPayment}만, 월변제금 40만 이하 → 40만 고정, ${saeYears}년")
            } else {
                saeTotalPayment = saeMonthly * saeYears * 12
                Log.d("HWP_CALC", "새새: 총변제=${saeTotalPayment}만, 소득비율=${String.format("%.1f", incomeRatio)}%, ${saeMonthly}만/${saeYears}년")
            }
        }

        // 특이사항
        val familyInfo = StringBuilder()
        familyInfo.append(if (isDivorced) "이혼" else if (hasSpouse || minorChildren > 0 || collegeChildren > 0) "기혼" else "미혼")
        if (minorChildren > 0) familyInfo.append(" 미성년$minorChildren")
        if (collegeChildren > 0) familyInfo.append(" 대학생$collegeChildren")
        if (parentCount > 0) familyInfo.append(" 60세부모$parentCount")
        specialNotesList.add(0, familyInfo.toString())
        if (isFreelancer) specialNotesList.add("프리랜서")

        // 과반 채권사 (미협약 채권은 과반 표시 제외)
        val isMajorCreditorNonAffiliated = nonAffiliatedNames.any { majorCreditorName.contains(it) || it.contains(majorCreditorName) }
        if (originalTargetDebt > 0 && majorCreditorName.isNotEmpty() && majorCreditorRatio > 50 && !isMajorCreditorNonAffiliated) {
            specialNotesList.add("$majorCreditorName 과반 (${String.format("%.0f", majorCreditorRatio)}%)")
            Log.d("HWP_CALC", "과반 채권사: $majorCreditorName ${majorCreditorDebtVal}만 / ${originalTargetDebt}만 = ${String.format("%.1f", majorCreditorRatio)}%")
        }
        specialNotesList.add("6개월 이내 ${String.format("%.0f", recentDebtRatio)}%")
        if (needsCarDisposal) {
            val reasonStr = carDisposalReasons.joinToString(", ")
            specialNotesList.add("차량 처분 필요 ($reasonStr)")
        } else if (longTermCarBlocked && !longTermCarBlockedEffective) {
            // 시세/담보 정보 없이 월납만으로 장기 차량 조건 해당 → 특이에 표시
            val reasons = buildList {
                if (carTotalSise >= 1000) add("시세${carTotalSise}만")
                if (carCount >= 2) add("${carCount}대")
                if (carMonthlyPayment >= 50) add("월 ${carMonthlyPayment}만")
            }.joinToString(", ")
            specialNotesList.add("차량 처분 필요 ($reasons)")
        }
        if (carCount >= 2) specialNotesList.add("차량 ${carCount}대 보유")
        // 사업자이력 년도 정보 추가 (새출발 가능기간 내 사업이력만 표시, 법인사업은 제외)
        val hasBizHistory = hasBusinessHistory && !isCorporateBusiness
        if (hasBizHistory && !specialNotesList.any { it.contains("사업자") || it.contains("자영업") || it.contains("폐업") }) {
            val bizNote = if (businessStartYear > 0) {
                if (businessEndYear > 0 && businessEndYear < java.time.LocalDate.now().year) {
                    "사업자이력 (${businessStartYear}년~${businessEndYear}년 폐업)"
                } else {
                    "사업자이력 (${businessStartYear}년~)"
                }
            } else "사업자이력"
            specialNotesList.add(bizNote)
        } else if (hasBizHistory) {
            // 이미 "사업자이력"이 들어있으면 년도 정보로 교체
            val idx = specialNotesList.indexOfFirst { it.contains("사업자") || it.contains("자영업") || it.contains("폐업") }
            if (idx >= 0 && businessStartYear > 0) {
                val bizNote = if (businessEndYear > 0 && businessEndYear < java.time.LocalDate.now().year) {
                    "사업자이력 (${businessStartYear}년~${businessEndYear}년 폐업)"
                } else {
                    "사업자이력 (${businessStartYear}년~)"
                }
                specialNotesList[idx] = bizNote
            }
        } else {
            // 새새 가능기간 밖 → 기존에 추가된 "사업자이력" 제거
            specialNotesList.removeAll { it.contains("사업자이력") }
        }
        if (hasGambling) specialNotesList.add("도박")
        if (hasStock) specialNotesList.add("주식")
        if (hasCrypto) specialNotesList.add("코인")
        if (childSupportAmount > 0) specialNotesList.add("월 양육비 ${childSupportAmount}만")
        if (spouseSecret) specialNotesList.add("배우자 모르게")
        if (familySecret) specialNotesList.add("가족 모르게")
        if (hasAuction) specialNotesList.add("경매진행중")
        if (hasSeizure) specialNotesList.add("압류진행중")
        if (delinquentDays >= 90) specialNotesList.add("장기연체자")
        if (hasCivilCase) specialNotesList.add("민사 소송금 따로 변제")
        if (hasUsedCarInstallment) specialNotesList.add("중고차 할부 따로 납부")
        if (hasHealthInsuranceDebt) specialNotesList.add("건강보험 체납 따로 변제")
        if (hasInsurancePolicyLoan) specialNotesList.add("보험약관대출 제외")
        if (hasHfcMortgage) specialNotesList.add("한국주택금융공사 집담보 보유")
        if (savingsDeposit > 0) specialNotesList.add("예적금 ${formatToEok(savingsDeposit)}")
        if (hasOngoingProcess) {
            if (ongoingProcessName.isNotEmpty()) {
                specialNotesList.add("다른 단계 진행중 ($ongoingProcessName)")
            } else {
                specialNotesList.add("다른 단계 진행중")
            }
        }
        if (hasPaymentOrder) specialNotesList.add("지급명령 받음")
        if (postApplicationDebtMan > 0 && ongoingProcessName.isNotEmpty()) {
            specialNotesList.add("($ongoingProcessName) 중 추가채무 ${postApplicationDebtMan}만")
        }
        if (dischargeWithin5Years) {
            specialNotesList.add("면책 5년 이내 (${dischargeYear}년)")
            val availableYear = dischargeYear + 5
            if (currentYear < availableYear) specialNotesList.add("면책 ${dischargeYear}년 → ${availableYear}년 이후 회생")
        }
        // 변제율은 특이사항에 표시하지 않음

        // 진단 코드
        var diagnosis = ""
        var diagnosisNote = ""
        val delinquentPrefix = when { delinquentDays < 30 -> "신"; delinquentDays < 90 -> "프"; else -> "회" }

        // 유예 가능 여부: 상환내역서 유예기간 12개월 미만이면 유예 가능
        val canDeferment = aiDefermentMonths < 12
        if (aiDefermentMonths > 0) {
            Log.d("HWP_CALC", "유예기간: ${aiDefermentMonths}개월 → 유예 ${if (canDeferment) "가능" else "불가"}")
            if (aiDefermentMonths >= 12) specialNotesList.add("유예기간 ${aiDefermentMonths}개월 (유예불가)")
            else specialNotesList.add("유예기간 ${aiDefermentMonths}개월")
        }

        val hasYuwoCond = canDeferment && (recentDebtRatio >= 30 || dischargeWithin5Years)

        // 새 진단 가능 여부 (새새 또는 회생폐지/실효/취하 사업자)
        val canGetSae = canApplySae || (isBusinessOwner && (hasOngoingProcess && ongoingProcessName == "회" || isDismissed || hasWorkoutExpired))
        // 경매가 자신 재산일 때만 장기 불가
        val isOwnPropertyAuction = hasAuction && hasOwnRealEstate

        // 차량 처분시 장기 가능 여부 (재산초과 해소 + 차량조건 해소, 채무한도초과면 불가)
        var canLongTermAfterCarSale = false
        if ((longTermPropertyExcess || longTermCarBlockedEffective) && carValue > 0 && !longTermDebtOverLimit) {
            val propertyAfterCarSale = netProperty - carValue
            var netAfterExemption = propertyAfterCarSale - exemptionAmount
            if (netAfterExemption < 0) netAfterExemption = 0
            val propertyOk = netAfterExemption <= targetDebt
            canLongTermAfterCarSale = propertyOk
            Log.d("HWP_CALC", "차량 처분시: 재산=$propertyAfterCarSale, 공제후=$netAfterExemption, 대상채무=$targetDebt, 재산OK=$propertyOk → $canLongTermAfterCarSale")
        }

        var isBangsaeng = false
        var bangsaengReason = ""
        if (netProperty > targetDebt && targetDebt > 0 && shortTermBlocked && longTermPropertyExcess && !canGetSae) {
            isBangsaeng = true; bangsaengReason = "재산초과"
        }
        // 경매 진행중 → 장기 불가. 단기도 불가면 방생 (압류는 회생으로 해제 가능하므로 별도 처리)
        if (!isBangsaeng && hasAuction && shortTermBlocked && targetDebt > 0 && !canGetSae) {
            isBangsaeng = true; bangsaengReason = "경매 진행중"
        }
        // 차량 조건 불가 (시세1000+/2대+/월납50+) + 처분해도 재산초과 → 장기 불가. 단기도 불가면 방생 (채무한도초과면 차량 무관)
        if (!isBangsaeng && longTermCarBlockedEffective && shortTermBlocked && targetDebt > 0 && !canGetSae && !canLongTermAfterCarSale && !longTermDebtOverLimit) {
            val carReason = buildList {
                if (carTotalSise >= 1000) add("시세${carTotalSise}만")
                if (carCount >= 2) add("${carCount}대")
                if (carMonthlyPayment >= 50) add("월납${carMonthlyPayment}만")
            }.joinToString("/")
            isBangsaeng = true; bangsaengReason = "차량($carReason)"
        }
        // 단기+장기 모두 채무한도초과 → 방생
        if (!isBangsaeng && shortTermDebtOverLimit && longTermDebtOverLimit && targetDebt > 0) {
            isBangsaeng = true; bangsaengReason = "채무한도초과"
        }
        // 소득부족은 단기에만 해당 → 장기는 최소 40만으로 항상 진행 가능하므로 소득부족 방생 없음

        var shortTermTotal = if (!shortTermBlocked && shortTermMonthly > 0) ((shortTermMonthly + 2) / 5) * 5 * shortTermMonths else 0
        // 차량 처분 시 단기 총액
        if (shortTermCarSaleApplied) {
            val debtAfterCar = targetDebt
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
        // 청산가치(공시지가) 보장은 단기에 적용하지 않음
        val longTermTotal = roundedLongTermMonthly * longTermYears * 12
        val aggressiveTotal = roundedAggressiveMonthly * aggressiveYears * 12

        // 차량 처분 시 장기 총액 계산
        var carSaleLongTermTotal = longTermTotal
        var carSaleAggressiveTotal = aggressiveTotal
        var carSaleFinalYear = 0
        var carSaleFinalMonthly = 0
        if ((shortTermCarSaleApplied || longTermCarBlockedEffective) && carTotalLoan > 0) {
            val csDebt = targetDebt
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
            val csRounded = ((csMonthly + 2) / 5) * 5
            val csYears = if (csRounded > 0) Math.floor(csTotalPayment.toDouble() / csRounded / 12.0).toInt().coerceIn(1, 10) else 10
            carSaleLongTermTotal = csRounded * csYears * 12
            // 차량 처분 시 공격 총액
            val csAggressiveMonthly = Math.ceil(csRounded * 2.0 / 3.0).toInt()
            var csAggressiveRounded = ((csAggressiveMonthly + 2) / 5) * 5
            if (csAggressiveRounded < 40) csAggressiveRounded = csRounded
            var csAggressiveYears = Math.ceil(csTotalPayment.toDouble() / csAggressiveRounded / 12.0).toInt()
            if (csAggressiveYears > 10) {
                csAggressiveRounded = ((Math.ceil(csTotalPayment.toDouble() / 120).toInt() + 2) / 5) * 5
                csAggressiveYears = 10
            }
            carSaleAggressiveTotal = csAggressiveRounded * csAggressiveYears * 12
            // 차량 처분 시 최종 년수/변제금 (percentage 적용)
            val csEffectiveMax = Math.min(csAggressiveYears, csYears + 4).coerceAtLeast(csYears)
            carSaleFinalYear = csYears  // 기본값 (나중에 percentage 적용 시 재계산)
            val csRawMonthly = if (csYears > 0) Math.ceil(csTotalPayment.toDouble() / (csYears * 12)).toInt() else csRounded
            carSaleFinalMonthly = ((csRawMonthly + 4) / 5) * 5
            if (carSaleFinalMonthly < 40) carSaleFinalMonthly = 40
            // 우선 조건: 월 변제금이 총 채무 3%보다 높으면 장기 공격
            if (carSaleFinalMonthly > targetDebt * 0.03) {
                carSaleFinalYear = csAggressiveYears
                carSaleFinalMonthly = csAggressiveRounded
            }
            Log.d("HWP_CALC", "차량처분 장기총액: 보수=${csRounded}만×${csYears}년=$carSaleLongTermTotal, 공격=${csAggressiveRounded}만×${csAggressiveYears}년=$carSaleAggressiveTotal, 최종=${carSaleFinalMonthly}만×${carSaleFinalYear}년")
        }

        // 진단에 사용할 장기 총액 (차량 처분 시에는 처분 기준)
        val effectiveLongTermTotal = if (shortTermCarSaleApplied || longTermCarBlockedEffective) carSaleLongTermTotal else longTermTotal
        val effectiveAggressiveTotal = if (shortTermCarSaleApplied || longTermCarBlockedEffective) carSaleAggressiveTotal else aggressiveTotal

        val propertyDamboDebt = parsedDamboTotal

        // ============= 장기 최종 년수/변제금 계산 =============
        var finalYear = 0
        var finalMonthly = 0
        var longTermAggressive = false
        val longTermBlockedByAuction = hasAuction  // 경매 진행중 → 장기 불가
        val longTermBlockedBySeizure = hasSeizure  // 압류 진행중 → 장기 불가
        val longTermFullyBlocked = ((longTermPropertyExcess || longTermCarBlockedEffective) && !canLongTermAfterCarSale) || longTermDebtOverLimit || longTermBlockedByAuction || longTermBlockedBySeizure || longTermDebtInsufficient

        if (targetDebt > 0 && longTermYears > 0 && totalPayment > 0 && !longTermFullyBlocked) {
            // 보수 기간이 3년 이하이면 3년으로 설정
            if (longTermYears < 3) longTermYears = 3
            val effectiveMax = Math.min(aggressiveYears, longTermYears + 4).coerceAtLeast(longTermYears)

            // 12개월 후에도 면책 5년 미해소 판단
            var dischargeNotClearedIn12Months = false
            if (dischargeWithin5Years && dischargeYear > 0) {
                val twelveMonthsLater = Calendar.getInstance().apply { add(Calendar.MONTH, 12) }
                val dischargeEndCal = Calendar.getInstance().apply {
                    set(dischargeYear + 5, if (dischargeMonth > 0) dischargeMonth - 1 else 0, 1)
                }
                if (dischargeEndCal.after(twelveMonthsLater)) {
                    dischargeNotClearedIn12Months = true
                }
            }

            // 퍼센티지 결정 (각 조건 독립 체크, 가장 높은 퍼센티지 적용)
            var percentage = 50 // 기본값

            // 100% 조건 (보수)
            if (isOwnPropertyAuction || (hasSeizure && hasOwnRealEstate) || hasGambling || recentDebtRatio >= 50 ||
                dischargeNotClearedIn12Months ||
                majorCreditorRatio > 50) {
                percentage = 100
            }

            // 75% 조건
            if (percentage < 75) {
                val surplus = income - livingCostShinbok
                if (hasStock || hasCrypto ||
                    (surplus > 0 && surplus < targetDebt * 0.03) ||
                    (recentDebtRatio >= 30 && recentDebtRatio < 50)) {
                    percentage = 75
                }
            }

            // 25% 조건 (50% 기본값보다 낮으므로, 다른 높은 조건이 없을 때만 적용)
            if (percentage == 50) {
                val surplus25 = income - livingCostShinbok
                if ((surplus25 > targetDebt * 0.03) || isFreelancer ||
                    delinquentDays >= 90 ||
                    (shortTermTotal > 0 && shortTermTotal < longTermTotal - 1000)) {
                    percentage = 25
                }
            }

            // 0% 조건 (공격) - 가장 낮은 우선순위, 다른 조건이 아무것도 없을 때만
            if (percentage == 50 && income <= livingCostShinbok) {
                percentage = 0
            }

            // 최종 년수 계산: 범위 차이 1년 이하면 보수년수로 고정
            finalYear = if (effectiveMax - longTermYears <= 1) {
                longTermYears
            } else {
                longTermYears + Math.round((effectiveMax - longTermYears) * (1.0 - percentage / 100.0)).toInt()
            }
            finalYear = finalYear.coerceIn(longTermYears, effectiveMax)

            // 최종 월변제금 계산: 5만 단위 올림, 최소 40만
            val rawMonthly = Math.ceil(totalPayment.toDouble() / (finalYear * 12)).toInt()
            finalMonthly = ((rawMonthly + 4) / 5) * 5  // 5만 단위 올림
            if (finalMonthly < 40) finalMonthly = 40

            // 우선 조건: 장기 월 변제금이 총 채무 3%보다 높으면 장기 공격
            if (finalMonthly > targetDebt * 0.03) {
                finalYear = aggressiveYears
                finalMonthly = roundedAggressiveMonthly
                longTermAggressive = true
            }

            Log.d("HWP_CALC", "최종 년수 계산: 보수=${longTermYears}년, 공격=${aggressiveYears}년, effectiveMax=$effectiveMax, percentage=$percentage%, finalYear=$finalYear, finalMonthly=${finalMonthly}만, 공격전환=$longTermAggressive")
        }

        // 차량 처분시 단기 가능이면 단기 가능으로 판단
        val effectiveShortTermBlocked = shortTermBlocked && !shortTermCarSaleApplied
        val hoeBlocked = dischargeWithin5Years || income <= 100  // 회(개인회생) 맨앞 불가: 면책5년이내, 소득100만이하
        val smallDebtNoHoe = targetDebt <= 4000  // 소액채무: 복합진단에서 회 제외 (회워는 유지)

        // 장기 최종 총액 (단기 vs 장기 비교용)
        val longTermFinalTotal = if (finalYear > 0 && finalMonthly > 0) finalMonthly * finalYear * 12 else longTermTotal
        // 단기가 장기보다 저렴한지 여부 (1000만 이상 차이)
        val shortTermCheaperThanLong = shortTermTotal > 0 && longTermFinalTotal > 0 && shortTermTotal + 1000 <= longTermFinalTotal

        val diagnosisAfterCarSale = when {
            !canLongTermAfterCarSale -> ""
            delinquentDays >= 90 -> if (hoeBlocked) (if (canDeferment) "워유워" else "워") else "회워"
            delinquentDays >= 30 -> if (hoeBlocked) (if (canDeferment) "프유워" else "워") else if (canDeferment && smallDebtNoHoe) "프유워" else "프회워"
            else -> if (hoeBlocked) (if (canDeferment) "신유워" else "워") else if (canDeferment && smallDebtNoHoe) "신유워" else "신회워"
        }



        val creditorCount = parsedCreditorCount
        Log.d("HWP_CALC", "채권사 수: $creditorCount (코드파싱)")
        val isShinbokSingleCreditor = creditorCount == 1 && (majorCreditorName.contains("신복") || majorCreditorName.contains("신복위"))
        if (creditorCount == 1 && !isShinbokSingleCreditor && !hasPdfFile) specialNotesList.add("채권사 1건")

        if (studentLoanRatio >= 50 && !longTermDebtOverLimit) {
            diagnosis = "단순 진행"
        } else if (shortTermDebt in 1..1500) {
            diagnosis = "방생"; diagnosisNote = "(소액)"
        } else if (creditorCount == 1 && !effectiveShortTermBlocked && !isShinbokSingleCreditor && !hasPdfFile && !longTermDebtOverLimit) {
            diagnosis = "단순유리"; diagnosisNote = "(채권사 1건, 개인회생 안내)"
        } else if (nonAffiliatedOver20 && !longTermDebtOverLimit) {
            diagnosis = if (!effectiveShortTermBlocked && repaymentRate < 100) "단순유리" else "방생"
            // [장기] 라인에서 이미 "미협약 초과" 표시하므로 diagnosisNote 생략
        } else if (nonAffiliatedOver20 && (effectiveShortTermBlocked || repaymentRate == 100)) {
            diagnosis = "방생"
        } else if (hasAuction && hasSeizure) {
            // 경매+압류 둘 다 → 단순유리 or 방생
            diagnosis = if (!effectiveShortTermBlocked) "단순유리" else "방생"
            if (effectiveShortTermBlocked) diagnosisNote = "(경매/압류, 회생불가)"
        } else if (hasAuction) {
            // 경매만 → 단순유리 or 방생
            diagnosis = if (!effectiveShortTermBlocked) "단순유리" else "방생"
            if (effectiveShortTermBlocked) diagnosisNote = "(경매, 회생불가)"
        } else if (hasSeizure) {
            // 압류 → 장기 불가. 단기(회생)로 압류 해제 후 장기 가능 → 회워, 단기도 불가면 방생
            diagnosis = if (!effectiveShortTermBlocked) "회워" else "방생"
        } else if (isBangsaeng) {
            if (canLongTermAfterCarSale && diagnosisAfterCarSale.isNotEmpty()) {
                diagnosis = diagnosisAfterCarSale
                diagnosisNote = "(차량 처분시 가능)"
            } else if (hasOthersRealEstate && parsedOthersProperty > 0) {
                // 등본 분리시 타인명의 재산 제외 → 장기 가능
                diagnosis = when {
                    delinquentDays >= 90 -> if (hoeBlocked) (if (canDeferment) "워유워" else "워") else "회워"
                    delinquentDays >= 30 -> if (hoeBlocked) (if (canDeferment) "프유워" else "워") else if (canDeferment && smallDebtNoHoe) "프유워" else "프회워"
                    else -> if (hoeBlocked) (if (canDeferment) "신유워" else "워") else if (canDeferment && smallDebtNoHoe) "신유워" else "신회워"
                }
                // [재산] 라인에서 이미 "(등본 분리 시)" 표시
            } else {
                diagnosis = "방생"
                if (bangsaengReason.isNotEmpty()) diagnosisNote = "($bangsaengReason)"
            }
        } else if (canApplySae && isBusinessOwner && (hasOngoingProcess && ongoingProcessName == "회" || isDismissed || hasWorkoutExpired)) {
            diagnosis = if (hoeBlocked) "새" else "회새"
        } else if (canApplySae && saeTotalPayment > 0 && netProperty <= 0) {
            // 새새 가능 + 재산 없으면 무조건 새새
            diagnosis = "새새"
        } else if (canApplySae && saeTotalPayment > 0) {
            val longTermFinalTotal = finalMonthly * finalYear * 12
            if (!effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && saeTotalPayment - shortTermTotal > 1000 && (targetDebt - shortTermTotal >= 1000 || shortTermCheaperThanLong)) {
                diagnosis = "단순유리"
            } else if (!effectiveShortTermBlocked && shortTermTotal > 0 && saeTotalPayment - shortTermTotal <= 1000) {
                // 새새 - 단기 <= 1000만이면 새새가 유리
                diagnosis = "새새"
            } else if (longTermFullyBlocked) {
                diagnosis = "새새"
            } else if (saeTotalPayment <= longTermFinalTotal) {
                // 새새 <= 장기이면 새새가 유리
                diagnosis = "새새"
            } else if (recentDebtRatio >= 30) {
                diagnosis = "새새"
            } else {
                diagnosis = if (!hoeBlocked && !hasYuwoCond) {
                    when {
                        delinquentDays >= 90 -> "회워"
                        delinquentDays >= 30 -> if (smallDebtNoHoe) (if (canDeferment) "프유워" else "워") else "프회워"
                        else -> if (smallDebtNoHoe) (if (canDeferment) "신유워" else "워") else "신회워"
                    }
                } else {
                    when {
                        delinquentDays >= 90 -> if (canDeferment) "워유워" else "워"
                        delinquentDays >= 30 -> if (canDeferment) "프유워" else "워"
                        else -> if (canDeferment) "신유워" else "워"
                    }
                }
            }
        } else if (hasShinbokwiHistory) {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked -> "회워"
                else -> when {
                    delinquentDays >= 90 -> if (hoeBlocked) (if (canDeferment) "워유워" else "워") else "회워"
                    delinquentDays >= 30 -> if (hoeBlocked) (if (canDeferment) "프유워" else "워") else if (canDeferment && smallDebtNoHoe) "프유워" else "프회워"
                    else -> if (hoeBlocked) (if (canDeferment) "신유워" else "워") else if (canDeferment && smallDebtNoHoe) "신유워" else "신회워"
                }
            }
        } else if (hasOngoingProcess) {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && (targetDebt - shortTermTotal >= 1000 || shortTermCheaperThanLong) -> "단순유리"
                !effectiveShortTermBlocked -> "회워"
                else -> when {
                    delinquentDays >= 90 -> if (hoeBlocked) (if (canDeferment) "워유워" else "워") else "회워"
                    delinquentDays >= 30 -> if (hoeBlocked) (if (canDeferment) "프유워" else "워") else if (canDeferment && smallDebtNoHoe) "프유워" else "프회워"
                    else -> if (hoeBlocked) (if (canDeferment) "신유워" else "워") else if (canDeferment && smallDebtNoHoe) "신유워" else "신회워"
                }
            }
        } else if (!canDeferment) {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && (targetDebt - shortTermTotal >= 1000 || shortTermCheaperThanLong) -> "단순유리"
                !effectiveShortTermBlocked -> "회워"
                else -> when {
                    delinquentDays >= 90 -> if (hoeBlocked) "워" else "회워"
                    delinquentDays >= 30 -> if (hoeBlocked) "워" else "프회워"
                    else -> if (hoeBlocked) "워" else "신회워"
                }
            }
        } else if (delinquentDays >= 90) {
            diagnosis = when {
                isBangsaeng -> "방생"
                hasWorkoutExpired && !longTermDebtOverLimit -> "단순워크"
                !effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && (targetDebt - shortTermTotal >= 1000 || shortTermCheaperThanLong) -> "단순유리"
                !effectiveShortTermBlocked && !longTermPropertyExcess -> "회워"
                hoeBlocked -> "워유워"
                else -> "회워"
            }
        } else if (delinquentDays >= 30) {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && (targetDebt - shortTermTotal >= 1000 || shortTermCheaperThanLong) -> "단순유리"
                recentDebtRatio >= 30 && !effectiveShortTermBlocked && !smallDebtNoHoe -> "프회워"
                (targetDebt <= 4000 || smallDebtNoHoe) && !effectiveShortTermBlocked -> "프유워"
                !effectiveShortTermBlocked && !smallDebtNoHoe -> "프회워"
                !effectiveShortTermBlocked -> "프유워"
                else -> if (hoeBlocked) "프유워" else "프회워"
            }
        } else {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && (targetDebt - shortTermTotal >= 1000 || shortTermCheaperThanLong) -> "단순유리"
                recentDebtRatio >= 30 && !effectiveShortTermBlocked && !smallDebtNoHoe -> "신회워"
                (targetDebt <= 4000 || smallDebtNoHoe) && !effectiveShortTermBlocked -> "신유워"
                !effectiveShortTermBlocked && !smallDebtNoHoe -> "신회워"
                !effectiveShortTermBlocked -> "신유워"
                else -> if (hoeBlocked) "신유워" else "신회워"
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
                // 10개월 이내 해소 → 회 계열로 변경 (신회워, 프회워, 회워)
                val afterDate = "${dischargeYear + 5}.${String.format("%02d", if (dischargeMonth > 0) dischargeMonth else 1)}"
                specialNotesList.add("면책 5년 해소 ${afterDate} (10개월 이내)")
                if (!hasOngoingProcess && !shortTermDebtOverLimit &&
                    (diagnosis == "신유워" || diagnosis == "프유워" || diagnosis == "워유워" ||
                     diagnosis == "워")) {
                    diagnosis = when {
                        delinquentDays >= 90 -> "회워"
                        delinquentDays >= 30 -> "프회워"
                        else -> "신회워"
                    }
                }
            } else {
                // 10개월 이내 해소 안됨 → 유 계열 유지 (유예 가능할 때만)
                val yuDiags = listOf("신유워", "프유워", "워유워", "워",
                    "신회워", "프회워", "회워")
                if (yuDiags.contains(diagnosis)) {
                    diagnosis = when {
                        delinquentDays >= 90 -> if (canDeferment) "워유워" else "워"
                        delinquentDays >= 30 -> if (canDeferment) "프유워" else "워"
                        else -> if (canDeferment) "신유워" else "워"
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


        // 6개월 이내 비율 30% 미만 가능 날짜 계산 (방생/단순유리/단순진행 등 확정 진단 시 건너뜀)
        val skipRecentDateCalc = diagnosis == "방생" || (diagnosis.startsWith("단순") && recentDebtRatio < 30) || diagnosis == "새새" || diagnosis == "새"
        // 6개월 30% 이상 + 단기 가능 → 회워 바로 가능 (단기로 갈 수 있으니 날짜 불필요)
        if (recentDebtRatio >= 30 && !effectiveShortTermBlocked && !skipRecentDateCalc && !diagnosis.startsWith("단순")) {
            diagnosis = "회워"
            diagnosisNote = ""
            Log.d("HWP_CALC", "6개월 30%이상 + 단기 가능 → 회워 바로 가능")
        } else if (recentDebtRatio >= 30 && recentDebtEntries.isNotEmpty() && targetDebt > 0 && !skipRecentDateCalc && !(effectiveShortTermBlocked && longTermFullyBlocked)) {
            recentDebtEntries.sortBy { it.first.timeInMillis } // 오래된 순
            var remainingMan = recentDebtMan

            for ((date, amountChon) in recentDebtEntries) {
                val amountMan = (amountChon + 5) / 10
                remainingMan -= amountMan
                if (remainingMan < 0) remainingMan = 0
                val newRatio = remainingMan.toDouble() / targetDebt * 100
                if (newRatio < 30) {
                    val thresholdCal = date.clone() as Calendar
                    thresholdCal.add(Calendar.MONTH, 6)
                    val possibleDate = "${thresholdCal.get(Calendar.YEAR)}.${String.format("%02d", thresholdCal.get(Calendar.MONTH) + 1)}.${String.format("%02d", thresholdCal.get(Calendar.DAY_OF_MONTH))}"
                    var afterDiag = if (hoeBlocked || effectiveShortTermBlocked) {
                        when {
                            delinquentDays >= 90 -> if (canDeferment) "워유워" else "워"
                            delinquentDays >= 30 -> if (canDeferment) "프유워" else "워"
                            else -> if (canDeferment) "신유워" else "워"
                        }
                    } else when {
                        delinquentDays >= 90 -> "회워"
                        delinquentDays >= 30 -> if (canDeferment && smallDebtNoHoe) "프유워" else "프회워"
                        else -> if (canDeferment && smallDebtNoHoe) "신유워" else "신회워"
                    }
                    // afterDiag 마무리 비교 (회/워/새)
                    if (canApplySae && saeTotalPayment > 0 && !longTermFullyBlocked && finalYear > 0 && afterDiag.isNotEmpty()) {
                        val ltForAfter = finalMonthly * finalYear * 12
                        val optEnd = if (!hoeBlocked && !effectiveShortTermBlocked && shortTermTotal > 0) {
                            val bestNonShort = minOf(ltForAfter, saeTotalPayment)
                            if (shortTermTotal + 1000 <= bestNonShort) "회"
                            else if (ltForAfter < saeTotalPayment) "워"
                            else "새"
                        } else {
                            if (saeTotalPayment <= ltForAfter) "새" else "워"
                        }
                        val lastChar = afterDiag.last().toString()
                        if (lastChar in listOf("회", "워", "새") && lastChar != optEnd) {
                            if (optEnd == "회" && afterDiag.contains("회")) {
                                // "회회" 중복 방지
                                val firstChar = afterDiag.first().toString()
                                if (firstChar != "회") {
                                    val yuStr = if (canDeferment) "유" else ""
                                    afterDiag = "$firstChar${yuStr}회"
                                }
                                // firstChar == "회" → skip (leave as-is)
                            } else {
                                afterDiag = afterDiag.dropLast(1) + optEnd
                            }
                        }
                    }
                    // 새출발 불가 + 단기+장기 가능 → afterDiag 마무리 비교
                    if (!canApplySae && !longTermFullyBlocked && finalYear > 0 && !hoeBlocked && !effectiveShortTermBlocked && shortTermTotal > 0 && afterDiag.isNotEmpty()) {
                        val ltForAfter = finalMonthly * finalYear * 12
                        if (shortTermTotal + 1000 <= ltForAfter) {
                            val lastChar = afterDiag.last().toString()
                            if (lastChar != "회" && afterDiag.contains("회")) {
                                val firstChar = afterDiag.first().toString()
                                if (firstChar != "회") {
                                    val yuStr = if (canDeferment) "유" else ""
                                    afterDiag = "$firstChar${yuStr}회"
                                }
                            }
                        }
                    }
                    val immediatePrefix = if (effectiveShortTermBlocked && !longTermFullyBlocked && finalYear > 0 && !hoeBlocked) {
                        "회워 바로 가능, "
                    } else if (!effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && (targetDebt - shortTermTotal >= 1000 || shortTermCheaperThanLong) && !diagnosis.startsWith("단순")) {
                        "단순 바로 가능, "
                    } else if (!effectiveShortTermBlocked) {
                        "회워 바로 가능, "
                    } else {
                        ""
                    }
                    diagnosis = "$immediatePrefix$afterDiag $possibleDate 이후 가능"
                    diagnosisNote = ""
                    Log.d("HWP_CALC", "6개월 30%미만 가능일: $possibleDate (남은 ${remainingMan}만/${targetDebt}만=${String.format("%.1f", newRatio)}%)")
                    break
                }
            }
        }

        // 단순유리 + 장기 가능 → 연체에 따라 신유회/프유회/워유회
        // 4000만 이상이면 (X유)회워 compound 표시
        if (diagnosis.startsWith("단순") && !longTermFullyBlocked && !nonAffiliatedOver20 && finalYear > 0 && !diagnosis.contains("이후 가능")) {
            val delinqPrefix = when {
                delinquentDays >= 90 -> "워"
                delinquentDays >= 30 -> "프"
                else -> "신"
            }
            if (targetDebt > 4000) {
                diagnosis = "(${delinqPrefix}유)회워"
            } else {
                val suffix = if (dischargeWithin5Years) "워" else "회"
                diagnosis = "${delinqPrefix}유$suffix"
            }
        }

        // 단기+장기+새새 모두 가능할 때 마무리(마지막 글자) 비용 비교 결정
        val allThreeAvailable = !effectiveShortTermBlocked && shortTermTotal > 0
            && !longTermFullyBlocked && finalYear > 0
            && canApplySae && saeTotalPayment > 0
        if (allThreeAvailable) {
            val ltTotal = finalMonthly * finalYear * 12
            val bestNonShort = minOf(ltTotal, saeTotalPayment)
            val optimalEnding = if (shortTermTotal + 1000 <= bestNonShort) "회"
                else if (ltTotal < saeTotalPayment) "워"
                else "새"
            val skipEnding = diagnosis == "방생" || diagnosis.startsWith("단순") || diagnosis.contains("이후 가능")
            if (!skipEnding && diagnosis.isNotEmpty()) {
                val lastChar = diagnosis.last().toString()
                if (lastChar in listOf("회", "워", "새") && lastChar != optimalEnding) {
                    val parenEnd = diagnosis.indexOf(")")
                    val isCompound = parenEnd >= 0 && diagnosis.startsWith("(")
                    if (optimalEnding == "회" && diagnosis.contains("회")) {
                        // 단기 최적 → "회회" 중복 방지: 단순유리 형태로 변환
                        if (isCompound) {
                            // compound: (X유)회워 → X유회
                            diagnosis = diagnosis.substring(1, parenEnd) + "회"
                        } else {
                            val firstChar = diagnosis.first().toString()
                            if (firstChar == "회") {
                                diagnosis = "단순유리"
                            } else {
                                val yuStr = if (canDeferment) "유" else ""
                                diagnosis = "$firstChar${yuStr}회"
                            }
                        }
                    } else if (isCompound) {
                        // compound: (X유)회워 → (X유)새
                        diagnosis = diagnosis.substring(0, parenEnd + 1) + optimalEnding
                    } else {
                        diagnosis = diagnosis.dropLast(1) + optimalEnding
                    }
                    Log.d("HWP_CALC", "마무리 비교: 단기=${shortTermTotal}+1000=${shortTermTotal + 1000}, 장기=$ltTotal, 새새=$saeTotalPayment → $optimalEnding (결과=$diagnosis)")
                }
            }
        }

        // 단기+장기만 가능 (새출발 불가) → compound 마무리 비교
        if (!allThreeAvailable && !effectiveShortTermBlocked && shortTermTotal > 0
            && !longTermFullyBlocked && finalYear > 0) {
            val ltTotal2 = finalMonthly * finalYear * 12
            val skipEnding2 = diagnosis == "방생" || diagnosis.startsWith("단순") || diagnosis.contains("이후 가능")
            val parenEnd2 = diagnosis.indexOf(")")
            val isCompound2 = parenEnd2 >= 0 && diagnosis.startsWith("(")
            if (!skipEnding2 && isCompound2 && shortTermTotal + 1000 <= ltTotal2 && diagnosis.contains("회")) {
                // 단기 유리 → (X유)회워 → X유회
                diagnosis = diagnosis.substring(1, parenEnd2) + "회"
                Log.d("HWP_CALC", "마무리 비교(2옵션): 단기=${shortTermTotal}+1000=${shortTermTotal + 1000}, 장기=$ltTotal2 → 회 (결과=$diagnosis)")
            }
        }

        // 장기불가 → 진단 마지막 워 제거 (워크아웃 불가)
        // 단, 방생/새새/단순/이후가능 등 특수 진단은 건너뜀
        val skipWorRemoval = diagnosis == "방생" || diagnosis.startsWith("단순") || diagnosis.startsWith("새") || diagnosis.contains("이후 가능")
        if (longTermFullyBlocked && !skipWorRemoval && diagnosis.endsWith("워") && diagnosis.length > 1) {
            diagnosis = diagnosis.dropLast(1)
            Log.d("HWP_CALC", "장기불가 → 마지막 워 제거: $diagnosis")
        }

        // 장기불가 → 맨앞 신/프/워 제거 (장기 시작점 불가)
        if (longTermFullyBlocked && !skipWorRemoval && diagnosis.length > 1 && (diagnosis.startsWith("신") || diagnosis.startsWith("프") || diagnosis.startsWith("워"))) {
            diagnosis = diagnosis.substring(1)
            Log.d("HWP_CALC", "장기불가 → 맨앞 신/프/워 제거: $diagnosis")
        }

        // 6개월 30% 이상 → 진단 맨 앞 신/프/워 불가 (장기 시작불가)
        // 장기 자체가 불가이면 prefix 제거 불필요
        if (recentDebtRatio >= 30 && !longTermFullyBlocked && !diagnosis.contains("이후 가능") && diagnosis.length > 1 && (diagnosis.startsWith("신") || diagnosis.startsWith("프") || diagnosis.startsWith("워"))) {
            diagnosis = diagnosis.substring(1)
            Log.d("HWP_CALC", "6개월 30%이상 → 진단 앞 신/프/워 제거: $diagnosis")
        }

        // 소득 예정(구직 중) → "회워 바로 가능" 대신 "회워 구직 이후 가능"
        if (isIncomeEstimated && !diagnosis.contains("방생") && !diagnosis.startsWith("단순") && !diagnosis.startsWith("새")) {
            if (diagnosis.contains("회워 바로 가능")) {
                diagnosis = diagnosis.replace("회워 바로 가능", "회워 구직 이후 가능")
            } else if (diagnosis.contains("이후 가능") && !diagnosis.contains("구직")) {
                diagnosis += ", 회워 구직 이후 가능"
            } else if (diagnosis == "회워") {
                diagnosis = "회워 구직 이후 가능"
            }
        }

        // UI 업데이트
        binding.name.text = "[이름] $name"
        val incomeSuffix = if (isIncomeEstimated) " (예정)" else ""
        binding.card.text = "[소득] ${income}만${incomeSuffix}"
        val datSuffix = buildString {
            if ((needsCarDisposal || wantsCarSale) && carDebtDeficit > 0) append(" (차량 처분후)")
            if (studentLoanMan > 0) append(" (학자금 제외)")
        }
        val datBase = if ((needsCarDisposal || wantsCarSale) && carDebtDeficit > 0) targetDebtBeforeDisposal + carDebtDeficit else targetDebtBeforeDisposal
        val datDisplayAmount = if (studentLoanMan > 0) maxOf(datBase - studentLoanMan, 0) else datBase
        binding.dat.text = "[대상] ${formatToEok(datDisplayAmount)}$datSuffix"
        binding.money.text = if (isRegistrySplit) "[재산] ${formatToEok(netProperty)} (등본 분리후)" else "[재산] ${formatToEok(netProperty)}"

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
        val studentLoanShortSuffix = if (studentLoanMan > 0) " (학자금 포함)" else ""
        binding.test1.text = "[단기] $shortTermResult$spouseSecretSuffix$studentLoanShortSuffix"

        val longTermText = StringBuilder()
        if (longTermDebtOverLimit) {
            val limitDetails = buildList {
                if (totalUnsecuredDebt > 50000) add("무담보${formatToEok(totalUnsecuredDebt)}")
                if (totalSecuredDebt > 100000) add("담보${formatToEok(totalSecuredDebt)}")
            }.joinToString("/")
            longTermText.append("[장기] 장기 불가 (채무한도초과($limitDetails))")
        } else if (isBangsaeng) {
            longTermText.append("[장기] 장기 불가 ($bangsaengReason)")
        } else if (nonAffiliatedOver20) {
            longTermText.append("[장기] 미협약 초과")
        } else if (hasAuction) {
            longTermText.append("[장기] 장기 불가 (경매 진행중)")
        } else if (hasSeizure) {
            longTermText.append("[장기] 장기 불가 (압류 진행중)")
        } else if (longTermDebtInsufficient) {
            longTermText.append("[장기] 장기 불가 (채무 부족)")
        } else if (longTermFullyBlocked) {
            val blockedReason = if (longTermCarBlockedEffective) {
                val reasons = buildList {
                    if (carTotalSise >= 1000) add("시세${carTotalSise}만")
                    if (carCount >= 2) add("${carCount}대")
                    if (carMonthlyPayment >= 50) add("월납${carMonthlyPayment}만")
                }.joinToString("/")
                "차량($reasons)"
            } else "재산초과"
            longTermText.append("[장기] 장기 불가 ($blockedReason)")
        } else if (finalYear > 0 && finalMonthly > 0) {
            val studentLoanLongSuffix = if (studentLoanMan > 0) " (학자금 제외)" else ""
            val fullRepaymentSuffix = if (repaymentRate == 100) " (원금전액)" else ""
            longTermText.append("[장기] ${finalMonthly}만 / ${finalYear}년납${fullRepaymentSuffix}$studentLoanLongSuffix")
        } else {
            val studentLoanLongSuffix = if (studentLoanMan > 0) " (학자금 제외)" else ""
            val fullRepaymentSuffix = if (repaymentRate == 100) " (원금전액)" else ""
            longTermText.append("[장기] ${roundedLongTermMonthly}만 / ${longTermYears}년납${fullRepaymentSuffix}$studentLoanLongSuffix")
        }
        if (canApplySaeBase && saeTotalPayment > 0) {
            longTermText.append("\n[새새] ${saeMonthly}만 / ${saeYears}년납")
        }
        binding.test2.text = longTermText.toString()

        // 단순유리 + 새새 가능 + 새새-단기 <= 1000만 → 새새가 유리
        if (diagnosis == "단순유리" && canApplySae && saeTotalPayment > 0 && shortTermTotal > 0 && saeTotalPayment - shortTermTotal <= 1000) {
            diagnosis = "새새"
            Log.d("HWP_CALC", "단순유리→새새: 새새총액=${saeTotalPayment}만 - 단기총액=${shortTermTotal}만 = ${saeTotalPayment - shortTermTotal}만 <= 1000만")
        }

        // 본인명의 집 + 새새 가능 → 새새 (회생 시 집경매 위험, 단 새새가 장기보다 비싸면 장기 유지)
        val longTermTotalForSaeCompare = finalMonthly * finalYear * 12
        if (hasOwnRealEstate && canApplySae && saeTotalPayment > 0 && diagnosis.contains("회") && saeTotalPayment <= longTermTotalForSaeCompare) {
            diagnosis = "새새"
            Log.d("HWP_CALC", "본인명의 집 + 새새 가능 → 새새 (회생 시 집경매 위험, 새새${saeTotalPayment}만 <= 장기${longTermTotalForSaeCompare}만)")
        }

        // 단순유리 + 장기 가능 → 연체에 따라 신유회/프유회/워유회 (유예가능시만 유 포함)
        // 단순유리이면 effectiveShortTermBlocked=false이므로 회 항상 가능
        else if (diagnosis == "단순유리" && !longTermFullyBlocked && !nonAffiliatedOver20 && finalYear > 0
            && (shortTermTotal <= 0 || targetDebt - shortTermTotal <= 1000)) {
            val yuStr2 = if (canDeferment) "유" else ""
            diagnosis = when {
                delinquentDays >= 90 -> "워${yuStr2}회"
                delinquentDays >= 30 -> "프${yuStr2}회"
                else -> "신${yuStr2}회"
            }
            // 단순유리 변환 후 6개월 30% 날짜 계산
            if (recentDebtRatio >= 30 && recentDebtEntries.isNotEmpty() && targetDebt > 0) {
                recentDebtEntries.sortBy { it.first.timeInMillis }
                var remainingMan2 = recentDebtMan
                for ((date, amountChon) in recentDebtEntries) {
                    val amountMan = (amountChon + 5) / 10
                    remainingMan2 -= amountMan
                    if (remainingMan2 < 0) remainingMan2 = 0
                    val newRatio = remainingMan2.toDouble() / targetDebt * 100
                    if (newRatio < 30) {
                        val thresholdCal = date.clone() as Calendar
                        thresholdCal.add(Calendar.MONTH, 6)
                        val possibleDate = "${thresholdCal.get(Calendar.YEAR)}.${String.format("%02d", thresholdCal.get(Calendar.MONTH) + 1)}.${String.format("%02d", thresholdCal.get(Calendar.DAY_OF_MONTH))}"
                        diagnosis = "$diagnosis $possibleDate 이후 가능"
                        Log.d("HWP_CALC", "단순유리 변환 후 6개월 날짜: $possibleDate (남은 ${remainingMan2}만/${targetDebt}만=${String.format("%.1f", newRatio)}%)")
                        break
                    }
                }
            }
        }

        // 미협약 초과 + 단순유리 + 6개월 30% → 날짜만 추가 (신유회 변환 없이)
        if (diagnosis == "단순유리" && nonAffiliatedOver20 && recentDebtRatio >= 30 && recentDebtEntries.isNotEmpty() && targetDebt > 0) {
            recentDebtEntries.sortBy { it.first.timeInMillis }
            var remainingMan3 = recentDebtMan
            for ((date, amountChon) in recentDebtEntries) {
                val amountMan = (amountChon + 5) / 10
                remainingMan3 -= amountMan
                if (remainingMan3 < 0) remainingMan3 = 0
                val newRatio = remainingMan3.toDouble() / targetDebt * 100
                if (newRatio < 30) {
                    val thresholdCal = date.clone() as Calendar
                    thresholdCal.add(Calendar.MONTH, 6)
                    val possibleDate = "${thresholdCal.get(Calendar.YEAR)}.${String.format("%02d", thresholdCal.get(Calendar.MONTH) + 1)}.${String.format("%02d", thresholdCal.get(Calendar.DAY_OF_MONTH))}"
                    diagnosis = "단순유리"
                    Log.d("HWP_CALC", "미협약 초과 + 단순유리 (6개월 날짜 생략: $possibleDate)")
                    break
                }
            }
        }

        // 다른 단계 진행중이면 진단 앞에 표시 (방생은 제외)
        if (hasOngoingProcess && ongoingProcessName.isNotEmpty() && diagnosis != "방생") {
            // 진행중 제도 prefix가 있으면 진단 앞의 연체 route(신/프/워) 제거 (중복 방지)
            var baseDiag = diagnosis
            if ((baseDiag.startsWith("신") || baseDiag.startsWith("프") || baseDiag.startsWith("워")) && baseDiag.length > 1) {
                baseDiag = baseDiag.substring(1)
            }
            // 축약: 단순유리 → 유회, 단순워크 → 유워, 새새/새 → 유새
            if (baseDiag == "단순유리") baseDiag = "유회"
            else if (baseDiag == "단순워크") baseDiag = "유워"
            else if (baseDiag == "새새" || baseDiag == "새") baseDiag = "유새"
            // hoeBlocked시 baseDiag에서 회 제거 (면책5년 10개월이상 or 소득≤100 → 회 불가)
            val cleanBase = if (baseDiag.startsWith("회") && hoeBlocked) baseDiag.removePrefix("회") else baseDiag
            if (aiDefermentMonths > 0) {
                // 유예 중복 제거: 워유워→워, 유워→워
                var deferBase = cleanBase
                if (deferBase.startsWith("워유워")) deferBase = deferBase.removePrefix("워유")
                else if (deferBase.startsWith("유")) deferBase = deferBase.removePrefix("유")
                if (aiDefermentMonths >= 12) {
                    // 유예기간 12개월 이상 (유예불가): (신유)워, (프유)워, (워유)워
                    diagnosis = "(${ongoingProcessName}유)$deferBase"
                } else {
                    // 유예기간 12개월 미만 (유예가능)
                    if (cleanBase.startsWith("회")) {
                        // 회워/회새 → (X)회워, (X)회새 (>4000만, 회 가능)
                        diagnosis = "($ongoingProcessName)$cleanBase"
                    } else {
                        // 유워/워/새 등 → (X)유워, (X)유새
                        diagnosis = "($ongoingProcessName)유$deferBase"
                    }
                }
            } else {
                if (baseDiag.startsWith("회") && hoeBlocked) {
                    // 회 불가 + 유예가능 → (X)유워, (X)유새
                    diagnosis = "($ongoingProcessName)유$cleanBase"
                } else {
                    diagnosis = "($ongoingProcessName)$cleanBase"
                }
            }
        }

        // 신복/신복위 진행 중 + 추가채무 있으면 "실효 후 1년 연체 필요" 추가
        if (hasOngoingProcess && ongoingProcessName == "신" && postApplicationDebtMan > 0) {
            diagnosis += ", 실효 후 1년 연체 필요"
        }

        // 진단 앞에 다른 내용이 있으면 새새 → 새 (앞 내용이 있으므로 새 1개로 충분)
        if (diagnosis != "새새" && diagnosis.contains("새새")) {
            diagnosis = diagnosis.replace("새새", "새")
        }

        // 존재하지 않는 진단 포맷 변환 (최종 표시 직전)
        if (diagnosis == "회") diagnosis = "단순유리"
        if (diagnosis == "워") diagnosis = "단순워크"
        if (diagnosis == "유") diagnosis = "방생"

        // 단순유리/단순워크인데 단기+장기 둘 다 불가 → 방생
        if ((diagnosis == "단순유리" || diagnosis == "단순워크") && effectiveShortTermBlocked && longTermFullyBlocked) {
            diagnosis = "방생"
            Log.d("HWP_CALC", "$diagnosis + 단기불가 + 장기불가 → 방생")
        }

        var finalDiagnosis = if (diagnosisNote.isNotEmpty()) "$diagnosis $diagnosisNote" else diagnosis

        if (shortTermCarSaleApplied) finalDiagnosis = "차량 처분 시 $finalDiagnosis"
        if (repaymentRate == 100 && !diagnosis.startsWith("단순") && diagnosis != "방생") finalDiagnosis = "$finalDiagnosis, 부결고지"
        binding.testing.text = "[진단] $finalDiagnosis"
        binding.half.text = ""

        Log.d("HWP_CALC", "이름: $name, 소득: ${income}만, 대상: ${targetDebt}만, 재산: ${netProperty}만, 6개월비율: ${String.format("%.1f", recentDebtRatio)}%, 진단: $finalDiagnosis")
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

    // 콜론 뒤의 텍스트를 반환 (없으면 전체 텍스트)
    private fun afterColonOrLine(line: String): String {
        return if (line.contains(":") || line.contains("：")) {
            line.substringAfter(":").let { if (it == line) line.substringAfter("：") else it }.trim()
        } else line
    }

    // 채권사명 추출: 테이블 라인에서 금융기관명 추출
    private fun extractCreditorFromLine(line: String): String {
        val noSpace = line.replace(" ", "")
        // 테이블 행 유형 설명은 채권사가 아님 (startsWith로 파생 패턴도 제외)
        val excludePrefixes = listOf("개인대출정보", "개인사업자대출", "연대보증정보", "보증채무정보", "기업대출정보")
        // 금융기관명 패턴 매칭 (접미사 기반, 긴 접미사 우선)
        val matches = Regex("([가-힣A-Za-z]+(?:저축은행|투자증권|새마을금고|자산관리|신용보증|보증재단|생명보험|화재보험|신용정보|은행|캐피탈|카드|금융|저축|보증|공사|재단|생명|화재|보험|공단|대부|정보|기금|증권|파이낸셜|위원회|금고))").findAll(noSpace)
        for (match in matches) {
            // "19년스타크레디트대부" → "년" 등 날짜/단위 접두어 제거
            val name = match.groupValues[1].replace(Regex("^[년월일만천억원]+"), "")
            if (name.isEmpty()) continue
            if (excludePrefixes.none { name.startsWith(it) }) return name
        }
        // 특수 기관명
        val specificNames = listOf("캠코", "국민행복기금", "새도약기금", "새출발기금", "머니무브", "피에프씨", "PFC")
        for (name in specificNames) {
            if (noSpace.contains(name)) return name
        }
        return ""
    }

    // 카드사명 추출: 라인에서 카드사 이름 추출 (KB국민카드, 삼성카드 등)
    private fun extractCardCompanyName(text: String): String {
        val cardPatterns = listOf(
            "KB국민카드", "국민카드", "삼성카드", "신한카드", "현대카드", "롯데카드",
            "하나카드", "우리카드", "BC카드", "비씨카드", "NH카드", "농협카드",
            "씨티카드", "카카오뱅크카드", "케이뱅크카드", "토스카드"
        )
        val noSpace = text.replace(" ", "")
        for (card in cardPatterns) {
            if (noSpace.contains(card.replace(" ", ""))) return card
        }
        // 패턴 매칭: "OO카드" 형태
        val m = Regex("([가-힣A-Za-z]+카드)").find(noSpace)
        return m?.groupValues?.get(1) ?: ""
    }

    /**
     * 연봉(만원) → 월 실수령액(만원) 계산 (2026년 기준)
     * 부양가족 1인(본인), 비과세 없음 가정
     */
    private fun calculateMonthlyNetSalary(annualSalaryMan: Int): Int {
        val ann = annualSalaryMan * 10000.0
        val monthly = ann / 12

        // 4대 보험
        val pension = minOf(monthly, 6_370_000.0) * 0.0475
        val health = monthly * 0.03595
        val longCare = health * 0.1314
        val employ = monthly * 0.009
        val totalIns = pension + health + longCare + employ

        // 근로소득공제
        val eid = when {
            ann <= 5_000_000 -> ann * 0.70
            ann <= 15_000_000 -> 3_500_000 + (ann - 5_000_000) * 0.40
            ann <= 45_000_000 -> 7_500_000 + (ann - 15_000_000) * 0.15
            ann <= 100_000_000 -> 12_000_000 + (ann - 45_000_000) * 0.05
            else -> minOf(14_750_000 + (ann - 100_000_000) * 0.02, 20_000_000.0)
        }
        val earnedIncome = ann - eid

        // 소득공제 → 과세표준
        val taxable = maxOf(earnedIncome - 1_500_000 - pension * 12 - (health + longCare) * 12, 0.0)

        // 누진세
        val calcTax = maxOf(when {
            taxable <= 14_000_000 -> taxable * 0.06
            taxable <= 50_000_000 -> taxable * 0.15 - 1_260_000
            taxable <= 88_000_000 -> taxable * 0.24 - 5_760_000
            taxable <= 150_000_000 -> taxable * 0.35 - 15_440_000
            taxable <= 300_000_000 -> taxable * 0.38 - 19_940_000
            taxable <= 500_000_000 -> taxable * 0.40 - 25_940_000
            taxable <= 1_000_000_000 -> taxable * 0.42 - 35_940_000
            else -> taxable * 0.45 - 65_940_000
        }, 0.0)

        // 근로소득 세액공제
        var credit = if (calcTax <= 1_300_000) calcTax * 0.55
                     else 715_000 + (calcTax - 1_300_000) * 0.30
        val limit = when {
            ann <= 33_000_000 -> 740_000.0
            ann <= 70_000_000 -> maxOf(740_000 - (ann - 33_000_000) * 0.008, 660_000.0)
            else -> maxOf(660_000 - (ann - 70_000_000) * 0.5, 500_000.0)
        }
        credit = minOf(credit, limit)

        val annTax = maxOf(calcTax - credit - 130_000, 0.0)
        val monthTax = annTax / 12
        val localTax = monthTax * 0.10

        val net = monthly - totalIns - monthTax - localTax
        return Math.round(net / 10000).toInt()
    }

    private fun extractAmount(text: String): Int {
        // HWP 특수 공백(비분리 공백, 전각 공백 등)을 일반 공백으로 정규화
        @Suppress("RegExpRedundantEscape") val t = text.replace('\u00A0', ' ').replace('\u3000', ' ').replace('\u2002', ' ').replace('\u2003', ' ')
        try {
            // 4억5천
            var m = Pattern.compile("(\\d+)억\\s*(\\d+)천").matcher(t)
            if (m.find()) return m.group(1)!!.toInt() * 10000 + m.group(2)!!.toInt() * 1000
            // 1억 630만
            m = Pattern.compile("(\\d+)억\\s*(\\d+)만").matcher(t)
            if (m.find()) return m.group(1)!!.toInt() * 10000 + m.group(2)!!.toInt()
            // 3억
            m = Pattern.compile("(\\d+)억").matcher(t)
            if (m.find()) {
                var total = m.group(1)!!.toInt() * 10000
                val mm = Pattern.compile("(\\d+)\\s*만").matcher(t)
                if (mm.find()) total += mm.group(1)!!.toInt()
                return total
            }
            // 9965만 or 600 만 (숫자와 만 사이 공백 허용)
            m = Pattern.compile("(\\d+)\\s*만").matcher(t)
            if (m.find()) return m.group(1)!!.toInt()
            // 콤마 숫자
            m = Pattern.compile("([\\d,]+)$").matcher(t.trim())
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

    // ============= 배치 진단 =============
    private fun openBatchFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, BATCH_REQUEST_CODE)
    }

    private fun showBatchFileDialog() {
        // 파일을 이름 기준으로 그룹화
        buildBatchGroups()

        if (batchGroups.isEmpty()) {
            showToast("HWP 파일이 없습니다")
            return
        }
        val fileNames = batchGroups.mapIndexed { i, group ->
            val pdfCount = group.pdfUris.size
            val pdfInfo = if (pdfCount > 0) " + PDF ${pdfCount}개" else ""
            "${i + 1}. ${group.baseName}.hwp$pdfInfo"
        }.joinToString("\n")

        val hasPdf = batchGroups.any { it.pdfUris.isNotEmpty() }
        val title = "${batchGroups.size}건 선택됨" +
                if (hasPdf) " (PDF 포함)" else ""

        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("$fileNames\n\n파일을 더 추가하시겠습니까?")
            .setPositiveButton("배치 시작") { _, _ ->
                startBatchProcessing()
            }
            .setNeutralButton("파일 추가") { _, _ ->
                openBatchFilePicker()
            }
            .setNegativeButton("취소") { _, _ ->
                batchUriList.clear()
                batchGroups.clear()
            }
            .setCancelable(false)
            .show()
    }

    // HWP+PDF를 그룹으로 묶기 (PDF 파일명이 HWP 이름으로 시작하면 매칭)
    // 예: 김영호.hwp ← 김영호.pdf, 김영호(변제계획안).pdf, 김영호_상환내역서.pdf
    private fun buildBatchGroups() {
        val hwpList = ArrayList<Pair<String, Uri>>() // (baseName, uri)
        val pdfList = ArrayList<Pair<String, Uri>>() // (baseName, uri)

        for (uri in batchUriList) {
            val fileName = getFileName(uri) ?: continue
            val lowerName = fileName.lowercase()
            val baseName = fileName.substringBeforeLast(".")

            when {
                lowerName.endsWith(".hwp") -> hwpList.add(baseName to uri)
                lowerName.endsWith(".pdf") -> pdfList.add(baseName to uri)
            }
        }

        batchGroups.clear()
        for ((hwpBase, hwpUri) in hwpList) {
            // HWP 파일명에서 이름 부분만 추출 (괄호 앞) - 예: "강동진(동승, 주용소개건)" → "강동진"
            val hwpName = hwpBase.substringBefore("(").trim()
            val matchedPdfs = pdfList
                .filter { (pdfBase, _) -> pdfBase.startsWith(hwpName) }
                .map { it.second }
            batchGroups.add(BatchFileGroup(hwpBase, hwpUri, matchedPdfs))
        }
    }

    private fun startBatchProcessing() {
        batchMode = true
        batchIndex = 0
        batchResults.clear()
        batchDialog = android.app.ProgressDialog(this).apply {
            setMessage("0/${batchGroups.size} 처리 중...")
            setCancelable(false)
            show()
        }
        processNextBatchFile()
    }

    private fun processNextBatchFile() {
        if (batchIndex >= batchGroups.size) {
            finishBatchProcessing()
            return
        }

        resetAllData()
        val group = batchGroups[batchIndex]
        val displayName = group.baseName
        Log.d("BATCH", "배치 처리 ${batchIndex + 1}/${batchGroups.size}: $displayName")
        batchDialog?.setMessage("${batchIndex + 1}/${batchGroups.size} 처리 중... ($displayName)")

        // PDF 존재 여부 설정 (파일 선택 모드와 동일)
        if (group.pdfUris.isNotEmpty()) hasPdfFile = true

        // HWP 텍스트 추출
        hwpText = extractHwpText(group.hwpUri)
        if (hwpText.isEmpty()) {
            batchResults.add("===== ${batchIndex + 1}/${batchGroups.size} =====\n[파일] $displayName\n[오류] HWP 텍스트 추출 실패")
            batchIndex++
            processNextBatchFile()
            return
        }

        // 같은 이름의 PDF 텍스트 추출 후 합치기 (단일 모드와 동일 필터링)
        val pdfTexts = ArrayList<String>()
        val ocrPdfUris = ArrayList<Pair<Uri, String>>()
        for (pdfUri in group.pdfUris) {
            val pdfFileName = getFileName(pdfUri) ?: "PDF"
            val lowerFileName = pdfFileName.lowercase()
            val isRelevantPdf = lowerFileName.contains("변제계획") || lowerFileName.contains("소금원") ||
                lowerFileName.contains("소득금액") || lowerFileName.contains("변제예정")
            if (isRelevantPdf) {
                val text = extractPdfText(pdfUri)
                if (text.isNotEmpty()) {
                    pdfTexts.add("=== PDF: $pdfFileName ===\n$text")
                    Log.d("BATCH", "PDF 추출 (AI포함): $pdfFileName (${text.length}자)")
                }
            } else {
                ocrPdfUris.add(Pair(pdfUri, pdfFileName))
            }
        }

        if (ocrPdfUris.isNotEmpty()) {
            extractDataFromPdfImages(ocrPdfUris) { ocrResult ->
                if (ocrResult.defermentMonths > 0) {
                    hwpText += "\n유예기간 ${ocrResult.defermentMonths}개월"
                    Log.d("BATCH", "PDF OCR 유예기간 추출: ${ocrResult.defermentMonths}개월")
                }
                if (ocrResult.agreementDebt > 0) {
                    pdfAgreementDebt = ocrResult.agreementDebt
                    Log.d("BATCH", "PDF OCR 합의서 대상채무 추출: ${ocrResult.agreementDebt}만")
                }
                if (ocrResult.processName.isNotEmpty()) {
                    pdfAgreementProcess = ocrResult.processName
                    Log.d("BATCH", "PDF OCR 진행중 제도: ${ocrResult.processName}")
                }
                if (ocrResult.applicationDate.isNotEmpty()) {
                    pdfApplicationDate = ocrResult.applicationDate
                    Log.d("BATCH", "PDF OCR 신청일자 추출: ${ocrResult.applicationDate}")
                }
                if (ocrResult.excludedGuaranteeDebt > 0) {
                    pdfExcludedGuaranteeDebt = ocrResult.excludedGuaranteeDebt
                    Log.d("BATCH", "PDF 합의서 제외 보증서담보대출: ${ocrResult.excludedGuaranteeDebt}만")
                }
                if (ocrResult.excludedOtherDebt > 0) {
                    pdfExcludedOtherDebt = ocrResult.excludedOtherDebt
                    Log.d("BATCH", "PDF 합의서 제외 기타: ${ocrResult.excludedOtherDebt}만")
                }
                finishFileProcessing(pdfTexts)
            }
        } else {
            finishFileProcessing(pdfTexts)
        }
    }

    private fun finishBatchProcessing() {
        batchMode = false
        batchDialog?.dismiss()
        batchDialog = null

        val fullResult = batchResults.joinToString("\n\n")

        // Downloads 폴더에 저장
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())
            val fileName = "배치진단_${timestamp}.txt"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { fos ->
                fos.write(fullResult.toByteArray(Charsets.UTF_8))
            }
            Log.d("BATCH", "배치 결과 저장: ${file.absolutePath}")

            // 공유 Intent
            val shareUri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "배치 진단 결과 공유"))
        } catch (e: Exception) {
            Log.e("BATCH", "배치 결과 저장 실패", e)
            showToast("파일 저장 실패: ${e.message}")
        }

        showToast("${batchGroups.size}건 처리 완료")
    }

    private fun resetAllData() {
        // 멤버 변수 초기화
        acost = 0; bCost = 0.0; bValue = 0; card = 0; cost = 0; value = 0
        baby = "0"; korea = "X"

        // AI 데이터 초기화
        pdfAgreementDebt = 0; pdfAgreementProcess = ""; pdfApplicationDate = ""; hasPdfFile = false
        aiDefermentMonths = 0; aiSogumwonMonthly = 0
        aiDataReady = false
        aiHasRecoveryPlan = false
        pdfExcludedGuaranteeDebt = 0; pdfExcludedOtherDebt = 0

        // 파일 텍스트 초기화
        hwpText = ""; pdfText = ""

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

    // AI 데이터 추출 (재시도 지원)
    private fun extractDataWithAI(text: String, retryCount: Int = 0) {
        val maxRetry = 2  // 최대 재시도 횟수 (총 3회 시도)
        if (retryCount == 0) aiExtractCancelled = false

        if (!batchMode) {
            runOnUiThread {
                loadingDialog?.dismiss()
                val retryMsg = if (retryCount > 0) " (재시도 ${retryCount}/${maxRetry})" else ""
                loadingDialog = android.app.ProgressDialog(this).apply {
                    setMessage("AI 데이터 분석 중...$retryMsg")
                    setCancelable(false)
                    setButton(android.app.ProgressDialog.BUTTON_NEGATIVE, "중단") { _, _ ->
                        aiExtractCancelled = true
                        dismiss()
                        showToast("AI 추출 중단됨")
                    }
                    show()
                }
            }
        }
        AiDataExtractor.extract(text, object : AiDataExtractor.OnExtractListener {
            override fun onSuccess(result: AiDataExtractor.ExtractResult) {
                if (aiExtractCancelled) return
                // 채권사/세금 관련 제거됨 - 코드에서 직접 파싱
                aiDefermentMonths = result.defermentMonths
                aiSogumwonMonthly = result.sogumwonMonthly
                aiHasRecoveryPlan = result.hasRecoveryPlan

                runOnUiThread {
                    loadingDialog?.dismiss()
                    Log.d("AI_EXTRACT", "AI 값 적용하여 재계산 시작")
                    if (hwpText.isNotEmpty()) parseHwpData(hwpText)
                    if (batchMode) {
                        val groupName = batchGroups[batchIndex].baseName
                        val pdfInfo = if (batchGroups[batchIndex].pdfUris.isNotEmpty()) " (+PDF)" else ""
                        batchResults.add("===== ${batchIndex + 1}/${batchGroups.size} =====\n[파일] $groupName$pdfInfo\n${buildResultText()}")
                        batchIndex++
                        batchDialog?.setMessage("${batchIndex}/${batchGroups.size} 처리 완료")
                        processNextBatchFile()
                    } else {
                        showToast("AI 데이터 반영 완료")
                    }
                }
            }
            override fun onError(error: String) {
                if (aiExtractCancelled) return
                runOnUiThread {
                    if (retryCount < maxRetry) {
                        Log.w("AI_EXTRACT", "AI 추출 실패 (${retryCount + 1}/${maxRetry + 1}): $error → 재시도")
                        extractDataWithAI(text, retryCount + 1)
                    } else {
                        loadingDialog?.dismiss()
                        // AI 실패해도 HWP 데이터만으로 결과 표시
                        if (hwpText.isNotEmpty()) parseHwpData(hwpText)
                        if (batchMode) {
                            val groupName = batchGroups[batchIndex].baseName
                            val pdfInfo = if (batchGroups[batchIndex].pdfUris.isNotEmpty()) " (+PDF)" else ""
                            batchResults.add("===== ${batchIndex + 1}/${batchGroups.size} =====\n[파일] $groupName$pdfInfo\n[주의] AI 추출 실패\n${buildResultText()}")
                            batchIndex++
                            batchDialog?.setMessage("${batchIndex}/${batchGroups.size} 처리 완료")
                            processNextBatchFile()
                        } else {
                            showToast("AI 추출 실패 - HWP 데이터만 표시")
                        }
                    }
                }
            }
        })
    }
}