package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.enums.PaymentCycleType;
import com.lusuoria.settlement.repository.BrandRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 品牌方 Excel 导入/导出
 * 品牌方无敏感字段，所有角色可见完整数据
 */
@Component
public class BrandExcelHandler {

    @Autowired private BrandRepository brandRepo;

    // ===== 导出 =====
    public void export(List<Brand> brands, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = "品牌方_" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("品牌方");
        XSSFCellStyle hdr  = createHeaderStyle(wb);
        XSSFCellStyle nor  = createNormalStyle(wb);

        String[] headers = {
            "品牌方名称", "国家/市场", "联系人", "结算币种",
            "付款周期类型", "阈值分档-成本阈值", "阈值分档-阈值以内天数", "阈值分档-阈值以上天数", "月结-对账日后天数",
            "备注"
        };
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(hdr);
            sheet.setColumnWidth(i, 20 * 256);
        }

        for (int i = 0; i < brands.size(); i++) {
            Brand b  = brands.get(i);
            Row row  = sheet.createRow(i + 1);
            int c    = 0;
            setCellStr(row, c++, b.getName(),              nor);
            setCellStr(row, c++, b.getCountryMarket(),     nor);
            setCellStr(row, c++, b.getContactPerson(),     nor);
            setCellStr(row, c++, b.getSettlementCurrency(), nor);
            setCellStr(row, c++, b.getPaymentCycleType() != null ? b.getPaymentCycleType().getLabel() : "", nor);
            setCellStr(row, c++, b.getCostThresholdAmount() != null ? b.getCostThresholdAmount().toPlainString() : "", nor);
            setCellStr(row, c++, b.getDaysWithinThreshold() != null ? String.valueOf(b.getDaysWithinThreshold()) : "", nor);
            setCellStr(row, c++, b.getDaysAboveThreshold()  != null ? String.valueOf(b.getDaysAboveThreshold())  : "", nor);
            setCellStr(row, c++, b.getDaysAfterMonthEnd()   != null ? String.valueOf(b.getDaysAfterMonthEnd())   : "", nor);
            setCellStr(row, c++, b.getNotes(),             nor);
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===== 下载导入模板 =====
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode("品牌方导入模板.xlsx", "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("品牌方导入");
        XSSFCellStyle hdr  = createHeaderStyle(wb);

        String[] headers = {
            "品牌方名称(必填)", "国家/市场", "联系人", "结算币种(USD/RMB)",
            "付款周期类型(按红人成本阈值分档/月底对账日后N天结款)",
            "阈值分档-成本阈值", "阈值分档-阈值以内天数", "阈值分档-阈值以上天数", "月结-对账日后天数",
            "备注"
        };
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(hdr);
            sheet.setColumnWidth(i, 22 * 256);
        }

        // 结算币种下拉
        DataValidationHelper dvHelper = sheet.getDataValidationHelper();
        DataValidation currencyDv = dvHelper.createValidation(
                dvHelper.createExplicitListConstraint(new String[]{"USD", "RMB", "EUR"}),
                new CellRangeAddressList(1, 1000, 3, 3));
        currencyDv.setShowErrorBox(true);
        sheet.addValidationData(currencyDv);

        // 付款周期类型下拉
        String[] cycleTypeLabels = { PaymentCycleType.COST_THRESHOLD.getLabel(), PaymentCycleType.MONTH_END.getLabel() };
        DataValidation cycleTypeDv = dvHelper.createValidation(
                dvHelper.createExplicitListConstraint(cycleTypeLabels),
                new CellRangeAddressList(1, 1000, 4, 4));
        cycleTypeDv.setShowErrorBox(true);
        sheet.addValidationData(cycleTypeDv);

        // 示例行：按红人成本阈值分档（阈值分档相关列填数字，月结列留空）
        Row ex = sheet.createRow(1);
        ex.createCell(0).setCellValue("TEMU");
        ex.createCell(1).setCellValue("美国");
        ex.createCell(2).setCellValue("张三");
        ex.createCell(3).setCellValue("USD");
        ex.createCell(4).setCellValue(PaymentCycleType.COST_THRESHOLD.getLabel());
        ex.createCell(5).setCellValue("1000");
        ex.createCell(6).setCellValue("30");
        ex.createCell(7).setCellValue("60");
        ex.createCell(8).setCellValue("");
        ex.createCell(9).setCellValue("示例数据，填完后请删除");

        wb.write(response.getOutputStream());
        wb.close();
    }

    /**
     * 公式求值器：Excel 里像"=xxx"这种公式单元格很常见，不处理的话会被 getStr() 判定成
     * "读不出来"返回 null。用 ThreadLocal 而不是普通实例字段是因为这个类是 Spring 单例 Bean，
     * 多个人同时导入时普通实例字段会互相覆盖，ThreadLocal 保证各自独立，互不干扰。
     */
    private static final ThreadLocal<FormulaEvaluator> FORMULA_EVALUATOR = new ThreadLocal<>();

    // ===== 导入（增量，按品牌方名称去重）=====
    public List<String> importData(MultipartFile file) throws IOException {
        List<String> errors = new ArrayList<String>();
        Workbook workbook = WorkbookFactory.create(file.getInputStream());
        FORMULA_EVALUATOR.set(workbook.getCreationHelper().createFormulaEvaluator());
        try {
        Sheet sheet = workbook.getSheetAt(0);

        int totalRows = sheet.getLastRowNum();
        if (totalRows < 1) { errors.add("Excel 文件为空"); workbook.close(); return errors; }

        // 读取表头列映射
        Row headerRow = sheet.getRow(0);
        Map<String, Integer> colMap = new HashMap<String, Integer>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null) colMap.put(cell.getStringCellValue().trim(), c);
        }

        // 表头完整性校验：少了关键列（比如表头被误改）直接拒绝整个文件，不再是
        // "这一列找不到就当作没填"这种静默处理
        String[] requiredHeaders = {
            "品牌方名称(必填)", "国家/市场", "联系人", "结算币种(USD/RMB)",
            "付款周期类型(按红人成本阈值分档/月底对账日后N天结款)",
            "阈值分档-成本阈值", "阈值分档-阈值以内天数", "阈值分档-阈值以上天数", "月结-对账日后天数",
            "备注"
        };
        List<String> missingColumns = new ArrayList<String>();
        for (String h : requiredHeaders) {
            if (!colMap.containsKey(h)) missingColumns.add("「" + h + "」");
        }
        if (!missingColumns.isEmpty()) {
            workbook.close();
            errors.add("导入失败：Excel 表头缺少以下必需的列（可能是表头被误改或者删除了），"
                    + "请对照模板核对后重新导入，本次没有导入任何数据：");
            errors.addAll(missingColumns);
            return errors;
        }

        int successCount = 0, skipCount = 0;
        for (int i = 1; i <= totalRows; i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            try {
                String name = getStr(row, colMap, "品牌方名称(必填)");
                if (name == null || name.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：品牌方名称不能为空"); continue;
                }
                // 去重：名称已存在则跳过
                if (brandRepo.existsByNameAndIsDeletedFalse(name)) {
                    skipCount++; continue;
                }
                Brand brand = new Brand();
                brand.setIsDeleted(false);
                brand.setName(name);
                brand.setCountryMarket(getStr(row, colMap, "国家/市场"));
                brand.setContactPerson(getStr(row, colMap, "联系人"));
                brand.setSettlementCurrency(getStr(row, colMap, "结算币种(USD/RMB)"));

                // 付款周期类型：留空表示还没配置，不是错误；填了但匹配不到有效选项才报错
                String cycleTypeRaw = getStr(row, colMap, "付款周期类型(按红人成本阈值分档/月底对账日后N天结款)");
                if (cycleTypeRaw != null && !cycleTypeRaw.trim().isEmpty()) {
                    PaymentCycleType cycleType = PaymentCycleType.fromLabel(cycleTypeRaw);
                    if (cycleType == null) {
                        errors.add("第" + (i + 1) + "行：付款周期类型 [" + cycleTypeRaw + "] 不是有效选项，请核对");
                        continue;
                    }
                    brand.setPaymentCycleType(cycleType);
                }
                try {
                    brand.setCostThresholdAmount(getDecimal(row, colMap, "阈值分档-成本阈值"));
                    brand.setDaysWithinThreshold(getInt(row, colMap, "阈值分档-阈值以内天数"));
                    brand.setDaysAboveThreshold(getInt(row, colMap, "阈值分档-阈值以上天数"));
                    brand.setDaysAfterMonthEnd(getInt(row, colMap, "月结-对账日后天数"));
                } catch (NumberFormatException e) {
                    errors.add("第" + (i + 1) + "行：付款周期的金额/天数必须是数字，请核对");
                    continue;
                }
                brand.setNotes(getStr(row, colMap, "备注"));
                brandRepo.save(brand);
                successCount++;
            } catch (Exception e) {
                errors.add("第" + (i + 1) + "行导入失败：" + e.getMessage());
            }
        }
        workbook.close();
        int failCount = errors.size();
        errors.add(0, "成功新增 " + successCount + " 条，跳过重复 " + skipCount + " 条，失败 " + failCount + " 条");
        return errors;
        } finally {
            FORMULA_EVALUATOR.remove();
        }
    }

    // ===== 工具 =====
    private String getStr(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.FORMULA) {
            FormulaEvaluator evaluator = FORMULA_EVALUATOR.get();
            if (evaluator == null) return null;
            CellValue value = evaluator.evaluate(cell);
            if (value == null) return null;
            switch (value.getCellType()) {
                case STRING:  return value.getStringValue().trim();
                case NUMERIC: return String.valueOf((long) value.getNumberValue());
                case BOOLEAN: return String.valueOf(value.getBooleanValue());
                default:      return null;
            }
        }
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue().trim();
            case NUMERIC: return String.valueOf((long) cell.getNumericCellValue());
            default:      return null;
        }
    }

    /**
     * 读取金额列，单元格为空返回 null，读不出数字抛 NumberFormatException。
     * 不走 getStr()：那个方法数字单元格是 (long) 强转，会把小数部分砍掉，
     * 阈值金额这种带小数的场景不能用（参考 CollaborationTrackingExcelHandler.getMoneyCell 的写法）。
     */
    private java.math.BigDecimal getDecimal(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.FORMULA) {
            FormulaEvaluator evaluator = FORMULA_EVALUATOR.get();
            if (evaluator == null) return null;
            CellValue value = evaluator.evaluate(cell);
            if (value == null) return null;
            switch (value.getCellType()) {
                case NUMERIC: return java.math.BigDecimal.valueOf(value.getNumberValue());
                case BLANK:   return null;
                case STRING: {
                    String s = value.getStringValue().trim();
                    return s.isEmpty() ? null : new java.math.BigDecimal(s.replaceAll(",", ""));
                }
                default: throw new NumberFormatException("公式结果不是数字");
            }
        }
        switch (cell.getCellType()) {
            case NUMERIC: return java.math.BigDecimal.valueOf(cell.getNumericCellValue());
            case BLANK:   return null;
            case STRING: {
                String s = cell.getStringCellValue().trim();
                return s.isEmpty() ? null : new java.math.BigDecimal(s.replaceAll(",", ""));
            }
            default: throw new NumberFormatException("不是数字");
        }
    }

    /** 读取天数列，单元格为空返回 null，读不出整数（含带小数点的值）抛 NumberFormatException */
    private Integer getInt(Row row, Map<String, Integer> map, String header) {
        java.math.BigDecimal d = getDecimal(row, map, header);
        if (d == null) return null;
        try {
            return d.intValueExact();
        } catch (ArithmeticException e) {
            throw new NumberFormatException("天数必须是整数");
        }
    }

    private void setCellStr(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
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

    private XSSFCellStyle createHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(new byte[]{(byte)41,(byte)128,(byte)185}, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private XSSFCellStyle createNormalStyle(XSSFWorkbook wb) {
        return wb.createCellStyle();
    }
}
