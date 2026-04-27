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
import com.google.android.gms.tasks.Tasks
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

    private var aiDefermentMonths = 0

    private var aiHasRecoveryPlan = false    // 변제계획안 존재 (개인회생 진행 중)
    private var pdfAgreementDebt = 0           // 합의서 PDF 대상채무 (만원)
    private var pdfAgreementProcess = ""       // 합의서 PDF 진행중 제도 (신속/프리/워크)
    private val pdfAgreementCreditors = mutableMapOf<String, Int>()  // 합의서 PDF 채권사 (이름→원금만원)
    private var pdfApplicationDate = ""        // 상환내역서 PDF 신청일자 (YYYY.MM.DD)
    private var hasPdfFile = false             // PDF 파일 존재 여부
    private var pdfExcludedGuaranteeDebt = 0  // 합의서 제외 보증서담보대출 채무 (만원)
    private var pdfExcludedOtherDebt = 0      // 합의서 제외 기타 채무 (만원)
    private var pdfExcludedDamboCreditors = mutableSetOf<String>()  // 제외된 담보 채권사명 (290 판단용)
    private data class PdfExcludedEntry(val seq: Int, val name: String, val principal: Int, val reason: String, val isDambo: Boolean)
    private var pdfExcludedEntries = mutableListOf<PdfExcludedEntry>()  // 제외 채무 목록 (표시용)
    private data class PdfOutsideEntry(val name: String, val principal: Int)  // 협약 외 채무 (이름, 원금만원)
    private var pdfOutsideEntries = mutableListOf<PdfOutsideEntry>()  // 협약 외 채무 목록
    private var pdfRecoveryDebt = 0            // 변제계획안 대상채무 (만원)
    private var pdfRecoveryIncome = 0          // 변제계획안 월변제금 (만원)
    private var pdfRecoveryMonths = 0          // 변제계획안 변제기간 (개월)
    private var pdfRecoveryCreditors = mutableMapOf<String, Int>()  // 변제계획안 채권사별 금액 (이름→만원)

    // 여러 파일 처리
    private var hwpText = ""
    private var pdfText = ""
    private var isClientMode = false  // 거래처 진단 모드


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

        binding.buttonClientDiagnosis.setOnClickListener {
            isClientMode = true
            resetAllData()
            batchMode = false
            batchUriList.clear()
            batchGroups.clear()
            batchResults.clear()
            batchIndex = 0
            openBatchFilePicker()
        }
        binding.buttonLegoDiagnosis.setOnClickListener {
            isClientMode = false
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
                                "application/pdf",
                                "image/jpeg", "image/png", "image/bmp", "image/webp"
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

            // HWP/PDF/이미지 아닌 파일 제거
            batchUriList = ArrayList(batchUriList.filter { uri ->
                val name = getFileName(uri)?.lowercase() ?: ""
                name.endsWith(".hwp") || name.endsWith(".pdf") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".bmp") || name.endsWith(".webp")
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
                lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".bmp") || lowerName.endsWith(".webp") -> {
                    pdfUris.add(Pair(uri, fileName))
                    hasPdfFile = true
                    Log.d("FILE_PROCESS", "이미지 파일 처리: $fileName")
                }
                lowerName.endsWith(".xlsx") -> {
                    readExcelFile(uri)
                    return
                }
            }
        }

        // 2단계: PDF 처리 (합의서/변제계획안=텍스트파싱, 상환내역서=이미지OCR, 기타=ML Kit OCR)
        val ocrPdfUris = ArrayList<Pair<Uri, String>>()
        for ((uri, fileName) in pdfUris) {
            val lowerFileName = fileName.lowercase()
            when {
                lowerFileName.contains("합의") -> {
                    // 합의서는 항상 Claude Vision으로 처리 (테이블 추출이 필요)
                    Log.d("FILE_PROCESS", "합의서 → Claude Vision 처리 ($fileName)")
                    ocrPdfUris.add(Pair(uri, fileName))
                }
                lowerFileName.contains("변제계획") || lowerFileName.contains("변제예정") -> {
                    val text = extractPdfText(uri)
                    if (text.length > 50) parseRecoveryPlanPdfText(text, fileName)
                    else ocrPdfUris.add(Pair(uri, fileName))  // 이미지 PDF → OCR
                }
                else -> ocrPdfUris.add(Pair(uri, fileName))
            }
        }

        if (ocrPdfUris.isNotEmpty()) {
            extractDataFromPdfImages(ocrPdfUris) { ocrResult ->
                if (ocrResult.defermentMonths > 0) {
                    hwpText += "\n유예기간 ${ocrResult.defermentMonths}개월"
                    if (ocrResult.defermentMonths > aiDefermentMonths) aiDefermentMonths = ocrResult.defermentMonths
                    Log.d("FILE_PROCESS", "PDF OCR 유예기간 추출: ${ocrResult.defermentMonths}개월 → aiDefermentMonths=$aiDefermentMonths")
                }
                if (ocrResult.applicationDate.isNotEmpty()) {
                    pdfApplicationDate = ocrResult.applicationDate
                    Log.d("FILE_PROCESS", "PDF OCR 신청일자 추출: ${ocrResult.applicationDate}")
                }
                finishFileProcessing()
            }
        } else {
            finishFileProcessing()
        }
    }

    private fun finishFileProcessing() {
        if (hwpText.isNotEmpty()) {
            parseHwpData(hwpText)
            if (batchMode) {
                val groupName = batchGroups[batchIndex].baseName
                val pdfInfo = if (batchGroups[batchIndex].pdfUris.isNotEmpty()) " (+PDF)" else ""
                batchResults.add("===== ${batchIndex + 1}/${batchGroups.size} =====\n[파일] $groupName$pdfInfo\n${buildResultText()}")
                batchIndex++
                batchDialog?.setMessage("${batchIndex}/${batchGroups.size} 처리 완료")
                processNextBatchFile()
            } else {
                showToast("파일 처리 완료")
            }
        } else {
            showToast("처리할 파일이 없습니다")
        }
    }

    private fun parseAgreementPdfText(text: String, fileName: String) {
        val noSpace = text.replace(Regex("\\s"), "")

        // [1] 제도 타입 - Claude가 못 잡았을 때만 OCR로 보완
        if (pdfAgreementProcess.isEmpty()) {
            pdfAgreementProcess = when {
                noSpace.contains("신속채무조정") -> "신"
                noSpace.contains("사전채무조정") -> "프"
                noSpace.contains("개인채무조정") && noSpace.contains("확정") -> "워"
                else -> ""
            }
        }

        // [2] 유예기간 (OCR 텍스트에서)
        val deferMatch = Regex("(?:유예기간|거치기간)\\D{0,10}(\\d+)\\s*개?월").find(text)
        if (deferMatch != null) {
            val months = deferMatch.groupValues[1].toInt()
            if (months > aiDefermentMonths) aiDefermentMonths = months
        }

        // [3] 대상채무, 제외채무 → Claude가 처리 (pdfAgreementDebt, pdfExcludedOtherDebt 이미 설정됨)

        Log.d("PDF_PARSE", "합의서 파싱 결과: 제도=$pdfAgreementProcess, 채무=${pdfAgreementDebt}만, " +
            "유예=${aiDefermentMonths}개월, 제외보증=${pdfExcludedGuaranteeDebt}만, 제외기타=${pdfExcludedOtherDebt}만 ($fileName)")
    }

    private fun parseRecoveryPlanPdfText(text: String, fileName: String) {
        aiHasRecoveryPlan = true

        val lines = text.split("\n")

        // [1] 대상채무: 변제예정액 테이블의 합계 행 (개인회생채권액, 0, 월변제금, 0, 총변제예정액, 0 구조)
        // → 금액이 2개 이상 있는 합계 행의 최대값 = 개인회생채권액
        for (line in lines) {
            val lineNoSpace = line.replace(Regex("\\s"), "")
            if (lineNoSpace.contains("합계") || lineNoSpace.contains("총계")) {
                val amounts = Regex("[\\d,]{5,}").findAll(line)
                    .map { it.value.replace(",", "").toLongOrNull() ?: 0L }
                    .filter { it > 0 }.toList()
                if (amounts.size >= 2 && pdfRecoveryDebt == 0) {
                    val maxAmount = amounts.max()
                    if (maxAmount > 10_000_000) {  // 1000만원 이상이면 유효한 대상채무
                        pdfRecoveryDebt = (maxAmount / 10000).toInt()
                    }
                }
            }
        }

        // [2] 총변제예정액: "총변제예정" 테이블의 합계 행에서 추출
        val textNoSpace = text.replace(Regex("\\s"), "")
        val totalRepaymentMatch = Regex("총변제예정[^합]{0,200}합계[^\\d]{0,50}([\\d,]{5,})[^\\d]{0,30}0[^\\d]{0,30}([\\d,]{5,})").find(textNoSpace)
        var pdfTotalRepayment = 0L
        if (totalRepaymentMatch != null) {
            // 합계 행의 총변제예정액 (두번째 큰 금액)
            pdfTotalRepayment = totalRepaymentMatch.groupValues[2].replace(",", "").toLongOrNull() ?: 0L
        }
        if (pdfTotalRepayment == 0L) {
            // 폴백: "총변제예정" 근처에서 합계 금액 추출
            val repayIdx = text.indexOf("총변제예정").takeIf { it >= 0 }
            if (repayIdx != null) {
                val repayArea = text.substring(repayIdx, minOf(repayIdx + 1000, text.length))
                val sumIdx = repayArea.indexOf("합계").takeIf { it >= 0 }
                if (sumIdx != null) {
                    val afterSum = repayArea.substring(sumIdx)
                    val amounts = Regex("[\\d,]{5,}").findAll(afterSum)
                        .map { it.value.replace(",", "").toLongOrNull() ?: 0L }
                        .filter { it in 100000..5000000000L }.toList()
                    // 합계 행: 개인회생채권액, 0, 총변제예정액, 0 순서 → 총변제예정액은 두번째 큰 금액
                    if (amounts.size >= 2) {
                        pdfTotalRepayment = amounts.sortedDescending()[1]
                    } else if (amounts.isNotEmpty()) {
                        pdfTotalRepayment = amounts.last()
                    }
                }
            }
        }

        // [3] 변제기간: "XX개월간" 또는 변제횟수
        val monthsMatch = Regex("(\\d+)\\s*개월간").find(text)
            ?: Regex("변제횟수[^\\d]{0,10}(\\d+)").find(textNoSpace)
        if (monthsMatch != null) {
            pdfRecoveryMonths = monthsMatch.groupValues[1].toInt()
        }

        // 총변제예정액 / 변제기간 = 월변제금
        if (pdfTotalRepayment > 10000 && pdfRecoveryMonths > 0) {
            pdfRecoveryIncome = (pdfTotalRepayment / 10000 / pdfRecoveryMonths).toInt()
        }

        Log.d("PDF_PARSE", "변제계획안 텍스트 파싱: 대상채무=${pdfRecoveryDebt}만, 월변제금=${pdfRecoveryIncome}만, 변제기간=${pdfRecoveryMonths}개월 ($fileName)")
    }

    data class PdfOcrResult(val defermentMonths: Int, val applicationDate: String = "")

    private fun extractDataFromPdfImages(
        pdfUris: List<Pair<Uri, String>>,
        onComplete: (PdfOcrResult) -> Unit
    ) {
        var maxDefermentMonths = 0
        var detectedApplicationDate = ""
        var remaining = pdfUris.size

        for ((uri, fileName) in pdfUris) {
            try {
                val lowerFileName = fileName.lowercase()
                val isImageFile = lowerFileName.endsWith(".jpg") || lowerFileName.endsWith(".jpeg") || lowerFileName.endsWith(".png") || lowerFileName.endsWith(".bmp") || lowerFileName.endsWith(".webp")

                // ===== 이미지 파일: BitmapFactory로 직접 로드 =====
                if (isImageFile) {
                    val imageBitmap = contentResolver.openInputStream(uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input)
                    }
                    if (imageBitmap == null) {
                        Log.e("FILE_PROCESS", "이미지 디코딩 실패: $fileName")
                        remaining--
                        if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                        continue
                    }
                    Log.d("FILE_PROCESS", "이미지 파일 로드: $fileName (${imageBitmap.width}x${imageBitmap.height})")

                    val isRepayment = lowerFileName.contains("상환")
                    val isAgreementImg = lowerFileName.contains("합의")
                    val isRecoveryPlanImg = lowerFileName.contains("변제계획") || lowerFileName.contains("변제예정")

                    if (isAgreementImg) {
                        Thread {
                            try {
                                callClaudeVisionForAgreement(arrayListOf(imageBitmap), fileName)
                            } catch (e: Exception) {
                                Log.e("FILE_PROCESS", "합의서 Claude AI 실패 ($fileName): ${e.message}")
                            }
                            runOnUiThread {
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                        }.start()
                    } else if (isRepayment) {
                        Thread {
                            try {
                                val result = callClaudeVisionForRepayment(imageBitmap, fileName)
                                if (result.first.isNotEmpty()) detectedApplicationDate = result.first
                                if (result.second > 0 && result.second > maxDefermentMonths) maxDefermentMonths = result.second
                            } catch (e: Exception) {
                                Log.e("FILE_PROCESS", "상환내역서 Claude AI 실패 ($fileName): ${e.message}")
                            }
                            // ★ Claude가 유예기간 0이면 ML Kit OCR 폴백
                            if (maxDefermentMonths <= 0) {
                                try {
                                    val image = InputImage.fromBitmap(imageBitmap, 0)
                                    val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                                    val ocrResult = Tasks.await(client.process(image))
                                    val ocrText = ocrResult.text
                                    val ocrNoSpace = ocrText.replace(" ", "")
                                    if (ocrNoSpace.contains("유예기간") || ocrNoSpace.contains("거치기간")) {
                                        val monthMatch = Regex("(?:유예기간|거치기간)\\s*(\\d+)\\s*개?월").find(ocrText)
                                            ?: Regex("(?:유예기간|거치기간)[^\\d]{0,10}(\\d+)\\s*개?월").find(ocrText)
                                        if (monthMatch != null) {
                                            val months = monthMatch.groupValues[1].toInt()
                                            if (months > maxDefermentMonths) maxDefermentMonths = months
                                            Log.d("FILE_PROCESS", "상환내역서 이미지 OCR 폴백 유예기간: ${months}개월 ($fileName)")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("FILE_PROCESS", "상환내역서 이미지 OCR 폴백 실패 ($fileName): ${e.message}")
                                }
                            }
                            runOnUiThread {
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                        }.start()
                    } else if (isRecoveryPlanImg) {
                        Thread {
                            try {
                                callClaudeVisionForRecoveryPlan(arrayListOf(imageBitmap), fileName)
                            } catch (e: Exception) {
                                Log.e("FILE_PROCESS", "변제계획안 Claude AI 실패 ($fileName): ${e.message}")
                            }
                            runOnUiThread {
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                        }.start()
                    } else {
                        val image = InputImage.fromBitmap(imageBitmap, 0)
                        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                        client.process(image)
                            .addOnSuccessListener { visionText ->
                                val ocrText = visionText.text
                                Log.d("FILE_PROCESS", "이미지 OCR ($fileName): ${ocrText.take(500)}")
                                val ocrNoSpace = ocrText.replace(" ", "")
                                if (ocrNoSpace.contains("유예기간") || ocrNoSpace.contains("거치기간")) {
                                    val monthMatch = Regex("(?:유예기간|거치기간)\\s*(\\d+)\\s*개?월").find(ocrText)
                                        ?: Regex("(?:유예기간|거치기간)[^\\d]{0,10}(\\d+)\\s*개?월").find(ocrText)
                                    if (monthMatch != null) {
                                        val months = monthMatch.groupValues[1].toInt()
                                        if (months > maxDefermentMonths) maxDefermentMonths = months
                                    }
                                }
                                if (ocrNoSpace.contains("신청일자") && detectedApplicationDate.isEmpty()) {
                                    val appDateMatch = Regex("신청일자\\s*(\\d{4})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})").find(ocrText)
                                    if (appDateMatch != null) {
                                        detectedApplicationDate = "${appDateMatch.groupValues[1]}.${appDateMatch.groupValues[2].padStart(2, '0')}.${appDateMatch.groupValues[3].padStart(2, '0')}"
                                    }
                                }
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                            .addOnFailureListener { e ->
                                Log.e("FILE_PROCESS", "이미지 OCR 실패 ($fileName): ${e.message}")
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                    }
                    continue  // 다음 파일로
                }

                // ===== PDF 파일 처리 =====
                val tempFile = File.createTempFile("pdf_", ".pdf", cacheDir)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                val fd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fd)

                val pageCount = renderer.pageCount
                if (pageCount > 0) {
                    val isRepayment = fileName.lowercase().contains("상환")
                    val isAgreementPdf = fileName.lowercase().contains("합의")
                    val isRecoveryPlanPdf = fileName.lowercase().let { it.contains("변제계획") || it.contains("변제예정") }

                    // ===== 합의서 이미지 PDF: Claude AI로 테이블 추출 =====
                    if (isAgreementPdf) {
                        // 전체 페이지 렌더링 (최대 10페이지, OOM 방지)
                        val neededPages = (0 until minOf(pageCount, 10)).toMutableList()
                        val visionPages = ArrayList<Bitmap>()
                        for (i in neededPages) {
                            val p = renderer.openPage(i)
                            val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            p.close()
                            visionPages.add(bmp)
                        }
                        renderer.close(); fd.close(); tempFile.delete()
                        Log.d("FILE_PROCESS", "합의서 PDF→이미지 변환: $fileName ${neededPages.size}/${pageCount}페이지")

                        Thread {
                            try {
                                callClaudeVisionForAgreement(visionPages, fileName)
                            } catch (e: Exception) {
                                Log.e("FILE_PROCESS", "합의서 Claude AI 실패 ($fileName): ${e.message}")
                            }
                            // Claude 완료 후 remaining 감소 (UI 스레드에서)
                            runOnUiThread {
                                Log.d("FILE_PROCESS", "합의서 Claude 완료 → remaining 감소 ($fileName)")
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                        }.start()
                        continue  // 다음 PDF로
                    }

                    // ===== 변제계획안 이미지 PDF: Claude AI로 대상채무/가용소득 추출 =====
                    if (isRecoveryPlanPdf) {
                        val neededPages = (0 until minOf(pageCount, 10)).toMutableList()
                        val visionPages = ArrayList<Bitmap>()
                        for (i in neededPages) {
                            val p = renderer.openPage(i)
                            val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            p.close()
                            visionPages.add(bmp)
                        }
                        renderer.close(); fd.close(); tempFile.delete()
                        Log.d("FILE_PROCESS", "변제계획안 PDF→이미지 변환: $fileName ${neededPages.size}/${pageCount}페이지")

                        Thread {
                            try {
                                callClaudeVisionForRecoveryPlan(visionPages, fileName)
                            } catch (e: Exception) {
                                Log.e("FILE_PROCESS", "변제계획안 Claude AI 실패 ($fileName): ${e.message}")
                            }
                            runOnUiThread {
                                Log.d("FILE_PROCESS", "변제계획안 Claude 완료 → remaining 감소 ($fileName)")
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                        }.start()
                        continue  // 다음 PDF로
                    }

                    if (isRepayment) {
                        // ===== 상환내역서: 전체 페이지 렌더링 (Claude Vision + OCR 폴백) =====
                        val repaymentBitmaps = ArrayList<Bitmap>()
                        for (i in 0 until minOf(pageCount, 10)) {
                            val p = renderer.openPage(i)
                            val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            p.close()
                            repaymentBitmaps.add(bmp)
                        }
                        renderer.close(); fd.close(); tempFile.delete()
                        Log.d("FILE_PROCESS", "상환내역서 PDF→이미지 변환: $fileName ${repaymentBitmaps.size}/${pageCount}페이지")

                        Thread {
                            try {
                                val result = callClaudeVisionForRepayment(repaymentBitmaps[0], fileName)
                                if (result.first.isNotEmpty()) detectedApplicationDate = result.first
                                if (result.second > 0 && result.second > maxDefermentMonths) maxDefermentMonths = result.second
                            } catch (e: Exception) {
                                Log.e("FILE_PROCESS", "상환내역서 Claude AI 실패 ($fileName): ${e.message}")
                            }
                            // ★ Claude가 유예기간 0이면 ML Kit OCR 폴백 (전체 페이지)
                            if (maxDefermentMonths <= 0) {
                                for ((idx, bmp) in repaymentBitmaps.withIndex()) {
                                    try {
                                        val image = InputImage.fromBitmap(bmp, 0)
                                        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                                        val ocrResult = Tasks.await(client.process(image))
                                        val ocrText = ocrResult.text
                                        val ocrNoSpace = ocrText.replace(" ", "")
                                        if (ocrNoSpace.contains("유예기간") || ocrNoSpace.contains("거치기간")) {
                                            val monthMatch = Regex("(?:유예기간|거치기간)\\s*(\\d+)\\s*개?월").find(ocrText)
                                                ?: Regex("(?:유예기간|거치기간)[^\\d]{0,10}(\\d+)\\s*개?월").find(ocrText)
                                            if (monthMatch != null) {
                                                val months = monthMatch.groupValues[1].toInt()
                                                if (months > maxDefermentMonths) maxDefermentMonths = months
                                                Log.d("FILE_PROCESS", "상환내역서 OCR 폴백 유예기간: ${months}개월 p${idx+1} ($fileName)")
                                            }
                                        }
                                        if (maxDefermentMonths > 0) break  // 찾으면 중단
                                    } catch (e: Exception) {
                                        Log.e("FILE_PROCESS", "상환내역서 OCR 폴백 실패 p${idx+1} ($fileName): ${e.message}")
                                    }
                                }
                            }
                            runOnUiThread {
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                        }.start()
                    } else {
                        // ===== 기타 PDF: ML Kit OCR로 유예기간 추출 =====
                        val page = renderer.openPage(0)
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        renderer.close()
                        fd.close()
                        tempFile.delete()

                        Log.d("FILE_PROCESS", "PDF→이미지 변환: $fileName p1/${pageCount} (${bitmap.width}x${bitmap.height})")

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
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                            .addOnFailureListener { e ->
                                Log.e("FILE_PROCESS", "PDF OCR 실패 ($fileName): ${e.message}")
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                    }
                } else {
                    Log.d("FILE_PROCESS", "PDF 페이지 없음: $fileName")
                    renderer.close()
                    fd.close()
                    tempFile.delete()
                    remaining--
                    if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                }
            } catch (e: Exception) {
                Log.e("FILE_PROCESS", "PDF 이미지 변환 실패 ($fileName): ${e.message}")
                remaining--
                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
            }
        }
    }


    /**
     * 상환내역서 PDF 이미지를 Claude AI 비전으로 분석하여 신청일자, 유예기간 추출
     * @return Pair(신청일자 YYYY.MM.DD, 유예기간 개월)
     */
    private fun callClaudeVisionForRepayment(bitmap: Bitmap, fileName: String): Pair<String, Int> {
        val prompt = """이 PDF 이미지는 채무조정 상환내역서입니다. 다음 정보를 추출하세요.

[1] 신청일자
- 표에서 "신청일자" 라벨에 해당하는 값을 찾으세요
- 테이블 구조에서 라벨과 값이 다른 셀에 있을 수 있습니다
- YYYY.MM.DD 형식으로 응답 (예: 2024.08.14)
- 없으면 빈 문자열 ""

[2] 유예기간 (거치기간)
- "유예기간" 또는 "거치기간"이라는 텍스트 바로 뒤의 개월 수를 추출
- 예: "(유예기간 18개월 중 12회 납입)" → 18
- 예: "유예기간 6개월" → 6
- 주의: "총 138개월 중 24회 납입" 같은 납입회차/총기간은 유예기간이 아닙니다!
- "유예기간" 또는 "거치기간"이라는 단어가 이미지에 없으면 반드시 0

반드시 JSON만 응답:
{"applicationDate": "YYYY.MM.DD", "defermentMonths": 숫자}"""

        val aiText = callClaudeVisionApi(prompt, listOf(bitmap), fileName, "상환내역서")
        if (aiText.isEmpty()) return Pair("", 0)

        val jsonStr = aiText.replace(Regex("```json\\s*"), "").replace(Regex("```\\s*"), "").trim()
        val jsonStart = jsonStr.indexOf("{")
        val jsonEnd = jsonStr.lastIndexOf("}")
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            Log.e("FILE_PROCESS", "상환내역서 Claude JSON 파싱 실패: $jsonStr")
            return Pair("", 0)
        }

        val data = JSONObject(jsonStr.substring(jsonStart, jsonEnd + 1))
        val applicationDate = data.optString("applicationDate", "")
        val defermentMonths = data.optInt("defermentMonths", 0)

        Log.d("FILE_PROCESS", "상환내역서 Claude 결과: 신청일자=$applicationDate, 유예=${defermentMonths}개월 ($fileName)")
        return Pair(applicationDate, defermentMonths)
    }

    private fun callClaudeVisionForAgreement(bitmaps: List<Bitmap>, fileName: String) {
        val prompt = """이 이미지들은 채무조정 체결합의서 PDF의 각 페이지입니다. 총 ${bitmaps.size}페이지입니다.

추출할 정보:

[1] 제도 타입 (첫 페이지 상단 제목)
- "신속채무조정" → "신", "사전채무조정" → "프", "개인워크아웃"/"개인채무조정 확정" → "워", 해당없음 → ""
- 주의: "신용회복위원회"는 기관명이므로 그 자체로는 제도 타입이 아님. 반드시 세부 제도명(신속채무조정/사전채무조정/개인워크아웃)을 찾아서 판단할 것
- processTitle: 문서의 "제 목" 항목 전체 원문 (예: "개인채무조정(사전채무조정) 확정 통지")

[2] "채무별 조정내역" 테이블 (조정 내용 섹션)
- 각 채권사별로 구분="전"(조정 전)과 구분="후"(조정 후) 두 행이 있음
- 반드시 구분="전" 행의 값만 사용할 것 ("후" 행은 무시)
- totalPrincipal: 맨 아래 합계 행에서 구분="전"인 행의 "원금" 값 (원 단위). "합계" 컬럼이 아닌 "원금" 컬럼의 값을 읽을 것
- creditors: 각 채권금융회사명 + 구분="전" 행의 "원금". 같은 채권사는 합산

[3] "■ 개인채무조정에서 제외된 채무내역" 또는 "■ 제외채무 내역" 테이블 ← 반드시 찾으세요!
- 이 테이블은 "채무별 조정내역"과 별도 페이지에 있음 (보통 후반부)
- "■ 개인채무조정에서 제외된 채무내역" 또는 "■ 제외채무 내역"이라는 ■ 마크가 있는 제목 아래에 있음
- 컬럼: 채권금융회사 | 대출과목 | 계좌번호 | 원금 | 이자 | 비용 | 제외사유
- 제외사유 예시: "개별상환(보증서 담보대출)", "개별상환(자동차 담보대출)", "소액 채무", "새출발기금 매입예정"
- ★ 단위 주의: 표 상단/하단의 "(단위: 원)" / "(단위: 천원)" / "(단위: 만원)" 표기를 반드시 확인.
  - 단위가 천원이면 → 표시값 × 1000 으로 변환 (예: 표에 "8,700" + 단위 천원 → 8700000원)
  - 단위가 만원이면 → 표시값 × 10000 으로 변환 (예: 표에 "870" + 단위 만원 → 8700000원)
  - 단위가 원이거나 단위 표기가 없으면 → 표시값 그대로 (예: 표에 "8,700,000" → 8700000원)
  - 단위가 모호하면 [2]번 totalPrincipal과 비교해서 단위 추정 (이 합의서의 totalPrincipal과 동일한 단위 사용)
- excludedCreditors: 각 행에서 추출 (reason 필드는 반드시 포함!)
  - name: 채권금융회사명
  - principal: 원금을 반드시 "원 단위"로 변환한 값 (천원/만원이면 변환 필수)
  - reason: 제외사유 컬럼의 값을 반드시 읽어서 변환. 이 필드는 필수!
    "자동차 담보" 포함 → "차량담보대출", "보증서 담보" 포함 → "보증서담보대출",
    "주택 담보"/"주택담보" 포함 → "주택담보대출", "현금서비스" 포함 → "현금서비스",
    "새출발기금 매입예정" → "새출발기금 매입예정" (그대로),
    그 외 → 제외사유 값 그대로 (예: "소액 채무", "완제")
- excludedDebtTotal: 제외 채무 원금 전체 합계 (원 단위)

[4] 유예기간: "유예기간"/"거치기간" (개월 수, 없으면 0)

[5] "협약 외 채무내역" / "협약 외 채권자" / "기타 협약외" / "협약외 채무" 테이블 ← [3]번과 완전히 다른 테이블!
★ [3]번과의 차이 (절대 혼동 금지):
  - [3] excludedCreditors = "■ 개인채무조정에서 제외된 채무내역" (제외사유 컬럼이 있음, 신복위 채무 중 일부를 제외한 것)
  - [5] outsideCreditors = "협약 외 채무" (제외사유 컬럼 없음, 신복위와 무관한 제3자 채무 - 주로 대부업체)
  - 같은 채권사를 양쪽에 넣지 말 것! 한 채무는 둘 중 한 곳에만 들어감
  - 제외사유에 "개별상환"이 있으면 → 무조건 [3] excludedCreditors (outsideCreditors 아님!)
  - 채권사명에 "대부"/"기타협약외" 포함되거나 "협약 외" 섹션에만 있으면 → [5] outsideCreditors
- 위치: "■ 협약 외 채무내역" 같은 ■ 마크 또는 "협약 외" 섹션 제목 아래. 본문 후반부
- 컬럼명 변형: "협약 외 채권자" / "채권금융회사" / "채권자명" / "기타협약외 채권자", 금액 컬럼은 "원금" / "잔액"
- 행 형식: "1 [채권사명] [대출종류] [원금]" 또는 "기타협약외( 주식회사에스씨알대부 ) [원금]"
- ★ 채권자명 정제 (반드시 괄호 안의 진짜 채권사명 추출):
  - "기타협약외( 주식회사에스씨알대부 )" → "에스씨알대부" (절대 "기타협약외" 자체를 채권사명으로 쓰지 말 것!)
  - "(주)에이비대부" → "에이비대부"
  - 제거 대상 prefix: "기타협약외(", "기타협약외", "협약외(", "기티협약외" 등
  - 제거 대상 법인 표기: "주식회사", "유한회사", "(주)", "㈜", 양 끝 괄호와 공백
- ★ 단위 주의: [3]번과 동일 — 표의 "(단위: 원/천원/만원)" 표기 확인 후 반드시 원 단위로 변환
  - 단위 천원 → ×1000, 단위 만원 → ×10000, 단위 원 → 그대로
  - 단위가 모호하면 [2]번 totalPrincipal과 동일한 단위 사용
- outsideCreditors: 각 행에서 name(정제된 채권사명), principal(반드시 원 단위로 변환한 값) 추출
- "협약 외" 섹션이 진짜로 없을 때만 빈 배열. "협약 외" 단어가 본문에 한 번이라도 나오면 그 주변 테이블을 반드시 확인할 것

반드시 JSON만 응답 (마크다운 코드블록 없이):
{"processType": "신/프/워/빈문자열", "processTitle": "제목원문", "totalPrincipal": 숫자, "excludedDebtTotal": 숫자, "defermentMonths": 숫자, "creditors": [{"name": "채권사명", "principal": 숫자}], "excludedCreditors": [{"name": "채권사명", "principal": 숫자, "reason": "사유"}], "outsideCreditors": [{"name": "채권사명", "principal": 숫자}]}"""

        val aiText = callClaudeVisionApi(prompt, bitmaps, fileName, "합의서")
        if (aiText.isEmpty()) return

        val jsonStr = aiText.replace(Regex("```json\\s*"), "").replace(Regex("```\\s*"), "").trim()
        val jsonStart = jsonStr.indexOf("{")
        val jsonEnd = jsonStr.lastIndexOf("}")
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            Log.e("FILE_PROCESS", "합의서 Claude JSON 파싱 실패: $jsonStr")
            return
        }

        val data = JSONObject(jsonStr.substring(jsonStart, jsonEnd + 1))
        val processType = data.optString("processType", "")
        val processTitle = data.optString("processTitle", "")
        val totalPrincipal = data.optLong("totalPrincipal", 0L)
        Log.d("FILE_PROCESS", "합의서 합계 조정전 원금: ${totalPrincipal} (${totalPrincipal / 10000}만)")
        val excludedDebtTotal = data.optLong("excludedDebtTotal", 0L)
        val deferMonths = data.optInt("defermentMonths", 0)

        // 제목의 괄호 안 세부 제도명 우선 추출 (예: "개인채무조정(사전채무조정) 확정 통지" → "사전채무조정")
        val parenContent = Regex("\\(([^)]+)\\)").find(processTitle)?.groupValues?.get(1) ?: ""
        val titleKey = if (parenContent.isNotEmpty()) parenContent else processTitle
        val detectedProcess = when {
            titleKey.contains("새출발기금") -> "새"
            titleKey.contains("신속") -> "신"
            titleKey.contains("사전") -> "프"
            titleKey.contains("개인") -> "워"
            processType.isNotEmpty() -> processType
            else -> ""
        }
        Log.d("FILE_PROCESS", "합의서 Claude 제목 원문: '$processTitle' → 제도='$detectedProcess' (Claude판단='$processType')")
        if (detectedProcess.isNotEmpty()) pdfAgreementProcess = detectedProcess
        if (totalPrincipal > 0) pdfAgreementDebt = (totalPrincipal / 10000).toInt()
        if (deferMonths > aiDefermentMonths) aiDefermentMonths = deferMonths

        // 채권사 목록 파싱 (조정내역 + 제외채무)
        pdfAgreementCreditors.clear()
        val creditorsArr = data.optJSONArray("creditors")
        if (creditorsArr != null) {
            for (i in 0 until creditorsArr.length()) {
                val c = creditorsArr.optJSONObject(i) ?: continue
                val cName = c.optString("name", "").trim()
                val cPrincipal = (c.optLong("principal", 0L) / 10000).toInt()
                if (cName.length >= 2 && cPrincipal > 0) {
                    pdfAgreementCreditors[cName] = (pdfAgreementCreditors[cName] ?: 0) + cPrincipal
                }
            }
        }
        val excludedCreditorsArr = data.optJSONArray("excludedCreditors")
        var excludedDamboTotal = 0
        var excludedGuaranteeTotal = 0
        if (excludedCreditorsArr != null) {
            for (i in 0 until excludedCreditorsArr.length()) {
                val c = excludedCreditorsArr.optJSONObject(i) ?: continue
                val cName = c.optString("name", "").trim()
                val cPrincipal = (c.optLong("principal", 0L) / 10000).toInt()
                val reason = c.optString("reason", "").trim()
                if (cPrincipal > 0) {
                    // 현금서비스는 채무에 포함하지 않음
                    if (reason.contains("현금서비스")) {
                        Log.d("FILE_PROCESS", "제외 채무 스킵(현금서비스): ${cPrincipal}만 (사유=$reason)")
                        continue
                    }
                    // 새출발기금 매입예정 → 대상채무에 포함
                    if (reason.contains("새출발기금") && reason.contains("매입")) {
                        pdfAgreementDebt += cPrincipal
                        if (cName.length >= 2) pdfAgreementCreditors[cName] = (pdfAgreementCreditors[cName] ?: 0) + cPrincipal
                        Log.d("FILE_PROCESS", "제외→대상채무 전환(새출발기금 매입예정): ${cPrincipal}만 ($cName, 사유=$reason)")
                        continue
                    }
                    // 보증서담보대출만 대상채무, 차량/주택/기타 담보 → 담보
                    val isDamboType = reason.contains("차량") || reason.contains("자동차") || reason.contains("주택") || reason.contains("할부") || reason.contains("신용보험")
                    val isGuarantee = !isDamboType && (reason.contains("보증서") || reason.contains("지급보증"))
                    if (isGuarantee) {
                        excludedGuaranteeTotal += cPrincipal
                    } else {
                        excludedDamboTotal += cPrincipal
                        if (cName.length >= 2) pdfExcludedDamboCreditors.add(cName)
                    }
                    // 개별상환 채무 → 제외 목록에 저장 (표시용)
                    if (reason.contains("개별상환")) {
                        pdfExcludedEntries.add(PdfExcludedEntry(i + 1, cName, cPrincipal, reason, isDamboType))
                    }
                    Log.d("FILE_PROCESS", "제외 채무: ${cPrincipal}만 (사유=$reason, 보증서=${isGuarantee})")
                }
            }
        }
        pdfExcludedGuaranteeDebt = excludedGuaranteeTotal
        pdfExcludedOtherDebt = excludedDamboTotal

        // 협약 외 채무내역 파싱
        pdfOutsideEntries.clear()
        val outsideCreditorsArr = data.optJSONArray("outsideCreditors")
        if (outsideCreditorsArr != null) {
            for (i in 0 until outsideCreditorsArr.length()) {
                val c = outsideCreditorsArr.optJSONObject(i) ?: continue
                val cName = c.optString("name", "").trim()
                val cPrincipal = (c.optLong("principal", 0L) / 10000).toInt()
                if (cName.length >= 2 && cPrincipal > 0) {
                    pdfOutsideEntries.add(PdfOutsideEntry(cName, cPrincipal))
                    Log.d("FILE_PROCESS", "협약 외 채무: $cName ${cPrincipal}만 (담보/신용은 한글파일에서 판단)")
                }
            }
            if (pdfOutsideEntries.isNotEmpty()) {
                Log.d("FILE_PROCESS", "협약 외 채무 합계: ${pdfOutsideEntries.sumOf { it.principal }}만 (${pdfOutsideEntries.size}건)")
            }
        }

        Log.d("FILE_PROCESS", "합의서 채권사: ${pdfAgreementCreditors.size}건 $pdfAgreementCreditors")

        Log.d("FILE_PROCESS", "합의서 대상채무: ${pdfAgreementDebt}만 (채권사 ${pdfAgreementCreditors.size}건)")

        // 제외채무가 비어있으면 → 페이지를 나눠서 최대 3번 재시도
        if ((excludedCreditorsArr == null || excludedCreditorsArr.length() == 0) && bitmaps.size >= 2) {
            // 페이지를 3~4장씩 나눠서 시도 (10장 한꺼번에 보내면 제외채무 테이블을 못 찾는 경우 방지)
            val pageBatches = mutableListOf<List<Bitmap>>()
            val batchSize = 4
            for (start in 0 until bitmaps.size step (batchSize - 1).coerceAtLeast(1)) {
                val end = minOf(start + batchSize, bitmaps.size)
                pageBatches.add(bitmaps.subList(start, end))
            }

            for (retryNum in 1..pageBatches.size.coerceAtMost(3)) {
                val batch = pageBatches[retryNum - 1]
                val excludedPrompt = """이 이미지들은 채무조정 체결합의서 PDF의 일부 페이지(${batch.size}장)입니다.

"■ 개인채무조정에서 제외된 채무내역" 또는 "■ 제외채무 내역"이라는 ■ 마크가 있는 제목의 테이블을 찾으세요.
이 테이블은 "채무별 조정내역"과는 다른 별도 테이블입니다.

이 테이블의 컬럼: 채권금융회사 | 대출과목 | 계좌번호 | 원금 | 이자 | 비용 | 제외사유
제외사유 예시: "개별상환(보증서 담보대출)", "개별상환(자동차 담보대출)", "소액 채무", "새출발기금 매입예정"

각 행에서 추출:
- name: 채권금융회사명
- principal: 원금 (원 단위 숫자)
- reason: 제외사유의 괄호 안 내용 기준으로:
  "자동차 담보" 포함 → "차량담보대출", "보증서 담보" 포함 → "보증서담보대출",
  "주택담보" 포함 → "주택담보대출", "현금서비스" 포함 → "현금서비스",
  "새출발기금 매입예정" → "새출발기금 매입예정" (그대로),
  그 외 → 제외사유 값 그대로

테이블이 없으면 빈 배열을 응답하세요.
반드시 JSON만 응답 (마크다운 코드블록 없이):
{"excludedCreditors": [{"name": "채권사명", "principal": 숫자, "reason": "사유"}]}"""

                Log.d("FILE_PROCESS", "합의서 제외채무 비어있음 → 페이지배치 ${retryNum}/${pageBatches.size.coerceAtMost(3)} (${batch.size}장) ($fileName)")
                val retryText = callClaudeVisionApi(excludedPrompt, batch, fileName, "제외채무(${retryNum}차)")
                if (retryText.isEmpty()) continue
                val retryJson = retryText.replace(Regex("```json\\s*"), "").replace(Regex("```\\s*"), "").trim()
                val rStart = retryJson.indexOf("{")
                val rEnd = retryJson.lastIndexOf("}")
                if (rStart == -1 || rEnd <= rStart) continue
                val retryData = JSONObject(retryJson.substring(rStart, rEnd + 1))
                val retryArr = retryData.optJSONArray("excludedCreditors")
                if (retryArr != null && retryArr.length() > 0) {
                    Log.d("FILE_PROCESS", "제외채무 ${retryNum}차 추출 성공: ${retryArr.length()}건")
                    for (i in 0 until retryArr.length()) {
                        val c = retryArr.optJSONObject(i) ?: continue
                        val cName = c.optString("name", "").trim()
                        val cPrincipal = (c.optLong("principal", 0L) / 10000).toInt()
                        val reason = c.optString("reason", "").trim()
                        if (cPrincipal > 0) {
                            if (reason.contains("현금서비스")) {
                                Log.d("FILE_PROCESS", "제외 채무 스킵(현금서비스): ${cPrincipal}만 (사유=$reason)")
                                continue
                            }
                            // 새출발기금 매입예정 → 대상채무에 포함
                            if (reason.contains("새출발기금") && reason.contains("매입")) {
                                pdfAgreementDebt += cPrincipal
                                if (cName.length >= 2) pdfAgreementCreditors[cName] = (pdfAgreementCreditors[cName] ?: 0) + cPrincipal
                                Log.d("FILE_PROCESS", "제외→대상채무 전환(${retryNum}차, 새출발기금 매입예정): ${cPrincipal}만 ($cName, 사유=$reason)")
                                continue
                            }
                            val isDamboType = reason.contains("차량") || reason.contains("자동차") || reason.contains("주택") || reason.contains("할부")
                            val isGuarantee = !isDamboType && (reason.contains("보증서") || reason.contains("지급보증"))
                            if (isGuarantee) {
                                excludedGuaranteeTotal += cPrincipal
                            } else {
                                excludedDamboTotal += cPrincipal
                                if (cName.length >= 2) pdfExcludedDamboCreditors.add(cName)
                            }
                            if (reason.contains("개별상환")) {
                                pdfExcludedEntries.add(PdfExcludedEntry(i + 1, cName, cPrincipal, reason, isDamboType))
                            }
                            Log.d("FILE_PROCESS", "제외 채무(${retryNum}차): ${cPrincipal}만 (사유=$reason, 보증서=${isGuarantee})")
                        }
                    }
                    pdfExcludedGuaranteeDebt = excludedGuaranteeTotal
                    pdfExcludedOtherDebt = excludedDamboTotal
                    break  // 성공하면 루프 종료
                } else {
                    Log.d("FILE_PROCESS", "제외채무 ${retryNum}차 추출: 테이블 없음 ($fileName)")
                }
            }
        }

        // 협약 외 채무가 비어있으면 → 페이지를 나눠서 최대 3번 재시도 (전담 호출)
        if (pdfOutsideEntries.isEmpty() && bitmaps.size >= 2) {
            val pageBatches = mutableListOf<List<Bitmap>>()
            val batchSize = 4
            for (start in 0 until bitmaps.size step (batchSize - 1).coerceAtLeast(1)) {
                val end = minOf(start + batchSize, bitmaps.size)
                pageBatches.add(bitmaps.subList(start, end))
            }

            for (retryNum in 1..pageBatches.size.coerceAtMost(3)) {
                val batch = pageBatches[retryNum - 1]
                val outsidePrompt = """이 이미지들은 채무조정 체결합의서 PDF의 일부 페이지(${batch.size}장)입니다.

"■ 협약 외 채무내역" 또는 "협약 외 채권자" 라는 제목의 테이블을 찾으세요.
이 테이블은 "■ 개인채무조정에서 제외된 채무내역"과는 완전히 다른 별도 테이블입니다.

이 테이블의 컬럼: 협약외 채권자 | 원금 | 비고
표 상단에 "(단위: 원)"이 표기되어 있으면 원금은 그대로 원 단위 숫자

★ 숫자 읽기 주의:
- "70,000,000" → 70000000 (콤마 무시하고 모든 숫자 그대로)
- "8,700,000" → 8700000
- 콤마 뒤 숫자를 절대 누락하지 말 것

각 행에서 추출:
- name: ★ 반드시 괄호 안의 진짜 채권사명만 추출 (절대 "기타협약외"를 채권사명으로 쓰지 말 것!)
  - "기타협약외( 주식회사에스씨알대부 )" → "에스씨알대부"
  - 제거 대상: "기타협약외(", "기타협약외", "협약외(", "기티협약외" 등 prefix
  - 제거 대상: "주식회사", "유한회사", "(주)", "㈜" 등 법인 표기
  - 양 끝 괄호와 공백 제거
- principal: 원금 (콤마 무시한 전체 원 단위 숫자)

★ 절대 [개인채무조정에서 제외된 채무내역] 테이블의 행을 읽지 말 것!
   - 그 테이블에는 "제외사유" 컬럼이 있고, 이 협약외 테이블에는 없음
   - 그 테이블의 채권사는 일반 은행/저축은행, 협약외 테이블은 주로 "대부업체"

테이블이 없으면 빈 배열을 응답하세요.
반드시 JSON만 응답 (마크다운 코드블록 없이):
{"outsideCreditors": [{"name": "채권사명", "principal": 숫자}]}"""

                Log.d("FILE_PROCESS", "합의서 협약외채무 비어있음 → 페이지배치 ${retryNum}/${pageBatches.size.coerceAtMost(3)} (${batch.size}장) ($fileName)")
                val retryText = callClaudeVisionApi(outsidePrompt, batch, fileName, "협약외채무(${retryNum}차)")
                if (retryText.isEmpty()) continue
                val retryJson = retryText.replace(Regex("```json\\s*"), "").replace(Regex("```\\s*"), "").trim()
                val rStart = retryJson.indexOf("{")
                val rEnd = retryJson.lastIndexOf("}")
                if (rStart == -1 || rEnd <= rStart) continue
                val retryData = JSONObject(retryJson.substring(rStart, rEnd + 1))
                val retryArr = retryData.optJSONArray("outsideCreditors")
                if (retryArr != null && retryArr.length() > 0) {
                    Log.d("FILE_PROCESS", "협약외채무 ${retryNum}차 추출 성공: ${retryArr.length()}건")
                    for (i in 0 until retryArr.length()) {
                        val c = retryArr.optJSONObject(i) ?: continue
                        val cName = c.optString("name", "").trim()
                        val cPrincipal = (c.optLong("principal", 0L) / 10000).toInt()
                        if (cName.length >= 2 && cPrincipal > 0) {
                            pdfOutsideEntries.add(PdfOutsideEntry(cName, cPrincipal))
                            Log.d("FILE_PROCESS", "협약 외 채무(${retryNum}차): $cName ${cPrincipal}만")
                        }
                    }
                    if (pdfOutsideEntries.isNotEmpty()) {
                        Log.d("FILE_PROCESS", "협약 외 채무 합계: ${pdfOutsideEntries.sumOf { it.principal }}만 (${pdfOutsideEntries.size}건)")
                        break
                    }
                } else {
                    Log.d("FILE_PROCESS", "협약외채무 ${retryNum}차 추출: 테이블 없음 ($fileName)")
                }
            }
        }

        Log.d("FILE_PROCESS", "합의서 Claude 결과: 제도=$processType, 대상채무=${pdfAgreementDebt}만, 제외채무=${pdfExcludedOtherDebt}만, 제외보증서=${pdfExcludedGuaranteeDebt}만, 유예=${deferMonths}개월, 채권사=${pdfAgreementCreditors.size}건 ($fileName)")
    }

    // ============= 변제계획안 Claude Vision =============
    private fun callClaudeVisionForRecoveryPlan(bitmaps: List<Bitmap>, fileName: String) {
        val prompt = """이 PDF 이미지들은 개인회생 변제계획안입니다. 다음 정보를 추출하세요.

[1] 대상채무 (원 단위) = 개인회생채권액
- "변제예정액" 또는 "채권자별 변제예정액의 산정내역" 테이블에서 "합계" 행의 왼쪽 큰 금액
- 컬럼명이 (D) "개인회생채권액" 확정채권액
- 예: 합계 행 | 64,437,772 | 0 | 100,010 | 0 | 3,600,360 | 0 → 64,437,772를 추출 (왼쪽 큰 금액)
- 주의: (F)/(P)총변제예정액(오른쪽 금액)이 아닌 (D)개인회생채권액(왼쪽 큰 금액)을 추출

[2] 총변제예정액 (원 단위)
- 합계 행의 오른쪽 큰 금액, 컬럼명이 (F) 또는 (P) "총 변제예정(유보)액" 확정채권액
- 예: 합계 행 | 64,437,772 | 0 | 100,010 | 0 | 3,600,360 | 0 → 3,600,360을 추출

[3] 변제기간 (개월 수)
- "변제기간" 섹션에서 "XX개월간" 값
- 또는 "⑥ 변제 횟수" 값
- 또는 제목의 "제1회 ~ 제XX회분" 에서 XX

[4] 채권자 목록 (creditors 배열)
- 변제예정액 테이블의 각 행(채권번호 1,2,3,...)에서 채권자명과 개인회생채권액(왼쪽 큰 금액) 추출
- 합계/총계 행은 제외
- 채권자명은 원문 그대로 (예: "예스자산대부(주)", "(재)신용보증재단중앙회", "(주)한빛자산관리대부")
- 금액은 (D)개인회생채권액 확정채권액(왼쪽 큰 금액, 원 단위)

반드시 JSON만 응답:
{"totalDebt": 원단위숫자, "totalRepayment": 총변제예정액원단위숫자, "repaymentMonths": 개월수숫자, "creditors": [{"name": "채권자명", "amount": 원단위숫자}, ...]}"""

        val aiText = callClaudeVisionApi(prompt, bitmaps, fileName, "변제계획안")
        if (aiText.isEmpty()) return

        val jsonStr = aiText.replace(Regex("```json\\s*"), "").replace(Regex("```\\s*"), "").trim()
        val jsonStart = jsonStr.indexOf("{")
        val jsonEnd = jsonStr.lastIndexOf("}")
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            Log.e("FILE_PROCESS", "변제계획안 Claude JSON 파싱 실패: $jsonStr")
            return
        }

        val data = JSONObject(jsonStr.substring(jsonStart, jsonEnd + 1))
        val totalDebt = data.optLong("totalDebt", 0L)
        val totalRepayment = data.optLong("totalRepayment", 0L)
        val repaymentMonths = data.optInt("repaymentMonths", 0)

        aiHasRecoveryPlan = true
        if (totalDebt > 10000) {
            pdfRecoveryDebt = (totalDebt / 10000).toInt()
        }
        if (repaymentMonths > 0) {
            pdfRecoveryMonths = repaymentMonths
        }
        if (totalRepayment > 10000 && pdfRecoveryMonths > 0) {
            pdfRecoveryIncome = (totalRepayment / 10000 / pdfRecoveryMonths).toInt()
        }

        // 채권자 목록 파싱 (대부/미협약 판단용)
        val creditorsArr = data.optJSONArray("creditors")
        if (creditorsArr != null) {
            for (i in 0 until creditorsArr.length()) {
                val c = creditorsArr.optJSONObject(i) ?: continue
                val name = c.optString("name", "").trim()
                val amountWon = c.optLong("amount", 0L)
                if (name.length >= 2 && amountWon > 0) {
                    val amountMan = ((amountWon + 5000) / 10000).toInt()
                    pdfRecoveryCreditors[name] = (pdfRecoveryCreditors[name] ?: 0) + amountMan
                }
            }
        }

        Log.d("FILE_PROCESS", "변제계획안 Claude 결과: 대상채무=${pdfRecoveryDebt}만, 총변제예정액=${totalRepayment / 10000}만, 월변제금=${pdfRecoveryIncome}만, 변제기간=${pdfRecoveryMonths}개월, 채권사=${pdfRecoveryCreditors.size}건 ($fileName)")
    }

    // ============= Claude Vision API 공통 호출 =============
    private fun callClaudeVisionApi(prompt: String, bitmaps: List<Bitmap>, fileName: String, docType: String): String {
        val apiKey = BuildConfig.CLAUDE_API_KEY
        val apiUrl = "https://api.anthropic.com/v1/messages"

        // content 배열: 이미지들 + 텍스트 프롬프트
        val contentArr = JSONArray()
        for (bmp in bitmaps) {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val imageBytes = baos.toByteArray()
            val base64Str = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
            val source = JSONObject()
                .put("type", "base64")
                .put("media_type", "image/jpeg")
                .put("data", base64Str)
            contentArr.put(JSONObject().put("type", "image").put("source", source))
        }
        contentArr.put(JSONObject().put("type", "text").put("text", prompt))
        Log.d("FILE_PROCESS", "$docType Claude: ${bitmaps.size}페이지 전송 ($fileName)")

        val message = JSONObject()
            .put("role", "user")
            .put("content", contentArr)
        val requestBody = JSONObject()
            .put("model", "claude-opus-4-20250514")
            .put("max_tokens", 4096)
            .put("system", "당신은 금융 문서 분석 전문가입니다. 여러 페이지의 이미지가 주어지면 모든 페이지를 빠짐없이 꼼꼼하게 확인합니다. 특히 문서 후반부에 있는 테이블도 반드시 확인합니다. JSON 형식으로만 응답합니다.")
            .put("messages", JSONArray().put(message))

        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
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
            Log.e("FILE_PROCESS", "$docType Claude API 오류 ($responseCode): $errorBody")
            return ""
        }

        val response = BufferedReader(
            InputStreamReader(conn.inputStream, Charsets.UTF_8)
        ).use { it.readText() }

        val jsonResponse = JSONObject(response)
        var aiText = ""
        try {
            val contentArray = jsonResponse.getJSONArray("content")
            for (i in 0 until contentArray.length()) {
                val block = contentArray.getJSONObject(i)
                if (block.optString("type") == "text") {
                    aiText = block.getString("text")
                }
            }
        } catch (e: Exception) {
            Log.e("FILE_PROCESS", "$docType Claude 응답 파싱 실패: ${e.message}")
            return ""
        }

        Log.d("FILE_PROCESS", "$docType Claude AI 응답: $aiText")
        return aiText
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
                                for ((rowIdx, row) in table.rowList.withIndex()) {
                                    val cellTexts = mutableListOf<String>()
                                    for (cell in row.cellList) {
                                        val cellText = StringBuilder()
                                        for (cellPara in cell.paragraphList) {
                                            val text = extractParagraphText(cellPara)
                                            if (text.isNotBlank()) cellText.append(text.trim()).append(" ")
                                        }
                                        cellTexts.add(cellText.toString().trim())
                                    }
                                    // 빈 셀도 탭 구분 유지 (열 인덱스 보존)
                                    val rowStr = cellTexts.joinToString("\t")
                                    val hasContent = cellTexts.any { it.isNotEmpty() }
                                    if (hasContent) {
                                        sb.append(rowStr).append("\n")
                                        Log.d("HWP_EXTRACT", "표 행[$rowIdx]: $rowStr")
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
        parseHwpData(text)
        showToast("HWP 파일 읽기 완료")
    }

    // 사이드바이사이드 표 분리 전처리 (채무현황/카드이용금액/대출과목/기타채무가 탭으로 합쳐진 경우)
    // 분리 후 표별로 그룹하여 출력 (채무현황 전체 → 카드이용금액 전체 → 대출과목 전체 → ...)
    private fun splitMergedTableLines(rawLines: List<String>): List<String> {
        val result = mutableListOf<String>()
        var splitIndices = listOf<Int>()
        val mergedRows = mutableListOf<List<String>>()

        for (l in rawLines) {
            val parts = l.split("\t")

            // 매 라인에서 섹션 경계 감지 (타이틀/헤더 모두 감지)
            val detected = detectTableBoundaries(parts)
            if (detected.size >= 2) {
                splitIndices = detected
                mergedRows.add(parts)
                Log.d("HWP_PRESPLIT", "병합표 감지: indices=$detected")
                continue
            }

            // 기존 splitIndices로 데이터 행 수집
            if (splitIndices.isNotEmpty() && parts.size > splitIndices.last()) {
                mergedRows.add(parts)
                continue
            }

            // 탭 수 부족 → 병합표 종료, 표별 그룹 출력
            if (mergedRows.isNotEmpty()) {
                flushMergedRows(mergedRows, splitIndices, result)
                mergedRows.clear()
                splitIndices = listOf()
                Log.d("HWP_PRESPLIT", "병합표 종료")
            }
            result.add(l)
        }

        // 남은 병합 행 flush
        if (mergedRows.isNotEmpty()) {
            flushMergedRows(mergedRows, splitIndices, result)
        }
        return result
    }

    // 수집된 병합 행을 표별로 그룹하여 출력 (표1 전체행 → 표2 전체행 → ...)
    private fun flushMergedRows(rows: List<List<String>>, indices: List<Int>, result: MutableList<String>) {
        for (s in indices.indices) {
            for (parts in rows) {
                val start = indices[s]
                if (start >= parts.size) continue
                val end = if (s < indices.lastIndex) minOf(indices[s + 1], parts.size) else parts.size
                val line = parts.subList(start, end).joinToString("\t")
                if (line.replace(Regex("\\s"), "").isNotEmpty()) {
                    result.add(line)
                }
            }
        }
    }

    // 탭 파트에서 알려진 표 섹션 경계 감지
    private fun detectTableBoundaries(parts: List<String>): List<Int> {
        if (parts.size < 3) return emptyList()

        val boundaries = mutableListOf<Int>()
        var lastSection = -1

        for (i in parts.indices) {
            val pns = parts[i].replace(Regex("\\s"), "")
            if (pns.isEmpty()) continue

            // 섹션 구분: 1=채무현황, 2=카드이용금액, 3=대출과목, 4=기타채무
            val section = when {
                pns.contains("채무현황") -> 1
                pns.contains("카드사") || pns.contains("이용금액") -> 2
                pns.contains("현황순번") || pns.contains("대출과목") -> 3
                pns.contains("기타채무") -> 4
                else -> -1
            }

            if (section > 0 && section != lastSection) {
                boundaries.add(i)
                lastSection = section
            }
        }

        // 첫 번째 경계가 0이 아니면 → 앞쪽 섹션이 index 0에서 시작
        if (boundaries.isNotEmpty() && boundaries.first() > 0) {
            boundaries.add(0, 0)
        }

        return if (boundaries.size >= 2) boundaries else emptyList()
    }

    // ============= 핵심 파싱 로직 (AI값 기반) =============
    private fun parseHwpData(text: String) {
        val lines = splitMergedTableLines(text.split("\n"))

        // 필드값 추출: "field : value" 또는 "field    value" 모두 지원
        fun extractValue(line: String, keyword: String): String {
            if (line.contains(":") || line.contains("：")) {
                return (line.substringAfter(":").takeIf { it != line }
                    ?: line.substringAfter("：")).trim()
            }
            // 키워드 문자를 공백 허용하여 매칭, 그 뒤 공백+값 추출
            val keyRegex = keyword.toList().joinToString("\\s*") { Regex.escape(it.toString()) }
            val match = Regex(keyRegex + "\\s+(.+)", RegexOption.DOT_MATCHES_ALL).find(line)
            return match?.groupValues?.get(1)?.trim() ?: line.trim()
        }

        // ============= 텍스트 파싱 기반 값 (AI 미사용) =============
        var income = 0
        var targetDebt = 0
        var netProperty = 0
        var taxDebt = 0
        var hasBusinessHistory = false
        var businessStartYear = 0
        var businessStartMonth = 0
        var businessEndYear = 0
        var hasDebtDuringBusiness = false  // 개업~폐업 기간 중 채무 존재 여부

        // 보조 정보 (텍스트에서 파싱)
        var name = ""
        var birthYear = 0  // 주민번호에서 추출한 출생년도
        var birthMonth = 1
        var birthDay = 1
        var region = ""
        var minorChildren = 0
        var collegeChildren = 0
        var parentCount = 0
        var hasSpouse = false
        var isDivorced = false
        var delinquentDays = 0
        var actualDelinquentDays = 0  // 실제 연체일수 (다른 단계 진행으로 인한 1095일 제외)
        var hasDischarge = false
        var isBankruptcyDischarge = false  // 파산 면책 여부 (두 면책 중 위험한 쪽이 파산인지)
        var dischargeYear = 0  // 대표 면책 년도 (표시/로직용, 아래 둘 중 위험한 쪽)
        var dischargeMonth = 0
        // 파산 면책과 회생(기타) 면책을 독립 추적 (5년 이내 판단 각각)
        var bankruptcyDischargeYear = 0
        var bankruptcyDischargeMonth = 0
        var recoveryDischargeYear = 0
        var recoveryDischargeMonth = 0
        var hasShinbokwiHistory = false
        var isBusinessOwner = false
        var isFreelancer = false
        var noSocialInsurance = false  // 4대보험 X → 소득*0.8
        var isNonProfit = false
        var isCorporateBusiness = false
        var hasSaechulbalBusilChaju = false  // 새출발 부실차주 여부 (새새 불가)
        var isFreelancerByNetIncome = false  // 순수익 비교로 인한 프리랜서 설정 (특이사항에 "프리랜서" 미표시)
        var hasBusinessLoan = false
        var hasGambling = false
        var hasGame = false  // 게임 (도박과 동일 로직, 표시만 구분)
        var hasStock = false
        var hasCrypto = false
        var hasDisability = false  // 장애등급 보유 여부
        var carValue = 0
        var carTotalSise = 0
        var carTotalLoan = 0
        var carMonthlyPayment = 0
        var carCount = 0
        var spouseCarSiseTotal = 0  // 배우자명의 차량 시세 합계 (표시용)
        var hasJointCar = false  // 공동명의 차량 보유
        // 외제차 브랜드 키워드
        val foreignCarBrands = listOf("벤츠", "메르세데스", "BMW", "비엠더블유", "아우디", "렉서스", "포르쉐",
            "볼보", "미니쿠퍼", "테슬라", "폭스바겐", "토요타", "혼다", "닛산", "인피니티",
            "재규어", "랜드로버", "링컨", "캐딜락", "지프", "외제", "수입차")
        // 개별 차량 정보: [시세, 대출, 월납부, 배우자(1/0), 외제(1/0)]
        val carInfoList = ArrayList<IntArray>()
        val carNameList = ArrayList<String>()  // 차량이름 (carInfoList와 1:1 매핑)
        val carDamboAmountList = ArrayList<Int>()  // 담보대출 금액 (carInfoList와 1:1)
        val carHalbuAmountList = ArrayList<Int>()  // 할부 금액 (carInfoList와 1:1)
        var spouseSecret = false  // 배우자 모르게 진행
        var familySecret = false  // 가족 모르게
        var hasAuction = false    // 경매 여부
        var hasSeizure = false    // 압류 여부
        var hasSpouseCoOwned = false  // 배우자 공동명의 부동산
        var regionIsSpouseOwned = false  // 지역(거주지)이 배우자명의
        var regionSpouseProperty = 0  // 지역 배우자명의 재산 (만원, ÷2 적용 후)
        var hasCivilCase = false   // 민사/소송 여부
        var civilAmount = 0        // 민사 소송금액 (만원)
        var hasUsedCarInstallment = false  // 중고차 할부
        var hasHealthInsuranceDebt = false  // 건강보험 체납
        var hasInsurancePolicyLoan = false  // 보험약관대출
        var hasHfcMortgage = false  // 한국주택금융공사 집담보
        var hasOthersRealEstate = false  // 타인명의 부동산 (배우자/부모/형제 등)
        var hasOwnRealEstate = false     // 본인/공동명의 부동산
        var ownRealEstateCount = 0       // 본인명의 부동산 개수 (2개=부결고지, 3개+=진행불가)
        var hasHomeMortgageInSidebar = false  // 대출과목 사이드바에 "집담보" 키워드 → 집경매 위험
        var savingsDeposit = 0  // 예적금 금액 (만원)
        var bizDeposit = 0  // 사업장 보증금 (만원, 재산 합산)
        var hasBunyangGwon = false  // 분양권 보유 여부 (재산 제외)
        var bunyangGwonNet = 0     // 분양권 순가치 (만원, 시세-대출)
        var jeonseNoJilgwon = false  // 전세대출 질권설정x → 대상채무 포함
        val jilgwonOCreditors = mutableSetOf<String>()  // 질권설정o 채권사 (담보 유지)
        val jilgwonXCreditors = mutableSetOf<String>()  // 질권설정x 채권사 (담보→대상채무 포함)
        var excludedSeqNumbers = mutableSetOf<Int>()  // 대출과목에서 제외할 순번
        var includedSeqNumbers = mutableSetOf<Int>()  // 강제 포함할 순번 (순번N 신용대출)
        val loanCatDamboCreditorNames = mutableSetOf<String>()  // 대출과목에서 담보 키워드와 함께 등장한 채권사명 (순번 매칭 실패 시 이름 매칭용)
        val loanCatCreditCreditorNames = mutableSetOf<String>()  // 대출과목에서 "신용"으로 명시된 채권사명 (담보 분류 무효화용)
        val thirdPartyDamboMap = mutableMapOf<String, Int>()  // 타인명의 담보대출 (채권사명 → 만원). 본인 명의 아니어도 담보로 분류
        var hasOngoingProcess = false  // 다른 채무조정 진행 중
        var ongoingProcessName = ""   // 진행중인 제도명 (회/신/워)
        var isIncomeEstimated = false  // 소득 예상
        var estimatedIncomeParsed = 0  // HWP에서 파싱한 예정/예상 소득 (만원)
        var isIncomeX = false  // "월 소득 x" → 소득 없음 강제
        var parsedMonthlyIncome = 0  // HWP 텍스트에서 직접 파싱한 월소득 (만원)
        var rentalIncomeMan = 0  // 월세수익 (만원, 단기 소득에만 포함)
        var hasPaymentOrder = false  // 지급명령 받음
        var hasWorkoutExpired = false  // 워크아웃 실효
        var hasPersonalRecovery = false  // 개인회생 기록
        var isDismissed = false  // 폐지/기각/취하 (장기연체자)
        var personalRecoveryYear = 0
        var personalRecoveryMonth = 0
        var wantsCarSale = false  // 차량 처분 의사
        var childSupportAmount = 0  // 양육비 (만원)
        val paidOffCreditorKeywords = mutableListOf<String>()  // 특이사항에서 완납된 채권사 키워드
        var telecomDebt = 0  // 기타채무 통신사 채무 (만원)
        val otherDebtCreditorNames = mutableListOf<String>()  // 기타채무 채권사명 (미협약 판단 제외용)
        val otherDebtEntries = mutableListOf<Pair<String, Int>>()  // 기타채무 실제 채권사 (이름, 금액만원)
        var childSupportReceiving = false  // 양육비 수급 (입금받음/양육O → 본인이 양육)
        var studentLoanTotal = 0   // 학자금 합계 (천원)
        var studentLoanUnknown = false  // 한국장학재단 일반/취업후 구별 불가
        var tableDebtTotal = 0     // 표 전체 합계 (천원, 제외항목 포함)
        // 대출과목 파싱 (표 없을 때 대상채무 fallback)
        var inLoanCategorySection = false // 대출과목 섹션 진입 여부 (신복위 파싱 제외용)
        var inRegionField = false // 지역 필드 연속줄 감지용
        var regionOwnership = "" // 지역 첫줄에서 감지한 명의 ("공동","본인","배우자")
        var inPropertySection = false // 재산 필드 연속줄 감지용
        var propertyOwnership = ""   // 재산 필드 명의 (공동/배우자/본인)
        var propertyOwnerRatio = 0   // 공동명의 본인 지분율 (%)
        var inJobSection = false      // 재직 필드 연속줄 감지용
        var inIncomeSection = false   // 연봉 필드 연속줄 감지용
        var inSpecialNotesSection = false // 특이사항 필드 연속줄 감지용
        var hasWolse = false                   // 월세 여부
        var rentalDeposit = 0                  // 세입자 보증금 합계 (만원)
        var inDebtSection = false           // [채무현황] ~ [최종정리] 구간
        var inOtherDebtSection = false   // 기타채무 섹션 진입 여부
        var inCardUsageTableSection = false // 카드이용금액 테이블 섹션
        val cardUsageCreditors = mutableSetOf<String>() // 카드이용금액 테이블에서 감지한 카드사명
        val cardUsageAmountMap = mutableMapOf<String, Int>() // 카드이용금액 카드사별 금액 (만원)
        var textShinbokDebt = 0  // 대출과목에서 파싱한 신복위 채무 (만원, PDF 없을 때 사용)
        var textTaxDebt = 0      // 대출과목에서 파싱한 국세/세금 채무 (만원)
        data class LoanCatRow(val seq: String, val creditor: String, val damboNote: String)
        val loanCatRows = mutableListOf<LoanCatRow>()  // 대출과목 행 (표시용)
        val specialNotesList = ArrayList<String>()
        val recentDebtEntries = ArrayList<Pair<Calendar, Int>>() // (대출일, 금액_천원)
        var parsedPropertyTotal = 0  // 재산줄에서 파싱한 재산 합계 (만원)
        var parsedOthersProperty = 0  // 타인명의 재산 합계 (만원, ÷2 적용 후)
        var parsedSpouseProperty = 0  // 재산필드 배우자명의 재산 (만원, ÷2 적용 후) - 장기에서 제외용
        var parsedDamboTotal = 0 // 표에서 파싱한 담보대출 합계 (만원)
        var parsedCarDamboTotal = 0 // 차량 담보대출 합계 (만원)
        val parsedDamboCreditorNames = mutableSetOf<String>() // 담보로 제외된 채권사명
        val parsedDamboCreditorMap = mutableMapOf<String, Int>() // 담보 채권사별 금액 (이름→만원)
        var totalParsedDebt = 0  // 표에서 파싱한 모든 채무 합계 (만원, 담보 포함)
        val parsedCreditorMap = mutableMapOf<String, Int>()  // 대상채무 채권사 (이름→금액만원)
        var parsedCardUsageTotal = 0  // 기타채무 요약에서 파싱한 카드이용금액 합계 (만원)
        val debtDateAmountSeen = mutableMapOf<String, Boolean>() // "날짜_금액" → isGuarantee (보증채무 중복 제거용)
        val businessLoanDates = mutableMapOf<String, Int>() // 운전자금/개인사업자대출 날짜 → 금액(천원) (지급보증 중복 제거용)
        var recentDebtRatio = 0.0
        var recentCarLoanMan = 0  // 6개월 이내 차량대출 합계 (만원)
        val recentCreditorNames = mutableSetOf<String>()  // 6개월 이내 채권사명
        val olderCreditorNames = mutableSetOf<String>()   // 6개월 이후 채권사명
        var postApplicationDebtMan = 0  // 신청일자 이후 추가채무 합계 (만원)
        val postApplicationCreditors = mutableMapOf<String, Int>()  // 신청일자 이후 채권사별 금액
        var jiguBojungExcludedMan = 0  // 지급보증 중복 제외 금액 (만원)
        var guaranteeDebtMan = 0  // 보증채무 합계: 운전자금 (만원)
        var jigubojungDebtMan = 0  // 지급보증 채무 합계 (만원, 장기 재산초과 판단용)
        var daebuDebtMan = 0  // 대부 채무 합계 (만원)
        var daebuCreditorCount = 0  // 대부 채권사 수
        val daebuCreditorNames = mutableSetOf<String>()  // 대부 채권사 중복 제거용
        var cardCapitalDebtMan = 0  // 카드/캐피탈 채무 합계 (만원)
        // 담보 reclassify 시 차감용: 채권사별 분류 합계 추적
        val guaranteeCreditorMap = mutableMapOf<String, Int>()
        val jigubojungCreditorMap = mutableMapOf<String, Int>()
        val daebuCreditorAmountMap = mutableMapOf<String, Int>()
        val cardCapitalCreditorMap = mutableMapOf<String, Int>()
        var saeExcludedDebtMan = 0  // 새출발 제외 채무 (기업은행 근로복지공단 보증 등) (만원)
        // ★ 과반 비율: 대상채무 확정 후 계산
        var majorCreditorRatio = 0.0

        // ============= 사전 스캔: 운전자금 월 수집 → 같은 월 융자담보지보 스킵 =============
        val unjeonMonths = mutableSetOf<String>()  // 운전자금 년.월 (예: "2018.05")
        val unjeonSeqs = mutableSetOf<Int>()       // 운전자금 순번 (지급보증 연번호 판단용)
        val yungjaSkipSeqs = mutableSetOf<Int>()   // 스킵할 융자담보지보 순번
        data class DamboJiboEntry(val seq: Int, val yearMonth: String)
        val damboJiboEntries = mutableListOf<DamboJiboEntry>()
        for (rawLine in lines) {
            val l = rawLine.trim().replace(Regex("\\s"), "")
            val seqMatch = Pattern.compile("^(\\d{1,2})개인사업자대출").matcher(l)
            if (seqMatch.find()) {
                val seq = seqMatch.group(1)!!.toInt()
                val dateMatch = Pattern.compile("(\\d{4})\\.(\\d{1,2})\\.\\d{1,2}").matcher(l)
                if (dateMatch.find()) {
                    val yearMonth = "${dateMatch.group(1)}.${dateMatch.group(2)!!.padStart(2, '0')}"
                    if (l.contains("운전자금")) {
                        unjeonMonths.add(yearMonth)
                        unjeonSeqs.add(seq)
                    } else if (l.contains("융자담보지보")) {
                        damboJiboEntries.add(DamboJiboEntry(seq, yearMonth))
                    }
                }
            }
        }
        val yungjaSkippedMonths = mutableSetOf<String>()  // 융자담보지보가 스킵된 월 (운전자금=지급보증 판단용)
        for (entry in damboJiboEntries) {
            if (entry.yearMonth in unjeonMonths) {
                yungjaSkipSeqs.add(entry.seq)
                yungjaSkippedMonths.add(entry.yearMonth)
                Log.d("HWP_PRESCAN", "융자담보지보 스킵 (운전자금 동월): 순번=${entry.seq}, 월=${entry.yearMonth}")
            }
        }

        // ============= 사전 스캔: 대출과목 "차량담보" 등 순번 수집 (사이드바이사이드 테이블 대응) =============
        val studentLoanExcludedSeqs = mutableSetOf<Int>()  // 학자금 취업 후 상환 순번
        val studentLoanGeneralSeqs = mutableSetOf<Int>()  // 학자금 일반 상환 순번
        var preScanLoanCat = false
        for (rawLine in lines) {
            val l = rawLine.trim().replace(Regex("\\s"), "")
            // 대출과목 섹션 기반 스캔
            if (l.contains("대출과목") || (l.contains("현황순번") && l.contains("담보"))) preScanLoanCat = true
            // 종료 조건: "대출과목"이 같은 라인에 없을 때만 (병합 라인 "[요약][대출과목]" 대응)
            if (preScanLoanCat && !l.contains("대출과목") && !l.contains("현황순번") && (l.contains("요약사항") || l.contains("최저납부") || l.contains("요약]") || l.contains("기타채무"))) preScanLoanCat = false
            // 대출과목 섹션 내 "신용" 표기 라인 → 해당 채권사명 수집 (담보 분류 무효화용)
            if (preScanLoanCat && l.contains("신용") && !l.contains("담보") && !l.contains("할부") && !l.contains("리스")) {
                extractLoanCatCreditorNames(l, loanCatCreditCreditorNames)
            }
            if (preScanLoanCat && (l.contains("담보") || l.contains("할부") || l.contains("리스") || l.contains("중도금") || l.contains("약관") || l.contains("후순위") || l.contains("보증금") || l.contains("전세") || l.contains("채무아님"))) {
                // 채권사명 추출 (순번이 없는 라인에서도 이름 매칭으로 담보 처리)
                extractLoanCatCreditorNames(l, loanCatDamboCreditorNames)
                // 콤마 구분 순번 감지: "7,8" "6,10" 등
                val commaSeqM = Pattern.compile("(\\d{1,2}(?:,\\d{1,2})+)").matcher(l)
                while (commaSeqM.find()) {
                    val parts = commaSeqM.group(1)!!.split(",")
                    for (part in parts) {
                        val seqNum = part.toIntOrNull() ?: continue
                        if (seqNum in 1..30) {
                            excludedSeqNumbers.add(seqNum)
                            Log.d("HWP_PRESCAN", "섹션기반 담보 순번(콤마): $seqNum - ${rawLine.trim()}")
                        }
                    }
                }
                // 병합 라인에서도 순번 감지: 숫자+비숫자(금융기관명) 패턴
                val seqM = Pattern.compile("(\\d{1,2})(?![억만원천년,])[A-Za-z\uFF21-\uFF3A\uFF41-\uFF5A가-힣]").matcher(l)
                while (seqM.find()) {
                    val seqNum = seqM.group(1)!!.toInt()
                    if (seqNum in 1..30) {
                        excludedSeqNumbers.add(seqNum)
                        Log.d("HWP_PRESCAN", "섹션기반 담보 순번: $seqNum - ${rawLine.trim()}")
                    }
                }
            }
            // 대출과목 사이드바에 "집담보" 키워드 → 집경매 위험 표시 (본인명의 부동산 감지 못 해도)
            if (preScanLoanCat && l.contains("집담보")) {
                hasHomeMortgageInSidebar = true
                Log.d("HWP_PRESCAN", "대출과목 집담보 감지 → 집경매 위험: ${rawLine.trim()}")
            }
            // 패턴기반: "N + 채권사명 + 차량담보/차담보 등" (테이블 합쳐진 경우 대응, 영문/전각 대응)
            val damboM = Pattern.compile("(\\d{1,2})(?!년)([^\\d]{2,}?)(?:차량담보|차량할부|차량리스|자동차담보|차담보|집담보|기계담보|중도금대출|중도금)").matcher(l)
            while (damboM.find()) {
                val seqNum = damboM.group(1)!!.toInt()
                if (seqNum in 1..30) {
                    excludedSeqNumbers.add(seqNum)
                    Log.d("HWP_PRESCAN", "패턴기반 담보 순번: $seqNum (${damboM.group(0)}) - ${rawLine.trim()}")
                }
            }
            // 학자금 취업 후 상환 / 일반 상환 순번 수집
            if (preScanLoanCat && (l.contains("학자금") || l.contains("장학재단"))) {
                val isAfterEmployment = l.contains("취업")
                val stuSeqs = mutableSetOf<Int>()
                // 콤마 구분 순번: "2,3,4,5,6,8"
                val stuCommaM = Pattern.compile("(\\d{1,2}(?:,\\d{1,2})+)").matcher(l)
                while (stuCommaM.find()) {
                    for (part in stuCommaM.group(1)!!.split(",")) {
                        val seqNum = part.toIntOrNull() ?: continue
                        if (seqNum in 1..30) stuSeqs.add(seqNum)
                    }
                }
                // 단일 순번: 숫자+비숫자 패턴
                val stuSeqM = Pattern.compile("(\\d{1,2})(?![억만원천년,])[A-Za-z\uFF21-\uFF3A\uFF41-\uFF5A가-힣]").matcher(l)
                while (stuSeqM.find()) {
                    val seqNum = stuSeqM.group(1)!!.toInt()
                    if (seqNum in 1..30) stuSeqs.add(seqNum)
                }
                for (seqNum in stuSeqs) {
                    if (isAfterEmployment) {
                        studentLoanExcludedSeqs.add(seqNum)
                        Log.d("HWP_PRESCAN", "학자금 취업후상환 순번: $seqNum - ${rawLine.trim()}")
                    } else {
                        studentLoanGeneralSeqs.add(seqNum)
                        Log.d("HWP_PRESCAN", "학자금 일반상환 순번: $seqNum - ${rawLine.trim()}")
                    }
                }
            }
            // 디버깅: 차량담보/할부 키워드 포함 라인 로그
            if (l.contains("담보") || l.contains("할부") || l.contains("리스") || l.contains("중도금")) {
                Log.d("HWP_PRESCAN", "담보키워드 포함 라인: $l")
            }
            // 타인명의 담보대출 감지 (어머님/아버님/배우자 등 명의 + 담보)
            if (l.contains("명의") && l.contains("담보") && !l.contains("본인명의")) {
                val tpM = Pattern.compile("([가-힣A-Za-z]+(?:은행|저축은행|상호저축|캐피탈|카드|보험|상호금융))(\\d+)억(?:(\\d{1,4})만)?").matcher(l)
                if (tpM.find()) {
                    val creditor = tpM.group(1)!!
                    val eok = tpM.group(2)!!.toIntOrNull() ?: 0
                    val man = tpM.group(3)?.toIntOrNull() ?: 0
                    val totalMan = eok * 10000 + man
                    if (totalMan > 0) {
                        thirdPartyDamboMap[creditor] = totalMan
                        Log.d("HWP_PRESCAN", "타인명의 담보 감지: $creditor ${totalMan}만 - ${rawLine.trim()}")
                    }
                }
            }
        }
        Log.d("HWP_PRESCAN", "사전스캔 결과: excludedSeqNumbers=$excludedSeqNumbers, 학자금취업후상환=$studentLoanExcludedSeqs, 타인명의담보=$thirdPartyDamboMap")

        // ============= 보조 정보 추출 =============
        // 거대 메서드 바이트코드 한계 회피 위해 loop body를 local function으로 추출 (Kotlin local fun = 별도 JVM method)
        var shouldStopParsing = false
        fun processHwpLine(rawLine: String) {
            val line = rawLine.trim()
            if (line.isEmpty()) return
            val lineNoSpace = line.replace(Regex("\\s"), "")

            // [채무현황] 섹션 감지
            if (lineNoSpace.contains("채무현황")) {
                inDebtSection = true
            }

            // 대출과목 섹션 감지 (조기 설정 → 차량/채무 파싱에서 제외용)
            // prescan과 동일한 조건: "대출과목" 또는 ("현황순번" + "담보") 헤더 모두 진입
            if (lineNoSpace.contains("대출과목") || lineNoSpace.contains("[대출과목]") ||
                (lineNoSpace.contains("현황순번") && lineNoSpace.contains("담보"))) {
                inLoanCategorySection = true
            }
            if (inLoanCategorySection && !lineNoSpace.contains("대출과목") && !lineNoSpace.contains("현황순번") &&
                (lineNoSpace.contains("요약사항") || lineNoSpace.contains("최저납부") || lineNoSpace.contains("요약]") || lineNoSpace.contains("기타채무"))) {
                inLoanCategorySection = false
            }

            // 필드 전환 감지 (연속줄 섹션 종료용)
            val fieldCheckStr = lineNoSpace.trimStart('[', '［')
            val isKnownField = fieldCheckStr.startsWith("성함") || fieldCheckStr.startsWith("전화") ||
                    fieldCheckStr.startsWith("주민") || fieldCheckStr.startsWith("결혼") ||
                    fieldCheckStr.startsWith("지역") || fieldCheckStr.startsWith("재직") ||
                    fieldCheckStr.startsWith("재산") || fieldCheckStr.startsWith("차량") || fieldCheckStr.startsWith("차") ||
                    fieldCheckStr.startsWith("연봉") || fieldCheckStr.startsWith("소득") ||
                    fieldCheckStr.startsWith("사대보험") || fieldCheckStr.startsWith("배우자") ||
                    fieldCheckStr.startsWith("보험내역") || fieldCheckStr.startsWith("채무조정") ||
                    fieldCheckStr.startsWith("대출사용") || fieldCheckStr.startsWith("연체") ||
                    fieldCheckStr.startsWith("특이") || fieldCheckStr.startsWith("장애") ||
                    fieldCheckStr.startsWith("60세") || fieldCheckStr.startsWith("채무현황")
            if (isKnownField) {
                if (!fieldCheckStr.startsWith("재직")) inJobSection = false
                if (!fieldCheckStr.startsWith("연봉") && !fieldCheckStr.startsWith("소득")) inIncomeSection = false
                if (!fieldCheckStr.startsWith("특이")) inSpecialNotesSection = false
            }

            // 이름 추출
            if (name.isEmpty() && line.length in 2..20) {
                // 필드 레이블은 이름이 아님
                val isFieldLabel = lineNoSpace.contains("소득") || lineNoSpace.contains("연락") || lineNoSpace.contains("주민") ||
                        lineNoSpace.contains("결혼") || lineNoSpace.contains("지역") || lineNoSpace.contains("재직") ||
                        lineNoSpace.contains("재산") || lineNoSpace.contains("차량") || lineNoSpace.contains("연봉") ||
                        lineNoSpace.contains("보험") || lineNoSpace.contains("채무") || lineNoSpace.contains("대출") ||
                        lineNoSpace.contains("특이") || lineNoSpace.contains("장애") || lineNoSpace.contains("부모") ||
                        lineNoSpace.contains("배우자") || lineNoSpace.contains("사대") || lineNoSpace.contains("카드") ||
                        lineNoSpace.contains("요약") || lineNoSpace.contains("계획") ||
                        lineNoSpace.contains("성함") || lineNoSpace.contains("전화")
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

            // 성함 필드에서 이름 추출
            if (name.isEmpty() && lineNoSpace.contains("성함")) {
                val nameVal = extractValue(line, "성함")
                val cleanName = nameVal.split("(", "（")[0].trim()
                if (cleanName.matches("^[가-힣]{2,5}$".toRegex())) {
                    name = cleanName
                }
            }

            // 주민번호에서 출생년도 추출
            if (birthYear == 0 && fieldCheckStr.startsWith("주민")) {
                val juminVal = extractValue(line, "주민").replace(Regex("[\\s\\-]"), "")
                val m = Pattern.compile("(\\d{2})(\\d{2})(\\d{2})(\\d)").matcher(juminVal)
                if (m.find()) {
                    val yy = m.group(1)!!.toInt()
                    birthMonth = m.group(2)!!.toIntOrNull() ?: 1
                    birthDay = m.group(3)!!.toIntOrNull() ?: 1
                    val gender = m.group(4)!!.toInt()
                    birthYear = when (gender) {
                        1, 2, 5, 6 -> 1900 + yy
                        3, 4, 7, 8 -> 2000 + yy
                        else -> 1900 + yy
                    }
                }
            }

            // 지역 (연속줄 대응: 첫줄에 명의만 있고 둘째줄에 시세/대출이 있는 경우)
            if (fieldCheckStr.startsWith("지역")) {
                inRegionField = true
                regionOwnership = when {
                    lineNoSpace.contains("공동명의") -> "공동"
                    lineNoSpace.contains("본인명의") -> "본인"
                    lineNoSpace.contains("배우자명의") || (lineNoSpace.contains("배우자") && lineNoSpace.contains("명의")) -> "배우자"
                    else -> ""
                }
                val regionVal = extractValue(line, "지역")
                if (regionVal.isNotEmpty()) {
                    region = regionVal
                    if (regionOwnership == "배우자") {
                        // 월세가 아닌 배우자명의 부동산만 등본분리 대상
                        if (!lineNoSpace.contains("월세")) {
                            regionIsSpouseOwned = true
                        }
                        // 지역 배우자명의 시세/대출 파싱 → ÷2 (시세 없으면 보증금=시세)
                        val siseMatch = Pattern.compile("시세\\s*(\\d+억?\\s*\\d*천?\\s*\\d*만?)").matcher(line)
                        val loanMatch = Pattern.compile("대출\\s*(\\d+억?\\s*\\d*천?\\s*\\d*만?)").matcher(line)
                        val bojungMatch = Pattern.compile("보증금\\s*(\\d+억?\\s*\\d*천?\\s*\\d*만?)").matcher(line)
                        val regionSise = if (siseMatch.find()) extractAmount(siseMatch.group(1)!!) else if (bojungMatch.find()) extractAmount(bojungMatch.group(1)!!) else 0
                        val regionLoan = if (loanMatch.find()) extractAmount(loanMatch.group(1)!!) else 0
                        regionSpouseProperty = maxOf((regionSise - regionLoan) / 2, 0)
                        Log.d("HWP_PARSE", "지역 배우자명의 감지: 시세${regionSise}만 - 대출${regionLoan}만 → ÷2 = ${regionSpouseProperty}만")
                    }
                    // 월세 감지 + 보증금 → 재산 (배우자명의면 ÷2)
                    if (lineNoSpace.contains("월세")) {
                        hasWolse = true
                        // "월세 보증금 XXX만" 또는 "월세 XXX만/YYY만" 형식에서 보증금 추출
                        val bojung = if (lineNoSpace.contains("보증금")) {
                            extractAmountAfterKeyword(line, "보증금")
                        } else {
                            extractAmountAfterKeyword(line, "월세")
                        }
                        if (bojung > 0) {
                            rentalDeposit += bojung
                            val appliedBojung = if (regionOwnership == "배우자") bojung / 2 else bojung
                            parsedPropertyTotal += appliedBojung
                            if (regionOwnership == "배우자") {
                                Log.d("HWP_PARSE", "지역 월세 보증금 재산 (배우자명의): ${bojung}만 → ÷2 = ${appliedBojung}만")
                            } else {
                                Log.d("HWP_PARSE", "지역 월세 보증금 재산: ${bojung}만")
                            }
                        }
                    }
                    // 지역 본인명의 시세/대출/보증금 파싱 → parsedPropertyTotal에 합산
                    else if (lineNoSpace.contains("본인명의") && (lineNoSpace.contains("시세") || lineNoSpace.contains("공시지가") || lineNoSpace.contains("보증금"))) {
                        // 질권설정x 선행 감지 (재산 계산 전에 필요)
                        if (!jeonseNoJilgwon && hasNoJilgwon(lineNoSpace, line)) {
                            jeonseNoJilgwon = true
                            Log.d("HWP_PARSE", "질권설정 없음 선행 감지 (지역 파싱): $line")
                        }
                        val groups = Regex("\\([^)]+\\)").findAll(regionVal).toList()
                        for (group in groups) {
                            val g = group.value
                            if (!g.contains("본인명의") && !g.contains("공동명의")) continue
                            // 본인명의 없이 배우자/타인/부모만 있으면 스킵
                            if (!g.contains("본인명의") && (g.contains("배우자") || g.contains("타인") || g.contains("부모"))) continue
                            val gNs = g.replace("\\s+".toRegex(), "")
                            // 그룹 내에 "배우자명의 시세/공시지가/보증금" 있으면 → 배우자 단독 부동산 (본인명의는 대출만)
                            // 시세는 배우자 소유, 본인명의 대출도 배우자 부동산에 묶인 것으로 간주 → ÷2
                            val spouseOwnsProperty = gNs.contains("배우자명의") &&
                                (gNs.contains("배우자명의시세") || gNs.contains("배우자명의공시지가") || gNs.contains("배우자명의보증금"))
                            val rawSise = extractAmountAfterKeyword(g, "시세").takeIf { it > 0 }
                                ?: extractAmountAfterKeyword(g, "공시지가").takeIf { it > 0 }
                            val bojungAmt = extractAmountAfterKeyword(g, "보증금")
                            if (rawSise == null && bojungAmt > 0) rentalDeposit += bojungAmt
                            val siseAmt = rawSise ?: bojungAmt.takeIf { it > 0 } ?: 0
                            // 본인+배우자명의 동시 → 양쪽 대출 합산
                            val loanAmt = if (gNs.contains("본인명의") && gNs.contains("배우자명의")) {
                                val ownLoan = extractAmountAfterKeyword(g, "본인명의 대출").takeIf { it > 0 }
                                    ?: extractAmountAfterKeyword(g, "본인명의대출")
                                val spouseLoan = extractAmountAfterKeyword(g, "배우자명의 대출").takeIf { it > 0 }
                                    ?: extractAmountAfterKeyword(g, "배우자명의대출")
                                ownLoan + spouseLoan
                            } else {
                                extractAllAmountsAfterKeyword(g, "대출")
                            }
                            val deductBojung = if (rawSise != null && rawSise > 0) bojungAmt else 0
                            val isJilgwonXProperty = jeonseNoJilgwon && loanAmt > 0
                            val rawNet = if (isJilgwonXProperty) siseAmt else maxOf(siseAmt - loanAmt - deductBojung, 0)
                            if (spouseOwnsProperty) {
                                // 배우자 단독 부동산 → ÷2 적용, 등본분리 대상으로 표시
                                val halvedNet = rawNet / 2
                                parsedPropertyTotal += halvedNet
                                parsedSpouseProperty += halvedNet
                                regionIsSpouseOwned = true
                                regionSpouseProperty = halvedNet
                                Log.d("HWP_PARSE", "지역 배우자명의(혼합) 재산: 시세${siseAmt}만 - 대출${loanAmt}만 = ${rawNet}만 → ÷2 = ${halvedNet}만")
                            } else {
                                parsedPropertyTotal += rawNet
                                if (isJilgwonXProperty) {
                                    Log.d("HWP_PARSE", "지역 본인명의 재산 (질권x): ${siseAmt}만 전액 재산, 대출${loanAmt}만 → 대상채무")
                                } else {
                                    Log.d("HWP_PARSE", "지역 본인명의 재산: 시세${siseAmt}만 - 대출${loanAmt}만 = ${rawNet}만")
                                }
                            }
                        }
                    }
                }
            } else if (inRegionField && !fieldCheckStr.startsWith("지역")) {
                // 지역 연속줄: 다른 필드 레이블이면 종료
                val isNextField = lineNoSpace.startsWith("재산") || lineNoSpace.startsWith("재직") ||
                        lineNoSpace.startsWith("소득") || lineNoSpace.startsWith("연봉") ||
                        lineNoSpace.startsWith("결혼") || lineNoSpace.startsWith("차량") ||
                        lineNoSpace.startsWith("보험") || lineNoSpace.startsWith("채무") ||
                        lineNoSpace.startsWith("특이") || lineNoSpace.startsWith("연락")
                if (isNextField) {
                    inRegionField = false
                } else if (lineNoSpace.contains("시세") || lineNoSpace.contains("공시지가") || lineNoSpace.contains("보증금") || lineNoSpace.contains("전세")) {
                    // 연속줄에 명의 표시 있으면 갱신 (본인명의/배우자명의/공동명의)
                    val lineOwnership = when {
                        lineNoSpace.contains("공동명의") -> "공동"
                        lineNoSpace.contains("본인명의") && !lineNoSpace.contains("본인명의대출") -> "본인"
                        lineNoSpace.contains("배우자명의") && !lineNoSpace.contains("배우자명의대출") -> "배우자"
                        else -> regionOwnership
                    }
                    val rawSise = extractAmountAfterKeyword(line, "시세").takeIf { it > 0 }
                        ?: extractAmountAfterKeyword(line, "공시지가").takeIf { it > 0 }
                    val bojungAmt = maxOf(extractAmountAfterKeyword(line, "보증금"), extractAmountAfterKeyword(line, "전세"))
                    if (rawSise == null && bojungAmt > 0) rentalDeposit += bojungAmt
                    val siseAmt = rawSise ?: bojungAmt.takeIf { it > 0 } ?: 0
                    // 본인명의 대출 + 배우자명의 대출 각각 추출
                    var loanAmt = if (lineNoSpace.contains("본인명의") && lineNoSpace.contains("배우자명의")) {
                        val ownLoan = extractAmountAfterKeyword(line, "본인명의 대출").takeIf { it > 0 }
                            ?: extractAmountAfterKeyword(line, "본인명의대출")
                        val spouseLoan = extractAmountAfterKeyword(line, "배우자명의 대출").takeIf { it > 0 }
                            ?: extractAmountAfterKeyword(line, "배우자명의대출")
                        ownLoan + spouseLoan
                    } else {
                        extractAllAmountsAfterKeyword(line, "대출")
                    }
                    val deductBojung = if (rawSise != null && rawSise > 0) bojungAmt else 0
                    val net = maxOf(siseAmt - loanAmt - deductBojung, 0)
                    when (lineOwnership) {
                        "공동" -> {
                            // 비율 파싱: "본인 50", "본인4:어머니4:누나2" 등
                            val ownerRatio = parseOwnerRatioPct(region) ?: 0
                            val portion = if (ownerRatio > 0) {
                                Math.round(net.toDouble() * ownerRatio / 100).toInt()
                            } else {
                                net / 2  // 비율 미기재 → (시세-대출)/2
                            }
                            val appliedPortion = maxOf(portion, 0)
                            parsedPropertyTotal += appliedPortion
                            Log.d("HWP_PARSE", "지역 연속줄 공동명의: 시세${siseAmt}만 - 대출${loanAmt}만 = ${net}만 → ${if (ownerRatio > 0) "${ownerRatio}%" else "÷2"} = ${appliedPortion}만")
                        }
                        "배우자" -> {
                            regionIsSpouseOwned = true
                            regionSpouseProperty = net / 2
                            Log.d("HWP_PARSE", "지역 연속줄 배우자명의: 시세${siseAmt}만 - 대출${loanAmt}만 = ${net}만 → ÷2 = ${regionSpouseProperty}만")
                        }
                        else -> { // 본인명의
                            parsedPropertyTotal += net
                            Log.d("HWP_PARSE", "지역 연속줄 본인명의: 시세${siseAmt}만 - 대출${loanAmt}만 = ${net}만")
                        }
                    }
                    // 지역 연속줄: 다음 필드 레이블이 나올 때까지 계속 처리 (여러 부동산 가능)
                }
            }

            // 재산 필드 파싱 (연속줄 지원)
            if (lineNoSpace.startsWith("재산") && !lineNoSpace.contains("채무") && !lineNoSpace.contains("대출과목")) {
                inPropertySection = true
                propertyOwnership = ""
            } else if (inPropertySection) {
                val isNextField = lineNoSpace.startsWith("차량") || lineNoSpace.startsWith("차") ||
                        lineNoSpace.startsWith("연봉") || lineNoSpace.startsWith("소득") ||
                        lineNoSpace.startsWith("사대보험") || lineNoSpace.startsWith("보험") ||
                        lineNoSpace.startsWith("배우자") || lineNoSpace.startsWith("채무") ||
                        lineNoSpace.startsWith("특이") || lineNoSpace.startsWith("연체") ||
                        lineNoSpace.startsWith("장애") || lineNoSpace.startsWith("60세") ||
                        lineNoSpace.startsWith("재직") || lineNoSpace.startsWith("결혼")
                if (isNextField) inPropertySection = false
            }
            if (inPropertySection && !lineNoSpace.contains("채무") && !lineNoSpace.contains("대출과목")) {
                var propertyVal = if (lineNoSpace.startsWith("재산")) {
                    // ":" inside content (e.g., 본인 50: 배우자 50) truncates extractValue → regex 우선
                    Regex("재\\s*산\\s+(.*)", RegexOption.DOT_MATCHES_ALL).find(line)?.groupValues?.get(1)?.trim()
                        ?: extractValue(line, "재산")
                } else line.trim()
                // 공동명의 감지 (재산 필드 내) + 본인 지분율 파싱
                if (lineNoSpace.contains("공동명의")) {
                    propertyOwnership = "공동"
                    propertyOwnerRatio = parseOwnerRatioPct(propertyVal) ?: 0
                }
                val propertyNoSpace = propertyVal.replace(Regex("\\s"), "")
                if (propertyNoSpace.isNotEmpty() && !propertyNoSpace.matches(Regex("[xX×없0].*"))) {
                    val groups = Regex("\\([^)]+\\)").findAll(propertyVal).toList()
                    for (group in groups) {
                        val g = group.value
                        // 분양권 → 재산에서 제외 (분양권 포기)
                        if (g.contains("분양권")) {
                            val bSise = extractAmountAfterKeyword(g, "분양권").takeIf { it > 0 }
                                ?: extractAmountAfterKeyword(g, "시세")
                            val bLoan = extractAmountAfterKeyword(g, "대출")
                            bunyangGwonNet = maxOf(bSise - bLoan, 0)
                            hasBunyangGwon = true
                            Log.d("HWP_PARSE", "분양권 감지 (재산 제외): ${bSise}만 - 대출${bLoan}만 = ${bunyangGwonNet}만 (원문: $g)")
                            continue
                        }
                        val gNoSpace = g.replace("\\s+".toRegex(), "")
                        val isGroupJoint = gNoSpace.contains("공동명의")  // 그룹 자체가 공동명의
                        // 배우자명의 재산 제외 (단, "배우자명의 대출"만 있고 시세/빌라/아파트 등 소유 표시 없으면 제외 안함)
                        // 공동명의는 배우자 단독명의가 아님 (50%가 본인 지분)
                        val hasSpouseOwnership = !isGroupJoint && gNoSpace.contains("배우자명의") && (gNoSpace.contains("시세") || gNoSpace.contains("빌라") || gNoSpace.contains("아파트") || gNoSpace.contains("건물") || gNoSpace.contains("공시지가") || gNoSpace.contains("보증금"))
                        val isSpouseOwned = hasSpouseOwnership || (g.contains("배우자") && !isGroupJoint && !gNoSpace.contains("배우자명의대출") && propertyOwnership != "공동")
                        val isOthers = isSpouseOwned || g.contains("타인") || g.contains("부모") || g.contains("형제") || g.contains("상대방")
                        val rawSise = extractAmountAfterKeyword(g, "시세").takeIf { it > 0 }
                            ?: extractAmountAfterKeyword(g, "공시지가").takeIf { it > 0 }
                        val bojungAmt = extractAmountAfterKeyword(g, "보증금")
                        val siseAmt = rawSise
                            ?: bojungAmt.takeIf { it > 0 }
                            ?: extractAmount(g)
                        // 본인명의 대출 + 배우자명의 대출 각각 추출
                        var loanAmt = if (gNoSpace.contains("본인명의") && gNoSpace.contains("배우자명의")) {
                            val ownLoan = extractAmountAfterKeyword(g, "본인명의 대출").takeIf { it > 0 }
                                ?: extractAmountAfterKeyword(g, "본인명의대출")
                            val spouseLoan = extractAmountAfterKeyword(g, "배우자명의 대출").takeIf { it > 0 }
                                ?: extractAmountAfterKeyword(g, "배우자명의대출")
                            ownLoan + spouseLoan
                        } else {
                            extractAmountAfterKeyword(g, "대출")
                        }
                        val seipjaAmt = extractAmountAfterKeyword(g, "세입자")
                        // 시세가 있으면 보증금 차감, 보증금만 있으면 보증금이 재산가치 (세입자 있으면 중복차감 방지)
                        val deductBojung = if (rawSise != null && rawSise > 0 && seipjaAmt == 0) bojungAmt else 0
                        // 본인지분 금액이 명시되어 있으면 우선 사용
                        val jibunAmtMatch = Regex("본인지분\\s*(\\d[\\d,]*만?)").find(g)
                        val explicitJibunAmt = if (jibunAmtMatch != null) extractAmount(jibunAmtMatch.value) else 0
                        val appliedNet = if (explicitJibunAmt > 0) {
                            maxOf(explicitJibunAmt - loanAmt - seipjaAmt, 0)
                        } else if (propertyOwnership == "공동" || isGroupJoint) {
                            // 공동명의: (시세-대출)×비율, 비율 없으면 (시세-대출)/2
                            val groupRatio = if (isGroupJoint) parseOwnerRatioPct(g) ?: 0 else propertyOwnerRatio
                            val net = maxOf(siseAmt - loanAmt - seipjaAmt - deductBojung, 0)
                            if (groupRatio > 0) {
                                Math.round(net.toDouble() * groupRatio / 100).toInt()
                            } else {
                                net / 2
                            }
                        } else {
                            val net = maxOf(siseAmt - loanAmt - seipjaAmt - deductBojung, 0)
                            if (isOthers) net / 2 else net
                        }
                        parsedPropertyTotal += appliedNet
                        if (isSpouseOwned) {
                            parsedSpouseProperty += appliedNet
                            Log.d("HWP_PARSE", "재산 배우자명의 ÷2: ${maxOf(siseAmt - loanAmt, 0)}만 → ${appliedNet}만")
                        }
                        if (isOthers) parsedOthersProperty += appliedNet
                    }
                    if (groups.isEmpty() && propertyOwnership != "공동") {
                        // 공동명의 첫줄은 시세 설명만 포함 → 둘째줄 그룹에서 정확히 계산하므로 스킵
                        val amt = extractAmount(propertyVal)
                        if (amt > 0) parsedPropertyTotal += amt
                    }
                    Log.d("HWP_PARSE", "재산 필드 파싱: ${parsedPropertyTotal}만 (분양권=${hasBunyangGwon}, 원문: $propertyVal)")
                }
            }

            // 부동산 소유 형태 감지 (타인명의 vs 본인/공동명의)
            val isRealEstateLine = (inRegionField || inPropertySection) &&
                    (lineNoSpace.contains("시세") || lineNoSpace.contains("공시지가") ||
                    lineNoSpace.contains("아파트") || lineNoSpace.contains("건물") ||
                    lineNoSpace.contains("분양권") || lineNoSpace.contains("전세"))
            if (isRealEstateLine) {
                val isOthersPropertyLine = lineNoSpace.contains("상대방") || lineNoSpace.contains("타인")
                // "명의" 포함 but 본인/공동이 아니면 타인명의, 상대방/타인 맥락도 타인
                if ((lineNoSpace.contains("명의") &&
                            !lineNoSpace.contains("본인명의") && !lineNoSpace.contains("공동명의")) || isOthersPropertyLine) {
                    hasOthersRealEstate = true
                } else if (lineNoSpace.contains("공동명의") && lineNoSpace.contains("배우자")) {
                    // 배우자 공동명의 → 등본분리 가능 → 타인명의로 처리, 단기 시 집경매 위험은 시세+담보 둘 다 있을 때
                    hasOthersRealEstate = true
                    val hasSise = lineNoSpace.contains("시세") || lineNoSpace.contains("공시지가")
                    val hasLoan = lineNoSpace.contains("대출") || lineNoSpace.contains("담보")
                    if (hasSise && hasLoan) hasSpouseCoOwned = true
                } else if (lineNoSpace.contains("본인명의")) {
                    // 본인명의: 시세와 담보(대출) 둘 다 있을 때만 집경매 위험
                    val hasSise = lineNoSpace.contains("시세") || lineNoSpace.contains("공시지가")
                    val hasLoan = lineNoSpace.contains("대출") || lineNoSpace.contains("담보")
                    if (hasSise) {
                        ownRealEstateCount++
                        Log.d("HWP_PARSE", "본인명의 부동산 감지: 누적 ${ownRealEstateCount}개 - $line")
                    }
                    if (hasSise && hasLoan) hasOwnRealEstate = true
                }
            }

            // 재직/직업 (연속줄 지원)
            if (lineNoSpace.contains("재직")) inJobSection = true
            if (inJobSection) {
                val job = if (lineNoSpace.contains("재직")) extractValue(line, "재직") else line.trim()
                if (job.contains("법인사업") || job.contains("법인대표") || job.contains("법인운영")) {
                    isCorporateBusiness = true
                }
                val jobNoCorp = job.replace("법인사업", "").replace("법인대표", "").replace("법인운영", "")
                if (jobNoCorp.contains("사업자") || jobNoCorp.contains("개인사업") || jobNoCorp.contains("자영업") || jobNoCorp.contains("음식점")) {
                    isBusinessOwner = true
                    if (!job.contains("폐업")) businessEndYear = 0
                }
                if (hasNonProfitKeyword(job)) {
                    isNonProfit = true
                }
            }

            // ============= 전역 감지 (줄 위치 무관, 2줄 이어쓰기 대응) =============

            // ★ 사업자 이력 감지 (텍스트 파싱)
            if (!hasBusinessHistory) {
                if ((lineNoSpace.contains("자영업") || (lineNoSpace.contains("개인사업") && !lineNoSpace.contains("사업자대출")) ||
                            lineNoSpace.contains("폐업") || lineNoSpace.contains("사업자") && lineNoSpace.contains("개시") ||
                            lineNoSpace.contains("사업자") && (lineNoSpace.contains("년") || lineNoSpace.contains("매출"))) && !lineNoSpace.contains("사업자대출")) {

                    if (!specialNotesList.any { it.contains("사업자") || it.contains("자영업") || it.contains("폐업") }) {
                        specialNotesList.add("사업자이력")
                    }
                    if (businessStartYear == 0) {
                        val bizYearPattern = Pattern.compile("(\\d{2,4})년도?\\s*(?:(\\d{1,2})월\\s*)?개업")
                        val bizYearMatcher = bizYearPattern.matcher(line)
                        if (bizYearMatcher.find()) {
                            val y = bizYearMatcher.group(1)!!.toInt()
                            businessStartYear = if (y < 100) 2000 + y else y
                            val monthStr = bizYearMatcher.group(2)
                            if (monthStr != null && businessStartMonth == 0) {
                                businessStartMonth = monthStr.toInt()
                            } else if (businessStartMonth == 0) {
                                businessStartMonth = 1
                            }
                        }
                        // "개인사업자(17년)" 형식: 괄호 안 연도만 있고 "개업" 키워드 없는 경우
                        if (businessStartYear == 0) {
                            val bizParenPattern = Pattern.compile("사업자\\s*\\(\\s*(\\d{2,4})년").matcher(line)
                            if (bizParenPattern.find()) {
                                val y = bizParenPattern.group(1)!!.toInt()
                                businessStartYear = if (y < 100) 2000 + y else y
                                if (businessStartMonth == 0) businessStartMonth = 1
                            }
                        }
                        // "개인사업자(전자상거래) / 23년" 형식: 사업자 뒤에 /와 연도
                        if (businessStartYear == 0) {
                            val bizSlashPattern = Pattern.compile("사업자.*?/\\s*(\\d{2,4})년").matcher(line)
                            if (bizSlashPattern.find()) {
                                val y = bizSlashPattern.group(1)!!.toInt()
                                businessStartYear = if (y < 100) 2000 + y else y
                                if (businessStartMonth == 0) businessStartMonth = 1
                            }
                        }
                    }
                    if (businessEndYear == 0) {
                        val bizEndPattern = Pattern.compile("(\\d{2,4})년도?\\s*(?:\\d{1,2}월\\s*)?폐업")
                        val bizEndMatcher = bizEndPattern.matcher(line)
                        if (bizEndMatcher.find()) {
                            val y = bizEndMatcher.group(1)!!.toInt()
                            businessEndYear = if (y < 100) 2000 + y else y
                        }
                    }

                    // 사업 기간이 2020.04 ~ 2025.06과 겹치면 사업자이력 인정
                    if (businessStartYear > 0 && (businessStartYear < 2025 || (businessStartYear == 2025 && (businessStartMonth == 0 || businessStartMonth <= 6))) && (businessEndYear == 0 || businessEndYear >= 2020)) {
                        hasBusinessHistory = true
                    }
                    if (lineNoSpace.contains("법인사업") || lineNoSpace.contains("법인대표") || lineNoSpace.contains("법인운영")) {
                        isCorporateBusiness = true
                    }
                    val bizNoCorp = lineNoSpace.replace("법인사업", "").replace("법인대표", "").replace("법인운영", "")
                    if (bizNoCorp.contains("사업자") || bizNoCorp.contains("개인사업") || bizNoCorp.contains("자영업")) {
                        isBusinessOwner = true
                    }
                    Log.d("HWP_PARSE", "사업자 이력 감지: $line (개업=$businessStartYear, 폐업=$businessEndYear)")
                }
            }

            // ★ 비영리 단체 감지 (전역 - hasBusinessHistory와 무관하게 항상 감지)
            if (!isNonProfit && hasNonProfitKeyword(lineNoSpace)) {
                isNonProfit = true
                Log.d("HWP_PARSE", "비영리 단체 감지: $line")
            }

            // 도박/주식/코인 (변제율 조건) - 사용처 줄 외에도 감지
            // 게임도 도박과 동일 취급 (단기 원금전액)
            if (lineNoSpace.contains("도박")) { hasGambling = true }
            if (lineNoSpace.contains("게임")) { hasGame = true; hasGambling = true }
            if ((lineNoSpace.contains("주식") && !lineNoSpace.contains("주식회사")) || lineNoSpace.contains("전액주식")) { hasStock = true }
            if (lineNoSpace.contains("코인") || lineNoSpace.contains("비트코인") || lineNoSpace.contains("가상화폐")) { hasCrypto = true }

            // 장애등급 감지
            if (fieldCheckStr.startsWith("장애") && !hasDisability) {
                val disabilityVal = extractValue(line, "장애")
                if (disabilityVal.isNotEmpty() && !disabilityVal.contains("x") && !disabilityVal.contains("X") && !disabilityVal.contains("없") && !disabilityVal.contains("무")) {
                    hasDisability = true
                    Log.d("HWP_PARSE", "장애등급 감지: $line")
                }
            }

            // 사대보험 필드: 3.3% → 프리랜서, X → 소득*0.8
            if (fieldCheckStr.startsWith("사대보험")) {
                val insuranceVal = extractValue(line, "사대보험")
                if (insuranceVal.contains("3.3%") || insuranceVal.contains("0.033")) {
                    isFreelancer = true
                    Log.d("HWP_PARSE", "사대보험 3.3%/0.033 → 프리랜서: $line")
                }
                val valNoSpace = insuranceVal.replace("\\s".toRegex(), "")
                if (valNoSpace.equals("x", ignoreCase = true) || valNoSpace.equals("없음") || valNoSpace.equals("미가입")) {
                    noSocialInsurance = true
                    Log.d("HWP_PARSE", "사대보험 X → 소득*0.8 적용: $line")
                }
            }

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

            // 경매 감지 (장기 불가 조건) - 상대방/타인 재산 경매는 제외, "경매x/X" = 경매 없음
            val isOthersContext = lineNoSpace.contains("상대방") || lineNoSpace.contains("타인") || lineNoSpace.contains("상대측")
            val auctionNegated = lineNoSpace.contains("경매x", ignoreCase = true) || lineNoSpace.contains("경매없") || lineNoSpace.contains("경매결과") || lineNoSpace.contains("통보")
            if (lineNoSpace.contains("경매") && !isOthersContext && !auctionNegated) {
                hasAuction = true
                Log.d("HWP_PARSE", "경매 감지: $line")
            } else if (lineNoSpace.contains("경매") && (isOthersContext || auctionNegated)) {
                Log.d("HWP_PARSE", "경매 감지 제외 (${if (auctionNegated) "경매x" else "상대방/타인 재산"}): $line")
            }
            // 압류 감지 (필드 O/X)
            if (lineNoSpace.startsWith("압류")) {
                val seizureFieldValue = extractValue(line, "압류").trim()
                if (seizureFieldValue.startsWith("O", ignoreCase = true)) {
                    hasSeizure = true
                    Log.d("HWP_PARSE", "압류 O 감지: $line")
                } else {
                    Log.d("HWP_PARSE", "압류 X/비어있음: $line")
                }
            }

            // 연봉/소득 섹션 진입
            if (lineNoSpace.contains("연봉") || lineNoSpace.startsWith("소득")) inIncomeSection = true

            // "월 소득 x", "소득 x" 감지 → AI 소득 무시
            if (lineNoSpace.contains("연봉") || lineNoSpace.contains("월소득")) {
                val fieldVal = if (lineNoSpace.contains("연봉")) extractValue(line, "연봉") else extractValue(line, "소득")
                if (fieldVal == "x" || fieldVal.endsWith("x") || fieldVal.contains("소득x") || fieldVal.contains("소득 x") || fieldVal.contains("월소득x")) {
                    isIncomeX = true
                    Log.d("HWP_PARSE", "소득 없음(x) 감지: $line")
                }
            }

            // "월 소득 N만" 직접 파싱 (AI 폴백용, 배우자소득 제외)
            // "수령액 없음/수령 없음" 등 실소득 없음 표현이 있으면 제외
            // ★ "순수익"이 명시되어 있으면 순수익 값 우선 사용 (예상/예정 키워드가 같이 있어도 순수익 우선)
            if ((lineNoSpace.contains("월소득") || lineNoSpace.contains("연봉") || lineNoSpace.contains("순수익")) && !lineNoSpace.contains("배우자") && (lineNoSpace.contains("순수익") || (!lineNoSpace.contains("예상") && !lineNoSpace.contains("예정"))) && !inSpecialNotesSection) {
                if (lineNoSpace.contains("수령") && (lineNoSpace.contains("없") || lineNoSpace.contains("수령0") || lineNoSpace.contains("수령x") || lineNoSpace.contains("수령X"))) {
                    parsedMonthlyIncome = 0
                    Log.d("HWP_PARSE", "실수령액 없음 감지 → parsedMonthlyIncome 초기화 ($line)")
                } else {
                    // 순수익이 명시되어 있으면 순수익 우선
                    val netIncomeMatch = Regex("순수익\\s*(\\d+)\\s*[~～]?\\s*(\\d+)?\\s*만").find(line)
                    val netIncomeDeficit = lineNoSpace.contains("순수익") &&
                            (lineNoSpace.contains("적자") || lineNoSpace.contains("마이너스") || lineNoSpace.contains("순수익-") || lineNoSpace.contains("순수익0"))
                    if (netIncomeDeficit) {
                        // 순수익 0/적자 → 이 항목은 0만 합산 (기존 소득 유지)
                        Log.d("HWP_PARSE", "순수익 적자 감지 → +0만 (기존 소득 ${parsedMonthlyIncome}만 유지) ($line)")
                    } else if (netIncomeMatch != null) {
                        val netMax = netIncomeMatch.groupValues[2].toIntOrNull() ?: netIncomeMatch.groupValues[1].toInt()
                        val lineMonthlyMatch = Regex("월\\s*소득[^\\d]*(\\d+)\\s*만").find(line)
                        val lineMonthlyIncome = lineMonthlyMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        // 전년도 사업수입 + 월 순수익 명시 → 월 순수익 항상 우선 사용 (월 소득은 총소득이라 순수익이 실제 가용소득)
                        if (netMax > 0) {
                            parsedMonthlyIncome += netMax
                            if (lineMonthlyIncome > 0) {
                                if (!specialNotesList.any { it.contains("사업자") }) specialNotesList.add("사업자")
                                Log.d("HWP_PARSE", "월 순수익 우선: 순수익${netMax}만 (월소득${lineMonthlyIncome}만 무시), 사업자 설정 ($line)")
                            } else {
                                Log.d("HWP_PARSE", "월 순수익 직접 파싱: ${netMax}만 ($line)")
                            }
                        }
                    } else {
                        // 괄호 안 금액 추출 (예: "연봉 5200만(369만)" → 369)
                        val parenMatch = Regex("\\((\\d+)만\\)").find(line)
                        val parenIncome = parenMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        // 월세수익 감지 → 단기 소득에만 포함 (구분자 파싱 전에 추출)
                        val rentalInLine = Regex("월세수익\\s*(\\d+)\\s*만").find(line)
                        if (rentalInLine != null) {
                            rentalIncomeMan = rentalInLine.groupValues[1].toInt()
                            Log.d("HWP_PARSE", "월세수익 감지: ${rentalIncomeMan}만 (단기 소득에만 포함)")
                        }
                        // "/" 또는 "," 또는 ">" 뒤 월 소득 (예: "/ 월 소득 330만", "연봉 5200만 > 월소득 350만")
                        // 월세수익/연금 부분은 제거 후 파싱 (연금은 별도로 합산됨)
                        val lineForSlash = line
                            .replace(Regex("/?\\s*월세수익\\s*\\d+\\s*만"), "")
                            .replace(Regex("[+,/]?\\s*(?:국민연금|기초연금|장애연금|노령연금|공무원연금|군인연금|유족연금|개인연금|실업급여)\\s*\\d+\\s*만"), "")
                        val afterSlash = if (lineForSlash.contains(">")) lineForSlash.substringAfter(">")
                            else if (lineForSlash.contains("/")) lineForSlash.substringAfter("/")
                            else if (lineForSlash.contains(",")) lineForSlash.substringAfter(",")
                            else ""
                        val slashRangeMatch = if (afterSlash.isNotEmpty()) Regex("(\\d+)\\s*[~～\\-]\\s*(\\d+)\\s*만").find(afterSlash) else null
                        val slashIncomeMatch = if (afterSlash.isNotEmpty()) Regex("(\\d+)\\s*만").find(afterSlash) else null
                        // 연봉이 포함되면 연봉*0.8/12로 월소득 계산
                        val annualMatch = if (lineNoSpace.contains("연봉")) Regex("연봉\\s*(\\d+)\\s*만").find(line) else null
                        val annualMonthly = if (annualMatch != null) (annualMatch.groupValues[1].toInt() * 0.8 / 12).toInt() else 0
                        if (slashRangeMatch != null) {
                            val slashParsed = maxOf(slashRangeMatch.groupValues[1].toInt(), slashRangeMatch.groupValues[2].toInt())
                            val parsed = maxOf(slashParsed, parenIncome, annualMonthly)
                            if (parsed > 0) {
                                parsedMonthlyIncome += parsed
                                Log.d("HWP_PARSE", "구분자 뒤 월소득 범위 파싱: 구분자=${slashParsed}만, 괄호=${parenIncome}만, 연봉환산=${annualMonthly}만 → +${parsed}만 합계=${parsedMonthlyIncome}만 ($line)")
                            }
                        } else if (slashIncomeMatch != null) {
                            val slashParsed = slashIncomeMatch.groupValues[1].toInt()
                            val parsed = maxOf(slashParsed, parenIncome, annualMonthly)
                            if (parsed > 0) {
                                parsedMonthlyIncome += parsed
                                Log.d("HWP_PARSE", "구분자 뒤 월소득 파싱: 구분자=${slashParsed}만, 괄호=${parenIncome}만, 연봉환산=${annualMonthly}만 → +${parsed}만 합계=${parsedMonthlyIncome}만 ($line)")
                            }
                        } else {
                            // "월 소득 200~220만" → 큰 값(220) 사용
                            val rangeMatch = Regex("월\\s*소득[^\\d]*(\\d+)\\s*[~～\\-]\\s*(\\d+)\\s*만").find(line)
                            val monthlyMatch = Regex("월\\s*소득[^\\d]*(\\d+)\\s*만").find(line)
                            if (rangeMatch != null) {
                                val val1 = rangeMatch.groupValues[1].toInt()
                                val val2 = rangeMatch.groupValues[2].toInt()
                                val parsed = maxOf(val1, val2, annualMonthly)
                                if (parsed > 0) {
                                    parsedMonthlyIncome += parsed
                                    Log.d("HWP_PARSE", "월소득 범위 파싱: ${val1}~${val2}, 연봉환산=${annualMonthly}만 → ${parsed}만 → 합계=${parsedMonthlyIncome}만 ($line)")
                                }
                            } else if (monthlyMatch != null) {
                                val parsed = maxOf(monthlyMatch.groupValues[1].toInt(), annualMonthly)
                                if (parsed > 0) {
                                    parsedMonthlyIncome += parsed
                                    Log.d("HWP_PARSE", "월소득 직접 파싱: 월=${monthlyMatch.groupValues[1]}만, 연봉환산=${annualMonthly}만 → ${parsed}만 → 합계=${parsedMonthlyIncome}만 ($line)")
                                }
                            } else if (annualMonthly > 0) {
                                parsedMonthlyIncome += annualMonthly
                                Log.d("HWP_PARSE", "연봉→월소득 변환: 연봉${annualMatch!!.groupValues[1]}만 × 0.8 ÷ 12 = ${annualMonthly}만 → 합계=${parsedMonthlyIncome}만 ($line)")
                            }
                        }
                    }
                    // 연금/월세수익 추가 (순수익/월소득/연봉 어떤 분기든 공통 적용)
                    val pensionMatch = Regex("(?:국민연금|기초연금|장애연금|노령연금|공무원연금|군인연금|유족연금|개인연금|실업급여)\\s*(\\d+)\\s*만").find(line)
                    if (pensionMatch != null) {
                        val pensionAmount = pensionMatch.groupValues[1].toInt()
                        parsedMonthlyIncome += pensionAmount
                        Log.d("HWP_PARSE", "연금 추가: ${pensionAmount}만 → 합계=${parsedMonthlyIncome}만 ($line)")
                    }
                    val rentalMatch = Regex("월세수익\\s*(\\d+)\\s*만").find(line)
                    if (rentalMatch != null && rentalIncomeMan == 0) {
                        rentalIncomeMan = rentalMatch.groupValues[1].toInt()
                        Log.d("HWP_PARSE", "월세수익 감지: ${rentalIncomeMan}만 (단기 소득에만 포함)")
                    }
                }
            }

            // 연봉 연속줄: 기존 키워드 없지만 소득 섹션 내 금액 → 추가 소득
            // "소득"이 포함되지만 ">"가 없는 라인(예: 전년도 사업소득 882만)은 월소득 환산이 아니므로 제외
            if (inIncomeSection && !lineNoSpace.contains("월소득") && !lineNoSpace.contains("연봉") && !lineNoSpace.contains("순수익") && !lineNoSpace.contains("배우자") && !isKnownField
                && !(lineNoSpace.contains("소득") && !line.contains(">"))) {
                val extraIncomeMatch = Regex("(\\d+)\\s*만").find(line)
                if (extraIncomeMatch != null) {
                    val extra = extraIncomeMatch.groupValues[1].toInt()
                    if (extra in 1..9999) {
                        // 전년도/연간 금액은 연봉이므로 12로 나눠서 월소득으로 환산
                        val isAnnual = lineNoSpace.contains("전년도") || lineNoSpace.contains("연간")
                        val addAmount = if (isAnnual) extra / 12 else extra
                        parsedMonthlyIncome += addAmount
                        Log.d("HWP_PARSE", "연봉 연속줄 소득 추가: ${addAmount}만${if (isAnnual) " (연${extra}만÷12)" else ""} → 합계=${parsedMonthlyIncome}만 ($line)")
                    }
                }
            }

            // 소득 예상/예정 감지 + 금액 추출 (실효예정 등 무관한 문맥 제외)
            // ★ 같은 줄에 "순수익"이 있으면 실수익 우선 → 예정/예상 처리 안 함
            if ((lineNoSpace.contains("예상") || lineNoSpace.contains("예정")) && !lineNoSpace.contains("실효") && !lineNoSpace.contains("순수익")) {
                val hasIncomeMonth = Regex("(?<!\\d)월").containsMatchIn(lineNoSpace)  // "4월" 등 날짜 월 제외
                if (lineNoSpace.contains("소득") || lineNoSpace.contains("연봉") || lineNoSpace.contains("월급") || hasIncomeMonth) {
                    isIncomeEstimated = true
                    // "월 350만 예정", "월 350만 예상", "예상 260만" 등에서 금액 추출
                    val incomeMatch = Regex("월\\s*(\\d+)만\\s*(?:예정|예상)").find(line)
                        ?: Regex("(\\d+)만\\s*(?:예정|예상)").find(line)
                        ?: Regex("(?:예상|예정)\\s*(?:월\\s*)?(?:소득\\s*)?(\\d+)\\s*만").find(line)
                    if (incomeMatch != null) {
                        val parsed = incomeMatch.groupValues[1].toInt()
                        if (parsed > 0) {
                            estimatedIncomeParsed = maxOf(estimatedIncomeParsed, parsed)
                            Log.d("HWP_PARSE", "소득 예정/예상 금액 감지: ${parsed}만 → 적용=${estimatedIncomeParsed}만 ($line)")
                        }
                    } else {
                        Log.d("HWP_PARSE", "소득 예정/예상 감지 (금액 없음): $line")
                    }
                }
            }

            // 한국주택금융공사 집담보 감지
            if (lineNoSpace.contains("주택금융공사")) {
                hasHfcMortgage = true
                Log.d("HWP_PARSE", "한국주택금융공사 집담보 감지: $line")
            }

            // 예적금 감지 (대출과목 라인은 제외 - "예/적금담보대출" 오인식 방지)
            if (savingsDeposit == 0 && !lineNoSpace.contains("대출") && (lineNoSpace.contains("예적금") || lineNoSpace.contains("예금") || lineNoSpace.contains("적금"))) {
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

            // 보험 대출 감지 (대상채무 제외)
            if (lineNoSpace.contains("보험") && (lineNoSpace.contains("대출") || lineNoSpace.contains("약관"))) {
                hasInsurancePolicyLoan = true
                Log.d("HWP_PARSE", "보험대출 감지: $line")
            }

            // 질권설정 채권사별 구분 (x=대상채무 포함, o=담보 유지)
            if (hasNoJilgwon(lineNoSpace, line)) {
                jeonseNoJilgwon = true
                Log.d("HWP_PARSE", "질권설정 없음 감지 → 대상채무 포함: $line")
            }
            // 질권설정x 채권사 추출 (해당 채권사는 담보→대상채무 포함)
            val jilgwonXPattern = Pattern.compile("([가-힣a-zA-Zａ-ｚＡ-Ｚ]+)\\s*질권설정\\s*[xX]")
            val jilgwonXMatcher = jilgwonXPattern.matcher(line)
            while (jilgwonXMatcher.find()) {
                val creditorName = jilgwonXMatcher.group(1)!!.trim()
                if (creditorName.isNotEmpty()) {
                    jilgwonXCreditors.add(creditorName)
                    Log.d("HWP_PARSE", "질권설정x 채권사 감지: $creditorName → 대상채무 포함")
                }
            }
            // 질권설정o 채권사 추출 (해당 채권사는 담보 유지)
            val jilgwonOPattern = Pattern.compile("([가-힣a-zA-Zａ-ｚＡ-Ｚ]+)\\s*질권설정\\s*[oO]")
            val jilgwonOMatcher = jilgwonOPattern.matcher(line)
            while (jilgwonOMatcher.find()) {
                val creditorName = jilgwonOMatcher.group(1)!!.trim()
                if (creditorName.isNotEmpty()) {
                    jilgwonOCreditors.add(creditorName)
                    Log.d("HWP_PARSE", "질권설정o 채권사 감지: $creditorName → 담보 유지")
                }
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
                !lineNoSpace.contains("연체일수x") && !lineNoSpace.contains("연체일수X") &&
                !lineNoSpace.contains("연체안") && !lineNoSpace.contains("미연체") &&
                lineNoSpace != "연체일수") {  // "연체일수" 필드 라벨만 있고 값 없으면 스킵
                if (lineNoSpace.contains("오늘부터") || lineNoSpace.contains("막연체") ||
                    lineNoSpace.contains("이제연체") || lineNoSpace.contains("방금연체")) {
                    if (delinquentDays < 1) delinquentDays = 1
                }
                // 카테고리형 연체일수: "90일 이상", "30일 이상 90일 미만", "30일 미만"
                val is90Plus = lineNoSpace.contains("90일이상") || lineNoSpace.matches(Regex(".*90일\\s*이상.*"))
                val is30to90 = lineNoSpace.contains("30일이상") && (lineNoSpace.contains("90일미만") || lineNoSpace.contains("미만"))
                val isUnder30 = lineNoSpace.contains("30일미만") && !lineNoSpace.contains("이상")
                if (is30to90) {
                    delinquentDays = maxOf(delinquentDays, 30)
                    Log.d("HWP_PARSE", "연체 카테고리: 30일이상 90일미만 → 30일 ($line)")
                } else if (is90Plus) {
                    delinquentDays = maxOf(delinquentDays, 90)
                    Log.d("HWP_PARSE", "연체 카테고리: 90일이상 → 90일 ($line)")
                } else if (isUnder30) {
                    delinquentDays = maxOf(delinquentDays, 1)
                    Log.d("HWP_PARSE", "연체 카테고리: 30일미만 → 1일 ($line)")
                } else {
                var m = Pattern.compile("(\\d+)개월").matcher(line)
                if (m.find()) delinquentDays = maxOf(delinquentDays, m.group(1)!!.toInt() * 30)
                m = Pattern.compile("(\\d+)달").matcher(line)
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
                } // else (카테고리형이 아닌 경우)
                // 실제 연체일수 동기화 (다른 단계 진행으로 인한 1095일과 구분)
                actualDelinquentDays = maxOf(actualDelinquentDays, delinquentDays)
            }

            // [최종정리] 감지 → 파싱 종료
            if (lineNoSpace.contains("최종정리")) {
                inDebtSection = false
                shouldStopParsing = true
                return
            }


            // 지급명령 감지 (필드 O/X)
            if (lineNoSpace.contains("지급명령")) {
                val paymentOrderValue = extractValue(line, "지급명령").trim()
                if (paymentOrderValue.startsWith("O", ignoreCase = true)) {
                    hasPaymentOrder = true
                    Log.d("HWP_PARSE", "지급명령 O 감지: $line")
                } else {
                    Log.d("HWP_PARSE", "지급명령 X/비어있음: $line")
                }
            }

            // 유예기간 감지 (상환내역서 텍스트에서 직접 파싱)
            if (lineNoSpace.contains("유예기간") || lineNoSpace.contains("거치기간")) {
                if (lineNoSpace.contains("별도")) {
                    // "유예기간 별도" → PDF OCR에서 이미 감지한 값이 없을 때만 0으로
                    if (aiDefermentMonths <= 0) {
                        aiDefermentMonths = 0
                        Log.d("HWP_PARSE", "유예기간 별도 → 0개월")
                    } else {
                        Log.d("HWP_PARSE", "유예기간 별도 무시 (PDF에서 ${aiDefermentMonths}개월 감지됨)")
                    }
                } else {
                    // "유예기간 6개월", "거치기간 12개월" 등 키워드 바로 뒤 숫자만 매칭
                    val deferM = Pattern.compile("(?:유예기간|거치기간)\\s*(\\d+)\\s*개?월").matcher(line.replace("\\s+".toRegex(), " "))
                    if (deferM.find()) {
                        val months = deferM.group(1)!!.toInt()
                        if (months in 1..24 && months > aiDefermentMonths) {
                            aiDefermentMonths = months
                            Log.d("HWP_PARSE", "유예기간 감지: ${months}개월")
                        }
                    }
                }
            }


            // 실효 감지 → 장기연체자 (줄 위치 무관)
            // "워크아웃 실효", "신복위 실효" 등 (개인회생 면책과 같은 줄에 있을 수 있음)
            // ★ 보험내역 실효는 제외
            if (lineNoSpace.contains("실효") && !lineNoSpace.contains("보험")) {
                delinquentDays = maxOf(delinquentDays, 1095)
                if (lineNoSpace.contains("워크아웃") || lineNoSpace.contains("워크") || lineNoSpace.contains("신복위") || lineNoSpace.contains("신용회복")) {
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

            // 면책 감지는 채무조정 필드 내에서만 처리 (아래 "채무조정 이력" 블록)

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
                if (lineNoSpace.contains("비양육") || lineNoSpace.contains("전처") || lineNoSpace.contains("전남편") ||
                    (lineNoSpace.contains("배우자") && lineNoSpace.contains("양육"))) {
                    minorChildren = 0
                    Log.d("HWP_PARSE", "비양육/전배우자/배우자 양육 감지: 미성년 0으로 설정")
                }
                // 양육O 감지 (본인이 양육 중)
                if (lineNoSpace.contains("양육O") || lineNoSpace.contains("양육o") || lineNoSpace.contains("양육중")) {
                    childSupportReceiving = true
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
                    // 입금받음 → 수급 (본인 양육), "주는거 없음" → 받지도 주지도 않음 (수급으로 분류)
                    // 송금중 → 지급(payout) → 수급 아님
                    if (lineNoSpace.contains("입금받") || lineNoSpace.contains("주는거없")) {
                        childSupportReceiving = true
                    } else if (lineNoSpace.contains("송금중") || lineNoSpace.contains("송금받")) {
                        // "송금중" → 본인이 송금 = 지급
                        // "송금받" → 본인이 송금 받음 = 수급
                        childSupportReceiving = lineNoSpace.contains("송금받")
                    }
                    Log.d("HWP_PARSE", "양육비 감지: ${childSupportAmount}만, 수급=${childSupportReceiving}")
                }
                if (line.contains("대학생")) {
                    val m = Pattern.compile("대학생\\s*(\\d+)").matcher(line)
                    if (m.find()) collegeChildren = m.group(1)!!.toInt()
                }
            }

            // 60세 이상 부모
            if (lineNoSpace.contains("60세") || lineNoSpace.contains("만60세")) {
                var count = 0
                val afterColon = extractValue(line, "60세부모").let { v ->
                    // extractValue가 전체 line을 반환한 경우 (키워드 매칭 실패 시) 기존 방식 폴백
                    if (v == line.trim() && !line.contains(":") && !line.contains("：")) line else v
                }
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
                } else if (afterColon.trim().let { it.startsWith("O", true) || it.startsWith("ㅇ") }) {
                    count = 1
                }
                if (count > 0) parentCount = count
            }

            // 대출사용처 (전역 감지로 이동됨 - 여기서는 추가 키워드만)
            // 도박/주식/코인은 위 전역 감지에서 처리

            // 특이사항 (연속줄 지원)
            if (lineNoSpace.contains("특이사항") || lineNoSpace.contains("특이:") || lineNoSpace.contains("특이：")) {
                inSpecialNotesSection = true
            }
            if (inSpecialNotesSection) {
                val content = if (lineNoSpace.contains("특이사항") || lineNoSpace.contains("특이:") || lineNoSpace.contains("특이：")) {
                    // 특이사항 키워드 뒤 전체를 값으로 추출 (중간 콜론이 있어도 보존)
                    val m = Regex("특이\\s*사항\\s*[:：]?\\s*|특이\\s*[:：]\\s*").find(line)
                    if (m != null) line.substring(m.range.last + 1).trim() else line.trim()
                } else line.trim()
                if (content.isNotEmpty() && !content.equals("x", true)) {
                    // 월 생활비 → 소득으로 적용
                    val livingExpMatch = Regex("생활비\\s*(\\d+)\\s*만").find(content)
                    if (livingExpMatch != null && parsedMonthlyIncome == 0) {
                        parsedMonthlyIncome = livingExpMatch.groupValues[1].toInt()
                        Log.d("HWP_PARSE", "특이사항 생활비 → 소득 적용: ${parsedMonthlyIncome}만 ($content)")
                    }
                    // 수당 (자녀수당, 양육수당 등) → 소득에 합산
                    val allowanceMatch = Regex("수당\\s*[:：]?\\s*(\\d+)\\s*만").find(content)
                    if (allowanceMatch != null) {
                        val allowance = allowanceMatch.groupValues[1].toInt()
                        parsedMonthlyIncome += allowance
                        Log.d("HWP_PARSE", "특이사항 수당 → 소득 합산: +${allowance}만 → ${parsedMonthlyIncome}만 ($content)")
                    }
                    // 사업장 보증금 → 재산 합산
                    val bizDepositMatch = Regex("보증금\\s*[:：]?\\s*(\\d+)\\s*만").find(content)
                    if (bizDepositMatch != null) {
                        bizDeposit = bizDepositMatch.groupValues[1].toInt()
                        Log.d("HWP_PARSE", "특이사항 사업장 보증금 → 재산 합산: ${bizDeposit}만 ($content)")
                    }
                    // 완납 채권사 감지 (예: "국민카드 완납", "삼성카드 완납함")
                    // 예금담보/적금담보 완납은 이미 담보로 제외되어 있으므로 스킵
                    if (content.contains("완납") && !content.replace("\\s".toRegex(), "").contains("예금담보완납") && !content.replace("\\s".toRegex(), "").contains("적금담보완납")) {
                        val creditorKws = listOf("국민", "신한", "삼성", "현대", "롯데", "하나", "우리", "비씨", "농협", "수협", "씨티", "카카오", "토스", "케이뱅크")
                        for (kw in creditorKws) {
                            if (content.contains(kw)) {
                                paidOffCreditorKeywords.add(kw)
                                Log.d("HWP_PARSE", "특이사항 완납 채권사 감지: $kw ($content)")
                            }
                        }
                    }

                    content.split("[,，/]".toRegex()).forEach { part ->
                        val trimmed = part.trim()
                        val isAutoDetected = trimmed.equals("x", true) ||
                                trimmed.contains("모르게") || trimmed.contains("경매") || trimmed.contains("압류") ||
                                trimmed.contains("도박") || trimmed.contains("주식") || trimmed.contains("코인") ||
                                trimmed.contains("민사") || trimmed.contains("소송") || trimmed.contains("지급명령") ||
                                trimmed.contains("건강보험") || trimmed.replace("\\s+".toRegex(), "") == "특이사항"
                        if (trimmed.isNotEmpty() && !isAutoDetected) specialNotesList.add(trimmed)
                    }
                }
            }

            // 채무조정 이력 (채무조정: 줄에서 연도 추출)
            if (lineNoSpace.contains("채무조정")) {
                val content = extractValue(line, "채무조정")
                if (!content.equals("x", true) && content.isNotEmpty()) {
                    // 새출발 부실차주 또는 거절 감지 → 새새 불가
                    if (content.contains("새출발") && (content.contains("부실차주") ||
                                content.contains("안되") || content.contains("안됬") || content.contains("거절") || content.contains("반려"))) {
                        hasSaechulbalBusilChaju = true
                        Log.d("HWP_PARSE", "새출발 불가 감지 → 새새 불가: $content")
                    }
                    // ★ / 기준 분리 후 각 세그먼트별 독립 판단
                    // 전체 내용에 결과(완납/면책 등)가 있으면 진행중 감지 스킵
                    val contentHasAnyResult = hasDebtResult(content)
                    val debtAdjSegments = content.split("/", "／").map { it.trim() }.filter { it.isNotEmpty() }
                    for (seg in debtAdjSegments) {
                        val segNoSpace = seg.replace("\\s+".toRegex(), "")
                        // 면책 감지 (파산만 적혀있어도 파산 면책으로 인식)
                        val hasDischargeKeyword = seg.contains("면책") || seg.contains("면채")
                        val isStandalonePasan = seg.contains("파산") && !hasDischargeKeyword &&
                            !seg.contains("폐지") && !seg.contains("기각") && !seg.contains("취하") &&
                            !seg.contains("진행") && !seg.contains("접수") && !seg.contains("신청")
                        if (hasDischargeKeyword || isStandalonePasan) {
                            hasDischarge = true
                            var segYear = 0; var segMonth = 0
                            var m = Pattern.compile("(\\d{2})년도?\\s*(\\d{1,2})월?").matcher(seg)
                            if (m.find()) {
                                segYear = 2000 + m.group(1)!!.toInt()
                                if (m.group(2) != null) segMonth = m.group(2)!!.toInt()
                            } else {
                                m = Pattern.compile("(20\\d{2})\\.?(\\d{1,2})?").matcher(seg)
                                if (m.find()) {
                                    segYear = m.group(1)!!.toInt()
                                    if (m.group(2) != null) segMonth = m.group(2)!!.toInt()
                                }
                            }
                            if (segYear == 0) {
                                m = Pattern.compile("(\\d{2})[.．]\\s*(\\d{1,2})월?").matcher(seg)
                                if (m.find()) {
                                    segYear = 2000 + m.group(1)!!.toInt()
                                    if (m.group(2) != null) segMonth = m.group(2)!!.toInt()
                                }
                            }
                            // 날짜 없이 면책만 적혀있으면 어제 날짜로 면책된 것으로 처리
                            if (segYear == 0) {
                                val yesterday = java.time.LocalDate.now().minusDays(1)
                                segYear = yesterday.year
                                segMonth = yesterday.monthValue
                                Log.d("HWP_PARSE", "면책 날짜 없음 → 어제 날짜로 설정: ${segYear}.${segMonth} ($seg)")
                            }
                            // 파산 면책과 회생 면책을 독립 추적
                            val isThisBankruptcy = seg.contains("파산")
                            if (isThisBankruptcy) {
                                if (segYear > bankruptcyDischargeYear || (segYear == bankruptcyDischargeYear && segMonth > bankruptcyDischargeMonth) || bankruptcyDischargeYear == 0) {
                                    bankruptcyDischargeYear = segYear; bankruptcyDischargeMonth = segMonth
                                }
                            } else {
                                if (segYear > recoveryDischargeYear || (segYear == recoveryDischargeYear && segMonth > recoveryDischargeMonth) || recoveryDischargeYear == 0) {
                                    recoveryDischargeYear = segYear; recoveryDischargeMonth = segMonth
                                }
                            }
                            Log.d("HWP_PARSE", "면책 감지 (세그먼트): $seg, 파산=$isThisBankruptcy${if (isStandalonePasan) "(파산만 적혀있어 면책 처리)" else ""}, 년도=$segYear, 월=$segMonth (파산면책=$bankruptcyDischargeYear, 회생면책=$recoveryDischargeYear)")
                        }
                        // 실효 감지 → 장기연체자 (1095일)
                        if (seg.contains("실효")) {
                            delinquentDays = maxOf(delinquentDays, 1095)
                            if (seg.contains("워크아웃") || seg.contains("워크") || seg.contains("신복위") || seg.contains("신용회복")) {
                                hasWorkoutExpired = true
                            }
                            Log.d("HWP_PARSE", "실효 감지 (세그먼트) → 장기연체자: $seg, 워크아웃실효=$hasWorkoutExpired")
                        }
                        // 폐지/기각/취하 감지 → 장기연체자 (1095일)
                        if (seg.contains("폐지") || seg.contains("기각") || seg.contains("취하")) {
                            delinquentDays = maxOf(delinquentDays, 1095)
                            if (seg.contains("회생") || hasPersonalRecovery) {
                                isDismissed = true
                            }
                            Log.d("HWP_PARSE", "폐지/기각/취하 감지 (세그먼트) → 장기연체자: $seg, 회생폐지=$isDismissed")
                        }
                        // 연체일수는 "연체일수" 필드에서만 체크 (채무조정에서는 파싱 안 함)
                        // "진행중" 문구가 명시된 경우에만 장기연체자로 판단
                        // 그 외 (회생/워크/신복/신속/접수 등만 적힌 경우)는 연체일수 필드로 판단
                        val segHasResult = hasDebtResult(seg)
                        if (!segHasResult && !contentHasAnyResult && seg.contains("진행중")) {
                            delinquentDays = maxOf(delinquentDays, 1095)
                            hasOngoingProcess = true
                            Log.d("HWP_PARSE", "제도 진행중 (세그먼트) → 장기연체자: $seg")
                        }
                    }
                }
            }

            // 차량 파싱 (재산은 AI가 처리하지만, 차량 대수/처분 판단용) - 대출과목 섹션 제외
            var wasCarLine = false
            val isDebtEntryLine = Pattern.compile("\\d{2}년\\s*\\d{1,2}월\\s*\\d{1,2}일").matcher(lineNoSpace).find()
            var isCarLine = !isDebtEntryLine && !inLoanCategorySection && (lineNoSpace.contains("차량") || (line.contains("자동차") && !lineNoSpace.contains("자동차금융") && !lineNoSpace.contains("자동차담보")) ||
                    (Pattern.compile("\\d{2}년").matcher(lineNoSpace).find() &&
                            (lineNoSpace.contains("시세") || lineNoSpace.contains("본인명의") || lineNoSpace.contains("배우자명의") || lineNoSpace.contains("공동명의"))))

            // 부동산 관련 키워드가 포함되면 차량 아님 (지역 줄 오인식 방지)
            if (isCarLine && !lineNoSpace.contains("차량") && (lineNoSpace.contains("아파트") || lineNoSpace.contains("주택") ||
                        lineNoSpace.contains("빌라") || lineNoSpace.contains("오피스텔") || lineNoSpace.contains("부동산"))) {
                isCarLine = false
            }
            val isMainCarLine = lineNoSpace.contains("시세") || Pattern.compile("\\d{2}년식").matcher(lineNoSpace).find()
            // 대출과목 엔트리 패턴 감지: "순번+금융기관명+차량담보" → 차량 추가대출 아님
            val startsWithSeqInst = Pattern.compile("^\\d{1,2}[가-힣]").matcher(lineNoSpace).find()
            if (isCarLine && (lineNoSpace.contains("차량담보") || lineNoSpace.contains("차량할부")) && !isMainCarLine && !startsWithSeqInst) {
                val additionalLoan = extractAmount(line)
                if (additionalLoan > 0) {
                    carTotalLoan += additionalLoan
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
            } else if (isCarLine && startsWithSeqInst && !isMainCarLine) {
                // 순번+기관명으로 시작하는 채무 테이블 행은 차량 아님
                Log.d("HWP_PARSE", "차량 감지 제외 (채무 테이블 행): $line")
                isCarLine = false
            }
            val prevWasCarLine = wasCarLine
            // 공동명의 차량 연속줄: "(시세..." 형식 → 이전 차량에 시세/담보 업데이트
            if (!isCarLine && carInfoList.isNotEmpty() && line.trim().startsWith("(") &&
                (lineNoSpace.contains("시세") || lineNoSpace.contains("담보") || lineNoSpace.contains("대출"))) {
                val lastInfo = carInfoList.last()
                val addSise = extractAmountAfterKeyword(lineNoSpace, "시세")
                val addLoan = maxOf(extractAmountAfterKeyword(lineNoSpace, "담보"), extractAmountAfterKeyword(lineNoSpace, "대출"))
                if (addSise > 0) { carTotalSise = carTotalSise - lastInfo[0] + addSise; lastInfo[0] = addSise }
                if (addLoan > 0) { carTotalLoan = carTotalLoan - lastInfo[1] + addLoan; lastInfo[1] = addLoan }
                Log.d("HWP_PARSE", "차량 연속줄 합산: 시세=$addSise, 담보=$addLoan → 누적시세=$carTotalSise, 누적담보=$carTotalLoan")
                wasCarLine = true
                return
            }
            wasCarLine = isCarLine

            if (isCarLine) {
                if (lineNoSpace.contains("장기렌트") || lineNoSpace.contains("렌트") || lineNoSpace.contains("리스")) {
                    Log.d("HWP_PARSE", "장기렌트/리스 감지 (차량 개수/처분 제외): $line")
                    return
                }
                if (line.contains(": x") || line.contains(":x") || line.endsWith(": x") ||
                    (line.contains("\t") && line.substringAfter("\t").trim().equals("x", true))) return
                // 차량 이미 처분/없음 관련 텍스트는 차량으로 카운트하지 않음 (진행중은 제외 - 아직 보유)
                if ((lineNoSpace.contains("공매") && !lineNoSpace.contains("진행")) || lineNoSpace.contains("처분후") || lineNoSpace.contains("잔존") ||
                    lineNoSpace.contains("잔채무") || lineNoSpace.contains("없음") || lineNoSpace.contains("폐차")) return

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
                var carLoan = 0; var carDamboOnly = 0; var carHalbuOnly = 0
                if (lineNoSpace.contains("담보") || lineNoSpace.contains("대출") || lineNoSpace.contains("할부")) {
                    carDamboOnly = maxOf(extractAmountAfterKeyword(lineNoSpace, "담보"), extractAmountAfterKeyword(lineNoSpace, "대출"))
                    carHalbuOnly = extractAmountAfterKeyword(lineNoSpace, "할부")
                    carLoan = maxOf(carDamboOnly, carHalbuOnly)
                }
                // 시세/대출/월납 모두 없으면 실제 차량 아님 (라벨만 있는 경우 등)
                var carMonthly = 0
                val carPaymentM = Pattern.compile("(?:원금\\+이자|이자만|원금만|월)\\s*(\\d+)만").matcher(lineNoSpace)
                while (carPaymentM.find()) carMonthly += carPaymentM.group(1)!!.toInt()
                val isJointCarLine = lineNoSpace.contains("공동명의") || (lineNoSpace.contains("공동") && !lineNoSpace.contains("공동인증"))
                if (carSise == 0 && carLoan == 0 && carMonthly == 0 && !isJointCarLine) return

                // 2줄=1차량: 공동명의 차량의 이전 줄이 차량이고 현재 줄이 "("로 시작하면 이전 차량 값 업데이트
                if (prevWasCarLine && carInfoList.isNotEmpty() && line.trim().startsWith("(") && hasJointCar) {
                    val lastInfo = carInfoList.last()
                    if (carSise > 0) { carTotalSise = carTotalSise - lastInfo[0] + carSise; lastInfo[0] = carSise }
                    if (carLoan > 0) { carTotalLoan = carTotalLoan - lastInfo[1] + carLoan; lastInfo[1] = carLoan }
                    if (carMonthly > 0) { carMonthlyPayment = carMonthlyPayment - lastInfo[2] + carMonthly; lastInfo[2] = carMonthly }
                    Log.d("HWP_PARSE", "차량 연속줄 합산: 시세=$carSise, 담보=$carLoan, 월납=$carMonthly → 누적시세=$carTotalSise, 누적담보=$carTotalLoan")
                    return
                }

                val isJointCar = isJointCarLine
                // 공동명의 본인 지분율 계산 (비율 명시 없으면 ÷2)
                var carJointRatio = 0  // 0=비공동, 1~100=본인 적용 비율(%)
                if (isJointCar) {
                    val ownerRatio = parseOwnerRatioPct(lineNoSpace) ?: 50
                    carJointRatio = ownerRatio
                }
                val isSpouseCar = (lineNoSpace.contains("배우자명의") || lineNoSpace.contains("배우자")) && !isJointCar
                if (isSpouseCar) {
                    // 배우자 단독 명의 차량은 계산 재산에 포함하지 않음 (표시용+단기에만 ÷2로 포함)
                    spouseCarSiseTotal += carSise / 2
                    Log.d("HWP_PARSE", "배우자명의 차량 제외: 시세=${carSise}만 (표시용 합계=${spouseCarSiseTotal}만) - $line")
                    return
                }
                // 외제차 여부 판별
                val isForeignCar = foreignCarBrands.any { brand -> lineNoSpace.contains(brand, ignoreCase = true) }

                carTotalSise += carSise; carTotalLoan += carLoan
                carMonthlyPayment += carMonthly
                // 차량명 추출: "년식" 뒤 한글 차명 우선, 없으면 "차량" 키워드 뒤
                val carNameByYear = Regex("\\d{2}년식\\s*([가-힣a-zA-Zａ-ｚＡ-Ｚ0-9０-９]+)").find(lineNoSpace)
                val carNameByKeyword = Regex("차량\\s*(.+?)\\s*(?:시세|담보|대출|할부|월|\\d{2}년식|본인|배우자|공동)").find(line)
                val carName = carNameByYear?.groupValues?.get(1)?.trim()?.takeIf { it.length in 1..10 }
                    ?: carNameByKeyword?.groupValues?.get(1)?.trim()?.takeIf { it.length in 1..15 }
                    ?: "차량${carCount + 1}"
                // 개별 차량 정보 저장: [시세, 대출, 월납부, 배우자(1/0), 외제(1/0), 공동비율(%,0=비공동)]
                carInfoList.add(intArrayOf(carSise, carLoan, carMonthly, if (isSpouseCar) 1 else 0, if (isForeignCar) 1 else 0, carJointRatio))
                carNameList.add(carName)
                carDamboAmountList.add(carDamboOnly)
                carHalbuAmountList.add(carHalbuOnly)
                carCount++
                if (isJointCar) hasJointCar = true
                Log.d("HWP_PARSE", "차량 파싱: 시세=$carSise, 담보=$carLoan, 월납=$carMonthly, 배우자=$isSpouseCar, 공동=$isJointCar, 외제=$isForeignCar, 누적시세=$carTotalSise, 누적담보=$carTotalLoan")
            }

            // 차량 라인은 채무 파싱에서 제외 (중복 카운트 방지)
            if (wasCarLine) return

            // 카드이용금액 테이블 섹션 감지 (채무 파싱 전에 체크)
            if (lineNoSpace.contains("카드사") && lineNoSpace.contains("이용금액")) {
                inCardUsageTableSection = true
                Log.d("HWP_PARSE", "카드이용금액 섹션 진입: $line")
            }
            if (inCardUsageTableSection && !lineNoSpace.contains("카드사")) {
                if (lineNoSpace.contains("대출과목") || lineNoSpace.contains("기타채무") || lineNoSpace.contains("요약") ||
                    lineNoSpace.contains("특이") || lineNoSpace.contains("채무조정") || lineNoSpace.isBlank()) {
                    inCardUsageTableSection = false
                }
            }

            // 6개월 이내 채무 파싱 (카드이용 테이블 안에서는 건너뛰기)
            var loanYear = 0; var loanMonth = 0; var loanDay = 0
            val dateMatcher = Pattern.compile("(\\d{4})[.\\-](\\d{1,2})[.\\-](\\d{1,2})").matcher(line)
            val dateMatcher1b = Pattern.compile("(?<!\\d)(\\d{2})[.\\-](\\d{1,2})[.\\-](\\d{1,2})(?!\\d)").matcher(line) // 25.04.22 형식
            val dateMatcher2 = Pattern.compile("(\\d{2})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일?").matcher(line)
            val dateMatcher3 = Pattern.compile("(\\d{2})년\\s*(\\d{1,2})월").matcher(line)
            val dateMatcher4 = Pattern.compile("^(\\d{2})년\\s+").matcher(line)
            val dateMatcher5 = Pattern.compile("(?<!\\d)(20\\d{2})[.\\-](\\d{1,2})(?!\\d)").matcher(line)
            val dateMatcher6 = Pattern.compile("(?<!\\d)(20\\d{2})(?!\\d)").matcher(line)

            // 날짜 칸이 "?"인 경우 → 날짜 없음 유지 (loanYear=0), 채무금액은 정상 파싱되어 총채무에 포함, 6개월 수집은 자동 스킵
            val hasQuestionMarkDate = line.contains("?") && !inCardUsageTableSection && !inOtherDebtSection
            if (hasQuestionMarkDate) {
                Log.d("HWP_PARSE", "날짜에 ? 포함 → 날짜 스킵 (총채무만 포함): $line")
            }
            if (!inCardUsageTableSection && !inOtherDebtSection && !hasQuestionMarkDate) {
                if (dateMatcher.find()) {
                    loanYear = dateMatcher.group(1)!!.toInt(); loanMonth = dateMatcher.group(2)!!.toInt(); loanDay = dateMatcher.group(3)!!.toInt()
                } else if (dateMatcher1b.find()) {
                    loanYear = 2000 + dateMatcher1b.group(1)!!.toInt(); loanMonth = dateMatcher1b.group(2)!!.toInt(); loanDay = dateMatcher1b.group(3)!!.toInt()
                } else if (dateMatcher2.find()) {
                    loanYear = 2000 + dateMatcher2.group(1)!!.toInt(); loanMonth = dateMatcher2.group(2)!!.toInt(); loanDay = dateMatcher2.group(3)!!.toInt()
                } else if (dateMatcher3.find()) {
                    loanYear = 2000 + dateMatcher3.group(1)!!.toInt(); loanMonth = dateMatcher3.group(2)!!.toInt(); loanDay = 15
                } else if (dateMatcher4.find()) {
                    loanYear = 2000 + dateMatcher4.group(1)!!.toInt(); loanMonth = 1; loanDay = 1
                } else if (dateMatcher5.find()) {
                    loanYear = dateMatcher5.group(1)!!.toInt(); loanMonth = dateMatcher5.group(2)!!.toInt(); loanDay = 1
                } else if (dateMatcher6.find()) {
                    loanYear = dateMatcher6.group(1)!!.toInt(); loanMonth = 1; loanDay = 1
                }
            } else if (dateMatcher.find() || dateMatcher5.find()) {
                Log.d("HWP_PARSE", "카드이용섹션으로 날짜 스킵됨: $line")
            }

            val hasFinancialKeyword = line.contains("은행") || line.contains("캐피탈") || line.contains("카드") ||
                    line.contains("금융") || line.contains("저축") || line.contains("보증") ||
                    line.contains("공사") || line.contains("재단") || line.contains("농협") ||
                    line.contains("신협") || line.contains("새마을") || line.contains("생명") ||
                    line.contains("화재") || line.contains("공단") || line.contains("대부") ||
                    line.contains("기금") || line.contains("뱅크") ||
                    line.contains("피에프씨") || line.contains("PFC") || line.contains("테크놀로지") ||
                    line.contains("머니무브") || line.contains("사금융") || line.contains("일수") ||
                    line.contains("통신") || line.contains("텔레콤") || line.contains("모바일")

            // 날짜 없는 채무현황 "순번X 채권사 금액" 형식 → 대상채무 + 채권사맵에 추가 (기타채무/대출과목 섹션 제외)
            // 헤더 순서 고정: 순번, 구분, 대출종류, 기관명, 발생일자, 금액
            if (loanYear == 0 && inDebtSection && !inOtherDebtSection && !inLoanCategorySection && !inPropertySection && !inRegionField && lineNoSpace.matches(Regex("^순?번?\\d{1,2}.+\\d+만.*"))) {
                val seqCreditorM = Pattern.compile("순?번?(\\d{1,2})\\s*(.+?)\\s+(\\d[\\d,]*)만").matcher(line.trim())
                if (seqCreditorM.find()) {
                    val fullMiddle = seqCreditorM.group(2)!!.trim()
                    val columns = fullMiddle.split(Regex("\\s+"))
                    // 컬럼이 4개 이상(구분/대출종류/기관명/발생일자)이면 3번째(기관명)만 추출
                    val credName = if (columns.size >= 4) columns[2] else fullMiddle
                    val loanType = if (columns.size >= 4) columns[1] else ""
                    val seqNum = seqCreditorM.group(1)!!.toInt()
                    val amountMan = seqCreditorM.group(3)!!.replace(",", "").toIntOrNull() ?: 0
                    val isDamboBySeq = seqNum in 1..30 && excludedSeqNumbers.contains(seqNum)
                    val isInsurancePolicy = lineNoSpace.contains("약관") || lineNoSpace.contains("보험담보")
                    val isCashServiceSeq = loanType.contains("현금서비스") || lineNoSpace.contains("(0041)")
                    if (isCashServiceSeq) {
                        Log.d("HWP_PARSE", "채무현황 순번 파싱: 현금서비스 제외 - $credName ${amountMan}만 - $line")
                    } else if ((loanType.contains("담보") && !loanType.contains("지급보증")) || isDamboBySeq || isInsurancePolicy) {
                        totalParsedDebt += amountMan
                        parsedDamboTotal += amountMan
                        if (credName.length >= 2) {
                            parsedDamboCreditorNames.add(credName)
                            parsedDamboCreditorMap[credName] = (parsedDamboCreditorMap[credName] ?: 0) + amountMan
                        }
                        Log.d("HWP_PARSE", "채무현황 순번 파싱: 담보 제외 - $credName ${amountMan}만 (담보=${ loanType.contains("담보")}, 순번제외=$isDamboBySeq, 약관=$isInsurancePolicy) - $line")
                    } else if (credName.length >= 2 && amountMan > 0) {
                        totalParsedDebt += amountMan
                        parsedCreditorMap[credName] = (parsedCreditorMap[credName] ?: 0) + amountMan
                        // 신청일자 이후 채무 판단 (발생일자 컬럼: "25.03" 등)
                        if (pdfApplicationDate.isNotEmpty() && columns.size >= 4) {
                            val dateCol = columns[3]  // 발생일자 컬럼
                            val seqDateM = Regex("(\\d{2})\\.(\\d{2})").find(dateCol)
                            if (seqDateM != null) {
                                val seqYear = 2000 + seqDateM.groupValues[1].toInt()
                                val seqMonth = seqDateM.groupValues[2].toInt()
                                val appParts = pdfApplicationDate.split(".")
                                val appYear = appParts.getOrNull(0)?.toIntOrNull() ?: 0
                                val appMonth = appParts.getOrNull(1)?.toIntOrNull() ?: 0
                                if (appYear > 0 && (seqYear > appYear || (seqYear == appYear && seqMonth > appMonth))) {
                                    postApplicationDebtMan += amountMan
                                    postApplicationCreditors[credName] = (postApplicationCreditors[credName] ?: 0) + amountMan
                                    Log.d("HWP_PARSE", "채무현황 순번 신청이후: ${seqYear}.${seqMonth} > ${appYear}.${appMonth} → ${amountMan}만 ($credName)")
                                }
                            }
                        }
                        Log.d("HWP_PARSE", "채무현황 순번 파싱: $credName ${amountMan}만 - $line")
                    }
                }
            }

            if (loanYear > 0 && (inSpecialNotesSection || inPropertySection || inRegionField)) {
                if (hasFinancialKeyword) Log.d("HWP_PARSE", "섹션플래그로 채무 스킵: spec=$inSpecialNotesSection prop=$inPropertySection reg=$inRegionField - $line")
            }
            if (loanYear > 0 && inDebtSection && !inSpecialNotesSection && !inPropertySection && !inRegionField) {
                var debtAmount = 0
                val lastToken = line.trim().split(Regex("\\s+")).lastOrNull() ?: ""
                if (Regex("\\d\\s*[만억]").containsMatchIn(lastToken)) {
                    debtAmount = extractAmount(lastToken) * 10
                } else {
                    val commaM = Pattern.compile("([\\d,]+)$").matcher(line.trim())
                    if (commaM.find()) {
                        val numStr = commaM.group(1)!!.replace(",", "")
                        if (numStr.isNotEmpty()) debtAmount = numStr.toInt()
                    }
                }
                if (debtAmount == 0 && hasFinancialKeyword) Log.d("HWP_PARSE", "날짜O 금액X: $line")

                if (debtAmount > 0) {
                    // 채권사명 추출: 금융기관 키워드 포함 토큰 찾기
                    val creditorTokens = line.trim().split(Regex("\\s+"))
                    val creditorFinKws = listOf("은행", "캐피탈", "카드", "금융", "저축", "보증", "공사", "재단", "농협", "신협", "새마을", "생명", "화재", "공단", "대부", "뱅크", "피에프씨", "테크놀로지", "머니무브", "통신", "텔레콤", "모바일", "자산관리", "추심", "기금")
                    // 채권사: 날짜 토큰 바로 앞 위치 (테이블 4번째 열), 없으면 키워드 기반 폴백
                    val dateTokenIdx = creditorTokens.indexOfFirst { it.matches(Regex("\\d{4}\\.\\d{1,2}\\..*")) }
                    val creditorToken = if (dateTokenIdx > 0) creditorTokens[dateTokenIdx - 1]
                        else creditorTokens.firstOrNull { tok -> creditorFinKws.any { tok.contains(it) } && !tok.contains("대출") && !tok.contains("지급보증") && !tok.contains("연대보증") }
                    var rawCreditorName = creditorToken?.replace(Regex("\\([^)]*\\)"), "")?.replace(Regex("[\\s]"), "")?.trim() ?: ""
                    // PDF OCR 오인식 보정
                    if (hasPdfFile) {
                        rawCreditorName = rawCreditorName.replace("웅장", "융창").replace("흥정", "융창")
                    }

                    // 담보대출 처리: 지급보증 제외한 담보대출은 담보로 계산 (대상채무 제외)
                    // 전세대출/전세자금대출(270): 항상 담보 처리
                    val isJeonse270 = lineNoSpace.contains("전세") || lineNoSpace.contains("(270)")
                    // "기타담보대출(290)": 종류명의 "담보"는 무시, 대출과목에서 담보 순번이면 담보 처리
                    val is290 = lineNoSpace.contains("(290)") || lineNoSpace.contains("기타담보대출")
                    val lineFor담보 = if (is290) lineNoSpace.replace("기타담보대출", "").replace("기타담보", "") else lineNoSpace
                    // (290) 순번이 대출과목에서 담보로 확인된 경우
                    val rowSeqM = Pattern.compile("^(\\d{1,2})(?!년)[\\s가-힣]").matcher(line.trimStart())
                    val rowSeqNum = if (rowSeqM.find()) rowSeqM.group(1)!!.toInt() else -1
                    val is290DamboByCategory = is290 && rowSeqNum > 0 && excludedSeqNumbers.contains(rowSeqNum)
                    // 대출과목에서 담보/할부로 확인된 순번 → 담보 처리
                    val isDamboByPreScan = rowSeqNum > 0 && excludedSeqNumbers.contains(rowSeqNum)
                    // 대출과목 표 채권사명 매칭 (순번이 없는 표 형식 대응) - 순번이 있고 사전스캔이 담보로 판정 안 했으면 이름 매칭 무시 (동일 채권사 다른 채권 오분류 방지)
                    val isDamboByLoanCatName = rowSeqNum == 0 && matchesLoanCatDambo(rawCreditorName, loanCatDamboCreditorNames)
                    // 대출과목 표에서 "신용"으로 명시된 채권사명 → 담보 분류 무효화 (강제 신용)
                    val isCreditByLoanCatName = matchesLoanCatDambo(rawCreditorName, loanCatCreditCreditorNames)
                    // PDF 제외 담보 채권사와 매칭 → (290) 담보 판단
                    val is290DamboByPdf = is290 && rawCreditorName.length >= 2 && pdfExcludedDamboCreditors.any { pdfName ->
                        val pdfNorm = pdfName.replace(Regex("\\(주\\)|\\(유\\)|주식회사|유한회사|[\\s\\(\\)]"), "")
                        pdfNorm.contains(rawCreditorName.replace(Regex("\\[.*?\\]"), "")) || rawCreditorName.replace(Regex("\\[.*?\\]"), "").contains(pdfNorm)
                    }
                    if (is290) {
                        Log.d("HWP_PARSE", "기타담보대출(290) 감지: rowSeqNum=$rowSeqNum, excluded=$excludedSeqNumbers, byCategory=$is290DamboByCategory, byPdf=$is290DamboByPdf - $line")
                    }
                    if (isDamboByPreScan && !is290) {
                        Log.d("HWP_PARSE", "대출과목 담보 순번 매칭: rowSeqNum=$rowSeqNum - $line")
                    }
                    val loanTypeToken = creditorTokens.firstOrNull { it.contains("대출") && it.contains(Regex("\\(\\d+\\)")) } ?: ""
                    val isInsurancePolicyLoan = loanTypeToken.contains("보험") || lineNoSpace.contains("(약관)") || lineNoSpace.contains("약관대출") || lineNoSpace.contains("약관") || lineNoSpace.contains("보험담보")
                    val isGuaranteeLoan = lineNoSpace.contains("지급보증") || lineNoSpace.contains("보증담보") || lineNoSpace.contains("보증서담보")
                    val creditorNameForJilgwon = rawCreditorName.replace(Regex("\\[.*"), "")
                    val isJilgwonOCreditor = jilgwonOCreditors.any { creditorNameForJilgwon.contains(it) || it.contains(creditorNameForJilgwon) }
                    val isJilgwonXCreditor = jilgwonXCreditors.any { creditorNameForJilgwon.contains(it) || it.contains(creditorNameForJilgwon) }
                    if (isJilgwonXCreditor && (is290DamboByCategory || is290DamboByPdf || isDamboByPreScan || isJeonse270)) {
                        Log.d("HWP_PARSE", "질권설정x → 담보 해제 (대상채무 포함): $rawCreditorName - $line")
                    }
                    val isJilgwonXOverride = jeonseNoJilgwon && (isJeonse270 || lineNoSpace.contains("보증금대출") || lineNoSpace.contains("보증금담보")) && !isJilgwonOCreditor
                    val isDamboLoan = !isJilgwonXCreditor && !isJilgwonXOverride && !isCreditByLoanCatName && ((lineFor담보.contains("담보") || is290DamboByCategory || is290DamboByPdf || isDamboByPreScan || isDamboByLoanCatName || hasDamboKeyword(lineNoSpace) || (isJeonse270 && (!jeonseNoJilgwon || isJilgwonOCreditor)) || isInsurancePolicyLoan) && !isGuaranteeLoan && !lineNoSpace.contains("(240)") && !lineNoSpace.contains("무담보") && !lineNoSpace.contains("마이너스"))

                    // 지급보증(3021): 대출과목에서 운전자금 바로 다음 연번호인 경우만 대상채무 제외
                    if (lineNoSpace.contains("(3021)")) {
                        val isImmediatelyAfterUnjeon = rowSeqNum > 0 && unjeonSeqs.contains(rowSeqNum - 1)
                        if (isImmediatelyAfterUnjeon) {
                            Log.d("HWP_PARSE", "지급보증(3021) 제외 (운전자금 순번${rowSeqNum - 1} 다음 연번): ${(debtAmount+5)/10}만 - $line")
                            return
                        } else {
                            Log.d("HWP_PARSE", "지급보증(3021) 포함 (운전자금 직후 아님, 순번=$rowSeqNum, 운전자금순번=$unjeonSeqs): ${(debtAmount+5)/10}만 - $line")
                        }
                    }

                    // 운전자금+지급보증 같은 날짜 중복 제거: 둘 중 높은 금액만 계산
                    // 융자담보지보 스킵 (운전자금 동월 → 같은 채무)
                    if (rowSeqNum > 0 && yungjaSkipSeqs.contains(rowSeqNum)) {
                        Log.d("HWP_PARSE", "융자담보지보 스킵 (운전자금 동월, 순번=$rowSeqNum): $line")
                        return
                    }

                    val isJiguBojung = isGuaranteeLoan
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

                        val isCashService = lineNoSpace.contains("현금서비스") || lineNoSpace.contains("(0041)")
                        if (isCashService) {
                            Log.d("HWP_PARSE", "현금서비스 제외: ${(debtAmount + 5) / 10}만 - $line")
                        } else if (prevGuarantee != null && (isGuaranteeDebt || prevGuarantee)) {
                            Log.d("HWP_PARSE", "보증채무 중복 제외: $dateAmountKey - $line")
                        } else {
                            debtDateAmountSeen[dateAmountKey] = isGuaranteeDebt
                            totalParsedDebt += (debtAmount + 5) / 10
                            if (loanYear in 2020..2025 && !(loanYear == 2020 && loanMonth < 4) && !(loanYear == 2025 && loanMonth > 6) && businessStartYear > 0 && loanYear >= businessStartYear && (businessEndYear == 0 || loanYear <= businessEndYear)) hasDebtDuringBusiness = true

                            // 기업은행(근로복지공단 보증) → 새출발 제외
                            if (rawCreditorName.contains("기업은행") && lineNoSpace.contains("근로복지공단")) {
                                saeExcludedDebtMan += (debtAmount + 5) / 10
                                Log.d("HWP_PARSE", "새출발 제외 (기업은행 근로복지공단 보증): ${(debtAmount + 5) / 10}만 - $line")
                            }

                            // 6개월 이내 채무 수집 (담보 포함 모든 채무)
                            val loanCal = Calendar.getInstance().apply {
                                set(loanYear, loanMonth - 1, if (loanDay > 0) loanDay else 15)
                            }
                            val sixMonthsAgo = Calendar.getInstance().apply { add(Calendar.MONTH, -6) }
                            if (loanCal.after(sixMonthsAgo)) {
                                recentDebtEntries.add(Pair(loanCal, debtAmount))
                                val isCarLoan = isCarLoanKeyword(lineNoSpace)
                                if (isCarLoan) recentCarLoanMan += (debtAmount + 5) / 10
                                if (rawCreditorName.length >= 2) recentCreditorNames.add(rawCreditorName)
                                Log.d("HWP_PARSE", "6개월 수집: ${loanYear}.${loanMonth}.${loanDay} ${debtAmount}천원 (${(debtAmount+5)/10}만)${if (isCarLoan) " [차량대출]" else ""} - $line")
                            } else if (rawCreditorName.length >= 2) {
                                olderCreditorNames.add(rawCreditorName)
                            }

                            // 신청일자 이후 추가채무 체크 (담보대출 제외)
                            if (pdfApplicationDate.isNotEmpty() && !isDamboLoan) {
                                val parts = pdfApplicationDate.split(".")
                                if (parts.size == 3) {
                                    val appCal = Calendar.getInstance().apply {
                                        set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                                    }
                                    if (loanCal.after(appCal)) {
                                        val postAmt = (debtAmount + 5) / 10
                                        postApplicationDebtMan += postAmt
                                        postApplicationCreditors[rawCreditorName] = (postApplicationCreditors[rawCreditorName] ?: 0) + postAmt
                                        Log.d("HWP_PARSE", "신청일자 이후 채무: ${loanYear}.${loanMonth}.${loanDay} ${postAmt}만 - $line")
                                    }
                                }
                            }

                            if (isDamboLoan) {
                                parsedDamboTotal += (debtAmount + 5) / 10
                                val isCarDambo = isCarLoanKeyword(lineNoSpace)
                                if (isCarDambo) parsedCarDamboTotal += (debtAmount + 5) / 10
                                if (rawCreditorName.length >= 2) {
                                    parsedDamboCreditorNames.add(rawCreditorName)
                                    parsedDamboCreditorMap[rawCreditorName] = (parsedDamboCreditorMap[rawCreditorName] ?: 0) + (debtAmount + 5) / 10
                                }
                                // 담보 제외여도 대부 채권사명 카운트 (대부 2건 이상 판단용)
                                if (rawCreditorName.contains("대부")) daebuCreditorNames.add(rawCreditorName)
                                if (isInsurancePolicyLoan) hasInsurancePolicyLoan = true
                                Log.d("HWP_PARSE", "담보대출 (대상채무 제외)${if (isCarDambo) " [차량]" else ""}: $line")
                            }
                            // parsedCreditorMap에 채권사 추가 (비담보, 비현금서비스만 - PDF 비교 및 과반 계산용)
                            val isCashServiceLine = lineNoSpace.contains("현금서비스") || lineNoSpace.contains("(0041)")
                            if (rawCreditorName.length >= 2 && !isDamboLoan && !isCashServiceLine) {
                                val amountMan = (debtAmount + 5) / 10
                                parsedCreditorMap[rawCreditorName] = (parsedCreditorMap[rawCreditorName] ?: 0) + amountMan
                                // 변제율 판단용 채무 유형별 집계
                                val unjeonYearMonth = if (loanYear > 0 && loanMonth > 0) "${loanYear}.${loanMonth.toString().padStart(2, '0')}" else ""
                                if (lineNoSpace.contains("운전자금")) {
                                    guaranteeDebtMan += amountMan
                                    guaranteeCreditorMap[rawCreditorName] = (guaranteeCreditorMap[rawCreditorName] ?: 0) + amountMan
                                }
                                if (lineNoSpace.contains("지급보증")) {
                                    jigubojungDebtMan += amountMan
                                    jigubojungCreditorMap[rawCreditorName] = (jigubojungCreditorMap[rawCreditorName] ?: 0) + amountMan
                                }
                                if (rawCreditorName.contains("대부")) {
                                    daebuDebtMan += amountMan
                                    daebuCreditorNames.add(rawCreditorName)
                                    daebuCreditorAmountMap[rawCreditorName] = (daebuCreditorAmountMap[rawCreditorName] ?: 0) + amountMan
                                }
                                if (rawCreditorName.contains("카드") || rawCreditorName.contains("캐피탈")) {
                                    cardCapitalDebtMan += amountMan
                                    cardCapitalCreditorMap[rawCreditorName] = (cardCapitalCreditorMap[rawCreditorName] ?: 0) + amountMan
                                }
                            }

                            if (!isDamboLoan && hasFinancialKeyword) {
                                // 학자금 합산: 취업후상환→제외, 일반상환→포함, 구별불가→제외+특이
                                if (lineNoSpace.contains("학자금") || lineNoSpace.contains("(150)") || lineNoSpace.contains("장학재단")) {
                                    val isAfterEmployment = (rowSeqNum > 0 && studentLoanExcludedSeqs.contains(rowSeqNum)) || lineNoSpace.contains("취업")
                                    val isGeneral = (rowSeqNum > 0 && studentLoanGeneralSeqs.contains(rowSeqNum)) || lineNoSpace.contains("일반")
                                    if (isAfterEmployment) {
                                        studentLoanTotal += debtAmount
                                        Log.d("HWP_PARSE", "학자금 취업후상환 (제외대상): ${(debtAmount+5)/10}만 순번=$rowSeqNum - $line")
                                    } else if (!isGeneral && lineNoSpace.contains("장학재단")) {
                                        // 한국장학재단인데 일반/취업후 구별 불가 → 제외
                                        studentLoanTotal += debtAmount
                                        studentLoanUnknown = true
                                        Log.d("HWP_PARSE", "학자금 구별불가 (제외+특이): ${(debtAmount+5)/10}만 순번=$rowSeqNum - $line")
                                    } else {
                                        Log.d("HWP_PARSE", "학자금 일반상환 (대상채무 포함): ${(debtAmount+5)/10}만 순번=$rowSeqNum - $line")
                                    }
                                }

                                // 표 전체 합산 (학자금 비율 계산용)
                                tableDebtTotal += debtAmount

                                // 사업자대출 감지
                                if (lineNoSpace.contains("개인사업자대출") || lineNoSpace.contains("운전자금") ||
                                    lineNoSpace.contains("사업자대출") || lineNoSpace.contains("(1051)")) {
                                    hasBusinessLoan = true
                                }
                                // 사업자대출만으로는 사업자이력 판단하지 않음 (AI에서 판단)
                            }
                        }
                    }
                }
            }

            // 대출과목 섹션 내 파싱 (섹션 감지는 루프 상단에서 처리)
            if (inLoanCategorySection) {
                // 대출과목에서 신복/신복위 금액 파싱 (PDF 없을 때만)
                if (!hasPdfFile && (lineNoSpace.contains("신복") || lineNoSpace.contains("신용회복")) && textShinbokDebt == 0) {
                    val amountMatcher = Pattern.compile("(\\d[\\d,]*)만").matcher(lineNoSpace)
                    if (amountMatcher.find()) {
                        textShinbokDebt = amountMatcher.group(1)!!.replace(",", "").toInt()
                        Log.d("HWP_PARSE", "대출과목 신복위 채무 감지: ${textShinbokDebt}만 - $line")
                    }
                }
                // 대출과목에서 담보 순번 수집 (현황순번 + 담보 키워드)
                val loanCatSeqM = Pattern.compile("^(\\d+)(?!년)").matcher(lineNoSpace)
                // "제외N" 형식 감지 (협약 외 채무)
                val excludeSeqM = Pattern.compile("^제외(\\d+)").matcher(lineNoSpace)
                if (loanCatSeqM.find() || excludeSeqM.find()) {
                    val isExcludeEntry = excludeSeqM.find(0)
                    val seqStr = if (isExcludeEntry) "제외${excludeSeqM.group(1)}" else loanCatSeqM.group(1)!!
                    val isDambo = lineNoSpace.contains("담보") || lineNoSpace.contains("할부") || lineNoSpace.contains("리스") || lineNoSpace.contains("약관") || lineNoSpace.contains("채무아님")
                    if (!isExcludeEntry) {
                        val seqNum = loanCatSeqM.group(1)!!.toInt()
                        if (isDambo) {
                            excludedSeqNumbers.add(seqNum)
                            Log.d("HWP_PARSE", "대출과목 담보 순번 감지: 순번$seqNum - $line")
                        }
                    }
                    // 대출과목 행 수집 (표시용)
                    val afterSeq = lineNoSpace.substring(seqStr.length).trim()
                    if (afterSeq.isNotEmpty()) {
                        val dkM = Pattern.compile("((?:차량|자동차|주택|보증서|집)?담보(?:대출)?|(?:차량)?할부|(?:차량)?리스|약관|신용|카드론|현금서비스|채무아님)").matcher(afterSeq)
                        if (dkM.find()) {
                            val creditor = afterSeq.substring(0, dkM.start()).trim()
                            val damboKeyword = dkM.group(1)!!
                            loanCatRows.add(LoanCatRow(seqStr, creditor, damboKeyword))
                            if (isExcludeEntry) Log.d("HWP_PARSE", "대출과목 협약외 감지: $seqStr $creditor ($damboKeyword) - $line")
                        } else {
                            loanCatRows.add(LoanCatRow(seqStr, afterSeq, ""))
                            if (isExcludeEntry) Log.d("HWP_PARSE", "대출과목 협약외 감지: $seqStr $afterSeq - $line")
                        }
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

            // 카드이용금액 테이블 섹션 감지 (개별 카드사를 채권사로 추가)
            if (lineNoSpace.contains("카드사") && lineNoSpace.contains("이용금액")) {
                inCardUsageTableSection = true
            } else if (inCardUsageTableSection) {
                // 빈 라인이나 다른 섹션 시작시 종료
                if (lineNoSpace.contains("대출과목") || lineNoSpace.contains("기타채무") || lineNoSpace.contains("요약") ||
                    lineNoSpace.contains("특이") || lineNoSpace.contains("채무조정")) {
                    inCardUsageTableSection = false
                } else {
                    // 카드명 뒤에 x/X/- 가 이용금액 자리에 있으면 0만 처리 (뒤 금액은 이용한도이므로 무시)
                    val hasXBeforeAmount = Pattern.compile("^[^\\d]*[xX\\-]").matcher(lineNoSpace).find()
                    // "N억M만" / "N억" 우선 매칭, 없으면 "M만"으로 폴백 (예: "국민1억2000만" → 12000만)
                    val cardEokM = Pattern.compile("(\\d+)억(\\d+)?만?").matcher(lineNoSpace)
                    val cardAmountMatcher = Pattern.compile("(\\d[\\d,]*)만").matcher(lineNoSpace)
                    var cardAmount = 0
                    var amountStart = -1
                    if (!hasXBeforeAmount) {
                        if (cardEokM.find()) {
                            val eok = cardEokM.group(1)?.toIntOrNull() ?: 0
                            val man = cardEokM.group(2)?.toIntOrNull() ?: 0
                            cardAmount = eok * 10000 + man
                            amountStart = cardEokM.start()
                        } else if (cardAmountMatcher.find()) {
                            cardAmount = cardAmountMatcher.group(1)!!.replace(",", "").toIntOrNull() ?: 0
                            amountStart = cardAmountMatcher.start()
                        }
                    }
                    if (!hasXBeforeAmount && amountStart >= 0) {
                        // 첫 번째 금액 앞까지만 카드명 (병합 테이블의 뒤쪽 데이터 제외)
                        // "사업자" 접미어 제거 (예: "비씨사업자" → "비씨")
                        val cardName = lineNoSpace.substring(0, amountStart).trim()
                            .replace("사업자", "").trim()
                        if (cardName.isNotEmpty() && cardAmount > 0) {
                            val fullCardName = cardName + "카드"
                            cardUsageCreditors.add(fullCardName)
                            cardUsageAmountMap[fullCardName] = (cardUsageAmountMap[fullCardName] ?: 0) + cardAmount
                            Log.d("HWP_PARSE", "카드이용금액 테이블: $fullCardName ${cardAmount}만 - $line")
                            if (!parsedCreditorMap.keys.any { it.contains(fullCardName) || (it.contains(cardName) && it.contains("카드")) }) {
                                parsedCreditorMap[fullCardName] = 0  // 채권사 수 카운트용 (금액은 parsedCardUsageTotal에서 합산)
                                Log.d("HWP_PARSE", "카드이용금액 테이블 채권사 추가: $fullCardName - $line")
                            }
                        }
                    } else if (hasXBeforeAmount || lineNoSpace.contains("x") || lineNoSpace.contains("X")) {
                        // 이용금액이 x/- 인 경우 → 0만 처리, 카드사만 등록 (뒤 이용한도 금액도 제거)
                        // "사업자" 접미어 제거 (예: "비씨사업자" → "비씨")
                        val cardName = lineNoSpace.replace(Regex("[xX\\-].*"), "").trim()
                            .replace("사업자", "").trim()
                        if (cardName.isNotEmpty()) {
                            val fullCardName = cardName + "카드"
                            cardUsageCreditors.add(fullCardName)
                            cardUsageAmountMap[fullCardName] = (cardUsageAmountMap[fullCardName] ?: 0)
                            Log.d("HWP_PARSE", "카드이용금액 테이블: $fullCardName x→0만 - $line")
                            if (!parsedCreditorMap.keys.any { it.contains(fullCardName) || (it.contains(cardName) && it.contains("카드")) }) {
                                parsedCreditorMap[fullCardName] = 0
                                Log.d("HWP_PARSE", "카드이용금액 테이블 채권사 추가: $fullCardName - $line")
                            }
                        }
                    }
                }
            }

            // 기타채무 섹션 감지
            if (lineNoSpace.contains("기타채무") || lineNoSpace.replace("\\s".toRegex(), "").let { it.contains("기타채무") }) {
                inOtherDebtSection = true
            }
            if (inOtherDebtSection && (lineNoSpace.contains("요약사항") || lineNoSpace.contains("최저납부") ||
                        lineNoSpace.contains("요약]") || lineNoSpace.contains("특이사항") || lineNoSpace.contains("채무조정"))) {
                inOtherDebtSection = false
            }
            // 기타채무 섹션 내 채권사명 수집 (미협약 판단 제외용)
            if (inOtherDebtSection && !lineNoSpace.contains("기타채무") && !lineNoSpace.contains("채권") && !lineNoSpace.contains("내용")) {
                val amountMatcher = Pattern.compile("(\\d[\\d,]*)만").matcher(lineNoSpace)
                if (amountMatcher.find()) {
                    val amountStr = amountMatcher.group(1)!!.replace(",", "")
                    val amountMan = amountStr.toIntOrNull() ?: 0
                    val creditorName = lineNoSpace.replace(Regex("\\d[\\d,]*만"), "").replace(Regex("[=＝]"), "").trim()
                    if (creditorName.length >= 2) {
                        otherDebtCreditorNames.add(creditorName)
                        // 요약 라인 제외: "1. 신용 0만", "총액 = 1억" 등
                        val cleanName = creditorName.replace(Regex("[0-9.\\s]"), "")
                        val summaryKeywords = listOf("신용", "카드", "담보", "총액", "기타", "합계", "신복위", "신복")
                        val isSummary = summaryKeywords.any { cleanName.startsWith(it) }
                        if (!isSummary && amountMan > 0) {
                            otherDebtEntries.add(Pair(creditorName, amountMan))
                            parsedCreditorMap[creditorName] = (parsedCreditorMap[creditorName] ?: 0) + amountMan
                            Log.d("HWP_PARSE", "기타채무 실제 채권사: $creditorName ${amountMan}만 - $line")
                        } else {
                            val cleanName = creditorName.replace(Regex("[0-9.\\s]"), "")
                            // 카드이용금액 합계 파싱
                            if (cleanName.startsWith("카드") && amountMan > 0) {
                                parsedCardUsageTotal = amountMan
                                Log.d("HWP_PARSE", "기타채무 카드이용금액 파싱: ${amountMan}만 - $line")
                            }
                            Log.d("HWP_PARSE", "기타채무 요약 라인: $creditorName - $line")
                        }
                    }
                }
            }

            // 기타채무 세금 감지
            if (inOtherDebtSection && (lineNoSpace.contains("세금") || lineNoSpace.contains("국세") || lineNoSpace.contains("지방세") || lineNoSpace.contains("체납세"))) {
                val taxM = Pattern.compile("(\\d[\\d,]*)만").matcher(lineNoSpace)
                if (taxM.find()) {
                    val amt = taxM.group(1)!!.replace(",", "").toInt()
                    if (amt > 0 && textTaxDebt == 0) { textTaxDebt = amt; Log.d("HWP_PARSE", "기타채무 세금 감지: ${amt}만 - $line") }
                }
            }
            // 기타채무 통신사 감지 → 대상채무에 포함
            if (lineNoSpace.contains("통신") || lineNoSpace.contains("텔레콤") || lineNoSpace.contains("모바일")) {
                val amountMatcher = Pattern.compile("(\\d[\\d,]*)만").matcher(lineNoSpace)
                if (amountMatcher.find()) {
                    val amount = amountMatcher.group(1)!!.replace(",", "").toInt()
                    if (amount > 0) {
                        telecomDebt += amount
                        Log.d("HWP_PARSE", "기타채무 통신사 감지: ${amount}만 - $line")
                    }
                }
            }
        }
        for (rawLine in lines) {
            if (shouldStopParsing) break
            processHwpLine(rawLine)
        }

        // 지급보증 중복분 대상채무에서 차감 (텍스트 파싱이 포함시킨 경우)
        if (jiguBojungExcludedMan > 0) {
            val parsedIncludedBojung = parsedCreditorMap.keys.any { it.contains("보증재단") || it.contains("신용보증") }
            if (parsedIncludedBojung) {
                targetDebt -= jiguBojungExcludedMan
                if (targetDebt < 0) targetDebt = 0
                Log.d("HWP_CALC", "지급보증 중복 차감: ${jiguBojungExcludedMan}만 → 대상채무=${targetDebt}만")
            } else {
                Log.d("HWP_CALC", "지급보증 중복 차감 생략 (파싱이 이미 제외): ${jiguBojungExcludedMan}만")
            }
        }

        // 학자금 계산 (단기/장기 분리용)
        val studentLoanMan = (studentLoanTotal + 5) / 10
        val tableDebtMan = (tableDebtTotal + 5) / 10
        val studentLoanRatio = if (tableDebtMan > 0) studentLoanMan.toDouble() / tableDebtMan * 100 else 0.0
        if (studentLoanMan > 0) {
            if (studentLoanRatio >= 50) specialNotesList.add("학자금 많음")
            if (studentLoanUnknown) specialNotesList.add("한국장학재단 채무제외")
            Log.d("HWP_CALC", "학자금: ${studentLoanMan}만 / 표전체${tableDebtMan}만 = ${String.format("%.1f", studentLoanRatio)}%")
        }

        // 합의서 PDF 진행중 제도 감지 (실효면 진행중 아님, 폐지는 회생폐지+신복위진행 가능하므로 합의서 우선)
        if (pdfAgreementProcess.isNotEmpty() && !hasWorkoutExpired) {
            hasOngoingProcess = true
            // AI 응답 정규화: "신속채무조정" 등 전체 이름 → "신"/"프"/"워"
            ongoingProcessName = when {
                pdfAgreementProcess.contains("신속") -> "신"
                pdfAgreementProcess.contains("사전") -> "프"
                pdfAgreementProcess.contains("개인") || pdfAgreementProcess.contains("워크") || pdfAgreementProcess == "워" -> "워"
                pdfAgreementProcess == "신" || pdfAgreementProcess == "프" -> pdfAgreementProcess
                else -> pdfAgreementProcess
            }
            hasShinbokwiHistory = true
            delinquentDays = maxOf(delinquentDays, 1095)
            Log.d("HWP_CALC", "합의서 PDF 제도 적용: $pdfAgreementProcess → $ongoingProcessName 진행중")
        }

        // ★ 대상채무: 텍스트 파싱 기반 (AI 제거)
        // 기타채무 요약에 카드이용금액이 없으면 카드이용금액 테이블 합계 사용
        if (parsedCardUsageTotal == 0 && cardUsageAmountMap.isNotEmpty()) {
            parsedCardUsageTotal = cardUsageAmountMap.values.sum()
            Log.d("HWP_CALC", "카드이용금액 테이블에서 합산: ${cardUsageAmountMap.entries.joinToString { "${it.key}=${it.value}만" }} → ${parsedCardUsageTotal}만")
        }
        // 타인명의 담보 reclassify: parsedCreditorMap의 매칭 채권사를 담보로 이동 (대상채무에서 제외)
        for ((tpCreditor, tpAmount) in thirdPartyDamboMap) {
            val matchKey = parsedCreditorMap.entries.firstOrNull { (k, v) ->
                val baseName = k.replace(Regex("\\[.*?\\]"), "")
                (baseName.contains(tpCreditor) || tpCreditor.contains(baseName)) &&
                kotlin.math.abs(v - tpAmount) <= maxOf(100, tpAmount / 50)  // 허용오차 100만 또는 2%
            }?.key
            if (matchKey != null) {
                val matched = parsedCreditorMap[matchKey] ?: 0
                parsedDamboTotal += matched
                parsedDamboCreditorMap[matchKey] = (parsedDamboCreditorMap[matchKey] ?: 0) + matched
                parsedDamboCreditorNames.add(matchKey)
                parsedCreditorMap.remove(matchKey)
                guaranteeDebtMan -= guaranteeCreditorMap.remove(matchKey) ?: 0
                jigubojungDebtMan -= jigubojungCreditorMap.remove(matchKey) ?: 0
                daebuDebtMan -= daebuCreditorAmountMap.remove(matchKey) ?: 0
                cardCapitalDebtMan -= cardCapitalCreditorMap.remove(matchKey) ?: 0
                Log.d("HWP_CALC", "타인명의 담보 reclassify: $matchKey ${matched}만 → 담보 (감지=$tpCreditor ${tpAmount}만)")
            } else {
                Log.d("HWP_CALC", "타인명의 담보 매칭 실패: $tpCreditor ${tpAmount}만 - parsedCreditorMap=${parsedCreditorMap.keys}")
            }
        }
        // 차량담보 reclassify: 차량 파싱에서 검출된 담보 금액 + 사이드바 담보 채권사명 매칭 → 담보로 이동
        // 금액 매칭으로 동일 채권사의 다른 채무(신용 등) 오분류 방지
        for (carDambo in carDamboAmountList) {
            if (carDambo <= 0) continue
            val matchKey = parsedCreditorMap.entries.firstOrNull { (k, v) ->
                kotlin.math.abs(v - carDambo) <= maxOf(50, carDambo / 100) &&
                matchesLoanCatDambo(k, loanCatDamboCreditorNames)
            }?.key
            if (matchKey != null) {
                val matched = parsedCreditorMap[matchKey] ?: 0
                parsedDamboTotal += matched
                parsedDamboCreditorMap[matchKey] = (parsedDamboCreditorMap[matchKey] ?: 0) + matched
                parsedDamboCreditorNames.add(matchKey)
                parsedCreditorMap.remove(matchKey)
                guaranteeDebtMan -= guaranteeCreditorMap.remove(matchKey) ?: 0
                jigubojungDebtMan -= jigubojungCreditorMap.remove(matchKey) ?: 0
                daebuDebtMan -= daebuCreditorAmountMap.remove(matchKey) ?: 0
                cardCapitalDebtMan -= cardCapitalCreditorMap.remove(matchKey) ?: 0
                Log.d("HWP_CALC", "차량담보 reclassify: $matchKey ${matched}만 → 담보 (차량담보=${carDambo}만)")
            }
        }
        val parsedTargetDebt = totalParsedDebt - parsedDamboTotal
        targetDebt = parsedTargetDebt + parsedCardUsageTotal
        Log.d("HWP_CALC", "대상채무 (텍스트파싱): 총${totalParsedDebt}-담보${parsedDamboTotal}+카드${parsedCardUsageTotal} = ${targetDebt}만 | 카드맵=$cardUsageAmountMap")

        // 카드이용금액을 채권사맵에 합산 (과반 판단용: 채무현황표 "롯데카드[서울]" + 카드이용금액 "롯데카드" 매칭)
        for ((cardName, cardAmt) in cardUsageAmountMap) {
            if (cardAmt <= 0) continue
            val cardBase = cardName.replace("카드", "")
            val matchKey = parsedCreditorMap.keys.firstOrNull { key ->
                val keyBase = key.replace(Regex("\\[.*"), "").replace("카드", "")
                keyBase == cardBase || key.contains(cardName) || cardName.contains(keyBase + "카드")
            }
            if (matchKey != null) {
                parsedCreditorMap[matchKey] = (parsedCreditorMap[matchKey] ?: 0) + cardAmt
            } else {
                parsedCreditorMap[cardName] = (parsedCreditorMap[cardName] ?: 0) + cardAmt
            }
            Log.d("HWP_CALC", "카드이용금액→채권사 합산: $cardName ${cardAmt}만 → ${matchKey ?: cardName} (합계=${parsedCreditorMap[matchKey ?: cardName]}만)")
        }

        // 완납 채권사 제외 (특이사항에서 감지) - 채무현황 금액만 제외, 카드이용금액은 유지
        if (paidOffCreditorKeywords.isNotEmpty()) {
            var paidOffDeduct = 0
            val removedCreditors = mutableListOf<String>()
            val iterator = parsedCreditorMap.entries.iterator()
            while (iterator.hasNext()) {
                val (credName, credAmount) = iterator.next()
                val matched = paidOffCreditorKeywords.any { kw -> credName.contains(kw) }
                if (matched && credAmount > 0) {
                    paidOffDeduct += credAmount
                    removedCreditors.add("$credName(${credAmount}만)")
                    iterator.remove()
                }
            }
            if (paidOffDeduct > 0) {
                targetDebt -= paidOffDeduct
                if (targetDebt < 0) targetDebt = 0
                Log.d("HWP_CALC", "완납 채권사 제외: ${removedCreditors.joinToString()} → -${paidOffDeduct}만 → 대상채무=${targetDebt}만")
            }
        }

        // 기타채무 통신사 → 대상채무에 합산
        if (telecomDebt > 0) {
            targetDebt += telecomDebt
            Log.d("HWP_CALC", "기타채무 통신사: +${telecomDebt}만 → 대상채무 ${targetDebt}만")
        }

        // 신청일자 이후 추가채무 (특이사항 표시용, 채무현황 테이블에 이미 포함되어 있으므로 대상채무에 별도 합산 안 함)
        if (postApplicationDebtMan > 0) {
            Log.d("HWP_PARSE", "신청일자 이후 추가채무: ${postApplicationDebtMan}만 (테이블에 이미 포함)")
        }

        // 합의서 제외 채무 참고 로그
        if (pdfExcludedGuaranteeDebt > 0 || pdfExcludedOtherDebt > 0) {
            Log.d("HWP_PARSE", "합의서 제외 채무: 보증서=${pdfExcludedGuaranteeDebt}만, 담보=${pdfExcludedOtherDebt}만")
        }

        // 합의서 PDF 채권사 + 한글파일 채권사 병합 (PDF 우선, 이름+금액 비교)
        if (pdfAgreementCreditors.isNotEmpty()) {
            // 한글파일 채권사명을 한글 변환 (ＮＨ농협→엔에이치농협 등)
            fun normalizeCreditorName(name: String): String {
                val cleaned = name.replace(Regex("\\[.*?\\]"), "").replace(Regex("[\\s\\(\\)]"), "")
                    .map { ch -> when (ch) {
                        in '\uFF10'..'\uFF19' -> (ch - 0xFEE0).toChar()
                        else -> ch
                    }}.joinToString("")
                return AffiliateList.convertEnglishToKorean(cleaned)
            }

            val mergedMap = mutableMapOf<String, Int>()
            // PDF 조정 채권사 추가
            for ((name, amount) in pdfAgreementCreditors) {
                mergedMap[name] = amount
            }
            // PDF 채권사 한글명 캐시
            val pdfNormalizedNames = mergedMap.keys.associate { it to normalizeCreditorName(it) }

            // 한글파일 채권사 중 신청일자 이후 채무만 추가 (PDF가 있으면 한글은 추가채무만)
            for ((name, amount) in postApplicationCreditors) {
                val hwpNorm = normalizeCreditorName(name)
                val matchedPdf = mergedMap.entries.firstOrNull { (pdfName, _) ->
                    val pdfNorm = pdfNormalizedNames[pdfName] ?: normalizeCreditorName(pdfName)
                    (pdfNorm.contains(hwpNorm.take(4)) || hwpNorm.contains(pdfNorm.take(4)))
                    || (hwpNorm.length >= 3 && pdfNorm.length >= 3
                        && (pdfNorm.contains(hwpNorm.take(3)) || hwpNorm.contains(pdfNorm.take(3))))
                }
                if (matchedPdf != null) {
                    mergedMap[matchedPdf.key] = matchedPdf.value + amount
                    Log.d("HWP_CALC", "한글 추가채무 (PDF 채권사에 합산): $name ${amount}만 → ${matchedPdf.key} ${matchedPdf.value + amount}만")
                } else {
                    mergedMap[name] = amount
                    Log.d("HWP_CALC", "한글 추가채무 (신규 채권사): $name ${amount}만")
                }
            }
            parsedCreditorMap.clear()
            parsedCreditorMap.putAll(mergedMap)
            Log.d("HWP_CALC", "채권사 병합: PDF조정=${pdfAgreementCreditors.size}건 + 신청이후=${postApplicationCreditors.size}건 → 총 ${parsedCreditorMap.size}건")

            // 합의서 대상채무 + 제외 보증서 + 신청일자 이후 추가채무 + 카드이용금액
            if (pdfAgreementDebt > 0) {
                targetDebt = pdfAgreementDebt + pdfExcludedGuaranteeDebt + postApplicationDebtMan + parsedCardUsageTotal + telecomDebt
                Log.d("HWP_PARSE", "합의서+한글 대상채무 합산: 합의서=${pdfAgreementDebt}만 + 제외보증서=${pdfExcludedGuaranteeDebt}만 + 신청이후=${postApplicationDebtMan}만 + 카드=${parsedCardUsageTotal}만 + 통신=${telecomDebt}만 = ${targetDebt}만")
            }
        } else if (pdfAgreementDebt > 0) {
            // PDF 채권사 목록 없이 대상채무만 있는 경우
            val hwpDebt = targetDebt
            targetDebt = pdfAgreementDebt + hwpDebt
            Log.d("HWP_PARSE", "합의서+한글 대상채무 합산: 합의서=${pdfAgreementDebt}만 + 한글=${hwpDebt}만 = ${targetDebt}만")
        }

        // 변제계획안 대상채무 + 한글 채무 합산 (신청 이후 발생한 채무 포함)
        if (pdfRecoveryDebt > 0) {
            val hwpDebt = targetDebt
            targetDebt = pdfRecoveryDebt + hwpDebt
            Log.d("HWP_CALC", "변제계획안+한글 대상채무 합산: 변제계획안=${pdfRecoveryDebt}만 + 한글=${hwpDebt}만 = ${targetDebt}만")
        }
        // 변제계획안 채권자 목록 → parsedCreditorMap/daebuCreditorNames 병합 (한글 채무현황과 동일 방식)
        if (pdfRecoveryCreditors.isNotEmpty()) {
            parsedCreditorMap.clear()  // 변제계획안이 우선 (한글 채무현황 대체)
            for ((name, amt) in pdfRecoveryCreditors) {
                parsedCreditorMap[name] = amt
                if (name.contains("대부")) {
                    daebuCreditorNames.add(name)
                    daebuDebtMan += amt
                }
            }
            Log.d("HWP_CALC", "변제계획안 채권자 병합: ${pdfRecoveryCreditors.size}건, 대부=${daebuCreditorNames.size}건")
        }
        // 변제계획안 월변제금은 단기 결과에서 직접 적용 (소득이 아님)

        // ★ 협약 외 채무: 한글파일 대출과목 "제외N" 행으로 담보/신용 판단 (PDF 순서 = HWP 제외N 순서)
        if (pdfOutsideEntries.isNotEmpty()) {
            for ((idx, entry) in pdfOutsideEntries.withIndex()) {
                val excludeKey = "제외${idx + 1}"
                val matchedRow = loanCatRows.firstOrNull { it.seq == excludeKey }
                val isDambo = matchedRow?.let {
                    it.damboNote.contains("담보") || it.damboNote.contains("할부") || it.damboNote.contains("리스")
                } ?: false
                // 채권사명: HWP 매칭 행이 있고 PDF 이름이 부정확("기타협약외"/"기티협약외" 등 prefix 잔여)이면 HWP 이름 사용
                val pdfNameLooksBad = entry.name.contains("협약외") || entry.name.contains("기타") || entry.name.length < 3
                val finalName = if (matchedRow != null && matchedRow.creditor.length >= 2 && pdfNameLooksBad) {
                    matchedRow.creditor
                } else entry.name
                if (isDambo) {
                    parsedDamboTotal += entry.principal
                    parsedDamboCreditorNames.add(finalName)
                    parsedDamboCreditorMap[finalName] = (parsedDamboCreditorMap[finalName] ?: 0) + entry.principal
                    Log.d("HWP_CALC", "협약 외 채무 → 담보: $excludeKey=${matchedRow?.creditor}(${matchedRow?.damboNote}) → $finalName ${entry.principal}만 (PDF원명='${entry.name}')")
                } else {
                    targetDebt += entry.principal
                    parsedCreditorMap[finalName] = (parsedCreditorMap[finalName] ?: 0) + entry.principal
                    Log.d("HWP_CALC", "협약 외 채무 → 신용(대상채무): $excludeKey=${matchedRow?.creditor ?: "매칭실패"} → $finalName ${entry.principal}만 (PDF원명='${entry.name}')")
                }
            }
        }

        // ★ 미협약 판단 (AffiliateList 대조) - 텍스트 파싱 기반
        val loanTypeNames = setOf("신용대출", "신용", "카드", "카드대출", "담보", "담보대출", "대출", "기타", "기타대출", "총액", "국세", "지방세", "세금", "지급보증", "연대보증", "보증", "고소", "소송", "벌금", "물품대금")
        val nonFinancialKeywords = listOf("물품대금", "세금", "국세", "지방세", "공사대금", "대금", "급여", "임금", "월세", "보증금")
        val financialKeywords = listOf("은행", "카드", "캐피탈", "저축", "대부", "보험", "증권", "금융", "신협", "농협", "수협", "새마을", "조합", "재단", "보증", "자산관리", "추심", "생명", "화재", "손해", "상사", "할부", "여신", "리스", "펀드", "투자", "파이낸스", "캐시", "론", "크레디트", "신용정보", "상호", "공사", "에스비아이", "웰컴", "오케이", "페퍼", "제이티", "모아", "예스", "에이앤피")
        var nonAffiliatedDebt = 0
        val nonAffNames = mutableListOf<String>()
        val nonAffEntries = mutableListOf<Pair<String, Int>>()
        val otherDebtNameSet = otherDebtEntries.map { it.first }.toSet()
        val loanCatNameSet = loanCatRows.map { it.creditor }.filter { it.isNotBlank() }.toSet()
        for ((name, amount) in parsedCreditorMap) {
            if (loanTypeNames.contains(name)) {
                Log.d("HWP_CALC", "대출유형명 (미협약 판단 제외): $name (${amount}만)")
                continue
            }
            if (name in otherDebtNameSet) {
                Log.d("HWP_CALC", "기타채무 (미협약 판단 제외): $name (${amount}만)")
                continue
            }
            if (pdfAgreementCreditors.containsKey(name)) {
                Log.d("HWP_CALC", "PDF 합의서 채권사 (미협약 판단 제외): $name (${amount}만)")
                continue
            }
            if (nonFinancialKeywords.any { name.contains(it) }) {
                Log.d("HWP_CALC", "비금융채무 (미협약 판단 제외): $name (${amount}만)")
                continue
            }
            if (name in cardUsageCreditors) {
                Log.d("HWP_CALC", "카드이용금액 테이블 (미협약 판단 제외): $name (${amount}만)")
                continue
            }
            if (name in loanCatNameSet) {
                Log.d("HWP_CALC", "대출과목 테이블 (미협약 판단 제외): $name (${amount}만)")
                continue
            }
            val nameForCheck = if (name.contains("대부")) name.replace("대부", "") else name
            if (!AffiliateList.isAffiliated(name) && !AffiliateList.isAffiliated(nameForCheck)) {
                val isFinancial = financialKeywords.any { name.contains(it) }
                if (!isFinancial) {
                    Log.d("HWP_CALC", "비금융 채권사 (미협약 제외): $name (${amount}만)")
                } else {
                    nonAffiliatedDebt += amount
                    nonAffNames.add(name)
                    nonAffEntries.add(Pair(name, amount))
                    Log.d("HWP_CALC", "미협약 채권사: $name (${amount}만)")
                }
            } else {
                Log.d("HWP_CALC", "협약 채권사: $name (${amount}만)")
            }
        }
        Log.d("HWP_CALC", "미협약 채무 합계: ${nonAffiliatedDebt}만 (${parsedCreditorMap.size}개 채권사 중)")
        // 미협약 → 대상채무에서 차감
        if (nonAffiliatedDebt > targetDebt) nonAffiliatedDebt = targetDebt
        targetDebt -= nonAffiliatedDebt

        // ★ 담보 채권사도 미협약 여부 체크 (대상채무 제외는 유지, 특이사항 표시만)
        val nonAffDamboNames = mutableListOf<String>()
        for (damboCreditor in parsedDamboCreditorNames) {
            val nameForCheck = if (damboCreditor.contains("대부")) damboCreditor.replace("대부", "") else damboCreditor
            if (!AffiliateList.isAffiliated(damboCreditor) && !AffiliateList.isAffiliated(nameForCheck)) {
                val isFinancial = financialKeywords.any { damboCreditor.contains(it) }
                if (isFinancial) {
                    nonAffDamboNames.add(damboCreditor)
                    Log.d("HWP_CALC", "미협약 담보 채권사: $damboCreditor")
                }
            }
        }

        // ★ 과반 채권사 (parsedCreditorMap에서 기타채무 제외, [] 제거 후 합산)
        // [] 제거하여 동일 기관 합산 (예: "현대카드[1]", "현대카드[2]" → "현대카드")
        val normalizedCreditorMap = mutableMapOf<String, Int>()
        for ((name, amount) in parsedCreditorMap) {
            if (name in otherDebtNameSet) continue
            val normalizedName = name.replace(Regex("\\[.*?\\]"), "").trim()
            normalizedCreditorMap[normalizedName] = (normalizedCreditorMap[normalizedName] ?: 0) + amount
        }
        val majorEntry = normalizedCreditorMap.entries
            .filter { it.key !in otherDebtNameSet && !it.key.contains("새출발") && !it.key.contains("새출발기금") }
            .maxByOrNull { it.value }
        val majorCreditorFromParsing = if (majorEntry != null && (targetDebt + nonAffiliatedDebt) > 0 &&
            majorEntry.value.toDouble() / (targetDebt + nonAffiliatedDebt) > 0.5) majorEntry.key else ""
        val majorCreditorDebtFromParsing = if (majorCreditorFromParsing.isNotEmpty()) majorEntry!!.value else 0

        // ★ 채권사 수
        val parsedCreditorCount = parsedCreditorMap.size
        Log.d("HWP_CALC", "텍스트 파싱 채권사: ${parsedCreditorCount}건, 과반=${majorCreditorFromParsing}(${majorCreditorDebtFromParsing}만), 미협약=${nonAffiliatedDebt}만")

        // ★ 재산: 텍스트 파싱 기반 (AI 제거)
        val carPropertyValue = carInfoList.sumOf { info ->
            val net = maxOf(0, info[0] - info[1])
            if (info.size > 5 && info[5] > 0) Math.round(net.toDouble() * info[5] / 100).toInt() else net
        }
        // 재산: 지역/재산 필드에서 이미 대출 차감된 순가치 사용
        if (parsedPropertyTotal > 0 || regionSpouseProperty > 0 || carPropertyValue > 0 || bizDeposit > 0) {
            netProperty = parsedPropertyTotal + regionSpouseProperty + carPropertyValue + bizDeposit
            if (netProperty < 0) netProperty = 0
            Log.d("HWP_CALC", "재산 (텍스트파싱): 부동산${parsedPropertyTotal}만 + 배우자${regionSpouseProperty}만 + 차량${carPropertyValue}만 + 보증금${bizDeposit}만 = ${netProperty}만")
        }

        // 단기용 재산: 예적금 포함, 등본 분리 미적용
        var originalNetProperty = netProperty

        // 예적금 제외 (장기 재산에만 적용, 단기는 포함)
        if (savingsDeposit > 0) {
            netProperty = netProperty - savingsDeposit
            if (netProperty < 0) netProperty = 0
            Log.d("HWP_CALC", "예적금 제외(장기): 재산 $originalNetProperty → ${netProperty}만 (예적금 ${savingsDeposit}만)")
        }

        // 재산필드 배우자명의: 장기에서 제외 ([재산]에는 /2로 포함됨)
        val displayNetProperty = netProperty  // [재산] 표시용 (배우자명의 /2 포함)
        if (parsedSpouseProperty > 0) {
            netProperty = netProperty - parsedSpouseProperty
            if (netProperty < 0) netProperty = 0
            Log.d("HWP_CALC", "재산 배우자명의 제외(장기): ${parsedSpouseProperty}만 제외 → 재산 ${netProperty}만")
        }

        // 분양권: 재산에 더하지 않음, 최종 진단에 멘트 삽입
        if (hasBunyangGwon) {
            Log.d("HWP_CALC", "분양권 보유: 순가치 ${bunyangGwonNet}만 (재산 미포함, 진단 멘트로 처리)")
        }

        // ★ 개별 차량 처분 판단 로직
        var needsCarDisposal = false
        var dispCarSise = 0
        var dispCarLoan = 0
        var carDisposalReasons = mutableListOf<String>()

        fun shouldDisposeCar(info: IntArray): Boolean {
            // info: [시세, 대출, 월납부, 배우자(1/0), 외제(1/0)]
            val netValue = maxOf(0, info[0] - info[1])
            return netValue >= 1000 || info[2] >= 50 || info[4] == 1
        }

        fun getDisposalReason(info: IntArray): String {
            val reasons = mutableListOf<String>()
            val netValue = maxOf(0, info[0] - info[1])
            if (netValue >= 1000) {
                reasons.add("시세${info[0]}만/대출${info[1]}만")
            }
            if (info[2] >= 50) reasons.add("월납${info[2]}만")
            if (info[4] == 1) reasons.add("외제차")
            return reasons.joinToString("/")
        }

        if (carInfoList.size >= 2) {
            // 다수 차량: 조건 해당 차량만 처분 (조건 미해당 차량은 보유)
            val conditionCars = carInfoList.filter { shouldDisposeCar(it) }

            if (conditionCars.isNotEmpty()) {
                // 조건 해당 차량만 처분
                for (info in conditionCars) {
                    dispCarSise += info[0]
                    dispCarLoan += info[1]
                    carDisposalReasons.add(getDisposalReason(info))
                }
                needsCarDisposal = true
            }
            // 조건 미해당 차량은 모두 보유 (처분 불필요)
        } else if (carInfoList.size == 1) {
            // 단일 차량: 조건 해당이면 처분
            val info = carInfoList[0]
            if (shouldDisposeCar(info)) {
                dispCarSise = info[0]
                dispCarLoan = info[1]
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
            // 차량 처분: 시세 > 대출 차액은 AI 재산에 이미 포함, 대출 > 시세 부족분은 대상채무에 추가 (하단)
        }

        // ★ 6개월 이내 채무: 표에서 파싱한 값만 사용
        val recentDebtMan = recentDebtEntries.sumOf { (it.second + 5) / 10 }

        // ★ 2/3/4개월 이내 채무 합산 (납부회수 판단용)
        val twoMonthsAgo = Calendar.getInstance().apply { add(Calendar.MONTH, -2) }
        val threeMonthsAgo = Calendar.getInstance().apply { add(Calendar.MONTH, -3) }
        val fourMonthsAgo = Calendar.getInstance().apply { add(Calendar.MONTH, -4) }
        var twoMonthDebt = 0; var threeMonthDebt = 0; var fourMonthDebt = 0
        for ((cal, amountChon) in recentDebtEntries) {
            val amtMan = (amountChon + 5) / 10
            if (cal.after(twoMonthsAgo)) twoMonthDebt += amtMan
            if (cal.after(threeMonthsAgo)) threeMonthDebt += amtMan
            if (cal.after(fourMonthsAgo)) fourMonthDebt += amtMan
        }
        Log.d("HWP_CALC", "납부회수용: 2개월=${twoMonthDebt}만, 3개월=${threeMonthDebt}만, 4개월=${fourMonthDebt}만")

        // 대상채무 0 + 차량담보만 존재 → 차량 처분 후 남은 채무로 대상채무 재계산
        val onlyCarDambo = targetDebt == 0 && parsedCarDamboTotal > 0 && parsedDamboTotal == parsedCarDamboTotal
        if (onlyCarDambo) {
            targetDebt = maxOf(0, carTotalLoan - carTotalSise)
            Log.d("HWP_CALC", "대상채무 0, 차량담보만 존재: 차량담보=${parsedCarDamboTotal}만, 전체담보=${parsedDamboTotal}만, 처분후 대상채무=${targetDebt}만(대출${carTotalLoan}-시세${carTotalSise})")
        }

        val targetDebtBeforeDisposal = targetDebt

        // 한국자산관리공사(캠코) 보유 여부
        val hasKamco = parsedCreditorMap.keys.any { it.contains("자산관리공사") || it.contains("캠코") } ||
                parsedDamboCreditorNames.any { it.contains("자산관리공사") || it.contains("캠코") }
        if (hasKamco) {
            Log.d("HWP_CALC", "한국자산관리공사(캠코) 채권 보유 감지")
            specialNotesList.add("한국자산관리공사 채권보유")
        }

        // 6개월 비율: 6개월 이내 채무 / 전체 채무 (처분 전 대상채무 + 담보대출)
        val totalDebtForRatio = maxOf(totalParsedDebt, targetDebtBeforeDisposal + parsedDamboTotal)
        recentDebtRatio = if (totalDebtForRatio > 0 && recentDebtMan > 0) recentDebtMan.toDouble() / totalDebtForRatio * 100 else 0.0

        // ★ 미협약 비율: 대상채무 미협약 + 담보 미협약 합산
        val originalTargetDebt = targetDebt
        val totalDamboNonAff = if (nonAffDamboNames.isNotEmpty()) nonAffDamboNames.distinct().sumOf { parsedDamboCreditorMap[it] ?: 0 } else 0
        val totalNonAffDebt = nonAffiliatedDebt + totalDamboNonAff
        val allNonAffNames = (nonAffNames + nonAffDamboNames.distinct()).distinct()
        val nonAffiliatedOver20: Boolean
        if (totalNonAffDebt > 0) {
            val totalDebtAll = targetDebt + nonAffiliatedDebt + parsedDamboTotal
            val nonAffiliatedRatio = if (totalDebtAll > 0) totalNonAffDebt.toDouble() / totalDebtAll * 100 else 0.0
            nonAffiliatedOver20 = nonAffiliatedRatio >= 20
            val nonAffNamesStr = allNonAffNames.joinToString(",")
            if (nonAffiliatedOver20) {
                specialNotesList.add("미협약 ${String.format("%.0f", nonAffiliatedRatio)}% (신복위 불가) $nonAffNamesStr".trim())
            } else {
                specialNotesList.add("미협약 ${formatToEok(totalNonAffDebt)} 별도 (${String.format("%.0f", nonAffiliatedRatio)}%) $nonAffNamesStr".trim())
            }
            Log.d("HWP_CALC", "미협약 합산: ${totalNonAffDebt}만 (대상${nonAffiliatedDebt}만+담보${totalDamboNonAff}만), 비율 ${String.format("%.1f", nonAffiliatedRatio)}%")
        } else {
            nonAffiliatedOver20 = false
        }

        Log.d("HWP_CALC", "6개월 비율: ${recentDebtMan}만 / ${totalDebtForRatio}만 = ${String.format("%.1f", recentDebtRatio)}%")
        // ★ 소득: 텍스트 파싱 기반 (AI 제거)
        if (isIncomeX) {
            income = 0
            Log.d("HWP_CALC", "소득 (텍스트파싱): '소득 x' 감지 → 0만")
        } else if (parsedMonthlyIncome > 0) {
            income = parsedMonthlyIncome
            Log.d("HWP_CALC", "소득 (텍스트파싱): ${parsedMonthlyIncome}만")
        } else if (estimatedIncomeParsed > 0) {
            income = estimatedIncomeParsed
            isIncomeEstimated = true
            Log.d("HWP_CALC", "소득 (텍스트파싱 예정): ${estimatedIncomeParsed}만")
        }
        // 양육비 수급(입금받음/입금중) → 소득에 합산
        if (childSupportReceiving && childSupportAmount > 0) {
            income += childSupportAmount
            Log.d("HWP_CALC", "양육비 수급 소득 합산: +${childSupportAmount}만 → 소득=${income}만")
        }
        // 4대보험 X → 소득*0.8 적용 (계산용만, 표시는 원래 소득)
        val incomeBeforeInsurance = income
        if (noSocialInsurance && income > 0) {
            income = Math.round(income * 0.8).toInt()
            Log.d("HWP_CALC", "4대보험 X → 소득*0.8: ${incomeBeforeInsurance}만 → ${income}만")
        }
        Log.d("HWP_CALC", "계산값: 소득=$income, 대상채무=$targetDebt, 재산=$netProperty")

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
                recoveryDischargeYear = personalRecoveryYear
                recoveryDischargeMonth = personalRecoveryMonth
            }
            Log.d("HWP_CALC", "개인회생 → 면책 처리 (회생 진행중 아님): ${recoveryDischargeYear}년 ${recoveryDischargeMonth}월")
        }
        // 폐지 후 면책을 받았으면 폐지는 무효 (이후 회생이 성공한 것)
        if (isDismissed && hasDischarge) {
            isDismissed = false
            Log.d("HWP_CALC", "폐지+면책 → 폐지 무효 처리 (면책이 폐지를 대체)")
        }

        // ★ 과반 비율: 미협약 포함 전체 채무 기준으로 계산
        val majorExcluded = setOf("신복위", "신복", "신용", "카드", "담보", "신용회복위원회")
        var effectiveMajorCreditor = majorCreditorFromParsing
        var effectiveMajorDebt = majorCreditorDebtFromParsing
        majorCreditorRatio = if (originalTargetDebt > 0 && effectiveMajorDebt > 0 && effectiveMajorCreditor !in majorExcluded) effectiveMajorDebt.toDouble() / originalTargetDebt * 100 else 0.0

        // 변제율 결정 (기본 85% = 15%탕감)
        var repaymentRate = 85
        var rateReason = ""
        val guaranteeDaebuTotal = guaranteeDebtMan + daebuDebtMan
        val guaranteeDaebuRatio = if (originalTargetDebt > 0) guaranteeDaebuTotal.toDouble() / originalTargetDebt * 100 else 0.0
        val guaranteeRatio = if (originalTargetDebt > 0) guaranteeDebtMan.toDouble() / originalTargetDebt * 100 else 0.0
        val daebuRatio = if (originalTargetDebt > 0) daebuDebtMan.toDouble() / originalTargetDebt * 100 else 0.0
        val cardCapitalRatio = if (originalTargetDebt > 0) cardCapitalDebtMan.toDouble() / originalTargetDebt * 100 else 0.0
        // 과반 판단: 지급보증만 또는 대부만 있으면 50%, 둘 다 있으면 70%
        val guaranteeDaebuHasBoth = guaranteeDebtMan > 0 && daebuDebtMan > 0
        val guaranteeDaebuOnlyGuarantee = guaranteeDebtMan > 0 && daebuDebtMan == 0
        val guaranteeDaebuOnlyDaebu = daebuDebtMan > 0 && guaranteeDebtMan == 0
        val isGuaranteeDaebuMajor = (guaranteeDaebuHasBoth && guaranteeDaebuRatio >= 70) ||
                (guaranteeDaebuOnlyGuarantee && guaranteeRatio >= 50) ||
                (guaranteeDaebuOnlyDaebu && daebuRatio >= 50)
        // 100%: 채무 4000만 이하
        if (originalTargetDebt <= 4000 && originalTargetDebt > 0) {
            repaymentRate = 100; rateReason = "소액"
        }
        // 100%: 1개 채권사 50% 이상
        else if (majorCreditorRatio >= 50) {
            repaymentRate = 100; rateReason = "과반"
        }
        // 100%: 지급보증/대부 과반 (단독 50% / 병존 70%)
        else if (isGuaranteeDaebuMajor) {
            repaymentRate = 100; rateReason = when {
                guaranteeDaebuOnlyDaebu -> "대부 과반"
                guaranteeDaebuOnlyGuarantee -> "지급보증 과반"
                daebuDebtMan > guaranteeDebtMan * 1.5 -> "대부 과반"
                guaranteeDebtMan > daebuDebtMan * 1.5 -> "지급보증 과반"
                else -> "지급보증/대부 과반"
            }
        }
        // 80%: 대상채무 1억 이상 & 카드/캐피탈 50% 이상 (20%탕감)
        else if (originalTargetDebt >= 10000 && cardCapitalRatio >= 50) {
            repaymentRate = 80; rateReason = "카드/캐피탈 과반"
        }
        Log.d("HWP_CALC", "변제율: ${repaymentRate}% (${rateReason.ifEmpty { "기본15%탕감" }}), 지급보증=${guaranteeDebtMan}만(${String.format("%.1f", guaranteeRatio)}%), 대부=${daebuDebtMan}만(${String.format("%.1f", daebuRatio)}%), 합계=${guaranteeDaebuTotal}만(${String.format("%.1f", guaranteeDaebuRatio)}%), 카드캐피탈=${cardCapitalDebtMan}만(${String.format("%.1f", cardCapitalRatio)}%)")

        // ★ 이혼 + 양육비 미수급 → 본인이 양육하지 않으므로 미성년 부양 인정 불가
        // ★ 양육비 입금중(수급아님) + 금액 있음 → 이혼/재혼 무관 항상 공제 (소득에서 빠짐)
        val childSupportDeduction: Int  // 단기 양육비 공제액
        if (isDivorced && !childSupportReceiving) {
            minorChildren = 0
            childSupportDeduction = childSupportAmount  // 양육비 지급 중이면 공제, 없으면 0
            Log.d("HWP_CALC", "이혼+양육 안함 → 1인 생계비, 미성년 제외${if (childSupportAmount > 0) ", 양육비 ${childSupportAmount}만 공제" else ""}")
        } else if (!childSupportReceiving && childSupportAmount > 0) {
            // 재혼 등 isDivorced=false 이지만 전처/전남편에게 양육비 지급중인 경우
            childSupportDeduction = childSupportAmount
            Log.d("HWP_CALC", "양육비 입금중 → 공제: ${childSupportAmount}만")
        } else {
            childSupportDeduction = 0
        }

        // 최저생계비 (2026년)
        val livingCostTable = intArrayOf(0, 154, 252, 322, 390, 453, 513)
        // 단기(회생): 배우자 모르게이면 미성년 자녀 절반만 인정 (1.5명 → 1명, floor)
        val effectiveMinorForHoeseng = if (spouseSecret) minorChildren / 2 else minorChildren
        val householdForHoeseng = minOf(1 + effectiveMinorForHoeseng, 6)
        val livingCostHoeseng = livingCostTable[householdForHoeseng]
        val householdForShinbok = minOf(1 + minorChildren + collegeChildren, 6)
        val livingCostShinbok = livingCostTable[householdForShinbok]

        // 단기(회생) 계산
        var shortTermBlocked = false
        var shortTermBlockReason = ""
        val currentYear = java.time.LocalDate.now().year
        val currentMonth = java.time.LocalDate.now().monthValue

        // 파산 면책과 회생 면책 각각 독립 판단 (둘 다 5년 기준)
        fun within5(y: Int, mo: Int): Boolean = y > 0 && (
                (currentYear - y) < 5 ||
                        ((currentYear - y) == 5 && mo > 0 && currentMonth < mo) ||
                        ((currentYear - y) == 5 && mo == 0)
                )
        val bankruptcyDischargeWithin5 = within5(bankruptcyDischargeYear, bankruptcyDischargeMonth)
        val recoveryDischargeWithin5 = within5(recoveryDischargeYear, recoveryDischargeMonth)
        val dischargeWithin5Years = bankruptcyDischargeWithin5 || recoveryDischargeWithin5
        // 표시/로직용 대표값: 5년 이내인 것 우선, 둘 다면 최신, 없으면 최신
        if (bankruptcyDischargeWithin5 && !recoveryDischargeWithin5) {
            dischargeYear = bankruptcyDischargeYear; dischargeMonth = bankruptcyDischargeMonth; isBankruptcyDischarge = true
        } else if (recoveryDischargeWithin5 && !bankruptcyDischargeWithin5) {
            dischargeYear = recoveryDischargeYear; dischargeMonth = recoveryDischargeMonth; isBankruptcyDischarge = false
        } else if (bankruptcyDischargeWithin5 && recoveryDischargeWithin5) {
            if (bankruptcyDischargeYear >= recoveryDischargeYear) {
                dischargeYear = bankruptcyDischargeYear; dischargeMonth = bankruptcyDischargeMonth; isBankruptcyDischarge = true
            } else {
                dischargeYear = recoveryDischargeYear; dischargeMonth = recoveryDischargeMonth; isBankruptcyDischarge = false
            }
        } else {
            // 둘 다 5년 초과 또는 없음: 최신 값으로 (표시용)
            if (bankruptcyDischargeYear >= recoveryDischargeYear) {
                dischargeYear = bankruptcyDischargeYear; dischargeMonth = bankruptcyDischargeMonth; isBankruptcyDischarge = bankruptcyDischargeYear > 0
            } else {
                dischargeYear = recoveryDischargeYear; dischargeMonth = recoveryDischargeMonth; isBankruptcyDischarge = false
            }
        }
        Log.d("HWP_CALC", "면책 5년 판단: 파산${bankruptcyDischargeYear}년=${bankruptcyDischargeWithin5}, 회생${recoveryDischargeYear}년=${recoveryDischargeWithin5}, 대표=${dischargeYear}년(파산=$isBankruptcyDischarge)")

        // ★ 세금: 텍스트 파싱 (AI 미사용)
        taxDebt = textTaxDebt
        if (taxDebt > 0) Log.d("HWP_CALC", "세금 (텍스트파싱): ${taxDebt}만")
        // 단기(회생)는 세금 포함
        val shortTermDebt = targetDebt + taxDebt
        if (taxDebt > 0) Log.d("HWP_CALC", "단기 대상채무: $targetDebt + 세금$taxDebt = ${shortTermDebt}만")

        // 단기 재산: 배우자 차량 + 분양권 순가치 포함 (장기는 분양권 미포함 - 진단 멘트로만 처리)
        val shortTermProperty = originalNetProperty + spouseCarSiseTotal + (if (hasBunyangGwon) bunyangGwonNet else 0)
        val dischargeEndsSameYear = dischargeWithin5Years && dischargeYear > 0 && (dischargeYear + 5 == currentYear)
        if (dischargeWithin5Years && !dischargeEndsSameYear) { shortTermBlocked = true; shortTermBlockReason = if (isBankruptcyDischarge) "파산 면책 5년 이내" else "면책 5년 이내" }
        if (shortTermProperty > shortTermDebt && shortTermDebt > 0) { shortTermBlocked = true; if (shortTermBlockReason.isNotEmpty()) shortTermBlockReason += ", "; shortTermBlockReason += "재산초과" }
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

        val shortTermIncome = (if (noSocialInsurance) incomeBeforeInsurance else income) + rentalIncomeMan
        if (rentalIncomeMan > 0) Log.d("HWP_CALC", "단기 소득에 월세수익 포함: ${shortTermIncome - rentalIncomeMan}+${rentalIncomeMan}=${shortTermIncome}만")
        // 차량 처분 의사 + 재산초과로 단기 불가일 때 → 차량 처분 기준으로 단기 재계산
        var shortTermAfterCarSale = ""
        var shortTermCarSaleApplied = false
        if ((wantsCarSale || needsCarDisposal) && shortTermBlocked && shortTermBlockReason == "재산초과" && carTotalLoan > 0) {
            val propertyAfterCarSale = originalNetProperty  // 차량 순가치는 AI 재산에 이미 포함
            val debtAfterCarSale = shortTermDebt
            Log.d("HWP_CALC", "차량처분 단기 검토: wantsCarSale=$wantsCarSale, carTotalLoan=$carTotalLoan, carTotalSise=$carTotalSise, carValue=$carValue, 재산후=$propertyAfterCarSale, 대상후=$debtAfterCarSale")

            if (propertyAfterCarSale <= debtAfterCarSale || debtAfterCarSale <= 0) {
                // 차량 처분하면 재산초과 해소 → 단기 가능
                val stMonthly = shortTermIncome - livingCostHoeseng
                val stMonthlyAdj = if (stMonthly <= 0 && shortTermIncome > livingCostTable[1]) shortTermIncome - livingCostTable[1] else stMonthly
                if (stMonthlyAdj > 0) {
                    var stMonths = Math.round(debtAfterCarSale.toDouble() / stMonthlyAdj).toInt()
                    if (stMonths > 60) stMonths = 60
                    if (stMonths < 1) stMonths = 1
                    val roundedSt = stMonthlyAdj
                    val minYears = Math.ceil(stMonths / 12.0).toInt()
                    val maxYears = Math.ceil(60 / 12.0).toInt()
                    shortTermAfterCarSale = "${roundedSt}만 ${minYears}~${maxYears}년납"
                    shortTermCarSaleApplied = true
                    Log.d("HWP_CALC", "차량 처분시 단기: 대상=$debtAfterCarSale, 재산=$propertyAfterCarSale, 월=$roundedSt, ${minYears}~${maxYears}년")
                } else if (shortTermIncome <= livingCostTable[1] && debtAfterCarSale > propertyAfterCarSale) {
                    // 소득 < 1인 생계비 → (대상-재산)÷36
                    val stByDebt = Math.ceil((debtAfterCarSale - propertyAfterCarSale).toDouble() / 36).toInt()
                    val roundedSt = stByDebt
                    shortTermAfterCarSale = "${roundedSt}만 3~5년납"
                    shortTermCarSaleApplied = true
                    Log.d("HWP_CALC", "차량 처분시 단기(소득<1인생계비): ($debtAfterCarSale-$propertyAfterCarSale)÷36=$stByDebt → ${roundedSt}만")
                }
            }
        }

        var shortTermMonthly = shortTermIncome - livingCostHoeseng - childSupportDeduction
        // 가구수 단계적으로 내려가며 적용 (3인→2인→1인)
        var shortTermHousehold = householdForHoeseng
        while (shortTermMonthly <= 0 && shortTermHousehold > 1) {
            shortTermHousehold--
            shortTermMonthly = shortTermIncome - livingCostTable[shortTermHousehold] - childSupportDeduction
        }
        if (shortTermHousehold != householdForHoeseng) {
            Log.d("HWP_CALC", "단기 생계비 조정: ${householdForHoeseng}인(${livingCostTable[householdForHoeseng]}) → ${shortTermHousehold}인(${livingCostTable[shortTermHousehold]}), 월변제금=$shortTermMonthly")
        }

        var shortTermMonths = 0
        var shortTermResult = ""

        var shortTermHardBlocked = (dischargeWithin5Years && !dischargeEndsSameYear) || (shortTermProperty > shortTermDebt && shortTermDebt > 0)
        if (shortTermBlocked && shortTermHardBlocked) {
            shortTermResult = "단기 불가 ($shortTermBlockReason)"
        } else if (shortTermIncome <= 100) {
            shortTermResult = "단기 불가 (소득부족)"
            shortTermBlocked = true
        } else if (shortTermMonthly <= 0) {
            shortTermResult = "단기 불가 (소득부족)"
            shortTermBlocked = true
        } else {
            // 소득-최저생계비 최소값 적용: 거래처 50만, 본체 40만
            val shortTermMinMonthly = if (isClientMode) 50 else 40
            if (shortTermMonthly < shortTermMinMonthly) shortTermMonthly = shortTermMinMonthly
            // 재산/(소득-최저생계비) 기준 기간 결정
            if (shortTermProperty <= 0) {
                shortTermMonths = 36
            } else {
                val propertyMonths = Math.ceil(shortTermProperty.toDouble() / shortTermMonthly).toInt()
                if (propertyMonths <= 36) {
                    shortTermMonths = 36
                } else if (propertyMonths <= 60) {
                    shortTermMonths = propertyMonths
                } else {
                    shortTermMonthly = Math.ceil(shortTermProperty.toDouble() / 60).toInt()
                    shortTermMonths = 60
                }
            }
            // 최종 월변제금이 소득보다 높으면 단기 불가 (소득부족)
            if (shortTermMonthly > shortTermIncome) {
                shortTermResult = "단기 불가 (소득부족)"
                shortTermBlocked = true
                Log.d("HWP_CALC", "단기 불가: 월변제금 ${shortTermMonthly}만 > 소득 ${shortTermIncome}만 → 소득부족")
            } else {
                shortTermResult = "${shortTermMonthly}만 / ${shortTermMonths}개월납"
                Log.d("HWP_CALC", "단기 계산: 가용소득=${shortTermMonthly}만, 재산=${shortTermProperty}만${if (spouseCarSiseTotal > 0) "(배우자차량${spouseCarSiseTotal}만포함)" else ""}, ${shortTermMonths}개월")
            }
        }
        // 면책/재산초과 외 불가사유 → 계산 결과 + 사유 별도 표시
        if (shortTermBlocked && !shortTermHardBlocked && shortTermBlockReason.isNotEmpty() && !shortTermResult.contains("불가")) {
            shortTermResult = "$shortTermResult ($shortTermBlockReason)"
        }
        // 대출과목에 "집담보" 키워드 있을 때만 → 집경매 위험 (본인 부동산이라도 본인 명의 집담보대출이 없으면 위험 없음)
        if (hasHomeMortgageInSidebar && shortTermDebt > 0) {
            shortTermBlocked = true
            if (shortTermBlockReason.isNotEmpty()) shortTermBlockReason += ", "
            shortTermBlockReason += "집경매 위험"
            if (shortTermHardBlocked) {
                shortTermResult = "단기 불가 ($shortTermBlockReason)"
            } else {
                // 집경매 위험: 전액 변제해야 경매 중단 → 단기 개월수를 대상채무/월변제금으로 최대화 (최대 60개월)
                if (shortTermMonthly > 0 && targetDebt > 0) {
                    val auctionMonths = (targetDebt / shortTermMonthly).coerceAtMost(60)
                    if (auctionMonths > shortTermMonths) {
                        shortTermMonths = auctionMonths
                        shortTermResult = "${shortTermMonthly}만 / ${shortTermMonths}개월납"
                        Log.d("HWP_CALC", "집경매 위험 → 단기 개월수 확장: ${targetDebt}만 / ${shortTermMonthly}만 = ${shortTermMonths}개월 (최대 60)")
                    }
                }
                if (!shortTermResult.contains("집경매")) shortTermResult = "$shortTermResult (집경매 위험)"
            }
        }

        // 변제계획안 존재 시 단기 결과 오버라이드 (이미 진행 중인 회생)
        if (aiHasRecoveryPlan && pdfRecoveryIncome > 0) {
            shortTermMonthly = pdfRecoveryIncome
            shortTermMonths = if (pdfRecoveryMonths > 0) pdfRecoveryMonths else 36
            shortTermResult = "${pdfRecoveryIncome}만 / ${shortTermMonths}개월납"
            shortTermBlocked = false
            Log.d("HWP_CALC", "변제계획안 단기 오버라이드: ${pdfRecoveryIncome}만 / ${shortTermMonths}개월납")
        }

        // 학자금대출: 단기는 포함, 장기는 제외 → 단기 계산 후 대상채무에서 차감
        val parsedIncludesStudentLoan = parsedCreditorMap.keys.any { it.contains("장학재단") || it.contains("학자금") }
        if (studentLoanMan > 0 && parsedIncludesStudentLoan) {
            targetDebt -= studentLoanMan
            if (targetDebt < 0) targetDebt = 0
            Log.d("HWP_CALC", "학자금 제외 (장기용): ${studentLoanMan}만 차감 → 대상채무=${targetDebt}만")
        } else if (studentLoanMan > 0) {
            Log.d("HWP_CALC", "학자금 차감 스킵: 파싱에서 학자금 미포함 (${studentLoanMan}만)")
        }
        // 학자금 제외 후 과반 비율 재계산
        if (studentLoanMan > 0 && parsedIncludesStudentLoan && targetDebt > 0) {
            // 학자금 제외 후 최대 채권사 재판단
            val majorEntryAfterStudent = normalizedCreditorMap.entries
                .filter { it.key !in otherDebtNameSet && !it.key.contains("장학재단") && !it.key.contains("학자금") }
                .maxByOrNull { it.value }
            if (majorEntryAfterStudent != null && majorEntryAfterStudent.value.toDouble() / targetDebt > 0.5) {
                effectiveMajorCreditor = majorEntryAfterStudent.key
                effectiveMajorDebt = majorEntryAfterStudent.value
                majorCreditorRatio = effectiveMajorDebt.toDouble() / targetDebt * 100
                Log.d("HWP_CALC", "학자금 제외 후 과반 재계산: ${effectiveMajorCreditor} ${effectiveMajorDebt}만 / ${targetDebt}만 = ${String.format("%.1f", majorCreditorRatio)}%")
            }
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

        // 단기(회생) 임차보증금 공제 — 재산초과 판단에만 적용 (기간/변제금 계산에는 원래 재산 사용)
        var rentalDeductionApplied = false
        if (rentalDeposit > 0 && rentalDeposit <= baseExemption) {
            val rentalDeduction = when {
                regionLower.contains("서울") -> 5500
                baseExemption == 14500 -> 4800  // 과밀억제권역
                baseExemption == 8500 -> 2800   // 광역시/인천
                else -> 2500
            }
            val deductedProperty = maxOf(shortTermProperty - rentalDeduction, 0)
            Log.d("HWP_CALC", "단기 임차보증금 공제: 보증금=${rentalDeposit}만 ≤ 한도${baseExemption}만 → ${rentalDeduction}만 공제, 공제후재산=${deductedProperty}만")
            // 공제 후 재산초과 재평가
            if (shortTermBlocked && shortTermBlockReason == "재산초과" && (deductedProperty <= shortTermDebt || shortTermDebt <= 0)) {
                shortTermBlocked = false
                shortTermBlockReason = ""
                shortTermHardBlocked = (dischargeWithin5Years && !dischargeEndsSameYear)
                rentalDeductionApplied = true
                // 단기 결과 재계산
                if (!shortTermHardBlocked && shortTermIncome > 100 && shortTermMonthly > 0) {
                    if (shortTermProperty <= 0) {
                        shortTermMonths = 36
                    } else {
                        val propertyMonths = Math.ceil(shortTermProperty.toDouble() / shortTermMonthly).toInt()
                        if (propertyMonths <= 36) {
                            shortTermMonths = 36
                        } else if (propertyMonths <= 60) {
                            shortTermMonths = propertyMonths
                        } else {
                            shortTermMonthly = Math.ceil(shortTermProperty.toDouble() / 60).toInt()
                            shortTermMonths = 60
                        }
                    }
                    shortTermResult = "${shortTermMonthly}만 / ${shortTermMonths}개월납"
                }
                Log.d("HWP_CALC", "임차보증금 공제로 재산초과 해소: 공제후재산=${deductedProperty}만 ≤ 대상${shortTermDebt}만 → $shortTermResult")
            }
        }

        var netPropertyAfterExemption = netProperty - exemptionAmount
        if (netPropertyAfterExemption < 0) netPropertyAfterExemption = 0
        // 지급보증이 있으면 운전자금도 보증채무에 포함
        val effectiveGuaranteeDebt = if (jigubojungDebtMan > 0) jigubojungDebtMan + guaranteeDebtMan else jigubojungDebtMan
        val targetDebtExGuarantee = if (delinquentDays < 90) maxOf(targetDebt - effectiveGuaranteeDebt, 0) else targetDebt
        var longTermPropertyExcess = netPropertyAfterExemption > targetDebtExGuarantee && targetDebtExGuarantee > 0
        Log.d("HWP_CALC", "장기 재산초과 판단: ($netProperty - $exemptionAmount) = $netPropertyAfterExemption > ${targetDebtExGuarantee}(대상${targetDebt}${if (delinquentDays < 90) "-보증채무${effectiveGuaranteeDebt}(지급보증${jigubojungDebtMan}+운전자금${guaranteeDebtMan})" else ""}) → $longTermPropertyExcess")

        // 등본 분리: 재산초과이면서 배우자명의 시세가 있을 때만 적용
        val isRegistrySplit = if (longTermPropertyExcess && regionIsSpouseOwned && regionSpouseProperty > 0) {
            // 지역이 배우자명의 + 재산초과 → 배우자명의 재산 + 재산필드 타인명의 모두 제외
            netProperty = netProperty - regionSpouseProperty - parsedOthersProperty
            if (netProperty < 0) netProperty = 0
            netPropertyAfterExemption = maxOf(netProperty - exemptionAmount, 0)
            longTermPropertyExcess = netPropertyAfterExemption > targetDebtExGuarantee
            Log.d("HWP_CALC", "등본 분리 적용(배우자명의 + 재산초과): 재산 → $netProperty (배우자${regionSpouseProperty}만 + 타인${parsedOthersProperty}만 제외), 재산초과=$longTermPropertyExcess")
            true
        } else if (longTermPropertyExcess && !hasOwnRealEstate && hasOthersRealEstate && parsedOthersProperty > 0) {
            // 본인명의 없고 타인명의만 + 재산초과 → 전체 타인명의 재산 제외
            netProperty = netProperty - parsedOthersProperty
            if (netProperty < 0) netProperty = 0
            netPropertyAfterExemption = maxOf(netProperty - exemptionAmount, 0)
            longTermPropertyExcess = netPropertyAfterExemption > targetDebtExGuarantee
            Log.d("HWP_CALC", "등본 분리 적용(타인명의 + 재산초과): 재산 → $netProperty (타인${parsedOthersProperty}만 제외), 재산초과=$longTermPropertyExcess")
            true
        } else false

        val longTermCarBlocked = carValue >= 1000 || carCount >= 2 || carMonthlyPayment >= 50
        if (longTermCarBlocked) Log.d("HWP_CALC", "장기 차량 불가: 순가치=${carValue}만(시세${carTotalSise}-대출${carTotalLoan}), ${carCount}대, 월납=${carMonthlyPayment}만")
        // 차량에 잔여가치 없으면 (시세-담보<=0) 장기 차량 불가 무시 (특이에만 표시)
        val longTermCarBlockedEffective = longTermCarBlocked && carValue > 0

        // 장기(신복위) 계산 준비 — 양육비 지급 시 소득에서 차감
        val longTermIncome = if (childSupportDeduction > 0) income - childSupportDeduction else income
        val availableIncome = longTermIncome - livingCostShinbok
        val yearlyIncomeCalc = longTermIncome * 12
        val totalPayment = (targetDebt * repaymentRate / 100.0).toInt()
        val parentDeduction = if (parentCount > 0) 50 else 0
        var longTermIsFullPayment = totalPayment >= targetDebt && targetDebt > 0

        Log.d("HWP_CALC", "장기 계산: 소득=$longTermIncome${if (childSupportDeduction > 0) "(양육비-${childSupportDeduction}만)" else ""}, 생계비=$livingCostShinbok, 부모=${parentCount}명(${parentDeduction}만), 가용소득=${availableIncome - parentDeduction}")

        // shortTermTotal 계산 (장기 계산보다 먼저 필요)
        if (shortTermMonthly > 0 && shortTermMonths > 0 && shortTermMonthly * shortTermMonths > shortTermDebt) {
            shortTermMonths = shortTermDebt / shortTermMonthly
            if (shortTermMonths < 1) shortTermMonths = 1
            shortTermResult = "${shortTermMonthly}만 / ${shortTermMonths}개월납"
        }
        var shortTermTotal = if (!shortTermBlocked && shortTermMonthly > 0) shortTermMonthly * shortTermMonths else 0
        // 도박 → 단기 원금전액 기준
        if (hasGambling && !shortTermBlocked && shortTermMonthly > 0 && shortTermTotal < shortTermDebt) {
            shortTermMonths = Math.ceil(shortTermDebt.toDouble() / shortTermMonthly).toInt()
            if (shortTermMonths > 60) {
                shortTermMonthly = Math.ceil(shortTermDebt.toDouble() / 60).toInt()
                shortTermMonths = 60
            }
            shortTermTotal = shortTermMonthly * shortTermMonths
            shortTermResult = "${shortTermMonthly}만 / ${shortTermMonths}개월납"
            Log.d("HWP_CALC", "도박 → 단기 원금전액: ${shortTermTotal}만 (${shortTermMonthly}만/${shortTermMonths}개월)")
        }
        if (shortTermCarSaleApplied) {
            val debtAfterCar = targetDebt
            val propAfterCar = netProperty - carValue
            val stMonthly = income - livingCostHoeseng
            val stAdj = if (stMonthly <= 0 && income > livingCostTable[1]) income - livingCostTable[1] else stMonthly
            if (stAdj > 0) {
                var stMonths = Math.round(debtAfterCar.toDouble() / stAdj).toInt().coerceIn(1, 60)
                shortTermTotal = stAdj * stMonths
            } else if (income <= livingCostTable[1] && debtAfterCar > propAfterCar) {
                val stByDebt = Math.ceil((debtAfterCar - propAfterCar).toDouble() / 36).toInt()
                shortTermTotal = stByDebt * 36
            }
        }
        if (shortTermBlocked && !shortTermHardBlocked && shortTermBlockReason.isNotEmpty() && !shortTermResult.contains(shortTermBlockReason)) {
            shortTermResult = "$shortTermResult ($shortTermBlockReason)"
        }

        // 장기(신복위) 보수 + 공격 + 최종 계산 (공통 함수 사용)
        val ltResult = calcLongTermValues(
            totalPayment, targetDebt, longTermIncome, livingCostShinbok, livingCostTable, parentDeduction,
            householdForShinbok, parentCount, isFreelancer, longTermIsFullPayment,
            hasWolse, parsedDamboTotal,
            hasAuction, hasSeizure, hasGambling, hasStock, hasCrypto,
            recentDebtRatio, delinquentDays, hasOwnRealEstate,
            majorCreditorRatio, shortTermTotal,
            minMonthly = 40, maxMonths = 120
        )
        var roundedLongTermMonthly = ltResult.conservativeMonthly
        var longTermYears = ltResult.conservativeYears
        var roundedAggressiveMonthly = ltResult.aggressiveMonthly
        var aggressiveYears = ltResult.aggressiveYears
        val longTermDebtInsufficient = ltResult.debtInsufficient
        longTermIsFullPayment = ltResult.isFullPayment
        var longTermUseMonths = ltResult.useMonths
        var longTermDisplayMonths = ltResult.displayMonths
        acost = roundedLongTermMonthly

        // 장기는 소득부족이어도 최소 40만으로 진행 가능 (소득부족은 단기에만 해당)

        // 새출발기금 - ★ 사업자 이력은 AI 값 사용, 프리랜서는 사업자이력이 아님
        // 사업 기간이 2020.04~2025.06과 겹치지 않으면 새출발기금 대상 아님
        if (hasBusinessHistory && businessStartYear > 0) {
            // 새새 가능기간: 2020.04 ~ 2025.06 안에 개업해야 함
            val bizStartInPeriod = businessStartYear < 2025 || (businessStartYear == 2025 && (businessStartMonth == 0 || businessStartMonth <= 6))
            val bizOverlap = bizStartInPeriod && (businessEndYear == 0 || businessEndYear >= 2020)
            if (!bizOverlap) {
                hasBusinessHistory = false
                Log.d("HWP_CALC", "사업자이력 제외: 개업=${businessStartYear}년${if (businessStartMonth > 0) "${businessStartMonth}월" else ""}, 폐업=${businessEndYear}년 (2020.04~2025.06 기간 밖)")
            }
        }
        val saeDebtOverLimit = totalSecuredDebt > 100000 || totalUnsecuredDebt > 50000  // 새새: 담보10억/무담보5억
        val saePropertyExcess = netProperty > 50000  // 새새: 재산 5억 초과 시 불가
        val noDebtDuringBusiness = businessEndYear > 0 && !hasDebtDuringBusiness  // 폐업했는데 사업기간 중 채무 없음 → 사업무관
        val effectiveCorporate = isCorporateBusiness && !isBusinessOwner  // 개인사업자 겸 법인이면 개인사업자 우선
        daebuCreditorCount = daebuCreditorNames.size
        val canApplySae = hasBusinessHistory && !(isFreelancer && !isBusinessOwner) && !isNonProfit && !effectiveCorporate && !saeDebtOverLimit && !saePropertyExcess && !hasSaechulbalBusilChaju
        Log.d("HWP_CALC", "새새 조건: 사업이력=$hasBusinessHistory(AI), 실제연체=${actualDelinquentDays}일(전체=${delinquentDays}일), 프리랜서=$isFreelancer, 사업자=$isBusinessOwner, 비영리=$isNonProfit, 법인=$isCorporateBusiness, 개업=${businessStartYear}년${if (businessStartMonth > 0) "${businessStartMonth}월" else ""}, 폐업=$businessEndYear, 사업중채무=$hasDebtDuringBusiness, 채무한도초과=$saeDebtOverLimit(담보=${totalSecuredDebt}만,무담보=${totalUnsecuredDebt}만), 재산초과=$saePropertyExcess(재산${netProperty}-채무$targetDebt=${netProperty - targetDebt}만)")
        var saeTotalPayment = 0; var saeMonthly = 0; var saeYears = 0
        val saeTargetDebt = targetDebt - saeExcludedDebtMan  // 기업은행 근로복지공단 보증 등 새출발 제외
        if (saeExcludedDebtMan > 0) Log.d("HWP_CALC", "새새 대상채무: ${targetDebt}만 - 제외${saeExcludedDebtMan}만 = ${saeTargetDebt}만")
        if (canApplySae && saeTargetDebt > 0) {
            // 새새용 본인/공동명의 재산 (배우자단독명의 제외, 등본분리 시 이미 제외됨)
            val saeOwnProperty = if (!isRegistrySplit) maxOf(0, netProperty - regionSpouseProperty) else netProperty
            saeTotalPayment = when {
                saeOwnProperty <= 0 -> (saeTargetDebt * 0.7).toInt()  // 본인/공동명의 재산 없음: 30% 탕감
                saeOwnProperty > saeTargetDebt / 2 -> saeTargetDebt - (maxOf(0, saeTargetDebt - saeOwnProperty) * 0.6).toInt()
                else -> (saeTargetDebt * 0.7).toInt()
            }
            // 소득/대상채무 비율로 기간 결정
            val incomeRatio = income.toDouble() / saeTargetDebt * 100
            saeYears = when {
                incomeRatio > 6 -> 5
                incomeRatio > 3 -> 8
                else -> 10
            }
            val saeIsFullPayment = saeOwnProperty >= saeTargetDebt  // 원금 전액 변제
            saeMonthly = saeTotalPayment / (saeYears * 12)
            // 5만 단위 반올림 (원금 전액 변제 시 적용 안함)
            if (!saeIsFullPayment && saeMonthly >= 40) {
                saeMonthly = (saeMonthly + 2) / 5 * 5
            }
            // 총변제액이 대상채무 초과 시 내림
            if (saeYears > 0 && saeMonthly * saeYears * 12 > saeTargetDebt) {
                saeMonthly = saeTargetDebt / (saeYears * 12)
            }
            if (saeMonthly < 40) {
                saeMonthly = 40
                val exactYears = saeTotalPayment.toDouble() / 40 / 12.0
                saeYears = if (exactYears - Math.floor(exactYears) >= 0.35) Math.ceil(exactYears).toInt() else Math.floor(exactYears).toInt()
                saeYears = saeYears.coerceIn(2, 10)
                saeTotalPayment = saeMonthly * saeYears * 12
                Log.d("HWP_CALC", "새새: 총변제=${saeTotalPayment}만, 월변제금 40만 미만 → 40만 고정, ${saeYears}년")
            } else {
                saeTotalPayment = saeMonthly * saeYears * 12
                Log.d("HWP_CALC", "새새: 총변제=${saeTotalPayment}만, 소득비율=${String.format("%.1f", incomeRatio)}%, ${saeMonthly}만/${saeYears}년${if (saeIsFullPayment) " (원금전액)" else ""}")
            }
        }

        // 특이사항
        val familyInfo = StringBuilder()
        familyInfo.append(if (isDivorced) "이혼" else if (hasSpouse || minorChildren > 0 || collegeChildren > 0) "기혼" else "미혼")
        if (minorChildren > 0) familyInfo.append(" 미성년$minorChildren")
        if (collegeChildren > 0) familyInfo.append(" 대학생$collegeChildren")
        if (parentCount > 0) familyInfo.append(" 60세부모$parentCount")
        specialNotesList.add(0, familyInfo.toString())
        if (isFreelancer && !isFreelancerByNetIncome) specialNotesList.add("프리랜서")

        // 과반 채권사 (미협약 채권은 과반 표시 제외)
        val isMajorCreditorNonAffiliated = nonAffNames.any { effectiveMajorCreditor.contains(it) || it.contains(effectiveMajorCreditor) }
        if (originalTargetDebt > 0 && effectiveMajorCreditor.isNotEmpty() && majorCreditorRatio > 50 && !isMajorCreditorNonAffiliated) {
            specialNotesList.add("$effectiveMajorCreditor 과반 (${String.format("%.0f", majorCreditorRatio)}%)")
            Log.d("HWP_CALC", "과반 채권사 (AI): $effectiveMajorCreditor ${effectiveMajorDebt}만 / ${originalTargetDebt}만 = ${String.format("%.1f", majorCreditorRatio)}%")
        }
        specialNotesList.add("6개월 이내 ${String.format("%.0f", recentDebtRatio)}%")
        if (needsCarDisposal && !longTermPropertyExcess) {
            val disposeCars = carInfoList.indices.filter { shouldDisposeCar(carInfoList[it]) }
            if (carCount >= 2) specialNotesList.add("차량 ${carCount}대 이상 보유")
            for (idx in disposeCars) {
                val name = if (idx < carNameList.size) carNameList[idx] else "차량${idx + 1}"
                val info = carInfoList[idx]
                val d = if (idx < carDamboAmountList.size) carDamboAmountList[idx] else 0
                val h = if (idx < carHalbuAmountList.size) carHalbuAmountList[idx] else 0
                val label = if (carCount == 1 && info[2] in 50..55) "일부 상환 필요" else "처분필요"
                val lp = if (d > 0 && h > 0) "담보${d}만 / 차량할부${h}만" else "담보${info[1]}만"
                specialNotesList.add("$name $label (시세${info[0]}만 / $lp / 월${info[2]}만)")
            }
        } else if (longTermCarBlocked && !longTermCarBlockedEffective && !longTermPropertyExcess) {
            val cn = if (carNameList.isNotEmpty()) carNameList[0] else "차량"
            val lb = if (carMonthlyPayment in 50..55) "일부 상환 필요" else "처분필요"
            val d0 = if (carDamboAmountList.isNotEmpty()) carDamboAmountList[0] else 0
            val h0 = if (carHalbuAmountList.isNotEmpty()) carHalbuAmountList[0] else 0
            val lp = if (d0 > 0 && h0 > 0) "담보${d0}만 / 차량할부${h0}만" else "담보${carTotalLoan}만"
            specialNotesList.add("$cn $lb (시세${carTotalSise}만 / $lp / 월${carMonthlyPayment}만)")
        } else if (carCount >= 2 && !needsCarDisposal) {
            // 2대 보유 자체만 표시 (처분 불필요)
            specialNotesList.add("차량 ${carCount}대 보유")
        }
        if (hasJointCar && !needsCarDisposal && !(longTermCarBlocked && !longTermCarBlockedEffective) && !longTermPropertyExcess) {
            specialNotesList.add("장기 시 차량처분 필요")
        }
        // 사업자이력 년도 정보 추가 (새출발 가능기간 내 사업이력만 표시, 법인사업은 제외)
        val hasBizHistory = hasBusinessHistory && !effectiveCorporate && !isNonProfit
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
        if (hasGambling) specialNotesList.add(if (hasGame) "게임" else "도박")
        if (hasStock) specialNotesList.add("주식")
        if (hasCrypto) specialNotesList.add("코인")
        if (childSupportAmount > 0) {
            val childSupportSuffixNote = if (childSupportReceiving) " 입금받음" else " 송금중"
            specialNotesList.add("월 양육비 ${childSupportAmount}만${childSupportSuffixNote}")
        }
        if (spouseSecret) specialNotesList.add("배우자 모르게")
        if (familySecret) specialNotesList.add("가족 모르게")
        if (hasHomeMortgageInSidebar) specialNotesList.add("집경매 위험")
        if (hasAuction) specialNotesList.add("경매진행중")
        if (hasSeizure) specialNotesList.add("압류진행중")
        if (delinquentDays >= 90) specialNotesList.add("장기연체자")
        if (isGuaranteeDaebuMajor) {
            val displayRatio = when {
                guaranteeDaebuOnlyGuarantee -> guaranteeRatio
                guaranteeDaebuOnlyDaebu -> daebuRatio
                else -> guaranteeDaebuRatio
            }
            val majorReason = when {
                guaranteeDaebuOnlyDaebu -> "대부 과반"
                guaranteeDaebuOnlyGuarantee -> "지급보증 과반"
                daebuDebtMan > guaranteeDebtMan * 1.5 -> "대부 과반"
                guaranteeDebtMan > daebuDebtMan * 1.5 -> "지급보증 과반"
                else -> "지급보증/대부 과반"
            }
            specialNotesList.add("$majorReason (${String.format("%.0f", displayRatio)}%)")
        }
        if (daebuCreditorCount >= 2) specialNotesList.add("대부 2건 이상")
        if (hasCivilCase) specialNotesList.add("민사 소송금 따로 변제")
        if (hasUsedCarInstallment) specialNotesList.add("중고차 할부 따로 납부")
        if (hasHealthInsuranceDebt) specialNotesList.add("건강보험 체납 따로 변제")
        if (hasInsurancePolicyLoan) specialNotesList.add("보험약관대출 제외")
        if (taxDebt > 0) specialNotesList.add("세금 미납 ${taxDebt}만 따로 납부")
        if (saeExcludedDebtMan > 0) specialNotesList.add("기업은행(근로복지공단) 따로납부")
        if (hasHfcMortgage) specialNotesList.add("한국주택금융공사 집담보 보유")
        if (savingsDeposit > 0) specialNotesList.add("예적금 ${formatToEok(savingsDeposit)}")
        // 만 나이 계산 + 65세 이상 고령자
        val age = if (birthYear > 0) {
            val today = java.time.LocalDate.now()
            val birthDate = java.time.LocalDate.of(birthYear, birthMonth.coerceIn(1, 12), birthDay.coerceIn(1, 28))
            java.time.Period.between(birthDate, today).years
        } else 0
        if (age >= 65) specialNotesList.add("고령자")
        if (hasDisability) specialNotesList.add("장애등급")
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

        // 유예 가능 여부: 상환내역서 유예기간 12개월 미만이면 유예 가능 (6개월x2회)
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
            val propertyAfterCarSale = netProperty  // 차량 매도 시 현금으로 전환되므로 재산 총액 동일
            var netAfterExemption = propertyAfterCarSale - exemptionAmount
            if (netAfterExemption < 0) netAfterExemption = 0
            val propertyOk = netAfterExemption <= targetDebtExGuarantee
            canLongTermAfterCarSale = propertyOk
            Log.d("HWP_CALC", "차량 처분시: 재산=$propertyAfterCarSale, 공제후=$netAfterExemption, 대상채무=$targetDebtExGuarantee, 재산OK=$propertyOk → $canLongTermAfterCarSale")
        }

        var isBangsaeng = false
        var bangsaengReason = ""
        Log.d("HWP_CALC", "방생 조건 체크: 재산초과=${netProperty > targetDebtExGuarantee && longTermPropertyExcess}, 경매=$hasAuction, 차량=${longTermCarBlockedEffective}, 단기불가=$shortTermBlocked, 장기채무한도=$longTermDebtOverLimit, canGetSae=$canGetSae")
        if (netProperty > targetDebtExGuarantee && targetDebtExGuarantee > 0 && shortTermBlocked && longTermPropertyExcess && !canGetSae) {
            isBangsaeng = true; bangsaengReason = "재산초과"
        }
        // 경매 진행중 → 장기 불가. 단기도 불가면 방생 (압류는 회생으로 해제 가능하므로 별도 처리)
        // 대상채무 0이어도 담보채무 있으면 방생 (집경매 케이스)
        if (!isBangsaeng && hasAuction && shortTermBlocked && (targetDebt > 0 || parsedDamboTotal > 0) && !canGetSae) {
            isBangsaeng = true; bangsaengReason = "경매 진행중"
        }
        // 차량 조건 불가 (시세1000+/2대+/월납50+) + 처분해도 재산초과 → 장기 불가. 단기도 불가면 방생 (채무한도초과면 차량 무관)
        if (!isBangsaeng && longTermCarBlockedEffective && shortTermBlocked && targetDebt > 0 && !canGetSae && !canLongTermAfterCarSale && !longTermDebtOverLimit) {
            val carReason = buildList {
                if (carValue >= 1000) add("순가치${carValue}만")
                if (carCount >= 2) add("${carCount}대")
                if (carMonthlyPayment >= 50) add("월납${carMonthlyPayment}만")
            }.joinToString("/")
            isBangsaeng = true; bangsaengReason = "차량($carReason)"
        }
        // 단기+장기 모두 채무한도초과 → 방생
        if (!isBangsaeng && shortTermDebtOverLimit && longTermDebtOverLimit && targetDebt > 0) {
            isBangsaeng = true; bangsaengReason = "채무한도초과"
        }
        // ★ 단기 불가 (모든 사유) + 장기 채무한도초과 → 방생 (소득부족+채무한도초과 등)
        if (!isBangsaeng && shortTermBlocked && longTermDebtOverLimit && !canGetSae && targetDebt > 0) {
            isBangsaeng = true; bangsaengReason = "채무한도초과"
        }
        // 본인명의 부동산 3개 이상 → 진행 불가 (방생)
        if (!isBangsaeng && ownRealEstateCount >= 3) {
            isBangsaeng = true; bangsaengReason = "본인명의 부동산 ${ownRealEstateCount}개"
        }

        // shortTermTotal, longTermTotal, aggressiveTotal은 위에서 계산 완료
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
                val csStep3Years = Math.round(csTotalPayment.toDouble() / 40 / 12.0).toInt()
                if (csStep3Years > 10) {
                    csMonthly = Math.ceil(csTotalPayment.toDouble() / 120).toInt()
                }
            }
            val csRounded = csMonthly
            val csYears = if (csRounded > 0) Math.round(csTotalPayment.toDouble() / csRounded / 12.0).toInt().coerceIn(1, 10) else 10
            carSaleLongTermTotal = csRounded * csYears * 12
            // 차량 처분 시 공격 총액
            val csAggressiveMonthly = Math.ceil(csRounded * 2.0 / 3.0).toInt()
            var csAggressiveRounded = csAggressiveMonthly
            if (csAggressiveRounded < 40) csAggressiveRounded = csRounded
            var csAggressiveYears = Math.round(csTotalPayment.toDouble() / csAggressiveRounded / 12.0).toInt()
            if (csAggressiveYears > 10) {
                csAggressiveRounded = Math.ceil(csTotalPayment.toDouble() / 120).toInt()
                csAggressiveYears = 10
            }
            carSaleAggressiveTotal = csAggressiveRounded * csAggressiveYears * 12
            // 차량 처분 시 최종 년수/변제금 (percentage 적용)
            val csEffectiveMax = Math.min(csAggressiveYears, csYears + 4).coerceAtLeast(csYears)
            // 차량처분 최종 년수: 본체와 동일한 5단계 로직 적용
            val csSurplus = income - livingCostShinbok
            val csIsConservative = hasAuction || hasSeizure || hasGambling ||
                recentDebtRatio >= 50 || delinquentDays >= 90 || hasOwnRealEstate
            val csIsAggressive = income <= livingCostShinbok
            val csIsMajorAverage = majorCreditorRatio > 50
            val csIsTowardConservative = !csIsMajorAverage && (hasStock || hasCrypto ||
                (csSurplus > 0 && csSurplus < targetDebt * 0.03) ||
                (recentDebtRatio >= 30 && recentDebtRatio < 50))
            val csIsTowardAggressive = (csSurplus > targetDebt * 0.03) || isFreelancer ||
                (shortTermTotal > 0 && shortTermTotal < longTermTotal - 1000) || (householdForShinbok == 1 && parentCount == 0)
            carSaleFinalYear = when {
                csIsMajorAverage -> Math.round((csYears + csAggressiveYears) / 2.0).toInt()
                csIsConservative -> csYears
                csIsAggressive -> csAggressiveYears
                csIsTowardConservative -> Math.round((3.0 * csYears + csAggressiveYears) / 4.0).toInt()
                csIsTowardAggressive -> Math.round((csYears + 3.0 * csAggressiveYears) / 4.0).toInt()
                else -> Math.round((csYears + csAggressiveYears) / 2.0).toInt()
            }
            carSaleFinalYear = carSaleFinalYear.coerceIn(csYears, maxOf(csYears, csAggressiveYears))
            carSaleFinalMonthly = if (carSaleFinalYear > 0) csTotalPayment / (carSaleFinalYear * 12) else csRounded
            if (carSaleFinalMonthly < 40) carSaleFinalMonthly = 40
            Log.d("HWP_CALC", "차량처분 장기총액: 보수=${csRounded}만×${csYears}년=$carSaleLongTermTotal, 공격=${csAggressiveRounded}만×${csAggressiveYears}년=$carSaleAggressiveTotal, 최종=${carSaleFinalMonthly}만×${carSaleFinalYear}년")
        }

        // 진단에 사용할 장기 총액 (차량 처분 시에는 처분 기준)
        val effectiveLongTermTotal = if (shortTermCarSaleApplied || longTermCarBlockedEffective) carSaleLongTermTotal else longTermTotal
        val effectiveAggressiveTotal = if (shortTermCarSaleApplied || longTermCarBlockedEffective) carSaleAggressiveTotal else aggressiveTotal

        val propertyDamboDebt = parsedDamboTotal

        // ============= 장기 최종 년수/변제금 계산 (calcLongTermValues 결과 사용) =============
        var longTermAggressive = false
        val longTermBlockedByAuction = hasAuction
        val longTermBlockedBySeizure = hasSeizure
        val allDebtIsGuarantee = effectiveGuaranteeDebt > 0 && effectiveGuaranteeDebt >= targetDebt
        if (allDebtIsGuarantee) Log.d("HWP_CALC", "장기 불가: 모든 채권이 보증채무 (${effectiveGuaranteeDebt}만/${targetDebt}만)")
        // 장기재산초과: 지급보증 포함으로도 재산초과인 경우만 장기 차단 (지급보증 포함 시 재산초과 아니면 회워 가능)
        val propertyExcessWithGuarantee = netPropertyAfterExemption > targetDebt && targetDebt > 0
        val effectivePropertyExcess = if (delinquentDays < 90 && effectiveGuaranteeDebt > 0) propertyExcessWithGuarantee else longTermPropertyExcess
        val longTermFullyBlocked = ((effectivePropertyExcess || longTermCarBlockedEffective) && !canLongTermAfterCarSale) || longTermDebtOverLimit || longTermBlockedByAuction || longTermDebtInsufficient || allDebtIsGuarantee

        // 최종 결과는 공통 함수(calcLongTermValues)에서 이미 계산됨 → longTermFullyBlocked 시 무시
        var finalYear = if (!longTermFullyBlocked) ltResult.finalYear else 0
        var finalMonthly = if (!longTermFullyBlocked) ltResult.finalMonthly else 0
        if (!longTermFullyBlocked) {
            longTermUseMonths = ltResult.useMonths
            longTermDisplayMonths = ltResult.displayMonths
            longTermIsFullPayment = ltResult.isFullPayment
        }
        // 1인가구 + 소득 250만 이상 → 장기 기간 1년 단축 후 월 변제금 재계산 (부모 여부 무관)
        if (!longTermFullyBlocked && finalYear > 3 && income >= 250 && householdForShinbok == 1) {
            val oldYear = finalYear; val oldMonthly = finalMonthly
            finalYear -= 1
            var newMonths = finalYear * 12
            finalMonthly = if (newMonths > 0) (totalPayment + newMonths - 1) / newMonths else oldMonthly
            finalMonthly = finalMonthly / 5 * 5  // 5만 단위 내림 (대상채무 초과 방지)
            // 내림 후 40만보다 작으면 년수를 더 줄임
            while (finalYear > 1 && finalMonthly < 40) {
                finalYear -= 1
                newMonths = finalYear * 12
                finalMonthly = if (newMonths > 0) (totalPayment + newMonths - 1) / newMonths else finalMonthly
                finalMonthly = finalMonthly / 5 * 5
            }
            if (longTermUseMonths) longTermDisplayMonths = newMonths
            Log.d("HWP_CALC", "1인가구+소득250↑ → 장기 -1년: ${oldYear}년/${oldMonthly}만 → ${finalYear}년/${finalMonthly}만")
        }

        // 최종 장기 총액 (단순유리 비교용)
        var finalLongTermTotalMonths = if (longTermUseMonths && longTermDisplayMonths > 0) longTermDisplayMonths else finalYear * 12
        var finalLongTermTotal = if (finalMonthly > 0 && finalLongTermTotalMonths > 0) finalMonthly * finalLongTermTotalMonths else 0

        // 차량 처분시 단기 가능이면 단기 가능으로 판단
        val effectiveShortTermBlocked = shortTermBlocked && !shortTermCarSaleApplied
        val shortTermPropertyExcess = shortTermBlockReason.contains("재산초과")
        val isDanSunYuri = !effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && finalLongTermTotal - shortTermTotal > 1000
        val shortTermBlockedByDischarge = effectiveShortTermBlocked && dischargeWithin5Years && !dischargeEndsSameYear
        val lowIncome = income <= 100  // 소득 100만 이하 → 회생 불가
        val hoeBlocked = shortTermBlockedByDischarge || shortTermDebtOverLimit || hasHfcMortgage || lowIncome || spouseSecret  // 회(개인회생) 불가: 면책 or 채무한도초과 or 한국주택금융공사 or 소득100만이하 or 배우자모르게
        val isDischargeBanned = dischargeWithin5Years || hasHfcMortgage  // 면책5년이내 or 한국주택금융공사
        val hoeBlockedForSae = shortTermBlockedByDischarge || hasHfcMortgage  // 새새 진단용: 면책5년이내/한국주택금융공사만 회새 차단
        Log.d("HWP_CALC", "회불가 판단: hoeBlocked=$hoeBlocked (면책단기=$shortTermBlockedByDischarge, 채무한도=$shortTermDebtOverLimit, 한국주택=$hasHfcMortgage, 소득100이하=$lowIncome, 배우자모르게=$spouseSecret)")
        // 새새 연체 분기: 90일 이상 회새/새, 나머지 새새 (연체 없으면 회새 아님)
        val saeDiagnosis = when {
            actualDelinquentDays >= 90 || delinquentDays >= 90 -> if (hoeBlockedForSae) "새" else "회새"
            else -> "새새"
        }

        val diagnosisAfterCarSale = if (!canLongTermAfterCarSale) "" else
            diagByDelin(delinquentDays, isDischargeBanned, hoeBlocked, "워유워", "회워", "프유워", "프회워", "신유워", "신회워")



        val creditorCount = parsedCreditorCount
        Log.d("HWP_CALC", "채권사 수: $creditorCount (텍스트파싱)")
        Log.d("HWP_CALC", "진단플래그: 압류=$hasSeizure, 신복이력=$hasShinbokwiHistory, 진행중=$hasOngoingProcess, 유예=$canDeferment, 단기불가=$effectiveShortTermBlocked, 장기불가=$longTermFullyBlocked")
        val isShinbokSingleCreditor = creditorCount == 1 && (effectiveMajorCreditor.contains("신복") || effectiveMajorCreditor.contains("신복위"))
        if (creditorCount == 1 && !isShinbokSingleCreditor && !hasPdfFile) specialNotesList.add("채권사 1건")

        if (studentLoanRatio >= 50 && !longTermDebtOverLimit) {
            diagnosis = "단순 진행"
        } else if (shortTermDebt in 1..1500) {
            diagnosis = "방생"; diagnosisNote = "(소액)"
        } else if (income <= 100 && !hasStock && !hasCrypto && !hasGambling && originalNetProperty <= 1000 && (age >= 65 || hasDisability)) {
            diagnosis = "파산"
        } else if (hasKamco && !canGetSae) {
            // 한국자산관리공사(캠코) 보유 + 새새 불가 → 회워/워유워/방생
            diagnosis = when {
                longTermFullyBlocked -> "방생"
                !hoeBlockedForSae -> "회워"
                else -> "워유워"
            }
            Log.d("HWP_CALC", "캠코 진단: hoeBlockedForSae=$hoeBlockedForSae, longTermFullyBlocked=$longTermFullyBlocked → $diagnosis")
        } else if (creditorCount == 1 && !isShinbokSingleCreditor && !hasPdfFile && !effectiveShortTermBlocked) {
            diagnosis = "단순유리"; diagnosisNote = "(채권사 1건, 개인회생 안내)"
        } else if (nonAffiliatedOver20 && (!longTermDebtOverLimit || effectiveShortTermBlocked) && !canGetSae) {
            diagnosis = if (!effectiveShortTermBlocked) "단순유리" else "방생"
            // [장기] 라인에서 이미 "미협약 초과" 표시하므로 diagnosisNote 생략
        } else if (hasAuction && hasSeizure && !(effectiveShortTermBlocked && canGetSae)) {
            // 경매+압류 둘 다 → 단순유리 or 방생 (새새 가능하면 새새 분기로)
            diagnosis = if (!effectiveShortTermBlocked) "단순유리" else "방생"
            if (effectiveShortTermBlocked) diagnosisNote = "(경매/압류, 회생불가)"
        } else if (hasAuction && !(effectiveShortTermBlocked && canGetSae)) {
            // 경매만 → 단순유리 or 방생 (새새 가능하면 새새 분기로)
            diagnosis = if (!effectiveShortTermBlocked) "단순유리" else "방생"
            if (effectiveShortTermBlocked) diagnosisNote = "(경매, 회생불가)"
        } else if (hasSeizure && !canGetSae && !hoeBlocked) {
            // 압류 → 장기(개인회생)로 압류 해제 가능 → 회워, 장기불가 or 단기불가면 방생 (면책5년은 시간 해소되므로 방생 아님)
            // hoeBlocked이면 회생 불가이므로 일반 진단 분기로 (프유워 등)
            val reallyShortTermBlocked = effectiveShortTermBlocked && shortTermDebtOverLimit
            diagnosis = when {
                isDanSunYuri -> "단순유리"
                !reallyShortTermBlocked && !longTermFullyBlocked -> "회워"
                else -> "방생"
            }
        } else if (isBangsaeng) {
            if (canLongTermAfterCarSale && diagnosisAfterCarSale.isNotEmpty()) {
                diagnosis = diagnosisAfterCarSale
                diagnosisNote = "(차량 처분시 가능)"
            } else if (isRegistrySplit && !longTermPropertyExcess) {
                // 등본 분리시 재산초과 해소 → 장기 가능
                diagnosis = diagByDelin(delinquentDays, isDischargeBanned, hoeBlocked, "워유워", "회워", "프유워", "프회워", "신유워", "신회워")
            } else {
                diagnosis = "방생"
                if (bangsaengReason.isNotEmpty() && bangsaengReason != "재산초과") diagnosisNote = "($bangsaengReason)"
            }
        } else if (canApplySae && isBusinessOwner && (hasOngoingProcess && ongoingProcessName == "회" || isDismissed || hasWorkoutExpired)) {
            diagnosis = if (hoeBlockedForSae) "새" else "회새"
        } else if (canApplySae && saeTotalPayment > 0 && daebuCreditorCount < 2 && netProperty <= 0) {
            // 새새 가능 + 재산 없으면 무조건 새새 (대부 2건 이상이면 장기)
            diagnosis = saeDiagnosis
        } else if (canApplySae && saeTotalPayment > 0 && daebuCreditorCount < 2) {
            val longTermFinalTotal = finalMonthly * finalYear * 12
            if (isDanSunYuri && saeTotalPayment - shortTermTotal > 1000) {
                diagnosis = "단순유리"
            } else if (!effectiveShortTermBlocked && shortTermTotal > 0 && saeTotalPayment - shortTermTotal <= 1000) {
                // 새새 - 단기 <= 1000만이면 새새가 유리
                diagnosis = saeDiagnosis
            } else if (longTermFullyBlocked) {
                diagnosis = saeDiagnosis
            } else if (saeTotalPayment <= longTermFinalTotal) {
                // 새새 <= 장기이면 새새가 유리
                diagnosis = saeDiagnosis
            } else if (recentDebtRatio >= 30) {
                diagnosis = saeDiagnosis
            } else {
                diagnosis = if (!hoeBlocked && !hasYuwoCond) {
                    when {
                        delinquentDays >= 90 -> "회워"
                        delinquentDays >= 30 -> "프회워"
                        else -> "신회워"
                    }
                } else {
                    when {
                        delinquentDays >= 90 -> if (isDischargeBanned) "워유워" else "회워"
                        delinquentDays >= 30 -> "프유워"
                        else -> "신유워"
                    }
                }
            }
        } else if (hasShinbokwiHistory) {
            Log.d("HWP_CALC", "진단분기: hasShinbokwiHistory=true")
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked -> if (isDischargeBanned) "워유워" else "회워"
                else -> diagByDelin(delinquentDays, isDischargeBanned, hoeBlocked, "워유워", "회워", "프유워", "프회워", "신유워", "신회워")
            }
        } else if (hasOngoingProcess) {
            diagnosis = when {
                isBangsaeng -> "방생"
                isDanSunYuri -> "단순유리"
                !effectiveShortTermBlocked -> if (isDischargeBanned) "워유워" else "회워"
                else -> diagByDelin(delinquentDays, isDischargeBanned, hoeBlocked, "워유워", "회워", "프유워", "프회워", "신유워", "신회워")
            }
        } else if (!canDeferment) {
            // 유예불가 → 유예(유) 포함 불가, 회생만 가능
            diagnosis = when {
                isBangsaeng -> "방생"
                isDanSunYuri -> "단순유리"
                !effectiveShortTermBlocked -> if (hoeBlocked) "워" else "회워"
                else -> diagByDelin(delinquentDays, hoeBlocked, hoeBlocked, "워", "회워", "프워", "프회워", "신워", "신회워")
            }
        } else if (delinquentDays >= 90) {
            diagnosis = when {
                isBangsaeng -> "방생"
                hasWorkoutExpired && !longTermDebtOverLimit -> "단순워크"
                isDanSunYuri -> "단순유리"
                !effectiveShortTermBlocked && !longTermPropertyExcess -> if (isDischargeBanned) "워유워" else "회워"
                isDischargeBanned -> "워유워"
                else -> "회워"
            }
        } else if (delinquentDays >= 30) {
            diagnosis = when {
                isBangsaeng -> "방생"
                isDanSunYuri -> "단순유리"
                recentDebtRatio >= 30 && !effectiveShortTermBlocked -> if (hoeBlocked) "프유워" else "프회워"
                targetDebt <= 4000 && !effectiveShortTermBlocked -> "프유워"
                !effectiveShortTermBlocked -> if (hoeBlocked) "프유워" else "프회워"
                else -> if (hoeBlocked || shortTermPropertyExcess) "프유워" else "프회워"
            }
        } else {
            diagnosis = when {
                isBangsaeng -> "방생"
                isDanSunYuri -> "단순유리"
                recentDebtRatio >= 30 && !effectiveShortTermBlocked -> if (hoeBlocked) "신유워" else "신회워"
                targetDebt <= 4000 && !effectiveShortTermBlocked -> "신유워"
                !effectiveShortTermBlocked -> if (hoeBlocked) "신유워" else "신회워"
                else -> if (hoeBlocked || shortTermPropertyExcess) "신유워" else "신회워"
            }
        }

        Log.d("HWP_CALC", "초기진단: $diagnosis (targetDebt=$targetDebt, shortTermTotal=$shortTermTotal, 장기총액=$finalLongTermTotal, 차이=${finalLongTermTotal - shortTermTotal})")

        // 단순유리일 때 장기를 공격 진단(aggressive)으로 변환
        // 공격 장기 월변제금이 단기 월변제금의 65%보다 적으면 장기로 진단 변경
        if (diagnosis == "단순유리" && !longTermFullyBlocked && roundedAggressiveMonthly > 0 && aggressiveYears > 0) {
            val aggressiveUnderThreshold = shortTermMonthly > 0 && roundedAggressiveMonthly * 100 < shortTermMonthly * 65
            Log.d("HWP_CALC", "단순유리 공격 변환: 공격월=${roundedAggressiveMonthly}만/${aggressiveYears}년, 단기월=${shortTermMonthly}만, 65%=${String.format("%.1f", shortTermMonthly * 0.65)}만, 장기전환=$aggressiveUnderThreshold")
            if (aggressiveUnderThreshold) {
                val useYu = isDischargeBanned || hoeBlocked
                diagnosis = when {
                    delinquentDays >= 90 -> if (isDischargeBanned) "워유워" else "회워"
                    delinquentDays >= 30 -> if (useYu) "프유워" else "프회워"
                    else -> if (useYu) "신유워" else "신회워"
                }
                Log.d("HWP_CALC", "단순유리 → $diagnosis (공격 장기 ${roundedAggressiveMonthly}만 < 단기 ${shortTermMonthly}만 × 65%)")
            }
            // 장기 표시를 공격값으로 변환 (단순유리 유지 시에도 적용)
            finalMonthly = roundedAggressiveMonthly
            finalYear = aggressiveYears
            longTermUseMonths = false
            longTermDisplayMonths = 0
            finalLongTermTotalMonths = finalYear * 12
            finalLongTermTotal = finalMonthly * finalLongTermTotalMonths
        }

        // 단기+장기 둘 다 불가 + 새출발 실질 불가(변제액 0) → 방생 강제 (워유워 등 다른 진단 나와도 교정)
        // canGetSae는 2차 경로(isDismissed/hasWorkoutExpired)로도 true되므로 saeTotalPayment로 실질 판단
        val saeActuallyUsable = canApplySae && saeTotalPayment > 0
        if (effectiveShortTermBlocked && longTermFullyBlocked && !saeActuallyUsable
            && diagnosis != "방생" && diagnosis != "파산" && !diagnosis.startsWith("단순") && !diagnosis.startsWith("새") && diagnosis != "회새") {
            Log.d("HWP_CALC", "단기+장기 불가 + 새출발 실질 불가 → 방생 강제 (기존: $diagnosis, canApplySae=$canApplySae, saeTotalPayment=$saeTotalPayment)")
            diagnosis = "방생"
            if (bangsaengReason.isNotEmpty() && bangsaengReason != "재산초과") diagnosisNote = "($bangsaengReason)"
        }



        // ============= 10개월 내 면책 5년 해소 시 회워 계열로 변경 =============
        // 면책 5년 이내로 단기불가인데, 면책+5년이 10개월 이내에 도래하면 회워 계열로
        var dischargeEndSoon = false
        if (dischargeWithin5Years && dischargeYear > 0) {
            val tenMonthsLater = Calendar.getInstance().apply { add(Calendar.MONTH, 10) }
            val dischargeEndCal = Calendar.getInstance().apply {
                set(dischargeYear + 5, if (dischargeMonth > 0) dischargeMonth - 1 else 0, 1)
            }
            dischargeEndSoon = !dischargeEndCal.after(tenMonthsLater)
            if (dischargeEndSoon) {
                // 10개월 이내 해소 → 회 계열로 변경 (신회워, 프회워, 워회워)
                val afterDateYear = (dischargeYear + 5) % 100
                val afterDateMonth = if (dischargeMonth > 0) dischargeMonth else 1
                val afterDate = "${afterDateYear}년 ${afterDateMonth}월"
                specialNotesList.add("면책 5년 해소 ${afterDate} (10개월 이내)")
                // 배우자 모르게이면 회생접수 어려워 회 변환 제외 → 유 유지
                if (!hasOngoingProcess && !shortTermDebtOverLimit && !spouseSecret && (diagnosis == "신유워" || diagnosis == "프유워" || diagnosis == "워유워")) {
                    diagnosis = when {
                        delinquentDays >= 90 -> "워회워"
                        delinquentDays >= 30 -> "프회워"
                        else -> "신회워"
                    }
                }
                // 단순유리인데 면책으로 단기 불가 → 유회 계열로 변경
                if (diagnosis.startsWith("단순")) {
                    diagnosis = when {
                        delinquentDays >= 90 -> "워유회"
                        delinquentDays >= 30 -> "프유회"
                        else -> "신유회"
                    }
                }
                // 면책 해소 전: 현재 불가 + 단기 금액 + 해소시점 표시
                if (shortTermMonthly > 0 && shortTermMonths > 0) {
                    shortTermResult = "현재 불가, ${shortTermMonthly}만 / ${shortTermMonths}개월납, ${afterDate} 이후 가능"
                    Log.d("HWP_CALC", "면책 해소 전 단기 표시: $shortTermResult")
                }
            } else {
                // 10개월 이내 해소 안됨 → 유 계열 유지 (신유워, 프유워, 워유워)
                if (diagnosis == "신유워" || diagnosis == "프유워" || diagnosis == "신회워" || diagnosis == "프회워" || diagnosis == "워회워") {
                    diagnosis = when {
                        delinquentDays >= 90 -> "워유워"
                        delinquentDays >= 30 -> "프유워"
                        else -> "신유워"
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

        // ============= 진행 중 + 면책기간 3/6개월 이내 =============
        if (hasOngoingProcess && dischargeWithin5Years && dischargeYear > 0) {
            val dischargeEndCal = Calendar.getInstance().apply {
                set(dischargeYear + 5, if (dischargeMonth > 0) dischargeMonth - 1 else 0, 1)
            }
            val threeMonthsLater = Calendar.getInstance().apply { add(Calendar.MONTH, 3) }
            val sixMonthsLater = Calendar.getInstance().apply { add(Calendar.MONTH, 6) }
            val afterDate2 = "${(dischargeYear + 5) % 100}년 ${if (dischargeMonth > 0) dischargeMonth else 1}월"
            when {
                !dischargeEndCal.after(threeMonthsLater) -> {
                    // 3개월 이내 + 단순 유리이면 diagnosisNote
                    val longTermFinalTotal = finalMonthly * finalYear * 12
                    val saeTotal = if (canApplySae && saeTotalPayment > 0) saeTotalPayment else Int.MAX_VALUE
                    if (shortTermTotal > 0 && shortTermTotal + 1000 < minOf(longTermFinalTotal, saeTotal)) {
                        diagnosisNote = "${afterDate2} 이후 단순회생 유리"
                        Log.d("HWP_CALC", "진행중+면책3개월이내: 단기총액=${shortTermTotal}+1000 < min(장기${longTermFinalTotal},새새${if (saeTotal == Int.MAX_VALUE) "없음" else "${saeTotal}"}) → $diagnosisNote")
                    }
                }
                !dischargeEndCal.after(sixMonthsLater) -> {
                    // 3~6개월 이내 → (진행중제도유)유회
                    val prefix = if (ongoingProcessName.isNotEmpty()) ongoingProcessName else when {
                        delinquentDays >= 90 -> "워"
                        delinquentDays >= 30 -> "프"
                        else -> "신"
                    }
                    diagnosis = if (canDeferment) "(${prefix})유회" else "(${prefix}유)회"
                    diagnosisNote = "${afterDate2} 이후 단순 유리"
                    Log.d("HWP_CALC", "진행중+면책6개월이내: 연체${delinquentDays}일 → $diagnosis, $diagnosisNote")
                }
            }
        }

        // 6개월 이내 비율 30% 미만 가능 날짜 계산 (방생/단순유리/단순진행 등 확정 진단 시 건너뜀)
        // 차량대출 제외 시 6개월 비율 30% 이하 여부
        val recentDebtExCarMan = recentDebtMan - recentCarLoanMan
        val recentRatioExCar = if (totalDebtForRatio > 0 && recentDebtExCarMan > 0) recentDebtExCarMan.toDouble() / totalDebtForRatio * 100 else 0.0
        val canAfterCarDisposal = recentDebtRatio >= 30 && recentCarLoanMan > 0 && recentRatioExCar < 30
        if (canAfterCarDisposal) {
            Log.d("HWP_CALC", "차량처분 시 6개월 30%미만: ${recentDebtMan}만-차량${recentCarLoanMan}만=${recentDebtExCarMan}만/${totalDebtForRatio}만=${String.format("%.1f", recentRatioExCar)}%")
            val carNames = carNameList.filter { !it.isNullOrEmpty() }
            val carNameStr = if (carNames.isNotEmpty()) carNames.joinToString("/") else "차량"
            specialNotesList.add("$carNameStr 처분시 바로 가능")
        }
        val isSaeDiagnosis = diagnosis == "새새" || diagnosis == "새" || diagnosis == "회새"
        if (isSaeDiagnosis) specialNotesList.removeAll { it.contains("차량처분 필요") }
        val skipRecentDateCalc = diagnosis == "방생" || diagnosis.startsWith("단순")
        // 6개월 30% 이상 → 가능일자 표기 (단기 가능 여부 무관하게 날짜 계산)
        if (recentDebtRatio >= 30 && recentDebtEntries.isNotEmpty() && targetDebt > 0 && !skipRecentDateCalc && (isSaeDiagnosis || !(effectiveShortTermBlocked && longTermFullyBlocked))) {
            recentDebtEntries.sortBy { it.first.timeInMillis } // 오래된 순
            var remainingMan = recentDebtMan

            for ((date, amountChon) in recentDebtEntries) {
                val amountMan = (amountChon + 5) / 10
                remainingMan -= amountMan
                if (remainingMan < 0) remainingMan = 0
                val newRatio = remainingMan.toDouble() / totalDebtForRatio * 100
                if (newRatio < 30) {
                    val thresholdCal = date.clone() as Calendar
                    thresholdCal.add(Calendar.MONTH, 6)
                    thresholdCal.add(Calendar.DATE, 1)
                    val possibleDate = formatCalDate(thresholdCal)
                    // 단기+1000 ≥ 장기 → 장기가 더 작거나 비슷 → 회 대신 워 (워크아웃 추천)
                    val longBetter = shortTermTotal > 0 && finalLongTermTotal > 0 && shortTermTotal + 1000 >= finalLongTermTotal
                    val afterDiag = if (isSaeDiagnosis) {
                        diagnosis  // 새출발 진단명 유지 (새새/회새/새)
                    } else if (hoeBlocked || effectiveShortTermBlocked) when {
                        delinquentDays >= 90 -> if (dischargeEndSoon) (if (longBetter) "워유워" else "워유회") else if (isDischargeBanned) "워유워" else "회워"
                        delinquentDays >= 30 -> if (dischargeEndSoon) (if (longBetter) "프유워" else "프유회") else "프유워"
                        else -> if (dischargeEndSoon) (if (longBetter) "신유워" else "신유회") else "신유워"
                    } else when {
                        delinquentDays >= 90 -> if (dischargeEndSoon) (if (longBetter) "워유워" else "워유회") else "회워"
                        delinquentDays >= 30 -> if (dischargeEndSoon) (if (longBetter) "프유워" else "프유회") else "프회워"
                        else -> if (dischargeEndSoon) (if (longBetter) "신유워" else "신유회") else "신회워"
                    }
                    val canDansun = !isSaeDiagnosis && isDanSunYuri
                    val immediatePrefix = if (!isSaeDiagnosis && !longTermFullyBlocked && finalYear > 0 && !dischargeWithin5Years && !hasHfcMortgage && !isSameYearMonthAsToday(thresholdCal)) "회워 바로 가능, " else ""
                    val immediateNote = if (canDansun && !isIncomeEstimated && !dischargeWithin5Years) ", 단순 바로 가능" else ""
                    val cdNote = if (canAfterCarDisposal) ", 차량 처분 후 바로 가능" else ""
                    fun stripLabel(s: String) = s.replace(",", "").replace("바로 가능", "").replace("구직 이후 가능", "").trim()
                    if (afterDiag.isNotEmpty() && (stripLabel(immediateNote) == afterDiag || stripLabel(immediatePrefix) == afterDiag)) {
                        diagnosis = afterDiag
                    } else {
                        diagnosis = "$immediatePrefix$afterDiag $possibleDate 이후 가능$immediateNote$cdNote"
                    }
                    diagnosisNote = ""
                    Log.d("HWP_CALC", "6개월 30%미만 가능일: $possibleDate (남은 ${remainingMan}만/${totalDebtForRatio}만=${String.format("%.1f", newRatio)}%)")
                    break
                }
            }
        }

        val practicalShortTermBlocked = spouseSecret || isIncomeEstimated

        // 단순유리 + 장기 가능 → 회워 바로 가능 + 연체별 날짜 추가
        // 단, 단기총액+1000 < 장기총액이면 단순유리 유지 (단기가 확실히 유리)
        // ★ 배우자 모르게, 소득 예정 등 단기 불가 조건 시 단기 우위 비교 무시 → 장기로 진행
        val singleCreditorDanSun = creditorCount == 1 && !isShinbokSingleCreditor && !hasPdfFile
        if (diagnosis.startsWith("단순") && !longTermFullyBlocked && (finalYear > 0 || practicalShortTermBlocked) && !singleCreditorDanSun
            && !(diagnosis == "단순유리" && !practicalShortTermBlocked && shortTermTotal > 0 && finalLongTermTotal - shortTermTotal > 1000)) {
            // 면책 5년 이내 또는 배우자 모르게 등 실질 단기 불가 → 장기 진단 기반
            // 단, 대상채무 4000만 이하면 회생도 가능 (유→회), hoeBlocked는 중간 회 불가
            // 면책5년이내/한국주택금융공사는 회생접수 자체 불가 → 회워도 불가
            val useYu = isDischargeBanned || hoeBlocked
            diagnosis = if (dischargeWithin5Years || practicalShortTermBlocked) {
                when {
                    delinquentDays >= 90 -> if (isDischargeBanned) "워유워" else "회워"
                    delinquentDays >= 30 -> if (useYu) "프유워" else "프회워"
                    else -> if (useYu) "신유워" else "신회워"
                }
            } else when {
                delinquentDays >= 90 -> "회워"
                delinquentDays >= 30 -> if (hoeBlocked) "프유워" else "프회워"
                targetDebt <= 4000 -> "신유워"
                else -> if (hoeBlocked) "신유워" else "신회워"
            }
            // 6개월 30% 이상이면 신유회/프유회/워유회 날짜 추가 (회생 가능할 때만)
            if (recentDebtRatio >= 30 && recentDebtEntries.isNotEmpty() && targetDebt > 0 && !useYu) {
                val longDiag = when {
                    delinquentDays >= 90 -> "워유회"
                    delinquentDays >= 30 -> "프유회"
                    else -> "신유회"
                }
                findThresholdDate(recentDebtEntries, recentDebtMan, totalDebtForRatio)?.let { pd ->
                    if (isDanSunYuri && practicalShortTermBlocked) {
                        // 단순유리 조건인데 소득 예정/배우자 모르게 → 신유회만 표시
                        diagnosis = "$longDiag $pd 이후 가능"
                    } else {
                        diagnosis += ", $longDiag $pd 이후 가능"
                    }
                }
            }
            // 6개월 30% 이상 + 회생 불가(useYu) → "X월 이후 신유워 가능" 형태로 표기
            else if (recentDebtRatio >= 30 && recentDebtEntries.isNotEmpty() && targetDebt > 0 && useYu) {
                findThresholdDate(recentDebtEntries, recentDebtMan, totalDebtForRatio)?.let { pd ->
                    diagnosis = "$diagnosis $pd 이후 가능"
                }
            }
        }

        // 6개월 30% 이상 → 진단 맨 앞 신/프/워 불가 (신속/프리/워크아웃 불가)
        // 단, 미래 날짜가 포함된 진단("이후 가능")은 이미 미래 시점 기준이므로 제거하지 않음
        if (recentDebtRatio >= 30 && !diagnosis.contains("이후 가능") && (diagnosis.startsWith("신") || diagnosis.startsWith("프") || diagnosis.startsWith("워"))) {
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

        // 새새 + 6개월 30% 이상 대기 중 → 차량 처분 시 30% 이하이면 "차량 처분 후 바로 가능", 아니면 "회새 가능"
        if ((diagnosis == "새새" || diagnosis == "새" || diagnosis == "회새" || diagnosis.startsWith("새새 ") || diagnosis.startsWith("새 ") || diagnosis.startsWith("회새 ")) && diagnosis.contains("이후 가능")) {
            if (needsCarDisposal && canAfterCarDisposal && !diagnosis.contains("차량 처분")) {
                diagnosis += ", 차량 처분 후 바로 가능"
            } else if (!needsCarDisposal && !diagnosis.contains("회새") && !hoeBlockedForSae) {
                diagnosis += ", 회새 가능"
            }
        }

        // 차량 처분 필요 + 6개월 30% 초과 사유가 차량 담보대출 → 차량 처분 후 바로 가능 + 차량 처분 안되면 회새 가능
        if (canAfterCarDisposal && canApplySae && !hoeBlocked && !diagnosis.contains("회새")) {
            if (!diagnosis.contains("차량 처분")) diagnosis += ", 차량 처분 후 바로 가능"
            diagnosis += ", 차량 처분 안되면 회새 가능"
        }

        // UI 업데이트
        binding.name.text = "[이름] $name"
        val incomeSuffix = if (isIncomeEstimated) " (예정)" else ""
        val dispIncome = if (noSocialInsurance && income > 0) Math.round(income / 0.8).toInt() else income
        binding.card.text = "[소득] ${dispIncome}만${incomeSuffix}"
        val studentLoanApplied = studentLoanMan > 0 && parsedIncludesStudentLoan
        val datSuffix = buildString {
            if (studentLoanApplied) append(" (학자금 제외)")
        }
        val datBase = if (onlyCarDambo) targetDebt else targetDebtBeforeDisposal
        val datDisplayAmount = if (studentLoanApplied) maxOf(datBase - studentLoanMan, 0) else datBase
        val carDisposalSuffix = if (onlyCarDambo) " (차량 처분 시)" else ""
        binding.dat.text = "[대상] ${formatToEok(datDisplayAmount)}$datSuffix$carDisposalSuffix"
        val registrySplitSuffix = if (isRegistrySplit) " (등본 분리 필요)" else ""
        val displayProperty = originalNetProperty + spouseCarSiseTotal
        binding.money.text = "[재산] ${formatToEok(displayProperty)}$registrySplitSuffix"

        // 납부회수는 finalDiagnosis 이후에 처리

        val specialNotesText = StringBuilder()
        if (specialNotesList.isNotEmpty()) {
            specialNotesText.append("[특이] ")
            specialNotesList.take(20).forEachIndexed { index, note ->
                if (index > 0) specialNotesText.append("\n")
                specialNotesText.append(note)
            }
        }
        binding.use.text = specialNotesText.toString()
        val spouseSecretSuffix = if (spouseSecret) " (배우자 모르게)" else ""
        val studentLoanShortSuffix = if (studentLoanApplied) " (학자금 포함)" else ""
        binding.test1.text = "[단기] $shortTermResult$spouseSecretSuffix$studentLoanShortSuffix"

        // 장기 전용 진단 라벨 (최종 진단과 별개)
        val longTermDiagLabel = if (longTermFullyBlocked || longTermDebtOverLimit) "" else {
            var label = if (!canDeferment) {
                // 유예불가 → 유예(유) 포함 불가
                if (hoeBlocked) {
                    // 회생도 불가 → 진단명과 동일하게 표시
                    diagnosis
                } else {
                    when {
                        delinquentDays >= 90 -> "회워"
                        delinquentDays >= 30 -> "프회워"
                        else -> "신회워"
                    }
                }
            } else if (!hoeBlocked && !hasYuwoCond) {
                when {
                    delinquentDays >= 90 -> "회워"
                    delinquentDays >= 30 -> "프회워"
                    else -> "신회워"
                }
            } else {
                // 단기+1000 ≥ 장기 → 장기가 비슷하거나 더 작음 → 회 대신 워 (워크아웃 추천)
                val longBetter = shortTermTotal > 0 && finalLongTermTotal > 0 && shortTermTotal + 1000 >= finalLongTermTotal
                when {
                    delinquentDays >= 90 -> if (dischargeEndSoon) (if (longBetter) "워유워" else "워유회") else if (isDischargeBanned) "워유워" else "회워"
                    delinquentDays >= 30 -> if (dischargeEndSoon) (if (longBetter) "프유워" else "프유회") else "프유워"
                    else -> if (dischargeEndSoon) (if (longBetter) "신유워" else "신유회") else "신유워"
                }
            }
            // 중간 회→유 변환: 사업자, 단순재산초과, 한국주택금융공사 집담보, 동일 채권사가 담보+신용 보유, 또는 대상채무 4000만 이하
            if (canDeferment) {
                val hasSameCreditorDamboAndCredit = parsedDamboCreditorNames.any { it in parsedCreditorMap }
                Log.d("HWP_CALC", "회→유 변환 체크: label=$label, 사업자=$isBusinessOwner, 재산초과=$shortTermPropertyExcess(reason=$shortTermBlockReason), 동일채권=$hasSameCreditorDamboAndCredit, 주택금융=$hasHfcMortgage, 소액=${targetDebt <= 4000}")
                if (isBusinessOwner || shortTermPropertyExcess || hasSameCreditorDamboAndCredit || hasHfcMortgage || targetDebt <= 4000) {
                    label = label.replace("신회워", "신유워").replace("프회워", "프유워")
                }
            }
            label
        }

        val longTermText = StringBuilder()
        // 진단 라벨이 "회"로 끝나면 (예: 프유회, 신유회, 워유회) 장기 표기를 단기 기준으로 (단기monthly / 단기개월/12+1년)
        val labelEndsWithHoe = longTermDiagLabel.endsWith("회")
        val overrideMonthly = if (labelEndsWithHoe && shortTermMonthly > 0) shortTermMonthly else 0
        val overrideYear = if (labelEndsWithHoe && shortTermMonths > 0) shortTermMonths / 12 + 1 else 0
        // 장기 불가 사유 수집
        val longTermBlockReasons = mutableListOf<String>()
        if (longTermDebtOverLimit) {
            val ld = mutableListOf<String>()
            if (totalUnsecuredDebt > 50000) ld.add("무담보${formatToEok(totalUnsecuredDebt)}")
            if (totalSecuredDebt > 100000) ld.add("담보${formatToEok(totalSecuredDebt)}")
            longTermBlockReasons.add("채무한도초과(${ld.joinToString("/")})")
        }
        if (nonAffiliatedOver20) longTermBlockReasons.add("미협약 초과")
        if (longTermPropertyExcess || propertyExcessWithGuarantee) longTermBlockReasons.add("재산초과")
        if (longTermCarBlockedEffective && !longTermPropertyExcess && !propertyExcessWithGuarantee) longTermBlockReasons.add("차량조건불가")
        if (allDebtIsGuarantee) longTermBlockReasons.add("지급보증채무")
        if (hasAuction) longTermBlockReasons.add("경매 진행중")
        if (isBangsaeng && bangsaengReason.isNotEmpty() && longTermBlockReasons.none { it.contains(bangsaengReason) }) longTermBlockReasons.add(bangsaengReason)
        if (longTermDebtOverLimit) {
            longTermText.append("[장기] 장기 불가 (${longTermBlockReasons.joinToString(", ")})")
        } else if (isBangsaeng) {
            longTermText.append("[장기] 장기 불가 (${longTermBlockReasons.joinToString(", ").ifEmpty { bangsaengReason }})")
        } else if (nonAffiliatedOver20) {
            longTermText.append("[장기] ${longTermBlockReasons.joinToString(", ")}")
        } else if (hasAuction) {
            longTermText.append("[장기] 장기 불가 (${longTermBlockReasons.joinToString(", ")})")
        } else if (hasSeizure) {
            if (finalYear > 0 && finalMonthly > 0) {
                val studentLoanLongSuffix = if (studentLoanApplied) " (학자금 제외)" else ""
                val displayMonthly = if (overrideMonthly > 0) overrideMonthly else finalMonthly
                val periodStr = if (overrideYear > 0) "${overrideYear}년납" else if (longTermUseMonths) "${longTermDisplayMonths}개월납" else "${finalYear}년납"
                val diagSuffix = if (longTermDiagLabel.isNotEmpty()) " / $longTermDiagLabel" else ""
                longTermText.append("[장기] ${displayMonthly}만 / $periodStr$studentLoanLongSuffix$diagSuffix")
            } else {
                longTermText.append("[장기] 장기 불가 (압류 진행중)")
            }
        } else if (longTermDebtInsufficient) {
            longTermText.append("[장기] 장기 불가 (채무 부족)")
        } else if (longTermFullyBlocked) {
            val blockedReason = if (allDebtIsGuarantee) "전액 지급보증"
            else "재산초과"
            longTermText.append("[장기] 장기 불가 ($blockedReason)")
        } else if (finalYear > 0 && finalMonthly > 0) {
            val studentLoanLongSuffix = if (studentLoanApplied) " (학자금 제외)" else ""
            val displayMonthly = if (overrideMonthly > 0) overrideMonthly else finalMonthly
            val periodStr = if (overrideYear > 0) "${overrideYear}년납" else if (longTermUseMonths) "${longTermDisplayMonths}개월납" else "${finalYear}년납"
            val diagSuffix = if (longTermDiagLabel.isNotEmpty()) " / $longTermDiagLabel" else ""
            longTermText.append("[장기] ${displayMonthly}만 / $periodStr$studentLoanLongSuffix$diagSuffix")
        } else {
            val studentLoanLongSuffix = if (studentLoanApplied) " (학자금 제외)" else ""
            val displayMonthly = if (overrideMonthly > 0) overrideMonthly else roundedLongTermMonthly
            val periodStr = if (overrideYear > 0) "${overrideYear}년납" else if (longTermUseMonths) "${longTermDisplayMonths}개월납" else "${longTermYears}년납"
            val diagSuffix = if (longTermDiagLabel.isNotEmpty()) " / $longTermDiagLabel" else ""
            longTermText.append("[장기] ${displayMonthly}만 / $periodStr$studentLoanLongSuffix$diagSuffix")
        }
        if (canApplySae && saeTotalPayment > 0) {
            val saeBugyeol = netProperty - targetDebt >= 10000 || longTermPropertyExcess || propertyExcessWithGuarantee
            longTermText.append("\n[새새] ${saeMonthly}만 / ${saeYears}년납${if (saeBugyeol) " (부결고지)" else ""}")
        } else if (hasBusinessHistory && isBusinessOwner && saeDebtOverLimit) {
            longTermText.append("\n[새새] 새새 불가(채무한도초과 담보${formatToEok(totalSecuredDebt)})")
        } else if (hasBusinessHistory && isBusinessOwner && saePropertyExcess) {
            longTermText.append("\n[새새] 새새 불가(재산초과)")
        }
        // binding.test2는 finalDiagnosis 확정 후 설정

        // 단순유리 + 새새 가능 + 새새-단기 <= 1000만 → 새새가 유리
        if (diagnosis == "단순유리" && canApplySae && saeTotalPayment > 0 && shortTermTotal > 0 && saeTotalPayment - shortTermTotal <= 1000) {
            diagnosis = saeDiagnosis
            Log.d("HWP_CALC", "단순유리→${saeDiagnosis}: 새새총액=${saeTotalPayment}만 - 단기총액=${shortTermTotal}만 = ${saeTotalPayment - shortTermTotal}만 <= 1000만")
        }

        // 본인명의 집 + 새새 가능 + 회생불가(면책/한국주택) → 새새 (집경매 위험만으로는 회 제거 안함)
        val longTermTotalForSaeCompare = finalMonthly * finalYear * 12
        if (hasOwnRealEstate && canApplySae && saeTotalPayment > 0 && diagnosis.contains("회") && saeTotalPayment <= longTermTotalForSaeCompare && hoeBlockedForSae) {
            diagnosis = saeDiagnosis
            Log.d("HWP_CALC", "본인명의 집 + 회생불가 + 새새 가능 → ${saeDiagnosis} (새새${saeTotalPayment}만 <= 장기${longTermTotalForSaeCompare}만)")
        }

        // 새새 변환 후 6개월 30% 날짜가 빠진 경우 추가
        if ((diagnosis == "새새" || diagnosis == "새" || diagnosis == "회새") && recentDebtRatio >= 30 && recentDebtEntries.isNotEmpty() && targetDebt > 0) {
            findThresholdDate(recentDebtEntries, recentDebtMan, totalDebtForRatio)?.let { pd ->
                diagnosis = "$diagnosis $pd 이후 가능"
                Log.d("HWP_CALC", "새새 변환 후 6개월 날짜 추가: $pd")
            }
        }

        // 단순유리 + 장기 가능 → 연체에 따라 신유회/프유회/워유회 (조건부)
        // 단, 단기 총액이 장기 총액보다 낮으면 단순유리 유지 (단기가 더 유리)
        else if (diagnosis == "단순유리" && !longTermFullyBlocked && !nonAffiliatedOver20 && finalYear > 0 && !singleCreditorDanSun
            && (shortTermTotal <= 0 || finalMonthly * finalYear * 12 - shortTermTotal <= 1000)) {
            // 회(회생) 불가 조건: 배우자모르게, 집경매, 재산초과, 면책5년(10개월 이내 해소 제외)
            val dischargeEndsWithin10Months = dischargeWithin5Years && dischargeYear > 0 && run {
                val tenMonthsLater = Calendar.getInstance().apply { add(Calendar.MONTH, 10) }
                val dischargeEndCal = Calendar.getInstance().apply {
                    set(dischargeYear + 5, if (dischargeMonth > 0) dischargeMonth - 1 else 0, 1)
                }
                !dischargeEndCal.after(tenMonthsLater)
            }
            val canAddHoe = !spouseSecret && !isOwnPropertyAuction && !longTermPropertyExcess &&
                    (!dischargeWithin5Years || dischargeEndsWithin10Months) && !shortTermDebtOverLimit && !(hasOwnRealEstate && originalNetProperty <= targetDebt)
            val longTermCheaper = finalMonthly * finalYear * 12 < shortTermTotal + 1000  // 장기 총액 < 단기+1000만이면 장기 유리
            val suffix = if (canAddHoe && !longTermCheaper) "회" else "워"
            diagnosis = when {
                delinquentDays >= 90 -> "워유$suffix"
                delinquentDays >= 30 -> "프유$suffix"
                else -> "신유$suffix"
            }
            // 단순유리→신유워 등 변환 후 6개월 30% 날짜 계산
            if (recentDebtRatio >= 30 && recentDebtEntries.isNotEmpty() && targetDebt > 0) {
                findThresholdDate(recentDebtEntries, recentDebtMan, totalDebtForRatio)?.let { pd ->
                    if (!(suffix == "워" && !dischargeWithin5Years)) diagnosis = "$diagnosis $pd 이후 가능"
                    Log.d("HWP_CALC", "단순유리 변환 후 6개월 날짜: $pd")
                }
            }
            // 6개월 30% 해당 안되더라도 → 진단 유지 (신유워/프유워 등 그대로)
        }

        // 미협약 초과 + 단순유리 + 6개월 30% → 날짜만 추가 (신유회 변환 없이)
        if (diagnosis == "단순유리" && nonAffiliatedOver20 && recentDebtRatio >= 30 && recentDebtEntries.isNotEmpty() && targetDebt > 0) {
            findThresholdDate(recentDebtEntries, recentDebtMan, totalDebtForRatio)?.let { pd ->
                diagnosis = "단순유리"
                Log.d("HWP_CALC", "미협약 초과 + 단순유리 (6개월 날짜 생략: $pd)")
            }
        }

        // 다른 단계 진행중이면 진단 앞에 표시 (방생은 제외, 이미 (제도)유회 형태이면 건너뜀)
        if (hasOngoingProcess && ongoingProcessName.isNotEmpty() && diagnosis != "방생"
            && !diagnosis.startsWith("(${ongoingProcessName})") && !diagnosis.startsWith("(${ongoingProcessName}유)")) {
            // 진행중 제도 prefix가 있으면 진단 앞의 연체 route(신/프/워) 제거 (중복 방지)
            var baseDiag = diagnosis
            if (baseDiag.startsWith("신") || baseDiag.startsWith("프") || baseDiag.startsWith("워")) {
                baseDiag = baseDiag.substring(1)
            }
            val isSaeBaseDiag = baseDiag.startsWith("새새") || baseDiag.startsWith("회새") || baseDiag.startsWith("새")
            if (isSaeBaseDiag && ongoingProcessName != "새") {
                // 새새/회새/새 진단은 진행중 제도 prefix 없이 그대로 유지 (단, 진행중이 새출발이면 (새) prefix 추가)
                diagnosis = baseDiag
            } else if (isSaeBaseDiag && ongoingProcessName == "새") {
                // 새출발 진행중 + 새 계열 진단 → (새)새, (새)회새, (새)새새
                diagnosis = "(새)$baseDiag"
            } else if (aiDefermentMonths > 0) {
                // 유예 중복 제거: 워유워→워, 유워→워
                if (baseDiag.startsWith("워유워")) baseDiag = baseDiag.removePrefix("워유")
                else if (baseDiag.startsWith("유")) baseDiag = baseDiag.removePrefix("유")
                if (aiDefermentMonths >= 12) {
                    // 유예기간 12개월 이상: (신유)워, (프유)워, (워유)워
                    diagnosis = "(${ongoingProcessName}유)$baseDiag"
                } else {
                    // 유예기간 12개월 미만: (신)유워, (프)유워, (워)유워
                    diagnosis = "($ongoingProcessName)유$baseDiag"
                }
            } else {
                diagnosis = "($ongoingProcessName)$baseDiag"
            }
        }

        // 진행중 + 유예 + 단기총변제금+1000 < 장기총변제금 → 워→회 (단기가 확실히 유리하면 회생 추천)
        // (프유)워 → (프유)회, (프)유워 → (프)유회 등 모든 형태 대응
        if (hasOngoingProcess && aiDefermentMonths > 0 && shortTermTotal > 0 && shortTermTotal + 1000 < effectiveLongTermTotal
            && diagnosis.contains("워") && !diagnosis.contains("회")) {
            diagnosis = diagnosis.replace("워", "회")
            Log.d("HWP_CALC", "진행중 워→회: 단기총액=${shortTermTotal}+1000 < 장기총액=${effectiveLongTermTotal} → $diagnosis")
        }

        // 신복/신복위 진행 중 + 추가채무 500만 초과 → "실효 후 1년 연체 필요"
        if (hasOngoingProcess && ongoingProcessName == "신" && postApplicationDebtMan > 500) {
            diagnosis += ", 실효 후 1년 연체 필요"
        }

        // 진단 앞에 다른 내용이 있으면 새새 → 새 (앞 내용이 있으므로 새 1개로 충분)
        if (!diagnosis.startsWith("새새") && diagnosis.contains("새새")) {
            diagnosis = diagnosis.replace("새새", "새")
        }

        // 중간 회→유 변환: 사업자, 단순재산초과, 한국주택금융공사 집담보, 동일 채권사가 담보+신용 보유, 또는 대상채무 4000만 이하
        val hasSameCreditorDamboAndCredit = parsedDamboCreditorNames.any { it in parsedCreditorMap }
        if (canDeferment && (isBusinessOwner || shortTermPropertyExcess || hasSameCreditorDamboAndCredit || hasHfcMortgage || targetDebt <= 4000)) {
            if (diagnosis.contains("신회워") || diagnosis.contains("프회워")) {
                diagnosis = diagnosis.replace("신회워", "신유워").replace("프회워", "프유워")
                val reason = when {
                    shortTermPropertyExcess -> "단순재산초과"
                    isBusinessOwner -> "사업자"
                    hasHfcMortgage -> "한국주택금융공사"
                    targetDebt <= 4000 -> "소액(${targetDebt}만)"
                    else -> "담보+신용 동일채권사"
                }
                Log.d("HWP_CALC", "중간 회→유($reason): $diagnosis")
            }
        }
        // 장기연체(90일+) → 회워/회새 유지 (중간 회 제거 대상 아님)
        if (delinquentDays >= 90 && (diagnosis == "신유워" || diagnosis == "프유워")) {
            diagnosis = "회워"
            Log.d("HWP_CALC", "장기연체 90일+ → 회워로 변경")
        }
        // 장기(신복위) 채무한도 초과 → 신복위 제도(신/프/유/워) 모두 불가 → 회생만 단순 진행
        if (longTermDebtOverLimit && !isBangsaeng) {
            val standardLabels = setOf("신회워", "신유워", "프회워", "프유워", "회워", "워회워")
            val before = diagnosis
            diagnosis = when {
                diagnosis == "워유워" -> "방생"  // 회생 불가 + 신복위 불가 → 단기 모두 불가 → 방생
                diagnosis in standardLabels -> "단순유리"  // 회생만 가능 → 단순 진행
                else -> diagnosis
            }
            if (before != diagnosis) Log.d("HWP_CALC", "장기 채무한도 초과 → 단순 회생 진행: $before → $diagnosis")
        }

        // 장기 라벨이 최종 진단과 불일치하면 동기화
        // 새새/새/회새는 새출발 진단이고, 단기성 진단도 장기 라벨과 다른 계열이므로 장기 원래 라벨 유지
        val standardLongTermDiags = setOf("신회워", "신유워", "프회워", "프유워", "회워", "워유워", "워회워")
        if (diagnosis in standardLongTermDiags && longTermDiagLabel.isNotEmpty() && longTermDiagLabel != diagnosis) {
            val updatedText = longTermText.toString().replace(" / $longTermDiagLabel", " / $diagnosis")
            longTermText.clear()
            longTermText.append(updatedText)
            Log.d("HWP_CALC", "장기라벨 동기화: $longTermDiagLabel → $diagnosis")
        }

        // 회워 진단 → 10%탕감(90%)으로 재조정 (기본 15%탕감보다 낮음)
        if (diagnosis == "회워" && repaymentRate == 85) {
            repaymentRate = 90
            rateReason = "회워"
            Log.d("HWP_CALC", "회워 진단 → 변제율 90%(10%탕감)으로 재조정")
        }

        var finalDiagnosis = if (diagnosisNote.isNotEmpty()) "$diagnosis $diagnosisNote" else diagnosis


        if (shortTermCarSaleApplied) finalDiagnosis = "차량 처분 시 $finalDiagnosis"
        if (diagnosis != "방생") {
            val daebuRatio = if (originalTargetDebt > 0) daebuDebtMan.toDouble() / originalTargetDebt * 100 else 0.0
            val isLongTermBugyeol = (repaymentRate == 100 && originalTargetDebt > 4000) || daebuRatio > 50 || (repaymentRate == 100 && majorCreditorRatio >= 50) || ownRealEstateCount == 2
            if (isLongTermBugyeol) {
                val newLt = longTermText.toString().replace(Regex("(\\[장기\\][^\n]*)")) { mr ->
                    val line = mr.value
                    if (!line.contains("불가") && !line.contains("부결고지")) "$line (부결고지)" else line
                }
                longTermText.clear()
                longTermText.append(newLt)
                binding.test2.text = longTermText.toString()
            }
            if (!isClientMode && originalTargetDebt <= 4000 && originalTargetDebt > 0) finalDiagnosis = "$finalDiagnosis, 수임료 오픈"
            val hasSuimOpen = originalTargetDebt in 1..4000
            if (majorCreditorRatio >= 70 && !(isSaeDiagnosis && !hasSuimOpen)) finalDiagnosis = "$finalDiagnosis, 수임 별도"
        }
        // 회워 진단 시 납부회수 표기 (2/3/4개월 이내 채무 기반)
        // 단, 6개월 비율이 30% 미만이면 회생 바로 가능 상태이므로 납부 후 표기 불필요
        var adjustedLtLabel = if (diagnosis in standardLongTermDiags && longTermDiagLabel.isNotEmpty() && longTermDiagLabel != diagnosis) diagnosis else longTermDiagLabel
        if (diagnosis.contains("회워") && recentDebtRatio >= 30) {
            // 각 조건마다: 해당 bucket에 속한 채무 중 가장 오래된 채무의 채권발생일 + result개월
            // → 그 중 가장 뒷 날짜를 X월까지 납부 후 회워로 표기 (헬퍼 함수)
            val (_, paymentCount, paymentNote) = calcPaymentDeadline(
                recentDebtEntries, twoMonthDebt, threeMonthDebt, fourMonthDebt
            )
            if (paymentNote.isNotEmpty()) {
                // "회워 바로 가능" 위치에 납부회수 텍스트를 대체
                if (finalDiagnosis.contains("회워 바로 가능")) {
                    finalDiagnosis = finalDiagnosis.replace("회워 바로 가능", paymentNote)
                } else {
                    finalDiagnosis = "$finalDiagnosis, $paymentNote"
                }
                // 납부 후 회워 vs 이후 가능 날짜 비교 → 더 빠른 쪽 기준으로 장기 제도 설정
                val dateMatch = Regex("(\\S+)\\s+(\\d{4}\\.\\d{2}\\.\\d{2})\\s+이후 가능").find(finalDiagnosis)
                if (dateMatch != null) {
                    val afterLabel = dateMatch.groupValues[1]
                    val dateParts = dateMatch.groupValues[2].split(".")
                    val afterCal = Calendar.getInstance().apply {
                        set(dateParts[0].toInt(), dateParts[1].toInt() - 1, dateParts[2].toInt())
                    }
                    val hoeCal = Calendar.getInstance().apply {
                        add(Calendar.MONTH, paymentCount)
                    }
                    val ltText = longTermText.toString()
                    if (afterCal.before(hoeCal)) {
                        // 이후 가능 날짜가 더 빠름 → 장기 라벨을 이후 가능 제도로 변경
                        if (longTermDiagLabel.isNotEmpty() && ltText.contains(" / $longTermDiagLabel")) {
                            longTermText.clear()
                            longTermText.append(ltText.replace(" / $longTermDiagLabel", " / $afterLabel"))
                        }
                        adjustedLtLabel = afterLabel
                        // 진단 순서도 이후 가능을 앞으로
                        finalDiagnosis = "$afterLabel ${dateMatch.groupValues[2]} 이후 가능, $paymentNote"
                        Log.d("HWP_CALC", "이후 가능이 더 빠름 → 장기 라벨: $afterLabel, 진단: $finalDiagnosis")
                    } else {
                        // 회워가 더 빠름 → 납부 후 적용이므로 항상 "회워"
                        val hoeLabel = "회워"
                        if (longTermDiagLabel.isNotEmpty() && ltText.contains(" / $longTermDiagLabel")) {
                            longTermText.clear()
                            longTermText.append(ltText.replace(" / $longTermDiagLabel", " / $hoeLabel"))
                        }
                        adjustedLtLabel = hoeLabel
                        Log.d("HWP_CALC", "납부 후 회워가 더 빠름 → 장기 라벨: $hoeLabel")
                    }
                }
            }
            Log.d("HWP_CALC", "납부회수: ${paymentNote.ifEmpty { "바로 회워 가능" }} (2개월=${twoMonthDebt}만, 3개월=${threeMonthDebt}만, 4개월=${fourMonthDebt}만)")
        }

        // 진행중 제도 prefix를 장기 라벨에도 반영
        if (hasOngoingProcess && ongoingProcessName.isNotEmpty()) {
            val ltText = longTermText.toString()
            val ongoingPrefix = "($ongoingProcessName)"
            // " / 회워" → " / (워)회워" 등
            for (diag in listOf("회워", "신유워", "프유워", "신회워", "프회워", "워유워")) {
                if (ltText.contains(" / $diag") && !ltText.contains(" / $ongoingPrefix")) {
                    longTermText.clear()
                    longTermText.append(ltText.replace(" / $diag", " / $ongoingPrefix$diag"))
                    break
                }
            }
        }
        binding.test2.text = longTermText.toString()
        binding.testing.text = "[진단] $finalDiagnosis"
        // 대출과목 테이블 출력 (카드이용금액 + 대출과목 + PDF 제외)
        val halfText = StringBuilder()
        // 대출과목 표시 제거
        binding.half.text = halfText.toString().trimEnd()

        // 거래처 진단 계산 + 결과 표시
        calculateClientDiagnosis(
            targetDebt, totalPayment, longTermIncome, livingCostShinbok, parentDeduction,
            longTermFullyBlocked, isFreelancer, longTermIsFullPayment,
            hasAuction, hasSeizure, hasGambling, hasStock, hasCrypto,
            recentDebtRatio, delinquentDays, hasOwnRealEstate,
            majorCreditorRatio, shortTermTotal, longTermTotal,
            canApplySae, saeTotalPayment, netProperty,
            isBusinessOwner, hasBusinessHistory, saeDebtOverLimit, saePropertyExcess, totalSecuredDebt,
            studentLoanApplied, isClientMode, longTermUseMonths, longTermDisplayMonths,
            name, finalDiagnosis,
            adjustedLtLabel, daebuDebtMan, repaymentRate, originalTargetDebt, diagnosis,
            livingCostTable = livingCostTable, householdForShinbok = householdForShinbok,
            parentCount = parentCount, hasWolse = hasWolse, parsedDamboTotal = parsedDamboTotal,
            hasOngoingProcess = hasOngoingProcess, ongoingProcessName = ongoingProcessName,
            saeExcludedDebtMan = saeExcludedDebtMan,
            ownRealEstateCount = ownRealEstateCount
        )
    }

    data class LongTermCalcResult(
        val conservativeMonthly: Int,
        val conservativeYears: Int,
        val aggressiveMonthly: Int,
        val aggressiveYears: Int,
        val finalMonthly: Int,
        val finalYear: Int,
        val useMonths: Boolean,
        val displayMonths: Int,
        val isFullPayment: Boolean,
        val debtInsufficient: Boolean
    )

    private fun calcLongTermValues(
        totalPayment: Int, targetDebt: Int, income: Int,
        livingCostShinbok: Int, livingCostTable: IntArray, parentDeduction: Int,
        householdForShinbok: Int, parentCount: Int,
        isFreelancer: Boolean, isFullPaymentInitial: Boolean,
        hasWolse: Boolean, parsedDamboTotal: Int,
        hasAuction: Boolean, hasSeizure: Boolean, hasGambling: Boolean,
        hasStock: Boolean, hasCrypto: Boolean,
        recentDebtRatio: Double, delinquentDays: Int, hasOwnRealEstate: Boolean,
        majorCreditorRatio: Double, shortTermTotal: Int,
        minMonthly: Int, maxMonths: Int
    ): LongTermCalcResult {
        val maxYears = maxMonths / 12
        if (targetDebt <= 0 || totalPayment <= 0) {
            return LongTermCalcResult(0, 0, 0, 0, 0, 0, false, 0, isFullPaymentInitial, false)
        }

        // === 보수 계산 ===
        var ltHousehold = householdForShinbok
        var ltLivingCost = livingCostTable[ltHousehold]
        var step1 = income - ltLivingCost - parentDeduction
        if (step1 < minMonthly && ltHousehold > 1) {
            while (step1 < minMonthly && ltHousehold > 1) {
                ltHousehold--
                ltLivingCost = livingCostTable[ltHousehold]
                step1 = income - ltLivingCost - parentDeduction
            }
        }
        var monthly: Int
        if (step1 >= minMonthly) {
            monthly = step1
        } else {
            val step2 = income - livingCostTable[1]
            if (step2 >= minMonthly) {
                monthly = step2
            } else {
                monthly = minMonthly
            }
        }
        // 월변제금 50만 이하 → 소득 기반 재계산
        if (monthly in 1..50 && income > 0) {
            val isAlone = householdForShinbok == 1 && parentCount == 0
            val isHeavy = hasWolse || parsedDamboTotal > 0 || householdForShinbok >= 3
            monthly = when {
                isAlone && isHeavy -> income / 4
                isAlone -> income / 3
                isHeavy -> income / 5
                else -> income / 4
            }
            if (monthly < minMonthly) monthly = minMonthly
        }
        // maxMonths 초과 체크
        val totalMonths = if (monthly > 0) Math.ceil(totalPayment.toDouble() / monthly).toInt() else 0
        if (totalMonths > maxMonths) {
            monthly = Math.ceil(totalPayment.toDouble() / maxMonths).toInt()
        }
        var conservativeMonthly = monthly

        // 보수 년수
        var conservativeYears = 0
        if (conservativeMonthly > 0) {
            conservativeYears = Math.round(totalPayment.toDouble() / conservativeMonthly / 12.0).toInt()
            conservativeYears = conservativeYears.coerceIn(3, maxYears)
            val calcMaxYears = Math.round(totalPayment.toDouble() / (conservativeMonthly * 12)).toInt().coerceAtLeast(3)
            if (conservativeYears > calcMaxYears) {
                conservativeYears = calcMaxYears
                conservativeMonthly = totalPayment / (conservativeYears * 12)
            }
        }

        // 프리랜서 (공격 월변제금보다 낮으면 일반 보수 계산 유지)
        if (isFreelancer && targetDebt > 0 && totalPayment > 0) {
            val freelancerMonthly = Math.ceil(totalPayment.toDouble() / 72).toInt()
            val aggressiveCheck = Math.ceil((income - livingCostShinbok - parentDeduction) * 2.0 / 3.0).toInt().coerceAtLeast(minMonthly)
            if (freelancerMonthly >= aggressiveCheck) {
                if (freelancerMonthly <= minMonthly) {
                    conservativeMonthly = minMonthly
                    conservativeYears = Math.round(totalPayment.toDouble() / minMonthly / 12.0).toInt()
                    conservativeYears = conservativeYears.coerceIn(3, maxYears)
                } else {
                    conservativeMonthly = freelancerMonthly
                    conservativeYears = 6
                }
            }
        }

        // 5만 단위 반올림
        var isFullPayment = isFullPaymentInitial
        if (conservativeMonthly >= minMonthly && !isFullPayment) {
            conservativeMonthly = (conservativeMonthly + 2) / 5 * 5
        }
        // 총변제액 초과 조정 (다단계)
        if (conservativeYears > 0 && conservativeMonthly * conservativeYears * 12 > targetDebt) {
            val reducedYears = maxOf(3, totalPayment / (conservativeMonthly * 12))
            if (conservativeMonthly * reducedYears * 12 <= totalPayment) {
                conservativeYears = reducedYears
            } else {
                val adjustedMonthly = totalPayment / (conservativeYears * 12)
                if (adjustedMonthly < minMonthly && conservativeYears > 1) {
                    conservativeMonthly = minMonthly
                    conservativeYears = Math.round(totalPayment.toDouble() / minMonthly / 12.0).toInt().coerceAtLeast(3)
                } else {
                    conservativeMonthly = adjustedMonthly
                }
            }
        }

        // 1년 미만 완납 → 100% 변제, 최소 3년
        if (conservativeMonthly > 0 && totalPayment < conservativeMonthly * 12) {
            conservativeMonthly = targetDebt / 36
            conservativeYears = 3
            isFullPayment = true
        }
        val debtInsufficient = conservativeMonthly in 1 until minMonthly

        // === 공격 계산 ===
        val aggressiveBase = income - livingCostShinbok - parentDeduction
        var aggressiveMonthly = Math.ceil(aggressiveBase * 2.0 / 3.0).toInt()
        if (aggressiveMonthly < minMonthly) aggressiveMonthly = minMonthly
        if (aggressiveMonthly >= minMonthly && !isFullPayment) {
            aggressiveMonthly = (aggressiveMonthly + 2) / 5 * 5
        }
        var aggressiveYears = 0
        if (aggressiveMonthly > 0 && aggressiveMonthly * maxOf(1, aggressiveYears) * 12 > targetDebt) {
            aggressiveMonthly = targetDebt / (maxOf(1, aggressiveYears) * 12)
        }
        aggressiveYears = Math.round(totalPayment.toDouble() / aggressiveMonthly / 12.0).toInt()
        if (aggressiveYears > maxYears) {
            aggressiveMonthly = Math.ceil(totalPayment.toDouble() / maxMonths).toInt()
            if (!isFullPayment) aggressiveMonthly = (aggressiveMonthly + 2) / 5 * 5
            aggressiveYears = maxYears
        }

        // === 최종 5단계 계산 (개월 기반 + 년 단위 반올림) ===
        if (conservativeYears < 3) conservativeYears = 3
        val longTermTotal = conservativeMonthly * conservativeYears * 12

        val surplus = income - livingCostShinbok
        val isAggressive = income <= livingCostShinbok
        val isMajorAverage = majorCreditorRatio > 50
        // 기존 보수 100% 조건(경매/압류/도박/6개월50%+/90일연체+/본인명의부동산)을 보수 75%로 통합
        val isTowardConservative = !isMajorAverage && (
            hasAuction || hasSeizure || hasGambling ||
            recentDebtRatio >= 50 || delinquentDays >= 90 || hasOwnRealEstate ||
            hasStock || hasCrypto ||
            (surplus > 0 && surplus < targetDebt * 0.03) ||
            (recentDebtRatio >= 30 && recentDebtRatio < 50))
        val isAloneFinal = householdForShinbok == 1 && parentCount == 0
        val isTowardAggressive = (surplus > targetDebt * 0.03) || isFreelancer ||
            (shortTermTotal > 0 && shortTermTotal < longTermTotal - 1000) || isAloneFinal

        val ltMonths = if (conservativeMonthly > 0) totalPayment / conservativeMonthly else conservativeYears * 12
        val agMonths = if (aggressiveMonthly > 0) totalPayment / aggressiveMonthly else aggressiveYears * 12
        val rawMonthsVal = when {
            isMajorAverage -> (ltMonths + agMonths) / 2
            isAggressive -> agMonths
            isTowardConservative -> (3 * ltMonths + agMonths) / 4
            isTowardAggressive -> (ltMonths + 3 * agMonths) / 4
            else -> (ltMonths + agMonths) / 2
        }
        var finalMonthsVal = rawMonthsVal.coerceIn(ltMonths, maxOf(ltMonths, agMonths))
        if (finalMonthsVal > maxMonths) finalMonthsVal = maxMonths
        var finalYear = finalMonthsVal / 12
        var useMonths = false
        var displayMonths = 0
        if (finalMonthsVal % 12 != 0) {
            // 년 단위 반올림 (6개월 이상 → 올림, 미만 → 내림)
            finalYear = if (finalMonthsVal % 12 >= 6) finalYear + 1 else finalYear
            if (finalYear < 1) finalYear = 1
        }

        // 최종 월변제금 (년 반올림 후 재계산)
        val finalTotalMonths = finalYear * 12
        var finalMonthly = if (finalTotalMonths > 0) totalPayment / finalTotalMonths else 0
        // 총 변제금이 대상채무 초과 시 년수 줄여서 조정
        if (finalMonthly > 0 && finalMonthly * finalTotalMonths > targetDebt) {
            finalMonthly = targetDebt / finalTotalMonths
        }
        if (finalMonthly < minMonthly) {
            finalMonthly = minMonthly
            val calcMaxYears = Math.round(totalPayment.toDouble() / (minMonthly * 12)).toInt().coerceAtLeast(3)
            if (finalYear > calcMaxYears) {
                finalYear = calcMaxYears
                useMonths = false
                displayMonths = 0
                finalMonthly = totalPayment / (finalYear * 12)
                if (finalMonthly <= 0) finalMonthly = Math.ceil(totalPayment.toDouble() / (finalYear * 12)).toInt().coerceAtLeast(1)
            }
        }

        // 5만 단위 반올림
        if (finalMonthly >= minMonthly && !isFullPayment) {
            val roundedUp = (finalMonthly + 2) / 5 * 5
            val checkMonths = if (useMonths && displayMonths > 0) displayMonths else finalYear * 12
            if (checkMonths > 0 && roundedUp * checkMonths > targetDebt) {
                finalMonthly = finalMonthly / 5 * 5
            } else {
                finalMonthly = roundedUp
            }
        }
        var checkMonths = if (useMonths && displayMonths > 0) displayMonths else finalYear * 12
        if (checkMonths > 0 && finalMonthly * checkMonths > targetDebt) {
            val newMonthly = targetDebt / checkMonths
            if (newMonthly >= minMonthly) {
                finalMonthly = newMonthly
            } else {
                // 월변제금 minMonthly 미만 방지 → 년수 단축
                finalYear = maxOf(3, targetDebt / (minMonthly * 12))
                useMonths = false
                displayMonths = 0
                checkMonths = finalYear * 12
                finalMonthly = if (checkMonths > 0) targetDebt / checkMonths else minMonthly
                if (finalMonthly < minMonthly) finalMonthly = minMonthly
            }
        }

        // 원금전액변제 → 년 단위 반올림
        if (isFullPayment && conservativeMonthly > 0) {
            if (majorCreditorRatio <= 50) {
                finalMonthly = conservativeMonthly
            }
            if (finalMonthly <= 0) finalMonthly = conservativeMonthly
            val rawMonths = totalPayment / finalMonthly
            finalYear = if (rawMonths % 12 >= 6) rawMonths / 12 + 1 else rawMonths / 12
            if (finalYear < 1) finalYear = 1
            finalMonthly = totalPayment / (finalYear * 12)
            if (finalMonthly < minMonthly) {
                finalMonthly = minMonthly
            }
            if (finalYear * 12 > maxMonths) {
                finalYear = maxMonths / 12
                finalMonthly = Math.ceil(totalPayment.toDouble() / (finalYear * 12)).toInt()
            }
            // 총변제액이 대상채무 초과 시 조정 (minMonthly 미만 방지)
            if (finalMonthly * finalYear * 12 > targetDebt) {
                val newMonthly = targetDebt / (finalYear * 12)
                if (newMonthly >= minMonthly) {
                    finalMonthly = newMonthly
                } else {
                    // 월변제금 minMonthly 미만 방지 → 년수 단축
                    finalYear = maxOf(3, targetDebt / (minMonthly * 12))
                    finalMonthly = if (finalYear > 0) targetDebt / (finalYear * 12) else minMonthly
                    if (finalMonthly < minMonthly) finalMonthly = minMonthly
                }
            }
        }

        // ★ 최종 보정: 월변제금 < minMonthly → 년수를 줄여서 월변제금 minMonthly 보장
        // (총변제금이 대상채무보다 높을 때 월변제금을 줄이지 말고 년수를 줄이라는 정책)
        if (finalMonthly in 1 until minMonthly && totalPayment > 0) {
            val newYears = ((totalPayment + minMonthly * 12 - 1) / (minMonthly * 12)).coerceIn(1, maxYears)
            Log.d("HWP_CALC", "장기 최종 보정: 월변제금 ${finalMonthly}만 < ${minMonthly}만 → ${minMonthly}만/${newYears}년 (이전 ${finalYear}년)")
            finalYear = newYears
            finalMonthly = minMonthly
            useMonths = false
            displayMonths = 0
        }

        return LongTermCalcResult(
            conservativeMonthly, conservativeYears,
            aggressiveMonthly, aggressiveYears,
            finalMonthly, finalYear,
            useMonths, displayMonths,
            isFullPayment, debtInsufficient
        )
    }

    private fun calculateClientDiagnosis(
        targetDebt: Int, totalPayment: Int, income: Int, livingCostShinbok: Int, parentDeduction: Int,
        longTermFullyBlocked: Boolean, isFreelancer: Boolean, longTermIsFullPayment: Boolean,
        hasAuction: Boolean, hasSeizure: Boolean, hasGambling: Boolean, hasStock: Boolean, hasCrypto: Boolean,
        recentDebtRatio: Double, delinquentDays: Int, hasOwnRealEstate: Boolean,
        majorCreditorRatio: Double, shortTermTotal: Int, longTermTotal: Int,
        canApplySae: Boolean, saeTotalPayment: Int, netProperty: Int,
        isBusinessOwner: Boolean, hasBusinessHistory: Boolean, saeDebtOverLimit: Boolean, saePropertyExcess: Boolean, totalSecuredDebt: Int,
        studentLoanApplied: Boolean, isClientMode: Boolean, longTermUseMonths: Boolean, longTermDisplayMonths: Int,
        name: String, finalDiagnosis: String,
        longTermDiagLabel: String, daebuDebtMan: Int, repaymentRate: Int, originalTargetDebt: Int, diagnosis: String,
        minMonthly: Int = 50, maxMonths: Int = 96,
        livingCostTable: IntArray, householdForShinbok: Int, parentCount: Int, hasWolse: Boolean, parsedDamboTotal: Int,
        hasOngoingProcess: Boolean = false, ongoingProcessName: String = "",
        saeExcludedDebtMan: Int = 0,
        ownRealEstateCount: Int = 0
    ) {
        val maxYears = maxMonths / 12

        // 공통 함수로 장기 계산 (본체와 동일 로직, minMonthly/maxMonths만 다름)
        val ltResult = if (targetDebt > 0 && !longTermFullyBlocked) {
            calcLongTermValues(
                totalPayment, targetDebt, income, livingCostShinbok, livingCostTable, parentDeduction,
                householdForShinbok, parentCount, isFreelancer, longTermIsFullPayment,
                hasWolse, parsedDamboTotal,
                hasAuction, hasSeizure, hasGambling, hasStock, hasCrypto,
                recentDebtRatio, delinquentDays, hasOwnRealEstate,
                majorCreditorRatio, shortTermTotal,
                minMonthly, maxMonths
            )
        } else null
        var clientFinalMonthly = ltResult?.finalMonthly ?: 0
        var clientFinalYear = ltResult?.finalYear ?: 0
        val clientUseMonths = ltResult?.useMonths ?: false
        var clientDisplayMonths = ltResult?.displayMonths ?: 0
        // 1인가구 + 소득 250만 이상 → 장기 기간 1년 단축 후 월 변제금 재계산 (부모 여부 무관)
        if (clientFinalYear > 3 && income >= 250 && householdForShinbok == 1) {
            val oldYear = clientFinalYear; val oldMonthly = clientFinalMonthly
            clientFinalYear -= 1
            var newMonths = clientFinalYear * 12
            clientFinalMonthly = if (newMonths > 0) (totalPayment + newMonths - 1) / newMonths else oldMonthly
            clientFinalMonthly = clientFinalMonthly / 5 * 5  // 5만 단위 내림 (대상채무 초과 방지)
            // 내림 후 50만보다 작으면 년수를 더 줄임
            while (clientFinalYear > 1 && clientFinalMonthly < 50) {
                clientFinalYear -= 1
                newMonths = clientFinalYear * 12
                clientFinalMonthly = if (newMonths > 0) (totalPayment + newMonths - 1) / newMonths else clientFinalMonthly
                clientFinalMonthly = clientFinalMonthly / 5 * 5
            }
            if (clientUseMonths) clientDisplayMonths = newMonths
            Log.d("HWP_CALC", "거래처 1인가구+소득250↑ → 장기 -1년: ${oldYear}년/${oldMonthly}만 → ${clientFinalYear}년/${clientFinalMonthly}만")
        }

        // 새새 계산 (min ${minMonthly}만, max ${maxYears}년)
        val clientSaeTargetDebt = targetDebt - saeExcludedDebtMan
        var clientSaeMonthly = 0; var clientSaeYears = 0; var clientSaeTotalPayment = 0
        if (canApplySae && clientSaeTargetDebt > 0 && saeTotalPayment > 0) {
            clientSaeTotalPayment = saeTotalPayment
            val incomeRatio = income.toDouble() / clientSaeTargetDebt * 100
            clientSaeYears = when {
                incomeRatio > 6 -> 5
                incomeRatio > 3 -> maxYears
                else -> maxYears
            }
            val saeIsFullPayment = netProperty >= clientSaeTargetDebt
            clientSaeMonthly = clientSaeTotalPayment / (clientSaeYears * 12)
            if (!saeIsFullPayment && clientSaeMonthly >= minMonthly) {
                clientSaeMonthly = (clientSaeMonthly + 2) / 5 * 5
            }
            if (clientSaeYears > 0 && clientSaeMonthly * clientSaeYears * 12 > clientSaeTargetDebt) {
                clientSaeMonthly = clientSaeTargetDebt / (clientSaeYears * 12)
            }
            if (clientSaeMonthly < minMonthly) {
                clientSaeMonthly = minMonthly
                val exactYears = clientSaeTotalPayment.toDouble() / minMonthly / 12.0
                clientSaeYears = Math.round(exactYears).toInt()
                clientSaeYears = clientSaeYears.coerceIn(2, maxYears)
                clientSaeTotalPayment = clientSaeMonthly * clientSaeYears * 12
            } else {
                clientSaeTotalPayment = clientSaeMonthly * clientSaeYears * 12
            }
        }

        // 거래처 모드일 때 장기/새새 결과를 거래처 기준으로 덮어쓰기
        if (isClientMode) {
            val clientLongTermText = StringBuilder()
            val legoTest2 = binding.test2.text.toString()
            val legoFirstLine = legoTest2.split("\n").firstOrNull() ?: ""
            // 본체가 정상 결과("만 /" 포함)이고 거래처도 결과 있으면 → 거래처 숫자로 교체
            // 본체가 불가/미협약 등이면 → 그대로 사용 (조건 중복 불필요)
            val legoHasResult = legoFirstLine.contains("만 /")
            if (legoHasResult && clientFinalMonthly > 0) {
                val studentLoanLongSuffix = if (studentLoanApplied) " (학자금 제외)" else ""
                val dispMonths = if (clientUseMonths) clientDisplayMonths.coerceAtMost(maxMonths) else clientFinalYear * 12
                val clientMonthlyForDisplay = if (clientUseMonths && clientDisplayMonths > maxMonths && dispMonths > 0) {
                    Math.ceil(totalPayment.toDouble() / maxMonths).toInt()
                } else clientFinalMonthly
                val clientPeriodStr = if (clientUseMonths) "${dispMonths}개월납" else "${clientFinalYear}년납"
                val clientLtLabel = if (hasOngoingProcess && ongoingProcessName.isNotEmpty() && longTermDiagLabel.isNotEmpty() && !longTermDiagLabel.startsWith("(")) "($ongoingProcessName)$longTermDiagLabel" else longTermDiagLabel
                val clientDiagSuffix = if (clientLtLabel.isNotEmpty()) " / $clientLtLabel" else ""
                clientLongTermText.append("[장기] ${clientMonthlyForDisplay}만 / $clientPeriodStr$studentLoanLongSuffix$clientDiagSuffix")
            } else {
                clientLongTermText.append(legoFirstLine)
            }
            if (canApplySae && clientSaeTotalPayment > 0) {
                val saeBugyeol = netProperty - targetDebt >= 10000 || longTermFullyBlocked
                clientLongTermText.append("\n[새새] ${clientSaeMonthly}만 / ${clientSaeYears}년납${if (saeBugyeol) " (부결고지)" else ""}")
            } else if (hasBusinessHistory && isBusinessOwner && saeDebtOverLimit) {
                clientLongTermText.append("\n[새새] 새새 불가(채무한도초과 담보${formatToEok(totalSecuredDebt)})")
            } else if (hasBusinessHistory && isBusinessOwner && saePropertyExcess) {
                clientLongTermText.append("\n[새새] 새새 불가(재산초과)")
            }
            // 부결고지 적용
            val daebuRatioClient = if (originalTargetDebt > 0) daebuDebtMan.toDouble() / originalTargetDebt * 100 else 0.0
            val isClientBugyeol = (repaymentRate == 100 && originalTargetDebt > 4000) || daebuRatioClient > 50 || (repaymentRate == 100 && majorCreditorRatio >= 50) || ownRealEstateCount == 2
            if (isClientBugyeol) {
                val newClt = clientLongTermText.toString().replace(Regex("(\\[장기\\][^\n]*)")) { mr ->
                    val line = mr.value
                    if (!line.contains("불가") && !line.contains("부결고지")) "$line (부결고지)" else line
                }
                clientLongTermText.clear()
                clientLongTermText.append(newClt)
            }
            binding.test2.text = clientLongTermText.toString()
        }

        Log.d("HWP_CALC", "이름: $name, 소득: ${income}만, 대상: ${targetDebt}만, 재산: ${netProperty}만, 6개월비율: ${String.format("%.1f", recentDebtRatio)}%, 진단: $finalDiagnosis")
        Log.d("HWP_CALC", "거래처 장기: ${clientFinalMonthly}만/${clientFinalYear}년, 새새: ${clientSaeMonthly}만/${clientSaeYears}년")
    }

    // ============= 대출과목 표 채권사명 매칭 헬퍼 =============
    private fun extractLoanCatCreditorNames(line: String, target: MutableSet<String>) {
        val nameP = Pattern.compile("([가-힣]{2,12}(?:저축은행|은행|카드|캐피탈|대부|증권|공단|보험|신협|농협|수협|보증재단|파이낸셜|파이낸스|투자증권|상호저축은행|할부금융))")
        val nameM = nameP.matcher(line)
        while (nameM.find()) {
            target.add(nameM.group(1)!!)
        }
    }

    private fun matchesLoanCatDambo(rawName: String, names: Set<String>): Boolean {
        if (rawName.length < 2 || names.isEmpty()) return false
        val cleaned = rawName.replace(Regex("\\[.*?\\]"), "")
        return names.any { cleaned.contains(it) || it.contains(cleaned) }
    }

    private fun isSameYearMonthAsToday(cal: Calendar): Boolean {
        val now = Calendar.getInstance()
        return cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) && cal.get(Calendar.MONTH) == now.get(Calendar.MONTH)
    }

    private fun extractSeqNumbers(line: String, target: MutableSet<Int>) {
        val ciM = Pattern.compile("(\\d{1,2}(?:,\\d{1,2})+)").matcher(line)
        while (ciM.find()) {
            for (part in ciM.group(1)!!.split(",")) {
                val n = part.toIntOrNull() ?: continue
                if (n in 1..30) target.add(n)
            }
        }
        val csM = Pattern.compile("^(\\d{1,2})(?![억만원천년,])").matcher(line)
        if (csM.find()) {
            val n = csM.group(1)!!.toInt()
            if (n in 1..30) target.add(n)
        }
    }

    // ============= 납부회수 계산 (parseHwpData 사이즈 축소용 추출) =============
    private fun calcPaymentDeadline(
        recentDebtEntries: List<Pair<Calendar, Int>>,
        twoMonthDebt: Int, threeMonthDebt: Int, fourMonthDebt: Int
    ): Triple<Calendar?, Int, String> {
        val candidates = mutableListOf<Pair<Calendar, String>>()
        val rules = listOf(
            Triple(fourMonthDebt >= 5000, 4, 6) to "4개월≥5000→6",
            Triple(threeMonthDebt >= 5000, 3, 6) to "3개월≥5000→6",
            Triple(threeMonthDebt >= 4000, 3, 5) to "3개월≥4000→5",
            Triple(twoMonthDebt >= 5000, 2, 6) to "2개월≥5000→6",
            Triple(twoMonthDebt >= 4000, 2, 5) to "2개월≥4000→5",
            Triple(twoMonthDebt >= 1000, 2, 4) to "2개월≥1000→4"
        )
        for ((cond, name) in rules) {
            if (!cond.first) continue
            val bucketMonths = cond.second
            val resultMonths = cond.third
            val boundary = Calendar.getInstance().apply { add(Calendar.MONTH, -bucketMonths) }
            val oldest = recentDebtEntries.filter { it.first.after(boundary) }
                .minByOrNull { it.first.timeInMillis }?.first ?: continue
            val cal = Calendar.getInstance().apply {
                timeInMillis = oldest.timeInMillis
                add(Calendar.MONTH, resultMonths)
            }
            candidates.add(cal to "$name(채권${oldest.get(Calendar.YEAR)}.${oldest.get(Calendar.MONTH) + 1}.${oldest.get(Calendar.DAY_OF_MONTH)}+${resultMonths}개월)")
        }
        val latest = candidates.maxByOrNull { it.first.timeInMillis }
        if (latest == null) return Triple(null, 0, "")
        val today = Calendar.getInstance()
        val monthsDiff = (latest.first.get(Calendar.YEAR) - today.get(Calendar.YEAR)) * 12 +
                         (latest.first.get(Calendar.MONTH) - today.get(Calendar.MONTH))
        Log.d("HWP_CALC", "납부회수 후보: ${candidates.joinToString { "${it.second}→${it.first.get(Calendar.MONTH) + 1}월" }} → 최종 ${latest.first.get(Calendar.MONTH) + 1}월")
        return Triple(latest.first, monthsDiff + 1, "${latest.first.get(Calendar.MONTH) + 1}월까지 납부 후 회워")
    }

    // ============= 유틸리티 =============
    private fun extractAmountAfterKeyword(text: String, keyword: String): Int {
        if (!text.contains(keyword)) return 0
        val afterKeyword = text.substring(text.indexOf(keyword))
        // "/" 구분자 이전까지만 추출 (시세 5억 / 대출 3억 → 시세 5억만 추출)
        val segment = afterKeyword.split("/")[0]
        return extractAmount(segment)
    }

    // "/" 구분된 모든 세그먼트에서 키워드 뒤 금액을 합산 (예: 담보대출 2억9500만 / 담보대출 2억3800만 → 53300)
    private fun extractAllAmountsAfterKeyword(text: String, keyword: String): Int {
        if (!text.contains(keyword)) return 0
        var total = 0
        for (seg in text.split("/")) {
            if (seg.contains(keyword)) {
                total += extractAmount(seg.substring(seg.indexOf(keyword)))
            }
        }
        return total
    }

    // 공동명의 본인 지분율(%) 파싱. "본인4:어머니4:누나2" → 40%, "본인 50" → 50%
    // 본인 숫자 뒤에 콜론이 오면 비율 표기로 보고 분모 합산하여 %로 변환
    private fun parseOwnerRatioPct(text: String): Int? {
        val ownMatch = Regex("본인\\s*(\\d+)").find(text) ?: return null
        val ownNum = ownMatch.groupValues[1].toIntOrNull() ?: return null
        val tail = text.substring(ownMatch.range.last + 1).trimStart()
        if (tail.startsWith(":") || tail.startsWith("：")) {
            val sum = Regex("[가-힣]+\\s*(\\d+)").findAll(text)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.sum()
            if (sum > 0) return Math.round(ownNum.toDouble() * 100 / sum).toInt()
        }
        return ownNum
    }

    private fun formatToEok(amountInMan: Int): String {
        return if (amountInMan >= 10000) {
            val eok = amountInMan / 10000
            val man = amountInMan % 10000
            if (man > 0) "${eok}억${man}만" else "${eok}억"
        } else "${amountInMan}만"
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

    private val debtResultKeywords = listOf("면책","면채","실효","폐지","기각","취하","완납","안되","안됬","거절","반려")
    private fun hasDebtResult(text: String) = debtResultKeywords.any { text.contains(it) }
    private val damboKeywordList = listOf("할부금융","리스","후순위","중고차할부","신차할부","차할부","차량할부","자동차할부","(500)","(510)","시설자금","(1071)","중도금","예적금","보증금대출","유가증권","기계담보","기계할부")
    private fun hasDamboKeyword(text: String) = damboKeywordList.any { text.contains(it) }

    private fun isCarLoanKeyword(t: String) = t.contains("신차할부") || t.contains("중고차할부") || t.contains("(500)") || t.contains("(510)") || t.contains("자동차담보") || t.contains("차량담보")

    private fun hasNoJilgwon(ns: String, ln: String): Boolean {
        // 질권설정 X 감지
        val noJilgwon = ns.contains("질권설정x") || ns.contains("질권설정X") || ns.contains("질권x") || ns.contains("질권X") || ns.contains("질권설정안") || ln.contains("질권설정 x") || ln.contains("질권설정 X") || ln.contains("질권 x") || ln.contains("질권 X") || ln.contains("질권설정 안")
        // 채권양도 X 감지
        val noChaegwonYangdo = ns.contains("채권양도x") || ns.contains("채권양도X") || ns.contains("채권양도안") || ln.contains("채권양도 x") || ln.contains("채권양도 X") || ln.contains("채권양도 안")
        // 질권설정 X + 채권양도 X 둘 다 충족해야 대상채무 포함
        return noJilgwon && noChaegwonYangdo
    }

    private val nonProfitKeywords = listOf("비영리","노동조합","종교","교회","사찰","재단법인","사단법인","협회","복지")
    private fun hasNonProfitKeyword(text: String) = nonProfitKeywords.any { text.contains(it) }

    private fun formatCalDate(cal: Calendar): String =
        "${cal.get(Calendar.YEAR)}.${String.format("%02d", cal.get(Calendar.MONTH) + 1)}.${String.format("%02d", cal.get(Calendar.DAY_OF_MONTH))}"

    private fun findThresholdDate(entries: MutableList<Pair<Calendar, Int>>, initialMan: Int, targetDebt: Int): String? {
        entries.sortBy { it.first.timeInMillis }
        var remaining = initialMan
        for ((date, amountChon) in entries) {
            remaining -= (amountChon + 5) / 10
            if (remaining < 0) remaining = 0
            if (remaining.toDouble() / targetDebt * 100 < 30) {
                val c = date.clone() as Calendar
                c.add(Calendar.MONTH, 6)
                c.add(Calendar.DATE, 1)
                return formatCalDate(c)
            }
        }
        return null
    }

    private fun diagByDelin(days: Int, b90: Boolean, b30: Boolean,
        v90b: String, v90: String, v30b: String, v30: String, vElseB: String, vElse: String
    ): String = when {
        days >= 90 -> if (b90) v90b else v90
        days >= 30 -> if (b30) v30b else v30
        else -> if (b30) vElseB else vElse
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
                lowerName.endsWith(".pdf") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".bmp") || lowerName.endsWith(".webp") -> pdfList.add(baseName to uri)
            }
        }

        batchGroups.clear()
        for ((hwpBase, hwpUri) in hwpList) {
            // HWP/PDF 파일명 공통 접두어(이름)로 매칭
            // "김현식의정" ↔ "김현식합의서" → 공통 "김현식" (3자) → 매칭
            val matchedPdfs = pdfList
                .filter { (pdfBase, _) ->
                    val commonLen = hwpBase.zip(pdfBase).takeWhile { (a, b) -> a == b }.count()
                    commonLen >= 2
                }
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

        // 같은 이름의 PDF 텍스트 추출 후 처리 (합의서/변제계획안=텍스트파싱, 상환내역서=OCR)
        val ocrPdfUris = ArrayList<Pair<Uri, String>>()
        for (pdfUri in group.pdfUris) {
            val pdfFileName = getFileName(pdfUri) ?: "PDF"
            val lowerFileName = pdfFileName.lowercase()
            when {
                lowerFileName.contains("합의") -> {
                    // 합의서는 항상 Claude Vision으로 처리 (테이블 추출이 필요)
                    Log.d("BATCH", "합의서 → Claude Vision 처리 ($pdfFileName)")
                    ocrPdfUris.add(Pair(pdfUri, pdfFileName))
                }
                lowerFileName.contains("변제계획") || lowerFileName.contains("변제예정") -> {
                    val text = extractPdfText(pdfUri)
                    if (text.length > 50) parseRecoveryPlanPdfText(text, pdfFileName)
                    else ocrPdfUris.add(Pair(pdfUri, pdfFileName))  // 이미지 PDF → OCR
                }
                else -> ocrPdfUris.add(Pair(pdfUri, pdfFileName))
            }
        }

        if (ocrPdfUris.isNotEmpty()) {
            extractDataFromPdfImages(ocrPdfUris) { ocrResult ->
                if (ocrResult.defermentMonths > 0) {
                    hwpText += "\n유예기간 ${ocrResult.defermentMonths}개월"
                    if (ocrResult.defermentMonths > aiDefermentMonths) aiDefermentMonths = ocrResult.defermentMonths
                    Log.d("BATCH", "PDF OCR 유예기간 추출: ${ocrResult.defermentMonths}개월 → aiDefermentMonths=$aiDefermentMonths")
                }
                if (ocrResult.applicationDate.isNotEmpty()) {
                    pdfApplicationDate = ocrResult.applicationDate
                    Log.d("BATCH", "PDF OCR 신청일자 추출: ${ocrResult.applicationDate}")
                }
                finishFileProcessing()
            }
        } else {
            finishFileProcessing()
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

        // PDF 데이터 초기화
        pdfAgreementDebt = 0; pdfAgreementProcess = ""; pdfApplicationDate = ""; hasPdfFile = false; pdfAgreementCreditors.clear()
        aiDefermentMonths = 0
        aiHasRecoveryPlan = false
        pdfExcludedGuaranteeDebt = 0; pdfExcludedOtherDebt = 0; pdfExcludedDamboCreditors.clear(); pdfExcludedEntries.clear()
        pdfOutsideEntries.clear()
        pdfRecoveryDebt = 0; pdfRecoveryIncome = 0; pdfRecoveryMonths = 0; pdfRecoveryCreditors.clear()

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

}