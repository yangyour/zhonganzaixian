import cn.mop.domain.contact.*;
import cn.mop.domain.contact.CustomerContactRepository.Access;
import cn.mop.domain.contact.ContactImportRepository.*;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Controlled real-component probe, not an API fixture. Existing identities only; own UUID-marked contacts archived. */
public final class ContactImportWorkerProbe {
    static final String TENANT="55000000-0000-4000-8000-000000000002";
    static String stage="arguments";
    public static void main(String[] args){try{run(args);}catch(Throwable failure){System.err.println("MOP_CONTACT_WORKER_PROBE_FAILED stage="+stage+" type="+failure.getClass().getSimpleName()+" details=redacted");System.exit(1);}}
    static void require(boolean condition){if(!condition)throw new IllegalStateException("Probe requirement not met");}
    static String id(String value){require(value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));return UUID.fromString(value).toString();}
    static void run(String[] args)throws Exception{
        require(args.length>=1&&Set.of("seed","inspect","cleanup","fingerprints").contains(args[0]));String action=args[0];require(action.equals("fingerprints")?args.length==1:action.equals("seed")?args.length==2:args.length==3);
        String run=action.equals("fingerprints")?null:id(args[action.equals("seed")?1:2]),jobId=action.equals("inspect")||action.equals("cleanup")?id(args[1]):null;
        stage="approved_database";String url=System.getenv("SPRING_DATASOURCE_URL");require("jdbc:postgresql://127.0.0.1:25432/mop_test".equals(url));require("mop_test_runtime".equals(System.getenv("SPRING_DATASOURCE_USERNAME")));
        HikariConfig cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(2);cfg.setConnectionTimeout(5000);
        try(HikariDataSource pool=new HikariDataSource(cfg)){
            ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);var platform=new PostgresPlatformRepository(pool,mapper);var contacts=new PostgresCustomerContactRepository(pool,platform);var imports=new PostgresContactImportRepository(pool,platform);require(imports.readinessProblems().isEmpty());
            Map<String,Object> out=new LinkedHashMap<>();out.put("action",action);
            if(action.equals("fingerprints")){stage="readonly_fingerprints";try(var c=pool.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);for(String table:List.of("mop_user","mop_membership","mop_outbox","mop_photo_job","mop_asset_import_job"))try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*),encode(sha256(convert_to(COALESCE(string_agg(to_jsonb(t)::text,E'\\n' ORDER BY to_jsonb(t)::text),''),'UTF8')),'hex') FROM public."+table+" t")){require(r.next());out.put(table,Map.of("rows",r.getLong(1),"equality_sha256",r.getString(2)));}c.rollback();}print(mapper,out);return;}
            String actor;try(var c=pool.getConnection();var s=c.prepareStatement("SELECT m.user_id::text FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.role_code='padmin' AND m.active AND u.active ORDER BY m.user_id LIMIT 1")){s.setString(1,TENANT);try(var r=s.executeQuery()){require(r.next());actor=r.getString(1);}}
            Access access=new Access(actor,TENANT,platform.membership(actor,TENANT).orElseThrow().permissionVersion(),run);
            if(action.equals("seed")){stage="durable_acceptance";var rows=new ArrayList<CustomerContact>();for(int i=1;i<=3;i++)rows.add(new CustomerContact("SYNTHETIC_WORKER_"+run+"_"+i,Set.of(CustomerContact.Role.REPORTER),null,null,null,null,null,false));String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("contact-worker-probe-v1|"+run).getBytes(StandardCharsets.UTF_8)));Object receipt=platform.idempotentAuthorized(actor,TENANT,"POST /qa/contact-worker",run,hash,access.permissionVersion(),()->imports.authorizeAcceptance(access),()->imports.accept(access,rows));JsonNode value=mapper.valueToTree(receipt);require(value.path("state").path("state").asText().equals("PENDING"));out.put("job_id",value.path("id").asText());out.put("accepted_state","PENDING");out.put("accepted_version",value.path("version").asLong());out.put("rows",3);print(mapper,out);return;}
            stage="authorized_current_job";Job job=imports.get(access,jobId);out.put("job_id",job.id());out.put("state",job.state().state().name());out.put("fencing_token",job.state().fencingToken());out.put("attempts",job.state().attempts());out.put("version",job.version());out.put("input_sha256",job.inputSha256());out.put("result_count",job.results().size());out.put("completed_rows",job.state().completedRows());out.put("lease_remaining_millis",job.state().leaseUntil()==null?null:Duration.between(Instant.now(),job.state().leaseUntil()).toMillis());
            long created;try(var c=pool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM public.mop_customer_contact h JOIN public.mop_customer_contact_revision r ON r.tenant_id=h.tenant_id AND r.contact_id=h.id AND r.version=1 WHERE h.tenant_id=?::uuid AND h.created_by=?::uuid AND r.name IN (?,?,?)")){s.setString(1,TENANT);s.setString(2,actor);for(int i=1;i<=3;i++)s.setString(i+2,"SYNTHETIC_WORKER_"+run+"_"+i);try(var r=s.executeQuery()){require(r.next());created=r.getLong(1);}}out.put("matching_created_contacts",created);
            if(job.state().state()==ContactImportJobState.State.SUCCEEDED){require(job.results().size()==3&&created==3);for(var result:job.results()){var current=contacts.get(access,result.contactId());require(current.createdBy().equals(actor));require(current.facts().name().equals("SYNTHETIC_WORKER_"+run+"_"+result.rowNumber()));}}
            if(action.equals("cleanup")){stage="archive_owned_contacts";require(job.state().terminal());int archived=0;for(var result:job.results()){var current=contacts.get(access,result.contactId());require(current.createdBy().equals(actor)&&current.facts().name().equals("SYNTHETIC_WORKER_"+run+"_"+result.rowNumber()));if(!current.archived()){platform.idempotentAuthorized(actor,TENANT,"POST /qa/contact-worker/archive/"+current.id(),run,"d".repeat(64),access.permissionVersion(),()->contacts.authorizeWrite(access,current.id()),()->contacts.archive(access,current.id(),current.version()));}require(contacts.get(access,current.id()).archived());archived++;}out.put("owned_contacts_archived",archived);}
            print(mapper,out);
        }
    }
    static void print(ObjectMapper mapper,Map<String,Object> value)throws Exception{System.out.println("MOP_CONTACT_WORKER_PROBE "+mapper.writeValueAsString(value));}
}
