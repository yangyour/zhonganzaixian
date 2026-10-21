package cn.mop.api;
import cn.mop.adapters.reports.QuarterlyReportPdfPreview;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@Configuration @Profile("remote_test")
public class QuarterlyReportPdfConfiguration {
 @Bean QuarterlyReportPdfPreview quarterlyReportPdfPreview(Environment env){
  return new QuarterlyReportPdfPreview(new QuarterlyReportPdfPreview.Settings("true".equals(env.getProperty("MOP_REPORT_PDF_PREVIEW_ENABLED","false")),env.getProperty("MOP_REPORT_PDF_FONT_PATH"),env.getProperty("MOP_REPORT_PDF_FONT_SHA256"),env.getProperty("MOP_REPORT_PDF_FALLBACK_FONT_PATH"),env.getProperty("MOP_REPORT_PDF_FALLBACK_FONT_SHA256")));
 }
}
