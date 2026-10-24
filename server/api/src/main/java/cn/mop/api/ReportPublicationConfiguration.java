package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.reporting.ReportPublication.*;
import cn.mop.domain.reporting.ReportPublicationRepository;
import cn.mop.persistence.*;
import cn.mop.adapters.reports.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

@Configuration @Profile("remote_test")
public class ReportPublicationConfiguration {
 @Bean ReportPublicationRepository reportPublicationRepository(PlatformRepository platform,ObjectMapper mapper,QuarterlyReportPdfPreview pdf,Environment env){
  if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Report publication requires designated PostgreSQL");
  String template=env.getProperty("MOP_REPORT_PUBLICATION_TEMPLATE_VERSION");
  if(template!=null&&!ReportPublicationRenderer.TEMPLATE.equals(template))throw new IllegalStateException("Unimplemented publication template version");
  var renderer=new ReportPublicationRenderer(pdf,mapper);
  return new PostgresReportPublicationRepository(postgres,mapper,(document,payload,now)->{
   var r=renderer.render(document,payload,now);return new ReportPublicationRepository.Rendered(r.html(),r.htmlBytes(),r.pdf(),r.pdfBytes(),r.failure());
  },policyProvider(env.getProperty("MOP_REPORT_PUBLICATION_POLICY_JSON")),template);
 }
 static Function<String,Policy> policyProvider(String raw){
  if(raw==null||raw.isBlank())return tenant->new Policy(null,null,null,null,null,null,null,null,null);
  if(raw.length()>65536)throw new IllegalStateException("Publication policy too large");
  try{
   var mapper=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
   var root=mapper.readTree(raw);if(!root.isObject()||root.size()>100)throw new IllegalArgumentException();
   var values=new HashMap<String,Policy>();var fields=root.fields();while(fields.hasNext()){
    var entry=fields.next();if(!entry.getKey().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException();
    var policy=mapper.treeToValue(entry.getValue(),Policy.class);
    String reference=policy.reference()==null?null:policy.reference()+":"+ReportPublicationRenderer.sha(mapper.writeValueAsBytes(entry.getValue()));
    values.put(entry.getKey(),new Policy(reference,policy.reviewers(),policy.publishers(),policy.customers(),policy.selfReviewAllowed(),policy.regeneration(),policy.annualEnabled(),policy.customerTemplateConfirmed(),policy.customerConfirmationReference()));
   }
   var frozen=Map.copyOf(values);return tenant->frozen.getOrDefault(tenant,new Policy(null,null,null,null,null,null,null,null,null));
  }catch(Exception failure){throw new IllegalStateException("Invalid explicit report publication policy; no defaults applied");}
 }
}
