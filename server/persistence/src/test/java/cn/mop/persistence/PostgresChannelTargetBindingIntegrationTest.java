package cn.mop.persistence;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Disposable PostgreSQL only. Synthetic bindings are inserted locally; no provider calls. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_CHANNEL_TARGET_PG_TESTS",matches="true")
class PostgresChannelTargetBindingIntegrationTest {
 static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002",SENDER="target-lookup@example.test",CORP="wwsynthetic12345678";
 static HikariDataSource pool;static PostgresPlatformRepository platform;static PostgresEmailRepository email;static PostgresWeComRepository wecom;static String actor,viewer,target,unbound,foreign;static long version;
 @BeforeAll static void setup()throws Exception{
  var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));cfg.setUsername("mop_test_runtime");cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));pool=new HikariDataSource(cfg);platform=new PostgresPlatformRepository(pool,new ObjectMapper().findAndRegisterModules());email=new PostgresEmailRepository(pool,platform);wecom=new PostgresWeComRepository(pool,platform);
  actor=platform.users().stream().map(FixtureUser::id).filter(id->platform.membership(id,TENANT).map(Membership::role).orElse("").equals("padmin")).findFirst().orElseThrow();viewer=platform.users().stream().map(FixtureUser::id).filter(id->platform.membership(id,TENANT).map(Membership::role).orElse("").equals("viewer")).findFirst().orElseThrow();version=platform.membership(actor,TENANT).orElseThrow().permissionVersion();
  try(var c=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("MOP_MIGRATION_USERNAME"),System.getenv("MOP_MIGRATION_PASSWORD"));var s=c.createStatement()){
   s.execute("INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) SELECT md5('synthetic-target-lookup-'||i)::uuid,'synthetic-target-lookup',i::text,'Synthetic target '||i,'viewer' FROM generate_series(1,203) i");
   s.execute("INSERT INTO public.mop_membership(tenant_id,user_id,role_code) SELECT CASE WHEN subject='203' THEN '"+OTHER+"'::uuid ELSE '"+TENANT+"'::uuid END,id,'viewer' FROM public.mop_user WHERE identity_source='synthetic-target-lookup'");
   s.execute("INSERT INTO public.mop_email_binding(id,tenant_id,user_id,sender,email,updated_by,created_at) SELECT gen_random_uuid(),'"+TENANT+"'::uuid,id,'"+SENDER+"','synthetic-'||subject||'@example.test','"+actor+"'::uuid,'2026-10-21T00:00:00Z'::timestamptz+subject::int*interval '1 second' FROM public.mop_user WHERE identity_source='synthetic-target-lookup' AND subject::int<=201");
   s.execute("INSERT INTO public.mop_wecom_binding(id,tenant_id,user_id,corp_id,wecom_user_id,verified_at,updated_by,created_at) SELECT gen_random_uuid(),'"+TENANT+"'::uuid,id,'"+CORP+"','synthetic_'||subject,'2026-10-21T00:00:00Z'::timestamptz+subject::int*interval '1 second','"+actor+"'::uuid,'2026-10-21T00:00:00Z'::timestamptz+subject::int*interval '1 second' FROM public.mop_user WHERE identity_source='synthetic-target-lookup' AND subject::int<=201");
   try(var r=s.executeQuery("SELECT subject,id FROM public.mop_user WHERE identity_source='synthetic-target-lookup' AND subject IN ('201','202','203')")){while(r.next()){switch(r.getString(1)){case "201"->target=r.getString(2);case "202"->unbound=r.getString(2);case "203"->foreign=r.getString(2);}}}
  }
 }
 @AfterAll static void close(){if(pool!=null)pool.close();}
 static PostgresEmailRepository.Access ea(String user,long pv){return new PostgresEmailRepository.Access(TENANT,user,pv,"synthetic-target-read");}
 static PostgresWeComRepository.Access wa(String user,long pv){return new PostgresWeComRepository.Access(TENANT,user,pv,"synthetic-target-read");}
 @Test void emailReadsBeyondTwoHundredWithoutListInference(){var a=ea(actor,version);assertEquals(200,email.bindings(a,SENDER).size());assertTrue(email.bindings(a,SENDER).stream().noneMatch(b->target.equals(b.get("user_id"))));var b=email.targetBinding(a,target,SENDER);assertEquals(target,b.get("user_id"));assertEquals(1L,b.get("version"));assertEquals("synthetic-201@example.test",b.get("email"));assertEquals(b,email.targetBinding(a,target,SENDER));}
 @Test void wecomReadsBeyondTwoHundredWithoutListInference(){var a=wa(actor,version);assertEquals(200,wecom.bindings(a,CORP).size());assertTrue(wecom.bindings(a,CORP).stream().noneMatch(b->target.equals(b.get("user_id"))));var b=wecom.targetBinding(a,target,CORP);assertEquals(target,b.get("user_id"));assertEquals(1L,b.get("version"));assertEquals("synthetic_201",b.get("wecom_user_id"));}
 @Test void knownMemberWithoutBindingReturnsExplicitNull(){assertNull(email.targetBinding(ea(actor,version),unbound,SENDER));assertNull(wecom.targetBinding(wa(actor,version),unbound,CORP));}
 @Test void senderAndCorpScopeDoNotLeakAnotherConfiguration(){assertNull(email.targetBinding(ea(actor,version),target,"other@example.test"));assertNull(wecom.targetBinding(wa(actor,version),target,"other-synthetic-corp"));}
 @Test void otherTenantTargetReturnsNotFoundInsteadOfFalseUnbound(){assertEquals(404,assertThrows(DomainException.class,()->email.targetBinding(ea(actor,version),foreign,SENDER)).status);assertEquals(404,assertThrows(DomainException.class,()->wecom.targetBinding(wa(actor,version),foreign,CORP)).status);}
 @Test void unknownTargetReturnsNotFound(){String missing="99000000-0000-4000-8000-000000000099";assertEquals(404,assertThrows(DomainException.class,()->email.targetBinding(ea(actor,version),missing,SENDER)).status);assertEquals(404,assertThrows(DomainException.class,()->wecom.targetBinding(wa(actor,version),missing,CORP)).status);}
 @Test void viewerCannotReadAdministrativeTargetBinding(){long pv=platform.membership(viewer,TENANT).orElseThrow().permissionVersion();assertEquals(403,assertThrows(DomainException.class,()->email.targetBinding(ea(viewer,pv),target,SENDER)).status);assertEquals(403,assertThrows(DomainException.class,()->wecom.targetBinding(wa(viewer,pv),target,CORP)).status);}
 @Test void stalePermissionVersionCannotRead(){assertEquals(401,assertThrows(DomainException.class,()->email.targetBinding(ea(actor,version+1),target,SENDER)).status);assertEquals(401,assertThrows(DomainException.class,()->wecom.targetBinding(wa(actor,version+1),target,CORP)).status);}
 @Test void malformedTargetIsRejected(){assertEquals(400,assertThrows(DomainException.class,()->email.targetBinding(ea(actor,version),"not-a-uuid",SENDER)).status);assertEquals(400,assertThrows(DomainException.class,()->wecom.targetBinding(wa(actor,version),"not-a-uuid",CORP)).status);}
}
