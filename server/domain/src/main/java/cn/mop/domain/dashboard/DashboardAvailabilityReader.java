package cn.mop.domain.dashboard;
/** Same-snapshot current-member authorization and observed database clock; no fabricated monitoring or ticket metrics. */
public interface DashboardAvailabilityReader {
    record Access(String actorId,String tenantId,long permissionVersion){}
    DashboardAvailabilitySnapshot read(Access access,DashboardAvailabilitySnapshot.TrendWindow window);
}
