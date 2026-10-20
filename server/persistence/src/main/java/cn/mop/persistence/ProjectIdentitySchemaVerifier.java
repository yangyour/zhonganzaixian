package cn.mop.persistence;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;
import static cn.mop.persistence.TestSchemaMigration.*;
/** Explicit schema verification only; no DDL or automatic provisioning. */
public final class ProjectIdentitySchemaVerifier {
    static final String SHA256="41e5060d4925e7c8ea761539e4695a6ea5792743240363a7d5f50f24bcb59566";
    static final Map<String,String> FUNCTIONS=Map.ofEntries(
        Map.entry("mop_identity_user_seed()","0b775f04f2be188e1feb376190d0aac95ce8591d3d40630c0a335a035d1b99f6"),
        Map.entry("mop_local_account_guard()","29af0670bcea7e2aca5553d41d0a79ada6c53d95abaa071dbda1e0fe161594f5"),
        Map.entry("mop_project_role_guard()","6cc7a87e1756640cb720cbb43a02f33cfd3c6f3a4e9f78c8f166e05cafd42613"),
        Map.entry("mop_seed_tenant_roles()","7cdf68e7708c39d7615f70b21fba2ed2cf92de01c1eb4e2f56cdb9a2aff24ad4"),
        Map.entry("mop_effective_capabilities(uuid,uuid)","dc57af461cf5c8f5565fede6678673e764823f96aa8b22c4f41e8dda23085489"),
        Map.entry("mop_role_permission_epoch()","a8db3eb24d656235d70ec5115295894464cbaaee2505c203ba3f7836fbb97a3a"),
        Map.entry("mop_assignment_scope_guard()","210c7a8e6696a96b0e7dda5c2ca64de53979a5312741131b4bfa1d0829111973"),
        Map.entry("mop_assignment_epoch()","aec5008cfd58714ca9d54ef10909d65dc24161af6783edd030137a6beb8e8d05"));
    private ProjectIdentitySchemaVerifier(){}
    static final String COMPATIBILITY_SHA256="5554e23a3aabfe489ae6b9258293d0f33dda0bcc7da19c60bdb873fb16b77ad7";
    static void compatibilityResource()throws Exception{try(var in=ProjectIdentitySchemaVerifier.class.getResourceAsStream("/db/migration/V28__identity_inventory_and_password_formats.sql")){if(in==null||!COMPATIBILITY_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Unreviewed identity compatibility SQL");}}
    static void resource()throws Exception{try(var in=ProjectIdentitySchemaVerifier.class.getResourceAsStream("/db/migration/V27__project_identity_and_roles.sql")){if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Unreviewed project identity SQL");}}
    public static void verify(Connection c)throws SQLException{verify(c,true);}
    static void verify(Connection c,boolean inventoryCapability)throws SQLException{
        requireTrue(c,"SELECT current_database()='mop_test'");
        for(String table:List.of("mop_identity_security","mop_local_account","mop_local_password_history","mop_identity_policy","mop_role_capability_limit","mop_project_role","mop_member_role_assignment","mop_external_identity_binding")){
            requireTrue(c,"SELECT to_regclass('public.'||?) IS NOT NULL AND has_table_privilege('mop_test_runtime','public.'||?,'SELECT')",table,table);
            requireTrue(c,"SELECT NOT has_table_privilege('mop_test_runtime','public.'||?,'TRUNCATE') AND NOT has_table_privilege('mop_test_runtime','public.'||?,'TRIGGER')",table,table);
        }
        requireTrue(c,"SELECT NOT has_table_privilege('mop_test_runtime','public.mop_local_account','DELETE') AND NOT has_column_privilege('mop_test_runtime','public.mop_local_account','username','UPDATE') AND NOT has_table_privilege('mop_test_runtime','public.mop_role_capability_limit','INSERT') AND NOT has_table_privilege('mop_test_runtime','public.mop_role_capability_limit','UPDATE')");
        requireTrue(c,"SELECT count(*)=1 FROM public.mop_identity_policy WHERE singleton");
        for(var entry:FUNCTIONS.entrySet())requireTrue(c,"SELECT NOT p.prosecdef AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid=to_regprocedure('public.'||?)",entry.getValue(),entry.getKey());
        boolean lifecycleCapability;try(var q=c.createStatement();var rows=q.executeQuery("SELECT to_regclass('public.mop_ticket_lifecycle_revision') IS NOT NULL")){rows.next();lifecycleCapability=rows.getBoolean(1);}
        for(String role:List.of("viewer","tadmin","msp","padmin")){
            var actual=new TreeSet<String>();try(var q=c.prepareStatement("SELECT capability FROM public.mop_role_capability_limit WHERE base_role=?")){q.setString(1,role);try(var r=q.executeQuery()){while(r.next())actual.add(r.getString(1));}}
            var expected=new TreeSet<>(cn.mop.domain.Capabilities.forRole(role));if(!inventoryCapability)expected.remove("tenants:read_inventory");if(!lifecycleCapability){expected.remove("tickets:lifecycle_write");expected.remove("tickets:customer_decide");}
            if(!actual.equals(expected))throw new IllegalStateException("Role capability limit differs from reviewed code");
        }
        requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM public.mop_tenant t WHERE (SELECT count(*) FROM public.mop_project_role r WHERE r.tenant_id=t.id AND r.builtin)<>4)");
    }
}
