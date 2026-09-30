package cn.mop.domain;
import java.util.List;
import java.util.Optional;
public interface AssetRepository {
    List<Asset> list(String tenantId);
    Optional<Asset> find(String tenantId, String id);
    Asset insert(String tenantId, String actorId, String name, String type, String ip, String siteId);
    boolean siteBelongsTo(String tenantId, String siteId);
}
