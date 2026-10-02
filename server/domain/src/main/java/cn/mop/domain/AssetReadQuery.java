package cn.mop.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Bounded main-ledger read matching the existing public filters; no inferred state or new business quota. */
public record AssetReadQuery(String type,String status,String siteId,String query,int limit,Instant afterCreatedAt,String afterId) {
    public AssetReadQuery {
        if(type!=null&&!Set.of("router","switch","firewall","ap","wireless_controller","other").contains(type)||status!=null&&!Set.of("unknown","ok","problem","maintenance","disabled").contains(status)||siteId!=null&&siteId.length()>64||query!=null&&query.length()>100||limit<1||limit>101||(afterCreatedAt==null)!=(afterId==null))throw invalid();
        for(String value:new String[]{siteId,query})if(value!=null&&value.codePoints().anyMatch(cp->cp==0||Character.getType(cp)==Character.SURROGATE))throw invalid();
        if(afterId!=null){if(!afterId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();afterId=UUID.fromString(afterId).toString();}
        if(afterCreatedAt!=null&&(afterCreatedAt.getNano()%1000000!=0||afterCreatedAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||afterCreatedAt.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))))throw invalid();
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","资产筛选或技术分页边界无效");}
}
