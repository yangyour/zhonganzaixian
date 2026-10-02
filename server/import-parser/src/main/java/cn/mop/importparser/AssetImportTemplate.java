package cn.mop.importparser;

import cn.mop.domain.assetimport.source.AssetImportSchema;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Empty contract-label template only: no business defaults, sample assets or import execution. */
public final class AssetImportTemplate {
    private AssetImportTemplate() {}

    public static byte[] create() throws IOException {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("资产导入");
            var text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            var headerStyle = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            font.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setDataFormat(text.getDataFormat());
            var header = sheet.createRow(0);
            header.setHeightInPoints(24);
            var definitions = AssetImportSchema.columns();
            for (int index = 0; index < definitions.size(); index++) {
                var definition = definitions.get(index);
                var cell = header.createCell(index);
                cell.setCellValue(definition.label());
                cell.setCellStyle(headerStyle);
                sheet.setDefaultColumnStyle(index, text);
                sheet.setColumnWidth(index, (definition.contractAlwaysRequired() ? 40 : 22) * 256);
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, definitions.size() - 1));
            workbook.getProperties().getCustomProperties().addProperty("mop.schema_version", AssetImportSchema.VERSION);
            workbook.getProperties().getCustomProperties().addProperty("mop.business_status", "CONFIG_PENDING");
            workbook.getProperties().getCoreProperties().setDescription(
                "合同15列空模板。备注必须填写。最多500数据行；设备字典、日期及IP冲突规则待Q20/Q31确认。模板不表示预检或资产导入已完成。");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    /** Explicit local output; never replace an existing file or read private runtime configuration. */
    public static void main(String[] args) throws IOException {
        if (args.length != 1 || !args[0].toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("Provide one new .xlsx output path");
        }
        Files.write(Path.of(args[0]), create(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
