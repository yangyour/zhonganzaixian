package cn.mop.api;
import cn.mop.persistence.PostgresBackupVersionRepository;
import cn.mop.persistence.PostgresBackupVersionRepository.*;
import javax.sql.DataSource;
import java.util.Objects;

/** Explicit application binding only. No default bean, role bypass, object IO, or draft-permission fallback.
 * Both authorizers must implement current device/version authority and hold their revocation locks inside the repository transaction. */
@FunctionalInterface
public interface BackupVersionInventoryQuery {
 InventoryPage inventory(Access access,InventoryScope scope,InventoryCursor before,int limit);
 static BackupVersionInventoryQuery postgres(DataSource source,Authorizer versionAuthorizer,InventoryAuthorizer inventoryAuthorizer){
  Objects.requireNonNull(inventoryAuthorizer);
  var repository=PostgresBackupVersionRepository.readOnly(source,Objects.requireNonNull(versionAuthorizer));
  return (access,scope,before,limit)->repository.inventory(access,scope,before,limit,inventoryAuthorizer);
 }
}
