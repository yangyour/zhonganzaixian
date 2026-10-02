package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.AssetImportPrechecker.Column;
import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated JDBC snapshot/validation tests only. These do not prove PostgreSQL/MinIO integration. */
class PostgresAssetImportRepositoryTest {
    static final String ACTOR="77000000-0000-4000-8000-000000000001",TENANT="77000000-0000-4000-8000-000000000002",SOURCE="77000000-0000-4000-8000-000000000003",OP="77000000-0000-4000-8000-000000000004";
    static final AssetImportSourceRepository.Access ACCESS=new AssetImportSourceRepository.Access(ACTOR,TENANT,7,"isolated-import");
    static final ObjectMapper MAPPER=new ObjectMapper();
    @Test void sourceAuthorizationAndProjectionShareOneReadOnlyRepeatableReadTransaction(){
        Jdbc fixture=new Jdbc();Map<String,Object> source=fixture.repository().source(ACCESS,SOURCE);
        assertEquals(1,fixture.acquired);assertTrue(fixture.committed);assertTrue(fixture.closed);assertFalse(fixture.rolledBack);
        assertEquals(List.of("authorization","source"),fixture.executed);assertEquals(List.of(TENANT,SOURCE,ACTOR),fixture.parameters.get("source"));
        assertEquals("asset_import_source",source.get("purpose"));assertEquals(1L,source.get("version"));assertEquals("PENDING",source.get("storage_status"));assertEquals("NOT_REQUESTED",source.get("parse_status"));assertNull(source.get("parsed_artifact"));assertNull(source.get("mapping"));
        assertEquals("CONFIG_PENDING",source.get("business_status"));assertEquals(false,source.get("can_import"));assertEquals(List.of("Q20","Q31"),source.get("question_ids"));assertNull(source.get("effective_rule_versions"));assertNull(source.get("quota_reservation"));assertNull(source.get("retention_until"));assertEquals("UNSCANNED",source.get("scan_status"));assertEquals("UNVERIFIED",source.get("production_approval"));assertFalse(source.containsKey("object_key"));
    }
    @Test void operationRemainsOwnerOnlyAndProjectsStructuredSafeErrorAndRelativeResultReference(){
        Jdbc fixture=new Jdbc();fixture.operation.put("error","{\"code\":\"UPSTREAM_UNAVAILABLE\",\"retryable\":true}");Map<String,Object> operation=fixture.repository().operation(ACCESS,OP);
        assertEquals(List.of("authorization","operation"),fixture.executed);assertEquals(List.of(TENANT,OP,ACTOR),fixture.parameters.get("operation"));assertEquals("/api/v1/assets/import-sources/"+SOURCE,operation.get("result_ref"));assertInstanceOf(Map.class,operation.get("error"));assertFalse(operation.containsKey("permission_version"));assertTrue(fixture.committed);
    }
    @Test void staleOrRevokedSnapshotNeverQueriesAnOwnedObject(){
        for(Long version:Arrays.asList(null,8L)){
            Jdbc fixture=new Jdbc();if(version==null)fixture.auth=null;else fixture.auth.put("permission_version",version);
            assertEquals(401,assertThrows(DomainException.class,()->fixture.repository().source(ACCESS,SOURCE)).status);assertEquals(List.of("authorization"),fixture.executed);assertTrue(fixture.rolledBack);assertFalse(fixture.committed);
        }
    }
    @Test void foreignUnownedMissingAndPhotoUuidAllUseTheSame404(){
        Jdbc source=new Jdbc();source.source=null;assertEquals(404,assertThrows(DomainException.class,()->source.repository().source(ACCESS,SOURCE)).status);
        Jdbc operation=new Jdbc();operation.operation=null;assertEquals(404,assertThrows(DomainException.class,()->operation.repository().operation(ACCESS,OP)).status);
        assertEquals(404,assertThrows(DomainException.class,()->source.repository().source(ACCESS,"photo-purpose-object")).status);
    }
    @Test void queryAndCommitFailureCannotReturnAProjectionOrInventAReadyFallback(){
        for(String stage:List.of("source","operation","commit")){
            Jdbc fixture=new Jdbc();fixture.failAt=stage;
            DomainException failure=assertThrows(DomainException.class,()->{if("operation".equals(stage))fixture.repository().operation(ACCESS,OP);else fixture.repository().source(ACCESS,SOURCE);});
            assertEquals(503,failure.status);assertEquals("UPSTREAM_UNAVAILABLE",failure.code);assertNull(failure.getCause());assertFalse(failure.toString().contains("UNIT_ONLY_SECRET"));assertTrue(fixture.rolledBack);
        }
        Jdbc unavailable=new Jdbc();unavailable.failAt="acquire";assertFalse(unavailable.repository().ready());
    }
    @Test void writesCannotOpenTheirOwnConnectionOutsideTheExistingIdempotentCallback(){
        Jdbc fixture=new Jdbc();PostgresAssetImportRepository repository=fixture.repository();
        assertThrows(IllegalStateException.class,()->repository.acceptSource(ACCESS,"MOP-FAKE.csv",ImportFormat.CSV,new byte[]{1}));
        assertThrows(IllegalStateException.class,()->repository.requestParse(ACCESS,SOURCE,1,new ParseSelection(ImportFormat.CSV,"UTF-8",",",null,1)));
        assertEquals(0,fixture.acquired);
    }
    @Test void jobScopeRestoresNestedFlagsAndCannotLeakThroughRollbackOrUnrelatedTransaction()throws Exception{
        List<String> calls=new ArrayList<>();Connection connection=proxy(Connection.class,(method,args)->switch(method){case "setAutoCommit"->{assertEquals(false,args[0]);calls.add("begin");yield null;}case "commit","rollback","close"->{calls.add(method);yield null;}default->throw new AssertionError(method);});
        DataSource source=proxy(DataSource.class,(method,args)->{assertEquals("getConnection",method);return connection;});PostgresPlatformRepository platform=new PostgresPlatformRepository(source,MAPPER);
        assertThrows(IllegalStateException.class,platform::importCommandConnection);
        assertSame(connection,platform.importJobTransaction(()->{Connection outer=platform.importCommandConnection();assertThrows(IllegalStateException.class,()->platform.importJobTransaction(()->{assertSame(outer,platform.importCommandConnection());throw new IllegalStateException("isolated nested abort");}));return platform.importCommandConnection();}));
        assertThrows(IllegalStateException.class,platform::importCommandConnection);assertEquals(List.of("begin","commit","close"),calls);calls.clear();
        assertThrows(IllegalStateException.class,()->platform.importJobTransaction(()->{assertSame(connection,platform.importCommandConnection());throw new IllegalStateException("isolated outer abort");}));assertThrows(IllegalStateException.class,platform::importCommandConnection);assertEquals(List.of("begin","rollback","close"),calls);
        var unprotected=PostgresPlatformRepository.class.getDeclaredMethod("inTransaction",java.util.function.Supplier.class);unprotected.setAccessible(true);
        InvocationTargetException failure=assertThrows(InvocationTargetException.class,()->unprotected.invoke(platform,(java.util.function.Supplier<Connection>)platform::importCommandConnection));assertInstanceOf(IllegalStateException.class,failure.getCause());assertThrows(IllegalStateException.class,platform::importCommandConnection);
    }
    @Test void canonicalHashesIgnoreObjectPropertyOrderButBindNullValuesEpochAndRawLexicalCells()throws Exception{
        String one=PostgresAssetImportRepository.canonicalHash(MAPPER.readTree("{\"source_id\":\"a\",\"mapping\":{\"NOTE\":1,\"NAME\":null},\"rows\":[{\"lexical\":\"001\",\"date_epoch\":\"WINDOWS_1900\"}]}"));
        assertEquals(one,PostgresAssetImportRepository.canonicalHash(MAPPER.readTree("{\"rows\":[{\"date_epoch\":\"WINDOWS_1900\",\"lexical\":\"001\"}],\"mapping\":{\"NAME\":null,\"NOTE\":1},\"source_id\":\"a\"}")));
        for(String changed:List.of("{\"source_id\":\"b\",\"mapping\":{\"NOTE\":1,\"NAME\":null},\"rows\":[{\"lexical\":\"001\",\"date_epoch\":\"WINDOWS_1900\"}]}","{\"source_id\":\"a\",\"mapping\":{\"NOTE\":1,\"NAME\":null},\"rows\":[{\"lexical\":\"1\",\"date_epoch\":\"WINDOWS_1900\"}]}","{\"source_id\":\"a\",\"mapping\":{\"NOTE\":1,\"NAME\":null},\"rows\":[{\"lexical\":\"001\",\"date_epoch\":\"MAC_1904\"}]}"))assertNotEquals(one,PostgresAssetImportRepository.canonicalHash(MAPPER.readTree(changed)));
    }
    @Test void mappedRowsRetainRawCoordinateCellAndExplicitUnmappedNull(){
        var date=new ParsedArtifact.SourceCell(0,ParsedArtifact.CellKind.NUMERIC,"42005","2015-01-01",3,"yyyy-mm-dd");var note=new ParsedArtifact.SourceCell(1,ParsedArtifact.CellKind.TEXT,"  原始备注  ","  原始备注  ",null,null);
        var artifact=new ParsedArtifact(ParsedArtifact.State.PARSED,ImportFormat.XLSX,List.of(new ParsedArtifact.SheetInfo(0,"MOP-FAKE",false)),0,1,ParsedArtifact.DateEpoch.WINDOWS_1900,ParsedArtifact.columns(List.of("日期","备注")),List.of(new ParsedArtifact.SourceRow(1,2,null,null,List.of(date,note))),"isolated-parser",ImportLimits.RESOURCE_POLICY_VERSION);
        EnumMap<Column,Integer> targets=new EnumMap<>(Column.class);for(Column column:Column.values())targets.put(column,null);targets.put(Column.PURCHASE_DATE,0);targets.put(Column.NOTE,1);
        MappingRequest mapping=new MappingRequest(1,"a".repeat(64),targets,Set.of());mapping.validateAgainst(artifact);Map<String,Object> row=PostgresAssetImportRepository.mappedRows(artifact,mapping).get(0);Map<?,?> cells=(Map<?,?>)row.get("cells");
        assertEquals(15,cells.size());assertNull(cells.get("NAME"));assertSame(date,cells.get("PURCHASE_DATE"));assertSame(note,cells.get("NOTE"));assertEquals(1L,row.get("logical_record_number"));assertEquals(2,row.get("source_row_number"));assertNull(row.get("physical_start_line"));
    }
    private static Map<String,Object> row(Object... pairs){Map<String,Object> result=new HashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    /** Enforces same-connection reads, explicit SQL ownership, read-only isolation and absence of locks/writes. */
    private static final class Jdbc {
        Map<String,Object> auth=row("role_code","padmin","permission_version",7L);
        Map<String,Object> source=row("id",SOURCE,"tenant_id",TENANT,"created_by",ACTOR,"purpose","asset_import_source","original_name","MOP-FAKE.csv","declared_format","CSV","sha256","a".repeat(64),"object_key","asset-import-sources/"+TENANT+"/"+SOURCE,"storage_status","PENDING","parse_status","NOT_REQUESTED","store_operation_id",OP,"version",1L,"size_bytes",1L,"created_at",Timestamp.from(Instant.parse("2026-10-02T01:00:00Z")),"updated_at",Timestamp.from(Instant.parse("2026-10-02T01:00:00Z")));
        Map<String,Object> operation=row("id",OP,"tenant_id",TENANT,"source_id",SOURCE,"created_by",ACTOR,"kind","STORE_RAW","status","PENDING","step","RETRY_WAIT","created_at",Timestamp.from(Instant.parse("2026-10-02T01:00:00Z")),"updated_at",Timestamp.from(Instant.parse("2026-10-02T01:00:00Z")));
        int acquired;boolean readOnly,autoCommit=true,committed,rolledBack,closed;int isolation;String failAt;List<String> executed=new ArrayList<>();Map<String,List<Object>> parameters=new HashMap<>();
        PostgresAssetImportRepository repository(){DataSource dataSource=proxy(DataSource.class,(method,args)->{if("getConnection".equals(method)){acquired++;fail("acquire");return connection();}throw new AssertionError(method);});return new PostgresAssetImportRepository(dataSource,MAPPER,new PostgresPlatformRepository(dataSource,MAPPER));}
        Connection connection(){return proxy(Connection.class,(method,args)->switch(method){
            case "setReadOnly"->{assertEquals(true,args[0]);readOnly=true;yield null;}
            case "setTransactionIsolation"->{isolation=(int)args[0];assertEquals(Connection.TRANSACTION_REPEATABLE_READ,isolation);yield null;}
            case "setAutoCommit"->{assertEquals(false,args[0]);autoCommit=false;yield null;}
            case "prepareStatement"->{assertTrue(readOnly);assertFalse(autoCommit);assertEquals(Connection.TRANSACTION_REPEATABLE_READ,isolation);yield statement((String)args[0]);}
            case "commit"->{fail("commit");committed=true;yield null;}
            case "rollback"->{rolledBack=true;yield null;}
            case "close"->{closed=true;yield null;}
            default->throw new AssertionError(method);
        });}
        PreparedStatement statement(String sql){
            assertFalse(sql.contains("FOR SHARE"));assertFalse(sql.contains("FOR UPDATE"));String kind=sql.contains("FROM public.mop_membership")?"authorization":sql.contains("FROM public.mop_asset_import_source s")?"source":sql.contains("FROM public.mop_asset_import_operation o")?"operation":null;assertNotNull(kind);
            if(!"authorization".equals(kind)){assertTrue(sql.contains("tenant_id=?::uuid"));assertTrue(sql.contains("created_by=?::uuid"));}
            List<Object> bound=new ArrayList<>();parameters.put(kind,bound);return proxy(PreparedStatement.class,(method,args)->switch(method){case "setObject"->{assertEquals(bound.size()+1,args[0]);bound.add(args[1]);yield null;}case "executeQuery"->{executed.add(kind);fail(kind);Map<String,Object> data=switch(kind){case "authorization"->auth;case "source"->source;default->operation;};yield result(data);}case "close"->null;default->throw new AssertionError(method);});
        }
        ResultSet result(Map<String,Object> data){boolean[] consumed={false};return proxy(ResultSet.class,(method,args)->{if("next".equals(method)){boolean next=!consumed[0]&&data!=null;consumed[0]=true;return next;}if("close".equals(method))return null;Object value=data.get((String)args[0]);return switch(method){case "getString"->(String)value;case "getLong"->value==null?0L:((Number)value).longValue();case "getTimestamp"->value;default->throw new AssertionError(method);};});}
        void fail(String stage)throws SQLException{if(stage.equals(failAt))throw new SQLException("UNIT_ONLY_SECRET raw driver detail","08006");}
    }
    @FunctionalInterface private interface Call{Object call(String method,Object[] args)throws Throwable;}
    private static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{if(method.getDeclaringClass()==Object.class)return switch(method.getName()){case "toString"->"Isolated import JDBC double";case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->throw new AssertionError(method.getName());};return call.call(method.getName(),args==null?new Object[0]:args);}));}
}
