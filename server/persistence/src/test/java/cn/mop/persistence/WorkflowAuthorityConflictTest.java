package cn.mop.persistence;
import cn.mop.domain.DomainException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkflowAuthorityConflictTest {
 @Test void mapsOnlyDedicatedSqlStateWithoutLeakingDatabaseMessage(){
  var error=assertThrows(DomainException.class,()->WorkflowAuthorityConflict.rejectIfConflict(new SQLException("sensitive database details","P7401")));
  assertFalse(error.toString().contains("sensitive database details"));
 }
 @Test void unrelatedFailuresKeepTheirOriginalHandling(){
  assertDoesNotThrow(()->WorkflowAuthorityConflict.rejectIfConflict(new SQLException("other","P0001")));
  assertDoesNotThrow(()->WorkflowAuthorityConflict.rejectIfConflict(new java.io.IOException("P7401")));
 }
 @Test void sqlTextCannotImpersonateDedicatedState(){
  assertDoesNotThrow(()->WorkflowAuthorityConflict.rejectIfConflict(new SQLException("P7401")));
 }
}
