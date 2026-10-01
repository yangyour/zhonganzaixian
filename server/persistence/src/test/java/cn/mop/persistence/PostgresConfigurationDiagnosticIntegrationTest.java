package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.configuration.*;
import cn.mop.persistence.configuration.PostgresConfigurationDiagnosticReader;
import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Real designated PostgreSQL only. Seeds clearly fictional rows; never migrates or changes existing actors. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresConfigurationDiagnosticIntegrationTest {
    private static final String RUN=UUID.randomUUID().toString(),TENANT=UUID.randomUUID().toString(),OTHER=UUID.randomUUID().toString();
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private static HikariDataSource pool;
    private static ConfigurationDiagnosticReader reader;
    private static final Map<String,String> actors=new LinkedHashMap<>();
    private static String url;

    @BeforeAll static void connectAndSeed()throws Exception{
        url=required("SPRING_DATASOURCE_URL");assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));
        assertEquals("mop_test_runtime",required("SPRING_DATASOURCE_USERNAME"));assertEquals("mop_test_migrator",required("MOP_MIGRATION_USERNAME"));
        HikariConfig configuration=new HikariConfig();configuration.setJdbcUrl(url);configuration.setUsername(required("SPRING_DATASOURCE_USERNAME"));configuration.setPassword(required("SPRING_DATASOURCE_PASSWORD"));configuration.setMaximumPoolSize(4);configuration.setConnectionTimeout(5000);
        pool=new HikariDataSource(configuration);reader=new PostgresConfigurationDiagnosticReader(pool);
        try(Connection connection=migrator()){
            try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT current_database(),current_user")){assertTrue(rows.next());assertEquals("mop_test",rows.getString(1));assertEquals("mop_test_migrator",rows.getString(2));}
            connection.setAutoCommit(false);
            for(String tenant:List.of(TENANT,OTHER))try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_tenant(id,name) VALUES (?::uuid,?)")){
                statement.setString(1,tenant);statement.setString(2,"MOP-FAKE-DIAGNOSTIC-"+RUN+"-"+tenant);assertEquals(1,statement.executeUpdate());
            }
            for(String name:List.of("admin","reviewer","viewer","msp","tadmin")){
                String actor=UUID.randomUUID().toString(),role=Set.of("admin","reviewer").contains(name)?"padmin":name;actors.put(name,actor);
                try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) VALUES (?::uuid,'test_pg_diagnostic',?,?,?)")){
                    statement.setString(1,actor);statement.setString(2,"MOP-FAKE-"+RUN+"-"+name);statement.setString(3,"MOP-FAKE诊断PG组件测试 "+name+" "+RUN);statement.setString(4,role);assertEquals(1,statement.executeUpdate());
                }
                for(String tenant:List.of(TENANT,OTHER))try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES (?::uuid,?::uuid,?)")){
                    statement.setString(1,tenant);statement.setString(2,actor);statement.setString(3,role);assertEquals(1,statement.executeUpdate());
                }
            }
            connection.commit();
        }
    }
    @AfterAll static void close(){if(pool!=null)pool.close();}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing private designated setting "+name);return value;}
    private static Connection migrator(){try{return DriverManager.getConnection(url,required("MOP_MIGRATION_USERNAME"),required("MOP_MIGRATION_PASSWORD"));}catch(SQLException unavailable){throw new IllegalStateException("Designated project test-data connection unavailable; details omitted");}}
    private static String key(){return "mop.fake.diagnostic."+UUID.randomUUID().toString().replace("-","");}
    private static String body(){return "MOP-FAKE-RAW-BODY-"+UUID.randomUUID();}
    private static ConfigurationDiagnosticReader.TrustedContext context(String name,String tenant){return new ConfigurationDiagnosticReader.TrustedContext(actors.get(name),tenant,permission(actors.get(name),tenant));}
    private static long permission(String actor,String tenant){
        try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT permission_version FROM public.mop_membership WHERE tenant_id=?::uuid AND user_id=?::uuid")){
            statement.setString(1,tenant);statement.setString(2,actor);try(ResultSet rows=statement.executeQuery()){assertTrue(rows.next());return rows.getLong(1);}
        }catch(SQLException unavailable){throw new IllegalStateException("Scoped permission read unavailable; details omitted");}
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode registry(String key,String marker){
        var registry=MAPPER.createObjectNode();registry.put("key",key);registry.put("description","MOP-FAKE诊断测试，非业务规则");registry.put("type","object");registry.putNull("unit");registry.put("scope","租户");registry.put("source","MOP-FAKE-PG-DIAGNOSTIC");registry.put("decision_status","pending");registry.put("contract_value_kind","configurable_default");registry.putArray("question_ids").add("Q28");registry.putObject("value").put("fake_body",marker);return registry;
    }
    private static void seedRegistry(String key,String marker){seedRegistry(key,registry(key,marker),marker);}
    private static void seedRegistry(String key,JsonNode registry,String marker){
        try(Connection connection=migrator();PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_configuration(key,registry_json,draft_json) VALUES (?,?::jsonb,?::jsonb)")){
            statement.setString(1,key);statement.setString(2,registry.toString());statement.setString(3,MAPPER.createObjectNode().put("fake_body",marker+"-draft").toString());assertEquals(1,statement.executeUpdate());
        }catch(SQLException unavailable){throw new IllegalStateException("Scoped registry fixture creation failed; details omitted");}
    }
    private static String seedProposal(String key,String tenant,String marker,String status,int index){
        try(Connection connection=migrator()){return seedProposal(connection,key,tenant,marker,status,index,null);}
        catch(SQLException unavailable){throw new IllegalStateException("Scoped proposal fixture creation failed; details omitted");}
    }
    private static String seedProposal(Connection connection,String key,String tenant,String marker,String status,int index,JsonNode evidence)throws SQLException{
        String id=UUID.randomUUID().toString();var proposal=MAPPER.createObjectNode();proposal.putObject("proposed_value").put("fake_body",marker+"-proposal");proposal.put("reason",marker+"-reason");
        if(evidence==null)proposal.putArray("evidence_refs").addObject().put("reference","document-ref:mop-fake-diagnostic").put("title",marker+"-evidence").put("sha256","a".repeat(64));else proposal.set("evidence_refs",evidence);
        boolean reviewed=Set.of("APPROVED","REJECTED").contains(status);Timestamp created=Timestamp.from(Instant.parse("2026-10-01T00:00:00Z").plusSeconds(index));
        try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_configuration_revision(id,tenant_id,configuration_key,proposal,workflow_status,version,created_by,created_at,updated_at,reviewed_by,reviewed_at,review_comment) VALUES (?::uuid,?::uuid,?,?::jsonb,?,?,?::uuid,?,?,?::uuid,?,?)")){
            statement.setString(1,id);statement.setString(2,tenant);statement.setString(3,key);statement.setString(4,proposal.toString());statement.setString(5,status);statement.setLong(6,index+1L);statement.setString(7,actors.get("admin"));statement.setTimestamp(8,created);statement.setTimestamp(9,created);statement.setString(10,reviewed?actors.get("reviewer"):null);statement.setTimestamp(11,reviewed?created:null);statement.setString(12,reviewed?marker+"-review":null);assertEquals(1,statement.executeUpdate());return id;
        }
    }
    private static JsonNode dto(ConfigurationDiagnostic diagnostic){return MAPPER.valueToTree(diagnostic.dto());}

    @Test void realRegistryDraftApprovedProposalAndEvidenceBodiesNeverBecomeAnEffectiveValueOrResponse(){
        String key=key(),marker=body();seedRegistry(key,marker);String id=seedProposal(key,TENANT,marker,"APPROVED",2);
        JsonNode result=dto(reader.read(context("admin",TENANT),key));assertEquals("CONFIG_PENDING",result.path("status").asText());assertTrue(result.has("effective_value"));assertTrue(result.path("effective_value").isNull());
        assertEquals("DESIGN_REGISTRY",result.path("registry").path("origin").asText());assertEquals(TENANT,result.path("context").path("tenant_id").asText());
        JsonNode proposal=result.path("recent_proposals").path("items").get(0);assertEquals(id,proposal.path("id").asText());assertEquals("APPROVED",proposal.path("workflow_status").asText());assertEquals("UNVERIFIED",proposal.path("confirmation_status").asText());assertEquals("REFERENCE_ONLY",proposal.path("evidence_input_status").asText());assertEquals(1,proposal.path("evidence_reference_count").asInt());
        for(String source:List.of("runtime_definition","publication","customer_verification"))assertEquals("NOT_IMPLEMENTED",result.path(source).path("availability").asText());
        assertFalse(result.toString().contains(marker));for(String forbidden:List.of("registered_value","draft_value","proposed_value","review_comment","evidence_refs","selected_revision"))assertFalse(result.toString().contains("\""+forbidden+"\""));
    }
    @Test void currentTenantLimitsToFiveRecentMetadataRowsAndDoesNotLeakTheOtherTenant(){
        String key=key(),marker=body();seedRegistry(key,marker);List<String> own=new ArrayList<>();for(int i=0;i<7;i++)own.add(seedProposal(key,TENANT,marker,"DRAFT",i));String foreign=seedProposal(key,OTHER,marker,"DRAFT",20);
        var ownResult=reader.read(context("admin",TENANT),key);assertTrue(ownResult.recentProposals().truncated());assertEquals(5,ownResult.recentProposals().items().size());
        assertEquals(List.of(own.get(6),own.get(5),own.get(4),own.get(3),own.get(2)),ownResult.recentProposals().items().stream().map(ConfigurationDiagnostic.Proposal::id).toList());assertFalse(ownResult.dto().toString().contains(foreign));
        var otherResult=reader.read(context("admin",OTHER),key);assertFalse(otherResult.recentProposals().truncated());assertEquals(List.of(foreign),otherResult.recentProposals().items().stream().map(ConfigurationDiagnostic.Proposal::id).toList());for(String id:own)assertFalse(otherResult.dto().toString().contains(id));
    }
    @Test void realRolesMissingMembershipStaleVersionsAndUnknownRegistryAreRejected(){
        String key=key();seedRegistry(key,body());for(String role:List.of("viewer","msp","tadmin"))assertEquals(403,assertThrows(DomainException.class,()->reader.read(context(role,TENANT),key)).status);
        var current=context("admin",TENANT);assertEquals(401,assertThrows(DomainException.class,()->reader.read(new ConfigurationDiagnosticReader.TrustedContext(current.actorId(),current.tenantId(),current.expectedPermissionVersion()+1),key)).status);
        assertEquals(401,assertThrows(DomainException.class,()->reader.read(new ConfigurationDiagnosticReader.TrustedContext(current.actorId(),UUID.randomUUID().toString(),1),key)).status);
        assertEquals(404,assertThrows(DomainException.class,()->reader.read(current,key())).status);
    }
    @Test void migrationAccountIsNeverAcceptedAsTheRuntimeDiagnosticSource(){
        String key=key();seedRegistry(key,body());HikariConfig configuration=new HikariConfig();configuration.setJdbcUrl(url);configuration.setUsername(required("MOP_MIGRATION_USERNAME"));configuration.setPassword(required("MOP_MIGRATION_PASSWORD"));configuration.setMaximumPoolSize(1);
        try(HikariDataSource wrongRole=new HikariDataSource(configuration)){assertEquals(503,assertThrows(DomainException.class,()->new PostgresConfigurationDiagnosticReader(wrongRole).read(context("admin",TENANT),key)).status);}
    }
    @Test void registryAndProposalReadsShareTheAuthorizationSnapshotDuringConcurrentCommit(){
        String key=key(),marker=body();seedRegistry(key,marker);AtomicBoolean changed=new AtomicBoolean();
        ConfigurationDiagnosticReader scheduled=new PostgresConfigurationDiagnosticReader(afterAuthorization(()->{
            try(Connection connection=migrator()){
                connection.setAutoCommit(false);try(PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_configuration SET version=version+1 WHERE key=? AND version=1")){statement.setString(1,key);assertEquals(1,statement.executeUpdate());}
                seedProposal(connection,key,TENANT,marker,"APPROVED",3,null);connection.commit();changed.set(true);
            }catch(SQLException unavailable){throw new IllegalStateException("Scoped interleaving fixture failed; details omitted");}
        }));
        var before=scheduled.read(context("admin",TENANT),key);assertTrue(changed.get());assertEquals(1,before.registry().version());assertTrue(before.recentProposals().items().isEmpty());
        var after=reader.read(context("admin",TENANT),key);assertEquals(2,after.registry().version());assertEquals(1,after.recentProposals().items().size());assertNull(after.effectiveValue());
    }
    @Test void revocationAfterTheReadSnapshotAffectsTheNextReadAndIsRestoredByExactCas(){
        String key=key();seedRegistry(key,body());var trusted=context("admin",TENANT);AtomicBoolean revoked=new AtomicBoolean();
        ConfigurationDiagnosticReader scheduled=new PostgresConfigurationDiagnosticReader(afterAuthorization(()->{
            try(Connection connection=migrator();PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid AND active AND role_code='padmin' AND permission_version=?")){
                statement.setString(1,TENANT);statement.setString(2,trusted.actorId());statement.setLong(3,trusted.expectedPermissionVersion());revoked.set(true);assertEquals(1,statement.executeUpdate());
            }catch(SQLException unavailable){throw new IllegalStateException("Scoped fake-actor revocation failed; details omitted");}
        }));
        try{assertEquals("CONFIG_PENDING",scheduled.read(trusted,key).status());assertTrue(revoked.get());assertEquals(401,assertThrows(DomainException.class,()->reader.read(trusted,key)).status);}
        finally{if(revoked.get())try(Connection connection=migrator();PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_membership SET active=true,permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid AND role_code='padmin' AND ((NOT active AND permission_version=?) OR (active AND permission_version=?))")){
            statement.setString(1,TENANT);statement.setString(2,trusted.actorId());statement.setLong(3,trusted.expectedPermissionVersion()+1);statement.setLong(4,trusted.expectedPermissionVersion());assertEquals(1,statement.executeUpdate());
        }catch(SQLException unavailable){throw new IllegalStateException("Scoped fake-actor CAS restore failed; details omitted");}}
    }
    @Test void explicitInvalidRegistryLabelsQuestionsAndProposalEvidenceShapesFailClosed(){
        for(String invalid:List.of("label","questions")){
            String key=key(),marker=body();var registry=registry(key,marker);if(invalid.equals("label"))registry.putObject("scope").put("fake_body",marker);else registry.put("question_ids",marker);
            seedRegistry(key,registry,marker);assertEquals(503,assertThrows(DomainException.class,()->reader.read(context("admin",TENANT),key)).status);
        }
        String key=key(),marker=body();seedRegistry(key,marker);try(Connection connection=migrator()){seedProposal(connection,key,TENANT,marker,"DRAFT",0,MAPPER.createArrayNode().add(marker));}catch(SQLException unavailable){throw new IllegalStateException("Scoped malformed metadata fixture failed; details omitted");}
        assertEquals(503,assertThrows(DomainException.class,()->reader.read(context("admin",TENANT),key)).status);
    }
    @Test void absentRegistryQuestionsStayUnknownRatherThanBecomingAnEmptyApprovalSet(){
        String key=key(),marker=body();var registry=registry(key,marker);registry.remove("question_ids");seedRegistry(key,registry,marker);
        JsonNode result=dto(reader.read(context("admin",TENANT),key));assertTrue(result.path("question_ids").isNull());assertTrue(result.path("registry").path("question_ids").isNull());assertTrue(result.path("reasons").toString().contains("REGISTRY_QUESTIONS_UNAVAILABLE"));
    }
    // Scheduling hooks only; every connection, SQL statement and result belongs to the designated PostgreSQL.
    private static DataSource afterAuthorization(Runnable hook){
        AtomicBoolean delivered=new AtomicBoolean();ClassLoader loader=PostgresConfigurationDiagnosticIntegrationTest.class.getClassLoader();
        return (DataSource)Proxy.newProxyInstance(loader,new Class<?>[]{DataSource.class},(proxy,method,args)->{
            Object result=invoke(pool,method,args);if(!method.getName().equals("getConnection")||!(result instanceof Connection connection))return result;
            return Proxy.newProxyInstance(loader,new Class<?>[]{Connection.class},(connectionProxy,connectionMethod,connectionArgs)->{
                Object statement=invoke(connection,connectionMethod,connectionArgs);
                if(!connectionMethod.getName().equals("prepareStatement")||!(connectionArgs[0] instanceof String sql)||!sql.startsWith("SELECT current_database()")||!(statement instanceof PreparedStatement prepared))return statement;
                return Proxy.newProxyInstance(loader,new Class<?>[]{PreparedStatement.class},(statementProxy,statementMethod,statementArgs)->{
                    Object resultSet=invoke(prepared,statementMethod,statementArgs);if(!statementMethod.getName().equals("executeQuery")||!(resultSet instanceof ResultSet rows))return resultSet;
                    return Proxy.newProxyInstance(loader,new Class<?>[]{ResultSet.class},(rowsProxy,rowsMethod,rowsArgs)->{
                        Object next=invoke(rows,rowsMethod,rowsArgs);if(rowsMethod.getName().equals("next")&&Boolean.TRUE.equals(next)&&delivered.compareAndSet(false,true))hook.run();return next;
                    });
                });
            });
        });
    }
    private static Object invoke(Object target,Method method,Object[] arguments)throws Throwable{try{return method.invoke(target,arguments);}catch(InvocationTargetException failure){throw failure.getCause();}}
}
