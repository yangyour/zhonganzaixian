package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.assetimport.source.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static cn.mop.persistence.PostgresAssetImportIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;
/** Explicit disposable PostgreSQL only. Tests never migrate or start services. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresAssetImportSourceListIntegrationTest {
 @BeforeAll static void begin(){connect();}
 @AfterAll static void end(){close();}
 @SuppressWarnings("unchecked") static List<Map<String,Object>> items(Map<String,Object> page){return (List<Map<String,Object>>)page.get("items");}
 @Test void keysetPagesKeepOwnerScopeAndNeverIncludeRawContent(){
  Set<String> seeded=new HashSet<>();for(int i=0;i<23;i++)seeded.add(accept(UUID.randomUUID().toString()).source());
  var adminAccess=access(admin,TENANT);var other=(Map<?,?>)platform.idempotent(admin,TENANT,"/qa/list-admin",UUID.randomUUID().toString(),"synthetic",adminAccess.permissionVersion(),()->repository.acceptSource(adminAccess,"SYNTHETIC_OTHER_OWNER.csv",ImportFormat.CSV,RAW));String otherId=(String)other.get("source_id");
  Set<String> seen=new HashSet<>();String cursor=null;int pages=0;
  do{var page=repository.listSources(access(),7,cursor);assertEquals(TENANT,page.get("tenant_id"));assertEquals(actor,page.get("created_by"));assertEquals("CREATOR_ONLY",page.get("scope"));assertEquals(false,page.get("can_import"));assertTrue(items(page).size()<=7);
   for(var item:items(page)){assertTrue(seen.add((String)item.get("id")));assertEquals(Set.of("id","original_name","declared_format","size_bytes","version","storage_status","parse_status","mapping_saved","created_at","updated_at"),item.keySet());assertNotEquals(otherId,item.get("id"));}
   cursor=(String)page.get("next_cursor");if(cursor!=null)assertEquals(items(page).get(items(page).size()-1).get("id"),cursor);assertTrue(++pages<100);
  }while(cursor!=null);assertTrue(seen.containsAll(seeded));assertTrue(pages>=4);
 }
 @Test void otherOwnerCursorCannotBeUsed(){var accepted=accept(UUID.randomUUID().toString());assertEquals(404,assertThrows(DomainException.class,()->repository.listSources(access(admin,TENANT),20,accepted.source())).status);}
 @Test void freshPermissionAndMembershipVersionAreRequired(){assertEquals(403,assertThrows(DomainException.class,()->repository.listSources(access(viewer,TENANT),20,null)).status);var stale=new AssetImportSourceRepository.Access(actor,TENANT,access().permissionVersion()+1,"list-stale");assertEquals(401,assertThrows(DomainException.class,()->repository.listSources(stale,20,null)).status);}
 @Test void badBoundsAndMissingCursorFailClosed(){for(int n:new int[]{0,-1,51})assertEquals(400,assertThrows(DomainException.class,()->repository.listSources(access(),n,null)).status);assertEquals(404,assertThrows(DomainException.class,()->repository.listSources(access(),20,UUID.randomUUID().toString())).status);}
 @Test void insertingANewerSourceDoesNotRepeatThePreviousPage(){for(int i=0;i<4;i++)accept(UUID.randomUUID().toString());var first=repository.listSources(access(),2,null);String cursor=(String)first.get("next_cursor");assertNotNull(cursor);String newer=accept(UUID.randomUUID().toString()).source();var second=repository.listSources(access(),2,cursor);Set<String> previous=new HashSet<>();items(first).forEach(i->previous.add((String)i.get("id")));for(var i:items(second)){assertFalse(previous.contains(i.get("id")));assertNotEquals(newer,i.get("id"));}}
}
