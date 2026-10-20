package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.TicketLifecycle;
import cn.mop.domain.ticket.TicketLifecycleRepository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** No JDBC doubles are reported as PostgreSQL integration. Tests only fail-closed entry boundaries. */
class PostgresTicketLifecycleBoundaryTest {
    private static final String TICKET="74000000-0000-4000-8000-000000000002";
    private static final Access ACCESS=new Access("74000000-0000-4000-8000-000000000003","74000000-0000-4000-8000-000000000001",1,"synthetic");
    private final AtomicInteger connections=new AtomicInteger();
    private final DataSource source=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
        if(m.getName().equals("getConnection")){connections.incrementAndGet();throw new SQLException("synthetic unavailable");}
        if(m.getName().equals("toString"))return "synthetic-data-source";
        throw new UnsupportedOperationException(m.getName());
    });
    private PostgresTicketLifecycleRepository repository(boolean enabled){
        var mapper=new ObjectMapper();return new PostgresTicketLifecycleRepository(source,new PostgresPlatformRepository(source,mapper),mapper,enabled);
    }
    @Test void disabledDependencyDoesNotOpenDatabaseOrPretendSuccess(){
        var r=repository(false);
        assertEquals("CONFIG_PENDING",assertThrows(DomainException.class,()->r.get(ACCESS,TICKET)).code);
        assertEquals("CONFIG_PENDING",assertThrows(DomainException.class,()->r.sla(ACCESS,TICKET)).code);
        assertEquals("CONFIG_PENDING",assertThrows(DomainException.class,()->r.solution(ACCESS,TICKET,"a".repeat(64))).code);
        assertEquals("CONFIG_PENDING",assertThrows(DomainException.class,()->r.start(ACCESS,TICKET,null,null,null)).code);
        assertEquals("CONFIG_PENDING",assertThrows(DomainException.class,()->r.startConfigured(ACCESS,TICKET,"P1")).code);
        assertEquals("CONFIG_PENDING",assertThrows(DomainException.class,()->r.act(ACCESS,TICKET,null)).code);
        assertEquals(java.util.List.of("TICKET_LIFECYCLE_DISABLED"),r.readinessProblems());
        assertEquals(0,connections.get());
    }
    @Test void writesCannotEscapeAnOwnedIdempotentCommandTransaction(){
        var r=repository(true);
        assertThrows(IllegalStateException.class,()->r.start(ACCESS,TICKET,"{}",null,null));
        assertThrows(IllegalStateException.class,()->r.startConfigured(ACCESS,TICKET,"P1"));
        assertThrows(IllegalStateException.class,()->r.act(ACCESS,TICKET,new Action(TicketLifecycle.Action.ACCEPT,1,null,null,null,false)));
        assertEquals(0,connections.get());
    }
    @Test void databaseFailureIsUnavailableNotEmptyHistory(){
        var r=repository(true);
        assertEquals(503,assertThrows(DomainException.class,()->r.get(ACCESS,TICKET)).status);
        assertEquals(503,assertThrows(DomainException.class,()->r.history(ACCESS,TICKET,10,10)).status);
        assertEquals(java.util.List.of("TICKET_LIFECYCLE_SCHEMA_UNAVAILABLE"),r.readinessProblems());
    }
    @Test void invalidScopeAndPagingFailBeforeDatabase(){
        var r=repository(true);
        assertEquals(400,assertThrows(DomainException.class,()->r.get(ACCESS,"not-a-uuid")).status);
        assertEquals(400,assertThrows(DomainException.class,()->r.history(ACCESS,TICKET,0,1)).status);
        assertEquals(400,assertThrows(DomainException.class,()->r.history(ACCESS,TICKET,10,102)).status);
        assertEquals(0,connections.get());
    }
    @Test void userPathCannotInvokeSystemAutomaticClosure(){
        var r=repository(true);
        assertEquals(403,assertThrows(DomainException.class,()->r.act(ACCESS,TICKET,new Action(TicketLifecycle.Action.AUTO_CLOSE,1,null,null,null,false))).status);
        assertEquals(0,connections.get());
    }
}
