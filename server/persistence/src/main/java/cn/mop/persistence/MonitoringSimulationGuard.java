package cn.mop.persistence;

import cn.mop.domain.DomainException;
import java.sql.*;
import java.util.Set;

/** Internal fault injection cannot be enabled in the active project database or protected customer tenants. */
final class MonitoringSimulationGuard {
 private static final Set<String> PROTECTED=Set.of("55000000-0000-4000-8000-000000000001","55000000-0000-4000-8000-000000000002");
 private MonitoringSimulationGuard(){}
 static void requireIsolated(Connection c,String tenant)throws SQLException{
  requireTenant(tenant);
  try(var s=c.prepareStatement("SELECT current_database(),current_user,session_user")){
   s.setQueryTimeout(5);try(var r=s.executeQuery()){
    if(!r.next()||!r.getString(1).matches("mop_monitoring_test_[a-f0-9]{12}")||!"mop_test_runtime".equals(r.getString(2))||!"mop_test_runtime".equals(r.getString(3))||r.next())throw forbidden();
   }
  }
 }
 /** Test harness preflight, before opening a connection. Never creates a namespace or grants access. */
 static void requireTestConfiguration(String jdbcUrl,String tenant){
  requireTenant(tenant);
  if(jdbcUrl==null||!jdbcUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/mop_monitoring_test_[a-f0-9]{12}"))throw forbidden();
  int start="jdbc:postgresql://127.0.0.1:".length(),port=Integer.parseInt(jdbcUrl.substring(start,jdbcUrl.indexOf('/',start)));
  if(port<1||port>65535)throw forbidden();
 }
 private static void requireTenant(String tenant){if(tenant==null||!tenant.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||PROTECTED.contains(tenant))throw forbidden();}
 private static DomainException forbidden(){return new DomainException(422,"MONITORING_SIMULATION_FORBIDDEN","建单失败模拟只允许明确隔离数据库和非实际客户测试命名空间");}
}
