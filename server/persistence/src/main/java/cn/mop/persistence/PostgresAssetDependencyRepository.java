package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.assetdependency.AssetDependencyRepository;
import cn.mop.domain.assetdependency.AssetDependencyValidator;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Complete-graph validation and immutable draft revision within one tenant-locked transaction. */
public final class PostgresAssetDependencyRepository implements AssetDependencyRepository {
    private static final System.Logger LOG=System.getLogger(PostgresAssetDependencyRepository.class.getName());
    private final DataSource dataSource;
    private final PostgresPlatformRepository platform;
    public PostgresAssetDependencyRepository(DataSource dataSource,PostgresPlatformRepository platform){this.dataSource=Objects.requireNonNull(dataSource);this.platform=Objects.requireNonNull(platform);}

    @Override public Draft current(String tenant,String actor){
        try(Connection c=dataSource.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{authorize(c,tenant,actor,false);Draft result=read(c,tenant);c.commit();return result;}
            catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure){throw unavailable(failure);}
    }
    @Override public Draft append(String tenant,String actor,long expected,List<ProposedEdge> input,String trace){
        Connection c=platform.idempotentCommandConnection();
        if(expected<0||expected==Long.MAX_VALUE||input==null||input.isEmpty()||input.size()>MAX_ADDITIONS)throw invalid();
        List<ProposedEdge> additions=new ArrayList<>();
        for(ProposedEdge edge:input){if(edge==null)throw invalid();additions.add(validated(edge));}
        try{
            authorize(c,tenant,actor,true);
            try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setQueryTimeout(10);s.setString(1,"mop-asset-dependency|"+tenant);s.execute();}
            Draft before=read(c,tenant);
            List<ProposedEdge> combined=new ArrayList<>(before.edges());combined.addAll(additions);
            // Check every referenced object before version/structure errors to avoid object disclosure.
            var authorization=authorization(c,tenant,combined);
            if(before.version()!=expected)throw conflict();
            if(combined.size()>MAX_GRAPH_EDGES)throw limited();
            var oldGraph=new AssetDependencyValidator.GraphSnapshot(tenant,before.version(),true,toDependencies(tenant,before.edges()));
            AssetDependencyValidator.validateAddition(authorization,oldGraph,toDependencies(tenant,additions));
            Set<String> pairs=new HashSet<>();
            for(ProposedEdge edge:combined)if(!pairs.add(edge.parentId()+"/"+edge.childId()))throw new DomainException(409,"ASSET_DEPENDENCY_DUPLICATE","依赖草稿已存在，未覆盖原提案");
            long version=expected+1;
            String update=expected==0?"INSERT INTO public.mop_asset_dependency_graph(version,tenant_id) VALUES (?,?::uuid)":"UPDATE public.mop_asset_dependency_graph SET version=?,updated_at=statement_timestamp() WHERE tenant_id::text=? AND version=?";
            try(var s=c.prepareStatement(update)){s.setLong(1,version);s.setString(2,tenant);if(expected>0)s.setLong(3,expected);if(s.executeUpdate()!=1)throw conflict();}
            try(var s=c.prepareStatement("INSERT INTO public.mop_asset_dependency_revision(tenant_id,version,created_by) VALUES (?::uuid,?,?::uuid)")){s.setString(1,tenant);s.setLong(2,version);s.setString(3,actor);s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO public.mop_asset_dependency_edge(tenant_id,revision_version,parent_id,child_id,proposed_dependency_type,proposed_suppression_scope,proposed_enabled) VALUES (?::uuid,?,?::uuid,?::uuid,?,?,?)")){
                for(ProposedEdge edge:combined){s.setString(1,tenant);s.setLong(2,version);s.setString(3,edge.parentId());s.setString(4,edge.childId());s.setString(5,edge.proposedType());s.setString(6,edge.proposedSuppressionScope());if(edge.proposedEnabled()==null)s.setNull(7,Types.BOOLEAN);else s.setBoolean(7,edge.proposedEnabled());s.addBatch();}s.executeBatch();
            }
            platform.audit(actor,tenant,"asset_dependency.draft.append","asset_dependency_graph",tenant,"SUCCEEDED","Q20_PENDING_NOT_PUBLISHED",trace);
            return read(c,tenant);
        }catch(SQLException failure){throw unavailable(failure);}
    }
    private static void authorize(Connection c,String tenant,String actor,boolean write)throws SQLException{
        String sql="SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id::text=? AND m.user_id::text=? AND m.active AND u.active"+(write?" FOR SHARE OF m,u":"");
        try(var s=c.prepareStatement(sql)){s.setString(1,tenant);s.setString(2,actor);try(var r=s.executeQuery()){
            if(!r.next())throw new DomainException(401,"SESSION_EXPIRED","当前租户成员关系已失效");
            if(!Capabilities.forRole(r.getString(1)).contains(write?"assets:write":"assets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无资产依赖动作权限");
        }}
    }
    private static Draft read(Connection c,String tenant)throws SQLException{
        long version=0;Instant updated=null;
        try(var s=c.prepareStatement("SELECT g.version,g.updated_at,r.lifecycle_status,r.decision_status,r.monitoring_applied FROM public.mop_asset_dependency_graph g JOIN public.mop_asset_dependency_revision r ON r.tenant_id=g.tenant_id AND r.version=g.version WHERE g.tenant_id::text=?")){
            s.setString(1,tenant);try(var r=s.executeQuery()){if(r.next()){
                version=r.getLong(1);updated=r.getTimestamp(2).toInstant();
                if(!"DRAFT".equals(r.getString(3))||!"pending".equals(r.getString(4))||r.getBoolean(5))throw new DomainException(503,"UPSTREAM_UNAVAILABLE","依赖草稿状态不符合已验证结构");
            }}
        }
        List<ProposedEdge> edges=new ArrayList<>();
        if(version>0)try(var s=c.prepareStatement("SELECT parent_id::text,child_id::text,proposed_dependency_type,proposed_suppression_scope,proposed_enabled FROM public.mop_asset_dependency_edge WHERE tenant_id::text=? AND revision_version=? ORDER BY parent_id,child_id LIMIT ?")){
            s.setString(1,tenant);s.setLong(2,version);s.setInt(3,MAX_GRAPH_EDGES+1);try(var r=s.executeQuery()){while(r.next())edges.add(new ProposedEdge(r.getString(1),r.getString(2),r.getString(3),r.getString(4),(Boolean)r.getObject(5)));}
        }
        if(edges.size()>MAX_GRAPH_EDGES)throw limited();
        return new Draft(tenant,version,updated,edges);
    }
    private static AssetDependencyValidator.AuthorizationFacts authorization(Connection c,String tenant,List<ProposedEdge> edges)throws SQLException{
        Set<String> ids=new HashSet<>();for(ProposedEdge edge:edges){ids.add(edge.parentId());ids.add(edge.childId());}
        Set<String> visible=new HashSet<>();
        if(!ids.isEmpty())try(var s=c.prepareStatement("SELECT id::text FROM public.mop_asset WHERE tenant_id::text=? AND id=ANY(?::uuid[])")){
            s.setString(1,tenant);var array=c.createArrayOf("uuid",ids.toArray());
            try{s.setArray(2,array);try(var r=s.executeQuery()){while(r.next())visible.add(r.getString(1));}}finally{array.free();}
        }
        if(!visible.equals(ids))throw new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");
        return new AssetDependencyValidator.AuthorizationFacts(Set.of(tenant),visible.stream().map(id->new AssetDependencyValidator.AssetFact(new AssetDependencyValidator.AssetReference(tenant,id),true)).toList());
    }
    private static List<AssetDependencyValidator.Dependency> toDependencies(String tenant,List<ProposedEdge> edges){return edges.stream().map(e->new AssetDependencyValidator.Dependency(new AssetDependencyValidator.AssetReference(tenant,e.parentId()),new AssetDependencyValidator.AssetReference(tenant,e.childId()),e.proposedType(),e.proposedSuppressionScope(),e.proposedEnabled())).toList();}
    private static ProposedEdge validated(ProposedEdge edge){
        String parent=uuid(edge.parentId()),child=uuid(edge.childId());text(edge.proposedType(),64);text(edge.proposedSuppressionScope(),128);
        return new ProposedEdge(parent,child,edge.proposedType(),edge.proposedSuppressionScope(),edge.proposedEnabled());
    }
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static void text(String value,int max){if(value!=null&&((value.isEmpty()||value.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff))||value.codePointCount(0,value.length())>max||value.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE)))throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","依赖草稿参数无效或超出明确的批次技术上限");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","租户依赖图版本已变化，请重新读取完整图");}
    private static DomainException limited(){return new DomainException(413,"ASSET_DEPENDENCY_TECHNICAL_LIMIT","依赖图超过当前技术验证上限，未截断保存");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Dependency PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","依赖草稿PostgreSQL结构或连接未就绪，未取得保存证据");}
}
