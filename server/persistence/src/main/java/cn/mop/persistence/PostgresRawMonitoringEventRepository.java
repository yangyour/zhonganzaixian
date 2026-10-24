package cn.mop.persistence;

import cn.mop.domain.monitoring.RawMonitoringEvent;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal storage only. Not wired to intake: caller must authenticate source and resolve mapping first. */
public final class PostgresRawMonitoringEventRepository {
    public enum Outcome { STORED, SAME_CONTENT, CONFLICT_REQUIRES_REVIEW }
    public record Receipt(Outcome outcome, UUID storedId, Instant firstReceivedAt) {
        public Receipt {
            Objects.requireNonNull(outcome);
            if(outcome==Outcome.CONFLICT_REQUIRES_REVIEW ? storedId!=null||firstReceivedAt!=null : storedId==null||firstReceivedAt==null)
                throw new IllegalArgumentException("Invalid raw event storage receipt");
        }
        @Override public String toString(){return "RawEventStorageReceipt["+outcome+"; no processing or authentication claim]";}
    }
    private final DataSource dataSource;
    public PostgresRawMonitoringEventRepository(DataSource source){dataSource=Objects.requireNonNull(source);}
    public Receipt store(RawMonitoringEvent incoming) {
        Objects.requireNonNull(incoming);
        try(Connection c=dataSource.getConnection()){
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
            try{
                try(var s=c.createStatement()){s.execute("SET LOCAL synchronous_commit='on'");s.execute("SET LOCAL lock_timeout='10s'");s.execute("SET LOCAL statement_timeout='10s'");}
                Receipt receipt=storeInTransaction(c,incoming);
                c.commit();return receipt;
            }catch(SQLException|RuntimeException failure){try{c.rollback();}catch(SQLException ignored){}throw unavailable();}
        }catch(SQLException|RuntimeException failure){throw unavailable();}
    }
    /** Borrowed owned transaction: never commits, rolls back or closes the caller connection. */
    static Receipt storeInTransaction(Connection c,RawMonitoringEvent incoming)throws SQLException{
        Objects.requireNonNull(incoming);
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw unavailable();
                boolean inserted;
                try(var s=c.prepareStatement("INSERT INTO public.mop_raw_monitoring_event(id,pool_id,source_event_id,source_phase,tenant_id,asset_id,occurred_epoch_second,occurred_nano,received_epoch_second,received_nano,payload) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(pool_id,source_event_id,source_phase) DO NOTHING RETURNING id")){
                    s.setObject(1,UUID.randomUUID());s.setObject(2,incoming.key().poolId());s.setString(3,incoming.key().sourceEventId());s.setString(4,incoming.key().phase().name());s.setObject(5,incoming.binding().tenantId());s.setObject(6,incoming.binding().assetId());s.setLong(7,incoming.occurredAt().getEpochSecond());s.setInt(8,incoming.occurredAt().getNano());s.setLong(9,incoming.receivedAt().getEpochSecond());s.setInt(10,incoming.receivedAt().getNano());s.setBytes(11,incoming.payload());
                    try(var r=s.executeQuery()){inserted=r.next();}
                }
                // Separate READ COMMITTED statement observes a concurrently committed winning insert.
                Receipt receipt;
                try(var s=c.prepareStatement("SELECT * FROM ONLY public.mop_raw_monitoring_event WHERE pool_id=? AND source_event_id=? AND source_phase=?")){
                    s.setObject(1,incoming.key().poolId());s.setString(2,incoming.key().sourceEventId());s.setString(3,incoming.key().phase().name());
                    try(var r=s.executeQuery()){
                        if(!r.next())throw unavailable();
                        var stored=read(r);
                        receipt=stored.compare(incoming)==RawMonitoringEvent.Comparison.SAME_CONTENT
                            ?new Receipt(inserted?Outcome.STORED:Outcome.SAME_CONTENT,r.getObject("id",UUID.class),stored.receivedAt())
                            :new Receipt(Outcome.CONFLICT_REQUIRES_REVIEW,null,null);
                    }
                }
        return receipt;
    }
    private static RawMonitoringEvent read(ResultSet r)throws SQLException{
        if(!"UNVERIFIED".equals(r.getString("source_authorization_status"))||!"STORED_ONLY".equals(r.getString("storage_status")))throw unavailable();
        var event=new RawMonitoringEvent(new RawMonitoringEvent.Key(r.getObject("pool_id",UUID.class),r.getString("source_event_id"),RawMonitoringEvent.Phase.valueOf(r.getString("source_phase"))),new RawMonitoringEvent.Binding(r.getObject("tenant_id",UUID.class),r.getObject("asset_id",UUID.class)),Instant.ofEpochSecond(r.getLong("occurred_epoch_second"),r.getInt("occurred_nano")),Instant.ofEpochSecond(r.getLong("received_epoch_second"),r.getInt("received_nano")),r.getBytes("payload"));
        if(!event.payloadSha256().equals(r.getString("payload_sha256"))||!event.contentFingerprint().equals(r.getString("content_fingerprint")))throw unavailable();
        return event;
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("Raw monitoring event storage unavailable; durable result unconfirmed");}
}
