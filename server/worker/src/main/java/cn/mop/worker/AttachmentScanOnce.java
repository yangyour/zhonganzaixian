package cn.mop.worker;

import cn.mop.adapters.ClamdAttachmentScanner;
import cn.mop.domain.DomainException;
import cn.mop.persistence.PostgresAttachmentSecurityRepository;
import java.util.UUID;
import java.util.function.Function;

/** Explicit one-item scanner consumer; never starts the ordinary photo poller or creates a component. */
public final class AttachmentScanOnce {
    private AttachmentScanOnce() {
    }

    /** Check all scanner/scope switches before constructing database or storage clients. */
    static String requireTenant(Function<String, String> setting) {
        ClamdAttachmentScanner.Policy.configured(setting)
            .orElseThrow(() -> new IllegalStateException("FAIL_CLOSED: attachment scanning is not enabled"));
        String tenant = setting.apply("MOP_ATTACHMENT_SCAN_TENANT_ID");
        if (tenant == null || !tenant.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new IllegalStateException("FAIL_CLOSED: explicit attachment scan tenant required");
        }
        return tenant;
    }

    static void run() {
        try {
            String tenant = requireTenant(System::getenv);
            var policy = ClamdAttachmentScanner.Policy.configured(System::getenv).orElseThrow();
            String profiles = System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "remote_test");
            var settings = WorkerRuntimeSettings.from(profiles.split(","), System::getenv);
            var application = new WorkerApplication();
            try (var source = application.workerDataSource(settings)) {
                var platform = application.workerRepository(source, application.workerObjectMapper());
                var security = new PostgresAttachmentSecurityRepository(platform,
                    "public", "CONFIGURED_NOT_VERIFIED", policy.sha256());
                var pending = security.nextQueued(tenant);
                if (pending.isEmpty()) {
                    System.out.println("{\"status\":\"NO_QUEUED_ATTACHMENT\",\"scans\":0,\"published\":false}");
                    return;
                }
                var job = pending.orElseThrow();
                var state = security.current(job.access(), job.fileId());
                if (state.version() != job.version() || !"QUEUED".equals(state.state())) {
                    throw new DomainException(409, "VERSION_CONFLICT", "附件扫描申请已变化");
                }
                var file = platform.file(tenant, job.access().actorId(), job.fileId()).orElseThrow();
                var storage = application.photoObjectStore(settings);
                if (!storage.ready()) {
                    throw new IllegalStateException("Attachment storage is unavailable");
                }
                byte[] content = storage.read("photos/" + tenant + "/" + job.fileId(),
                    ((Number) file.get("size_bytes")).longValue(), state.contentSha256());
                var beforeScan = security.current(job.access(), job.fileId());
                if (beforeScan.version() != job.version() || !"QUEUED".equals(beforeScan.state())
                        || !job.requestId().equals(beforeScan.scanRequestId())
                        || !java.util.Objects.equals(state.contentSha256(), beforeScan.contentSha256())) {
                    throw new DomainException(409, "VERSION_CONFLICT", "存储读取期间扫描申请已变化，未调用扫描器");
                }
                var observation = new ClamdAttachmentScanner(policy).scan(job.requestId(), state.contentSha256(), content);
                var recorded = security.recordScan(job.access(), job.fileId(), job.version(), observation, UUID.randomUUID().toString());
                System.out.println("{\"status\":\"" + recorded.state() + "\",\"scans\":1,\"published\":false}");
            }
        } catch (Exception unavailable) {
            throw new IllegalStateException("FAIL_CLOSED: attachment scan was not confirmed; inspect current authorized request state, no automatic retry or publication");
        }
    }
}
