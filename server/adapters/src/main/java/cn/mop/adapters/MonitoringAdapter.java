package cn.mop.adapters;

/** Integration boundary; no implementation or invented monitoring result is installed. */
public interface MonitoringAdapter {
    record Observation(String assetId, String status, String sampledAt, String unknownReason) {}
    Observation observe(String tenantId, String assetId);
}
