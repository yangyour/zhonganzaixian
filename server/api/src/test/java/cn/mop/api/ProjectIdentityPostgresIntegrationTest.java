package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Actual designated PG and Argon2; synthetic accounts retained disabled with their audit trail. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class ProjectIdentityPostgresIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static HikariDataSource pool;static PostgresPlatformRepository platform;static ProjectIdentityRepository repo;static String admin,viewerRole;static ObjectMapper mapper;
    final List<String> created=new ArrayList<>();
    @BeforeAll static void connect()throws Exception{
        assertTrue(System.getenv("SPRING_DATASOURCE_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));
        var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(6);pool=new HikariDataSource(cfg);
        mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        platform=new PostgresPlatformRepository(pool,mapper);repo=new ProjectIdentityRepository(pool,platform,new ProjectPasswordCodec(),mapper);
        admin=platform.users().stream().filter(u->platform.membership(u.id(),TENANT).filter(m->m.capabilities().containsAll(List.of("users:write","roles:write"))).isPresent()).findFirst().orElseThrow().id();
        viewerRole=(String)repo.roles(access()).stream().filter(r->"viewer".equals(r.get("base_role"))&&Boolean.TRUE.equals(r.get("builtin"))).findFirst().orElseThrow().get("id");
        try(var c=pool.getConnection()){ProjectIdentitySchemaVerifier.verify(c);}
        assertTrue(repo.ready(),"Explicit acceptance bootstrap must precede real identity tests");
    }
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static ProjectIdentityRepository.Access access(){return access(admin,TENANT);}
    static ProjectIdentityRepository.Access access(String actor,String tenant){return new ProjectIdentityRepository.Access(actor,tenant,platform.membership(actor,tenant).orElseThrow().permissionVersion(),UUID.randomUUID().toString());}
    static Map<String,Object> write(String route,Supplier<Object> action){return write(access(),route,UUID.randomUUID().toString(),"a".repeat(64),action);}
    @SuppressWarnings("unchecked") static Map<String,Object> write(ProjectIdentityRepository.Access a,String route,String key,String hash,Supplier<Object> action){return mapper.convertValue(repo.command(a,route,key,hash,null,()->{},action),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});}
    Map<String,Object> user(String role){String suffix=UUID.randomUUID().toString();var result=write("POST /integration/identity/users",()->repo.createUser(access(),"identity-"+suffix,"SYNTHETIC IDENTITY "+suffix,"x",role,true));created.add((String)result.get("id"));return result;}
    static long version(Map<String,Object> row){return ((Number)row.get("version")).longValue();}
    @AfterEach void disable(){for(String id:created){var current=repo.user(access(),id);if(Boolean.TRUE.equals(current.get("active")))write("PATCH /integration/identity/users/"+id,()->repo.updateUser(access(),id,version(current),(String)current.get("display_name"),false));}}
    @Test void nullPoliciesKeepDeclinedDefaultsUnsetAndRealPasswordsWorkCaseInsensitively(){
        for(var entry:repo.policy().entrySet())if(!entry.getKey().equals("version"))assertNull(entry.getValue());
        var u=user(viewerRole);String id=(String)u.get("id"),login=(String)u.get("username");assertFalse((Boolean)u.get("must_change_password"));
        for(int i=0;i<6;i++)assertTrue(repo.authenticate(login,"wrong",UUID.randomUUID().toString()).isEmpty());
        assertNull(repo.user(access(),id).get("locked_until"));var result=repo.authenticate(login.toUpperCase(Locale.ROOT),"x",UUID.randomUUID().toString()).orElseThrow();assertEquals(id,result.userId());assertFalse(result.passwordChangeRequired());assertNull(result.idleMinutes());
        assertFalse(u.containsKey("password_hash"));assertTrue(repo.authenticate("unmapped-"+UUID.randomUUID(),"x",UUID.randomUUID().toString()).isEmpty());
    }
    @Test void replayAndConflictPreserveOneAccountAndOneAudit()throws Exception{
        String login="identity-"+UUID.randomUUID(),key=UUID.randomUUID().toString();var a=access();Supplier<Object> action=()->repo.createUser(a,login,"SYNTHETIC REPLAY","x",viewerRole,true);
        var first=write(a,"POST /integration/identity/replay",key,"b".repeat(64),action);created.add((String)first.get("id"));var second=write(a,"POST /integration/identity/replay",key,"b".repeat(64),action);assertEquals(mapper.readTree(mapper.writeValueAsBytes(first)),mapper.readTree(mapper.writeValueAsBytes(second)));
        assertEquals(409,assertThrows(DomainException.class,()->write(a,"POST /integration/identity/replay",key,"c".repeat(64),action)).status);
        try(var c=pool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM public.mop_audit WHERE payload->>'action'='identity.user.create' AND payload->>'resource_id'=?")){s.setString(1,(String)first.get("id"));try(var r=s.executeQuery()){r.next();assertEquals(1,r.getLong(1));}}
    }
    @Test void passwordResetRevokesSecurityRevisionAndOldPasswordWithoutInventingForcedChange(){
        var u=user(viewerRole);String id=(String)u.get("id"),login=(String)u.get("username");long before=repo.authenticate(login,"x","reset-before").orElseThrow().securityVersion();
        write("POST /integration/identity/"+id+"/password",()->repo.resetPassword(access(),id,version(u),"replacement"));assertTrue(repo.sessionState(id).orElseThrow().securityVersion()>before);assertTrue(repo.authenticate(login,"x","old-password").isEmpty());assertFalse(repo.authenticate(login,"replacement","new-password").orElseThrow().passwordChangeRequired());
        assertEquals(409,assertThrows(DomainException.class,()->write("POST /integration/stale-reset/"+id,()->repo.resetPassword(access(),id,version(u),"other"))).status);
    }
    @Test void customRoleLimitsActuallyDenyReadsAndEpochChangesInvalidateOldAccess(){
        var role=write("POST /integration/identity/role",()->repo.createRole(access(),new ProjectRole("Synthetic "+UUID.randomUUID(),null,"viewer",List.of("assets:read"),true)));
        var u=user((String)role.get("id"));String id=(String)u.get("id");var before=platform.membership(id,TENANT).orElseThrow();assertEquals(List.of("assets:read"),before.capabilities());
        assertEquals(403,assertThrows(DomainException.class,()->repo.userPage(access(id,TENANT),"",true,1)).status);
        write("PATCH /integration/identity/role/"+role.get("id"),()->repo.updateRole(access(),(String)role.get("id"),version(role),new ProjectRole((String)role.get("name"),null,"viewer",List.of(),true)));
        var after=platform.membership(id,TENANT).orElseThrow();assertTrue(after.permissionVersion()>before.permissionVersion());assertEquals(List.of(),after.capabilities());
        assertEquals(401,assertThrows(DomainException.class,()->repo.command(new ProjectIdentityRepository.Access(id,TENANT,before.permissionVersion(),"old-epoch"),"POST /integration/old-epoch",UUID.randomUUID().toString(),"d".repeat(64),null,()->{},()->Map.of())).status);
    }
    @Test void crossTenantTargetsAre404AndBuiltinsCannotBeChanged(){
        String foreign=platform.users().stream().filter(u->platform.membership(u.id(),OTHER).isPresent()&&platform.membership(u.id(),TENANT).isEmpty()).findFirst().orElseThrow().id();
        assertEquals(404,assertThrows(DomainException.class,()->repo.user(access(),foreign)).status);
        var builtin=repo.roles(access()).stream().filter(r->r.get("id").equals(viewerRole)).findFirst().orElseThrow();
        assertEquals("BUILTIN_ROLE_PROTECTED",assertThrows(DomainException.class,()->write("PATCH /integration/builtin",()->repo.updateRole(access(),viewerRole,version(builtin),new ProjectRole("changed",null,"viewer",List.of(),true)))).code);
    }
    @Test void delegatedAccountManagerCannotEscalateOrResetHigherAccounts(){
        var role=write("POST /integration/delegated-role",()->repo.createRole(access(),new ProjectRole("Synthetic delegated "+UUID.randomUUID(),null,"padmin",List.of("users:read","users:write","roles:read"),true)));
        var u=user((String)role.get("id"));String actor=(String)u.get("id");var a=access(actor,TENANT);var padmin=repo.roles(access()).stream().filter(r->Boolean.TRUE.equals(r.get("builtin"))&&"padmin".equals(r.get("base_role"))).findFirst().orElseThrow();
        assertEquals("ROLE_GRANT_EXCEEDS_AUTHORITY",assertThrows(DomainException.class,()->write(a,"POST /integration/escalate",UUID.randomUUID().toString(),"e".repeat(64),()->repo.setMembership(a,actor,TENANT,a.permissionVersion(),(String)padmin.get("id"),true))).code);
        assertEquals(403,assertThrows(DomainException.class,()->write(a,"POST /integration/escalate-password",UUID.randomUUID().toString(),"e".repeat(64),()->repo.resetPassword(a,admin,version(repo.user(access(),admin)),"x"))).status);
    }
    @Test void explicitExternalBindingRevocationAndSessionRevocationUseRealData(){
        var u=user(viewerRole);String id=(String)u.get("id"),issuer="https://identity.example.test",subject="synthetic-"+UUID.randomUUID();assertTrue(repo.externalIdentity(issuer,subject).isEmpty());
        var binding=write("POST /integration/bind/"+id,()->repo.bindExternal(access(),id,issuer,subject));assertEquals(id,repo.externalIdentity(issuer,subject).orElseThrow().userId());long before=repo.sessionState(id).orElseThrow().securityVersion();
        write("POST /integration/unbind/"+id,()->repo.disableBinding(access(),id,(String)binding.get("id"),version(binding)));assertTrue(repo.externalIdentity(issuer,subject).isEmpty());assertTrue(repo.sessionState(id).orElseThrow().securityVersion()>before);
        var fresh=repo.user(access(),id);write("POST /integration/revoke/"+id,()->repo.revokeSessions(access(),id,version(fresh)));assertTrue(repo.sessionState(id).orElseThrow().securityVersion()>version(fresh));
    }
    @Test void ownPasswordRequiresCurrentSecretAndDisableStopsAuthentication(){
        var u=user(viewerRole);String id=(String)u.get("id");var a=access(id,TENANT);
        assertEquals("CURRENT_PASSWORD_INVALID",assertThrows(DomainException.class,()->write(a,"POST /integration/self-password",UUID.randomUUID().toString(),"e".repeat(64),()->repo.changePassword(a,version(u),"wrong","changed"))).code);
        write(a,"POST /integration/self-password",UUID.randomUUID().toString(),"f".repeat(64),()->repo.changePassword(a,version(u),"x","changed"));assertTrue(repo.authenticate((String)u.get("username"),"changed","own-change").isPresent());
        var fresh=repo.user(access(),id);write("PATCH /integration/disable/"+id,()->repo.updateUser(access(),id,version(fresh),(String)fresh.get("display_name"),false));assertTrue(repo.authenticate((String)u.get("username"),"changed","disabled").isEmpty());assertTrue(repo.sessionState(id).isEmpty());
    }
    @Test void tenantOnlyRoleAdministratorCannotChangeGlobalPolicy(){
        var role=write("POST /integration/policy-admin-role",()->repo.createRole(access(),new ProjectRole("Synthetic policy scope "+UUID.randomUUID(),null,"padmin",List.of("users:read","users:write","roles:read","roles:write"),true)));
        var u=user((String)role.get("id"));var a=access((String)u.get("id"),TENANT);var before=repo.policy();
        assertEquals("POLICY_SCOPE_INCOMPLETE",assertThrows(DomainException.class,()->write(a,"PUT /integration/global-policy",UUID.randomUUID().toString(),"a".repeat(64),()->repo.updatePolicy(a,version(before),before))).code);
        assertEquals(before,repo.policy());
    }
    @Test void caseInsensitiveDuplicateLoginDoesNotCreateAnotherAccount(){
        var u=user(viewerRole);assertEquals("NAME_UNAVAILABLE",assertThrows(DomainException.class,()->write("POST /integration/duplicate-login",()->repo.createUser(access(),((String)u.get("username")).toUpperCase(Locale.ROOT),"SYNTHETIC DUPLICATE","x",viewerRole,true))).code);
        assertEquals(1,repo.userPage(access(),(String)u.get("username"),true,1).get("items") instanceof List<?> values?values.size():0);
    }
}
