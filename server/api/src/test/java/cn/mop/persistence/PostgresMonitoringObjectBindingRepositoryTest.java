package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringObjectBinding.*;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.*;
import cn.mop.domain.monitoring.MonitoringObjectMapping.*;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** JDBC protocol doubles only, not designated PostgreSQL evidence. */
class PostgresMonitoringObjectBindingRepositoryTest {
 final UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),author=UUID.randomUUID(),asset=UUID.randomUUID(),pool=UUID.randomUUID(),id=UUID.randomUUID();
 final Instant at=Instant.parse("2026-10-23T00:00:00Z");final String hash="a".repeat(64);final Key key=new Key(pool,ObjectType.HOST,"23");
 final ObjectMapper json=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
 final PostgresPlatformRepository platform=mock(PostgresPlatformRepository.class);final Connection c=mock(Connection.class);final Source source=mock(Source.class);final Access access=new Access(tenant.toString(),actor.toString(),7,"unit-mapping");
 final List<String> inserts=new ArrayList<>();MockedStatic<MonitoringObjectBindingSchemaVerifier> schema;MockedStatic<PostgresAuthorization> authorization;
 Policy policy;State state;boolean foreign;Instant databaseTime=at.plusSeconds(3);long assetVersion=1;PostgresMonitoringObjectBindingRepository repository;
 @BeforeEach void setup()throws Exception{
  policy=new Policy("v1:"+hash,Set.of(pool),Set.of(actor),false,60);state=MonitoringObjectBinding.create(new Document(id,tenant,key,"ZABBIX_7",author,at),asset,1,null);
  schema=mockStatic(MonitoringObjectBindingSchemaVerifier.class);authorization=mockStatic(PostgresAuthorization.class);
  when(platform.idempotentCommandConnection()).thenReturn(c);doAnswer(call->((Function<Connection,?>)call.getArgument(0)).apply(c)).when(platform).identityTransaction(any());when(platform.authorizedAssetBatch(eq(tenant.toString()),eq(actor.toString()),eq(7L),any())).thenReturn(List.of(mock(Asset.class)));
  when(source.provider(pool)).thenReturn("ZABBIX_7");when(source.definitionSha256(pool)).thenReturn(hash);when(source.verify(key)).thenAnswer(call->metadata(asset));
  when(c.prepareStatement(anyString())).thenAnswer(call->{String sql=call.getArgument(0);var stmt=mock(PreparedStatement.class);var values=new HashMap<Integer,Object>();doAnswer(inv->{values.put(inv.getArgument(0),inv.getArgument(1));return null;}).when(stmt).setString(anyInt(),anyString());
   when(stmt.executeUpdate()).thenAnswer(inv->{inserts.add(sql+"|"+values.get(5));return 1;});when(stmt.executeQuery()).thenAnswer(inv->{var r=mock(ResultSet.class);boolean found=true;
    if(sql.startsWith("SELECT m.role_code"))when(r.getString(1)).thenReturn("padmin");
    else if(sql.startsWith("SELECT m.permission_version")){when(r.getLong(1)).thenReturn(7L);when(r.getBoolean(2)).thenReturn(true);}
    else if(sql.startsWith("SELECT document"))when(r.getString(1)).thenReturn(json.writeValueAsString(state.document()));
    else if(sql.startsWith("SELECT version,state")){when(r.getLong(1)).thenReturn(state.version());when(r.getString(2)).thenReturn(json.writeValueAsString(state));}
    else if(sql.startsWith("SELECT version FROM public.mop_asset"))when(r.getLong(1)).thenReturn(assetVersion);
    else if(sql.startsWith("SELECT date_trunc"))when(r.getObject(1,OffsetDateTime.class)).thenReturn(databaseTime.atOffset(ZoneOffset.UTC));
    else if(sql.startsWith("SELECT 1 FROM public.mop_monitoring_object_binding b"))found=foreign;
    else throw new AssertionError("Unexpected query: "+sql);
    when(r.next()).thenReturn(found,false);return r;});return stmt;});
  repository=new PostgresMonitoringObjectBindingRepository(platform,json,t->policy,source);
 }
 @AfterEach void close(){schema.close();authorization.close();}
 SourceMetadata metadata(UUID claimed){return new SourceMetadata(key,"ZABBIX_7","23",pool,tenant,claimed,hash,MonitoringObjectBinding.metadataHash(key,"ZABBIX_7","23",pool,tenant,claimed));}
 Receipt activate(){return repository.change(access,id,Action.ACTIVATE,state.version(),null,null,policy.reference(),hash,null);}
 @Test void publishesOnlyAfterBoundReadThenAppendsAndAudits()throws Exception{var result=activate();assertEquals(2,result.acceptedVersion());assertTrue(result.acknowledgementOnly());assertEquals(1,inserts.size());assertTrue(inserts.get(0).contains("INSERT INTO public.mop_monitoring_object_binding_revision"));assertTrue(inserts.get(0).contains("activated_at"));verify(source).verify(key);verify(platform).idempotentCommandConnection();verify(platform).audit(eq(actor.toString()),eq(tenant.toString()),eq("monitoring_mapping.activate"),anyString(),eq(id.toString()),eq("SUCCEEDED"),eq("MAPPING_ONLY_NOT_COLLECTION_OR_DISPATCH"),eq("unit-mapping"));}
 @Test void staleAssetNeverCallsSourceOrAppends(){assetVersion=2;assertEquals(409,assertThrows(DomainException.class,this::activate).status);verify(source,never()).verify(any());assertTrue(inserts.isEmpty());}
 @Test void foreignPublishedMappingRemains404WithoutSourceDisclosure(){foreign=true;assertEquals(404,assertThrows(DomainException.class,this::activate).status);verify(source,never()).verify(any());assertTrue(inserts.isEmpty());}
 @Test void wrongSourceClaimDoesNotAppend(){when(source.verify(key)).thenReturn(metadata(UUID.randomUUID()));assertEquals(404,assertThrows(DomainException.class,this::activate).status);assertTrue(inserts.isEmpty());}
 @Test void policyChangedDuringSourceReadDoesNotAppend(){when(source.verify(key)).thenAnswer(call->{policy=new Policy("changed:"+hash,Set.of(pool),Set.of(actor),false,60);return metadata(asset);});assertEquals(409,assertThrows(DomainException.class,this::activate).status);assertTrue(inserts.isEmpty());}
 @Test void sourceFailureNeverInventsSuccessfulPublication(){when(source.verify(key)).thenThrow(new DomainException(503,"SOURCE_FAILED","unit"));assertEquals(503,assertThrows(DomainException.class,this::activate).status);assertTrue(inserts.isEmpty());}
 @Test void revokeDoesNotRequireOldSourceOrPolicyToStillExist(){state=MonitoringObjectBinding.activate(state,1,actor,policy,policy.reference(),hash,metadata(asset),at.plusSeconds(1),null);policy=new Policy(null,null,null,null,null);var result=repository.change(access,id,Action.REVOKE,2,null,null,null,null,"operator revoke");assertEquals(3,result.acceptedVersion());verifyNoInteractions(source);assertEquals(1,inserts.size());assertTrue(inserts.get(0).contains("\"active\":null"));}
 void published(){state=MonitoringObjectBinding.activate(state,1,actor,policy,policy.reference(),hash,metadata(asset),at.plusSeconds(1),null);when(source.readEvent(key,"44")).thenReturn(new FetchedEvent("44",RawMonitoringEvent.Phase.RECOVERED,at,"23",new byte[]{1,2,3}));}
 @Test void captureUsesOwnedConnectionAndNeverProcessesRawRecovery()throws Exception{published();when(c.createStatement()).thenReturn(mock(Statement.class));try(var raw=mockStatic(RawMonitoringEventSchemaVerifier.class);var hold=mockStatic(MonitoringQuarantineSchemaVerifier.class);var storage=mockStatic(PostgresMonitoringEventStorageCoordinator.class)){
  UUID record=UUID.randomUUID();storage.when(()->PostgresMonitoringEventStorageCoordinator.storeInTransaction(eq(c),any())).thenAnswer(call->{var e=(RawMonitoringEvent)call.getArgument(1);assertEquals(RawMonitoringEvent.Phase.RECOVERED,e.key().phase());assertEquals(tenant,e.binding().tenantId());assertEquals(asset,e.binding().assetId());assertEquals(at,e.occurredAt());return new PostgresMonitoringEventStorageCoordinator.RawResult(new PostgresRawMonitoringEventRepository.Receipt(PostgresRawMonitoringEventRepository.Outcome.STORED,record,databaseTime));});
  var receipt=repository.capture(access,id,2,"44",hash);assertEquals("RAW_STORED",receipt.outcome());assertEquals(record,receipt.recordId());assertEquals(2,receipt.mappingVersion());assertTrue(inserts.isEmpty(),"Capture does not revise mapping or business event");verify(source).verify(key);verify(source).readEvent(key,"44");verify(c,never()).commit();verify(c,never()).close();
 }}
 @Test void captureRechecksExpiryAfterBoundReadBeforeAnyStore()throws Exception{published();when(source.readEvent(key,"44")).thenAnswer(call->{databaseTime=at.plusSeconds(100);return new FetchedEvent("44",RawMonitoringEvent.Phase.PROBLEM,at,"23",new byte[]{1});});try(var raw=mockStatic(RawMonitoringEventSchemaVerifier.class);var hold=mockStatic(MonitoringQuarantineSchemaVerifier.class);var storage=mockStatic(PostgresMonitoringEventStorageCoordinator.class)){assertEquals(409,assertThrows(DomainException.class,()->repository.capture(access,id,2,"44",hash)).status);storage.verifyNoInteractions();}}
 @Test void captureRejectsOtherHostBeforeStorage()throws Exception{published();when(source.readEvent(key,"44")).thenReturn(new FetchedEvent("44",RawMonitoringEvent.Phase.PROBLEM,at,"24",new byte[]{1}));try(var raw=mockStatic(RawMonitoringEventSchemaVerifier.class);var hold=mockStatic(MonitoringQuarantineSchemaVerifier.class);var storage=mockStatic(PostgresMonitoringEventStorageCoordinator.class)){assertEquals(404,assertThrows(DomainException.class,()->repository.capture(access,id,2,"44",hash)).status);storage.verifyNoInteractions();}}
 @Test void captureDoesNotUseUnpublishedDraftAsAuthority(){assertEquals(409,assertThrows(DomainException.class,()->repository.capture(access,id,1,"44",hash)).status);verify(source,never()).verify(any());verify(source,never()).readEvent(any(),any());}


    MappedSourcePage.Window pageWindow() {
        return new MappedSourcePage.Window("44", "99", at.getEpochSecond(), at.getEpochSecond(), 1);
    }


    void assemblePage() throws Exception {
        published();
        repository = new PostgresMonitoringObjectBindingRepository(
            platform,
            json,
            t -> policy,
            source,
            mock(PostgresMonitoringEventCenterRepository.class)
        );
        when(c.createStatement()).thenReturn(mock(Statement.class));
        when(source.readPage(key, pageWindow())).thenReturn(
            new MappedSourcePage.Fetched(
                pageWindow(),
                List.of(new FetchedEvent("44", RawMonitoringEvent.Phase.RECOVERED, at, "23", new byte[] { 1 })),
                "45"
            )
        );
    }


    @Test
    void pageExpiryDuringSourceReadRejectsBeforeAnyStore() throws Exception {
        assemblePage();
        when(source.readPage(key, pageWindow())).thenAnswer(call -> {
            databaseTime = at.plusSeconds(100);
            return new MappedSourcePage.Fetched(pageWindow(), List.of(), null);
        });
        try (var storage = mockStatic(PostgresMonitoringEventStorageCoordinator.class)) {
            assertEquals(
                409,
                assertThrows(DomainException.class, () ->
                    repository.intakePage(access, id, 2, hash, pageWindow())
                ).status
            );
            storage.verifyNoInteractions();
        }
    }


    @Test
    void pageProofExpiryDuringEffectsPreventsACommittedContinuationReceipt() throws Exception {
        assemblePage();
        try (
            var raw = mockStatic(RawMonitoringEventSchemaVerifier.class);
            var hold = mockStatic(MonitoringQuarantineSchemaVerifier.class);
            var storage = mockStatic(PostgresMonitoringEventStorageCoordinator.class)
        ) {
            storage
                .when(() -> PostgresMonitoringEventStorageCoordinator.storeInTransaction(eq(c), any()))
                .thenAnswer(call -> {
                    databaseTime = at.plusSeconds(100);
                    return new PostgresMonitoringEventStorageCoordinator.RawResult(
                        new PostgresRawMonitoringEventRepository.Receipt(
                            PostgresRawMonitoringEventRepository.Outcome.STORED,
                            UUID.randomUUID(),
                            at.plusSeconds(3)
                        )
                    );
                });
            assertEquals(
                409,
                assertThrows(DomainException.class, () ->
                    repository.intakePage(access, id, 2, hash, pageWindow())
                ).status
            );
            verify(platform, never()).audit(
                any(),
                any(),
                eq("monitoring_mapping.page_intake"),
                any(),
                any(),
                any(),
                any(),
                any()
            );
            verify(c, never()).commit();
        }
    }


    @Test
    void sourceDefinitionChangeDuringPageReadRejectsBeforeStorage() throws Exception {
        assemblePage();
        when(source.readPage(key, pageWindow())).thenAnswer(call -> {
            when(source.definitionSha256(pool)).thenReturn("b".repeat(64));
            return new MappedSourcePage.Fetched(pageWindow(), List.of(), null);
        });
        try (var storage = mockStatic(PostgresMonitoringEventStorageCoordinator.class)) {
            assertEquals(
                409,
                assertThrows(DomainException.class, () ->
                    repository.intakePage(access, id, 2, hash, pageWindow())
                ).status
            );
            storage.verifyNoInteractions();
        }
    }
}
