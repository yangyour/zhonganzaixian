package cn.mop.adapters.scp;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;
import java.util.function.Function;

/** Explicit private configuration references. Reading/validating this object performs no I/O. */
public final class ScpSettings {
    public enum Auth {PASSWORD,EC2}
    public enum Tls {SYSTEM,CA_FILE,TEST_INSECURE}
    public final URI baseUrl;
    public final boolean enabled;
    public final String tenantId,assetId,poolId,projectId,region,service,usernameRef,passwordRef,accessKeyRef,secretKeyRef,caFileRef,publicKeyApi;
    public final Boolean legacyCookieRequired;
    public final Auth auth;
    public final Tls tls;
    private ScpSettings(JsonNode root){
        enabled=root.path("enabled").booleanValue();baseUrl=ScpProtocol.origin(ScpProtocol.requiredText(root,"base_url",512));
        tenantId=optional(root,"tenant_id");assetId=optional(root,"asset_id");poolId=optional(root,"resource_pool_id");projectId=optional(root,"scp_project_id");
        for(String id:Arrays.asList(tenantId,assetId))if(id!=null&&!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw ScpProtocol.fail("INVALID_MOP_SCOPE");
        for(String id:Arrays.asList(poolId,projectId))if(id!=null)ScpProtocol.identifier(id);
        region=optional(root,"region");service=optional(root,"service");
        for(String value:Arrays.asList(region,service))if(value!=null&&!value.matches("[a-z0-9-]{1,64}"))throw ScpProtocol.fail("INVALID_EC2_SCOPE");
        String mode=optional(root,"auth_mode");auth=mode==null?null:Auth.valueOf(mode);
        tls=Tls.valueOf(ScpProtocol.requiredText(root,"tls_mode",20));
        if(tls==Tls.TEST_INSECURE&&!Set.of("https://192.168.1.179","https://192.168.1.179:443").contains(baseUrl.toString()))throw ScpProtocol.fail("TEST_TLS_TARGET_NOT_APPROVED");
        usernameRef=reference(root,"username_ref");passwordRef=reference(root,"password_ref");accessKeyRef=reference(root,"access_key_ref");secretKeyRef=reference(root,"secret_key_ref");caFileRef=reference(root,"ca_file_ref");
        publicKeyApi=optional(root,"public_key_api");if(publicKeyApi!=null&&!Set.of("V1","V2").contains(publicKeyApi))throw ScpProtocol.fail("INVALID_PUBLIC_KEY_API");
        JsonNode legacy=root.get("legacy_cookie_required");if(!legacy.isNull()&&!legacy.isBoolean())throw ScpProtocol.fail("INVALID_COOKIE_POLICY");legacyCookieRequired=legacy.isNull()?null:legacy.booleanValue();
    }
    public static ScpSettings parse(byte[] bytes){
        try{
            if(bytes==null||bytes.length>16384)throw ScpProtocol.fail("INVALID_CONFIGURATION");JsonNode root=ScpProtocol.JSON.readTree(bytes);
            Set<String> keys=Set.of("schema_version","enabled","base_url","tenant_id","asset_id","resource_pool_id","scp_project_id","auth_mode","username_ref","password_ref","access_key_ref","secret_key_ref","region","service","legacy_cookie_required","public_key_api","tls_mode","ca_file_ref");
            if(!root.isObject()||root.size()!=keys.size()||!root.path("enabled").isBoolean()||!root.path("schema_version").isInt()||root.path("schema_version").intValue()!=1)throw ScpProtocol.fail("INVALID_CONFIGURATION");
            for(String key:keys)if(!root.has(key))throw ScpProtocol.fail("INVALID_CONFIGURATION");
            return new ScpSettings(root);
        }catch(ScpProtocol.Failure failure){throw failure;}catch(Exception failure){throw ScpProtocol.fail("INVALID_CONFIGURATION");}
    }
    public List<String> problems(Function<String,String> resolver){
        List<String> problems=new ArrayList<>(connectionProblems(resolver));
        if(tenantId==null||assetId==null||poolId==null||projectId==null)problems.add("MOP_AND_SCP_SCOPE_PENDING");
        return List.copyOf(problems);
    }
    /** Connection diagnostics do not invent a resource scope or permit resource-page reads. */
    public List<String> connectionProblems(Function<String,String> resolver){
        List<String> problems=new ArrayList<>();
        if(!enabled)problems.add("DISABLED");
        if(auth==null){problems.add("AUTH_MODE_PENDING");problems.add("READ_ONLY_CREDENTIALS_PENDING");}
        if(auth==Auth.PASSWORD){if(!present(usernameRef,resolver)||!present(passwordRef,resolver))problems.add("READ_ONLY_CREDENTIALS_PENDING");if(publicKeyApi==null)problems.add("PUBLIC_KEY_API_VERSION_PENDING");}
        if(auth==Auth.EC2){if(!present(accessKeyRef,resolver)||!present(secretKeyRef,resolver))problems.add("READ_ONLY_CREDENTIALS_PENDING");if(region==null||service==null)problems.add("EC2_SIGNING_SCOPE_PENDING");}
        if(legacyCookieRequired==null)problems.add("COOKIE_VERSION_POLICY_PENDING");
        if(tls==Tls.CA_FILE&&!present(caFileRef,resolver))problems.add("CA_FILE_PENDING");
        return List.copyOf(problems);
    }
    public boolean runtimeReadyVerified(){return false;}
    private static boolean present(String ref,Function<String,String> resolver){try{String value=ref==null?null:resolver.apply(ref);return value!=null&&!value.isEmpty();}catch(Exception failure){return false;}}
    private static String optional(JsonNode root,String key){return ScpProtocol.optionalText(root,key,512);}
    private static String reference(JsonNode root,String key){String value=optional(root,key);if(value!=null&&!value.matches("MOP_SCP_[A-Z0-9_]{1,64}"))throw ScpProtocol.fail("INVALID_PRIVATE_REFERENCE");return value;}
    @Override public String toString(){return "ScpSettings[private references and identifiers redacted; enabled="+enabled+"]";}
}
