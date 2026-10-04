package cn.mop.assetexport;
import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.*;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zaxxer.hikari.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Existing real per-asset authorized snapshots -> real workbook bytes; not complete filtered async export. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresAssetLedgerProjectionIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static HikariDataSource pool;static PostgresPlatformRepository platform;static PostgresManualAssetMetadataRepository metadata;static String admin,assetId;
    @BeforeAll static void connect()throws Exception{String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(2);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);var mapper=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);platform=new PostgresPlatformRepository(pool,mapper);metadata=new PostgresManualAssetMetadataRepository(pool,platform,mapper);admin=actor("padmin");try(var c=pool.getConnection();var s=c.prepareStatement("SELECT asset_id::text FROM public.mop_asset_manual_details WHERE tenant_id=?::uuid ORDER BY asset_id LIMIT 1")){s.setString(1,TENANT);try(var r=s.executeQuery()){assertTrue(r.next(),"Existing metadata dataset required; no fixture replacement");assetId=r.getString(1);}}}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static String actor(String role){return platform.users().stream().filter(u->platform.membership(u.id(),TENANT).filter(m->m.role().equals(role)).isPresent()).findFirst().orElseThrow().id();}
    static Access access(String actor){return new Access(actor,TENANT,platform.membership(actor,TENANT).orElseThrow().permissionVersion(),"export-projection-readonly-test");}
    static long count(String table)throws SQLException{assertTrue(Set.of("mop_audit","mop_asset","mop_asset_manual_revision").contains(table));try(var c=pool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM public."+table)){r.next();return r.getLong(1);}}
    @Test void existingAuthorizedSnapshotRendersSameCoreAndManualMetadata()throws Exception{var source=metadata.current(access(admin),assetId);assertNotNull(source.snapshot().metadata());var input=AssetLedgerProjection.fromCurrent(UUID.fromString(TENANT),source);var encoded=AssetLedgerWorkbook.encode(UUID.fromString(TENANT),List.of(input),true,Instant.parse("2026-10-04T00:00:00Z"));try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(encoded.bytes()))){var row=workbook.getSheetAt(0).getRow(1);assertEquals(source.snapshot().asset().name(),row.getCell(0).getStringCellValue());assertEquals(source.snapshot().asset().managementIp(),row.getCell(2).getStringCellValue());assertEquals(source.snapshot().metadata().note(),row.getCell(14).getStringCellValue());assertEquals(CellType.BLANK,row.getCell(15).getCellType());}assertFalse(encoded.published());}
    @Test void existingInternalEngineerReadsUnderOwnCurrentMembership(){var current=metadata.current(access(actor("msp")),assetId);assertEquals(assetId,AssetLedgerProjection.fromCurrent(UUID.fromString(TENANT),current).assetId().toString());}
    @Test void customerRolesCannotObtainTheInternalMetadataProjection(){for(String role:List.of("viewer","tadmin"))assertEquals(403,assertThrows(DomainException.class,()->metadata.current(access(actor(role)),assetId)).status);}
    @Test void foreignAssetAndStaleMembershipAreRejectedBeforeRendering(){String foreign=platform.list(OTHER).get(0).id();assertEquals(404,assertThrows(DomainException.class,()->metadata.current(access(admin),foreign)).status);var current=access(admin);var stale=new Access(current.actorId(),current.tenantId(),current.permissionVersion()+1,current.traceId());assertEquals(401,assertThrows(DomainException.class,()->metadata.current(stale,assetId)).status);}
    @Test void conversionDoesNotWriteAuditAssetOrMetadataRows()throws Exception{var before=List.of(count("mop_audit"),count("mop_asset"),count("mop_asset_manual_revision"));var current=metadata.current(access(admin),assetId);AssetLedgerWorkbook.encode(UUID.fromString(TENANT),List.of(AssetLedgerProjection.fromCurrent(UUID.fromString(TENANT),current)),true,Instant.EPOCH);assertEquals(before,List.of(count("mop_audit"),count("mop_asset"),count("mop_asset_manual_revision")));}
    @Test void wrongSnapshotNamingCodecFailsClosedWhileConfiguredReaderReadsSameRow()throws Exception{long before=count("mop_asset_manual_revision");var incorrect=new PostgresManualAssetMetadataRepository(pool,platform,new ObjectMapper().findAndRegisterModules());assertEquals(503,assertThrows(DomainException.class,()->incorrect.current(access(admin),assetId)).status);assertNotNull(metadata.current(access(admin),assetId).snapshot().metadata());assertEquals(before,count("mop_asset_manual_revision"));}

    @Test void completeFilteredSnapshotEncodesEveryMatchingExistingRow()throws Exception{
        var current=metadata.current(access(admin),assetId);var filters=new cn.mop.domain.assetexport.AssetExportJobState.Filters(null,null,null,current.snapshot().asset().name());var command=new cn.mop.domain.assetexport.AssetExportJobState.Command(new cn.mop.domain.assetexport.AssetExportJobState.Access(UUID.fromString(TENANT),UUID.fromString(admin),access(admin).permissionVersion(),"padmin",true),filters);var selection=metadata.select(command);assertFalse(selection.rows().isEmpty());var rows=selection.rows().stream().map(r->AssetLedgerProjection.fromCurrent(UUID.fromString(TENANT),r)).toList();var result=AssetLedgerWorkbook.encode(UUID.fromString(TENANT),rows,true,selection.transactionStartedAt());try(var book=new XSSFWorkbook(new ByteArrayInputStream(result.bytes()))){assertEquals(rows.size(),book.getSheetAt(0).getLastRowNum());for(int i=0;i<rows.size();i++){assertEquals(selection.rows().get(i).snapshot().asset().name(),book.getSheetAt(0).getRow(i+1).getCell(0).getStringCellValue());assertEquals(CellType.BLANK,book.getSheetAt(0).getRow(i+1).getCell(15).getCellType());}}assertFalse(result.published());
    }
    @Test void emptyAuthorizedSelectionProducesHeaderOnlyWorkbookWithoutInventedRows()throws Exception{
        var filters=new cn.mop.domain.assetexport.AssetExportJobState.Filters(null,null,"",null);var command=new cn.mop.domain.assetexport.AssetExportJobState.Command(new cn.mop.domain.assetexport.AssetExportJobState.Access(UUID.fromString(TENANT),UUID.fromString(admin),access(admin).permissionVersion(),"padmin",true),filters);var selection=metadata.select(command);assertTrue(selection.rows().isEmpty());var result=AssetLedgerWorkbook.encode(UUID.fromString(TENANT),List.of(),true,selection.transactionStartedAt());try(var book=new XSSFWorkbook(new ByteArrayInputStream(result.bytes()))){assertEquals(1,book.getSheetAt(0).getPhysicalNumberOfRows());assertEquals(16,book.getSheetAt(0).getRow(0).getLastCellNum());}assertFalse(result.published());
    }

}
