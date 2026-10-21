package cn.mop.api;
import cn.mop.adapters.cmdb.CustomerCmdbDictionary;
import cn.mop.domain.*;
import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CmdbSourcePreflightControllerTest {
 static final String USER="64000000-0000-4000-8000-000000000001",TENANT="64000000-0000-4000-8000-000000000002",ID="64000000-0000-4000-8000-000000000003",ART="64000000-0000-4000-8000-000000000004",OP="64000000-0000-4000-8000-000000000005";
 final ObjectMapper mapper=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
 PlatformRepository platform;AssetImportSourceRepository sources;CmdbSourcePreflightController controller;Map<String,Object> source;Map<String,Object> stored;
 @BeforeEach void setup(){
  platform=mock(PlatformRepository.class);sources=mock(AssetImportSourceRepository.class);controller=new CmdbSourcePreflightController(platform,sources,mapper);
  when(platform.membership(USER,TENANT)).thenReturn(Optional.of(new Membership(USER,TENANT,"padmin",7,true)));
  var headers=new ArrayList<String>();CustomerCmdbDictionary.load(mapper).document().path("import_columns").elements().forEachRemaining(v->headers.add(v.asText()));
  var cells=List.of(cell(0,"Synthetic"),cell(1,"SW"),cell(2,"192.0.2.1"),cell(16,"sw_core"));
  var artifact=new ParsedArtifact(ParsedArtifact.State.PARSED,ImportFormat.CSV,List.of(new ParsedArtifact.SheetInfo(0,"CSV",false)),0,1,ParsedArtifact.DateEpoch.NOT_APPLICABLE,ParsedArtifact.columns(headers),List.of(new ParsedArtifact.SourceRow(2,2,2L,2L,cells)),"synthetic","synthetic");
  stored=mapper.convertValue(artifact,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
  stored.putAll(Map.of("id",ART,"tenant_id",TENANT,"source_id",ID,"operation_id",OP,"parse_hash","a".repeat(64),"source_sha256","b".repeat(64),"schema_version",AssetImportSchema.VERSION));
  source=new LinkedHashMap<>(Map.of("id",ID,"tenant_id",TENANT,"created_by",USER,"version",3L,"parse_status","PARSED","latest_artifact_id",ART,"parse_operation_id",OP,"sha256","b".repeat(64),"parsed_artifact",stored));
  when(sources.source(any(),eq(ID))).thenReturn(source);
 }
 static ParsedArtifact.SourceCell cell(int i,String v){return new ParsedArtifact.SourceCell(i,ParsedArtifact.CellKind.TEXT,v,v,null,null);}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/assets/import-sources/"+ID+"/cmdb-preflight");r.setUserPrincipal(()->USER);r.getSession().setAttribute("mop.tenant_id",TENANT);return r;}
 Map<?,?> read(MockHttpServletRequest request){return (Map<?,?>)((Map<?,?>)controller.get(ID,request,new MockHttpServletResponse())).get("data");}
 @Test void boundedReadOnlySourceResult(){var r=read(request());assertEquals("CONFIG_PENDING",r.get("status"));assertEquals(1,r.get("records"));assertEquals(false,r.get("can_import"));assertEquals(false,r.get("asset_mutations"));assertEquals("NOT_RUN",r.get("database_checks"));assertFalse(r.containsKey("rows"));}
 @Test void reauthorizesAndOnlyReadsOwnerSource(){read(request());verify(platform).membership(USER,TENANT);verify(sources).source(argThat(a->a.actorId().equals(USER)&&a.tenantId().equals(TENANT)&&a.permissionVersion()==7),eq(ID));verifyNoMoreInteractions(platform,sources);}
 @Test void anonymousAndInactiveFailBeforeSourceRead(){var r=request();r.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->read(r)).status);when(platform.membership(USER,TENANT)).thenReturn(Optional.of(new Membership(USER,TENANT,"padmin",7,false)));assertEquals(401,assertThrows(DomainException.class,()->read(request())).status);verifyNoInteractions(sources);}
 @Test void readOnlyViewerCannotPreflight(){when(platform.membership(USER,TENANT)).thenReturn(Optional.of(new Membership(USER,TENANT,"viewer",7,true)));assertEquals(403,assertThrows(DomainException.class,()->read(request())).status);verifyNoInteractions(sources);}
 @Test void queryCannotProvideRulesOrTenant(){var r=request();r.addParameter("tenant_id","other");assertEquals(400,assertThrows(DomainException.class,()->read(r)).status);verifyNoInteractions(sources);}
 @Test void unparsedSourceRequiresExplicitSelection(){source.put("parse_status","PENDING");assertEquals(409,assertThrows(DomainException.class,()->read(request())).status);}
 @Test void crossTenantAndArtifactIdentityDriftFailClosed(){source.put("tenant_id",USER);assertEquals(503,assertThrows(DomainException.class,()->read(request())).status);source.put("tenant_id",TENANT);stored.put("source_sha256","c".repeat(64));assertEquals(503,assertThrows(DomainException.class,()->read(request())).status);}
 @Test void malformedArtifactAndWrongHeaderAreDistinct(){stored.put("columns",List.of());assertEquals(503,assertThrows(DomainException.class,()->read(request())).status);setup();((Map<String,Object>)((List<?>)stored.get("columns")).get(0)).put("header","wrong");assertEquals(422,assertThrows(DomainException.class,()->read(request())).status);}
 @Test void noStoreAndNoReferrer(){var response=new MockHttpServletResponse();controller.get(ID,request(),response);assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("no-referrer",response.getHeader("Referrer-Policy"));}
 @Test void actualControllerMatchesWireFixture()throws Exception{
  var actual=mapper.readTree(mapper.writeValueAsBytes(controller.get(ID,request(),new MockHttpServletResponse())));var p=java.nio.file.Path.of("../../fixtures/cmdb-source-preflight-controller.json");
  if(Boolean.getBoolean("mop.exportCmdbSourceFixture"))java.nio.file.Files.writeString(p,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual)+"\n");
  assertEquals(actual,mapper.readTree(java.nio.file.Files.readString(p)));
 }

 Map<?,?> names(){return (Map<?,?>)((Map<?,?>)controller.names(ID,request(),new MockHttpServletResponse())).get("data");}
 void snapshot(Set<String> existing){when(sources.assetNameSnapshot(any(),eq(ID))).thenReturn(new AssetImportSourceRepository.AssetNameSnapshot(source,existing));}
 @Test void databaseNamesUsesOnlyAtomicSnapshotAndRedactsExistingNames(){snapshot(Set.of("Synthetic"));var r=names();assertEquals("ERROR",r.get("status"));assertEquals(1,r.get("error_rows"));assertEquals("EXACT_ASSET_NAMES_ONLY",r.get("database_checks"));assertEquals("SOURCE_DICTIONARY_AND_ASSET_NAMES",r.get("scope"));assertEquals(false,r.get("can_import"));assertFalse(r.toString().contains("Synthetic"));verify(sources,never()).source(any(),any());verify(sources).assetNameSnapshot(argThat(a->a.tenantId().equals(TENANT)&&a.actorId().equals(USER)&&a.permissionVersion()==7),eq(ID));}
 @Test void noCollisionStillDoesNotApproveImport(){snapshot(Set.of());var r=names();assertEquals("CONFIG_PENDING",r.get("status"));assertEquals(false,r.get("production_approved"));assertEquals(false,r.get("asset_mutations"));}
 @Test void unexpectedNameOutsideSourceFailsClosed(){snapshot(Set.of("foreign-data"));assertEquals(503,assertThrows(DomainException.class,this::names).status);}
 @Test void databaseLookupFailureCannotReturnDictionaryOnlySuccess(){when(sources.assetNameSnapshot(any(),eq(ID))).thenThrow(new DomainException(503,"UPSTREAM_UNAVAILABLE","unavailable"));assertEquals(503,assertThrows(DomainException.class,this::names).status);verify(sources,never()).source(any(),any());}
 @Test void databaseSnapshotUnparsedAndWrongHeadersFail(){snapshot(Set.of());source.put("parse_status","PENDING");snapshot(Set.of());assertEquals(409,assertThrows(DomainException.class,this::names).status);setup();((Map<String,Object>)((List<?>)stored.get("columns")).get(0)).put("header","wrong");snapshot(Set.of());assertEquals(422,assertThrows(DomainException.class,this::names).status);}
 @Test void databaseNamesCannotBeClientOverridden(){var r=request();r.addParameter("names","Synthetic");assertEquals(400,assertThrows(DomainException.class,()->controller.names(ID,r,new MockHttpServletResponse())).status);verifyNoInteractions(sources);}
 @Test void databaseNamesRechecksAccessBeforeStorage(){when(platform.membership(USER,TENANT)).thenReturn(Optional.of(new Membership(USER,TENANT,"viewer",7,true)));assertEquals(403,assertThrows(DomainException.class,this::names).status);verifyNoInteractions(sources);}
 @Test void databaseNamesNoStore(){snapshot(Set.of());var response=new MockHttpServletResponse();controller.names(ID,request(),response);assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("no-referrer",response.getHeader("Referrer-Policy"));}
}
