package cn.mop.persistence;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Project-local identities and RBAC. Every management mutation shares audit and durable replay. */
public final class ProjectIdentityRepository {
    public record Access(String actor, String tenant, long permissionVersion, String trace) {}
    public record Authentication(String userId, long securityVersion, boolean passwordChangeRequired, Integer idleMinutes) {}
    public record SessionState(long securityVersion, boolean passwordChangeRequired) {}
    private record Credential(String user, String hash, long version, boolean mustChange, int failures, Instant lockedUntil) {
        @Override public String toString() { return "Credential[REDACTED]"; }
    }
    @FunctionalInterface private interface Sql<T> { T run(Connection connection) throws SQLException; }
    private final DataSource dataSource;
    private final PostgresPlatformRepository platform;
    private final PasswordCodec passwords;
    private final ObjectMapper mapper;
    private final String dummyPasswordHash;
    public ProjectIdentityRepository(DataSource source, PostgresPlatformRepository platform, PasswordCodec passwords, ObjectMapper mapper) {
        this.dataSource=source; this.platform=platform; this.passwords=passwords; this.mapper=mapper;
        this.dummyPasswordHash=passwords.encode("unusable-account-timing-placeholder");
    }
    private static DomainException unavailable() { return new DomainException(503,"IDENTITY_UNAVAILABLE","账号与权限数据暂不可用"); }
    private static DomainException missing() { return new DomainException(404,"NOT_FOUND","对象不存在或不在授权范围"); }
    private static DomainException conflict() { return new DomainException(409,"VERSION_CONFLICT","记录已变化，请重新读取后确认"); }
    private static DomainException denied() { return new DomainException(403,"ACTION_FORBIDDEN","当前身份无此管理权限"); }
    private static DomainException invalid(String message) { return new DomainException(400,"VALIDATION_ERROR",message); }
    private <T> T tx(Sql<T> work) { return platform.identityTransaction(c -> { try { return work.run(c); } catch(SQLException failure) { throw sqlFailure(failure); } }); }
    private <T> T read(Sql<T> work) {
        try(Connection c=dataSource.getConnection()) { c.setReadOnly(true); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
            try { T value=work.run(c); c.commit(); return value; } catch(SQLException|RuntimeException error) { c.rollback(); throw error; }
        } catch(SQLException failure) { throw sqlFailure(failure); }
    }
    private static DomainException sqlFailure(SQLException failure) {
        if("23505".equals(failure.getSQLState())) return new DomainException(409,"NAME_UNAVAILABLE","账号名称或角色名称不可用");
        System.getLogger(ProjectIdentityRepository.class.getName()).log(System.Logger.Level.WARNING,"Identity SQL failure state={0}",failure.getSQLState());
        return unavailable();
    }
    private static PreparedStatement statement(Connection c,String sql,Object...values)throws SQLException {
        var s=c.prepareStatement(sql); s.setQueryTimeout(10);
        for(int i=0;i<values.length;i++) {
            Object value=values[i];
            if(value instanceof List<?> list) s.setArray(i+1,c.createArrayOf("text",list.toArray(String[]::new)));
            else s.setObject(i+1,value);
        }
        return s;
    }
    private static Map<String,Object> row(ResultSet r)throws SQLException {
        var result=new LinkedHashMap<String,Object>(); var meta=r.getMetaData();
        for(int i=1;i<=meta.getColumnCount();i++) {
            Object value=r.getObject(i);
            if(value instanceof java.sql.Array a) value=List.of((String[])a.getArray());
            else if(value instanceof UUID) value=value.toString();
            else if(value instanceof Timestamp t) value=t.toInstant().toString();
            result.put(meta.getColumnLabel(i),value);
        }
        return result;
    }
    private static List<Map<String,Object>> rows(Connection c,String sql,Object...args)throws SQLException {
        try(var s=statement(c,sql,args);var r=s.executeQuery()) { var out=new ArrayList<Map<String,Object>>();while(r.next())out.add(row(r));return out; }
    }
    private static Map<String,Object> one(Connection c,String sql,Object...args)throws SQLException { var list=rows(c,sql,args);if(list.size()!=1)throw missing();return list.get(0); }
    private static void update(Connection c,String sql,Object...args)throws SQLException { try(var s=statement(c,sql,args)){if(s.executeUpdate()!=1)throw conflict();} }
    private static String id(String value) { try { if(value==null||!UUID.fromString(value).toString().equals(value))throw missing();return value; } catch(IllegalArgumentException e){throw missing();} }
    public static String username(String value) { if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{2,63}"))throw invalid("账号须为3至64个字母、数字或_.@-字符");return value; }
    public static String displayName(String value) { if(value==null||value.isBlank()||value.codePointCount(0,value.length())>100||value.codePoints().anyMatch(Character::isISOControl))throw invalid("显示名称为空、过长或含控制字符");return value; }
    public static void passwordInput(String value) { if(value==null||value.isEmpty()||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024||value.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff))throw invalid("密码不能为空，且须在技术输入上限内"); }
    private static long version(Map<String,Object> value) { return ((Number)value.get("version")).longValue(); }
    private void audit(Access a,String action,String kind,String target,String reason) { platform.audit(a.actor(),a.tenant(),action,kind,target,"SUCCEEDED",reason,a.trace()); }
    private Membership authorize(Access a,String permission) {
        var m=platform.membership(a.actor(),a.tenant()).orElseThrow(ProjectIdentityRepository::denied);
        if(!m.active()||m.permissionVersion()!=a.permissionVersion()||permission!=null&&!m.capabilities().contains(permission))throw denied();return m;
    }
    private static void readAccess(Connection c,Access a,String permission)throws SQLException { PostgresAuthorization.require(c,a.actor(),a.tenant(),a.permissionVersion(),permission); }
    public Object command(Access a,String route,String key,String fingerprint,String permission,Runnable resourceCheck,Supplier<Object> action) {
        return tx(c->{
            // Serialize identity administration before taking membership locks, including cross-tenant changes.
            try(var s=statement(c,"SELECT pg_advisory_xact_lock(hashtextextended('mop.project.identity.administration',0))")){s.execute();}
            return platform.idempotentAuthorized(a.actor(),a.tenant(),route,key,fingerprint,a.permissionVersion(),()->{authorize(a,permission);resourceCheck.run();},action);
        });
    }
    public boolean ready() {
        try { return read(c -> { ProjectIdentitySchemaVerifier.verify(c);try(var s=statement(c,"SELECT EXISTS(SELECT 1 FROM public.mop_local_account account JOIN public.mop_user u ON u.id=account.user_id JOIN public.mop_membership m ON m.user_id=u.id WHERE u.active AND m.active AND public.mop_effective_capabilities(u.id,m.tenant_id) @> ARRAY['users:read','users:write','roles:read','roles:write']::text[])" );var r=s.executeQuery()){return r.next()&&r.getBoolean(1);} }); }
        catch(RuntimeException unavailable){return false;}
    }
    /** Explicit operator bootstrap only; no HTTP route calls this operation. Existing hashes are never overwritten. */
    public Map<String,Object> bootstrapExistingTestAccounts(com.fasterxml.jackson.databind.JsonNode input){
        if(input==null||!input.isArray()||input.size()!=6)throw invalid("Expected six existing acceptance identities");
        return tx(c->{ProjectIdentitySchemaVerifier.verify(c);int inserted=0,verified=0;var ids=new HashSet<String>();
            for(var item:input){String user=id(item.path("platform_user_id").asText()),login=username(item.path("username").asText()),password=item.path("password").asText();passwordInput(password);if(!ids.add(user))throw invalid("Duplicate identity");
                var existing=one(c,"SELECT identity_source,active FROM public.mop_user WHERE id=?::uuid FOR UPDATE",user);if(!"test_oidc".equals(existing.get("identity_source"))||!Boolean.TRUE.equals(existing.get("active")))throw invalid("Unexpected acceptance identity");
                var accounts=rows(c,"SELECT username,password_hash FROM public.mop_local_account WHERE user_id=?::uuid",user);
                if(!accounts.isEmpty()){if(!login.equals(accounts.get(0).get("username"))||!passwords.matches(password,(String)accounts.get(0).get("password_hash")))throw invalid("Existing credential mismatch");verified++;}
                else{validatePassword(c,user,password,null);update(c,"INSERT INTO public.mop_local_account(user_id,username,password_hash) VALUES(?::uuid,?,?)",user,login,passwords.encode(password));platform.audit(null,null,"identity.local_account.bootstrap","user",user,"SUCCEEDED","EXPLICIT_ACCEPTANCE_ACCOUNT_BOOTSTRAP",UUID.randomUUID().toString());inserted++;}
            }return result("inserted",inserted,"verified_existing",verified,"passwords_output",false);
        });
    }
    public Map<String,Object> policy() { return read(this::policy); }
    private Map<String,Object> policy(Connection c)throws SQLException { return one(c,"SELECT version,minimum_password_length,password_history_count,failed_attempt_limit,lock_minutes,idle_minutes,force_change_on_reset FROM public.mop_identity_policy WHERE singleton"); }
    private Credential credential(Connection c,String user,boolean lock)throws SQLException {
        try(var s=statement(c,"SELECT user_id,password_hash,version,must_change_password,failed_attempts,locked_until FROM public.mop_local_account WHERE user_id=?::uuid"+(lock?" FOR UPDATE":""),id(user));var r=s.executeQuery()) {
            if(!r.next())throw missing();var until=r.getTimestamp(6);return new Credential(r.getString(1),r.getString(2),r.getLong(3),r.getBoolean(4),r.getInt(5),until==null?null:until.toInstant());
        }
    }
    public Optional<Authentication> authenticate(String login,String password,String trace) {
        passwordInput(password);
        return tx(c->{
            var policy=policy(c); String user=null; boolean active=false;
            try(var s=statement(c,"SELECT u.id,u.active FROM public.mop_user u JOIN public.mop_local_account a ON a.user_id=u.id WHERE a.username_key=lower(? COLLATE \"C\") FOR SHARE OF u",login);var r=s.executeQuery()){if(r.next()){user=r.getString(1);active=r.getBoolean(2);}}
            Credential account=user==null?null:credential(c,user,true);
            boolean verified=passwords.matches(password,account==null?dummyPasswordHash:account.hash());
            Instant now;try(var s=statement(c,"SELECT clock_timestamp()");var r=s.executeQuery()){r.next();now=r.getTimestamp(1).toInstant();}
            boolean locked=account!=null&&account.lockedUntil()!=null&&account.lockedUntil().isAfter(now);
            if(account==null||!active||!verified||locked) {
                if(account!=null&&!locked&&active) {
                    int failures=Math.min(account.failures()+1,1000000);Integer limit=(Integer)policy.get("failed_attempt_limit"),minutes=(Integer)policy.get("lock_minutes");
                    Timestamp until=limit!=null&&failures>=limit?Timestamp.from(now.plusSeconds(minutes*60L)):null;
                    update(c,"UPDATE public.mop_local_account SET failed_attempts=?,locked_until=?,updated_at=statement_timestamp() WHERE user_id=?::uuid",failures,until,user);
                }
                platform.audit(null,null,"local.login","account",null,"DENIED","INVALID_LOCAL_CREDENTIALS",trace);
                return Optional.empty();
            }
            var security=one(c,"SELECT revision FROM public.mop_identity_security WHERE user_id=?::uuid",user);
            update(c,"UPDATE public.mop_local_account SET failed_attempts=0,locked_until=NULL,last_login_at=statement_timestamp() WHERE user_id=?::uuid",user);
            platform.audit(user,null,"local.login","account",user,"SUCCEEDED","LOCAL_PASSWORD_VERIFIED",trace);
            return Optional.of(new Authentication(user,((Number)security.get("revision")).longValue(),account.mustChange(),(Integer)policy.get("idle_minutes")));
        });
    }
    public Optional<SessionState> sessionState(String user) {
        return read(c->{var values=rows(c,"SELECT s.revision,COALESCE(a.must_change_password,false) required FROM public.mop_identity_security s JOIN public.mop_user u ON u.id=s.user_id LEFT JOIN public.mop_local_account a ON a.user_id=u.id WHERE s.user_id=?::uuid AND u.active",id(user));return values.isEmpty()?Optional.empty():Optional.of(new SessionState(((Number)values.get(0).get("revision")).longValue(),(Boolean)values.get(0).get("required")));});
    }
    public Optional<Authentication> externalIdentity(String issuer,String subject) {
        return read(c->{var values=rows(c,"SELECT b.user_id,s.revision FROM public.mop_external_identity_binding b JOIN public.mop_user u ON u.id=b.user_id JOIN public.mop_identity_security s ON s.user_id=u.id WHERE b.provider='oidc' AND b.issuer=? AND b.subject=? AND b.active AND u.active",issuer,subject);return values.isEmpty()?Optional.empty():Optional.of(new Authentication(values.get(0).get("user_id").toString(),((Number)values.get(0).get("revision")).longValue(),false,(Integer)policy(c).get("idle_minutes")));});
    }
    private static final String USER_SELECT="SELECT u.id,u.display_name,u.active,local.username,(local.user_id IS NOT NULL) local_login_enabled,local.must_change_password,local.locked_until,local.last_login_at,security.revision version,m.role_code base_role,m.active member_active,m.permission_version,r.id role_id,r.name role_name,public.mop_effective_capabilities(u.id,m.tenant_id) permissions FROM public.mop_user u JOIN public.mop_membership m ON m.user_id=u.id LEFT JOIN public.mop_local_account local ON local.user_id=u.id LEFT JOIN public.mop_identity_security security ON security.user_id=u.id LEFT JOIN public.mop_member_role_assignment assignment ON assignment.user_id=m.user_id AND assignment.tenant_id=m.tenant_id JOIN public.mop_project_role r ON r.tenant_id=m.tenant_id AND r.id=COALESCE(assignment.role_id,(SELECT default_role.id FROM public.mop_project_role default_role WHERE default_role.tenant_id=m.tenant_id AND default_role.base_role=m.role_code AND default_role.builtin))";
    public List<Map<String,Object>> users(Access a,String query,boolean includeInactive) {
        if(query==null||query.length()>100)throw invalid("搜索条件无效");
        return read(c->{readAccess(c,a,"users:read");return rows(c,USER_SELECT+" WHERE m.tenant_id=?::uuid AND (? OR (u.active AND m.active)) AND (position(lower(?) IN lower(u.display_name))>0 OR position(lower(?) IN COALESCE(local.username_key,''))>0) ORDER BY COALESCE(local.username_key,''),u.id LIMIT 201",a.tenant(),includeInactive,query,query);});
    }
    public Map<String,Object> userPage(Access a,String query,boolean includeInactive,int page){
        if(query==null||query.length()>100||page<1||page>10000)throw invalid("分页或搜索条件无效");
        return read(c->{readAccess(c,a,"users:read");var values=rows(c,USER_SELECT+" WHERE m.tenant_id=?::uuid AND (? OR (u.active AND m.active)) AND (position(lower(?) IN lower(u.display_name))>0 OR position(lower(?) IN COALESCE(local.username_key,''))>0) ORDER BY COALESCE(local.username_key,''),u.id LIMIT 51 OFFSET ?",a.tenant(),includeInactive,query,query,(page-1)*50);return result("items",values.stream().limit(50).toList(),"has_more",values.size()>50,"page",page,"page_size",50,"tenant_id",a.tenant());});
    }
    public Map<String,Object> user(Access a,String target) { return read(c->{readAccess(c,a,"users:read");return one(c,USER_SELECT+" WHERE m.tenant_id=?::uuid AND u.id=?::uuid",a.tenant(),id(target));}); }
    public void requireTarget(Access a,String target) { Connection c=platform.idempotentCommandConnection();try{one(c,"SELECT user_id FROM public.mop_membership WHERE tenant_id=?::uuid AND user_id=?::uuid",a.tenant(),id(target));}catch(SQLException e){throw sqlFailure(e);} }
    private void fullUserScope(Connection c,Access a,String target)throws SQLException {
        for(var scope:rows(c,"SELECT tenant_id FROM public.mop_membership WHERE user_id=?::uuid ORDER BY tenant_id",id(target))) {
            String tenant=scope.get("tenant_id").toString();var member=platform.membership(a.actor(),tenant).orElseThrow(()->new DomainException(403,"USER_SCOPE_INCOMPLETE","该账号还关联其他管理范围，当前身份不能修改其全局状态或密码"));
            if(!member.capabilities().contains("users:write"))throw new DomainException(403,"USER_SCOPE_INCOMPLETE","该账号还关联其他管理范围，当前身份不能修改其全局状态或密码");
            guardManagedRole(c,member,tenant,target);
        }
    }
    private void grantCeiling(Membership actor,Map<String,Object> role){
        if(actor.capabilities().contains("roles:write"))return;
        if(!actor.role().equals(role.get("base_role"))||!actor.capabilities().containsAll((List<?>)role.get("permissions")))throw new DomainException(403,"ROLE_GRANT_EXCEEDS_AUTHORITY","当前账号不能管理或授予高于自身范围的角色");
    }
    private void guardManagedRole(Connection c,Membership actor,String tenant,String target)throws SQLException{
        var roles=rows(c,"SELECT r.base_role,r.permissions FROM public.mop_membership m LEFT JOIN public.mop_member_role_assignment a ON a.tenant_id=m.tenant_id AND a.user_id=m.user_id JOIN public.mop_project_role r ON r.tenant_id=m.tenant_id AND r.id=COALESCE(a.role_id,(SELECT d.id FROM public.mop_project_role d WHERE d.tenant_id=m.tenant_id AND d.base_role=m.role_code AND d.builtin)) WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid",tenant,target);
        if(roles.size()!=1)throw missing();grantCeiling(actor,roles.get(0));
    }
    private void securityVersion(Connection c,String target,long expected)throws SQLException { var value=one(c,"SELECT revision version FROM public.mop_identity_security WHERE user_id=?::uuid FOR UPDATE",id(target));if(version(value)!=expected)throw conflict(); }
    private static void advanceSecurity(Connection c,String target)throws SQLException { update(c,"UPDATE public.mop_identity_security SET revision=revision+1,updated_at=statement_timestamp() WHERE user_id=?::uuid",target); }
    private void protectLastAdmin(Connection c,String target,String tenant)throws SQLException {
        var current=one(c,"SELECT public.mop_effective_capabilities(?::uuid,?::uuid) @> ARRAY['users:read','users:write','roles:read','roles:write']::text[] administrator",target,tenant);
        if(!Boolean.TRUE.equals(current.get("administrator")))return;
        var count=one(c,"SELECT count(*) total FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id JOIN public.mop_local_account local ON local.user_id=u.id WHERE m.tenant_id=?::uuid AND m.user_id<>?::uuid AND m.active AND u.active AND public.mop_effective_capabilities(m.user_id,m.tenant_id) @> ARRAY['users:read','users:write','roles:read','roles:write']::text[]",tenant,target);
        if(((Number)count.get("total")).longValue()==0)throw new DomainException(409,"LAST_ADMINISTRATOR","不能移除当前租户最后一个可用管理账号");
    }
    private void validatePassword(Connection c,String user,String password,Credential current)throws SQLException {
        passwordInput(password);var policy=policy(c);Integer minimum=(Integer)policy.get("minimum_password_length"),history=(Integer)policy.get("password_history_count");
        if(minimum!=null&&password.codePointCount(0,password.length())<minimum)throw invalid("密码长度未达到已配置要求");
        if(history!=null&&current!=null) {
            if(passwords.matches(password,current.hash()))throw invalid("不能重复使用密码历史中的口令");
            for(var entry:rows(c,"SELECT password_hash FROM public.mop_local_password_history WHERE user_id=?::uuid ORDER BY revision DESC LIMIT ?",user,Math.max(0,history-1)))if(passwords.matches(password,(String)entry.get("password_hash")))throw invalid("不能重复使用密码历史中的口令");
        }
    }
    private void rememberPassword(Connection c,Credential previous)throws SQLException {
        Integer count=(Integer)policy(c).get("password_history_count");if(count==null)return;
        try(var s=statement(c,"INSERT INTO public.mop_local_password_history(user_id,revision,password_hash) VALUES(?::uuid,?,?)",previous.user(),previous.version(),previous.hash())){s.executeUpdate();}
        try(var s=statement(c,"DELETE FROM public.mop_local_password_history WHERE user_id=?::uuid AND revision NOT IN (SELECT revision FROM public.mop_local_password_history WHERE user_id=?::uuid ORDER BY revision DESC LIMIT ?)",previous.user(),previous.user(),Math.max(0,count-1))){s.executeUpdate();}
    }
    public Map<String,Object> createUser(Access a,String login,String name,String password,String roleId,boolean enabled) {
        return tx(c->{var actor=authorize(a,"users:write");username(login);displayName(name);validatePassword(c,null,password,null);var role=role(c,a.tenant(),roleId,true);grantCeiling(actor,role);if(!Boolean.TRUE.equals(role.get("enabled")))throw invalid("所选角色已停用");String user=UUID.randomUUID().toString();
            update(c,"INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code,active) VALUES(?::uuid,'project_local',?,?,?,?)",user,user,name,role.get("base_role"),enabled);
            update(c,"INSERT INTO public.mop_local_account(user_id,username,password_hash,must_change_password) VALUES(?::uuid,?,?,?)",user,login,passwords.encode(password),Boolean.TRUE.equals(policy(c).get("force_change_on_reset")));
            update(c,"INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES(?::uuid,?::uuid,?)",a.tenant(),user,role.get("base_role"));
            update(c,"INSERT INTO public.mop_member_role_assignment(tenant_id,user_id,role_id) VALUES(?::uuid,?::uuid,?::uuid)",a.tenant(),user,roleId);
            audit(a,"identity.user.create","user",user,"PROJECT_LOCAL_ACCOUNT_CREATED");return one(c,USER_SELECT+" WHERE m.tenant_id=?::uuid AND u.id=?::uuid",a.tenant(),user);
        });
    }
    public Map<String,Object> updateUser(Access a,String target,long expected,String name,boolean enabled) {
        return tx(c->{authorize(a,"users:write");requireTarget(a,target);fullUserScope(c,a,target);if(target.equals(a.actor())&&!enabled)throw invalid("不能停用当前登录账号");securityVersion(c,target,expected);displayName(name);
            if(!enabled)for(var member:rows(c,"SELECT tenant_id FROM public.mop_membership WHERE user_id=?::uuid AND active",target))protectLastAdmin(c,target,member.get("tenant_id").toString());
            update(c,"UPDATE public.mop_user SET display_name=?,active=? WHERE id=?::uuid",name,enabled,target);advanceSecurity(c,target);
            try(var s=statement(c,"UPDATE public.mop_membership SET permission_version=permission_version+1 WHERE user_id=?::uuid",target)){s.executeUpdate();}
            audit(a,"identity.user.update","user",target,enabled?"ACCOUNT_ACTIVE_REVISION_CHANGED":"ACCOUNT_DISABLED_SESSIONS_REVOKED");return one(c,USER_SELECT+" WHERE m.tenant_id=?::uuid AND u.id=?::uuid",a.tenant(),target);
        });
    }
    public Map<String,Object> resetPassword(Access a,String target,long expected,String newPassword) {
        return tx(c->{authorize(a,"users:write");requireTarget(a,target);fullUserScope(c,a,target);if(target.equals(a.actor()))throw invalid("请使用当前账号的修改密码入口");securityVersion(c,target,expected);var before=credential(c,target,true);validatePassword(c,target,newPassword,before);rememberPassword(c,before);
            update(c,"UPDATE public.mop_local_account SET password_hash=?,version=version+1,must_change_password=?,failed_attempts=0,locked_until=NULL,updated_at=statement_timestamp() WHERE user_id=?::uuid",passwords.encode(newPassword),Boolean.TRUE.equals(policy(c).get("force_change_on_reset")),target);audit(a,"identity.password.reset","user",target,"PASSWORD_RESET_SESSIONS_REVOKED");return result("password_reset",true,"sessions_revoked",true,"user_id",target);
        });
    }
    public Map<String,Object> enableLocalAccount(Access a,String target,long expected,String login,String password){
        return tx(c->{authorize(a,"users:write");requireTarget(a,target);fullUserScope(c,a,target);securityVersion(c,target,expected);username(login);validatePassword(c,target,password,null);
            if(!rows(c,"SELECT user_id FROM public.mop_local_account WHERE user_id=?::uuid",target).isEmpty())throw new DomainException(409,"LOCAL_ACCOUNT_EXISTS","该用户已开通本地账号");
            update(c,"INSERT INTO public.mop_local_account(user_id,username,password_hash,must_change_password) VALUES(?::uuid,?,?,?)",target,login,passwords.encode(password),Boolean.TRUE.equals(policy(c).get("force_change_on_reset")));advanceSecurity(c,target);audit(a,"identity.local_account.enable","user",target,"EXPLICIT_LOCAL_ACCOUNT_ENABLED");return one(c,USER_SELECT+" WHERE m.tenant_id=?::uuid AND u.id=?::uuid",a.tenant(),target);
        });
    }
    public Map<String,Object> unlock(Access a,String target,long expected) {
        return tx(c->{authorize(a,"users:write");requireTarget(a,target);fullUserScope(c,a,target);securityVersion(c,target,expected);update(c,"UPDATE public.mop_local_account SET failed_attempts=0,locked_until=NULL,version=version+1,updated_at=statement_timestamp() WHERE user_id=?::uuid",target);advanceSecurity(c,target);audit(a,"identity.account.unlock","user",target,"LOGIN_LOCK_CLEARED");return result("unlocked",true,"user_id",target);});
    }
    public Map<String,Object> revokeSessions(Access a,String target,long expected) {
        return tx(c->{authorize(a,"users:write");requireTarget(a,target);fullUserScope(c,a,target);securityVersion(c,target,expected);advanceSecurity(c,target);audit(a,"identity.sessions.revoke","user",target,"AUTHENTICATION_REVISION_ADVANCED");return result("sessions_revoked",true,"user_id",target);});
    }
    public Map<String,Object> changePassword(Access a,long expectedSecurity,String current,String replacement) {
        return tx(c->{securityVersion(c,a.actor(),expectedSecurity);var before=credential(c,a.actor(),true);passwordInput(current);if(!passwords.matches(current,before.hash()))throw new DomainException(403,"CURRENT_PASSWORD_INVALID","当前密码不正确");validatePassword(c,a.actor(),replacement,before);rememberPassword(c,before);
            update(c,"UPDATE public.mop_local_account SET password_hash=?,version=version+1,must_change_password=false,failed_attempts=0,locked_until=NULL,updated_at=statement_timestamp() WHERE user_id=?::uuid",passwords.encode(replacement),a.actor());audit(a,"identity.password.change","user",a.actor(),"PASSWORD_CHANGED_SESSIONS_REVOKED");return result("password_changed",true,"reauthenticate",true);});
    }
    public Map<String,Object> ownAccount(String user) {
        return read(c->one(c,"SELECT u.id,u.display_name,u.active,a.username,a.must_change_password,a.last_login_at,s.revision version FROM public.mop_user u JOIN public.mop_identity_security s ON s.user_id=u.id LEFT JOIN public.mop_local_account a ON a.user_id=u.id WHERE u.id=?::uuid AND u.active",id(user)));
    }
    private static final String ROLE_SELECT="SELECT r.id,r.tenant_id,r.name,r.description,r.base_role,r.permissions,r.builtin,r.enabled,r.version,r.created_at,r.updated_at,(SELECT count(*) FROM public.mop_membership m LEFT JOIN public.mop_member_role_assignment a ON a.tenant_id=m.tenant_id AND a.user_id=m.user_id WHERE m.tenant_id=r.tenant_id AND (a.role_id=r.id OR (a.role_id IS NULL AND r.builtin AND m.role_code=r.base_role))) assigned_members FROM public.mop_project_role r";
    private Map<String,Object> role(Connection c,String tenant,String roleId,boolean lock)throws SQLException { if(lock)one(c,"SELECT id FROM public.mop_project_role WHERE tenant_id=?::uuid AND id=?::uuid FOR SHARE",id(tenant),id(roleId));return one(c,ROLE_SELECT+" WHERE r.tenant_id=?::uuid AND r.id=?::uuid",tenant,roleId); }
    public List<Map<String,Object>> roles(Access a) { return read(c->{readAccess(c,a,"roles:read");return rows(c,ROLE_SELECT+" WHERE r.tenant_id=?::uuid ORDER BY r.builtin DESC,r.name,r.id LIMIT 201",a.tenant());}); }
    public void requireRole(Access a,String roleId) { try { role(platform.idempotentCommandConnection(),a.tenant(),roleId,false); } catch(SQLException e){throw sqlFailure(e);} }
    public Map<String,Object> createRole(Access a,ProjectRole value) {
        return tx(c->{authorize(a,"roles:write");if(((Number)one(c,"SELECT count(*) total FROM public.mop_project_role WHERE tenant_id=?::uuid",a.tenant()).get("total")).intValue()>=200)throw new DomainException(409,"ROLE_LIMIT_REACHED","当前租户达到200个角色的技术上限，请复用已有角色");String id=UUID.randomUUID().toString();update(c,"INSERT INTO public.mop_project_role(id,tenant_id,name,description,base_role,permissions,enabled,created_by) VALUES(?::uuid,?::uuid,?,?,?,?::text[],?,?::uuid)",id,a.tenant(),value.name(),value.description(),value.baseRole(),value.permissions(),value.enabled(),a.actor());audit(a,"identity.role.create","role",id,"EXPLICIT_ROLE_GRANTS");return role(c,a.tenant(),id,false);});
    }
    public Map<String,Object> updateRole(Access a,String roleId,long expected,ProjectRole value) {
        return tx(c->{authorize(a,"roles:write");var before=role(c,a.tenant(),roleId,true);if(version(before)!=expected)throw conflict();if(Boolean.TRUE.equals(before.get("builtin")))throw new DomainException(409,"BUILTIN_ROLE_PROTECTED","内置角色保留基线，请复制为自定义角色后调整");if(!before.get("base_role").equals(value.baseRole()))throw invalid("角色的基础业务类别不能修改");
            if(!value.enabled()||!value.permissions().containsAll(List.of("users:read","users:write","roles:read","roles:write"))) {
                var assigned=rows(c,"SELECT user_id FROM public.mop_member_role_assignment WHERE tenant_id=?::uuid AND role_id=?::uuid AND public.mop_effective_capabilities(user_id,tenant_id) @> ARRAY['users:read','users:write','roles:read','roles:write']::text[]",a.tenant(),roleId);
                if(!assigned.isEmpty()) {
                    var remaining=one(c,"SELECT count(*) total FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id JOIN public.mop_local_account account ON account.user_id=u.id WHERE m.tenant_id=?::uuid AND m.active AND u.active AND public.mop_effective_capabilities(m.user_id,m.tenant_id) @> ARRAY['users:read','users:write','roles:read','roles:write']::text[] AND NOT EXISTS(SELECT 1 FROM public.mop_member_role_assignment assignment WHERE assignment.tenant_id=m.tenant_id AND assignment.user_id=m.user_id AND assignment.role_id=?::uuid)",a.tenant(),roleId);
                    if(((Number)remaining.get("total")).longValue()==0)throw new DomainException(409,"LAST_ADMINISTRATOR","该角色变更将移除最后一个可用管理账号");
                }
            }
            update(c,"UPDATE public.mop_project_role SET name=?,description=?,permissions=?::text[],enabled=?,version=version+1,updated_at=statement_timestamp() WHERE tenant_id=?::uuid AND id=?::uuid AND version=?",value.name(),value.description(),value.permissions(),value.enabled(),a.tenant(),roleId,expected);
            audit(a,"identity.role.update","role",roleId,"ROLE_CHANGED_PERMISSION_EPOCH_ADVANCED");return role(c,a.tenant(),roleId,false);
        });
    }
    public List<Map<String,Object>> memberships(Access a,String target) {
        return read(c->{readAccess(c,a,"users:read");one(c,"SELECT user_id FROM public.mop_membership WHERE tenant_id=?::uuid AND user_id=?::uuid",a.tenant(),id(target));return rows(c,"SELECT m.tenant_id,t.name tenant_name,m.role_code base_role,m.active,m.permission_version,assignment.role_id FROM public.mop_membership m JOIN public.mop_tenant t ON t.id=m.tenant_id LEFT JOIN public.mop_member_role_assignment assignment ON assignment.tenant_id=m.tenant_id AND assignment.user_id=m.user_id WHERE m.user_id=?::uuid AND public.mop_effective_capabilities(?::uuid,m.tenant_id) @> ARRAY['users:read']::text[] ORDER BY m.tenant_id",target,a.actor());});
    }
    public Map<String,Object> setMembership(Access a,String target,String tenant,long expected,String roleId,boolean active) {
        return tx(c->{authorize(a,"users:write");requireTarget(a,target);var administrator=platform.membership(a.actor(),id(tenant)).orElseThrow(ProjectIdentityRepository::missing);if(!administrator.capabilities().contains("users:write"))throw missing();var selected=role(c,tenant,roleId,true);grantCeiling(administrator,selected);if(!Boolean.TRUE.equals(selected.get("enabled")))throw invalid("不能分配已停用的角色");
            var previous=rows(c,"SELECT role_code,permission_version,active FROM public.mop_membership WHERE tenant_id=?::uuid AND user_id=?::uuid FOR UPDATE",tenant,target);
            if(previous.isEmpty()){if(expected!=0)throw conflict();update(c,"INSERT INTO public.mop_membership(tenant_id,user_id,role_code,active) VALUES(?::uuid,?::uuid,?,?)",tenant,target,selected.get("base_role"),active);}
            else {if(((Number)previous.get(0).get("permission_version")).longValue()!=expected)throw conflict();guardManagedRole(c,administrator,tenant,target);if(target.equals(a.actor())&&!active)throw invalid("不能移除当前登录账号的成员资格");if(!active||!((List<?>)selected.get("permissions")).containsAll(List.of("users:read","users:write","roles:read","roles:write")))protectLastAdmin(c,target,tenant);update(c,"UPDATE public.mop_membership SET role_code=?,active=?,permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid",selected.get("base_role"),active,tenant,target);}
            try(var s=statement(c,"INSERT INTO public.mop_member_role_assignment(tenant_id,user_id,role_id) VALUES(?::uuid,?::uuid,?::uuid) ON CONFLICT(tenant_id,user_id) DO UPDATE SET role_id=excluded.role_id",tenant,target,roleId)){s.executeUpdate();}
            platform.audit(a.actor(),tenant,"identity.member.assign","user",target,"SUCCEEDED","EXPLICIT_TENANT_ROLE_ASSIGNMENT",a.trace());return one(c,"SELECT tenant_id,user_id,role_code base_role,active,permission_version FROM public.mop_membership WHERE tenant_id=?::uuid AND user_id=?::uuid",tenant,target);
        });
    }
    public Map<String,Object> updatePolicy(Access a,long expected,Map<String,Object> values) {
        return tx(c->{authorize(a,"roles:write");for(var tenant:rows(c,"SELECT id FROM public.mop_tenant")){var scope=platform.membership(a.actor(),tenant.get("id").toString());if(scope.isEmpty()||!scope.get().capabilities().contains("roles:write"))throw new DomainException(403,"POLICY_SCOPE_INCOMPLETE","全局身份策略要求具备全部项目租户的角色管理权限");}var previous=policy(c);if(version(previous)!=expected)throw conflict();update(c,"UPDATE public.mop_identity_policy SET version=version+1,minimum_password_length=?,password_history_count=?,failed_attempt_limit=?,lock_minutes=?,idle_minutes=?,force_change_on_reset=?,updated_at=statement_timestamp() WHERE singleton AND version=?",values.get("minimum_password_length"),values.get("password_history_count"),values.get("failed_attempt_limit"),values.get("lock_minutes"),values.get("idle_minutes"),values.get("force_change_on_reset"),expected);audit(a,"identity.policy.update","identity_policy",null,"EXPLICIT_POLICY_CONFIGURATION");return policy(c);});
    }
    public List<Map<String,Object>> bindings(Access a,String target) { return read(c->{readAccess(c,a,"users:read");one(c,"SELECT user_id FROM public.mop_membership WHERE tenant_id=?::uuid AND user_id=?::uuid",a.tenant(),id(target));return rows(c,"SELECT id,provider,issuer,subject,active,version,created_at FROM public.mop_external_identity_binding WHERE user_id=?::uuid ORDER BY created_at,id",target);}); }
    public Map<String,Object> bindExternal(Access a,String target,String issuer,String subject) {
        return tx(c->{authorize(a,"users:write");requireTarget(a,target);fullUserScope(c,a,target);String binding=UUID.randomUUID().toString();update(c,"INSERT INTO public.mop_external_identity_binding(id,user_id,provider,issuer,subject) VALUES(?::uuid,?::uuid,'oidc',?,?)",binding,target,issuer,subject);audit(a,"identity.external.bind","user",target,"EXPLICIT_ISSUER_SUBJECT_BINDING");return one(c,"SELECT id,provider,issuer,subject,active,version,created_at FROM public.mop_external_identity_binding WHERE id=?::uuid",binding);});
    }
    public Map<String,Object> disableBinding(Access a,String target,String binding,long expected) {
        return tx(c->{authorize(a,"users:write");requireTarget(a,target);fullUserScope(c,a,target);one(c,"SELECT id FROM public.mop_external_identity_binding WHERE id=?::uuid AND user_id=?::uuid",id(binding),target);update(c,"UPDATE public.mop_external_identity_binding SET active=false,version=version+1,updated_at=statement_timestamp() WHERE id=?::uuid AND user_id=?::uuid AND version=?",binding,target,expected);advanceSecurity(c,target);audit(a,"identity.external.unbind","user",target,"EXTERNAL_BINDING_DISABLED_SESSIONS_REVOKED");return result("binding_disabled",true,"sessions_revoked",true);});
    }
    public static Map<String,Object> result(Object...values) { var out=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out; }
}
