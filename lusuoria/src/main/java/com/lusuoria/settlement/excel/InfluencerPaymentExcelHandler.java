package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.entity.InfluencerPayment;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import com.lusuoria.settlement.repository.InfluencerPaymentRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 红人结款 Excel 导入/导出
 *
 * 敏感列（仅 ADMIN / AUDITOR）：
 *   payableAmount（应付金额）、rmbAmount（人民币金额）、paidAmount（已付金额）
 *
 * 非敏感列（所有角色）：
 *   结款单号、结算月份、红人信息、合作内容/数量、红人单价、币种、汇率、日期、状态、备注
 */
@Component
public class InfluencerPaymentExcelHandler {

    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerPaymentRepository paymentRepo;

    // ===== 导出 =====
    public void export(List<InfluencerPayment> payments, boolean canViewSensitive,
                       HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = "红人结款_" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("红人结款");
        XSSFCellStyle hdrN  = createHeaderStyle(wb, false);
        XSSFCellStyle hdrS  = createHeaderStyle(wb, true);
        XSSFCellStyle money = createMoneyStyle(wb);
        XSSFCellStyle nor   = createNormalStyle(wb);

        // 列定义：(标题, 是否敏感)
        List<String[]> cols = new ArrayList<String[]>();
        cols.add(new String[]{"结款单号",     "0"});
        cols.add(new String[]{"结算月份",     "0"});
        cols.add(new String[]{"红人团队",     "0"});
        cols.add(new String[]{"红人ID",     "0"});
        cols.add(new String[]{"关联项目编号", "0"});
        cols.add(new String[]{"合作内容",     "0"});
        cols.add(new String[]{"合作数量",     "0"});
        cols.add(new String[]{"红人单价",     "0"});
        cols.add(new String[]{"应付金额",     "0"});
        cols.add(new String[]{"币种",         "0"});
        cols.add(new String[]{"汇率",         "0"});
        cols.add(new String[]{"人民币金额",   "0"});
        cols.add(new String[]{"对账日期",     "0"});
        cols.add(new String[]{"预计付款日",   "0"});
        cols.add(new String[]{"实际付款日",   "0"});
        cols.add(new String[]{"付款状态",     "0"});
        cols.add(new String[]{"已付金额",     "0"});
        cols.add(new String[]{"备注",         "0"});

        Row headerRow = sheet.createRow(0);
        int colIdx = 0;
        for (String[] col : cols) {
            if ("1".equals(col[1]) && !canViewSensitive) continue;
            Cell cell = headerRow.createCell(colIdx++);
            cell.setCellValue(col[0]);
            cell.setCellStyle("1".equals(col[1]) ? hdrS : hdrN);
        }
        for (int i = 0; i < colIdx; i++) sheet.setColumnWidth(i, 16 * 256);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        for (int i = 0; i < payments.size(); i++) {
            InfluencerPayment p = payments.get(i);
            Row row = sheet.createRow(i + 1);
            int c = 0;

            setCellStr(row, c++, p.getPaymentNo(), nor);
            setCellStr(row, c++, p.getSettlementMonth(), nor);
            setCellStr(row, c++, p.getInfluencer() != null ? p.getInfluencer().getTeamName()    : "", nor);
            setCellStr(row, c++, p.getInfluencer() != null ? p.getInfluencer().getAccountName() : "", nor);
            setCellStr(row, c++, p.getProjectOrder() != null ? p.getProjectOrder().getInternalProjectNo() : "", nor);
            setCellStr(row, c++, p.getCooperationContent(), nor);
            setCellNum(row, c++, p.getCooperationQuantity() != null ? (double) p.getCooperationQuantity() : null, nor);
            setCellMoney(row, c++, p.getInfluencerUnitPrice(), money);
            setCellMoney(row, c++, p.getPayableAmount(), money);
            setCellStr(row, c++, p.getCurrency(), nor);
            setCellMoney(row, c++, p.getExchangeRate(), money);
            setCellMoney(row, c++, p.getRmbAmount(), money);
            setCellStr(row, c++, p.getReconcileDate()       != null ? sdf.format(p.getReconcileDate())       : "", nor);
            setCellStr(row, c++, p.getExpectedPaymentDate() != null ? sdf.format(p.getExpectedPaymentDate()) : "", nor);
            setCellStr(row, c++, p.getActualPaymentDate()   != null ? sdf.format(p.getActualPaymentDate())   : "", nor);
            setCellStr(row, c++, p.getPaymentStatus() != null ? statusLabel(p.getPaymentStatus()) : "", nor);
            setCellMoney(row, c++, p.getPaidAmount(), money);
            setCellStr(row, c++, p.getNotes(), nor);
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===== 下载导入模板 =====
    public void downloadTemplate(boolean canViewSensitive, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode("红人结款导入模板.xlsx", "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("红人结款导入");
        XSSFCellStyle hdrN = createHeaderStyle(wb, false);
        XSSFCellStyle hdrS = createHeaderStyle(wb, true);

        List<String[]> cols = new ArrayList<String[]>();
        cols.add(new String[]{"结算月份(必填,如202604)", "0"});
        cols.add(new String[]{"红人ID(必填)",          "0"});
        cols.add(new String[]{"合作内容",                "0"});
        cols.add(new String[]{"合作数量",                "0"});
        cols.add(new String[]{"红人单价",                "0"});
        cols.add(new String[]{"应付金额",                "0"});
        cols.add(new String[]{"币种(USD/RMB)",           "0"});
        cols.add(new String[]{"汇率",                    "0"});
        cols.add(new String[]{"人民币金额",              "0"});
        cols.add(new String[]{"对账日期(yyyy-MM-dd)",    "0"});
        cols.add(new String[]{"预计付款日(yyyy-MM-dd)",  "0"});
        cols.add(new String[]{"实际付款日(yyyy-MM-dd)",  "0"});
        cols.add(new String[]{"付款状态",                "0"});
        cols.add(new String[]{"已付金额",                "0"});
        cols.add(new String[]{"备注",                    "0"});

        Row headerRow = sheet.createRow(0);
        int colIdx = 0;
        for (String[] col : cols) {
            if ("1".equals(col[1]) && !canViewSensitive) continue;
            Cell cell = headerRow.createCell(colIdx++);
            cell.setCellValue(col[0]);
            cell.setCellStyle("1".equals(col[1]) ? hdrS : hdrN);
            sheet.setColumnWidth(colIdx - 1, 22 * 256);
        }

        // 付款状态下拉
        int statusColIdx = getColIdx(cols, canViewSensitive, "付款状态");
        if (statusColIdx >= 0) {
            DataValidationHelper dvHelper = sheet.getDataValidationHelper();
            DataValidation dv = dvHelper.createValidation(
                    dvHelper.createExplicitListConstraint(new String[]{
                            "待对账", "已对账", "待付款", "部分付款", "已付款", "异常"}),
                    new CellRangeAddressList(1, 1000, statusColIdx, statusColIdx));
            dv.setShowErrorBox(true);
            sheet.addValidationData(dv);
        }

        // 示例行
        Row ex = sheet.createRow(1);
        int ec = 0;
        for (String[] col : cols) {
            if ("1".equals(col[1]) && !canViewSensitive) continue;
            ex.createCell(ec++).setCellValue(getPaymentExampleValue(col[0]));
        }

        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===== 导入（增量，按「结算月份+红人账号+合作内容」去重）=====
    public List<String> importData(MultipartFile file) throws IOException {
        List<String> errors = new ArrayList<String>();
        Workbook workbook = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);

        int totalRows = sheet.getLastRowNum();
        if (totalRows < 1) { errors.add("Excel 文件为空"); workbook.close(); return errors; }

        Row headerRow = sheet.getRow(0);
        Map<String, Integer> colMap = new HashMap<String, Integer>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null) colMap.put(cell.getStringCellValue().trim(), c);
        }

        // 构建账号 -> 红人 映射
        Map<String, Influencer> influencerMap = new HashMap<String, Influencer>();
        influencerRepo.findByIsDeletedFalseOrderByTeamNameAscAccountNameAsc()
                .forEach(inf -> influencerMap.put(inf.getAccountName().trim(), inf));

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        int successCount = 0, skipCount = 0;

        for (int i = 1; i <= totalRows; i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            try {
                String settlementMonth = getStr(row, colMap, "结算月份(必填,如202604)");
                String accountName     = getStr(row, colMap, "红人ID(必填)");
                String cooperationContent = getStr(row, colMap, "合作内容");

                if (settlementMonth == null || settlementMonth.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：结算月份不能为空"); continue;
                }
                if (accountName == null || accountName.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：红人ID不能为空"); continue;
                }
                Influencer influencer = influencerMap.get(accountName.trim());
                if (influencer == null) {
                    errors.add("第" + (i + 1) + "行：红人ID [" + accountName + "] 不存在"); continue;
                }

                // 去重：结算月份 + 红人 + 合作内容
                boolean alreadyExists = paymentRepo.existsByMonthInfluencerContent(
                        settlementMonth, influencer.getId(),
                        cooperationContent != null && !cooperationContent.isEmpty()
                                ? cooperationContent : null);
                if (alreadyExists) { skipCount++; continue; }

                InfluencerPayment payment = new InfluencerPayment();
                payment.setIsDeleted(false);
                payment.setPaymentNo("PAY-" + settlementMonth + "-"
                        + new SimpleDateFormat("HHmmssSSS").format(new Date()));
                payment.setSettlementMonth(settlementMonth);
                payment.setInfluencer(influencer);
                payment.setCooperationContent(cooperationContent);
                payment.setCooperationQuantity(getInt(row, colMap, "合作数量"));
                payment.setInfluencerUnitPrice(getBigDecimal(row, colMap, "红人单价"));
                payment.setPayableAmount(getBigDecimal(row, colMap, "应付金额"));
                payment.setCurrency(getStr(row, colMap, "币种(USD/RMB)"));
                payment.setExchangeRate(getBigDecimal(row, colMap, "汇率"));
                payment.setRmbAmount(getBigDecimal(row, colMap, "人民币金额"));
                payment.setReconcileDate(parseDate(sdf, getStr(row, colMap, "对账日期(yyyy-MM-dd)")));
                payment.setExpectedPaymentDate(parseDate(sdf, getStr(row, colMap, "预计付款日(yyyy-MM-dd)")));
                payment.setActualPaymentDate(parseDate(sdf, getStr(row, colMap, "实际付款日(yyyy-MM-dd)")));
                String statusStr = getStr(row, colMap, "付款状态");
                payment.setPaymentStatus(parseStatus(statusStr));
                payment.setPaidAmount(getBigDecimal(row, colMap, "已付金额"));
                payment.setNotes(getStr(row, colMap, "备注"));

                paymentRepo.save(payment);
                successCount++;
            } catch (Exception e) {
                errors.add("第" + (i + 1) + "行导入失败：" + e.getMessage());
            }
        }
        workbook.close();
        int failCount = errors.size();
        errors.add(0, "成功新增 " + successCount + " 条，跳过重复 " + skipCount + " 条，失败 " + failCount + " 条");
        return errors;
    }

    // ===== 工具方法 =====
    private String statusLabel(InfluencerPaymentStatus s) {
        switch (s) {
            case PENDING_RECONCILE: return "待对账";
            case RECONCILED:        return "已对账";
            case PENDING_PAYMENT:   return "待付款";
            case PARTIAL_PAYMENT:   return "部分付款";
            case PAID:              return "已付款";
            case ABNORMAL:          return "异常";
            default:                return s.name();
        }
    }

    private InfluencerPaymentStatus parseStatus(String label) {
        if (label == null) return InfluencerPaymentStatus.PENDING_RECONCILE;
        switch (label.trim()) {
            case "已对账":  return InfluencerPaymentStatus.RECONCILED;
            case "待付款":  return InfluencerPaymentStatus.PENDING_PAYMENT;
            case "部分付款":return InfluencerPaymentStatus.PARTIAL_PAYMENT;
            case "已付款":  return InfluencerPaymentStatus.PAID;
            case "异常":    return InfluencerPaymentStatus.ABNORMAL;
            default:        return InfluencerPaymentStatus.PENDING_RECONCILE;
        }
    }

    private java.util.Date parseDate(SimpleDateFormat sdf, String str) {
        if (str == null || str.isEmpty()) return null;
        try { return sdf.parse(str); } catch (ParseException e) { return null; }
    }

    private int getColIdx(List<String[]> cols, boolean canViewSensitive, String title) {
        int idx = 0;
        for (String[] col : cols) {
            if ("1".equals(col[1]) && !canViewSensitive) continue;
            if (col[0].equals(title)) return idx;
            idx++;
        }
        return -1;
    }

    private String getPaymentExampleValue(String col) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("结算月份(必填,如202604)", "202604");
        m.put("红人ID(必填)",          "bigdogtech");
        m.put("合作内容",                "视频拍摄");
        m.put("合作数量",                "5");
        m.put("红人单价",                "130");
        m.put("应付金额",                "650");
        m.put("币种(USD/RMB)",           "USD");
        m.put("汇率",                    "7.25");
        m.put("人民币金额",              "4712.50");
        m.put("对账日期(yyyy-MM-dd)",    "2026-04-15");
        m.put("预计付款日(yyyy-MM-dd)",  "2026-04-30");
        m.put("实际付款日(yyyy-MM-dd)",  "");
        m.put("付款状态",                "待付款");
        m.put("已付金额",                "0");
        m.put("备注",                    "示例数据，填完后请删除");
        String v = m.get(col);
        return v != null ? v : "";
    }

    private String getStr(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue().trim();
            case NUMERIC: return String.valueOf((long) cell.getNumericCellValue());
            default:      return null;
        }
    }

    private BigDecimal getBigDecimal(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC)
                return BigDecimal.valueOf(cell.getNumericCellValue());
            if (cell.getCellType() == CellType.STRING) {
                String v = cell.getStringCellValue().trim();
                return v.isEmpty() ? null : new BigDecimal(v);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Integer getInt(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) return (int) cell.getNumericCellValue();
            if (cell.getCellType() == CellType.STRING) {
                String v = cell.getStringCellValue().trim();
                return v.isEmpty() ? null : Integer.parseInt(v);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void setCellStr(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private void setCellMoney(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private void setCellNum(Row row, int col, Double value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private boolean isRowEmpty(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String v = cell.getCellType() == CellType.STRING
                        ? cell.getStringCellValue().trim() : "x";
                if (!v.isEmpty()) return false;
            }
        }
        return true;
    }

    private XSSFCellStyle createHeaderStyle(XSSFWorkbook wb, boolean sensitive) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        byte[] color = sensitive
                ? new byte[]{(byte)211,(byte)84,(byte)0}
                : new byte[]{(byte)39,(byte)174,(byte)96};
        style.setFillForegroundColor(new XSSFColor(color, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private XSSFCellStyle createMoneyStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return style;
    }

    private XSSFCellStyle createNormalStyle(XSSFWorkbook wb) {
        return wb.createCellStyle();
    }
}
