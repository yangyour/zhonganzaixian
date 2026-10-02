package cn.mop.persistence;

import cn.mop.persistence.audit.AuditChainVerifier;
import cn.mop.persistence.audit.AuditChainVerifier.Row;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Existing real PG snapshots only. No audit write, identity mutation, migration or tamper of stored data. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresAuditChainIntegrationTest {
    static final List<String> TENANTS=List.of("55000000-0000-4000-8000-000000000001","55000000-0000-4000-8000-000000000002");
    static HikariDataSource pool;static final ObjectMapper JSON=new ObjectMapper();
    @BeforeAll static void connect(){String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(2);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static List<Row> rows(Connection c,String tenant,long from,long to)throws Exception{
        try(var s=c.prepareStatement("SELECT id::text,namespace,tenant_id::text,sequence,previous_hash,entry_hash,payload FROM public.mop_audit WHERE tenant_id::text=? AND sequence>=? AND sequence<=? ORDER BY sequence")){
            s.setQueryTimeout(10);s.setString(1,tenant);s.setLong(2,from);s.setLong(3,to);try(var r=s.executeQuery()){List<Row> result=new ArrayList<>();while(r.next())result.add(new Row(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getString(6),JSON.readTree(r.getString(7))));return result;}
        }
    }
    @Test void existingTenantChainsVerifyInBoundedReadOnlySnapshots()throws Exception{
        long records=0;int ranges=0;
        for(String tenant:TENANTS)try(var c=pool.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            long tail;try(var s=c.prepareStatement("SELECT COALESCE(max(sequence),0) FROM public.mop_audit WHERE tenant_id::text=?")){s.setString(1,tenant);try(var r=s.executeQuery()){r.next();tail=r.getLong(1);}}
            assertTrue(tail>0,"Expected existing test tenant audit history, not an invented empty fixture");
            for(long from=1;from<=tail;from+=1000){long to=Math.min(tail,from+999);Row anchor=from==1?null:rows(c,tenant,from-1,from-1).get(0);var result=AuditChainVerifier.verify(tenant,from,to,anchor,rows(c,tenant,from,to));assertTrue(result.valid(),()->"Audit chain verification failed: "+result.failure()+" at "+result.failureSequence());assertFalse(result.externallyAnchored());records+=result.verifiedRecords();ranges++;}
            c.rollback();
        }
        System.out.println("MOP_AUDIT_CHAIN_READ_ONLY records="+records+" ranges="+ranges+" tenants=2");
    }
    @Test void changedInMemoryCopyOfRealPayloadFailsWithoutAlteringStoredAudit()throws Exception{
        String tenant=TENANTS.get(0);try(var c=pool.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            Row original=rows(c,tenant,1,1).get(0);ObjectNode altered=(ObjectNode)original.payload();altered.put("reason","SYNTHETIC_IN_MEMORY_CHANGE_ONLY");Row changed=new Row(original.id(),original.namespace(),original.tenantId(),original.sequence(),original.previousHash(),original.entryHash(),altered);
            assertEquals(AuditChainVerifier.Failure.HASH_MISMATCH,AuditChainVerifier.verify(tenant,1,1,null,List.of(changed)).failure());Row unchanged=rows(c,tenant,1,1).get(0);assertEquals(original,unchanged);assertTrue(AuditChainVerifier.verify(tenant,1,1,null,List.of(unchanged)).valid());c.rollback();
        }
    }
}
