package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.IngestEventCommand;
import cn.mop.domain.monitoring.MonitoringEventPipeline.MonitorState;
import cn.mop.domain.monitoring.MonitoringEventPipeline.Severity;
import cn.mop.domain.monitoring.RawMonitoringEvent;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** Decodes the exact server-read Zabbix envelope; ownership must be rechecked by the mapping transaction. */
final class MappedMonitoringProblem {

    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of(
        "provider",
        "source_event_id",
        "trigger_id",
        "clock",
        "nanoseconds",
        "value",
        "severity",
        "recovery_event_id",
        "host_ids"
    );
    private static final BigInteger MAX_ID = new BigInteger("18446744073709551615");

    private MappedMonitoringProblem() {}

    static IngestEventCommand decode(RawMonitoringEvent raw, String sourceHost) {
        if (raw.key().phase() != RawMonitoringEvent.Phase.PROBLEM) {
            throw new DomainException(
                422,
                "RECOVERY_REQUIRES_OBSERVATION",
                "原始恢复信号须通过当前周期观察证据核验，不能在问题事件入口确认恢复"
            );
        }
        if (raw.occurredAt().isAfter(raw.receivedAt())) {
            throw new DomainException(
                422,
                "SOURCE_CLOCK_AHEAD",
                "来源问题时间晚于服务端接收时刻，未采用该时刻创建业务事件"
            );
        }
        try {
            JsonNode body = JSON.readTree(raw.payload());
            if (body == null || !body.isObject() || body.size() != FIELDS.size()) {
                throw invalid();
            }
            var names = body.fieldNames();
            while (names.hasNext()) {
                if (!FIELDS.contains(names.next())) {
                    throw invalid();
                }
            }
            String trigger = numeric(body, "trigger_id", false);
            numeric(body, "recovery_event_id", true);
            if (
                !"ZABBIX_7".equals(text(body, "provider")) ||
                !raw
                    .key()
                    .sourceEventId()
                    .equals(numeric(body, "source_event_id", false)) ||
                whole(body, "value") != 1 ||
                whole(body, "clock") != raw.occurredAt().getEpochSecond() ||
                whole(body, "nanoseconds") != raw.occurredAt().getNano() ||
                !body.path("host_ids").isArray() ||
                body.path("host_ids").size() != 1 ||
                !body.path("host_ids").get(0).isTextual() ||
                !sourceHost.equals(body.path("host_ids").get(0).textValue())
            ) {
                throw invalid();
            }
            long severity = whole(body, "severity");
            if (severity == 0) {
                throw new DomainException(
                    422,
                    "SOURCE_SEVERITY_UNCLASSIFIED",
                    "来源严重级别尚未分类，未推断业务级别或创建工单"
                );
            }
            if (severity < 1 || severity > 5) {
                throw invalid();
            }
            // Pool plus trigger is a technical identity, not a customer threshold or priority policy.
            String rule = "ZBX_" + raw.key().poolId().toString().replace("-", "") + "_" + trigger;
            return new IngestEventCommand(
                raw.binding().assetId().toString(),
                rule,
                "Zabbix trigger " + trigger,
                Severity.values()[(int) severity - 1],
                MonitorState.PROBLEM,
                raw.key().sourceEventId(),
                raw.occurredAt().truncatedTo(ChronoUnit.MILLIS),
                false
            );
        } catch (DomainException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static String text(JsonNode body, String key) {
        if (!body.path(key).isTextual()) {
            throw invalid();
        }
        return body.path(key).textValue();
    }

    private static String numeric(JsonNode body, String key, boolean zero) {
        String value = text(body, key);
        if (
            !value.matches(zero ? "(?:0|[1-9][0-9]{0,19})" : "[1-9][0-9]{0,19}") ||
            new BigInteger(value).compareTo(MAX_ID) > 0
        ) {
            throw invalid();
        }
        return value;
    }

    private static long whole(JsonNode body, String key) {
        if (!body.path(key).isIntegralNumber() || !body.path(key).canConvertToLong()) {
            throw invalid();
        }
        return body.path(key).longValue();
    }

    private static DomainException invalid() {
        return new DomainException(
            503,
            "MAPPED_PROBLEM_ENVELOPE_INVALID",
            "来源问题原文、对象、时间或级别不一致，未推进业务处理"
        );
    }
}
