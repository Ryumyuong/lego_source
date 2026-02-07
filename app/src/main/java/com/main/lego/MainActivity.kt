package com.main.lego;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.main.lego.databinding.ActivityMainBinding;
import java.io.OutputStream;
import kotlin.Unit;
import kotlin.io.CloseableKt;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import kotlin.text.Charsets;
import org.apache.poi.ss.usermodel.Cell;
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.ParaText;
import kr.dogfoot.hwplib.reader.HWPReader;

public final class MainActivity extends AppCompatActivity {
    public static final int CREATE_FILE_REQUEST_CODE = 43;
    public static final int READ_REQUEST_CODE = 42;
    private int acost;
    private double bCost;
    private int bValue;
    public ActivityMainBinding binding;
    private int card;
    private int cost;
    private int value;

    public static final Companion INSTANCE = new Companion(null);
    private static final StringBuilder recognizedText4 = new StringBuilder();
    private static final StringBuilder recognizedText5 = new StringBuilder();
    private String baby = "0";
    private String korea = "X";

    public final ActivityMainBinding getBinding() {
        ActivityMainBinding activityMainBinding = this.binding;
        if (activityMainBinding != null) {
            return activityMainBinding;
        }
        Intrinsics.throwUninitializedPropertyAccessException("binding");
        return null;
    }

    public final void setBinding(ActivityMainBinding activityMainBinding) {
        Intrinsics.checkNotNullParameter(activityMainBinding, "<set-?>");
        this.binding = activityMainBinding;
    }

    public static final class Companion {
        public Companion(DefaultConstructorMarker defaultConstructorMarker) {
            this();
        }

        private Companion() {
        }

        public final StringBuilder getRecognizedText4() {
            return MainActivity.recognizedText4;
        }

        public final StringBuilder getRecognizedText5() {
            return MainActivity.recognizedText5;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding inflate = ActivityMainBinding.inflate(getLayoutInflater());
        Intrinsics.checkNotNullExpressionValue(inflate, "inflate(layoutInflater)");
        setBinding(inflate);
        setContentView(getBinding().getRoot());
        getBinding().buttonSelectFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view) {
                MainActivity.onCreate$lambda$0(MainActivity.this, view);
            }
        });
    }

    private static final void onCreate$lambda$0(MainActivity this$0, View view) {
        Intrinsics.checkNotNullParameter(this$0, "this$0");
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        String[] mimeTypes = {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/x-hwp", "application/haansofthwp", "application/vnd.hancom.hwp"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        this$0.startActivityForResult(intent, 42);
    }

    private static final void onCreate$lambda$1(MainActivity this$0, View view) {
        Intrinsics.checkNotNullParameter(this$0, "this$0");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);
        if (requestCode == 42) {
            Uri uri = resultData != null ? resultData.getData() : null;
            if (uri != null) {
                String fileName = getFileName(uri);
                if (fileName != null && fileName.toLowerCase().endsWith(".hwp")) {
                    readHwpFile(uri);
                } else {
                    readExcelFile(uri);
                }
            }
        } else {
            if (requestCode != 43) {
                return;
            }
            Uri data = resultData != null ? resultData.getData() : null;
            if (data != null) {
                saveResultToFileAndShare(data);
            }
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        }
        return result;
    }

    private void readHwpFile(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                showToast("파일을 열 수 없습니다.");
                return;
            }

            File tempFile = new File(getCacheDir(), "temp.hwp");
            FileOutputStream fos = new FileOutputStream(tempFile);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.close();
            inputStream.close();

            HWPFile hwpFile = HWPReader.fromFile(tempFile.getAbsolutePath());
            if (hwpFile == null) {
                showToast("HWP 파일을 읽을 수 없습니다.");
                return;
            }

            StringBuilder tableText = extractTableData(hwpFile);
            processHwpText(tableText.toString());

            tempFile.delete();
        } catch (Exception e) {
            showToast("HWP 파일 읽기 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private StringBuilder extractTableData(HWPFile hwpFile) {
        StringBuilder sb = new StringBuilder();
        try {
            int sectionCount = 0;
            for (Section section : hwpFile.getBodyText().getSectionList()) {
                sectionCount++;
                int paraCount = 0;
                for (Paragraph paragraph : section.getParagraphs()) {
                    paraCount++;
                    String paraText = extractParagraphText(paragraph);
                    if (paraText != null && !paraText.trim().isEmpty()) {
                        sb.append(paraText.trim()).append("\n");
                        android.util.Log.d("HWP_EXTRACT", "문단 " + paraCount + ": " + paraText.trim());
                    }

                    java.util.ArrayList<Control> controlList = paragraph.getControlList();
                    if (controlList != null && controlList.size() > 0) {
                        android.util.Log.d("HWP_EXTRACT", "컨트롤 개수: " + controlList.size());
                        for (Control control : controlList) {
                            if (control != null) {
                                android.util.Log.d("HWP_EXTRACT", "컨트롤 타입: " + control.getType());
                                if (control.getType() == ControlType.Table) {
                                    ControlTable table = (ControlTable) control;
                                    sb.append("[표 시작]\n");
                                    for (kr.dogfoot.hwplib.object.bodytext.control.table.Row row : table.getRowList()) {
                                        StringBuilder rowText = new StringBuilder();
                                        for (kr.dogfoot.hwplib.object.bodytext.control.table.Cell cell : row.getCellList()) {
                                            StringBuilder cellText = new StringBuilder();
                                            for (Paragraph cellPara : cell.getParagraphList()) {
                                                String text = extractParagraphText(cellPara);
                                                if (text != null && !text.trim().isEmpty()) {
                                                    cellText.append(text.trim()).append(" ");
                                                }
                                            }
                                            rowText.append(cellText.toString().trim()).append("\t");
                                        }
                                        String rowStr = rowText.toString().trim();
                                        if (!rowStr.isEmpty()) {
                                            sb.append(rowStr).append("\n");
                                            android.util.Log.d("HWP_EXTRACT", "표 행: " + rowStr);
                                        }
                                    }
                                    sb.append("[표 끝]\n\n");
                                }
                            }
                        }
                    }
                }
                android.util.Log.d("HWP_EXTRACT", "섹션 " + sectionCount + " 완료, 문단 수: " + paraCount);
            }
            android.util.Log.d("HWP_EXTRACT", "총 섹션 수: " + sectionCount);
        } catch (Exception e) {
            sb.append("[오류: ").append(e.getMessage()).append("]\n");
            android.util.Log.e("HWP_EXTRACT", "추출 오류", e);
            e.printStackTrace();
        }
        return sb;
    }

    private String extractParagraphText(Paragraph paragraph) {
        if (paragraph == null || paragraph.getText() == null) {
            return "";
        }
        try {
            ParaText paraText = paragraph.getText();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < paraText.getCharList().size(); i++) {
                kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar hwpChar = paraText.getCharList().get(i);
                if (hwpChar.getType() == kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharType.Normal) {
                    kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal normalChar =
                            (kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal) hwpChar;
                    text.append((char) normalChar.getCode());
                }
            }
            return text.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void processHwpText(String text) {
        if (text == null || text.isEmpty()) {
            showToast("HWP 파일에서 텍스트를 찾을 수 없습니다.");
            return;
        }

        android.util.Log.d("HWP_DATA", "추출된 텍스트:\n" + text);
        parseHwpData(text);
        showToast("HWP 파일 읽기 완료");
    }

    private void parseHwpData(String text) {
        String[] lines = text.split("\n");

        // ============= 기본 변수 =============
        String name = "";
        String region = "";
        String job = "";

        // 소득 관련
        int yearlyIncome = 0;
        int monthlyIncome = 0;
        int netProfitIncome = 0;
        int allowanceIncome = 0;

        // 부양가족 관련
        int minorChildren = 0;
        int collegeChildren = 0;
        int parentCount = 0;
        boolean hasSpouse = false;

        // 재산 관련
        int propertyValue = 0;
        int propertyLoan = 0;
        int spousePropertyValue = 0;
        int spousePropertyLoan = 0;
        int depositValue = 0;
        int depositLoan = 0;
        int carValue = 0;
        int carTotalSise = 0;   // 차량 시세 합계
        int carTotalLoan = 0;   // 차량 대출 합계
        int carMonthlyPayment = 0;
        int carCount = 0;
        boolean livingTogether = true;
        boolean hasPreSaleRight = false;

        // 채무 관련
        int creditDebt = 0;
        int cardLoanDebt = 0;
        int cardUsageDebt = 0;
        int mortgageDebt = 0;
        int insurancePolicyDebt = 0;
        int paymentGuaranteeDebt = 0;
        int businessLoanDebt = 0;
        int settlementDebt = 0;
        int totalDebt = 0;
        int shinbokwiDebt = 0;  // 신복위 채무 추가

        // 6개월 비율 관련
        long recentCreditDebt = 0;
        long totalCreditDebt = 0;
        long recentAllDebt = 0;
        long totalAllDebt = 0;
        double recentDebtRatio = 0;
        double recentDebtRatioSae = 0;
        String lastCreditDate = "";

        // 사용처 관련
        boolean hasGambling = false;
        boolean hasStock = false;
        boolean hasCrypto = false;
        boolean hasBusinessUse = false;
        boolean hasLiving = false;

        // 연체/이력 관련
        int delinquentDays = 0;
        boolean hasDischarge = false;
        int dischargeYear = 0;
        int dischargeMonth = 0;
        boolean hasShinbokwiHistory = false;
        boolean hasSaeHistory = false;

        // 사업자 관련
        boolean isBusinessOwner = false;
        boolean hasBusinessHistory = false;
        boolean hasBusinessLoan = false;

        // 채권사 관련
        String majorCreditor = "";
        int majorCreditorDebt = 0;
        java.util.Map<String, Integer> creditorDebts = new java.util.HashMap<>();

        // 특이사항
        java.util.List<String> specialNotesList = new java.util.ArrayList<>();

        // ============= 데이터 추출 =============
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 공백 제거 버전 (키워드 체크용)
            String lineNoSpace = line.replace(" ", "");

            // 이름 추출 (첫 번째 줄에서 한글 2~10자)
            if (name.isEmpty() && line.length() >= 2 && line.length() <= 20) {
                // 영문 이름 포함 케이스: "한춘걸 HAN CHUNJIE"
                String[] nameParts = line.split("\\s+");
                if (nameParts.length > 0 && nameParts[0].matches("^[가-힣]{2,5}$")) {
                    name = nameParts[0];
                } else if (line.matches("^[가-힣]+$") && line.length() <= 5) {
                    name = line;
                }
            }

            // ============= 지역/부동산 파싱 =============
            if (lineNoSpace.contains("지역") && line.contains(":")) {
                region = line.substring(line.indexOf(":") + 1).trim();
                android.util.Log.d("HWP_PARSE", "지역 줄 감지: [" + line + "]");
                android.util.Log.d("HWP_PARSE", "  lineNoSpace: [" + lineNoSpace + "]");

                // 타인명의 체크 (본인/배우자 외)
                boolean isOtherOwner = lineNoSpace.contains("아들명의") || lineNoSpace.contains("딸명의") ||
                        lineNoSpace.contains("처형명의") || lineNoSpace.contains("형명의") ||
                        lineNoSpace.contains("부모명의") || lineNoSpace.contains("부명의") || lineNoSpace.contains("모명의");

                android.util.Log.d("HWP_PARSE", "  isOtherOwner: " + isOtherOwner);
                android.util.Log.d("HWP_PARSE", "  아파트포함: " + lineNoSpace.contains("아파트"));
                android.util.Log.d("HWP_PARSE", "  전세포함: " + lineNoSpace.contains("전세"));

                if (!isOtherOwner && (lineNoSpace.contains("아파트") || lineNoSpace.contains("주택") ||
                        lineNoSpace.contains("빌라") || lineNoSpace.contains("오피스텔") || lineNoSpace.contains("원룸") ||
                        lineNoSpace.contains("시세") || lineNoSpace.contains("전세") || lineNoSpace.contains("월세"))) {

                    android.util.Log.d("HWP_PARSE", "지역 줄 파싱 시작!");

                    int marketValue = 0;
                    int loanValue = 0;

                    // 월세 파싱: "월세 300만/38만" 또는 "월세 > 보증금 : 100만"
                    boolean isWolse = lineNoSpace.contains("월세");
                    // 전세 파싱
                    boolean isJeonse = lineNoSpace.contains("전세") && !isWolse;

                    if (isWolse) {
                        // 월세 보증금 추출
                        if (lineNoSpace.contains("보증금")) {
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("보증금[^\\d]*(\\d+)").matcher(line);
                            if (m.find()) {
                                marketValue = Integer.parseInt(m.group(1));
                            }
                        } else {
                            // "월세 300만/38만" 형식 - 앞의 숫자가 보증금
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("월세[^\\d]*(\\d+)").matcher(line);
                            if (m.find()) {
                                marketValue = Integer.parseInt(m.group(1));
                            }
                        }
                        depositValue = marketValue;
                        android.util.Log.d("HWP_PARSE", "  월세 보증금: " + marketValue + "만");
                    } else if (isJeonse) {
                        // 전세금 추출 - "전세금 : 1억5500만" 형식
                        if (line.contains("전세금")) {
                            marketValue = extractAmountAfterKeyword(line, "전세금");
                            android.util.Log.d("HWP_PARSE", "  전세금 키워드로 추출: " + marketValue);
                        }
                        if (marketValue == 0) {
                            marketValue = extractAmountAfterKeyword(line, "전세");
                        }
                        if (marketValue == 0) {
                            marketValue = extractAmountAfterKeyword(line, "시세");
                        }

                        // 대출 추출
                        loanValue = extractAmountAfterKeyword(line, "대출");

                        int netDeposit = marketValue - loanValue;
                        if (netDeposit < 0) netDeposit = 0;

                        // 배우자명의면 절반
                        if (lineNoSpace.contains("배우자명의") || lineNoSpace.contains("배우자")) {
                            netDeposit = netDeposit / 2;
                        }
                        depositValue = netDeposit;
                        android.util.Log.d("HWP_PARSE", "  전세: 금액=" + marketValue + ", 대출=" + loanValue + ", 순재산=" + netDeposit);
                    } else {
                        // 부동산(시세) 파싱: "시세 – 4억5천" 또는 "시세: 2억7천"
                        marketValue = extractAmountAfterKeyword(line, "시세");
                        loanValue = extractAmountAfterKeyword(line, "대출");

                        int netProperty = marketValue - loanValue;
                        if (netProperty < 0) netProperty = 0;

                        // 배우자명의면 절반
                        if (lineNoSpace.contains("배우자명의") || lineNoSpace.contains("배우자")) {
                            netProperty = netProperty / 2;
                        }
                        propertyValue = netProperty;
                        android.util.Log.d("HWP_PARSE", "  부동산: 시세=" + marketValue + ", 대출=" + loanValue + ", 순재산=" + netProperty);
                    }
                }
            }

            // ============= 재직/직업 추출 =============
            if (lineNoSpace.contains("재직") && line.contains(":")) {
                job = line.substring(line.indexOf(":") + 1).trim();
                // 사업자 여부 체크
                if (job.contains("사업자") || job.contains("개인사업") || job.contains("자영업") || job.contains("음식점")) {
                    isBusinessOwner = true;
                    android.util.Log.d("HWP_PARSE", "사업자 감지: " + job);
                }
            }

            // ============= 재산 파싱 =============
            if (lineNoSpace.contains("재산") && line.contains(":")) {
                String afterColon = line.substring(line.indexOf(":") + 1).trim();

                // "x" 또는 "없음"이 아닌 경우만
                if (!afterColon.equalsIgnoreCase("x") && !afterColon.contains("없음")) {
                    // 사업장보증금 파싱
                    if (lineNoSpace.contains("사업장보증금") || afterColon.contains("사업장")) {
                        android.util.Log.d("HWP_PARSE", "사업장 체크 - lineNoSpace: [" + lineNoSpace + "]");
                        android.util.Log.d("HWP_PARSE", "사업장 체크 - 보증금x포함: " + lineNoSpace.contains("보증금x"));
                        // "보증금 x" 또는 "보증금x" 또는 "보증금 없음"이면 제외
                        if (lineNoSpace.contains("보증금x") || lineNoSpace.contains("보증금없음") ||
                                afterColon.contains("보증금 x") || afterColon.contains("보증금 없음")) {
                            android.util.Log.d("HWP_PARSE", "사업장보증금 없음 → 제외");
                        } else {
                            int amount = extractAmountAfterKeyword(line, "보증금");
                            if (amount == 0) {
                                amount = extractAmount(afterColon);
                            }
                            if (amount > 0) {
                                depositValue += amount;
                                android.util.Log.d("HWP_PARSE", "사업장보증금: " + amount + "만");
                            }
                        }
                    }
                    // 토지 파싱
                    else if (lineNoSpace.contains("토지")) {
                        int siseAmount = extractAmountAfterKeyword(line, "시세");
                        if (siseAmount == 0) {
                            siseAmount = extractAmountAfterKeyword(line, "공시지가");
                        }
                        if (siseAmount == 0) {
                            siseAmount = extractAmount(afterColon);
                        }

                        // 대출 차감
                        int loanAmount = extractAmountAfterKeyword(line, "대출");
                        int netAmount = siseAmount - loanAmount;
                        if (netAmount < 0) netAmount = 0;

                        // 배우자명의면 절반
                        if (lineNoSpace.contains("배우자명의") || lineNoSpace.contains("배우자")) {
                            netAmount = netAmount / 2;
                            android.util.Log.d("HWP_PARSE", "토지 배우자명의 → 절반: " + netAmount + "만");
                        }

                        if (netAmount > 0) {
                            propertyValue += netAmount;
                            android.util.Log.d("HWP_PARSE", "토지: 시세=" + siseAmount + ", 대출=" + loanAmount + ", 순재산=" + netAmount + "만");
                        }
                    }
                    // 일반 재산
                    else {
                        int amount = extractAmount(afterColon);
                        if (amount > 0) {
                            propertyValue += amount;
                            android.util.Log.d("HWP_PARSE", "재산: " + amount + "만");
                        }
                    }
                }
            }

            // 재산 줄 외의 사업장보증금 파싱
            if (!lineNoSpace.contains("재산") && lineNoSpace.contains("사업장") && lineNoSpace.contains("보증금") &&
                    !lineNoSpace.contains("없음") && !lineNoSpace.contains("x")) {
                int amount = extractAmount(line);
                if (amount > 0) {
                    depositValue += amount;
                    android.util.Log.d("HWP_PARSE", "사업장보증금(별도): " + amount + "만");
                }
            }

            // ============= 연봉/소득 파싱 =============
            // 배우자소득은 제외
            if (lineNoSpace.contains("배우자소득")) {
                continue;
            }

            if (lineNoSpace.contains("연봉") || lineNoSpace.contains("연금") ||
                    (lineNoSpace.contains("월") && lineNoSpace.contains("만") && !lineNoSpace.contains("차량") && !lineNoSpace.contains("카드"))) {

                // 순수익 파싱: "월 순수익 : 300만"
                if (line.contains("순수익")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("순수익[^\\d]*(\\d+)").matcher(line);
                    if (m.find()) {
                        int profit = Integer.parseInt(m.group(1));
                        if (profit > netProfitIncome) {
                            netProfitIncome = profit;
                            android.util.Log.d("HWP_CALC", "순수익 감지: " + profit + "만");
                        }
                    }
                }

                // 연금수령 파싱: "연금수령 : 월 80만"
                if (line.contains("연금")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)만").matcher(line);
                    if (m.find()) {
                        int pension = Integer.parseInt(m.group(1));
                        if (pension > monthlyIncome) {
                            monthlyIncome = pension;
                            android.util.Log.d("HWP_CALC", "연금 감지: " + pension + "만");
                        }
                    }
                }

                // 연봉 파싱: "연 5000만(369만) / 월 320~380만" 또는 "연 4100만(300만) / 월 360만"
                if (lineNoSpace.contains("연봉") || line.contains("연 ")) {
                    // 소득금액증명원 기준 연소득 추출: "소득 : 1518만"
                    if (line.contains("소득") && !line.contains("배우자소득")) {
                        int amount = extractAmountAfterKeyword(line, "소득");
                        if (amount > 0 && amount < 50000) {
                            yearlyIncome = amount;
                            android.util.Log.d("HWP_CALC", "소득 감지: " + amount + "만");
                        }
                    }

                    // "연 XXXX만" 형식
                    java.util.regex.Matcher yearlyM = java.util.regex.Pattern.compile("연\\s*(\\d+)만").matcher(line);
                    if (yearlyM.find()) {
                        int yearly = Integer.parseInt(yearlyM.group(1));
                        if (yearly > yearlyIncome && yearly < 50000) {
                            yearlyIncome = yearly;
                            android.util.Log.d("HWP_CALC", "연봉 감지: " + yearly + "만");
                        }
                    }

                    // 괄호 안 실수령액 추출: "연 6700만(482만)" → 482만
                    java.util.regex.Matcher realIncomeM = java.util.regex.Pattern.compile("연\\s*\\d+만\\s*\\((\\d+)만\\)").matcher(line);
                    if (realIncomeM.find()) {
                        int realIncome = Integer.parseInt(realIncomeM.group(1));
                        if (realIncome > monthlyIncome && realIncome < 2000) {
                            monthlyIncome = realIncome;
                            android.util.Log.d("HWP_CALC", "실수령액 감지: " + realIncome + "만");
                        }
                    }

                    // 월소득 범위 추출: "월 320~380만" → 380 사용
                    java.util.regex.Matcher rangeM = java.util.regex.Pattern.compile("월\\s*(\\d+)\\s*[~\\-]\\s*(\\d+)만?").matcher(line);
                    if (rangeM.find()) {
                        int high = Math.max(Integer.parseInt(rangeM.group(1)), Integer.parseInt(rangeM.group(2)));
                        if (high > monthlyIncome && high < 2000) {
                            monthlyIncome = high;
                            android.util.Log.d("HWP_CALC", "월소득 범위 감지: " + high + "만");
                        }
                    }
                    // 단일 월소득: "월 360만" 또는 "월360만"
                    else {
                        java.util.regex.Matcher monthlyM = java.util.regex.Pattern.compile("월\\s*(\\d+)만").matcher(line);
                        if (monthlyM.find()) {
                            int monthly = Integer.parseInt(monthlyM.group(1));
                            if (monthly > monthlyIncome && monthly < 2000) {
                                monthlyIncome = monthly;
                                android.util.Log.d("HWP_CALC", "월소득 감지: " + monthly + "만");
                            }
                        }
                    }
                }

                // 단독 "월 XXX만" 형식 (연봉 줄이 아닌 경우): "월 290만"
                if (!lineNoSpace.contains("연봉") && !lineNoSpace.contains("순수익") && !lineNoSpace.contains("연금")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("월\\s*(\\d+)만").matcher(line);
                    if (m.find()) {
                        int monthly = Integer.parseInt(m.group(1));
                        if (monthly > monthlyIncome && monthly < 2000) {
                            monthlyIncome = monthly;
                            android.util.Log.d("HWP_CALC", "월급 감지: " + monthly + "만");
                        }
                    }
                }
            }

            // ============= 차량 파싱 =============
            // "차량", "자동차", 또는 "XX년식 + 차량명"으로 시작하는 줄
            // 단, "차량담보", "차량할부"만 있는 줄은 제외
            boolean isCarLine = (lineNoSpace.contains("차량") || line.contains("자동차") ||
                    (java.util.regex.Pattern.compile("\\d{2}년식").matcher(lineNoSpace).find() &&
                            (lineNoSpace.contains("시세") || lineNoSpace.contains("본인명의") || lineNoSpace.contains("배우자명의"))));

            // 차량담보/차량할부만 있는 줄은 차량 대수에서 제외하되, 대출은 합산
            if (isCarLine && (lineNoSpace.startsWith("차량담보") || lineNoSpace.startsWith("차량할부"))) {
                int additionalLoan = extractAmount(line);
                if (additionalLoan > 0) {
                    carTotalLoan += additionalLoan;
                    android.util.Log.d("HWP_PARSE", "차량담보/할부 추가: " + additionalLoan + "만 (총 대출: " + carTotalLoan + "만)");
                }
                isCarLine = false;
            }

            if (isCarLine) {
                // 장기렌트는 재산 제외
                if (lineNoSpace.contains("장기렌트") || lineNoSpace.contains("렌트")) {
                    // 월 납부금만 추출
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("월\\s*(\\d+)만").matcher(line);
                    if (m.find()) {
                        carMonthlyPayment = Integer.parseInt(m.group(1));
                        android.util.Log.d("HWP_PARSE", "장기렌트 월납: " + carMonthlyPayment + "만");
                    }
                    continue;
                }

                // "x"면 건너뛰기
                if (line.contains(": x") || line.contains(":x") || line.endsWith(": x")) {
                    continue;
                }

                int carSise = 0;
                int carLoan = 0;

                // 시세 추출
                carSise = extractAmountAfterKeyword(line, "시세");
                if (carSise == 0) {
                    // 시세 키워드 없으면 전체에서 추출
                    carSise = extractAmount(line);
                }

                // 차량담보/할부 추출
                if (lineNoSpace.contains("담보") || lineNoSpace.contains("할부")) {
                    int dambo = extractAmountAfterKeyword(line, "담보");
                    int halbu = extractAmountAfterKeyword(line, "할부");
                    carLoan = Math.max(dambo, halbu);
                }

                // 순재산 계산
                int netCar = carSise - carLoan;
                if (netCar < 0) netCar = 0;

                // 배우자명의면 절반
                if (lineNoSpace.contains("배우자명의") || lineNoSpace.contains("배우자")) {
                    netCar = netCar / 2;
                    carSise = carSise / 2;
                    carLoan = carLoan / 2;
                }

                // 차량 합산
                carValue += netCar;
                carTotalSise += carSise;
                carTotalLoan += carLoan;

                // 월납 추출
                java.util.regex.Matcher monthlyM = java.util.regex.Pattern.compile("월\\s*(\\d+)만").matcher(line);
                if (monthlyM.find()) {
                    int payment = Integer.parseInt(monthlyM.group(1));
                    carMonthlyPayment += payment;
                }

                // 차량 대수 카운트
                carCount++;

                android.util.Log.d("HWP_PARSE", "차량: 시세=" + carSise + ", 담보=" + carLoan + ", 순재산=" + netCar);
            }

            // ============= 미성년 자녀 추출 =============
            if (lineNoSpace.contains("결혼여부") || lineNoSpace.contains("결혼")) {
                // 기혼 여부 체크
                if (lineNoSpace.contains("기혼")) {
                    hasSpouse = true;
                }

                // 비양육이면 0 (단, 양육비 내고 있으면 포함)
                if ((lineNoSpace.contains("비양육") || lineNoSpace.contains("전처") ||
                        lineNoSpace.contains("전남편") || lineNoSpace.contains("양육중")) &&
                        !lineNoSpace.contains("양육비")) {
                    minorChildren = 0;
                    android.util.Log.d("HWP_CALC", "비양육 감지 (양육비 없음) → 미성년자녀 0명");
                } else if (lineNoSpace.contains("미성년")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("미성년\\s*(\\d+)").matcher(line);
                    if (m.find()) {
                        minorChildren = Integer.parseInt(m.group(1));
                        android.util.Log.d("HWP_CALC", "미성년자녀 감지: " + minorChildren + "명");
                    }
                }

                // 대학생 자녀 추출
                if (line.contains("대학생")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("대학생\\s*(\\d+)").matcher(line);
                    if (m.find()) {
                        collegeChildren = Integer.parseInt(m.group(1));
                        android.util.Log.d("HWP_CALC", "대학생자녀 감지: " + collegeChildren + "명");
                    }
                }
            }

            // ============= 60세 이상 부모 =============
            if (lineNoSpace.contains("60세") || lineNoSpace.contains("만60세")) {
                int count = 0;
                String afterColon = line.contains(":") ? line.substring(line.indexOf(":") + 1) : line;

                // "부,모" 또는 "부, 모" 형식
                if ((afterColon.contains("부") && !afterColon.contains("부별세") && !afterColon.contains("부-별세")) ||
                        (afterColon.contains("모") && !afterColon.contains("모별세") && !afterColon.contains("모-별세"))) {

                    // 부 체크
                    if (afterColon.contains("부") && !afterColon.contains("부별세") && !afterColon.contains("부-별세") &&
                            !afterColon.contains("부 별세") && !afterColon.contains("부x") && !afterColon.contains("부X")) {
                        count++;
                    }
                    // 모 체크
                    if (afterColon.contains("모") && !afterColon.contains("모별세") && !afterColon.contains("모-별세") &&
                            !afterColon.contains("모 별세") && !afterColon.contains("모x") && !afterColon.contains("모X") &&
                            !afterColon.contains("모르게")) {
                        count++;
                    }
                }
                // "2분다 별세" 형식
                else if (afterColon.contains("별세") && afterColon.contains("2분")) {
                    count = 0;
                }
                // "x" 형식
                else if (afterColon.trim().equalsIgnoreCase("x")) {
                    count = 0;
                }

                if (count > 0) {
                    parentCount = count;
                    android.util.Log.d("HWP_CALC", "60세이상 부모: " + parentCount + "명");
                }
            }

            // ============= 카드 이용금액 파싱 =============
            if (line.contains("카드") && line.contains("/") && line.contains("만")) {
                // "현대카드 700만 / 700만" 형식
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)만\\s*/").matcher(line);
                if (m.find()) {
                    int amount = Integer.parseInt(m.group(1));
                    cardUsageDebt += amount;
                    android.util.Log.d("HWP_DEBT", "카드이용금액: " + amount + "만");
                }
            }

            // ============= 대출사용처 파싱 =============
            if (lineNoSpace.contains("사용처") || lineNoSpace.contains("용처")) {
                if (line.contains("도박")) hasGambling = true;
                if (line.contains("주식") || line.contains("전액주식")) hasStock = true;
                if (line.contains("코인") || line.contains("비트코인") || line.contains("가상화폐")) hasCrypto = true;
                if (line.contains("생활") || line.contains("생활비") || line.contains("생활자금")) hasLiving = true;
                if (line.contains("사업") || line.contains("사업자금")) hasBusinessUse = true;
            }

            // ============= 특이사항 파싱 =============
            if (lineNoSpace.contains("특이사항") || lineNoSpace.contains("특이:")) {
                if (line.contains(":")) {
                    String content = line.substring(line.indexOf(":") + 1).trim();
                    if (!content.isEmpty() && !content.equalsIgnoreCase("x")) {
                        String[] parts = content.split("[,，]");
                        for (String part : parts) {
                            String trimmed = part.trim();
                            if (!trimmed.isEmpty()) {
                                specialNotesList.add(trimmed);
                            }
                        }
                    }
                }
            }

            // ============= 채무조정 이력 파싱 =============
            if (lineNoSpace.contains("채무조정") && line.contains(":")) {
                String content = line.substring(line.indexOf(":") + 1).trim();
                if (!content.equalsIgnoreCase("x") && !content.isEmpty()) {
                    // 면책 이력
                    if (content.contains("면책")) {
                        hasDischarge = true;
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2})년").matcher(content);
                        if (m.find()) {
                            dischargeYear = 2000 + Integer.parseInt(m.group(1));
                        } else {
                            m = java.util.regex.Pattern.compile("(20\\d{2})").matcher(content);
                            if (m.find()) {
                                dischargeYear = Integer.parseInt(m.group(1));
                            }
                        }
                        android.util.Log.d("HWP_PARSE", "면책 이력: " + dischargeYear + "년");
                    }
                    // 신복위 이력
                    if (content.contains("신복위") || content.contains("신용회복")) {
                        hasShinbokwiHistory = true;
                        android.util.Log.d("HWP_PARSE", "신복위 이력 감지");
                    }
                    // 신속채무조정
                    if (content.contains("신속채무조정")) {
                        hasShinbokwiHistory = true;
                    }
                }
            }

            // ============= 연체 감지 =============
            if (line.contains("연체")) {
                // "오늘부터 연체", "막 연체" 등은 30일 미만
                if (lineNoSpace.contains("오늘부터") || lineNoSpace.contains("막연체") ||
                        lineNoSpace.contains("이제연체") || lineNoSpace.contains("방금연체")) {
                    if (delinquentDays < 1) delinquentDays = 1; // 30일 미만
                    android.util.Log.d("HWP_PARSE", "연체 시작 감지 (30일 미만)");
                }
                // 구체적인 기간
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)개월").matcher(line);
                if (m.find()) {
                    int months = Integer.parseInt(m.group(1));
                    delinquentDays = Math.max(delinquentDays, months * 30);
                }
                m = java.util.regex.Pattern.compile("(\\d+)일").matcher(line);
                if (m.find()) {
                    int days = Integer.parseInt(m.group(1));
                    delinquentDays = Math.max(delinquentDays, days);
                }
                // 그 외 "연체중"만 있으면 30일 이상으로 추정
                if (delinquentDays == 0 && (lineNoSpace.contains("연체중") || lineNoSpace.contains("연체"))) {
                    delinquentDays = 30;
                }
            }

            // ============= 대출과목 요약 파싱 =============
            // "1. 신용 = 7000만" 또는 "신용 = 1억 490만" 또는 "신용 1억 7512만"
            if ((lineNoSpace.startsWith("신용") || lineNoSpace.startsWith("1.신용") ||
                    lineNoSpace.contains("신용=") || lineNoSpace.contains("신용:")) &&
                    !lineNoSpace.contains("신용대출") && !lineNoSpace.contains("신용회복") && !lineNoSpace.contains("신용보증")) {
                int amount = extractAmount(line);
                if (amount > creditDebt) {
                    creditDebt = amount;
                    android.util.Log.d("HWP_DEBT", "신용채무 요약: " + amount + "만");
                }
            }

            // 카드 채무
            if ((lineNoSpace.startsWith("카드") || lineNoSpace.startsWith("2.카드") ||
                    lineNoSpace.contains("카드=") || lineNoSpace.contains("카드:")) &&
                    !lineNoSpace.contains("카드이용") && !lineNoSpace.contains("이용금액") && !line.contains("/")) {
                int amount = extractAmount(line);
                if (amount > cardLoanDebt) {
                    cardLoanDebt = amount;
                    android.util.Log.d("HWP_DEBT", "카드채무 요약: " + amount + "만");
                }
            }

            // 담보 채무
            if ((lineNoSpace.startsWith("담보") || lineNoSpace.startsWith("3.담보") ||
                    lineNoSpace.contains("담보=") || lineNoSpace.contains("담보:")) &&
                    !lineNoSpace.contains("담보대출") && !line.contains("/")) {
                int amount = extractAmount(line);
                if (amount > mortgageDebt) {
                    mortgageDebt = amount;
                    android.util.Log.d("HWP_DEBT", "담보채무 요약: " + amount + "만");
                }
            }

            // 신복위 채무
            if (lineNoSpace.contains("신복") && (line.contains("=") || line.contains(":"))) {
                int amount = extractAmount(line);
                if (amount > shinbokwiDebt) {
                    shinbokwiDebt = amount;
                    android.util.Log.d("HWP_DEBT", "신복위채무: " + amount + "만");
                }
            }

            // 기타 채무
            if (lineNoSpace.contains("기타") && !lineNoSpace.contains("기타담보") && (line.contains("=") || line.contains(":"))) {
                int amount = extractAmount(line);
                if (amount > 0) {
                    creditDebt += amount;
                    android.util.Log.d("HWP_DEBT", "기타채무: " + amount + "만");
                }
            }

            // 총액 파싱
            if (lineNoSpace.contains("총액")) {
                int amount = extractAmount(line);
                if (amount > totalDebt) {
                    totalDebt = amount;
                    android.util.Log.d("HWP_DEBT", "총액: " + amount + "만");
                }
            }

            // ============= 채무현황 개별 항목 파싱 =============
            // 날짜 추출
            int loanYear = 0, loanMonth = 0, loanDay = 0;

            // "2016.10.18." 또는 "2025-11-15"
            java.util.regex.Matcher dateMatcher = java.util.regex.Pattern.compile("(\\d{4})[.\\-](\\d{1,2})[.\\-](\\d{1,2})").matcher(line);
            // "25년 11월 7일" 또는 "17년 1월 5일"
            java.util.regex.Matcher dateMatcher2 = java.util.regex.Pattern.compile("(\\d{2})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일?").matcher(line);
            // "21년 농협은행" 형식 (날짜 없이 연도만)
            java.util.regex.Matcher dateMatcher3 = java.util.regex.Pattern.compile("^(\\d{2})년\\s+").matcher(line);

            if (dateMatcher.find()) {
                loanYear = Integer.parseInt(dateMatcher.group(1));
                loanMonth = Integer.parseInt(dateMatcher.group(2));
                loanDay = Integer.parseInt(dateMatcher.group(3));
            } else if (dateMatcher2.find()) {
                loanYear = 2000 + Integer.parseInt(dateMatcher2.group(1));
                loanMonth = Integer.parseInt(dateMatcher2.group(2));
                loanDay = Integer.parseInt(dateMatcher2.group(3));
            } else if (dateMatcher3.find()) {
                loanYear = 2000 + Integer.parseInt(dateMatcher3.group(1));
                loanMonth = 1;
                loanDay = 1;
            }

            // 채무현황 줄 감지
            boolean hasFinancialKeyword = line.contains("은행") || line.contains("캐피탈") || line.contains("카드") ||
                    line.contains("금융") || line.contains("저축") || line.contains("보증") ||
                    line.contains("공사") || line.contains("재단") || line.contains("농협") ||
                    line.contains("신협") || line.contains("새마을") || line.contains("생명") ||
                    line.contains("화재") || line.contains("공단") || line.contains("대부");

            if (loanYear > 0 && hasFinancialKeyword) {
                int debtAmount = 0;

                // "만"이 붙어있으면 만원 단위 → 천원으로 변환해서 저장
                if (line.contains("만")) {
                    int manAmount = extractAmount(line);
                    debtAmount = manAmount * 10; // 만원 → 천원 (나중에 합산 후 반올림)
                    android.util.Log.d("HWP_DEBT", "만원단위: " + manAmount + "만 → " + debtAmount + "천원");
                } else {
                    // "만"이 없으면 천원 단위
                    java.util.regex.Matcher commaM = java.util.regex.Pattern.compile("([\\d,]+)$").matcher(line.trim());
                    if (commaM.find()) {
                        String numStr = commaM.group(1).replace(",", "");
                        if (!numStr.isEmpty()) {
                            debtAmount = Integer.parseInt(numStr); // 천원 단위 그대로
                            android.util.Log.d("HWP_DEBT", "천원단위: " + debtAmount + "천원");
                        }
                    }
                }

                if (debtAmount > 0) {
                    // 채권사 이름 추출
                    String creditorName = "";
                    java.util.regex.Matcher creditorM = java.util.regex.Pattern.compile("([가-힣A-Za-z]+(?:은행|캐피탈|카드|저축은행|금융|생명|화재|대부|공사|재단))").matcher(line);
                    if (creditorM.find()) {
                        creditorName = creditorM.group(1);
                        // 이름 정규화 (예: "신한은행" → "신한")
                        creditorName = creditorName.replace("은행", "").replace("저축", "").replace("캐피탈", "")
                                .replace("카드", "").replace("금융", "").replace("생명", "").replace("화재", "")
                                .replace("대부", "").replace("공사", "").replace("재단", "");
                    }

                    // 분류
                    boolean isGuarantee = lineNoSpace.contains("지급보증") || lineNoSpace.contains("융자담보") ||
                            lineNoSpace.contains("(3021)") || lineNoSpace.contains("(3011)");
                    boolean isMortgage = lineNoSpace.contains("집담보") || lineNoSpace.contains("담보대출") ||
                            lineNoSpace.contains("주담대") || lineNoSpace.contains("보금자리론") ||
                            lineNoSpace.contains("전세대출") || lineNoSpace.contains("(270)") || lineNoSpace.contains("(290)");
                    boolean isCarLoan = lineNoSpace.contains("차할부") || lineNoSpace.contains("차담보") ||
                            lineNoSpace.contains("차량할부") || lineNoSpace.contains("차량담보");

                    // 만원으로 변환 (반올림)
                    int debtAmountMan = (debtAmount + 5) / 10;

                    if (isGuarantee) {
                        android.util.Log.d("HWP_DEBT", "지급보증(제외): " + debtAmountMan + "만");
                    } else if (isMortgage || isCarLoan) {
                        android.util.Log.d("HWP_DEBT", "담보대출(참고): " + debtAmountMan + "만");
                    } else {
                        // 사업자대출 감지 (개인사업자대출, 운전자금 등)
                        if (lineNoSpace.contains("개인사업자대출") || lineNoSpace.contains("운전자금") ||
                                lineNoSpace.contains("사업자대출") || lineNoSpace.contains("(1051)")) {
                            hasBusinessLoan = true;
                            android.util.Log.d("HWP_PARSE", "사업자대출 감지 → 새새 가능");
                        }

                        // 2019-2024 사업자 경력 감지
                        if ((lineNoSpace.contains("개인사업자대출") || lineNoSpace.contains("운전자금")) &&
                                loanYear >= 2019 && loanYear <= 2024) {
                            hasBusinessHistory = true;
                            android.util.Log.d("HWP_PARSE", "2019-2024 사업자 경력 감지 → 새새 가능");
                        }

                        // 신용대출 → 대상채무, 채권사별 집계 (천원 단위로 저장)
                        if (!creditorName.isEmpty()) {
                            int currentAmount = creditorDebts.containsKey(creditorName) ? creditorDebts.get(creditorName) : 0;
                            creditorDebts.put(creditorName, currentAmount + debtAmount);
                            android.util.Log.d("HWP_DEBT", "신용대출: " + creditorName + " " + debtAmountMan + "만 (누적: " + ((currentAmount + debtAmount + 5) / 10) + "만)");
                        } else {
                            android.util.Log.d("HWP_DEBT", "신용대출: " + debtAmountMan + "만 from [" + line + "]");
                        }

                        // 6개월 이내 여부 (천원 단위로 계산)
                        if (loanYear > 0) {
                            java.util.Calendar loanDate = java.util.Calendar.getInstance();
                            loanDate.set(loanYear, loanMonth - 1, loanDay);

                            java.util.Calendar sixMonthsAgo = java.util.Calendar.getInstance();
                            sixMonthsAgo.add(java.util.Calendar.MONTH, -6);

                            if (loanDate.after(sixMonthsAgo)) {
                                recentCreditDebt += debtAmount; // 천원 단위
                                String thisDate = loanYear + "." + loanMonth + "." + loanDay;
                                if (lastCreditDate.isEmpty() || thisDate.compareTo(lastCreditDate) > 0) {
                                    lastCreditDate = thisDate;
                                }
                                android.util.Log.d("HWP_CALC", "6개월 내 신용대출: " + thisDate + " / " + debtAmountMan + "만");
                            }
                        }
                    }
                }
            }
        }

        // ============= 채무 합계 계산 =============
        // 신복위 채무 포함
        if (shinbokwiDebt > 0) {
            creditDebt += shinbokwiDebt;
        }

        // 대상채무 계산
        // 1. 신용/카드 요약이 파싱되었으면 사용
        int targetDebt = creditDebt + cardLoanDebt;

        // 2. 카드채무 요약이 없으면 카드이용금액 사용
        if (cardLoanDebt == 0 && cardUsageDebt > 0) {
            targetDebt += cardUsageDebt;
        }

        // 3. 총액이 있고 대상채무가 총액보다 작으면, 총액 - 담보로 재계산
        if (totalDebt > 0) {
            int targetFromTotal = totalDebt - mortgageDebt - paymentGuaranteeDebt;
            if (targetFromTotal > targetDebt) {
                targetDebt = targetFromTotal;
                android.util.Log.d("HWP_CALC", "총액 기반 대상채무: " + totalDebt + " - " + mortgageDebt + " - " + paymentGuaranteeDebt + " = " + targetDebt);
            }
        }

        if (targetDebt < 0) targetDebt = 0;

        // ============= 6개월 비율 계산 =============
        int creditTypeDebt = creditDebt + cardLoanDebt;
        if (recentCreditDebt > 0 && creditTypeDebt > 0) {
            // recentCreditDebt는 천원 단위, creditTypeDebt는 만원 단위
            long recentCreditDebtMan = (recentCreditDebt + 5) / 10; // 천원 → 만원 반올림
            recentDebtRatio = (double) recentCreditDebtMan / creditTypeDebt * 100;
            android.util.Log.d("HWP_CALC", "6개월 비율: " + recentCreditDebtMan + "만 / " + creditTypeDebt + "만 = " + String.format("%.1f", recentDebtRatio) + "%");
        }

        // ============= 재산 계산 =============
        android.util.Log.d("HWP_CALC", "재산 계산 전: propertyValue=" + propertyValue + ", depositValue=" + depositValue + ", carValue=" + carValue);
        int netProperty = propertyValue + depositValue + carValue;
        android.util.Log.d("HWP_CALC", "재산 계산 후: netProperty=" + netProperty);

        // 재산 설명 생성
        StringBuilder propertyDesc = new StringBuilder();
        if (propertyValue > 0) propertyDesc.append("부동산 ").append(formatToEok(propertyValue));
        if (depositValue > 0) {
            if (propertyDesc.length() > 0) propertyDesc.append(" + ");
            propertyDesc.append("전세/보증금 ").append(formatToEok(depositValue));
        }
        if (carValue > 0) {
            if (propertyDesc.length() > 0) propertyDesc.append(" + ");
            propertyDesc.append("차량 ").append(formatToEok(carValue));
        }
        if (propertyDesc.length() == 0) propertyDesc.append("없음");

        // ============= 소득 계산 =============
        int income = 0;

        if (isBusinessOwner) {
            // 사업자: 순수익 우선, 없으면 연소득÷12
            if (netProfitIncome > 0) {
                income = netProfitIncome;
            } else if (yearlyIncome > 0) {
                income = yearlyIncome / 12;
            } else {
                income = monthlyIncome;
            }
            android.util.Log.d("HWP_CALC", "사업자 소득: 순수익=" + netProfitIncome + ", 연소득÷12=" + (yearlyIncome/12) + ", 월급=" + monthlyIncome + " → " + income);
        } else {
            // 직장인: 월급(괄호 실수령액 포함)이 있으면 사용, 없으면 연소득÷12
            if (monthlyIncome > 0) {
                income = monthlyIncome;
            } else if (yearlyIncome > 0) {
                income = yearlyIncome / 12;
            }
            android.util.Log.d("HWP_CALC", "직장인 소득: 월급=" + monthlyIncome + ", 연소득÷12=" + (yearlyIncome/12) + " → " + income);
        }

        // ============= 변제율 결정 =============
        int repaymentRate = 90;
        String rateReason = "";

        if (hasGambling || hasStock || hasCrypto) {
            repaymentRate = 95;
            if (hasGambling) rateReason = "도박";
            else if (hasStock) rateReason = "주식";
            else rateReason = "코인";
        } else if (recentDebtRatio >= 80) {
            repaymentRate = 95;
            rateReason = "6개월비율 80%+";
        }

        // ============= 최저생계비 계산 (2026년 기준) =============
        int[] livingCostTable = {0, 154, 252, 322, 390, 453, 513};

        int householdForHoeseng = 1 + minorChildren;
        if (householdForHoeseng > 6) householdForHoeseng = 6;
        int livingCostHoeseng = livingCostTable[householdForHoeseng];

        // 신복위용 가구원: 본인 + 미성년자녀만
        int householdForShinbok = 1 + minorChildren;
        if (householdForShinbok > 6) householdForShinbok = 6;
        int livingCostShinbok = livingCostTable[householdForShinbok];

        // ============= 단기(회생) 계산 =============
        boolean shortTermBlocked = false;
        String shortTermBlockReason = "";
        int currentYear = java.time.LocalDate.now().getYear();

        if (netProperty > targetDebt && targetDebt > 0) {
            shortTermBlocked = true;
            shortTermBlockReason = "재산초과";
        }
        if (hasDischarge && dischargeYear > 0 && (currentYear - dischargeYear) < 5) {
            shortTermBlocked = true;
            shortTermBlockReason = "면책 5년 이내";
        }

        int shortTermMonthly = income - livingCostHoeseng;
        if (shortTermMonthly <= 0 && income > livingCostTable[1]) {
            shortTermMonthly = income - livingCostTable[1];
        }

        int shortTermMonths = 0;
        int shortTermTotalPayment = 0;
        String shortTermResult = "";

        if (!shortTermBlocked && shortTermMonthly > 0) {
            // 대상채무 / 월변제금으로 개월수 계산 (반올림)
            shortTermMonths = (int) Math.round((double) targetDebt / shortTermMonthly);
            if (shortTermMonths > 60) shortTermMonths = 60;
            if (shortTermMonths < 1) shortTermMonths = 1;

            int roundedShortTerm = ((shortTermMonthly + 2) / 5) * 5;
            shortTermResult = roundedShortTerm + "만 / " + shortTermMonths + "개월납";
        } else if (shortTermBlocked) {
            shortTermResult = "단기 불가 (" + shortTermBlockReason + ")";
        } else {
            shortTermResult = "단기 불가 (소득 < 생계비)";
            shortTermBlocked = true;
        }

        // ============= 공제기준가액 계산 (장기/신복위용) =============
        // 지역별 기준가액 (A) - 단위: 만원
        int baseExemption = 7500; // 기타 지역 기본값

        String regionLower = region.toLowerCase();
        if (regionLower.contains("서울")) {
            baseExemption = 16500;
        } else if (regionLower.contains("용인") || regionLower.contains("화성") || regionLower.contains("세종") ||
                regionLower.contains("김포") || regionLower.contains("고양") || regionLower.contains("과천") ||
                regionLower.contains("성남") || regionLower.contains("하남") || regionLower.contains("광명") ||
                regionLower.contains("인천")) {
            baseExemption = 14500; // 과밀억제권역
        } else if (regionLower.contains("부산") || regionLower.contains("대구") || regionLower.contains("광주") ||
                regionLower.contains("대전") || regionLower.contains("울산") || regionLower.contains("안산") ||
                regionLower.contains("파주") || regionLower.contains("이천") || regionLower.contains("평택")) {
            baseExemption = 8500; // 광역시 등
        }

        // 가구원수별 공제기준가액 계산
        // 가구원 = 본인 + 배우자(기혼시) + 미성년자녀
        int householdForExemption = 1 + minorChildren;
        if (hasSpouse) {
            householdForExemption += 1;
        }
        int exemptionAmount = baseExemption; // 3인 기준 (A×100%)
        if (householdForExemption <= 2) {
            exemptionAmount = (int)(baseExemption * 0.8); // 2인 이하 (A×80%)
        } else if (householdForExemption >= 4) {
            exemptionAmount = (int)(baseExemption * 1.2); // 4인 이상 (A×120%)
        }

        android.util.Log.d("HWP_CALC", "공제기준가액: " + exemptionAmount + "만 (지역: " + region + ", 가구: " + householdForExemption + "인)");

        // 장기 재산초과 판단: (순재산 - 공제기준가액) > 대상채무
        int netPropertyAfterExemption = netProperty - exemptionAmount;
        if (netPropertyAfterExemption < 0) netPropertyAfterExemption = 0;
        boolean longTermPropertyExcess = netPropertyAfterExemption > targetDebt && targetDebt > 0;
        android.util.Log.d("HWP_CALC", "장기 재산초과 판단: (" + netProperty + " - " + exemptionAmount + ") = " + netPropertyAfterExemption + " > " + targetDebt + " → " + longTermPropertyExcess);

        // ============= 장기(신복위) 보수 계산 =============
        int availableIncome = income - livingCostShinbok;

        int yearlyIncomeCalc = income * 12;
        int longTermYears = 0;
        int longTermMonthly = 0;

        // 총변제금 계산 (대상채무 × 변제율)
        int totalPayment = (int)(targetDebt * repaymentRate / 100.0);

        if (availableIncome > 0 && targetDebt > 0) {
            // 가용소득 양수: 기존 로직
            if (yearlyIncomeCalc > targetDebt) {
                longTermYears = 3;
            } else {
                double exactYears = (double) targetDebt / availableIncome / 12.0;
                longTermYears = (int) Math.ceil(exactYears);
                if (longTermYears < 5) longTermYears = 5;
                if (longTermYears > 10) longTermYears = 10;
            }
            longTermMonthly = (int) Math.ceil((double) targetDebt / (longTermYears * 12));
        } else if (targetDebt > 0) {
            // 가용소득 0 이하: 총변제금 / 120
            longTermYears = 10;
            longTermMonthly = (int) Math.ceil((double) totalPayment / 120);
        }

        // 최소 변제금 = 대상채무 × 1.5%
        int minPayment = (int) Math.ceil(targetDebt * 1.5 / 100.0);

        int roundedLongTermMonthly = ((longTermMonthly + 2) / 5) * 5;
        if (roundedLongTermMonthly < minPayment && targetDebt > 0) {
            roundedLongTermMonthly = ((minPayment + 4) / 5) * 5; // 올림
        }

        // ============= 장기(신복위) 공격 계산 =============
        // 공격 기본값 = 보수 × 2/3
        int aggressiveYears = 0;
        int aggressiveMonthly = 0;
        int roundedAggressiveMonthly = 0;

        if (targetDebt > 0 && roundedLongTermMonthly > 0) {
            // Step 1: 보수 × 2/3
            aggressiveMonthly = (int) Math.ceil(roundedLongTermMonthly * 2.0 / 3.0);

            // Step 2: 5의 배수로 반올림 (최소 변제금 제한 없음)
            roundedAggressiveMonthly = ((aggressiveMonthly + 2) / 5) * 5;
            if (roundedAggressiveMonthly < 40) roundedAggressiveMonthly = 40;

            // Step 3: 예외규칙 - 공격월변제금 × 120 < 총변제금이면 보수와 동일
            if (roundedAggressiveMonthly * 120 < totalPayment) {
                roundedAggressiveMonthly = roundedLongTermMonthly;
                aggressiveYears = longTermYears;
            } else {
                // 년수 계산
                aggressiveYears = (int) Math.ceil((double) totalPayment / roundedAggressiveMonthly / 12.0);
                if (aggressiveYears > 10) aggressiveYears = 10;
                if (aggressiveYears < 1) aggressiveYears = 1;
            }
        }

        this.acost = roundedLongTermMonthly;

        // ============= 새출발기금 계산 =============
        boolean canApplySae = (hasBusinessHistory || hasBusinessLoan) && delinquentDays < 90;

        int saeTotalPayment = 0;
        int saeMonthlyConservative = 0;
        int saeMonthlyAggressive = 0;

        if (canApplySae && targetDebt > 0) {
            int netDebtAfterProperty = targetDebt - netProperty;
            if (netDebtAfterProperty < 0) netDebtAfterProperty = 0;
            saeTotalPayment = targetDebt - (int)(netDebtAfterProperty * 0.35);
            saeTotalPayment = ((saeTotalPayment + 2) / 5) * 5;

            saeMonthlyConservative = (int) Math.ceil((double) saeTotalPayment / 72);
            saeMonthlyConservative = ((saeMonthlyConservative + 2) / 5) * 5;

            saeMonthlyAggressive = (int) Math.ceil((double) saeTotalPayment / 120);
            saeMonthlyAggressive = ((saeMonthlyAggressive + 2) / 5) * 5;
            if (saeMonthlyAggressive < 35) saeMonthlyAggressive = 35;
        }

        // ============= 특이사항 추가 =============
        // 가구원 정보 (맨 앞에 추가)
        StringBuilder familyInfo = new StringBuilder();
        if (hasSpouse || minorChildren > 0 || collegeChildren > 0) {
            familyInfo.append("기혼");
        } else {
            familyInfo.append("미혼");
        }
        if (minorChildren > 0) {
            familyInfo.append(" 미성년").append(minorChildren);
        }
        if (collegeChildren > 0) {
            familyInfo.append(" 대학생").append(collegeChildren);
        }
        if (parentCount > 0) {
            familyInfo.append(" 60세부모").append(parentCount);
        }
        specialNotesList.add(0, familyInfo.toString());

        // 과반 채권사 체크 (대상채무의 50% 초과)
        if (targetDebt > 0 && !creditorDebts.isEmpty()) {
            for (java.util.Map.Entry<String, Integer> entry : creditorDebts.entrySet()) {
                String creditor = entry.getKey();
                int amountCheon = entry.getValue(); // 천원 단위
                int amountMan = (amountCheon + 5) / 10; // 만원 반올림
                double ratio = (double) amountMan / targetDebt * 100;
                if (ratio > 50) {
                    specialNotesList.add(creditor + " 과반 (" + String.format("%.0f", ratio) + "%)");
                    android.util.Log.d("HWP_CALC", "과반 채권사: " + creditor + " " + amountMan + "만 / " + targetDebt + "만 = " + String.format("%.1f", ratio) + "%");
                }
            }
        }

        // 6개월 비율 30% 초과시 추가
        if (recentDebtRatio >= 30) {
            specialNotesList.add("6개월 " + String.format("%.0f", recentDebtRatio) + "%");
        }

        if (carValue >= 1000 || carMonthlyPayment >= 50) {
            specialNotesList.add("차량 처분 필요");
        }
        if (carCount >= 2) {
            specialNotesList.add("차량 " + carCount + "대 보유");
        }
        if (hasDischarge && dischargeYear > 0) {
            int availableYear = dischargeYear + 5;
            if (currentYear < availableYear) {
                specialNotesList.add("면책 " + dischargeYear + "년 → " + availableYear + "년 이후 회생");
            }
        }
        if (repaymentRate == 95) {
            specialNotesList.add("변제율 95% (" + rateReason + ")");
        }

        // ============= 진단 코드 결정 =============
        String diagnosis = "";
        String diagnosisNote = "";

        String delinquentPrefix = "";
        if (delinquentDays < 30) {
            delinquentPrefix = "신";
        } else if (delinquentDays < 90) {
            delinquentPrefix = "프";
        } else {
            delinquentPrefix = "회";
        }

        boolean hasYuwoCond = (netProperty > targetDebt && targetDebt > 0) ||
                recentDebtRatio >= 30 ||
                (hasDischarge && (currentYear - dischargeYear) < 5);

        boolean isBangsaeng = false;
        String bangsaengReason = "";

        // 방생 판단: 단기 재산초과 + 장기 재산초과 + 새새 불가
        if (netProperty > targetDebt && targetDebt > 0) {
            if (shortTermBlocked && longTermPropertyExcess && !canApplySae) {
                isBangsaeng = true;
                bangsaengReason = "재산초과";
            }
        }

        if (!isBangsaeng && roundedLongTermMonthly > 0) {
            int remainingAfterPayment = income - roundedAggressiveMonthly;
            if (remainingAfterPayment <= 30) {
                isBangsaeng = true;
                bangsaengReason = "소득부족";
            }
        }

        // 단기 총 납부액 계산
        int shortTermTotal = 0;
        if (!shortTermBlocked && shortTermMonthly > 0) {
            int roundedShortTerm = ((shortTermMonthly + 2) / 5) * 5;
            shortTermTotal = roundedShortTerm * shortTermMonths;
        }

        // 장기 총 납부액 계산
        int longTermTotal = roundedLongTermMonthly * longTermYears * 12;
        int aggressiveTotal = roundedAggressiveMonthly * aggressiveYears * 12;

        // 가장 유리한 옵션 찾기
        int minTotal = Integer.MAX_VALUE;
        String bestOption = "";

        if (!shortTermBlocked && shortTermTotal > 0) {
            minTotal = shortTermTotal;
            bestOption = "단기";
        }
        if (!longTermPropertyExcess && longTermTotal > 0 && longTermTotal < minTotal) {
            minTotal = longTermTotal;
            bestOption = "장기보수";
        }
        if (!longTermPropertyExcess && aggressiveTotal > 0 && aggressiveTotal < minTotal) {
            minTotal = aggressiveTotal;
            bestOption = "장기공격";
        }

        // 차량 처분시 장기 가능 여부 체크
        // 차량 처분시: 재산 - 차량순재산, 대상채무 + (차량대출 - 차량시세) if 대출 > 시세
        boolean canLongTermAfterCarSale = false;
        if (longTermPropertyExcess && (carValue > 0 || carTotalLoan > carTotalSise)) {
            int propertyAfterCarSale = netProperty - carValue;
            int debtAfterCarSale = targetDebt;
            if (carTotalLoan > carTotalSise) {
                debtAfterCarSale += (carTotalLoan - carTotalSise);
            }
            int netAfterExemption = propertyAfterCarSale - exemptionAmount;
            if (netAfterExemption < 0) netAfterExemption = 0;
            if (netAfterExemption <= debtAfterCarSale) {
                canLongTermAfterCarSale = true;
            }
            android.util.Log.d("HWP_CALC", "차량 처분시: 재산=" + propertyAfterCarSale + ", 공제후=" + netAfterExemption + ", 대상채무=" + debtAfterCarSale + " → " + canLongTermAfterCarSale);
        }

        // 부동산 처분시 장기 가능 여부 체크
        boolean canLongTermAfterPropertySale = false;
        if (longTermPropertyExcess && propertyValue > 0) {
            int propertyAfterRealEstateSale = netProperty - propertyValue;
            int netAfterExemptionProperty = propertyAfterRealEstateSale - exemptionAmount;
            if (netAfterExemptionProperty < 0) netAfterExemptionProperty = 0;
            if (netAfterExemptionProperty <= targetDebt) {
                canLongTermAfterPropertySale = true;
            }
            android.util.Log.d("HWP_CALC", "부동산 처분시: 재산=" + propertyAfterRealEstateSale + ", 공제후=" + netAfterExemptionProperty + ", 대상채무=" + targetDebt + " → " + canLongTermAfterPropertySale);
        }

        // 차량 처분시 적용될 진단 계산
        String diagnosisAfterCarSale = "";
        if (canLongTermAfterCarSale) {
            if (delinquentDays >= 1095) {
                diagnosisAfterCarSale = "워유워";
            } else if (delinquentDays >= 90) {
                diagnosisAfterCarSale = shortTermBlocked ? "회유워" : "회워";
            } else if (delinquentDays >= 30) {
                diagnosisAfterCarSale = shortTermBlocked ? "프유워" : "프회워";
            } else {
                diagnosisAfterCarSale = shortTermBlocked ? "신유워" : "신회워";
            }
        }

        if (isBangsaeng) {
            diagnosis = "방생";
            if (!bangsaengReason.isEmpty()) {
                diagnosisNote = "(" + bangsaengReason + ")";
            }
            // 처분시 장기 가능하면 추가 (최소 처분 우선: 차량 > 부동산)
            if (canLongTermAfterCarSale) {
                diagnosisNote += " (차량 처분시 " + diagnosisAfterCarSale + " 가능)";
            } else if (canLongTermAfterPropertySale) {
                diagnosisNote += " (부동산 처분시 " + diagnosisAfterCarSale + " 가능)";
            }
        } else if (bestOption.equals("단기")) {
            // 단기가 가장 유리
            diagnosis = "단순유리";
        } else if (canApplySae && saeTotalPayment > 0) {
            int shinbokTotal = roundedLongTermMonthly * longTermYears * 12;
            if (saeTotalPayment <= shinbokTotal) {
                diagnosis = "새새";
            } else {
                diagnosis = delinquentPrefix + (hasYuwoCond ? "유워" : "회워");
            }
        } else if (hasShinbokwiHistory) {
            // 신복위 이력 있을 때
            if (isBangsaeng) {
                // 소득 부족, 제도 전환도 의미 없음 → 방생
                diagnosis = "방생";
            } else if (!shortTermBlocked || (availableIncome > 0 && !longTermPropertyExcess)) {
                // 소득 있고 회생이 가능한 구조 → 회워
                diagnosis = "회워";
            } else {
                // 신복위 유지가 더 유리 → 기존 장기 진단 + 특이사항에 표시
                diagnosis = "워유워";
                specialNotesList.add("현재 신복위 변제 중 (유지 권장)");
            }
        } else if (delinquentDays >= 1095) {
            diagnosis = "워유워";
        } else if (delinquentDays >= 90) {
            diagnosis = hasYuwoCond ? "회유워" : "회워";
        } else if (delinquentDays >= 30) {
            if (hasYuwoCond) {
                diagnosis = "프유워";
            } else if (!shortTermBlocked) {
                diagnosis = "프회워";
            } else {
                diagnosis = "프유워";
            }
        } else {
            // 30일 미만 연체 (신규)
            if (!shortTermBlocked) {
                diagnosis = "신회워";
            } else {
                diagnosis = "신유워";
            }
        }

        if (recentDebtRatio >= 30 && !lastCreditDate.isEmpty()) {
            diagnosisNote = lastCreditDate + " +6개월 이후";
        }

        // ============= UI 업데이트 =============
        getBinding().name.setText("[이름] " + name);
        getBinding().card.setText("[소득] " + income + "만");
        getBinding().dat.setText("[대상] " + formatToEok(targetDebt));
        getBinding().money.setText("[재산] " + formatToEok(netProperty));

        StringBuilder specialNotesText = new StringBuilder();
        if (!specialNotesList.isEmpty()) {
            specialNotesText.append("[특이] ");
            int noteCount = 0;
            for (String note : specialNotesList) {
                if (noteCount >= 6) break;
                if (noteCount > 0) specialNotesText.append("\n");
                specialNotesText.append(note);
                noteCount++;
            }
        }
        getBinding().use.setText(specialNotesText.toString());

        getBinding().test1.setText("[단기] " + shortTermResult);

        StringBuilder longTermText = new StringBuilder();
        if (longTermPropertyExcess) {
            // 처분시 장기 가능하면 계산해서 표시
            if (canLongTermAfterCarSale || canLongTermAfterPropertySale) {
                int newTargetDebt = targetDebt;
                if (canLongTermAfterCarSale && carTotalLoan > carTotalSise) {
                    newTargetDebt = targetDebt + (carTotalLoan - carTotalSise);
                }

                // 처분시 장기 보수 계산
                int dispTotalPayment = (int)(newTargetDebt * repaymentRate / 100.0);
                int dispMinPayment = (int) Math.ceil(newTargetDebt * 1.5 / 100.0); // 최소 변제금액 = 대상채무 × 1.5%
                int dispLongTermYears = 10;
                int dispLongTermMonthly = (int) Math.ceil((double) dispTotalPayment / 120);
                int dispRoundedLongTerm = ((dispLongTermMonthly + 2) / 5) * 5;
                if (dispRoundedLongTerm < dispMinPayment) {
                    dispRoundedLongTerm = ((dispMinPayment + 4) / 5) * 5; // 올림
                }

                // 처분시 장기 공격 계산
                int dispAggressiveMonthly = (int) Math.ceil(dispRoundedLongTerm * 2.0 / 3.0);
                int dispRoundedAggressive = ((dispAggressiveMonthly + 2) / 5) * 5;
                if (dispRoundedAggressive < 40) dispRoundedAggressive = 40;

                int dispAggressiveYears = (int) Math.ceil((double) dispTotalPayment / dispRoundedAggressive / 12.0);
                if (dispAggressiveYears > 10) dispAggressiveYears = 10;

                // 예외규칙: 공격월변제금 × 120 < 총변제금이면 보수와 동일
                if (dispRoundedAggressive * 120 < dispTotalPayment) {
                    dispRoundedAggressive = dispRoundedLongTerm;
                    dispAggressiveYears = dispLongTermYears;
                }

                String dispType = canLongTermAfterCarSale ? "차량" : "부동산";
                longTermText.append("[장기 보수] ").append(dispRoundedLongTerm).append("만 / ").append(dispLongTermYears).append("년납 (").append(dispType).append(" 처분시)\n");
                longTermText.append("[장기 공격] ").append(dispRoundedAggressive).append("만 / ").append(dispAggressiveYears).append("년납 (").append(dispType).append(" 처분시)");
            } else {
                longTermText.append("[장기 보수] 장기 불가 (재산초과)\n");
                longTermText.append("[장기 공격] 장기 불가 (재산초과)");
            }
        } else {
            longTermText.append("[장기 보수] ").append(roundedLongTermMonthly).append("만 / ").append(longTermYears).append("년납\n");
            longTermText.append("[장기 공격] ").append(roundedAggressiveMonthly).append("만 / ").append(aggressiveYears).append("년납");
        }
        if (canApplySae && saeTotalPayment > 0) {
            longTermText.append("\n[새새 보수] ").append(saeMonthlyConservative).append("만 / 6년납");
            longTermText.append("\n[새새 공격] ").append(saeMonthlyAggressive).append("만 / 10년납");
        }
        getBinding().test2.setText(longTermText.toString());

        String finalDiagnosis = diagnosis;
        if (!diagnosisNote.isEmpty()) {
            finalDiagnosis += " " + diagnosisNote;
        }
        getBinding().testing.setText("[진단] " + finalDiagnosis);

        getBinding().half.setText("");

        android.util.Log.d("HWP_CALC", String.format(
                "이름: %s, 소득: %d만, 대상: %d만, 재산: %d만, 6개월비율: %.1f%%, 진단: %s",
                name, income, targetDebt, netProperty, recentDebtRatio, diagnosis));
    }

    // 특정 키워드 뒤의 금액 추출
    private int extractAmountAfterKeyword(String text, String keyword) {
        if (!text.contains(keyword)) return 0;

        int idx = text.indexOf(keyword);
        String afterKeyword = text.substring(idx);
        return extractAmount(afterKeyword);
    }

    // 만원 단위를 억 단위 문자열로 변환
    private String formatToEok(int amountInMan) {
        if (amountInMan >= 10000) {
            int eok = amountInMan / 10000;
            int man = amountInMan % 10000;
            if (man > 0) {
                return eok + "억" + man + "만";
            } else {
                return eok + "억";
            }
        } else {
            return amountInMan + "만";
        }
    }

    private int extractAmount(String text) {
        try {
            // "4억5천" → 45000만
            java.util.regex.Matcher cheonMatcher = java.util.regex.Pattern.compile("(\\d+)억(\\d+)천").matcher(text);
            if (cheonMatcher.find()) {
                int eok = Integer.parseInt(cheonMatcher.group(1)) * 10000;
                int cheon = Integer.parseInt(cheonMatcher.group(2)) * 1000;
                return eok + cheon;
            }

            // "2억7천" 형식 (공백 없음)
            cheonMatcher = java.util.regex.Pattern.compile("(\\d+)억\\s*(\\d+)천").matcher(text);
            if (cheonMatcher.find()) {
                int eok = Integer.parseInt(cheonMatcher.group(1)) * 10000;
                int cheon = Integer.parseInt(cheonMatcher.group(2)) * 1000;
                return eok + cheon;
            }

            // "1억 630만" 형식 (억 + 공백 + 만)
            java.util.regex.Pattern patternWithSpace = java.util.regex.Pattern.compile("(\\d+)억\\s*(\\d+)만");
            java.util.regex.Matcher matcherWithSpace = patternWithSpace.matcher(text);
            if (matcherWithSpace.find()) {
                int billions = Integer.parseInt(matcherWithSpace.group(1)) * 10000;
                int millions = Integer.parseInt(matcherWithSpace.group(2));
                return billions + millions;
            }

            // "3억9965만" 형식 (공백 없음)
            java.util.regex.Pattern patternFull = java.util.regex.Pattern.compile("(\\d+)억(\\d+)만");
            java.util.regex.Matcher matcherFull = patternFull.matcher(text);
            if (matcherFull.find()) {
                int billions = Integer.parseInt(matcherFull.group(1)) * 10000;
                int millions = Integer.parseInt(matcherFull.group(2));
                return billions + millions;
            }

            // "3억" 만 있는 경우
            java.util.regex.Pattern patternBillion = java.util.regex.Pattern.compile("(\\d+)억");
            java.util.regex.Matcher matcherBillion = patternBillion.matcher(text);
            if (matcherBillion.find()) {
                int total = Integer.parseInt(matcherBillion.group(1)) * 10000;
                // 뒤에 "만"이 있으면 합산
                java.util.regex.Matcher matcherMillion = java.util.regex.Pattern.compile("(\\d+)만").matcher(text);
                if (matcherMillion.find()) {
                    total += Integer.parseInt(matcherMillion.group(1));
                }
                return total;
            }

            // "9965만" 형식
            java.util.regex.Pattern patternMillion = java.util.regex.Pattern.compile("(\\d+)만");
            java.util.regex.Matcher matcherMillion = patternMillion.matcher(text);
            if (matcherMillion.find()) {
                return Integer.parseInt(matcherMillion.group(1));
            }

            // 콤마 숫자 (9,554) - 마지막에 있는 경우
            java.util.regex.Pattern patternComma = java.util.regex.Pattern.compile("([\\d,]+)$");
            java.util.regex.Matcher matcherComma = patternComma.matcher(text.trim());
            if (matcherComma.find()) {
                String numStr = matcherComma.group(1).replace(",", "");
                if (!numStr.isEmpty()) {
                    return Integer.parseInt(numStr);
                }
            }

        } catch (Exception e) {
            android.util.Log.e("HWP_PARSE", "금액 추출 오류: " + text, e);
        }
        return 0;
    }

    public final void processTextResult(Text visionText) {
        showToast("이 기능은 비활성화되었습니다.");
    }

    private final void processImage(Bitmap bitmap) {
        InputImage fromBitmap = InputImage.fromBitmap(bitmap, 0);
        Intrinsics.checkNotNullExpressionValue(fromBitmap, "fromBitmap(bitmap, 0)");
        TextRecognizer client = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        Intrinsics.checkNotNullExpressionValue(client, "getClient(...)");
        Task<Text> process = client.process(fromBitmap);
        final MainActivity mainActivity = this;
        process.addOnSuccessListener(new OnSuccessListener<Text>() {
            @Override
            public void onSuccess(Text visionText) {
                Intrinsics.checkNotNullExpressionValue(visionText, "visionText");
                mainActivity.processTextResult(visionText);
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(Exception exc) {
                MainActivity.processImage$lambda$3(mainActivity, exc);
            }
        });
    }

    private static final void processImage$lambda$3(MainActivity this$0, Exception e) {
        Intrinsics.checkNotNullParameter(this$0, "this$0");
        Intrinsics.checkNotNullParameter(e, "e");
        this$0.showToast("Text recognition failed: " + e.getMessage());
    }

    private final void readExcelFile(android.net.Uri r56) {
        throw new UnsupportedOperationException("Method not decompiled: com.main.lego.MainActivity.readExcelFile(android.net.Uri):void");
    }

    private static final void readExcelFile$lambda$6(MainActivity this$0, View view) {
        Intrinsics.checkNotNullParameter(this$0, "this$0");
        Object systemService = this$0.getSystemService("clipboard");
        Intrinsics.checkNotNull(systemService, "null cannot be cast to non-null type android.content.ClipboardManager");
        ((ClipboardManager) systemService).setPrimaryClip(ClipData.newPlainText("resultText", this$0.buildResultText()));
        this$0.showToast("텍스트가 클립보드에 복사되었습니다.");
    }

    private final String getCellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        org.apache.poi.ss.usermodel.CellType cellType = cell.getCellType();
        if (cellType == org.apache.poi.ss.usermodel.CellType.STRING) {
            String stringCellValue = cell.getStringCellValue();
            Intrinsics.checkNotNullExpressionValue(stringCellValue, "cell.stringCellValue");
            return stringCellValue;
        }
        if (cellType == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
            return String.valueOf(cell.getNumericCellValue());
        }
        return "";
    }

    private final void saveResultToFileAndShare(Uri uri) {
        try {
            OutputStream openOutputStream = getContentResolver().openOutputStream(uri);
            if (openOutputStream != null) {
                OutputStream outputStream = openOutputStream;
                try {
                    byte[] bytes = buildResultText().getBytes(Charsets.UTF_8);
                    Intrinsics.checkNotNullExpressionValue(bytes, "this as java.lang.String).getBytes(charset)");
                    outputStream.write(bytes);
                    Unit unit = Unit.INSTANCE;
                    CloseableKt.closeFinally(outputStream, null);
                } finally {
                }
            }
            showToast("파일로 저장되었습니다.");
        } catch (Exception e) {
            e.printStackTrace();
            showToast("저장에 실패하였습니다.");
        }
    }

    private final String buildResultText() {
        String name = getBinding().name.getText().toString();
        String testing = getBinding().testing.getText().toString();
        CharSequence test1 = getBinding().test1.getText();
        CharSequence test2 = getBinding().test2.getText();
        CharSequence card = getBinding().card.getText();
        CharSequence dat = getBinding().dat.getText();
        CharSequence money = getBinding().money.getText();
        CharSequence use = getBinding().use.getText();
        CharSequence half = getBinding().half.getText();

        StringBuilder sb = new StringBuilder();
        sb.append(name).append("\n\n");
        sb.append(card).append('\n');
        sb.append(dat).append('\n');
        sb.append(money).append("\n\n");
        sb.append(use).append('\n');
        sb.append(half).append("\n\n");
        sb.append(test1).append('\n');
        sb.append(test2).append("\n\n");
        sb.append(testing);
        return sb.toString();
    }

    private final void showToast(String message) {
        Toast.makeText(this, message, 0).show();
    }
}