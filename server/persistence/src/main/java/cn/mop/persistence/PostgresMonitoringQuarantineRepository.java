package cn.mop.persistence;
import cn.mop.domain.monitoring.QuarantinedMonitoringEvent;
import cn.mop.domain.monitoring.RawMonitoringEvent;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal immutable held-candidate storage; no claim-based authorization or automatic dispatch. */
public final class PostgresMonitoringQuarantineRepository {
    public enum Outcome { HELD, SAME_CANDIDATE }
    public record Receipt(Outcome outcome, UUID quarantineId, Instant firstReceivedAt){
        public Receipt{Objects.requireNonNull(outcome);Objects.requireNonNull(quarantineId);Objects.requireNonNull(firstReceivedAt);}
        @Override public String toString(){return "MonitoringQuarantineReceipt["+outcome+"; unverified, dispatch forbidden]";}
    }
    private final DataSource dataSource;
    public PostgresMonitoringQuarantineRepository(DataSource source){dataSource=Objects.requireNonNull(source);}
    public Receipt hold(QuarantinedMonitoringEvent incoming){
        Objects.requireNonNull(incoming);
        try(Connection c=dataSource.getConnection()){
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
            try{
                try(var s=c.createStatement()){s.execute("SET LOCAL synchronous_commit='on'");s.execute("SET LOCAL lock_timeout='10s'");s.execute("SET LOCAL statement_timeout='10s'");}
                boolean inserted;
                try(var s=c.prepareStatement("INSERT INTO public.mop_monitoring_quarantine(id,pool_id,source_event_id,source_phase,claimed_tenant_id,claimed_asset_id,quarantine_reason,occurred_epoch_second,occurred_nano,received_epoch_second,received_nano,payload) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(candidate_fingerprint) DO NOTHING RETURNING id")){
                    s.setObject(1,UUID.randomUUID());s.setObject(2,incoming.key().poolId());s.setString(3,incoming.key().sourceEventId());s.setString(4,incoming.key().phase().name());s.setObject(5,incoming.claims().tenantId());s.setObject(6,incoming.claims().assetId());s.setString(7,incoming.reason().name());s.setLong(8,incoming.occurredAt().getEpochSecond());s.setInt(9,incoming.occurredAt().getNano());s.setLong(10,incoming.receivedAt().getEpochSecond());s.setInt(11,incoming.receivedAt().getNano());s.setBytes(12,incoming.payload());try(var r=s.executeQuery()){inserted=r.next();}
                }
                Receipt receipt;
                try(var s=c.prepareStatement("SELECT * FROM ONLY public.mop_monitoring_quarantine WHERE candidate_fingerprint=?")){
                    s.setString(1,incoming.candidateFingerprint());try(var r=s.executeQuery()){
                        if(!r.next())throw unavailable();var stored=read(r);
                        if(!stored.candidateFingerprint().equals(incoming.candidateFingerprint()))throw unavailable();
                        receipt=new Receipt(inserted?Outcome.HELD:Outcome.SAME_CANDIDATE,r.getObject("id",UUID.class),stored.receivedAt());
                    }
                }
                c.commit();return receipt;
            }catch(SQLException|RuntimeException failure){try{c.rollback();}catch(SQLException ignored){}throw unavailable();}
        }catch(SQLException|RuntimeException failure){throw unavailable();}
    }
    private static QuarantinedMonitoringEvent read(ResultSet r)throws SQLException{
        if(!"HELD_UNVERIFIED".equals(r.getString("quarantine_status"))||r.getBoolean("dispatch_allowed")||r.getBoolean("source_authorized"))throw unavailable();
        var event=new QuarantinedMonitoringEvent(new RawMonitoringEvent.Key(r.getObject("pool_id",UUID.class),r.getString("source_event_id"),RawMonitoringEvent.Phase.valueOf(r.getString("source_phase"))),new QuarantinedMonitoringEvent.Claims(r.getObject("claimed_tenant_id",UUID.class),r.getObject("claimed_asset_id",UUID.class)),QuarantinedMonitoringEvent.Reason.valueOf(r.getString("quarantine_reason")),Instant.ofEpochSecond(r.getLong("occurred_epoch_second"),r.getInt("occurred_nano")),Instant.ofEpochSecond(r.getLong("received_epoch_second"),r.getInt("received_nano")),r.getBytes("payload"));
        if(!event.payloadSha256().equals(r.getString("payload_sha256"))||!event.candidateFingerprint().equals(r.getString("candidate_fingerprint")))throw unavailable();return event;
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("Monitoring quarantine storage unavailable; durable result unconfirmed");}
}
