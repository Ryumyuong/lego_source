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
    private var pdfRecoveryDebt = 0            // 변제계획안 대상채무 (만원)
    private var pdfRecoveryIncome = 0          // 변제계획안 월변제금 (만원)
    private var pdfRecoveryMonths = 0          // 변제계획안 변제기간 (개월)

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
                    // 합의서는 항상 Gemini Vision으로 처리 (테이블 추출이 필요)
                    Log.d("FILE_PROCESS", "합의서 → Gemini Vision 처리 ($fileName)")
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
                    Log.d("FILE_PROCESS", "PDF OCR 유예기간 추출: ${ocrResult.defermentMonths}개월")
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

        // [1] 제도 타입 - Gemini가 못 잡았을 때만 OCR로 보완
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

        // [3] 대상채무, 제외채무 → Gemini가 처리 (pdfAgreementDebt, pdfExcludedOtherDebt 이미 설정됨)

        Log.d("PDF_PARSE", "합의서 파싱 결과: 제도=$pdfAgreementProcess, 채무=${pdfAgreementDebt}만, " +
            "유예=${aiDefermentMonths}개월, 제외보증=${pdfExcludedGuaranteeDebt}만, 제외기타=${pdfExcludedOtherDebt}만 ($fileName)")
    }

    private fun parseRecoveryPlanPdfText(text: String, fileName: String) {
        aiHasRecoveryPlan = true

        val lines = text.split("\n")

        // [1] 대상채무: "합계" 또는 "총계" 행에서 가장 큰 금액
        for (line in lines) {
            val lineNoSpace = line.replace(Regex("\\s"), "")
            if (lineNoSpace.contains("합계") || lineNoSpace.contains("총계")) {
                val amounts = Regex("[\\d,]{5,}").findAll(line)
                    .map { it.value.replace(",", "").toLongOrNull() ?: 0L }
                    .filter { it > 0 }.toList()
                val maxAmount = amounts.maxOrNull() ?: 0L
                if (maxAmount > 10000 && pdfRecoveryDebt == 0) {
                    pdfRecoveryDebt = (maxAmount / 10000).toInt()
                }
            }
        }

        // [2] 월 변제금: "월 실제 가용소득(⑤)" 우선, 없으면 "가용소득" 섹션에서 추출
        val textNoSpace = text.replace(Regex("\\s"), "")
        val actualIncomeMatch = Regex("월실제가용소득[^\\d]{0,20}([\\d,]{5,})").find(textNoSpace)
        if (actualIncomeMatch != null) {
            val amount = actualIncomeMatch.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
            if (amount in 100000..50000000) {
                pdfRecoveryIncome = (amount / 10000).toInt()
            }
        } else {
            val incomeIdx = text.indexOf("가용소득").takeIf { it >= 0 }
            if (incomeIdx != null) {
                val incomeArea = text.substring(incomeIdx, minOf(incomeIdx + 500, text.length))
                val amounts = Regex("[\\d,]{5,}").findAll(incomeArea)
                    .map { it.value.replace(",", "").toLongOrNull() ?: 0L }
                    .filter { it in 100000..50000000 }.toList()
                if (amounts.size >= 3) {
                    pdfRecoveryIncome = (amounts[2] / 10000).toInt()
                } else if (amounts.isNotEmpty()) {
                    pdfRecoveryIncome = (amounts.last() / 10000).toInt()
                }
            }
        }

        // [3] 변제기간: "XX개월간" 또는 변제횟수
        val monthsMatch = Regex("(\\d+)\\s*개월간").find(text)
            ?: Regex("변제횟수[^\\d]{0,10}(\\d+)").find(textNoSpace)
        if (monthsMatch != null) {
            pdfRecoveryMonths = monthsMatch.groupValues[1].toInt()
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
                                callGeminiVisionForAgreement(arrayListOf(imageBitmap), fileName)
                            } catch (e: Exception) {
                                Log.e("FILE_PROCESS", "합의서 Gemini AI 실패 ($fileName): ${e.message}")
                            }
                            runOnUiThread {
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                        }.start()
                    } else if (isRepayment) {
                        Thread {
                            try {
                                val result = callGeminiVisionForRepayment(imageBitmap, fileName)
                                if (result.first.isNotEmpty()) detectedApplicationDate = result.first
                                if (result.second > 0 && result.second > maxDefermentMonths) maxDefermentMonths = result.second
                            } catch (e: Exception) {
                                Log.e("FILE_PROCESS", "상환내역서 Gemini AI 실패 ($fileName): ${e.message}")
                            }
                            runOnUiThread {
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                        }.start()
                    } else if (isRecoveryPlanImg) {
                        Thread {
                            try {
                                callGeminiVisionForRecoveryPlan(arrayListOf(imageBitmap), fileName)
                            } catch (e: Exception) {
                                Log.e("FILE_PROCESS", "변제계획안 Gemini AI 실패 ($fileName): ${e.message}")
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

                    // ===== 합의서 이미지 PDF: Gemini AI로 테이블 추출 =====
                    if (isAgreementPdf) {
                        // 전체 페이지 렌더링 (최대 10페이지, OOM 방지)
                        val neededPages = (0 until minOf(pageCount, 10)).toMutableList()
                        val geminiPages = ArrayList<Bitmap>()
                        for (i in neededPages) {
                            val p = renderer.openPage(i)
                            val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            p.close()
                            geminiPages.add(bmp)
                        }
                        renderer.close(); fd.close(); tempFile.delete()
                        Log.d("FILE_PROCESS", "합의서 PDF→이미지 변환: $fileName ${neededPages.size}/${pageCount}페이지")

                        Thread {
                            try {
                                callGeminiVisionForAgreement(geminiPages, fileName)
                            } catch (e: Exception) {
                                Log.e("FILE_PROCESS", "합의서 Gemini AI 실패 ($fileName): ${e.message}")
                            }
                            // Gemini 완료 후 remaining 감소 (UI 스레드에서)
                            runOnUiThread {
                                Log.d("FILE_PROCESS", "합의서 Gemini 완료 → remaining 감소 ($fileName)")
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                        }.start()
                        continue  // 다음 PDF로
                    }

                    // ===== 변제계획안 이미지 PDF: Gemini AI로 대상채무/가용소득 추출 =====
                    if (isRecoveryPlanPdf) {
                        val neededPages = (0 until minOf(pageCount, 10)).toMutableList()
                        val geminiPages = ArrayList<Bitmap>()
                        for (i in neededPages) {
                            val p = renderer.openPage(i)
                            val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            p.close()
                            geminiPages.add(bmp)
                        }
                        renderer.close(); fd.close(); tempFile.delete()
                        Log.d("FILE_PROCESS", "변제계획안 PDF→이미지 변환: $fileName ${neededPages.size}/${pageCount}페이지")

                        Thread {
                            try {
                                callGeminiVisionForRecoveryPlan(geminiPages, fileName)
                            } catch (e: Exception) {
                                Log.e("FILE_PROCESS", "변제계획안 Gemini AI 실패 ($fileName): ${e.message}")
                            }
                            runOnUiThread {
                                Log.d("FILE_PROCESS", "변제계획안 Gemini 완료 → remaining 감소 ($fileName)")
                                remaining--
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
                            }
                        }.start()
                        continue  // 다음 PDF로
                    }

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
                                if (remaining <= 0) onComplete(PdfOcrResult(maxDefermentMonths, detectedApplicationDate))
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
- 반드시 "유예기간" 또는 "거치기간"이라는 텍스트가 이미지에 명시적으로 존재해야 합니다
- "유예기간 N개월" 또는 "거치기간 N개월" 형태로 적혀있는 경우에만 해당 숫자를 추출
- 상환금액 테이블의 회차, 소계, 납입횟수 등은 유예기간이 아닙니다
- "유예기간" 또는 "거치기간"이라는 단어가 이미지에 없으면 반드시 0

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

    private fun callGeminiVisionForAgreement(bitmaps: List<Bitmap>, fileName: String) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        val prompt = """이 PDF 이미지들은 채무조정 체결합의서입니다. 다음 정보를 추출하세요.

[1] 제도 타입 - 문서 첫 페이지 상단 제목/헤더에서 확인
- "신속채무조정" → "신"
- "사전채무조정" → "프"
- "개인워크아웃" 또는 "개인채무조정 확정" → "워"
- 해당없음 → ""
- 주의: "사전채무조정"과 "개인채무조정"은 다른 제도임. 정확히 구분하세요.

[2] 대상채무 - "채무별 조정내역" 테이블
- 이 테이블은 "조정 전"과 "조정 후"로 나뉘어 있음
- 맨 아래 "합계" 행에서 "조정 전" 원금 값(원 단위 숫자)을 totalPrincipal로 응답
- summaryBefore/summaryAfter는 더 이상 사용하지 않으므로 빈 배열로 응답

[3] 제외된 채무내역 원금 합계 (원 단위, 숫자만)
- "제외된 채무내역" 테이블이 있으면 모든 행의 "원금잔액" 합계
- 없으면 0

[4] 유예기간 (개월 수)
- "유예기간" 또는 "거치기간" 값
- 없으면 0

[5] 채권금융회사 목록
- "채무별 조정내역" 테이블의 채권금융회사명과 조정 전 원금 (원 단위). 같은 채권사에 여러 대출과목/계좌가 있으면 조정 전 원금을 모두 합산
- "제외된 채무내역" 테이블의 채권금융회사명, 원금 (원 단위), 채권분류 또는 대출종류
- 두 테이블 모두에서 채권사명과 원금을 추출
- 제외된 채무의 경우 "제외사유" 컬럼 값도 함께 추출 (예: "담보채무", "보증서담보대출", "소액채무" 등)

반드시 JSON만 응답:
{"processType": "신/프/워/빈문자열", "processTitle": "문서 제목 원문", "totalPrincipal": 합계행조정전원금숫자, "excludedDebtTotal": 숫자, "defermentMonths": 숫자, "creditors": [{"name": "채권사명", "principal": 원금숫자}, ...], "excludedCreditors": [{"name": "채권사명", "principal": 원금숫자, "reason": "제외사유"}, ...]}"""

        val parts = JSONArray()
        parts.put(JSONObject().put("text", prompt))

        // 첫 페이지(테이블)와 제외채무 페이지 전송
        for (bmp in bitmaps) {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val imageBytes = baos.toByteArray()
            val base64Str = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
            val inlineData = JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", base64Str)
            parts.put(JSONObject().put("inlineData", inlineData))
        }
        Log.d("FILE_PROCESS", "합의서 Gemini: ${bitmaps.size}페이지 전송 ($fileName)")

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
            return
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
            return
        }

        Log.d("FILE_PROCESS", "합의서 Gemini AI 응답: $aiText")

        val jsonStr = aiText
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()
        val jsonStart = jsonStr.indexOf("{")
        val jsonEnd = jsonStr.lastIndexOf("}")
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            Log.e("FILE_PROCESS", "합의서 Gemini JSON 파싱 실패: $jsonStr")
            return
        }

        val data = JSONObject(jsonStr.substring(jsonStart, jsonEnd + 1))
        val processType = data.optString("processType", "")
        val processTitle = data.optString("processTitle", "")
        // 합계 행의 조정 전 원금 직접 읽기
        val totalPrincipal = data.optLong("totalPrincipal", 0L)
        Log.d("FILE_PROCESS", "합의서 합계 조정전 원금: ${totalPrincipal} (${totalPrincipal / 10000}만)")
        val excludedDebtTotal = data.optLong("excludedDebtTotal", 0L)
        val deferMonths = data.optInt("defermentMonths", 0)

        // Gemini의 processType 대신 processTitle 원문으로 직접 판단 (더 정확)
        val detectedProcess = when {
            processTitle.contains("새출발기금") -> "새"
            processTitle.contains("사전채무조정") -> "프"
            processTitle.contains("신속채무조정") -> "신"
            processTitle.contains("개인워크아웃") -> "워"
            processTitle.contains("개인채무조정") && !processTitle.contains("워크아웃") -> "프"
            processType.isNotEmpty() -> processType  // fallback
            else -> ""
        }
        Log.d("FILE_PROCESS", "합의서 Gemini 제목 원문: '$processTitle' → 제도='$detectedProcess' (Gemini판단='$processType')")
        if (detectedProcess.isNotEmpty()) pdfAgreementProcess = detectedProcess
        if (totalPrincipal > 0) pdfAgreementDebt = (totalPrincipal / 10000).toInt()
        // excludedDebtTotal은 채권사별 담보 분류로 대체 (아래 excludedCreditorsArr 파싱에서 처리)
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
                if (cName.length >= 2 && cPrincipal > 0) {
                    // 제외된 채권사는 pdfAgreementCreditors에 넣지 않음 (merge 비교 시 동일 채권사 비담보와 혼동 방지)
                    // 제외사유: 보증서담보/지급보증 → 대상채무, 기타 담보(주택담보, 담보채무 등) → 담보
                    val isGuarantee = reason.contains("보증서") || reason.contains("지급보증")
                    if (isGuarantee) {
                        excludedGuaranteeTotal += cPrincipal
                    } else {
                        excludedDamboTotal += cPrincipal
                        pdfExcludedDamboCreditors.add(cName)
                    }
                    Log.d("FILE_PROCESS", "제외 채권사: $cName ${cPrincipal}만 (사유=$reason, 보증서=${isGuarantee})")
                }
            }
        }
        // 제외채무: 보증서담보→대상채무, 기타담보→담보
        pdfExcludedGuaranteeDebt = excludedGuaranteeTotal
        pdfExcludedOtherDebt = excludedDamboTotal
        Log.d("FILE_PROCESS", "합의서 채권사: ${pdfAgreementCreditors.size}건 $pdfAgreementCreditors")

        // totalPrincipal이 없으면 개별 채권사 합산으로 fallback
        val creditorSum = pdfAgreementCreditors.values.sum()
        if (totalPrincipal <= 0 && creditorSum > 0) {
            pdfAgreementDebt = creditorSum
            Log.d("FILE_PROCESS", "합의서 대상채무: 채권사합산 ${creditorSum}만 (합계행 없음)")
        }

        Log.d("FILE_PROCESS", "합의서 Gemini 결과: 제도=$processType, 대상채무=${pdfAgreementDebt}만, 제외채무=${pdfExcludedOtherDebt}만, 유예=${deferMonths}개월, 채권사=${pdfAgreementCreditors.size}건 ($fileName)")
    }

    // ============= 변제계획안 Gemini Vision =============
    private fun callGeminiVisionForRecoveryPlan(bitmaps: List<Bitmap>, fileName: String) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        val prompt = """이 PDF 이미지들은 개인회생 변제계획안입니다. 다음 정보를 추출하세요.

[1] 대상채무 총액 (원 단위)
- "채권자별 변제예정액의 산정내역" 테이블에서 "총계" 행의 "개인회생채권액" (확정채권액+미확정채권액 합산된 값)
- 또는 "개인회생채권 목록" 등의 테이블에서 "합계"/"총계" 행의 가장 큰 금액

[2] 월 변제금 (원 단위)
- "채무자의 가용소득" 테이블에서 "⑤ 월 실제 가용소득" 값 (③-④)
- 이것이 실제 월 변제금입니다
- "③ 월 평균 가용소득"이 아니라 "⑤ 월 실제 가용소득"을 추출하세요

[3] 변제기간 (개월 수)
- "변제기간" 섹션에서 "XX개월간" 값
- 또는 "⑥ 변제 횟수" 값

반드시 JSON만 응답:
{"totalDebt": 원단위숫자, "monthlyPayment": 월변제금원단위숫자, "repaymentMonths": 개월수숫자}"""

        val parts = JSONArray()
        parts.put(JSONObject().put("text", prompt))

        for (bmp in bitmaps) {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val imageBytes = baos.toByteArray()
            val base64Str = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
            val inlineData = JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", base64Str)
            parts.put(JSONObject().put("inlineData", inlineData))
        }
        Log.d("FILE_PROCESS", "변제계획안 Gemini: ${bitmaps.size}페이지 전송 ($fileName)")

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
            Log.e("FILE_PROCESS", "변제계획안 Gemini API 오류 ($responseCode): $errorBody")
            return
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
            Log.e("FILE_PROCESS", "변제계획안 Gemini 응답 파싱 실패: ${e.message}")
            return
        }

        Log.d("FILE_PROCESS", "변제계획안 Gemini AI 응답: $aiText")

        val jsonStr = aiText
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()
        val jsonStart = jsonStr.indexOf("{")
        val jsonEnd = jsonStr.lastIndexOf("}")
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            Log.e("FILE_PROCESS", "변제계획안 Gemini JSON 파싱 실패: $jsonStr")
            return
        }

        val data = JSONObject(jsonStr.substring(jsonStart, jsonEnd + 1))
        val totalDebt = data.optLong("totalDebt", 0L)
        val monthlyPayment = data.optLong("monthlyPayment", 0L)
        val repaymentMonths = data.optInt("repaymentMonths", 0)

        aiHasRecoveryPlan = true
        if (totalDebt > 10000) {
            pdfRecoveryDebt = (totalDebt / 10000).toInt()
        }
        if (monthlyPayment > 0) {
            pdfRecoveryIncome = (monthlyPayment / 10000).toInt()
        }
        if (repaymentMonths > 0) {
            pdfRecoveryMonths = repaymentMonths
        }

        Log.d("FILE_PROCESS", "변제계획안 Gemini 결과: 대상채무=${pdfRecoveryDebt}만, 월변제금=${pdfRecoveryIncome}만, 변제기간=${pdfRecoveryMonths}개월 ($fileName)")
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
        parseHwpData(text)
        showToast("HWP 파일 읽기 완료")
    }

    // ============= 핵심 파싱 로직 (AI값 기반) =============
    private fun parseHwpData(text: String) {
        val lines = text.split("\n")

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
        var hasJointCar = false  // 공동명의 차량 보유
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
        var savingsDeposit = 0  // 예적금 금액 (만원)
        var hasBunyangGwon = false  // 분양권 보유 여부 (재산 제외)
        var bunyangGwonNet = 0     // 분양권 순가치 (만원, 시세-대출)
        var jeonseNoJilgwon = false  // 전세대출 질권설정x → 대상채무 포함
        var excludedSeqNumbers = mutableSetOf<Int>()  // 대출과목에서 제외할 순번
        var includedSeqNumbers = mutableSetOf<Int>()  // 강제 포함할 순번 (순번N 신용대출)
        var hasOngoingProcess = false  // 다른 채무조정 진행 중
        var ongoingProcessName = ""   // 진행중인 제도명 (회/신/워)
        var isIncomeEstimated = false  // 소득 예상
        var estimatedIncomeParsed = 0  // HWP에서 파싱한 예정/예상 소득 (만원)
        var isIncomeX = false  // "월 소득 x" → 소득 없음 강제
        var parsedMonthlyIncome = 0  // HWP 텍스트에서 직접 파싱한 월소득 (만원)
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
        var tableDebtTotal = 0     // 표 전체 합계 (천원, 제외항목 포함)
        // 대출과목 파싱 (표 없을 때 대상채무 fallback)
        var inLoanCategorySection = false // 대출과목 섹션 진입 여부 (신복위 파싱 제외용)
        var inRegionField = false // 지역 필드 연속줄 감지용
        var regionOwnership = "" // 지역 첫줄에서 감지한 명의 ("공동","본인","배우자")
        var inPropertySection = false // 재산 필드 연속줄 감지용
        var inJobSection = false      // 재직 필드 연속줄 감지용
        var inIncomeSection = false   // 연봉 필드 연속줄 감지용
        var inSpecialNotesSection = false // 특이사항 필드 연속줄 감지용
        var inOtherDebtSection = false   // 기타채무 섹션 진입 여부
        var inCardUsageTableSection = false // 카드이용금액 테이블 섹션
        val cardUsageCreditors = mutableSetOf<String>() // 카드이용금액 테이블에서 감지한 카드사명
        val cardUsageAmountMap = mutableMapOf<String, Int>() // 카드이용금액 카드사별 금액 (만원)
        var textShinbokDebt = 0  // 대출과목에서 파싱한 신복위 채무 (만원, PDF 없을 때 사용)
        var textTaxDebt = 0      // 대출과목에서 파싱한 국세/세금 채무 (만원)
        val specialNotesList = ArrayList<String>()
        val recentDebtEntries = ArrayList<Pair<Calendar, Int>>() // (대출일, 금액_천원)
        var parsedPropertyTotal = 0  // 재산줄에서 파싱한 재산 합계 (만원)
        var parsedOthersProperty = 0  // 타인명의 재산 합계 (만원, ÷2 적용 후)
        var parsedDamboTotal = 0 // 표에서 파싱한 담보대출 합계 (만원)
        val parsedDamboCreditorNames = mutableSetOf<String>() // 담보로 제외된 채권사명
        var totalParsedDebt = 0  // 표에서 파싱한 모든 채무 합계 (만원, 담보 포함)
        val parsed0041Map = mutableMapOf<String, Int>()  // 현금서비스(0041) 카드사별 금액 (만원)
        val parsedCreditorMap = mutableMapOf<String, Int>()  // 대상채무 채권사 (이름→금액만원)
        var parsedCardUsageTotal = 0  // 기타채무 요약에서 파싱한 카드이용금액 합계 (만원)
        val debtDateAmountSeen = mutableMapOf<String, Boolean>() // "날짜_금액" → isGuarantee (보증채무 중복 제거용)
        val businessLoanDates = mutableMapOf<String, Int>() // 운전자금/개인사업자대출 날짜 → 금액(천원) (지급보증 중복 제거용)
        var recentDebtRatio = 0.0
        var postApplicationDebtMan = 0  // 신청일자 이후 추가채무 합계 (만원)
        var jiguBojungExcludedMan = 0  // 지급보증 중복 제외 금액 (만원)
        var guaranteeDebtMan = 0  // 지급보증 채무 합계 (만원, 대상채무 포함분)
        var daebuDebtMan = 0  // 대부 채무 합계 (만원)
        var cardCapitalDebtMan = 0  // 카드/캐피탈 채무 합계 (만원)
        // ★ 과반 비율: 대상채무 확정 후 계산
        var majorCreditorRatio = 0.0

        // ============= 사전 스캔: 운전자금 월 수집 → 같은 월 융자담보지보 스킵 =============
        val unjeonMonths = mutableSetOf<String>()  // 운전자금 년.월 (예: "2018.05")
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
        var preScanLoanCat = false
        for (rawLine in lines) {
            val l = rawLine.trim().replace(Regex("\\s"), "")
            // 대출과목 섹션 기반 스캔
            if (l.contains("대출과목") || (l.contains("현황순번") && l.contains("담보"))) preScanLoanCat = true
            // 종료 조건: "대출과목"이 같은 라인에 없을 때만 (병합 라인 "[요약][대출과목]" 대응)
            if (preScanLoanCat && !l.contains("대출과목") && !l.contains("현황순번") && (l.contains("요약사항") || l.contains("최저납부") || l.contains("요약]"))) preScanLoanCat = false
            if (preScanLoanCat && (l.contains("담보") || l.contains("할부") || l.contains("리스") || l.contains("중도금") || l.contains("약관") || l.contains("후순위"))) {
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
            // 패턴기반: "N + 채권사명 + 차량담보/차담보 등" (테이블 합쳐진 경우 대응, 영문/전각 대응)
            val damboM = Pattern.compile("(\\d{1,2})(?!년)([^\\d]{2,}?)(?:차량담보|차량할부|차량리스|자동차담보|차담보|집담보|중도금대출|중도금)").matcher(l)
            while (damboM.find()) {
                val seqNum = damboM.group(1)!!.toInt()
                if (seqNum in 1..30) {
                    excludedSeqNumbers.add(seqNum)
                    Log.d("HWP_PRESCAN", "패턴기반 담보 순번: $seqNum (${damboM.group(0)}) - ${rawLine.trim()}")
                }
            }
            // 학자금 취업 후 상환 순번 수집
            if (preScanLoanCat && l.contains("학자금") && l.contains("취업")) {
                val stuSeqM = Pattern.compile("(\\d{1,2})(?![억만원천년])[A-Za-z\uFF21-\uFF3A\uFF41-\uFF5A가-힣]").matcher(l)
                while (stuSeqM.find()) {
                    val seqNum = stuSeqM.group(1)!!.toInt()
                    if (seqNum in 1..30) {
                        studentLoanExcludedSeqs.add(seqNum)
                        Log.d("HWP_PRESCAN", "학자금 취업후상환 순번: $seqNum - ${rawLine.trim()}")
                    }
                }
            }
            // 디버깅: 차량담보/할부 키워드 포함 라인 로그
            if (l.contains("담보") || l.contains("할부") || l.contains("리스") || l.contains("중도금")) {
                Log.d("HWP_PRESCAN", "담보키워드 포함 라인: $l")
            }
        }
        Log.d("HWP_PRESCAN", "사전스캔 결과: excludedSeqNumbers=$excludedSeqNumbers, 학자금취업후상환=$studentLoanExcludedSeqs")

        // ============= 보조 정보 추출 =============
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val lineNoSpace = line.replace(Regex("\\s"), "")

            // 대출과목 섹션 감지 (조기 설정 → 차량/채무 파싱에서 제외용)
            if (lineNoSpace.contains("대출과목") || lineNoSpace.contains("[대출과목]")) {
                inLoanCategorySection = true
            }
            if (inLoanCategorySection && !lineNoSpace.contains("대출과목") && (lineNoSpace.contains("요약사항") || lineNoSpace.contains("최저납부") || lineNoSpace.contains("요약]"))) {
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

            // 지역 (연속줄 대응: 첫줄에 명의만 있고 둘째줄에 시세/대출이 있는 경우)
            if (lineNoSpace.contains("지역")) {
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
                        // 지역 배우자명의 시세/대출 파싱 → ÷2
                        val siseMatch = Pattern.compile("시세\\s*(\\d+억?\\s*\\d*천?\\s*\\d*만?)").matcher(line)
                        val loanMatch = Pattern.compile("대출\\s*(\\d+억?\\s*\\d*천?\\s*\\d*만?)").matcher(line)
                        val regionSise = if (siseMatch.find()) extractAmount(siseMatch.group(1)!!) else 0
                        val regionLoan = if (loanMatch.find()) extractAmount(loanMatch.group(1)!!) else 0
                        regionSpouseProperty = maxOf((regionSise - regionLoan) / 2, 0)
                        Log.d("HWP_PARSE", "지역 배우자명의 감지: 시세${regionSise}만 - 대출${regionLoan}만 → ÷2 = ${regionSpouseProperty}만")
                    }
                    // 월세 보증금 → 재산 (배우자명의면 ÷2)
                    if (lineNoSpace.contains("월세") && lineNoSpace.contains("보증금")) {
                        val bojung = extractAmountAfterKeyword(line, "보증금")
                        if (bojung > 0) {
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
                        val groups = Regex("\\([^)]+\\)").findAll(regionVal).toList()
                        for (group in groups) {
                            val g = group.value
                            if (g.contains("배우자") || g.contains("타인") || g.contains("부모")) continue
                            if (!g.contains("본인명의") && !g.contains("공동명의")) continue
                            val rawSise = extractAmountAfterKeyword(g, "시세").takeIf { it > 0 }
                                ?: extractAmountAfterKeyword(g, "공시지가").takeIf { it > 0 }
                            val bojungAmt = extractAmountAfterKeyword(g, "보증금")
                            val siseAmt = rawSise ?: bojungAmt.takeIf { it > 0 } ?: 0
                            val loanAmt = extractAmountAfterKeyword(g, "대출")
                            val deductBojung = if (rawSise != null && rawSise > 0) bojungAmt else 0
                            val net = maxOf(siseAmt - loanAmt - deductBojung, 0)
                            parsedPropertyTotal += net
                            Log.d("HWP_PARSE", "지역 본인명의 재산: 시세${siseAmt}만 - 대출${loanAmt}만 = ${net}만")
                        }
                    }
                }
            } else if (inRegionField && !lineNoSpace.contains("지역")) {
                // 지역 연속줄: 다른 필드 레이블이면 종료
                val isNextField = lineNoSpace.startsWith("재산") || lineNoSpace.startsWith("재직") ||
                        lineNoSpace.startsWith("소득") || lineNoSpace.startsWith("연봉") ||
                        lineNoSpace.startsWith("결혼") || lineNoSpace.startsWith("차량") ||
                        lineNoSpace.startsWith("보험") || lineNoSpace.startsWith("채무") ||
                        lineNoSpace.startsWith("특이") || lineNoSpace.startsWith("연락")
                if (isNextField) {
                    inRegionField = false
                } else if (lineNoSpace.contains("시세") || lineNoSpace.contains("공시지가") || lineNoSpace.contains("보증금")) {
                    // 첫줄 명의 유지 ("본인명의 대출"은 소유형태가 아니라 대출주체)
                    val lineOwnership = regionOwnership
                    val rawSise = extractAmountAfterKeyword(line, "시세").takeIf { it > 0 }
                        ?: extractAmountAfterKeyword(line, "공시지가").takeIf { it > 0 }
                    val bojungAmt = extractAmountAfterKeyword(line, "보증금")
                    val siseAmt = rawSise ?: bojungAmt.takeIf { it > 0 } ?: 0
                    val loanAmt = extractAmountAfterKeyword(line, "대출")
                    val deductBojung = if (rawSise != null && rawSise > 0) bojungAmt else 0
                    val net = maxOf(siseAmt - loanAmt - deductBojung, 0)
                    when (lineOwnership) {
                        "공동" -> {
                            val half = net / 2
                            parsedPropertyTotal += half
                            Log.d("HWP_PARSE", "지역 연속줄 공동명의: 시세${siseAmt}만 - 대출${loanAmt}만 = ${net}만 → ÷2 = ${half}만")
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
                    inRegionField = false // 시세 처리 후 종료
                }
            }

            // 재산 필드 파싱 (연속줄 지원)
            if (lineNoSpace.startsWith("재산") && !lineNoSpace.contains("채무") && !lineNoSpace.contains("대출과목")) {
                inPropertySection = true
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
                val propertyVal = if (lineNoSpace.startsWith("재산")) extractValue(line, "재산") else line.trim()
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
                        val isSpouseOwned = g.contains("배우자")
                        val isOthers = isSpouseOwned || g.contains("타인") || g.contains("부모") || g.contains("형제") || g.contains("상대방")
                        // 배우자 명의 재산은 재산에 포함하지 않음
                        if (isSpouseOwned) {
                            Log.d("HWP_PARSE", "재산 배우자명의 제외: $g")
                            continue
                        }
                        val rawSise = extractAmountAfterKeyword(g, "시세").takeIf { it > 0 }
                            ?: extractAmountAfterKeyword(g, "공시지가").takeIf { it > 0 }
                        val bojungAmt = extractAmountAfterKeyword(g, "보증금")
                        val siseAmt = rawSise
                            ?: bojungAmt.takeIf { it > 0 }
                            ?: extractAmount(g)
                        val loanAmt = extractAmountAfterKeyword(g, "대출")
                        val seipjaAmt = extractAmountAfterKeyword(g, "세입자")
                        // 시세가 있으면 보증금 차감, 보증금만 있으면 보증금이 재산가치
                        val deductBojung = if (rawSise != null && rawSise > 0) bojungAmt else 0
                        val net = maxOf(siseAmt - loanAmt - seipjaAmt - deductBojung, 0)
                        val appliedNet = if (isOthers) net / 2 else net
                        parsedPropertyTotal += appliedNet
                        if (isOthers) parsedOthersProperty += appliedNet
                    }
                    if (groups.isEmpty()) {
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
                    // 배우자 공동명의 → 등본분리 가능 → 타인명의로 처리, 단기 시 집경매 위험
                    hasOthersRealEstate = true
                    hasSpouseCoOwned = true
                } else if (lineNoSpace.contains("본인명의") && lineNoSpace.contains("전세")) {
                    // 본인명의 전세: 질권설정 되어있으면 집경매 위험
                    val hasJilgwon = lineNoSpace.contains("질권") && !lineNoSpace.matches(Regex(".*질권설?정?[0xX없].*"))
                    if (hasJilgwon) hasOwnRealEstate = true
                } else if (!lineNoSpace.contains("전세") && !lineNoSpace.contains("월세") &&
                    !(lineNoSpace.contains("토지") && !lineNoSpace.contains("건물") && !lineNoSpace.contains("아파트"))) {
                    // 전세/월세는 본인 소유 부동산이 아님, 토지만(건물/아파트 없음)은 집경매 위험 아님
                    hasOwnRealEstate = true
                }
            }

            // 재직/직업 (연속줄 지원)
            if (lineNoSpace.contains("재직")) inJobSection = true
            if (inJobSection) {
                val job = if (lineNoSpace.contains("재직")) extractValue(line, "재직") else line.trim()
                if (job.contains("사업자") || job.contains("개인사업") || job.contains("자영업") || job.contains("음식점")) {
                    isBusinessOwner = true
                    if (!job.contains("폐업")) {
                        businessEndYear = 0
                    }
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
                            }
                        }
                        // "개인사업자(17년)" 형식: 괄호 안 연도만 있고 "개업" 키워드 없는 경우
                        if (businessStartYear == 0) {
                            val bizParenPattern = Pattern.compile("사업자\\s*\\(\\s*(\\d{2,4})년").matcher(line)
                            if (bizParenPattern.find()) {
                                val y = bizParenPattern.group(1)!!.toInt()
                                businessStartYear = if (y < 100) 2000 + y else y
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
                    if (lineNoSpace.contains("사업자") || lineNoSpace.contains("개인사업") || lineNoSpace.contains("자영업")) {
                        isBusinessOwner = true
                    }
                    Log.d("HWP_PARSE", "사업자 이력 감지: $line (개업=$businessStartYear, 폐업=$businessEndYear)")
                }
            }

            // ★ 비영리 단체 감지 (전역 - hasBusinessHistory와 무관하게 항상 감지)
            if (!isNonProfit && (lineNoSpace.contains("비영리") || lineNoSpace.contains("노동조합") || lineNoSpace.contains("종교") || lineNoSpace.contains("교회") || lineNoSpace.contains("사찰") || lineNoSpace.contains("재단법인") || lineNoSpace.contains("사단법인") || lineNoSpace.contains("협회") || lineNoSpace.contains("복지"))) {
                isNonProfit = true
                Log.d("HWP_PARSE", "비영리 단체 감지: $line")
            }

            // 도박/주식/코인 (변제율 조건) - 사용처 줄 외에도 감지
            if (lineNoSpace.contains("도박")) { hasGambling = true }
            if ((lineNoSpace.contains("주식") && !lineNoSpace.contains("주식회사")) || lineNoSpace.contains("전액주식")) { hasStock = true }
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

            // 경매 감지 (장기 불가 조건) - 상대방/타인 재산 경매는 제외, "경매x/X" = 경매 없음
            val isOthersContext = lineNoSpace.contains("상대방") || lineNoSpace.contains("타인") || lineNoSpace.contains("상대측")
            val auctionNegated = lineNoSpace.contains("경매x", ignoreCase = true) || lineNoSpace.contains("경매없") || lineNoSpace.contains("경매결과") || lineNoSpace.contains("통보")
            if (lineNoSpace.contains("경매") && !isOthersContext && !auctionNegated) {
                hasAuction = true
                Log.d("HWP_PARSE", "경매 감지: $line")
            } else if (lineNoSpace.contains("경매") && (isOthersContext || auctionNegated)) {
                Log.d("HWP_PARSE", "경매 감지 제외 (${if (auctionNegated) "경매x" else "상대방/타인 재산"}): $line")
            }
            // 압류/강제집행 감지 (회워, 회생 불가시 방생) - 상대방/타인 재산 가압류는 제외, "압류x/X" = 압류 없음
            val seizureNegated = lineNoSpace.contains("압류x", ignoreCase = true) || lineNoSpace.contains("압류없") || lineNoSpace.contains("예정")
            if ((lineNoSpace.contains("압류") || lineNoSpace.contains("강제집행")) && !isOthersContext && !seizureNegated) {
                hasSeizure = true
                Log.d("HWP_PARSE", "압류 감지: $line")
            } else if ((lineNoSpace.contains("압류") || lineNoSpace.contains("강제집행")) && (isOthersContext || seizureNegated)) {
                Log.d("HWP_PARSE", "압류 감지 제외 (${if (seizureNegated) "압류x" else "상대방/타인 재산"}): $line")
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
            // ★ "순수익"이 명시되어 있으면 순수익 값 우선 사용
            if ((lineNoSpace.contains("월소득") || lineNoSpace.contains("연봉") || lineNoSpace.contains("순수익")) && !lineNoSpace.contains("배우자") && !lineNoSpace.contains("예상") && !lineNoSpace.contains("예정") && !inSpecialNotesSection) {
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
                        if (netMax > 0) {
                            parsedMonthlyIncome += netMax
                            // 국민연금 등 추가 소득 합산
                            val pensionMatch = Regex("(?:국민연금|기초연금|장애연금|노령연금)\\s*(\\d+)\\s*만").find(line)
                            if (pensionMatch != null) {
                                val pensionAmount = pensionMatch.groupValues[1].toInt()
                                parsedMonthlyIncome += pensionAmount
                                Log.d("HWP_PARSE", "월 순수익 직접 파싱: ${netMax}만 + 연금 ${pensionAmount}만 = ${parsedMonthlyIncome}만 ($line)")
                            } else {
                                Log.d("HWP_PARSE", "월 순수익 직접 파싱: ${netMax}만 ($line)")
                            }
                        }
                    } else {
                        // 괄호 안 금액 추출 (예: "연봉 5200만(369만)" → 369)
                        val parenMatch = Regex("\\((\\d+)만\\)").find(line)
                        val parenIncome = parenMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        // "/" 또는 "," 뒤 월 소득 (예: "/ 월 소득 330만", "연 3000만(226만), 월 250만")
                        val afterSlash = if (line.contains("/")) line.substringAfter("/")
                            else if (line.contains(",")) line.substringAfter(",")
                            else ""
                        val slashRangeMatch = if (afterSlash.isNotEmpty()) Regex("(\\d+)\\s*[~～\\-]\\s*(\\d+)\\s*만").find(afterSlash) else null
                        val slashIncomeMatch = if (afterSlash.isNotEmpty()) Regex("(\\d+)\\s*만").find(afterSlash) else null
                        if (slashRangeMatch != null) {
                            val slashParsed = maxOf(slashRangeMatch.groupValues[1].toInt(), slashRangeMatch.groupValues[2].toInt())
                            val parsed = maxOf(slashParsed, parenIncome)
                            if (parsed > 0) {
                                parsedMonthlyIncome = parsed
                                Log.d("HWP_PARSE", "/ 뒤 월소득 범위 파싱: 슬래시=${slashParsed}만, 괄호=${parenIncome}만 → ${parsed}만 ($line)")
                            }
                        } else if (slashIncomeMatch != null) {
                            val slashParsed = slashIncomeMatch.groupValues[1].toInt()
                            val parsed = maxOf(slashParsed, parenIncome)
                            if (parsed > 0) {
                                parsedMonthlyIncome = parsed
                                Log.d("HWP_PARSE", "/ 뒤 월소득 파싱: 슬래시=${slashParsed}만, 괄호=${parenIncome}만 → ${parsed}만 ($line)")
                            }
                        } else {
                            // "월 소득 200~220만" → 큰 값(220) 사용
                            val rangeMatch = Regex("월\\s*소득[^\\d]*(\\d+)\\s*[~～\\-]\\s*(\\d+)\\s*만").find(line)
                            val monthlyMatch = Regex("월\\s*소득[^\\d]*(\\d+)\\s*만").find(line)
                            // 값에 "연봉"이 포함되면 연봉 금액 → *0.85/12로 월소득 변환 (예: "연봉 5300만")
                            val annualMatch = if (lineNoSpace.contains("연봉")) Regex("연봉\\s*(\\d+)\\s*만").find(line) else null
                            if (rangeMatch != null) {
                                val val1 = rangeMatch.groupValues[1].toInt()
                                val val2 = rangeMatch.groupValues[2].toInt()
                                val parsed = maxOf(val1, val2)
                                if (parsed > 0) {
                                    parsedMonthlyIncome += parsed
                                    Log.d("HWP_PARSE", "월소득 범위 파싱: ${val1}~${val2} → ${parsed}만 → 합계=${parsedMonthlyIncome}만 ($line)")
                                }
                            } else if (monthlyMatch != null) {
                                val parsed = monthlyMatch.groupValues[1].toInt()
                                if (parsed > 0) {
                                    parsedMonthlyIncome += parsed
                                    Log.d("HWP_PARSE", "월소득 직접 파싱: ${parsed}만 → 합계=${parsedMonthlyIncome}만 ($line)")
                                }
                            } else if (annualMatch != null) {
                                val annualSalary = annualMatch.groupValues[1].toInt()
                                val parsed = (annualSalary * 0.85 / 12).toInt()
                                if (parsed > 0) {
                                    parsedMonthlyIncome += parsed
                                    Log.d("HWP_PARSE", "연봉→월소득 변환: 연봉${annualSalary}만 × 0.85 ÷ 12 = ${parsed}만 → 합계=${parsedMonthlyIncome}만 ($line)")
                                }
                            }
                        }
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
                        parsedMonthlyIncome += extra
                        Log.d("HWP_PARSE", "연봉 연속줄 소득 추가: ${extra}만 → 합계=${parsedMonthlyIncome}만 ($line)")
                    }
                }
            }

            // 소득 예상/예정 감지 + 금액 추출 (실효예정 등 무관한 문맥 제외)
            if ((lineNoSpace.contains("예상") || lineNoSpace.contains("예정")) && !lineNoSpace.contains("실효")) {
                val hasIncomeMonth = Regex("(?<!\\d)월").containsMatchIn(lineNoSpace)  // "4월" 등 날짜 월 제외
                if (lineNoSpace.contains("소득") || lineNoSpace.contains("연봉") || lineNoSpace.contains("월급") || hasIncomeMonth) {
                    isIncomeEstimated = true
                    // "월 350만 예정", "월 350만 예상", "예상 260만" 등에서 금액 추출
                    val incomeMatch = Regex("월\\s*(\\d+)만\\s*(?:예정|예상)").find(line)
                        ?: Regex("(\\d+)만\\s*(?:예정|예상)").find(line)
                        ?: Regex("(?:예상|예정)\\s*(\\d+)\\s*만").find(line)
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
            if (lineNoSpace.contains("주택금융공사")) {
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

            // 보험 대출 감지 (대상채무 제외)
            if (lineNoSpace.contains("보험") && (lineNoSpace.contains("대출") || lineNoSpace.contains("약관"))) {
                hasInsurancePolicyLoan = true
                Log.d("HWP_PARSE", "보험대출 감지: $line")
            }

            // 전세대출 질권설정 없음 감지 → 대상채무 포함
            if (lineNoSpace.contains("질권설정x") || lineNoSpace.contains("질권설정X") ||
                        lineNoSpace.contains("질권x") || lineNoSpace.contains("질권X") ||
                        lineNoSpace.contains("질권설정안") ||
                        line.contains("질권설정 x") || line.contains("질권 x") || line.contains("질권설정 안")) {
                jeonseNoJilgwon = true
                Log.d("HWP_PARSE", "전세대출 질권설정 없음 감지 → 대상채무 포함: $line")
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
                    // 입금받음 → 수급 (본인 양육)
                    if (lineNoSpace.contains("입금받") || lineNoSpace.contains("입금")) {
                        childSupportReceiving = true
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
                val content = if (lineNoSpace.contains("특이사항") || lineNoSpace.contains("특이:") || lineNoSpace.contains("특이："))
                    extractValue(line, "특이사항") else line.trim()
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
                    // 완납 채권사 감지 (예: "국민카드 완납", "삼성카드 완납함")
                    if (content.contains("완납")) {
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
                                trimmed.contains("건강보험")
                        if (trimmed.isNotEmpty() && !isAutoDetected) specialNotesList.add(trimmed)
                    }
                }
            }

            // 채무조정 이력 (채무조정: 줄에서 연도 추출)
            if (lineNoSpace.contains("채무조정")) {
                val content = extractValue(line, "채무조정")
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
                        if (content.contains("워크아웃") || content.contains("워크") || content.contains("신복위") || content.contains("신용회복")) {
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
                    // ★ 전체 내용에 완납/면책 등 결과가 있으면 진행중 아님
                    val contentHasResult = content.contains("면책") || content.contains("면채") || content.contains("실효") ||
                            content.contains("폐지") || content.contains("기각") || content.contains("취하") || content.contains("완납")
                    if (!contentHasResult) {
                        val segments = content.split("/", "／", ",", "，").map { it.trim() }
                        for (seg in segments) {
                            val segNoSpace = seg.replace("\\s+".toRegex(), "")
                            // "회생", "워크아웃" 등 제도명만 단독으로 적힌 경우 무시
                            if (segNoSpace == "회생" || segNoSpace == "워크아웃" || segNoSpace == "신복위" || segNoSpace == "신속") continue
                            val segProcess = seg.contains("회생") || seg.contains("워크") ||
                                    seg.contains("신복") || seg.contains("신속") || seg.contains("진행") ||
                                    seg.contains("접수")
                            if (segProcess) {
                                delinquentDays = maxOf(delinquentDays, 1095)
                                hasOngoingProcess = true
                                Log.d("HWP_PARSE", "제도 진행중 결과 없음 → 장기연체자: $seg")
                                break
                            }
                        }
                    }
                }
            }

            // 차량 파싱 (재산은 AI가 처리하지만, 차량 대수/처분 판단용) - 대출과목 섹션 제외
            val wasCarLine: Boolean
            val isDebtEntryLine = Pattern.compile("\\d{2}년\\s*\\d{1,2}월\\s*\\d{1,2}일").matcher(lineNoSpace).find()
            var isCarLine = !isDebtEntryLine && !inLoanCategorySection && (lineNoSpace.contains("차량") || (line.contains("자동차") && !lineNoSpace.contains("자동차금융") && !lineNoSpace.contains("자동차담보")) ||
                    (Pattern.compile("\\d{2}년").matcher(lineNoSpace).find() &&
                            (lineNoSpace.contains("시세") || lineNoSpace.contains("본인명의") || lineNoSpace.contains("배우자명의"))))

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
            wasCarLine = isCarLine

            if (isCarLine) {
                if (lineNoSpace.contains("장기렌트") || lineNoSpace.contains("렌트") || lineNoSpace.contains("리스")) {
                    Log.d("HWP_PARSE", "장기렌트/리스 감지 (차량 개수/처분 제외): $line")
                    continue
                }
                if (line.contains(": x") || line.contains(":x") || line.endsWith(": x") ||
                    (line.contains("\t") && line.substringAfter("\t").trim().equals("x", true))) continue
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
                if (lineNoSpace.contains("담보") || lineNoSpace.contains("대출")) {
                    carLoan = extractAmountAfterKeyword(lineNoSpace, "담보")
                    carLoan = maxOf(carLoan, extractAmountAfterKeyword(lineNoSpace, "대출"))
                }
                // 시세/대출/월납 모두 없으면 실제 차량 아님 (라벨만 있는 경우 등)
                val monthlyM = Pattern.compile("월\\s*(\\d+)만").matcher(lineNoSpace)
                var carMonthly = 0
                if (monthlyM.find()) carMonthly = monthlyM.group(1)!!.toInt()
                if (carSise == 0 && carLoan == 0 && carMonthly == 0) continue

                val isJointCar = lineNoSpace.contains("공동명의") || lineNoSpace.contains("공동")
                val isSpouseCar = lineNoSpace.contains("배우자명의") || lineNoSpace.contains("배우자")
                if (isSpouseCar) {
                    // 배우자 명의 차량은 재산에 포함하지 않음
                    Log.d("HWP_PARSE", "배우자명의 차량 제외: $line")
                    continue
                }
                // 외제차 여부 판별
                val isForeignCar = foreignCarBrands.any { brand -> lineNoSpace.contains(brand, ignoreCase = true) }

                carTotalSise += carSise; carTotalLoan += carLoan
                carMonthlyPayment += carMonthly
                // 개별 차량 정보 저장: [시세, 대출, 월납부, 배우자(1/0), 외제(1/0)]
                carInfoList.add(intArrayOf(carSise, carLoan, carMonthly, if (isSpouseCar) 1 else 0, if (isForeignCar) 1 else 0))
                carCount++
                if (isJointCar) hasJointCar = true
                Log.d("HWP_PARSE", "차량 파싱: 시세=$carSise, 담보=$carLoan, 월납=$carMonthly, 배우자=$isSpouseCar, 공동=$isJointCar, 외제=$isForeignCar, 누적시세=$carTotalSise, 누적담보=$carTotalLoan")
            }

            // 차량 라인은 채무 파싱에서 제외 (중복 카운트 방지)
            if (wasCarLine) continue

            // 카드이용금액 테이블 섹션 감지 (채무 파싱 전에 체크)
            if (lineNoSpace.contains("카드사") && lineNoSpace.contains("이용금액")) {
                inCardUsageTableSection = true
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
            val dateMatcher2 = Pattern.compile("(\\d{2})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일?").matcher(line)
            val dateMatcher3 = Pattern.compile("(\\d{2})년\\s*(\\d{1,2})월").matcher(line)
            val dateMatcher4 = Pattern.compile("^(\\d{2})년\\s+").matcher(line)
            val dateMatcher5 = Pattern.compile("(?<!\\d)(20\\d{2})[.\\-](\\d{1,2})(?!\\d)").matcher(line)
            val dateMatcher6 = Pattern.compile("(?<!\\d)(20\\d{2})(?!\\d)").matcher(line)

            if (!inCardUsageTableSection) {
                if (dateMatcher.find()) {
                    loanYear = dateMatcher.group(1)!!.toInt(); loanMonth = dateMatcher.group(2)!!.toInt(); loanDay = dateMatcher.group(3)!!.toInt()
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
            if (loanYear == 0 && !inOtherDebtSection && !inLoanCategorySection && !inPropertySection && !inRegionField && lineNoSpace.matches(Regex("^순?번?\\d{1,2}.+\\d+만.*"))) {
                val seqCreditorM = Pattern.compile("순?번?(\\d{1,2})\\s*(.+?)\\s+(\\d[\\d,]*)만").matcher(line.trim())
                if (seqCreditorM.find()) {
                    val fullMiddle = seqCreditorM.group(2)!!.trim()
                    val columns = fullMiddle.split(Regex("\\s+"))
                    // 컬럼이 4개 이상(구분/대출종류/기관명/발생일자)이면 3번째(기관명)만 추출
                    val credName = if (columns.size >= 4) columns[2] else fullMiddle
                    val loanType = if (columns.size >= 4) columns[1] else ""
                    val amountMan = seqCreditorM.group(3)!!.replace(",", "").toIntOrNull() ?: 0
                    if (loanType.contains("담보")) {
                        Log.d("HWP_PARSE", "채무현황 순번 파싱: 담보 제외 - $credName ${amountMan}만 - $line")
                    } else if (credName.length >= 2 && amountMan > 0) {
                        totalParsedDebt += amountMan
                        parsedCreditorMap[credName] = (parsedCreditorMap[credName] ?: 0) + amountMan
                        Log.d("HWP_PARSE", "채무현황 순번 파싱: $credName ${amountMan}만 - $line")
                    }
                }
            }

            if (loanYear > 0 && !inSpecialNotesSection && !inPropertySection && !inRegionField) {
                var debtAmount = 0
                if (line.contains("만") || line.contains("억")) {
                    debtAmount = extractAmount(line) * 10
                } else {
                    val commaM = Pattern.compile("([\\d,]+)$").matcher(line.trim())
                    if (commaM.find()) {
                        val numStr = commaM.group(1)!!.replace(",", "")
                        if (numStr.isNotEmpty()) debtAmount = numStr.toInt()
                    }
                }

                if (debtAmount > 0) {
                    // 채권사명 추출: 금융기관 키워드 포함 토큰 찾기
                    val creditorTokens = line.trim().split(Regex("\\s+"))
                    val creditorFinKws = listOf("은행", "캐피탈", "카드", "금융", "저축", "보증", "공사", "재단", "농협", "신협", "새마을", "생명", "화재", "공단", "대부", "뱅크", "피에프씨", "테크놀로지", "머니무브", "통신", "텔레콤", "모바일", "자산관리", "추심", "기금")
                    val creditorToken = creditorTokens.firstOrNull { tok -> creditorFinKws.any { tok.contains(it) } && !tok.contains("대출") }
                    val rawCreditorName = creditorToken?.replace(Regex("\\([^)]*\\)"), "")?.replace(Regex("[\\s]"), "")?.trim() ?: ""

                    // 담보대출 처리: 지급보증 제외한 담보대출은 담보로 계산 (대상채무 제외)
                    // 전세대출(270): 질권설정 없으면(jeonseNoJilgwon) 대상채무 포함, 있으면 담보
                    val isJeonse270 = (lineNoSpace.contains("전세") || lineNoSpace.contains("(270)")) && !jeonseNoJilgwon
                    // "기타담보대출(290)": 종류명의 "담보"는 무시, 대출과목에서 담보 순번이면 담보 처리
                    val is290 = lineNoSpace.contains("(290)") || lineNoSpace.contains("기타담보대출")
                    val lineFor담보 = if (is290) lineNoSpace.replace("기타담보대출", "").replace("기타담보", "") else lineNoSpace
                    // (290) 순번이 대출과목에서 담보로 확인된 경우
                    val rowSeqM = Pattern.compile("^(\\d{1,2})(?!년)[\\s가-힣]").matcher(line.trimStart())
                    val rowSeqNum = if (rowSeqM.find()) rowSeqM.group(1)!!.toInt() else -1
                    val is290DamboByCategory = is290 && rowSeqNum > 0 && excludedSeqNumbers.contains(rowSeqNum)
                    // 대출과목에서 담보/할부로 확인된 순번 → 담보 처리
                    val isDamboByPreScan = rowSeqNum > 0 && excludedSeqNumbers.contains(rowSeqNum)
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
                    val isInsurancePolicyLoan = loanTypeToken.contains("보험") || lineNoSpace.contains("(약관)") || lineNoSpace.contains("약관대출")
                    val isDamboLoan = ((lineFor담보.contains("담보") || is290DamboByCategory || is290DamboByPdf || isDamboByPreScan || lineNoSpace.contains("할부금융") || lineNoSpace.contains("리스") || lineNoSpace.contains("후순위") || lineNoSpace.contains("중고차할부") || lineNoSpace.contains("신차할부") || lineNoSpace.contains("(500)") || lineNoSpace.contains("(510)") || lineNoSpace.contains("시설자금") || lineNoSpace.contains("(1071)") || lineNoSpace.contains("중도금") || isJeonse270 || isInsurancePolicyLoan) && !lineNoSpace.contains("지급보증") && !lineNoSpace.contains("(240)") && !lineNoSpace.contains("무담보"))

                    // 지급보증(3021): 담보/비담보 전부 대상채무 제외
                    if (lineNoSpace.contains("(3021)")) {
                        Log.d("HWP_PARSE", "지급보증(3021) 제외: ${(debtAmount+5)/10}만 - $line")
                        continue
                    }

                    // 운전자금+지급보증 같은 날짜 중복 제거: 둘 중 높은 금액만 계산
                    // 융자담보지보 스킵 (운전자금 동월 → 같은 채무)
                    if (rowSeqNum > 0 && yungjaSkipSeqs.contains(rowSeqNum)) {
                        Log.d("HWP_PARSE", "융자담보지보 스킵 (운전자금 동월, 순번=$rowSeqNum): $line")
                        continue
                    }

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
                                if (rawCreditorName.length >= 2) parsedDamboCreditorNames.add(rawCreditorName)
                                if (isInsurancePolicyLoan) hasInsurancePolicyLoan = true
                                Log.d("HWP_PARSE", "담보대출 (대상채무 제외): $line")
                            }
                            // 현금서비스(0041) → 카드사별 집계
                            if (lineNoSpace.contains("(0041)") || lineNoSpace.contains("현금서비스")) {
                                val amountMan0041 = (debtAmount + 5) / 10
                                parsed0041Map[rawCreditorName] = (parsed0041Map[rawCreditorName] ?: 0) + amountMan0041
                                Log.d("HWP_PARSE", "현금서비스(0041): $rawCreditorName ${amountMan0041}만 - $line")
                            }

                            // parsedCreditorMap에 채권사 추가 (비담보만 - PDF 비교 및 과반 계산용)
                            if (rawCreditorName.length >= 2 && !isDamboLoan) {
                                val amountMan = (debtAmount + 5) / 10
                                parsedCreditorMap[rawCreditorName] = (parsedCreditorMap[rawCreditorName] ?: 0) + amountMan
                                // 변제율 판단용 채무 유형별 집계
                                val unjeonYearMonth = if (loanYear > 0 && loanMonth > 0) "${loanYear}.${loanMonth.toString().padStart(2, '0')}" else ""
                                if (lineNoSpace.contains("지급보증") || (lineNoSpace.contains("운전자금") && unjeonYearMonth in yungjaSkippedMonths)) guaranteeDebtMan += amountMan
                                if (rawCreditorName.contains("대부")) daebuDebtMan += amountMan
                                if (rawCreditorName.contains("카드") || rawCreditorName.contains("캐피탈")) cardCapitalDebtMan += amountMan
                            }

                            if (!isDamboLoan && hasFinancialKeyword) {
                                // 학자금 합산: 취업 후 상환만 대상채무에서 제외, 일반 상환은 포함
                                if (lineNoSpace.contains("학자금") || lineNoSpace.contains("(150)") || lineNoSpace.contains("장학재단")) {
                                    val isAfterEmployment = (rowSeqNum > 0 && studentLoanExcludedSeqs.contains(rowSeqNum)) || lineNoSpace.contains("취업")
                                    if (isAfterEmployment) {
                                        studentLoanTotal += debtAmount
                                        Log.d("HWP_PARSE", "학자금 취업후상환 (제외대상): ${(debtAmount+5)/10}만 순번=$rowSeqNum - $line")
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
                if (loanCatSeqM.find() && (lineNoSpace.contains("담보") || lineNoSpace.contains("할부") || lineNoSpace.contains("리스") || lineNoSpace.contains("약관"))) {
                    val seqNum = loanCatSeqM.group(1)!!.toInt()
                    excludedSeqNumbers.add(seqNum)
                    Log.d("HWP_PARSE", "대출과목 담보 순번 감지: 순번$seqNum - $line")
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
                    val cardAmountMatcher = Pattern.compile("(\\d[\\d,]*)만").matcher(lineNoSpace)
                    if (cardAmountMatcher.find()) {
                        val cardAmount = cardAmountMatcher.group(1)!!.replace(",", "").toIntOrNull() ?: 0
                        // 첫 번째 금액 앞까지만 카드명 (병합 테이블의 뒤쪽 데이터 제외)
                        val cardName = lineNoSpace.substring(0, cardAmountMatcher.start()).trim()
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
                        // 요약 라인(신용, 카드, 담보, 총액, 기타 등) 제외 → 실제 채권사만 저장
                        val summaryKeywords = listOf("신용", "카드", "담보", "총액", "기타", "합계")
                        val isSummary = summaryKeywords.any { creditorName.replace(Regex("[0-9.\\s]"), "").startsWith(it) }
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
            Log.d("HWP_CALC", "학자금: ${studentLoanMan}만 / 표전체${tableDebtMan}만 = ${String.format("%.1f", studentLoanRatio)}%")
        }

        // 합의서 PDF 진행중 제도 감지 (실효/폐지면 진행중 아님)
        if (pdfAgreementProcess.isNotEmpty() && !hasWorkoutExpired && !isDismissed) {
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

        // ★ 대상채무: 텍스트 파싱 기반 (AI 제거)
        // 현금서비스(0041) 카드사별 비교: 카드이용금액 > 채무현황 → 채무현황 제외, 아니면 둘 다 포함
        var deduct0041 = 0
        for ((creditorName, amount0041) in parsed0041Map) {
            // 카드이용금액 테이블에서 매칭되는 카드사 찾기
            val matchedCardUsage = cardUsageAmountMap.entries.firstOrNull { (cardName, _) ->
                creditorName.contains(cardName.replace("카드", "")) || cardName.contains(creditorName.replace("카드", "").take(2))
            }
            if (matchedCardUsage != null && matchedCardUsage.value > amount0041) {
                // 카드이용금액이 더 크면: 0041을 채무에서 제외하고 카드이용금액만 사용
                deduct0041 += amount0041
                Log.d("HWP_CALC", "0041 제외: $creditorName ${amount0041}만 < 카드이용 ${matchedCardUsage.key} ${matchedCardUsage.value}만")
            } else if (matchedCardUsage != null) {
                // 0041 >= 카드이용금액: 0041이 이미 카드이용을 포함하므로 카드이용금액 중복 제거
                parsedCardUsageTotal -= matchedCardUsage.value
                Log.d("HWP_CALC", "카드이용 제외(0041에 포함): $creditorName 0041=${amount0041}만 >= 카드이용 ${matchedCardUsage.key} ${matchedCardUsage.value}만, 카드합계→${parsedCardUsageTotal}")
            } else {
                Log.d("HWP_CALC", "0041 유지(카드이용 없음): $creditorName ${amount0041}만")
            }
        }
        val parsedTargetDebt = totalParsedDebt - parsedDamboTotal - deduct0041
        targetDebt = parsedTargetDebt + parsedCardUsageTotal
        Log.d("HWP_CALC", "대상채무 (텍스트파싱): 총${totalParsedDebt}-담보${parsedDamboTotal}-0041제외${deduct0041}+카드${parsedCardUsageTotal} = ${targetDebt}만 | 0041맵=$parsed0041Map 카드맵=$cardUsageAmountMap")

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

        // 합의서 제외 채무: HWP에 이미 포함된 금액이므로 별도 합산하지 않음
        // (제외 채권사 정보는 is290DamboByPdf로 담보 판단에만 활용)
        if (pdfExcludedGuaranteeDebt > 0 || pdfExcludedOtherDebt > 0) {
            Log.d("HWP_PARSE", "합의서 제외 채무 참고: 보증서=${pdfExcludedGuaranteeDebt}만, 담보=${pdfExcludedOtherDebt}만 (HWP에 이미 포함)")
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
            // PDF 채권사 먼저 추가
            for ((name, amount) in pdfAgreementCreditors) {
                mergedMap[name] = amount
            }
            // PDF 채권사 한글명 캐시
            val pdfNormalizedNames = mergedMap.keys.associate { it to normalizeCreditorName(it) }

            // 한글파일 채권사 중 PDF에 없는 것만 추가 (한글변환 이름+금액 비교)
            var overlappingAmount = 0  // HWP 채권사 중 PDF와 겹치는 금액
            for ((name, amount) in parsedCreditorMap) {
                val hwpNorm = normalizeCreditorName(name)
                val matchedPdf = mergedMap.entries.firstOrNull { (pdfName, pdfAmt) ->
                    val pdfNorm = pdfNormalizedNames[pdfName] ?: normalizeCreditorName(pdfName)
                    // 앞 4글자 매칭
                    (pdfNorm.contains(hwpNorm.take(4)) || hwpNorm.contains(pdfNorm.take(4)))
                    // 앞 3글자 매칭 + 금액 유사 (OCR 오타 대응: 드림인자 vs 드림인진자)
                    || (hwpNorm.length >= 3 && pdfNorm.length >= 3
                        && (pdfNorm.contains(hwpNorm.take(3)) || hwpNorm.contains(pdfNorm.take(3)))
                        && kotlin.math.abs(amount - pdfAmt) <= maxOf(pdfAmt, amount) * 0.3)
                }
                if (matchedPdf == null) {
                    mergedMap[name] = amount
                    Log.d("HWP_CALC", "한글 추가 채권사 (PDF에 없음): $name ${amount}만 (변환=$hwpNorm)")
                } else {
                    val pdfAmt = matchedPdf.value
                    val diff = kotlin.math.abs(amount - pdfAmt)
                    if (diff > maxOf(pdfAmt, amount) * 0.3 && diff > 50) {
                        mergedMap[name] = amount
                        Log.d("HWP_CALC", "한글 추가 채권사 (금액 차이): $name ${amount}만 vs PDF ${matchedPdf.key} ${pdfAmt}만")
                    } else {
                        overlappingAmount += amount
                        Log.d("HWP_CALC", "한글 채권사 스킵 (PDF에 있음): $name(${hwpNorm}) ${amount}만 ≈ ${matchedPdf.key} ${pdfAmt}만")
                    }
                }
            }
            parsedCreditorMap.clear()
            parsedCreditorMap.putAll(mergedMap)
            Log.d("HWP_CALC", "채권사 병합: PDF ${pdfAgreementCreditors.size}건 + 한글추가 → 총 ${parsedCreditorMap.size}건")

            // 합의서 대상채무 + 한글 대상채무 합산 (겹치는 금액 제외)
            if (pdfAgreementDebt > 0) {
                val hwpDebt = targetDebt
                targetDebt = hwpDebt + pdfAgreementDebt - overlappingAmount
                Log.d("HWP_PARSE", "합의서+한글 대상채무 합산: 한글=${hwpDebt}만 + 합의서=${pdfAgreementDebt}만 - 중복=${overlappingAmount}만 = ${targetDebt}만")
            }
        } else if (pdfAgreementDebt > 0) {
            // PDF 채권사 목록 없이 대상채무만 있는 경우
            val hwpDebt = targetDebt
            targetDebt = pdfAgreementDebt + hwpDebt
            Log.d("HWP_PARSE", "합의서+한글 대상채무 합산: 합의서=${pdfAgreementDebt}만 + 한글=${hwpDebt}만 = ${targetDebt}만")
        }

        // 변제계획안 대상채무 적용 (있으면 우선)
        if (pdfRecoveryDebt > 0 && pdfRecoveryDebt > targetDebt) {
            Log.d("HWP_CALC", "변제계획안 대상채무 적용: 한글=${targetDebt}만 → 변제계획안=${pdfRecoveryDebt}만")
            targetDebt = pdfRecoveryDebt
        }
        // 변제계획안 월변제금은 단기 결과에서 직접 적용 (소득이 아님)

        // ★ 미협약 판단 (AffiliateList 대조) - 텍스트 파싱 기반
        val loanTypeNames = setOf("신용대출", "신용", "카드", "카드대출", "담보", "담보대출", "대출", "기타", "기타대출", "총액", "국세", "지방세", "세금", "지급보증", "연대보증", "보증", "고소", "소송", "벌금", "물품대금")
        val nonFinancialKeywords = listOf("물품대금", "세금", "국세", "지방세", "공사대금", "대금", "급여", "임금", "월세", "보증금")
        val financialKeywords = listOf("은행", "카드", "캐피탈", "저축", "대부", "보험", "증권", "금융", "신협", "농협", "수협", "새마을", "조합", "재단", "보증", "자산관리", "추심", "생명", "화재", "손해", "상사", "할부", "여신", "리스", "펀드", "투자", "파이낸스", "캐시", "론", "크레디트", "신용정보", "상호", "공사", "에스비아이", "웰컴", "오케이", "페퍼", "제이티", "모아", "예스", "에이앤피")
        var nonAffiliatedDebt = 0
        val nonAffNames = mutableListOf<String>()
        val nonAffEntries = mutableListOf<Pair<String, Int>>()
        val otherDebtNameSet = otherDebtEntries.map { it.first }.toSet()
        for ((name, amount) in parsedCreditorMap) {
            if (loanTypeNames.contains(name)) {
                Log.d("HWP_CALC", "대출유형명 (미협약 판단 제외): $name (${amount}만)")
                continue
            }
            if (name in otherDebtNameSet) {
                Log.d("HWP_CALC", "기타채무 (미협약 판단 제외): $name (${amount}만)")
                continue
            }
            if (nonFinancialKeywords.any { name.contains(it) }) {
                Log.d("HWP_CALC", "비금융채무 (미협약 판단 제외): $name (${amount}만)")
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

        // ★ 과반 채권사 (parsedCreditorMap에서 담보/기타채무 제외 최대)
        val majorEntry = parsedCreditorMap.entries
            .filter { !parsedDamboCreditorNames.contains(it.key) }
            .filter { it.key !in otherDebtNameSet }
            .maxByOrNull { it.value }
        val majorCreditorFromParsing = if (majorEntry != null && (targetDebt + nonAffiliatedDebt) > 0 &&
            majorEntry.value.toDouble() / (targetDebt + nonAffiliatedDebt) > 0.5) majorEntry.key else ""
        val majorCreditorDebtFromParsing = if (majorCreditorFromParsing.isNotEmpty()) majorEntry!!.value else 0

        // ★ 채권사 수
        val parsedCreditorCount = parsedCreditorMap.size
        Log.d("HWP_CALC", "텍스트 파싱 채권사: ${parsedCreditorCount}건, 과반=${majorCreditorFromParsing}(${majorCreditorDebtFromParsing}만), 미협약=${nonAffiliatedDebt}만")

        // ★ 재산: 텍스트 파싱 기반 (AI 제거)
        val carPropertyValue = carInfoList.sumOf { maxOf(0, it[0] - it[1]) }
        if (parsedPropertyTotal > 0 || regionSpouseProperty > 0 || carPropertyValue > 0) {
            netProperty = parsedPropertyTotal + regionSpouseProperty + carPropertyValue
            Log.d("HWP_CALC", "재산 (텍스트파싱): 부동산${parsedPropertyTotal}만 + 배우자${regionSpouseProperty}만 + 차량${carPropertyValue}만 = ${netProperty}만")
        }

        // 단기용 재산: 예적금 포함, 등본 분리 미적용
        var originalNetProperty = netProperty

        // 예적금 제외 (장기 재산에만 적용, 단기는 포함)
        if (savingsDeposit > 0) {
            netProperty = netProperty - savingsDeposit
            if (netProperty < 0) netProperty = 0
            Log.d("HWP_CALC", "예적금 제외(장기): 재산 $originalNetProperty → ${netProperty}만 (예적금 ${savingsDeposit}만)")
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
        val targetDebtBeforeDisposal = targetDebt

        // 6개월 비율: 6개월 이내 채무 / 전체 채무 (처분 전 대상채무 + 담보대출)
        val totalDebtForRatio = maxOf(totalParsedDebt, targetDebtBeforeDisposal + parsedDamboTotal)
        recentDebtRatio = if (totalDebtForRatio > 0 && recentDebtMan > 0) recentDebtMan.toDouble() / totalDebtForRatio * 100 else 0.0

        // ★ 미협약 비율: 처분 반영된 대상채무 + 미협약 포함 기준
        val originalTargetDebt = targetDebt
        val nonAffiliatedOver20: Boolean
        if (nonAffiliatedDebt > 0) {
            val totalDebtAll = targetDebt + nonAffiliatedDebt + parsedDamboTotal
            val nonAffiliatedRatio = if (totalDebtAll > 0) nonAffiliatedDebt.toDouble() / totalDebtAll * 100 else 0.0
            nonAffiliatedOver20 = nonAffiliatedRatio >= 20
            val nonAffNamesStr = if (nonAffNames.isNotEmpty()) nonAffNames.joinToString(",") else ""
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
        val majorExcluded = setOf("신복위", "신복", "신용", "카드", "담보", "신용회복위원회")
        var effectiveMajorCreditor = majorCreditorFromParsing
        var effectiveMajorDebt = majorCreditorDebtFromParsing
        majorCreditorRatio = if (originalTargetDebt > 0 && effectiveMajorDebt > 0 && effectiveMajorCreditor !in majorExcluded) effectiveMajorDebt.toDouble() / originalTargetDebt * 100 else 0.0

        // 변제율 결정 (기본 85% = 15%탕감)
        var repaymentRate = 85
        var rateReason = ""
        val guaranteeDaebuTotal = guaranteeDebtMan + daebuDebtMan
        val guaranteeDaebuRatio = if (originalTargetDebt > 0) guaranteeDaebuTotal.toDouble() / originalTargetDebt * 100 else 0.0
        val cardCapitalRatio = if (originalTargetDebt > 0) cardCapitalDebtMan.toDouble() / originalTargetDebt * 100 else 0.0
        // 100%: 채무 4000만 이하
        if (originalTargetDebt <= 4000 && originalTargetDebt > 0) {
            repaymentRate = 100; rateReason = "소액"
        }
        // 100%: 1개 채권사 50% 이상
        else if (majorCreditorRatio >= 50) {
            repaymentRate = 100; rateReason = "과반"
        }
        // 100%: 지급보증+대부 합계 50% 초과
        else if (guaranteeDaebuRatio > 50) {
            repaymentRate = 100; rateReason = "지급보증/대부 과반"
        }
        // 80%: 대상채무 1억 이상 & 카드/캐피탈 50% 이상 (20%탕감)
        else if (originalTargetDebt >= 10000 && cardCapitalRatio >= 50) {
            repaymentRate = 80; rateReason = "카드/캐피탈 과반"
        }
        Log.d("HWP_CALC", "변제율: ${repaymentRate}% (${rateReason.ifEmpty { "기본15%탕감" }}), 지급보증+대부=${guaranteeDaebuTotal}만(${String.format("%.1f", guaranteeDaebuRatio)}%), 카드캐피탈=${cardCapitalDebtMan}만(${String.format("%.1f", cardCapitalRatio)}%)")

        // ★ 이혼 + 양육비 지급 (비수급) → 본인이 양육하지 않으므로 미성년 부양 인정 불가
        val childSupportDeduction: Int  // 단기 양육비 공제액
        if (isDivorced && childSupportAmount > 0 && !childSupportReceiving) {
            minorChildren = 0
            childSupportDeduction = childSupportAmount
            Log.d("HWP_CALC", "이혼+양육비 지급 → 1인 생계비, 양육비 ${childSupportAmount}만 공제")
        } else {
            childSupportDeduction = 0
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

        // ★ 세금: 텍스트 파싱 (AI 미사용)
        taxDebt = textTaxDebt
        if (taxDebt > 0) Log.d("HWP_CALC", "세금 (텍스트파싱): ${taxDebt}만")
        // 단기(회생)는 세금 포함
        val shortTermDebt = targetDebt + taxDebt
        if (taxDebt > 0) Log.d("HWP_CALC", "단기 대상채무: $targetDebt + 세금$taxDebt = ${shortTermDebt}만")

        if (dischargeWithin5Years) { shortTermBlocked = true; shortTermBlockReason = "면책 5년 이내" }
        if (originalNetProperty > shortTermDebt && shortTermDebt > 0) { shortTermBlocked = true; if (shortTermBlockReason.isNotEmpty()) shortTermBlockReason += ", "; shortTermBlockReason += "재산초과" }
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
            val propertyAfterCarSale = originalNetProperty  // 차량 순가치는 AI 재산에 이미 포함
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
                    val roundedSt = stMonthlyAdj
                    val minYears = Math.ceil(stMonths / 12.0).toInt()
                    val maxYears = Math.ceil(60 / 12.0).toInt()
                    shortTermAfterCarSale = "${roundedSt}만 ${minYears}~${maxYears}년납"
                    shortTermCarSaleApplied = true
                    Log.d("HWP_CALC", "차량 처분시 단기: 대상=$debtAfterCarSale, 재산=$propertyAfterCarSale, 월=$roundedSt, ${minYears}~${maxYears}년")
                } else if (income <= livingCostTable[1] && debtAfterCarSale > propertyAfterCarSale) {
                    // 소득 < 1인 생계비 → (대상-재산)÷36
                    val stByDebt = Math.ceil((debtAfterCarSale - propertyAfterCarSale).toDouble() / 36).toInt()
                    val roundedSt = stByDebt
                    shortTermAfterCarSale = "${roundedSt}만 3~5년납"
                    shortTermCarSaleApplied = true
                    Log.d("HWP_CALC", "차량 처분시 단기(소득<1인생계비): ($debtAfterCarSale-$propertyAfterCarSale)÷36=$stByDebt → ${roundedSt}만")
                }
            }
        }

        var shortTermMonthly = income - livingCostHoeseng - childSupportDeduction
        // 가구수 단계적으로 내려가며 적용 (3인→2인→1인)
        var shortTermHousehold = householdForHoeseng
        while (shortTermMonthly <= 0 && shortTermHousehold > 1) {
            shortTermHousehold--
            shortTermMonthly = income - livingCostTable[shortTermHousehold] - childSupportDeduction
        }
        if (shortTermHousehold != householdForHoeseng) {
            Log.d("HWP_CALC", "단기 생계비 조정: ${householdForHoeseng}인(${livingCostTable[householdForHoeseng]}) → ${shortTermHousehold}인(${livingCostTable[shortTermHousehold]}), 월변제금=$shortTermMonthly")
        }

        var shortTermMonths = 0
        var shortTermResult = ""

        val shortTermHardBlocked = dischargeWithin5Years || (originalNetProperty > shortTermDebt && shortTermDebt > 0)
        if (shortTermBlocked && shortTermHardBlocked) {
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
            val roundedShortTerm = shortTermMonthly
            shortTermResult = "${roundedShortTerm}만 / ${shortTermMonths}개월납"
            Log.d("HWP_CALC", "단기 40만 기준: 재산=${originalNetProperty}만, 월=${roundedShortTerm}만, ${shortTermMonths}개월")
        } else {
            // 소득 - 최저생계비 > 40: 일반 계산 (세금 포함)
            shortTermMonths = Math.round(shortTermDebt.toDouble() / shortTermMonthly).toInt()
            if (shortTermMonths > 60) shortTermMonths = 60
            if (shortTermMonths < 1) shortTermMonths = 1
            val roundedShortTerm = shortTermMonthly
            shortTermResult = "${roundedShortTerm}만 / ${shortTermMonths}개월납"
        }
        // 면책/재산초과 외 불가사유 → 계산 결과 + 사유 별도 표시
        if (shortTermBlocked && !shortTermHardBlocked && shortTermBlockReason.isNotEmpty() && !shortTermResult.contains("불가")) {
            shortTermResult = "$shortTermResult ($shortTermBlockReason)"
        }
        // 본인명의 집 또는 배우자 공동명의 집 → 집경매 위험 (재산초과 여부와 무관)
        if ((hasOwnRealEstate || hasSpouseCoOwned) && shortTermDebt > 0) {
            shortTermBlocked = true
            if (shortTermBlockReason.isNotEmpty()) shortTermBlockReason += ", "
            shortTermBlockReason += "집경매 위험"
            if (shortTermHardBlocked) {
                shortTermResult = "단기 불가 ($shortTermBlockReason)"
            } else {
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
        var longTermPropertyExcess = netPropertyAfterExemption > targetDebt && targetDebt > 0
        Log.d("HWP_CALC", "장기 재산초과 판단: ($netProperty - $exemptionAmount) = $netPropertyAfterExemption > $targetDebt → $longTermPropertyExcess")

        // 등본 분리: 재산초과이면서 배우자명의 시세가 있을 때만 적용
        val isRegistrySplit = if (longTermPropertyExcess && regionIsSpouseOwned && regionSpouseProperty > 0) {
            // 지역이 배우자명의 + 재산초과 → 배우자명의 재산 + 재산필드 타인명의 모두 제외
            netProperty = netProperty - regionSpouseProperty - parsedOthersProperty
            if (netProperty < 0) netProperty = 0
            netPropertyAfterExemption = maxOf(netProperty - exemptionAmount, 0)
            longTermPropertyExcess = netPropertyAfterExemption > targetDebt
            Log.d("HWP_CALC", "등본 분리 적용(배우자명의 + 재산초과): 재산 → $netProperty (배우자${regionSpouseProperty}만 + 타인${parsedOthersProperty}만 제외), 재산초과=$longTermPropertyExcess")
            true
        } else if (longTermPropertyExcess && !hasOwnRealEstate && hasOthersRealEstate && parsedOthersProperty > 0) {
            // 본인명의 없고 타인명의만 + 재산초과 → 전체 타인명의 재산 제외
            netProperty = netProperty - parsedOthersProperty
            if (netProperty < 0) netProperty = 0
            netPropertyAfterExemption = maxOf(netProperty - exemptionAmount, 0)
            longTermPropertyExcess = netPropertyAfterExemption > targetDebt
            Log.d("HWP_CALC", "등본 분리 적용(타인명의 + 재산초과): 재산 → $netProperty (타인${parsedOthersProperty}만 제외), 재산초과=$longTermPropertyExcess")
            true
        } else false

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
                // 1단계: 소득-생계비-부모 ≥ 45
                step1 >= 45 -> {
                    Log.d("HWP_CALC", "장기 1단계: 소득-생계비-부모=$step1")
                    step1
                }
                // 2단계: 부모 제외, 소득-생계비 ≥ 45
                step2 >= 45 -> {
                    Log.d("HWP_CALC", "장기 2단계: 부모 제외, 소득-생계비=$step2")
                    step2
                }
                // 3단계: 45만 고정
                else -> {
                    Log.d("HWP_CALC", "장기 3단계: 최소 45만 적용")
                    45
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

        var roundedLongTermMonthly = longTermMonthly

        // 보수 년수: 총변제금 ÷ 월변제금 ÷ 12 (소수점 .35 기준 반올림, 최소 1년, 최대 10년)
        if (roundedLongTermMonthly > 0) {
            val exactYears = totalPayment.toDouble() / roundedLongTermMonthly / 12.0
            longTermYears = if (exactYears - Math.floor(exactYears) >= 0.35) Math.ceil(exactYears).toInt() else Math.floor(exactYears).toInt()
            longTermYears = longTermYears.coerceIn(1, 10)
            // 월변제금 × 기간이 총변제금 초과 시 기간 축소 후 월변제금 재계산
            val maxYears = (totalPayment.toDouble() / (roundedLongTermMonthly * 12)).toInt().coerceAtLeast(1)
            if (longTermYears > maxYears) {
                longTermYears = maxYears
                roundedLongTermMonthly = totalPayment / (longTermYears * 12)
                longTermMonthly = roundedLongTermMonthly
            }
        }
        Log.d("HWP_CALC", "장기 보수: 총변제금=$totalPayment, 월변제금=$roundedLongTermMonthly, ${longTermYears}년")

        // 프리랜서: 기간 6년 고정, 월변제금 = 총변제금/72, 40만 이하면 40만 기준으로 기간 재계산
        if (isFreelancer && targetDebt > 0 && totalPayment > 0) {
            val freelancerMonthly = Math.ceil(totalPayment.toDouble() / 72).toInt()
            val roundedFreelancer = freelancerMonthly
            if (roundedFreelancer <= 45) {
                roundedLongTermMonthly = 45
                longTermMonthly = 45
                val exactYears = totalPayment.toDouble() / 45 / 12.0
                longTermYears = if (exactYears - Math.floor(exactYears) >= 0.35) Math.ceil(exactYears).toInt() else Math.floor(exactYears).toInt()
                longTermYears = longTermYears.coerceIn(3, 10)
                Log.d("HWP_CALC", "프리랜서 장기: 변제금 45만 고정 → ${longTermYears}년")
            } else {
                roundedLongTermMonthly = roundedFreelancer
                longTermMonthly = freelancerMonthly
                longTermYears = 6
                Log.d("HWP_CALC", "프리랜서 장기: 기간 6년 고정, 월변제금=${roundedLongTermMonthly}(총변제금÷72)")
            }
        }

        // 소득 기반 변제금으로 1년 이내 완납 가능 → 100% 변제, 최소 3년
        if (roundedLongTermMonthly > 0 && totalPayment < roundedLongTermMonthly * 12) {
            roundedLongTermMonthly = Math.ceil(targetDebt.toDouble() / 36).toInt()
            longTermYears = 3
            Log.d("HWP_CALC", "장기 1년 미만 → 100% 변제: ${roundedLongTermMonthly}만 / 3년납")
        }

        // 장기 채무 부족: 월변제금 < 45만이면 채무가 너무 적어 장기(신복위) 불가
        val longTermDebtInsufficient = roundedLongTermMonthly in 1 until 45
        if (longTermDebtInsufficient) Log.d("HWP_CALC", "장기 채무 부족: 월변제금=${roundedLongTermMonthly}만 < 45만")

        // 방생 판단은 공격 계산 후에

        // 장기(신복위) 공격 계산: 보수 월변제금 × 2/3
        var aggressiveYears = 0
        var roundedAggressiveMonthly = 0

        if (targetDebt > 0 && roundedLongTermMonthly > 0) {
            val aggressiveMonthly = Math.ceil(roundedLongTermMonthly * 2.0 / 3.0).toInt()
            roundedAggressiveMonthly = aggressiveMonthly
            if (roundedAggressiveMonthly < 45) roundedAggressiveMonthly = roundedLongTermMonthly

            // 공격 년수: 총변제금 ÷ 공격 월변제금 ÷ 12
            aggressiveYears = Math.ceil(totalPayment.toDouble() / roundedAggressiveMonthly / 12.0).toInt()
            if (aggressiveYears > 10) {
                // 10년 초과 시: 총변제금 ÷ 120 = 공격 월변제금
                roundedAggressiveMonthly = Math.ceil(totalPayment.toDouble() / 120).toInt()
                aggressiveYears = 10
                Log.d("HWP_CALC", "장기 공격 10년 초과 → 총변제금÷120=${roundedAggressiveMonthly}만/10년")
            }
        }
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
        val canApplySae = hasBusinessHistory && !(isFreelancer && !isBusinessOwner) && !isNonProfit && !isCorporateBusiness && !saeDebtOverLimit
        Log.d("HWP_CALC", "새새 조건: 사업이력=$hasBusinessHistory(AI), 실제연체=${actualDelinquentDays}일(전체=${delinquentDays}일), 프리랜서=$isFreelancer, 사업자=$isBusinessOwner, 비영리=$isNonProfit, 법인=$isCorporateBusiness, 개업=${businessStartYear}년${if (businessStartMonth > 0) "${businessStartMonth}월" else ""}, 폐업=$businessEndYear, 채무한도초과=$saeDebtOverLimit(담보=${totalSecuredDebt}만,무담보=${totalUnsecuredDebt}만)")
        var saeTotalPayment = 0; var saeMonthly = 0; var saeYears = 0
        if (canApplySae && targetDebt > 0) {
            saeTotalPayment = if (netProperty > targetDebt / 2) {
                targetDebt - ((targetDebt - netProperty) * 0.6).toInt()
            } else {
                (targetDebt * 0.7).toInt()
            }
            // 소득/대상채무 비율로 기간 결정
            val incomeRatio = income.toDouble() / targetDebt * 100
            saeYears = when {
                incomeRatio > 6 -> 5
                incomeRatio > 3 -> 8
                else -> 10
            }
            saeMonthly = saeTotalPayment / (saeYears * 12)
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
        val isMajorCreditorNonAffiliated = nonAffNames.any { effectiveMajorCreditor.contains(it) || it.contains(effectiveMajorCreditor) }
        if (originalTargetDebt > 0 && effectiveMajorCreditor.isNotEmpty() && majorCreditorRatio > 50 && !isMajorCreditorNonAffiliated) {
            specialNotesList.add("$effectiveMajorCreditor 과반 (${String.format("%.0f", majorCreditorRatio)}%)")
            Log.d("HWP_CALC", "과반 채권사 (AI): $effectiveMajorCreditor ${effectiveMajorDebt}만 / ${originalTargetDebt}만 = ${String.format("%.1f", majorCreditorRatio)}%")
        }
        specialNotesList.add("6개월 이내 ${String.format("%.0f", recentDebtRatio)}%")
        if (needsCarDisposal) {
            val dispMonthly = carInfoList.filter { shouldDisposeCar(it) }.sumOf { it[2] }
            specialNotesList.add("장기 시 차량처분 필요(시세${dispCarSise}만 / 담보${dispCarLoan}만 / 월납부${dispMonthly}만)")
        } else if (longTermCarBlocked && !longTermCarBlockedEffective) {
            specialNotesList.add("장기 시 차량처분 필요(시세${carTotalSise}만 / 담보${carTotalLoan}만 / 월납부${carMonthlyPayment}만)")
        }
        if (hasJointCar && !needsCarDisposal && !(longTermCarBlocked && !longTermCarBlockedEffective)) {
            specialNotesList.add("장기 시 차량처분 필요")
        }
        if (carCount >= 2) specialNotesList.add("차량 ${carCount}대 보유")
        // 사업자이력 년도 정보 추가 (새출발 가능기간 내 사업이력만 표시, 법인사업은 제외)
        val hasBizHistory = hasBusinessHistory && !isCorporateBusiness && !isNonProfit
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
        if (hasOwnRealEstate) specialNotesList.add("집경매 위험")
        if (hasSpouseCoOwned) specialNotesList.add("집경매 위험")
        if (hasAuction) specialNotesList.add("경매진행중")
        if (hasSeizure) specialNotesList.add("압류진행중")
        if (delinquentDays >= 90) specialNotesList.add("장기연체자")
        if (rateReason == "지급보증/대부 과반") specialNotesList.add("지급보증/대부 과반")
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
            val propertyAfterCarSale = netProperty  // 차량 순가치는 AI 재산에 이미 포함
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
        // ★ 단기 불가 (모든 사유) + 장기 채무한도초과 → 방생 (소득부족+채무한도초과 등)
        if (!isBangsaeng && shortTermBlocked && longTermDebtOverLimit && !canGetSae && targetDebt > 0) {
            isBangsaeng = true; bangsaengReason = "채무한도초과"
        }

        var shortTermTotal = if (!shortTermBlocked && shortTermMonthly > 0) shortTermMonthly * shortTermMonths else 0
        // 차량 처분 시 단기 총액
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
                val roundedSt = stByDebt
                shortTermTotal = roundedSt * 36
            }
        }
        // 청산가치 보장: 단기 총 변제금 < 재산이면 재산÷60 = 단기 월 변제금
        if (!shortTermBlocked && originalNetProperty > 0 && shortTermTotal > 0 && shortTermTotal < originalNetProperty) {
            val liquidationMonthly = Math.ceil(originalNetProperty.toDouble() / 60).toInt()
            val roundedLiquidation = liquidationMonthly
            shortTermMonthly = roundedLiquidation
            shortTermMonths = 60
            shortTermTotal = roundedLiquidation * 60
            shortTermResult = "${roundedLiquidation}만 / 60개월납"
            Log.d("HWP_CALC", "청산가치 보장 적용: 단기총액($shortTermTotal) < 재산($originalNetProperty) → 월${roundedLiquidation}만 × 60개월 = ${shortTermTotal}만")
        }
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
                csStep1 >= 45 -> csStep1
                csStep2 >= 45 -> csStep2
                else -> 45
            }
            if (csMonthly == 45) {
                val csStep3Years = Math.ceil(csTotalPayment.toDouble() / 45 / 12.0).toInt()
                if (csStep3Years > 10) {
                    csMonthly = Math.ceil(csTotalPayment.toDouble() / 120).toInt()
                }
            }
            val csRounded = csMonthly
            val csYears = if (csRounded > 0) Math.floor(csTotalPayment.toDouble() / csRounded / 12.0).toInt().coerceIn(1, 10) else 10
            carSaleLongTermTotal = csRounded * csYears * 12
            // 차량 처분 시 공격 총액
            val csAggressiveMonthly = Math.ceil(csRounded * 2.0 / 3.0).toInt()
            var csAggressiveRounded = csAggressiveMonthly
            if (csAggressiveRounded < 45) csAggressiveRounded = csRounded
            var csAggressiveYears = Math.ceil(csTotalPayment.toDouble() / csAggressiveRounded / 12.0).toInt()
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
            val csIsTowardConservative = hasStock || hasCrypto || majorCreditorRatio > 50 ||
                (csSurplus > 0 && csSurplus < targetDebt * 0.03) ||
                (recentDebtRatio >= 30 && recentDebtRatio < 50)
            val csIsTowardAggressive = (csSurplus > targetDebt * 0.03) || isFreelancer ||
                (shortTermTotal > 0 && shortTermTotal < longTermTotal - 1000)
            carSaleFinalYear = when {
                csIsConservative -> csYears
                csIsAggressive -> csAggressiveYears
                csIsTowardConservative -> Math.round((3.0 * csYears + csAggressiveYears) / 4.0).toInt()
                csIsTowardAggressive -> Math.round((csYears + 3.0 * csAggressiveYears) / 4.0).toInt()
                else -> Math.round((csYears + csAggressiveYears) / 2.0).toInt()
            }
            carSaleFinalYear = carSaleFinalYear.coerceIn(csYears, maxOf(csYears, csAggressiveYears))
            carSaleFinalMonthly = if (carSaleFinalYear > 0) csTotalPayment / (carSaleFinalYear * 12) else csRounded
            if (carSaleFinalMonthly < 45) carSaleFinalMonthly = 45
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
        val allDebtIsGuarantee = guaranteeDebtMan > 0 && guaranteeDebtMan >= targetDebt  // 모든 채권이 지급보증 → 장기 불가
        if (allDebtIsGuarantee) Log.d("HWP_CALC", "장기 불가: 모든 채권이 지급보증채무 (${guaranteeDebtMan}만/${targetDebt}만)")
        val longTermFullyBlocked = ((longTermPropertyExcess || longTermCarBlockedEffective) && !canLongTermAfterCarSale) || longTermDebtOverLimit || longTermBlockedByAuction || longTermDebtInsufficient || allDebtIsGuarantee

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

            // 최종 년수 결정 (5단계: 보수 → 보수방향 → 중간 → 공격방향 → 공격)
            val surplus = income - livingCostShinbok

            // 보수 그대로: 경매/압류/도박/최근50%+/장기연체/집처분
            val isConservative = hasAuction || hasSeizure || hasGambling ||
                recentDebtRatio >= 50 || delinquentDays >= 90 || hasOwnRealEstate

            // 공격 그대로: 소득 <= 생계비
            val isAggressive = income <= livingCostShinbok

            // 보수 방향 (3*보수+공격)/4: 주식/코인/소득<3%/최근30-50%/과반
            val isTowardConservative = hasStock || hasCrypto || majorCreditorRatio > 50 ||
                (surplus > 0 && surplus < targetDebt * 0.03) ||
                (recentDebtRatio >= 30 && recentDebtRatio < 50)

            // 공격 방향 (보수+3*공격)/4: 소득>3%/프리랜서/단기<장기-1000
            val isTowardAggressive = (surplus > targetDebt * 0.03) || isFreelancer ||
                (shortTermTotal > 0 && shortTermTotal < longTermTotal - 1000)

            val yearLabel: String
            finalYear = when {
                isConservative -> { yearLabel = "보수"; longTermYears }
                isAggressive -> { yearLabel = "공격"; aggressiveYears }
                isTowardConservative -> { yearLabel = "(3보수+공격)/4"; Math.round((3.0 * longTermYears + aggressiveYears) / 4.0).toInt() }
                isTowardAggressive -> { yearLabel = "(보수+3공격)/4"; Math.round((longTermYears + 3.0 * aggressiveYears) / 4.0).toInt() }
                else -> { yearLabel = "(보수+공격)/2"; Math.round((longTermYears + aggressiveYears) / 2.0).toInt() }
            }
            finalYear = finalYear.coerceIn(longTermYears, maxOf(longTermYears, aggressiveYears))

            // 최종 월변제금 계산: 최소 45만
            finalMonthly = if (finalYear > 0) totalPayment / (finalYear * 12) else 0
            if (finalMonthly < 45) {
                finalMonthly = 45
                // 최소 월변제금 × 기간이 총변제금 초과 시 기간 축소 후 월변제금 재계산
                val maxYears = (totalPayment.toDouble() / (45 * 12)).toInt().coerceAtLeast(1)
                if (finalYear > maxYears) {
                    finalYear = maxYears
                    finalMonthly = totalPayment / (finalYear * 12)
                }
            }

            Log.d("HWP_CALC", "최종 년수 계산: 보수=${longTermYears}년, 공격=${aggressiveYears}년, $yearLabel → finalYear=$finalYear, finalMonthly=${finalMonthly}만")
        }

        // 차량 처분시 단기 가능이면 단기 가능으로 판단
        val effectiveShortTermBlocked = shortTermBlocked && !shortTermCarSaleApplied
        val shortTermBlockedByDischarge = effectiveShortTermBlocked && dischargeWithin5Years
        val lowIncome = parsedMonthlyIncome <= 100  // 소득 100만 이하 → 회생 불가
        val hoeBlocked = shortTermBlockedByDischarge || shortTermDebtOverLimit || hasHfcMortgage || lowIncome  // 회(개인회생) 불가: 면책 or 채무한도초과 or 한국주택금융공사 or 소득100만이하
        Log.d("HWP_CALC", "회불가 판단: hoeBlocked=$hoeBlocked (면책단기=${shortTermBlockedByDischarge}, 채무한도=${shortTermDebtOverLimit}, 한국주택=${hasHfcMortgage}, 소득100이하=$lowIncome)")
        // 새새 연체 분기: 90일 이상 회새/새, 나머지 새새 (연체 없으면 회새 아님)
        val saeDiagnosis = when {
            actualDelinquentDays >= 90 || delinquentDays >= 90 -> if (hoeBlocked) "새" else "회새"
            else -> "새새"
        }

        val diagnosisAfterCarSale = when {
            !canLongTermAfterCarSale -> ""
            delinquentDays >= 90 -> if (dischargeWithin5Years) "워유워" else "회워"
            delinquentDays >= 30 -> if (hoeBlocked) "프유워" else "프회워"
            else -> if (hoeBlocked) "신유워" else "신회워"
        }



        val creditorCount = parsedCreditorCount
        Log.d("HWP_CALC", "채권사 수: $creditorCount (텍스트파싱)")
        val isShinbokSingleCreditor = creditorCount == 1 && (effectiveMajorCreditor.contains("신복") || effectiveMajorCreditor.contains("신복위"))
        if (creditorCount == 1 && !isShinbokSingleCreditor && !hasPdfFile) specialNotesList.add("채권사 1건")

        if (studentLoanRatio >= 50 && !longTermDebtOverLimit) {
            diagnosis = "단순 진행"
        } else if (shortTermDebt in 1..1500) {
            diagnosis = "방생"; diagnosisNote = "(소액)"
        } else if (creditorCount == 1 && !effectiveShortTermBlocked && !isShinbokSingleCreditor && !hasPdfFile && !longTermDebtOverLimit) {
            diagnosis = "단순유리"; diagnosisNote = "(채권사 1건, 개인회생 안내)"
        } else if (nonAffiliatedOver20 && (!longTermDebtOverLimit || effectiveShortTermBlocked)) {
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
        } else if (hasSeizure && !(effectiveShortTermBlocked && canGetSae)) {
            // 압류 → 장기 불가. 단기(회생)로 압류 해제 후 장기 가능 → 회워, 단기도 불가면 방생 (새새 가능하면 새새 분기로)
            diagnosis = if (!effectiveShortTermBlocked) "회워" else "방생"
        } else if (isBangsaeng) {
            if (canLongTermAfterCarSale && diagnosisAfterCarSale.isNotEmpty()) {
                diagnosis = diagnosisAfterCarSale
                diagnosisNote = "(차량 처분시 가능)"
            } else if (isRegistrySplit && !longTermPropertyExcess) {
                // 등본 분리시 재산초과 해소 → 장기 가능
                diagnosis = when {
                    delinquentDays >= 90 -> if (dischargeWithin5Years) "워유워" else "회워"
                    delinquentDays >= 30 -> if (hoeBlocked) "프유워" else "프회워"
                    else -> if (hoeBlocked) "신유워" else "신회워"
                }
                // [재산] 라인에서 이미 "(등본 분리 시)" 표시
            } else {
                diagnosis = "방생"
                if (bangsaengReason.isNotEmpty() && bangsaengReason != "재산초과") diagnosisNote = "($bangsaengReason)"
            }
        } else if (canApplySae && isBusinessOwner && (hasOngoingProcess && ongoingProcessName == "회" || isDismissed || hasWorkoutExpired)) {
            diagnosis = if (hoeBlocked) "새" else "회새"
        } else if (canApplySae && saeTotalPayment > 0 && netProperty <= 0) {
            // 새새 가능 + 재산 없으면 무조건 새새
            diagnosis = saeDiagnosis
        } else if (canApplySae && saeTotalPayment > 0) {
            val longTermFinalTotal = finalMonthly * finalYear * 12
            if (!effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && saeTotalPayment - shortTermTotal > 1000 && effectiveAggressiveTotal - shortTermTotal > 1000) {
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
                        delinquentDays >= 90 -> if (dischargeWithin5Years) "워유워" else "회워"
                        delinquentDays >= 30 -> "프유워"
                        else -> "신유워"
                    }
                }
            }
        } else if (hasShinbokwiHistory) {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked -> "회워"
                else -> when {
                    delinquentDays >= 90 -> if (dischargeWithin5Years) "워유워" else "회워"
                    delinquentDays >= 30 -> if (hoeBlocked) "프유워" else "프회워"
                    else -> if (hoeBlocked) "신유워" else "신회워"
                }
            }
        } else if (hasOngoingProcess) {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && effectiveAggressiveTotal - shortTermTotal > 1000 -> "단순유리"
                !effectiveShortTermBlocked -> "회워"
                else -> when {
                    delinquentDays >= 90 -> if (dischargeWithin5Years) "워유워" else "회워"
                    delinquentDays >= 30 -> if (hoeBlocked) "프유워" else "프회워"
                    else -> if (hoeBlocked) "신유워" else "신회워"
                }
            }
        } else if (!canDeferment) {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && effectiveAggressiveTotal - shortTermTotal > 1000 -> "단순유리"
                !effectiveShortTermBlocked -> "회워"
                else -> when {
                    delinquentDays >= 90 -> if (dischargeWithin5Years) "워유워" else "회워"
                    delinquentDays >= 30 -> if (hoeBlocked) "프유워" else "프회워"
                    else -> if (hoeBlocked) "신유워" else "신회워"
                }
            }
        } else if (delinquentDays >= 90) {
            diagnosis = when {
                isBangsaeng -> "방생"
                hasWorkoutExpired && !longTermDebtOverLimit -> "단순워크"
                !effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && effectiveAggressiveTotal - shortTermTotal > 1000 -> "단순유리"
                !effectiveShortTermBlocked && !longTermPropertyExcess -> "회워"
                dischargeWithin5Years -> "워유워"
                else -> "회워"
            }
        } else if (delinquentDays >= 30) {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && effectiveAggressiveTotal - shortTermTotal > 1000 -> "단순유리"
                recentDebtRatio >= 30 && !effectiveShortTermBlocked -> "프회워"
                targetDebt <= 4000 && !effectiveShortTermBlocked -> "프유워"
                !effectiveShortTermBlocked -> "프회워"
                else -> if (hoeBlocked) "프유워" else "프회워"
            }
        } else {
            diagnosis = when {
                isBangsaeng -> "방생"
                !effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && effectiveAggressiveTotal - shortTermTotal > 1000 -> "단순유리"
                recentDebtRatio >= 30 && !effectiveShortTermBlocked -> "신회워"
                targetDebt <= 4000 && !effectiveShortTermBlocked -> "신유워"
                !effectiveShortTermBlocked -> "신회워"
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
                // 10개월 이내 해소 → 회 계열로 변경 (신회워, 프회워, 워회워)
                val afterDate = "${dischargeYear + 5}.${String.format("%02d", if (dischargeMonth > 0) dischargeMonth else 1)}"
                specialNotesList.add("면책 5년 해소 ${afterDate} (10개월 이내)")
                if (!hasOngoingProcess && !shortTermDebtOverLimit && (diagnosis == "신유워" || diagnosis == "프유워" || diagnosis == "워유워")) {
                    diagnosis = when {
                        delinquentDays >= 90 -> "워회워"
                        delinquentDays >= 30 -> "프회워"
                        else -> "신회워"
                    }
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


        // 6개월 이내 비율 30% 미만 가능 날짜 계산 (방생/단순유리/단순진행 등 확정 진단 시 건너뜀)
        val isSaeDiagnosis = diagnosis == "새새" || diagnosis == "새" || diagnosis == "회새"
        if (isSaeDiagnosis) specialNotesList.removeAll { it.contains("차량처분 필요") }
        val skipRecentDateCalc = diagnosis == "방생" || diagnosis.startsWith("단순")
        // 6개월 30% 이상 → 가능일자 표기 (단기 가능 여부 무관하게 날짜 계산)
        if (recentDebtRatio >= 30 && recentDebtEntries.isNotEmpty() && targetDebt > 0 && !skipRecentDateCalc && !(effectiveShortTermBlocked && longTermFullyBlocked)) {
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
                    val afterDiag = if (isSaeDiagnosis) {
                        diagnosis  // 새출발 진단명 유지 (새새/회새/새)
                    } else when {
                        hoeBlocked || effectiveShortTermBlocked -> when {
                            delinquentDays >= 90 -> if (dischargeWithin5Years) "워유워" else "회워"
                            delinquentDays >= 30 -> "프유워"
                            else -> "신유워"
                        }
                        else -> when {
                            delinquentDays >= 90 -> "회워"
                            delinquentDays >= 30 -> "프회워"
                            else -> "신회워"
                        }
                    }
                    val immediatePrefix = if (!isSaeDiagnosis && effectiveShortTermBlocked && !longTermFullyBlocked && finalYear > 0 && !dischargeWithin5Years) {
                        "회워 바로 가능, "
                    } else if (!isSaeDiagnosis && !effectiveShortTermBlocked && !longTermDebtOverLimit && shortTermTotal > 0 && effectiveAggressiveTotal - shortTermTotal > 1000) {
                        ""  // 단순 바로 가능은 뒤에서 처리
                    } else {
                        ""
                    }
                    val immediateNote = if (!isSaeDiagnosis && !effectiveShortTermBlocked && !isIncomeEstimated && !longTermDebtOverLimit && shortTermTotal > 0 && effectiveAggressiveTotal - shortTermTotal > 1000) {
                        ", 단순 바로 가능"
                    } else if (!isSaeDiagnosis && !effectiveShortTermBlocked) {
                        ", 회워 바로 가능"
                    } else {
                        ""
                    }
                    diagnosis = "$immediatePrefix$afterDiag $possibleDate 이후 가능$immediateNote"
                    diagnosisNote = ""
                    Log.d("HWP_CALC", "6개월 30%미만 가능일: $possibleDate (남은 ${remainingMan}만/${targetDebt}만=${String.format("%.1f", newRatio)}%)")
                    break
                }
            }
        }

        // 단순유리 + 장기 가능 → 회워 바로 가능 + 연체별 날짜 추가
        // 단, 단기총액+1000 < 장기총액이면 단순유리 유지 (단기가 확실히 유리)
        // ★ 배우자 모르게, 소득 예정 등 단기 불가 조건 시 단기 우위 비교 무시 → 장기로 진행
        val practicalShortTermBlocked = spouseSecret || isIncomeEstimated
        if (diagnosis.startsWith("단순") && !longTermFullyBlocked && finalYear > 0
            && !(diagnosis == "단순유리" && !practicalShortTermBlocked && shortTermTotal > 0 && effectiveAggressiveTotal - shortTermTotal > 1000)) {
            // 면책 5년 이내 또는 배우자 모르게 등 실질 단기 불가 → 장기 진단 기반
            // 단, 대상채무 4000만 이하면 회생도 가능 (유→회)
            val useYu = dischargeWithin5Years || (practicalShortTermBlocked && targetDebt > 4000)
            diagnosis = if (dischargeWithin5Years || practicalShortTermBlocked) {
                when {
                    delinquentDays >= 90 -> if (dischargeWithin5Years) "워유워" else "회워"
                    delinquentDays >= 30 -> if (useYu) "프유워" else "프회워"
                    else -> if (useYu) "신유워" else "신회워"
                }
            } else "회워 바로 가능"
            // 6개월 30% 이상이면 신유회/프유회/워유회 날짜 추가
            if (recentDebtRatio >= 30 && recentDebtEntries.isNotEmpty() && targetDebt > 0) {
                val longDiag = when {
                    delinquentDays >= 90 -> "워유회"
                    delinquentDays >= 30 -> "프유회"
                    else -> "신유회"
                }
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
                        diagnosis += ", $longDiag $possibleDate 이후 가능"
                        break
                    }
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

        // UI 업데이트
        binding.name.text = "[이름] $name"
        val incomeSuffix = if (isIncomeEstimated) " (예정)" else ""
        binding.card.text = "[소득] ${income}만${incomeSuffix}"
        val studentLoanApplied = studentLoanMan > 0 && parsedIncludesStudentLoan
        val datSuffix = buildString {
            if (studentLoanApplied) append(" (학자금 제외)")
        }
        val datBase = targetDebtBeforeDisposal
        val datDisplayAmount = if (studentLoanApplied) maxOf(datBase - studentLoanMan, 0) else datBase
        binding.dat.text = "[대상] ${formatToEok(datDisplayAmount)}$datSuffix"
        binding.money.text = when {
            isRegistrySplit || regionIsSpouseOwned -> "[재산] ${formatToEok(netProperty)} (등본 분리 시)"
            else -> "[재산] ${formatToEok(netProperty)}"
        }

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
        val studentLoanShortSuffix = if (studentLoanApplied) " (학자금 포함)" else ""
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
            if (finalYear > 0 && finalMonthly > 0) {
                val studentLoanLongSuffix = if (studentLoanApplied) " (학자금 제외)" else ""
                longTermText.append("[장기] ${finalMonthly}만 / ${finalYear}년납$studentLoanLongSuffix")
            } else {
                longTermText.append("[장기] 장기 불가 (압류 진행중)")
            }
        } else if (longTermDebtInsufficient) {
            longTermText.append("[장기] 장기 불가 (채무 부족)")
        } else if (longTermFullyBlocked) {
            val blockedReason = if (allDebtIsGuarantee) "전액 지급보증"
            else if (longTermCarBlockedEffective) {
                val reasons = buildList {
                    if (carTotalSise >= 1000) add("시세${carTotalSise}만")
                    if (carCount >= 2) add("${carCount}대")
                    if (carMonthlyPayment >= 50) add("월납${carMonthlyPayment}만")
                }.joinToString("/")
                "차량($reasons)"
            } else "재산초과"
            longTermText.append("[장기] 장기 불가 ($blockedReason)")
        } else if (finalYear > 0 && finalMonthly > 0) {
            val studentLoanLongSuffix = if (studentLoanApplied) " (학자금 제외)" else ""
            longTermText.append("[장기] ${finalMonthly}만 / ${finalYear}년납$studentLoanLongSuffix")
        } else {
            val studentLoanLongSuffix = if (studentLoanApplied) " (학자금 제외)" else ""
            longTermText.append("[장기] ${roundedLongTermMonthly}만 / ${longTermYears}년납$studentLoanLongSuffix")
        }
        if (canApplySae && saeTotalPayment > 0) {
            longTermText.append("\n[새새] ${saeMonthly}만 / ${saeYears}년납")
        } else if (hasBusinessHistory && isBusinessOwner && saeDebtOverLimit) {
            longTermText.append("\n[새새] 새새 불가(채무한도초과 담보${formatToEok(totalSecuredDebt)})")
        }
        binding.test2.text = longTermText.toString()

        // 단순유리 + 새새 가능 + 새새-단기 <= 1000만 → 새새가 유리
        if (diagnosis == "단순유리" && canApplySae && saeTotalPayment > 0 && shortTermTotal > 0 && saeTotalPayment - shortTermTotal <= 1000) {
            diagnosis = saeDiagnosis
            Log.d("HWP_CALC", "단순유리→${saeDiagnosis}: 새새총액=${saeTotalPayment}만 - 단기총액=${shortTermTotal}만 = ${saeTotalPayment - shortTermTotal}만 <= 1000만")
        }

        // 본인명의 집 + 새새 가능 + 회생불가(면책/한국주택) → 새새 (집경매 위험만으로는 회 제거 안함)
        val longTermTotalForSaeCompare = finalMonthly * finalYear * 12
        if (hasOwnRealEstate && canApplySae && saeTotalPayment > 0 && diagnosis.contains("회") && saeTotalPayment <= longTermTotalForSaeCompare && hoeBlocked) {
            diagnosis = saeDiagnosis
            Log.d("HWP_CALC", "본인명의 집 + 회생불가 + 새새 가능 → ${saeDiagnosis} (새새${saeTotalPayment}만 <= 장기${longTermTotalForSaeCompare}만)")
        }

        // 단순유리 + 장기 가능 → 연체에 따라 신유회/프유회/워유회 (조건부)
        // 단, 단기 총액이 장기 총액보다 낮으면 단순유리 유지 (단기가 더 유리)
        else if (diagnosis == "단순유리" && !longTermFullyBlocked && !nonAffiliatedOver20 && finalYear > 0
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
                        if (suffix == "워" && !dischargeWithin5Years) {
                            // 유워 + 회워 둘 다 가능 → 회워
                            diagnosis = "회워"
                        } else {
                            diagnosis = "$diagnosis $possibleDate 이후 가능"
                        }
                        Log.d("HWP_CALC", "단순유리 변환 후 6개월 날짜: $possibleDate (남은 ${remainingMan2}만/${targetDebt}만=${String.format("%.1f", newRatio)}%)")
                        break
                    }
                }
            }
            // 6개월 30% 해당 안되더라도 면책 5년이내가 아닌 사유로 워 배정이면 → 회워
            if (suffix == "워" && !dischargeWithin5Years && !diagnosis.contains("회워")) {
                diagnosis = "회워"
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
            if (baseDiag.startsWith("신") || baseDiag.startsWith("프") || baseDiag.startsWith("워")) {
                baseDiag = baseDiag.substring(1)
            }
            if (aiDefermentMonths > 0) {
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

        // 신복/신복위 진행 중 + 추가채무 500만 초과 → "실효 후 1년 연체 필요"
        if (hasOngoingProcess && ongoingProcessName == "신" && postApplicationDebtMan > 500) {
            diagnosis += ", 실효 후 1년 연체 필요"
        }

        // 진단 앞에 다른 내용이 있으면 새새 → 새 (앞 내용이 있으므로 새 1개로 충분)
        if (!diagnosis.startsWith("새새") && diagnosis.contains("새새")) {
            diagnosis = diagnosis.replace("새새", "새")
        }

        // 중간 회 제거: 담보+신용 공존, 단기 최종 불가, 사업자 → 신회워→신유워, 프회워→프유워
        val hasDamboAndCredit = parsedDamboCreditorNames.any { it in parsedCreditorMap }
        val removeMiddleHoe = hasDamboAndCredit || effectiveShortTermBlocked || isBusinessOwner
        if (removeMiddleHoe) {
            if (diagnosis.contains("신회워") || diagnosis.contains("프회워")) {
                diagnosis = diagnosis.replace("신회워", "신유워").replace("프회워", "프유워")
                val reason = when {
                    hasDamboAndCredit -> "담보+신용"
                    effectiveShortTermBlocked -> "단기불가"
                    else -> "사업자"
                }
                Log.d("HWP_CALC", "중간 회 제거($reason): $diagnosis")
            }
        }
        // 장기연체(90일+) → 회워/회새 유지 (중간 회 제거 대상 아님)
        if (delinquentDays >= 90 && (diagnosis == "신유워" || diagnosis == "프유워")) {
            diagnosis = "회워"
            Log.d("HWP_CALC", "장기연체 90일+ → 회워로 변경")
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
            if (isSaeDiagnosis) {
                // 새새 진단: 재산-대상채무 >= 1억일 때만 부결고지
                if (netProperty - targetDebt >= 10000) finalDiagnosis = "$finalDiagnosis, 부결고지"
            } else {
                if (repaymentRate == 100 && majorCreditorRatio >= 50 && !diagnosis.startsWith("단순")) finalDiagnosis = "$finalDiagnosis, 부결고지"
                val daebuRatio = if (originalTargetDebt > 0) daebuDebtMan.toDouble() / originalTargetDebt * 100 else 0.0
                if (daebuRatio > 50 && !finalDiagnosis.contains("부결고지")) finalDiagnosis = "$finalDiagnosis, 부결고지"
            }
            if (hasBunyangGwon) finalDiagnosis = "$finalDiagnosis, 분양권 포기해야 진행 가능"
            if (originalTargetDebt <= 4000 && originalTargetDebt > 0) finalDiagnosis = "$finalDiagnosis, 수임료 오픈"
            if (repaymentRate == 100 || isSaeDiagnosis) finalDiagnosis = "$finalDiagnosis, 수임 별도"
        }
        binding.testing.text = "[진단] $finalDiagnosis"
        binding.half.text = ""

        // 거래처 진단 계산 (최소 월 50만, 최대 8년)
        var clientFinalMonthly = 0
        var clientFinalYear = 0
        var clientRoundedLongTermMonthly = 0
        var clientLongTermYears = 0
        if (targetDebt > 0 && !longTermFullyBlocked) {
            // 보수 계산 (min 50만, max 96개월)
            val step1 = income - livingCostShinbok - parentDeduction
            val step2 = income - livingCostShinbok
            var cltMonthly = when {
                step1 >= 50 -> step1
                step2 >= 50 -> step2
                else -> 50
            }
            val cltTotalMonths = if (cltMonthly > 0) Math.ceil(totalPayment.toDouble() / cltMonthly).toInt() else 0
            if (cltTotalMonths > 96) {
                cltMonthly = Math.ceil(totalPayment.toDouble() / 96).toInt()
            }
            clientRoundedLongTermMonthly = cltMonthly

            // 보수 년수 (max 8년)
            if (clientRoundedLongTermMonthly > 0) {
                val exactYears = totalPayment.toDouble() / clientRoundedLongTermMonthly / 12.0
                clientLongTermYears = if (exactYears - Math.floor(exactYears) >= 0.35) Math.ceil(exactYears).toInt() else Math.floor(exactYears).toInt()
                clientLongTermYears = clientLongTermYears.coerceIn(1, 8)
                // 월변제금 × 기간이 총변제금 초과 시 기간 축소 후 월변제금 재계산
                val maxYears = (totalPayment.toDouble() / (clientRoundedLongTermMonthly * 12)).toInt().coerceAtLeast(1)
                if (clientLongTermYears > maxYears) {
                    clientLongTermYears = maxYears
                    clientRoundedLongTermMonthly = totalPayment / (clientLongTermYears * 12)
                }
            }

            // 프리랜서
            if (isFreelancer && totalPayment > 0) {
                val freelancerMonthly = Math.ceil(totalPayment.toDouble() / 72).toInt()
                if (freelancerMonthly <= 50) {
                    clientRoundedLongTermMonthly = 50
                    val exactYears = totalPayment.toDouble() / 50 / 12.0
                    clientLongTermYears = if (exactYears - Math.floor(exactYears) >= 0.35) Math.ceil(exactYears).toInt() else Math.floor(exactYears).toInt()
                    clientLongTermYears = clientLongTermYears.coerceIn(3, 8)
                } else {
                    clientRoundedLongTermMonthly = freelancerMonthly
                    clientLongTermYears = 6
                }
            }

            // 1년 미만 완납 → 100% 변제, 최소 3년
            if (clientRoundedLongTermMonthly > 0 && totalPayment < clientRoundedLongTermMonthly * 12) {
                clientRoundedLongTermMonthly = Math.ceil(targetDebt.toDouble() / 36).toInt()
                if (clientRoundedLongTermMonthly < 50) clientRoundedLongTermMonthly = 50
                clientLongTermYears = 3
            }

            // 공격 계산 (min 50만, max 8년)
            var cltAggressiveMonthly = Math.ceil(clientRoundedLongTermMonthly * 2.0 / 3.0).toInt()
            if (cltAggressiveMonthly < 50) cltAggressiveMonthly = clientRoundedLongTermMonthly
            var cltAggressiveYears = Math.ceil(totalPayment.toDouble() / cltAggressiveMonthly / 12.0).toInt()
            if (cltAggressiveYears > 8) {
                cltAggressiveMonthly = Math.ceil(totalPayment.toDouble() / 96).toInt()
                cltAggressiveYears = 8
            }

            // 최종 년수/변제금 (percentage 재적용, max 8년)
            if (clientLongTermYears < 3) clientLongTermYears = 3
            val cltEffectiveMax = Math.min(cltAggressiveYears, clientLongTermYears + 4).coerceAtLeast(clientLongTermYears).coerceAtMost(8)

            // 거래처 최종 년수: 본체와 동일한 5단계 로직 (max 8년)
            val cltSurplus = income - livingCostShinbok
            val cltIsConservative = hasAuction || hasSeizure || hasGambling ||
                recentDebtRatio >= 50 || delinquentDays >= 90 || hasOwnRealEstate
            val cltIsAggressive = income <= livingCostShinbok
            val cltIsTowardConservative = hasStock || hasCrypto || majorCreditorRatio > 50 ||
                (cltSurplus > 0 && cltSurplus < targetDebt * 0.03) ||
                (recentDebtRatio >= 30 && recentDebtRatio < 50)
            val cltIsTowardAggressive = (cltSurplus > targetDebt * 0.03) || isFreelancer ||
                (shortTermTotal > 0 && shortTermTotal < longTermTotal - 1000)
            clientFinalYear = when {
                cltIsConservative -> clientLongTermYears
                cltIsAggressive -> cltAggressiveYears
                cltIsTowardConservative -> Math.round((3.0 * clientLongTermYears + cltAggressiveYears) / 4.0).toInt()
                cltIsTowardAggressive -> Math.round((clientLongTermYears + 3.0 * cltAggressiveYears) / 4.0).toInt()
                else -> Math.round((clientLongTermYears + cltAggressiveYears) / 2.0).toInt()
            }
            clientFinalYear = clientFinalYear.coerceIn(clientLongTermYears, maxOf(clientLongTermYears, cltAggressiveYears)).coerceAtMost(8)

            clientFinalMonthly = if (clientFinalYear > 0) totalPayment / (clientFinalYear * 12) else 0
            if (clientFinalMonthly < 50) {
                clientFinalMonthly = 50
                // 월변제금 × 기간이 총변제금 초과 시 기간 축소 후 월변제금 재계산
                val maxYears = (totalPayment.toDouble() / (50 * 12)).toInt().coerceAtLeast(1)
                if (clientFinalYear > maxYears) {
                    clientFinalYear = maxYears
                    clientFinalMonthly = totalPayment / (clientFinalYear * 12)
                }
            }
        }

        // 거래처 새새 계산 (min 50만, max 8년)
        var clientSaeMonthly = 0; var clientSaeYears = 0; var clientSaeTotalPayment = 0
        if (canApplySae && targetDebt > 0 && saeTotalPayment > 0) {
            clientSaeTotalPayment = saeTotalPayment
            val incomeRatio = income.toDouble() / targetDebt * 100
            clientSaeYears = when {
                incomeRatio > 6 -> 5
                incomeRatio > 3 -> 8
                else -> 8  // 거래처는 max 8년
            }
            clientSaeMonthly = Math.ceil(clientSaeTotalPayment.toDouble() / (clientSaeYears * 12)).toInt()
            if (clientSaeMonthly <= 50) {
                clientSaeMonthly = 50
                val exactYears = clientSaeTotalPayment.toDouble() / 50 / 12.0
                clientSaeYears = if (exactYears - Math.floor(exactYears) >= 0.35) Math.ceil(exactYears).toInt() else Math.floor(exactYears).toInt()
                clientSaeYears = clientSaeYears.coerceIn(1, 8)
                clientSaeTotalPayment = clientSaeMonthly * clientSaeYears * 12
            } else {
                clientSaeTotalPayment = clientSaeMonthly * clientSaeYears * 12
            }
        }

        // 거래처 모드일 때 장기/새새 결과를 거래처 기준으로 덮어쓰기
        if (isClientMode) {
            val clientLongTermText = StringBuilder()
            // 불가 사유는 레고와 동일
            val legoTest2 = binding.test2.text.toString()
            val legoFirstLine = legoTest2.split("\n").firstOrNull() ?: ""
            if (targetDebt > 0 && !longTermFullyBlocked && clientFinalMonthly > 0) {
                val studentLoanLongSuffix = if (studentLoanApplied) " (학자금 제외)" else ""
                clientLongTermText.append("[장기] ${clientFinalMonthly}만 / ${clientFinalYear}년납$studentLoanLongSuffix")
            } else {
                clientLongTermText.append(legoFirstLine)
            }
            if (canApplySae && clientSaeTotalPayment > 0) {
                clientLongTermText.append("\n[새새] ${clientSaeMonthly}만 / ${clientSaeYears}년납")
            } else if (hasBusinessHistory && isBusinessOwner && saeDebtOverLimit) {
                clientLongTermText.append("\n[새새] 새새 불가(채무한도초과 담보${formatToEok(totalSecuredDebt)})")
            }
            binding.test2.text = clientLongTermText.toString()
        }

        Log.d("HWP_CALC", "이름: $name, 소득: ${income}만, 대상: ${targetDebt}만, 재산: ${netProperty}만, 6개월비율: ${String.format("%.1f", recentDebtRatio)}%, 진단: $finalDiagnosis")
        Log.d("HWP_CALC", "거래처 장기: ${clientFinalMonthly}만/${clientFinalYear}년, 새새: ${clientSaeMonthly}만/${clientSaeYears}년")
    }

    // ============= 유틸리티 =============
    private fun extractAmountAfterKeyword(text: String, keyword: String): Int {
        if (!text.contains(keyword)) return 0
        val afterKeyword = text.substring(text.indexOf(keyword))
        // "/" 구분자 이전까지만 추출 (시세 5억 / 대출 3억 → 시세 5억만 추출)
        val segment = afterKeyword.split("/")[0]
        return extractAmount(segment)
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
                    // 합의서는 항상 Gemini Vision으로 처리 (테이블 추출이 필요)
                    Log.d("BATCH", "합의서 → Gemini Vision 처리 ($pdfFileName)")
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
                    Log.d("BATCH", "PDF OCR 유예기간 추출: ${ocrResult.defermentMonths}개월")
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
        pdfExcludedGuaranteeDebt = 0; pdfExcludedOtherDebt = 0; pdfExcludedDamboCreditors.clear()
        pdfRecoveryDebt = 0; pdfRecoveryIncome = 0; pdfRecoveryMonths = 0

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