package cn.mop.adapters.monitoring;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MappedSourcePage;
import cn.mop.domain.monitoring.MonitoringObjectMapping;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only host-filtered event.get; range/order/host checks precede any consumer transaction. */
public final class ZabbixMappedEventPageReader {

    private final UUID pool;
    /** Restricted read transport; request parameters and every host, including lookahead, are checked here. */
    private final ZabbixEventPuller.Reader reader;

    public ZabbixMappedEventPageReader(UUID pool, ZabbixReadClient client) {
        this(pool, parameters -> client.call("event.get", parameters));
    }

    ZabbixMappedEventPageReader(UUID pool, ZabbixEventPuller.Reader reader) {
        this.pool = Objects.requireNonNull(pool);
        this.reader = Objects.requireNonNull(reader);
    }

    public MappedSourcePage.Fetched read(MonitoringObjectMapping.Key key, MappedSourcePage.Window window) {
        if (
            key == null ||
            !pool.equals(key.poolId()) ||
            key.objectType() != MonitoringObjectMapping.ObjectType.HOST ||
            !MappedSourcePage.numeric(key.externalId()) ||
            window == null
        ) {
            throw new DomainException(422, "MAPPING_CAPTURE_HOST_REQUIRED", "分页读取须绑定一个明确主机及来源窗口");
        }
        try {
            var puller = new ZabbixEventPuller(pool, parameters -> {
                var scoped = new HashMap<String, Object>(parameters);
                scoped.put("hostids", List.of(key.externalId()));
                var rows = reader.read(scoped);
                // Include the lookahead row: a foreign host cannot manufacture the next-page position.
                if (rows != null && rows.isArray()) {
                    for (var row : rows) {
                        var hosts = row.path("hosts");
                        if (
                            !hosts.isArray() ||
                            hosts.size() != 1 ||
                            !key.externalId().equals(hosts.path(0).path("hostid").textValue())
                        ) {
                            throw new IllegalStateException("Source page host scope mismatch");
                        }
                    }
                }
                return rows;
            });
            var query = new ZabbixEventPuller.Query(
                pool,
                window.firstEventId(),
                window.lastEventId(),
                window.fromClock(),
                window.throughClock(),
                window.pageSize()
            );
            var page = puller.read(query);
            var events = new ArrayList<cn.mop.domain.monitoring.MonitoringObjectBindingRepository.FetchedEvent>();
            for (var event : page.events()) {
                if (!event.hostIds().equals(List.of(key.externalId()))) {
                    throw new IllegalStateException("Source page host scope mismatch");
                }
                events.add(ZabbixMappedEventReader.project(key, event));
            }
            return new MappedSourcePage.Fetched(window, events, page.nextEventId());
        } catch (Exception failure) {
            throw new DomainException(
                503,
                "MAPPING_SOURCE_PAGE_UNAVAILABLE",
                "来源分页范围、主机或协议未通过核验，未消费该页"
            );
        }
    }
}
