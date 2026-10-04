package cn.mop.persistence;
import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionWorksheetRepository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static cn.mop.persistence.PostgresInspectionWorksheetIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real approved PG. Optional pre-application DDL transaction always rolls back; actual runtime checks are separate. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresInspectionSourceCollectionSchemaIntegrationTest {
    static final boolean DRY_RUN="true".equals(System.getenv("MOP_SOURCE_COLLECTION_SCHEMA_DRY_RUN"));
    final PostgresInspectionWorksheetIntegrationTest f=new PostgresInspectionWorksheetIntegrationTest();
    Entry one;InspectionSourceManifest manifest;byte[] document;
    @BeforeAll static void connectSources()throws Exception{connect();}
    @AfterAll static void closeSources(){close();}
    @BeforeEach void prepare(){f.source();one=f.create(filled("😀\n真实测试原文"));manifest=repository.captureSources(admin(),List.of(new Selection(one.id(),1)));document=InspectionSourceDocumentCodec.encode(manifest);}
    @AfterEach void archiveSources(){f.archiveOnlyOwnedDrafts();}
    Connection transaction()throws Exception{
        Connection c;
        if(DRY_RUN){assertEquals("mop_test_migrator",System.getenv("MOP_MIGRATION_USERNAME"));c=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("MOP_MIGRATION_USERNAME"),System.getenv("MOP_MIGRATION_PASSWORD"));c.setAutoCommit(false);
            try(var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('public.mop_inspection_source_collection') IS NULL")){assertTrue(r.next());assertTrue(r.getBoolean(1));}
            try(var in=getClass().getResourceAsStream("/db/migration/V23__immutable_inspection_source_collections.sql");var s=c.createStatement()){assertNotNull(in);s.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        }else{c=pool.getConnection();c.setAutoCommit(false);}
        return c;
    }
    String head(Connection c,byte[] bytes,String tenant,String hash,int count)throws Exception{
        String id=UUID.randomUUID().toString();try(var s=c.prepareStatement("INSERT INTO public.mop_inspection_source_collection(id,tenant_id,created_by,label,recorded_at,source_count,document,source_manifest_sha256) VALUES(?::uuid,?::uuid,?::uuid,'SYNTHETIC_SOURCE_COLLECTION',date_trunc('milliseconds',clock_timestamp()),?,?,?)")){s.setString(1,id);s.setString(2,tenant);s.setString(3,admin);s.setInt(4,count);s.setBytes(5,bytes);s.setString(6,hash);assertEquals(1,s.executeUpdate());}return id;
    }
    String head(Connection c)throws Exception{return head(c,document,B,manifest.fingerprint(),1);}
    void item(Connection c,String collection,int position,String worksheet,long version,String sha)throws Exception{try(var s=c.prepareStatement("INSERT INTO public.mop_inspection_source_collection_item(tenant_id,collection_id,position,worksheet_id,worksheet_version,source_snapshot_sha256) VALUES(?::uuid,?::uuid,?,?::uuid,?,?)")){s.setString(1,B);s.setString(2,collection);s.setInt(3,position);s.setString(4,worksheet);s.setLong(5,version);s.setString(6,sha);s.executeUpdate();}}
    void item(Connection c,String collection)throws Exception{item(c,collection,1,one.id(),1,manifest.sources().get(0).fingerprint());}
    void flush(Connection c)throws Exception{try(var s=c.createStatement()){s.execute("SET CONSTRAINTS ALL IMMEDIATE");}}
    byte[] changed(Consumer<ObjectNode> edit)throws Exception{var node=(ObjectNode)new ObjectMapper().readTree(document);edit.accept(node);return node.toString().getBytes(StandardCharsets.UTF_8);}
    ObjectNode src(ObjectNode root){return (ObjectNode)root.path("sources").get(0);}
    @Test void completeCollectionMatchesDocumentAndAllJavaDigests()throws Exception{try(var c=transaction()){try{String id=head(c);item(c,id);flush(c);try(var s=c.prepareStatement("SELECT document,document_sha256,source_manifest_sha256,source_count,report_published,formal_report_generatable FROM public.mop_inspection_source_collection WHERE id=?::uuid")){s.setString(1,id);try(var r=s.executeQuery()){assertTrue(r.next());assertArrayEquals(document,r.getBytes(1));assertEquals(InspectionSourceDocumentCodec.hash(document),r.getString(2));assertEquals(manifest,InspectionSourceDocumentCodec.decode(r.getBytes(1),UUID.fromString(B),r.getString(2),r.getString(3)));assertEquals(1,r.getInt(4));assertFalse(r.getBoolean(5));assertFalse(r.getBoolean(6));}}}finally{c.rollback();}}}
    @Test void incompleteCollectionCannotPassDeferredCompleteness()throws Exception{try(var c=transaction()){try{head(c);assertEquals("23514",assertThrows(SQLException.class,()->flush(c)).getSQLState());}finally{c.rollback();}}}
    @Test void wrongItemHashCannotPassDeferredCompleteness()throws Exception{try(var c=transaction()){try{String id=head(c);item(c,id,1,one.id(),1,"0".repeat(64));assertEquals("23514",assertThrows(SQLException.class,()->flush(c)).getSQLState());}finally{c.rollback();}}}
    @Test void wrongVersionAndForeignParentAreRejectedByScopedForeignKeys()throws Exception{try(var c=transaction()){try{String id=head(c);assertEquals("23503",assertThrows(SQLException.class,()->item(c,id,1,one.id(),999,manifest.sources().get(0).fingerprint())).getSQLState());}finally{c.rollback();}}try(var c=transaction()){try{assertEquals("23503",assertThrows(SQLException.class,()->item(c,UUID.randomUUID().toString())).getSQLState());}finally{c.rollback();}}}
    @Test void explicitForeignTenantCannotCaptureKnownOtherTenantWorksheet()throws Exception{byte[] bytes=changed(root->root.put("tenant_id",A));try(var c=transaction()){try{assertEquals("23503",assertThrows(SQLException.class,()->head(c,bytes,A,manifest.fingerprint(),1)).getSQLState());}finally{c.rollback();}}}
    @Test void bodyAndTemplateAndRecordingChangesAreRejectedEvenWithMatchingOuterBytes()throws Exception{for(var edit:List.<Consumer<ObjectNode>>of(r->((ObjectNode)src(r).get("content")).put("asset_name","forged"),r->((ObjectNode)src(r).path("content").path("template")).put("name","forged"),r->src(r).put("recorded_by",UUID.randomUUID().toString()),r->src(r).put("action","UPDATE"))){byte[] bytes=changed(edit);try(var c=transaction()){try{assertEquals("23514",assertThrows(SQLException.class,()->head(c,bytes,B,manifest.fingerprint(),1)).getSQLState());}finally{c.rollback();}}}}
    @Test void sourceAndManifestHashesAreRecomputedInDatabase()throws Exception{byte[] source=changed(r->src(r).put("source_sha256","f".repeat(64))),manifestBytes=changed(r->r.put("manifest_sha256","f".repeat(64)));try(var c=transaction()){try{assertEquals("23514",assertThrows(SQLException.class,()->head(c,source,B,manifest.fingerprint(),1)).getSQLState());}finally{c.rollback();}}try(var c=transaction()){try{assertEquals("23514",assertThrows(SQLException.class,()->head(c,manifestBytes,B,"f".repeat(64),1)).getSQLState());}finally{c.rollback();}}}
    @Test void unsupportedEvidenceReferencesCannotBeSmuggledIntoCurrentWorksheetSources()throws Exception{byte[] bytes=changed(r->((ObjectNode)src(r).path("content").path("observations").get(0)).withArray("evidence_ids").add(UUID.randomUUID().toString()));try(var c=transaction()){try{assertEquals("23514",assertThrows(SQLException.class,()->head(c,bytes,B,manifest.fingerprint(),1)).getSQLState());}finally{c.rollback();}}}
    @Test void headerChangesAndTemplateArchivalDoNotRewriteSelectedOldRevision()throws Exception{f.archive(f.update(one,empty()));f.templateChange(f.template,true);try(var c=transaction()){try{String id=head(c);item(c,id);flush(c);}finally{c.rollback();}}}
    @Test void multipleSelectedVersionsAndArchivedBooleanMatchCanonicalHashesAndRejectReordering()throws Exception{
        var two=f.update(one,empty());var archived=f.archive(two);var selected=repository.captureSources(admin(),List.of(new Selection(one.id(),3),new Selection(one.id(),1),new Selection(one.id(),2)));byte[] bytes=InspectionSourceDocumentCodec.encode(selected);
        assertTrue(selected.sources().get(2).revisionArchived());assertEquals(selected.sources().get(1).content().fingerprint(),selected.sources().get(2).content().fingerprint());
        try(var c=transaction()){try{String collection=head(c,bytes,B,selected.fingerprint(),3);for(int i=0;i<3;i++){var source=selected.sources().get(i);item(c,collection,i+1,source.worksheetId().toString(),source.version(),source.fingerprint());}flush(c);}finally{c.rollback();}}
        var root=(ObjectNode)new ObjectMapper().readTree(bytes);var array=root.withArray("sources");var first=array.get(0);array.set(0,array.get(2));array.set(2,first);byte[] wrong=root.toString().getBytes(StandardCharsets.UTF_8);
        try(var c=transaction()){try{assertEquals("23514",assertThrows(SQLException.class,()->head(c,wrong,B,selected.fingerprint(),3)).getSQLState());}finally{c.rollback();}}
    }
    @Test void emptyCollectionPreservesEmptySelectionWithoutImplyingReportGeneration()throws Exception{var empty=new InspectionSourceManifest(UUID.fromString(B),List.of());try(var c=transaction()){try{head(c,InspectionSourceDocumentCodec.encode(empty),B,empty.fingerprint(),0);flush(c);}finally{c.rollback();}}}
    @Test void boundsAndSourceCountDisagreementFailBeforeAcceptance()throws Exception{for(int count:new int[]{-1,0,1001})try(var c=transaction()){try{assertEquals("23514",assertThrows(SQLException.class,()->head(c,document,B,manifest.fingerprint(),count)).getSQLState());}finally{c.rollback();}}}
    @Test void earlyConstraintFlushCannotPermitAdditionalMembersInSameTransaction()throws Exception{try(var c=transaction()){try{String id=head(c);item(c,id);flush(c);assertEquals("23514",assertThrows(SQLException.class,()->item(c,id,2,one.id(),1,manifest.sources().get(0).fingerprint())).getSQLState());}finally{c.rollback();}}}
    @Test void directFalseApprovalAndCallerSuppliedTransactionAreRejected()throws Exception{
        for(var extra:Map.of("report_published","true","formal_report_generatable","true","insert_transaction","'0'::xid8").entrySet())try(var c=transaction()){try{try(var statement=c.prepareStatement("INSERT INTO public.mop_inspection_source_collection(id,tenant_id,created_by,label,recorded_at,source_count,document,source_manifest_sha256,"+extra.getKey()+") VALUES(?::uuid,?::uuid,?::uuid,'SYNTHETIC_SOURCE_COLLECTION_REJECTED',date_trunc('milliseconds',clock_timestamp()),1,?,?,"+extra.getValue()+")")){statement.setString(1,UUID.randomUUID().toString());statement.setString(2,B);statement.setString(3,admin);statement.setBytes(4,document);statement.setString(5,manifest.fingerprint());assertEquals("23514",assertThrows(SQLException.class,statement::executeUpdate).getSQLState());}}finally{c.rollback();}}
    }
    @Test void laterTransactionCannotAddItemsToAnAlreadyCommittedOwnedCollection()throws Exception{
        Assumptions.assumeFalse(DRY_RUN,"Pre-application DDL transaction must not commit schema");String collection;
        try(var c=transaction()){collection=head(c);item(c,collection);c.commit();}
        try(var c=transaction()){try{assertEquals("23514",assertThrows(SQLException.class,()->item(c,collection)).getSQLState());}finally{c.rollback();}}
        try(var c=pool.getConnection();var statement=c.prepareStatement("SELECT label,source_count,document_sha256 FROM public.mop_inspection_source_collection WHERE tenant_id=?::uuid AND id=?::uuid")){statement.setString(1,B);statement.setString(2,collection);try(var result=statement.executeQuery()){assertTrue(result.next());assertEquals("SYNTHETIC_SOURCE_COLLECTION",result.getString(1));assertEquals(1,result.getInt(2));assertEquals(InspectionSourceDocumentCodec.hash(document),result.getString(3));}}
    }
    @Test void createdStructureMatchesTheReviewedVerifier()throws Exception{try(var c=transaction()){try{InspectionSourceCollectionSchemaVerifier.verify(c);}finally{c.rollback();}}}
    @Test void actualRuntimeAclHasNoMutationOrPublicAccess()throws Exception{Assumptions.assumeFalse(DRY_RUN,"Rolled-back migrator DDL precheck is not runtime ACL evidence");try(var c=transaction()){try{for(String table:List.of("mop_inspection_source_collection","mop_inspection_source_collection_item")){try(var s=c.createStatement();var r=s.executeQuery("SELECT current_user='mop_test_runtime' AND has_table_privilege(current_user,'public."+table+"','SELECT') AND has_table_privilege(current_user,'public."+table+"','INSERT') AND NOT has_table_privilege(current_user,'public."+table+"','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,'public."+table+"','UPDATE') AND NOT EXISTS(SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid='public."+table+"'::regclass AND a.grantee=0)")){assertTrue(r.next());assertTrue(r.getBoolean(1));}}}finally{c.rollback();}}}
}
