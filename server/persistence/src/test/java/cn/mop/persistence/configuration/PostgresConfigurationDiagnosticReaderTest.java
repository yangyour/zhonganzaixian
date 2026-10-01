package cn.mop.persistence.configuration;

import cn.mop.domain.DomainException;
import cn.mop.domain.configuration.ConfigurationDiagnostic;
import cn.mop.domain.configuration.ConfigurationDiagnosticReader.TrustedContext;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated JDBC protocol/diagnostic tests only. They open no socket and do not prove PostgreSQL
 * snapshot behavior or customer approval. Real designated-PG concurrency tests are separate.
 */
class PostgresConfigurationDiagnosticReaderTest {
    private static final String ACTOR="66000000-0000-4000-8000-000000000001";
    private static final String TENANT="55000000-0000-4000-8000-000000000001";
    private static final String OTHER="55000000-0000-4000-8000-000000000002";
    private static final String KEY="remote.idle_timeout_sec";
    private static final String OMITTED_BODY="MOP_FAKE_BODY_MUST_NOT_APPEAR";
    private static final Instant AT=Instant.parse("2026-10-01T01:02:03Z");
    private static TrustedContext context(){return new TrustedContext(ACTOR,TENANT,8);}

    @Test void invalidKeyOrContextIsRejectedBeforeAcquiringAnyConnection(){
        Fixture fixture=new Fixture();var reader=fixture.reader();
        for(String invalid:List.of(""," ","x?tenant_id="+OTHER,"x/../../other","x".repeat(129)))assertEquals(400,assertThrows(DomainException.class,()->reader.read(context(),invalid)).status);
        assertEquals(400,assertThrows(DomainException.class,()->reader.read(null,KEY)).status);
        assertThrows(IllegalArgumentException.class,()->new TrustedContext("not-a-uuid",TENANT,8));
        assertThrows(IllegalArgumentException.class,()->new TrustedContext(ACTOR,TENANT,0));
        assertEquals(0,fixture.acquired);
    }

    @Test void activeUserMembershipAndExactPermissionVersionAreAllRequired(){
        for(String field:List.of("user_active","membership_active","permission_version")){
            Fixture fixture=new Fixture();fixture.auth.put(field,field.equals("permission_version")?9L:false);
            DomainException rejection=assertThrows(DomainException.class,()->fixture.reader().read(context(),KEY));
            assertEquals(401,rejection.status);assertEquals("SESSION_EXPIRED",rejection.code);
            assertEquals(List.of("authorization"),fixture.executed);assertTrue(fixture.rolledBack);assertFalse(fixture.committed);
        }
    }

    @Test void onlyTheCurrentPadminConfigurationReadCapabilityIsAccepted(){
        for(String role:List.of("msp","tadmin","viewer","unknown")){
            Fixture fixture=new Fixture();fixture.auth.put("role_code",role);
            assertEquals(403,assertThrows(DomainException.class,()->fixture.reader().read(context(),KEY)).status);
            assertEquals(List.of("authorization"),fixture.executed);
        }
    }

    @Test void wrongDatabaseRuntimeIdentityOrInheritedMigrationSessionFailsClosed(){
        for(String field:List.of("database_name","database_user","session_user_name")){
            Fixture fixture=new Fixture();fixture.auth.put(field,"unapproved");
            assertSafeUnavailable(assertThrows(DomainException.class,()->fixture.reader().read(context(),KEY)));
            assertEquals(List.of("authorization"),fixture.executed);assertTrue(fixture.rolledBack);
        }
    }

    @Test void allReadsUseOneReadOnlyRepeatableReadConnectionAndNoLocksOrWrites(){
        Fixture fixture=new Fixture();ConfigurationDiagnostic diagnostic=fixture.reader().read(context(),KEY);
        assertEquals(1,fixture.acquired);assertEquals(List.of("authorization","registry","proposals"),fixture.executed);
        assertEquals(List.of("read_only","repeatable_read","begin"),fixture.setup);
        assertTrue(fixture.committed);assertFalse(fixture.rolledBack);assertTrue(fixture.closed);
        assertEquals(AT,diagnostic.observedAt());assertEquals(TENANT,diagnostic.context().tenantId());
        for(String sql:fixture.sql){assertTrue(sql.startsWith("SELECT "));assertFalse(sql.contains("FOR UPDATE"));assertFalse(sql.contains("FOR SHARE"));assertFalse(sql.contains("SELECT *"));}
        assertEquals(List.of(3,3,3),fixture.queryTimeouts);
        assertEquals(Map.of(1,ACTOR,2,TENANT),fixture.parameters.get("authorization"));
        assertEquals(Map.of(1,KEY),fixture.parameters.get("registry"));
        assertEquals(Map.of(1,TENANT,2,KEY),fixture.parameters.get("proposals"));
    }

    @Test void approvedInternalProposalNeverBecomesPublishedAndNoBodyOrValueIsProjected(){
        Fixture fixture=new Fixture();fixture.proposals.add(proposal(1,"APPROVED",2));
        ConfigurationDiagnostic diagnostic=fixture.reader().read(context(),KEY);Map<String,Object> dto=diagnostic.dto();
        assertEquals("CONFIG_PENDING",dto.get("status"));assertNull(dto.get("effective_value"));assertEquals(List.of("Q19"),dto.get("question_ids"));
        assertEquals("租户/服务档位",diagnostic.registry().scopeLabel());assertNull(diagnostic.registry().unitLabel());
        Map<?,?> definition=(Map<?,?>)dto.get("runtime_definition"),publication=(Map<?,?>)dto.get("publication"),customer=(Map<?,?>)dto.get("customer_verification");
        assertEquals("NOT_IMPLEMENTED",definition.get("availability"));assertNull(definition.get("precedence"));assertNull(definition.get("resolved_scope"));
        assertEquals("NOT_IMPLEMENTED",publication.get("availability"));assertNull(publication.get("revision_version"));assertNull(publication.get("authorization_verified"));
        assertNull(customer.get("required"));assertNull(customer.get("authority_verified"));assertNull(customer.get("authenticity_verified"));
        assertTrue(diagnostic.reasons().stream().anyMatch(reason->reason.code()==ConfigurationDiagnostic.ReasonCode.INTERNAL_REVISION_NOT_RUNTIME_PUBLICATION));
        assertFalse(dto.toString().contains(OMITTED_BODY));
        assertFalse(dto.containsKey("registered_value"));assertFalse(dto.containsKey("draft_value"));
        Map<?,?> recent=(Map<?,?>)dto.get("recent_proposals");Map<?,?> item=(Map<?,?>)((List<?>)recent.get("items")).get(0);
        assertEquals("APPROVED",item.get("workflow_status"));assertEquals("UNVERIFIED",item.get("confirmation_status"));assertEquals(2,item.get("evidence_reference_count"));
        assertFalse(item.containsKey("proposed_value"));assertFalse(item.containsKey("evidence_refs"));assertFalse(item.containsKey("review_comment"));
    }

    @Test void sixRowLookaheadReturnsOnlyFiveProposalsAndExplicitTruncation(){
        Fixture fixture=new Fixture();for(int index=1;index<=6;index++)fixture.proposals.add(proposal(index,"DRAFT",1));
        ConfigurationDiagnostic diagnostic=fixture.reader().read(context(),KEY);
        assertEquals(5,diagnostic.recentProposals().items().size());assertTrue(diagnostic.recentProposals().truncated());
        assertFalse(diagnostic.dto().toString().contains(id(6)));assertTrue(fixture.sql.get(2).contains("LIMIT 6"));
        assertThrows(UnsupportedOperationException.class,()->diagnostic.recentProposals().items().clear());
    }

    @Test void unavailableQuestionAndReferenceFactsStayNullWithoutInventedQuestions(){
        Fixture fixture=new Fixture();fixture.registry.put("question_ids",null);fixture.proposals.add(proposal(1,"IN_REVIEW",null));
        ConfigurationDiagnostic diagnostic=fixture.reader().read(context(),KEY);
        assertNull(diagnostic.registry().questionIds());assertNull(diagnostic.dto().get("question_ids"));
        assertNull(diagnostic.recentProposals().items().get(0).evidenceReferenceCount());
        assertTrue(diagnostic.reasons().stream().anyMatch(reason->reason.code()==ConfigurationDiagnostic.ReasonCode.REGISTRY_QUESTIONS_UNAVAILABLE));
        assertTrue(diagnostic.reasons().stream().anyMatch(reason->reason.code()==ConfigurationDiagnostic.ReasonCode.PROPOSAL_EVIDENCE_REFERENCES_UNAVAILABLE));
        assertEquals("NOT_IMPLEMENTED",((Map<?,?>)diagnostic.dto().get("publication")).get("availability"));
        assertFalse(((Map<?,?>)diagnostic.dto().get("publication")).containsKey("records"));
    }

    @Test void unknownKeyIs404AfterAuthorizationAndNeverQueriesProposals(){
        Fixture fixture=new Fixture();fixture.registry=null;
        assertEquals(404,assertThrows(DomainException.class,()->fixture.reader().read(context(),KEY)).status);
        assertEquals(List.of("authorization","registry"),fixture.executed);assertTrue(fixture.rolledBack);assertTrue(fixture.closed);
    }

    @Test void foreignTenantWrongKeyAndImpossibleInternalStatesCannotEnterDiagnostics(){
        for(String field:List.of("tenant_id","configuration_key","workflow_status","version")){
            Fixture fixture=new Fixture();Map<String,Object> row=proposal(1,"DRAFT",1);
            row.put(field,switch(field){case "tenant_id"->OTHER;case "configuration_key"->"another.key";case "workflow_status"->"PUBLISHED";default->0L;});fixture.proposals.add(row);
            assertSafeUnavailable(assertThrows(DomainException.class,()->fixture.reader().read(context(),KEY)));
            assertTrue(fixture.rolledBack);
        }
    }

    @Test void providerQueryAndCommitFailuresNeverReturnAnEmptySuccessfulFallback(){
        for(String stage:List.of("acquire","registry","proposals","commit")){
            Fixture fixture=new Fixture();fixture.failAt=stage;
            assertSafeUnavailable(assertThrows(DomainException.class,()->fixture.reader().read(context(),KEY)));
            assertFalse(fixture.committed);if(!stage.equals("acquire")){assertTrue(fixture.rolledBack);assertTrue(fixture.closed);}
        }
    }

    @Test void malformedMetadataFailsBeforeLabelsOrReferenceCountsCanExposeAnyBody(){
        Fixture registry=new Fixture();registry.registry.put("metadata_shape_valid",false);registry.registry.put("type_label",OMITTED_BODY);
        assertSafeUnavailable(assertThrows(DomainException.class,()->registry.reader().read(context(),KEY)));
        assertFalse(registry.fetchedColumns.contains("type_label"));assertEquals(List.of("authorization","registry"),registry.executed);
        Fixture proposals=new Fixture();Map<String,Object> corrupt=proposal(1,"DRAFT",1);corrupt.put("metadata_shape_valid",false);proposals.proposals.add(corrupt);
        assertSafeUnavailable(assertThrows(DomainException.class,()->proposals.reader().read(context(),KEY)));
        assertFalse(proposals.fetchedColumns.contains("evidence_reference_count"));
    }

    @Test void outputCollectionsAreImmutableAndDetachSourceQuestionArrays(){
        Fixture fixture=new Fixture();String[] questions={"Q19"};fixture.registry.put("question_ids",questions);
        ConfigurationDiagnostic diagnostic=fixture.reader().read(context(),KEY);questions[0]="Q09";
        assertEquals(List.of("Q19"),diagnostic.registry().questionIds());
        assertThrows(UnsupportedOperationException.class,()->diagnostic.registry().questionIds().clear());
        assertThrows(UnsupportedOperationException.class,()->diagnostic.reasons().clear());
        Map<String,Object> dto=diagnostic.dto();assertThrows(UnsupportedOperationException.class,()->dto.put("effective_value",1));
        assertThrows(UnsupportedOperationException.class,()->((Map<?,?>)dto.get("registry")).clear());
    }

    private static void assertSafeUnavailable(DomainException failure){assertEquals(503,failure.status);assertEquals("UPSTREAM_UNAVAILABLE",failure.code);assertNull(failure.getCause());assertTrue(failure.details.isEmpty());assertFalse(failure.toString().contains(OMITTED_BODY));}
    private static String id(int index){return String.format(Locale.ROOT,"77000000-0000-4000-8000-%012d",index);}
    private static Map<String,Object> proposal(int index,String status,Integer references){return row("id",id(index),"tenant_id",TENANT,"configuration_key",KEY,"version",2L,"workflow_status",status,"evidence_reference_count",references,"metadata_shape_valid",true,"proposal",OMITTED_BODY,"review_comment",OMITTED_BODY,"evidence_refs",OMITTED_BODY);}
    private static Map<String,Object> row(Object...pairs){Map<String,Object> row=new HashMap<>();for(int index=0;index<pairs.length;index+=2)row.put((String)pairs[index],pairs[index+1]);return row;}

    /** Minimal enforcing JDBC double; every unexpected JDBC method fails the isolated test. */
    private static final class Fixture {
        Map<String,Object> auth=row("database_name","mop_test","database_user","mop_test_runtime","session_user_name","mop_test_runtime","user_active",true,"membership_active",true,"permission_version",8L,"role_code","padmin","observed_at",Timestamp.from(AT));
        Map<String,Object> registry=row("key",KEY,"version",3L,"type_label","integer","unit_label",null,"scope_label","租户/服务档位","source_ref","MOP_FAKE_METADATA_SOURCE","decision_status_label","pending","classification_label","customer_pending","question_ids",new String[]{"Q19"},"metadata_shape_valid",true,"registered_value",OMITTED_BODY,"draft_json",OMITTED_BODY);
        final List<Map<String,Object>> proposals=new ArrayList<>();final List<String> setup=new ArrayList<>(),executed=new ArrayList<>(),sql=new ArrayList<>();
        final List<Integer> queryTimeouts=new ArrayList<>();final List<String> fetchedColumns=new ArrayList<>();final Map<String,Map<Integer,String>> parameters=new HashMap<>();
        int acquired;boolean readOnly,autoCommit=true,committed,rolledBack,closed;int isolation;String failAt;
        PostgresConfigurationDiagnosticReader reader(){return new PostgresConfigurationDiagnosticReader(dataSource());}
        DataSource dataSource(){return proxy(DataSource.class,(method,args)->{
            if(method.equals("getConnection")){acquired++;fail("acquire");return connection();}
            throw new AssertionError("Unexpected DataSource method "+method);
        });}
        Connection connection(){return proxy(Connection.class,(method,args)->switch(method){
            case "getAutoCommit"->autoCommit;
            case "setReadOnly"->{assertEquals(true,args[0]);readOnly=true;setup.add("read_only");yield null;}
            case "setTransactionIsolation"->{assertEquals(Connection.TRANSACTION_REPEATABLE_READ,args[0]);isolation=(int)args[0];setup.add("repeatable_read");yield null;}
            case "setAutoCommit"->{assertEquals(false,args[0]);autoCommit=false;setup.add("begin");yield null;}
            case "prepareStatement"->{assertTrue(readOnly);assertFalse(autoCommit);assertEquals(Connection.TRANSACTION_REPEATABLE_READ,isolation);yield statement((String)args[0]);}
            case "commit"->{fail("commit");committed=true;yield null;}
            case "rollback"->{rolledBack=true;yield null;}
            case "close"->{closed=true;yield null;}
            default->throw new AssertionError("Unexpected connection method "+method);
        });}
        PreparedStatement statement(String query){
            sql.add(query);String kind=query.contains("AS database_name")?"authorization":query.contains("FROM public.mop_configuration c")?"registry":query.contains("FROM public.mop_configuration_revision r")?"proposals":null;
            assertNotNull(kind,"Only explicit read queries are permitted");Map<Integer,String> bound=new HashMap<>();parameters.put(kind,bound);
            return proxy(PreparedStatement.class,(method,args)->switch(method){
                case "setString"->{bound.put((int)args[0],(String)args[1]);yield null;}
                case "setQueryTimeout"->{queryTimeouts.add((int)args[0]);yield null;}
                case "executeQuery"->{executed.add(kind);fail(kind);yield results(switch(kind){case "authorization"->List.of(auth);case "registry"->registry==null?List.of():List.of(registry);default->proposals;});}
                case "close"->null;
                default->throw new AssertionError("Unexpected statement method "+method);
            });
        }
        void fail(String stage)throws SQLException{if(stage.equals(failAt))throw new SQLException(OMITTED_BODY+" raw driver or SQL details","08006");}
        ResultSet results(List<Map<String,Object>> values){
            int[] cursor={-1};boolean[] wasNull={false};
            return proxy(ResultSet.class,(method,args)->{
                if(method.equals("next"))return ++cursor[0]<values.size();if(method.equals("close"))return null;if(method.equals("wasNull"))return wasNull[0];
                String column=(String)args[0];fetchedColumns.add(column);assertFalse(Set.of("registry_json","registered_value","draft_json","proposal","review_comment","evidence_refs","proposed_value").contains(column),"Bodies must never be requested");
                Object value=values.get(cursor[0]).get(column);wasNull[0]=value==null;
                return switch(method){case "getString"->(String)value;case "getBoolean"->value!=null&&(boolean)value;case "getLong"->value==null?0L:((Number)value).longValue();case "getInt"->value==null?0:((Number)value).intValue();case "getTimestamp"->value;case "getArray"->value==null?null:array((Object[])value);default->throw new AssertionError("Unexpected row method "+method);};
            });
        }
        java.sql.Array array(Object[] values){return proxy(java.sql.Array.class,(method,args)->switch(method){case "getArray"->values;case "free"->null;default->throw new AssertionError("Unexpected array method "+method);});}
    }
    @FunctionalInterface private interface Call {Object apply(String method,Object[] args)throws Throwable;}
    private static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(object,method,args)->{
        if(method.getDeclaringClass()==Object.class)return switch(method.getName()){case "toString"->"Isolated JDBC double";case "hashCode"->System.identityHashCode(object);case "equals"->object==args[0];default->throw new AssertionError("Unexpected object method");};
        return call.apply(method.getName(),args==null?new Object[0]:args);
    }));}
}
