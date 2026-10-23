package cn.mop.persistence;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** JDBC boundary tests: real production SQL/transaction code with instrumented JDBC, not a live PG run. */
class PostgresAssetBatchReadTest {
 static final String TENANT=id(1),ACTOR=id(2);
 static String id(int n){return String.format("79000000-0000-4000-8000-%012d",n);}
 DataSource ds;Connection c;PreparedStatement membership,caps,assets;ResultSet membershipRows,capsRows,assetRows;java.sql.Array selection,grants;PostgresPlatformRepository repo;List<String> sql;
 @BeforeEach void setup()throws Exception{
  ds=mock(DataSource.class);c=mock(Connection.class);when(ds.getConnection()).thenReturn(c);repo=new PostgresPlatformRepository(ds,new ObjectMapper());sql=new ArrayList<>();
  membership=mock(PreparedStatement.class);caps=mock(PreparedStatement.class);assets=mock(PreparedStatement.class);membershipRows=mock(ResultSet.class);capsRows=mock(ResultSet.class);assetRows=mock(ResultSet.class);selection=mock(java.sql.Array.class);grants=mock(java.sql.Array.class);
  when(c.prepareStatement(anyString())).thenAnswer(call->{String query=call.getArgument(0);sql.add(query);return query.contains("FROM public.mop_membership")?membership:query.contains("mop_effective_capabilities")?caps:assets;});
  when(membership.executeQuery()).thenReturn(membershipRows);when(membershipRows.next()).thenReturn(true);when(membershipRows.getLong(2)).thenReturn(7L);
  when(caps.executeQuery()).thenReturn(capsRows);when(capsRows.next()).thenReturn(true);when(capsRows.getArray(1)).thenReturn(grants);when(grants.getArray()).thenReturn(new String[]{"assets:read"});
  when(c.createArrayOf(eq("uuid"),any(Object[].class))).thenReturn(selection);when(assets.executeQuery()).thenReturn(assetRows);
 }
 void rows(int count)throws Exception{int[] index={-1};when(assetRows.next()).thenAnswer(call->++index[0]<count);when(assetRows.getString(anyString())).thenAnswer(call->{String key=call.getArgument(0);return switch(key){case "id"->id(100+index[0]);case "tenant_id"->TENANT;case "name"->"Synthetic "+index[0];case "type"->"switch";case "management_ip"->"192.0.2.1";case "site_id"->"synthetic-site";case "source"->"manual";case "availability"->"UNKNOWN";case "sync_state"->"PENDING_ONBOARDING";default->null;};});when(assetRows.getLong("version")).thenReturn(1L);}
 @Test void hundredAndOneAssetsNeedOneConnectionAndOneAssetSelect()throws Exception{
  List<String> ids=new ArrayList<>();for(int i=0;i<101;i++)ids.add(id(100+i));rows(101);
  var result=repo.authorizedAssetBatch(TENANT,ACTOR,7,ids);assertEquals(101,result.size());assertEquals(3,sql.size());assertTrue(sql.get(0).contains("u.active"));assertTrue(sql.get(2).contains("a.tenant_id=?::uuid AND a.id=ANY(?::uuid[])"));assertTrue(sql.get(2).endsWith("LIMIT 101"));
  verify(ds,times(1)).getConnection();verify(c).setReadOnly(true);verify(c).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);verify(c).setAutoCommit(false);verify(c).commit();verify(c).close();verify(assets).setString(1,TENANT);verify(assets).setArray(2,selection);verify(selection).free();assertThrows(UnsupportedOperationException.class,()->result.clear());
 }
 @Test void emptySelectionStillChecksCurrentAuthority()throws Exception{assertEquals(List.of(),repo.authorizedAssetBatch(TENANT,ACTOR,7,List.of()));assertEquals(2,sql.size());verify(assets,never()).executeQuery();verify(c).commit();}
 @Test void nullOversizedNoncanonicalAndDuplicateInputNeverReachesSql()throws Exception{
  List<List<String>> invalid=new ArrayList<>();invalid.add(null);invalid.add(Collections.nCopies(102,id(100)));invalid.add(List.of(id(100),id(100)));invalid.add(Arrays.asList((String)null));invalid.add(List.of("not-a-uuid"));invalid.add(List.of("79000000-0000-4000-8000-0000000000AB"));
  for(var ids:invalid)assertEquals(400,assertThrows(DomainException.class,()->repo.authorizedAssetBatch(TENANT,ACTOR,7,ids)).status);verifyNoInteractions(ds);
 }
 @Test void inactiveOrVersionChangedMembershipFailsBeforeAnyAssetSelect()throws Exception{when(membershipRows.getLong(2)).thenReturn(8L);assertEquals(401,assertThrows(DomainException.class,()->repo.authorizedAssetBatch(TENANT,ACTOR,7,List.of(id(100)))).status);assertEquals(1,sql.size());verify(c).rollback();verify(assets,never()).executeQuery();}
 @Test void missingMembershipFailsBeforeCapabilitiesOrAssets()throws Exception{when(membershipRows.next()).thenReturn(false);assertEquals(401,assertThrows(DomainException.class,()->repo.authorizedAssetBatch(TENANT,ACTOR,7,List.of(id(100)))).status);assertEquals(1,sql.size());verify(c).rollback();}
 @Test void capabilityRevocationStopsAssetQueryEvenForEmptySelection()throws Exception{when(grants.getArray()).thenReturn(new String[]{});assertEquals(403,assertThrows(DomainException.class,()->repo.authorizedAssetBatch(TENANT,ACTOR,7,List.of())).status);assertEquals(2,sql.size());verify(assets,never()).executeQuery();}
 @Test void absentAssetsRemainAbsentInsteadOfFallingBackToUnscopedFind()throws Exception{when(assetRows.next()).thenReturn(false);assertTrue(repo.authorizedAssetBatch(TENANT,ACTOR,7,List.of(id(100))).isEmpty());assertEquals(3,sql.size());verify(c).commit();}
 @Test void sqlFailureFreesArrayRollsBackAndNeverReturnsPartialData()throws Exception{when(assets.executeQuery()).thenThrow(new SQLException("synthetic detail must not escape","08006"));var e=assertThrows(DomainException.class,()->repo.authorizedAssetBatch(TENANT,ACTOR,7,List.of(id(100))));assertEquals(503,e.status);assertFalse(e.getMessage().contains("synthetic detail"));verify(selection).free();verify(c).rollback();verify(c,never()).commit();verify(c).close();}
 @Test void inputSelectionIsBoundAsAnArrayNotInterpolatedIntoSql()throws Exception{rows(1);repo.authorizedAssetBatch(TENANT,ACTOR,7,List.of(id(100)));assertTrue(sql.stream().noneMatch(q->q.contains(id(100))));verify(c).createArrayOf("uuid",new Object[]{id(100)});verify(assets).setQueryTimeout(10);}
}
