package cn.mop.persistence.configuration;

import cn.mop.domain.Capabilities;
import cn.mop.domain.DomainException;
import cn.mop.domain.configuration.ConfigurationDiagnostic;
import cn.mop.domain.configuration.ConfigurationDiagnosticReader;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Independent read-only reader of the designated PostgreSQL registry/proposal metadata.
 * Authorization and all source reads share one REPEATABLE READ snapshot. A revocation committed
 * after that snapshot affects the next read; no membership row lock or DDL is issued here.
 * Only descriptive metadata/reference counts are selected. Values and document bodies never enter the DTO.
 */
public final class PostgresConfigurationDiagnosticReader implements ConfigurationDiagnosticReader {
    private final DataSource dataSource;
    private static final int QUERY_TIMEOUT_SECONDS=3;
    private static final String AUTHORIZATION_SQL="SELECT current_database() AS database_name,current_user AS database_user,session_user AS session_user_name,u.active AS user_active,m.active AS membership_active,m.permission_version,m.role_code,transaction_timestamp() AS observed_at FROM (SELECT 1) requested LEFT JOIN public.mop_user u ON u.id=?::uuid LEFT JOIN public.mop_membership m ON m.user_id=u.id AND m.tenant_id=?::uuid";
    private static final String REGISTRY_SQL=registrySql();
    private static final String PROPOSALS_SQL="SELECT r.id,r.tenant_id,r.configuration_key,r.version,r.workflow_status,CASE WHEN jsonb_typeof(r.proposal->'evidence_refs')='array' THEN jsonb_array_length(r.proposal->'evidence_refs') ELSE NULL END AS evidence_reference_count,jsonb_typeof(r.proposal)='object' AND CASE WHEN jsonb_typeof(r.proposal->'evidence_refs')='array' THEN NOT EXISTS(SELECT 1 FROM jsonb_array_elements(r.proposal->'evidence_refs') evidence(value) WHERE jsonb_typeof(evidence.value)<>'object') ELSE jsonb_typeof(r.proposal->'evidence_refs') IS NULL OR jsonb_typeof(r.proposal->'evidence_refs')='null' END AS metadata_shape_valid FROM public.mop_configuration_revision r WHERE r.tenant_id=?::uuid AND r.configuration_key=? ORDER BY r.created_at DESC,r.id DESC LIMIT 6";

    public PostgresConfigurationDiagnosticReader(DataSource dataSource){this.dataSource=Objects.requireNonNull(dataSource,"dataSource");}

    @Override public ConfigurationDiagnostic read(TrustedContext context,String key) {
        if(context==null||key==null||!key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))throw invalid();
        try(Connection connection=dataSource.getConnection()) {
            if(!connection.getAutoCommit())throw new SQLException("An independent connection is required");
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                Instant observedAt=authorize(connection,context);
                ConfigurationDiagnostic.Registry registry=registry(connection,key);
                ConfigurationDiagnostic.RecentProposals proposals=proposals(connection,context.tenantId(),key);
                ConfigurationDiagnostic result=ConfigurationDiagnostic.blocked(key,new ConfigurationDiagnostic.Context(context.tenantId()),observedAt,registry,proposals);
                connection.commit();return result;
            }catch(SQLException|RuntimeException|Error failure) {
                try{connection.rollback();}catch(SQLException ignored){}
                throw failure;
            }
        }catch(DomainException rejected){throw rejected;}
        catch(SQLException|RuntimeException unavailable){throw unavailable();}
    }

    private static Instant authorize(Connection connection,TrustedContext context)throws SQLException {
        try(PreparedStatement statement=query(connection,AUTHORIZATION_SQL)) {
            statement.setString(1,context.actorId());statement.setString(2,context.tenantId());
            try(ResultSet rows=statement.executeQuery()) {
                if(!rows.next())throw new SQLException("Authorization snapshot unavailable");
                if(!"mop_test".equals(rows.getString("database_name"))||!"mop_test_runtime".equals(rows.getString("database_user"))||!"mop_test_runtime".equals(rows.getString("session_user_name")))throw new SQLException("Unexpected namespace");
                if(!rows.getBoolean("user_active")||!rows.getBoolean("membership_active")||rows.getLong("permission_version")!=context.expectedPermissionVersion())throw expired();
                String role=rows.getString("role_code");
                if(!"padmin".equals(role)||!cn.mop.persistence.PostgresAuthorization.capabilities(connection,context.actorId(),context.tenantId()).contains("configurations:read"))throw forbidden();
                Timestamp observed=rows.getTimestamp("observed_at");if(observed==null||rows.next())throw new SQLException("Invalid authorization snapshot");
                return observed.toInstant();
            }
        }
    }

    private static ConfigurationDiagnostic.Registry registry(Connection connection,String key)throws SQLException {
        try(PreparedStatement statement=query(connection,REGISTRY_SQL)) {
            statement.setString(1,key);
            try(ResultSet rows=statement.executeQuery()) {
                if(!rows.next())throw new DomainException(404,"OBJECT_NOT_FOUND","配置登记不存在或不可见");
                if(!key.equals(rows.getString("key")))throw new SQLException("Registry binding mismatch");
                if(!rows.getBoolean("metadata_shape_valid"))throw new SQLException("Invalid registry metadata shape");
                ConfigurationDiagnostic.Registry result=new ConfigurationDiagnostic.Registry(rows.getLong("version"),rows.getString("type_label"),rows.getString("unit_label"),rows.getString("scope_label"),rows.getString("source_ref"),rows.getString("decision_status_label"),rows.getString("classification_label"),questions(rows.getArray("question_ids")));
                if(rows.next())throw new SQLException("Ambiguous registry");
                return result;
            }
        }
    }

    private static List<String> questions(java.sql.Array array)throws SQLException {
        if(array==null)return null;
        try {
            Object values=array.getArray();if(!(values instanceof Object[] items))throw new SQLException("Invalid question metadata");
            List<String> questions=new ArrayList<>();
            for(Object item:items){if(!(item instanceof String question)||!question.matches("Q[0-9]{2}"))throw new SQLException("Invalid question reference");questions.add(question);}
            return List.copyOf(questions);
        }finally{array.free();}
    }

    private static ConfigurationDiagnostic.RecentProposals proposals(Connection connection,String tenant,String key)throws SQLException {
        try(PreparedStatement statement=query(connection,PROPOSALS_SQL)) {
            statement.setString(1,tenant);statement.setString(2,key);
            List<ConfigurationDiagnostic.Proposal> proposals=new ArrayList<>();boolean truncated=false;
            try(ResultSet rows=statement.executeQuery()) {
                while(rows.next()) {
                    if(!tenant.equals(rows.getString("tenant_id"))||!key.equals(rows.getString("configuration_key")))throw new SQLException("Proposal binding mismatch");
                    if(!rows.getBoolean("metadata_shape_valid"))throw new SQLException("Invalid proposal metadata shape");
                    if(proposals.size()==5){truncated=true;break;}
                    int count=rows.getInt("evidence_reference_count");Integer references=rows.wasNull()?null:count;
                    proposals.add(new ConfigurationDiagnostic.Proposal(rows.getString("id"),rows.getLong("version"),rows.getString("workflow_status"),references));
                }
            }
            return new ConfigurationDiagnostic.RecentProposals(proposals,truncated);
        }
    }

    private static PreparedStatement query(Connection connection,String sql)throws SQLException {
        PreparedStatement statement=connection.prepareStatement(sql);
        try{statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);return statement;}
        catch(SQLException failure){try{statement.close();}catch(SQLException ignored){}throw failure;}
    }
    private static String registrySql() {
        String[] names={"type","unit","scope","source","decision_status","contract_value_kind"};
        String[] aliases={"type_label","unit_label","scope_label","source_ref","decision_status_label","classification_label"};
        StringBuilder projected=new StringBuilder("SELECT c.key,c.version");
        StringBuilder shape=new StringBuilder("jsonb_typeof(c.registry_json)='object'");
        for(int index=0;index<names.length;index++) {
            // These column names are code constants. A JSON object/array is never stringified into a label.
            String value="c.registry_json->'"+names[index]+"'";
            projected.append(",CASE WHEN jsonb_typeof(").append(value).append(")='string' THEN c.registry_json->>'").append(names[index]).append("' ELSE NULL END AS ").append(aliases[index]);
            shape.append(" AND (jsonb_typeof(").append(value).append(") IS NULL OR jsonb_typeof(").append(value).append(") IN ('string','null'))");
        }
        projected.append(",CASE WHEN jsonb_typeof(c.registry_json->'question_ids')='array' THEN ARRAY(SELECT CASE WHEN jsonb_typeof(element.value)='string' THEN element.value #>> '{}' ELSE NULL END FROM jsonb_array_elements(c.registry_json->'question_ids') element(value)) ELSE NULL END AS question_ids");
        shape.append(" AND CASE WHEN jsonb_typeof(c.registry_json->'question_ids')='array' THEN NOT EXISTS(SELECT 1 FROM jsonb_array_elements(c.registry_json->'question_ids') element(value) WHERE jsonb_typeof(element.value)<>'string' OR (element.value #>> '{}') !~ '^Q[0-9]{2}$') ELSE jsonb_typeof(c.registry_json->'question_ids') IS NULL OR jsonb_typeof(c.registry_json->'question_ids')='null' END");
        return projected.append(',').append(shape).append(" AS metadata_shape_valid FROM public.mop_configuration c WHERE c.key=?").toString();
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","配置诊断请求无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员关系或权限版本已失效");}
    private static DomainException forbidden(){return new DomainException(403,"ACTION_FORBIDDEN","当前角色无配置诊断读取权限");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","指定 PostgreSQL 配置诊断来源不可用，未返回替代结果");}
}
