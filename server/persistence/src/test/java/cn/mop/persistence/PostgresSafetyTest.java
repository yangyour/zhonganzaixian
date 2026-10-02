package cn.mop.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class PostgresSafetyTest {
    @Test void nestedSessionSecretsCannotEnterReplayRecords()throws Exception{
        ObjectMapper mapper=new ObjectMapper();
        for(String value:new String[]{"{\"csrf_token\":\"value\"}","{\"nested\":[{\"refresh_token\":\"value\"}]}","{\"ACCESS_TOKEN\":\"value\"}"})
            assertThrows(IllegalStateException.class,()->PostgresPlatformRepository.rejectSessionSecrets(mapper.readTree(value)));
        assertDoesNotThrow(()->PostgresPlatformRepository.rejectSessionSecrets(mapper.readTree("{\"tenant_id\":\"test\",\"name\":\"draft\"}")));
    }
    @Test void disconnectedReadinessNeverFallsBackToFixture(){
        DataSource disconnected=new DataSource(){
            public Connection getConnection()throws SQLException{throw new SQLException("offline");}
            public Connection getConnection(String user,String password)throws SQLException{return getConnection();}
            public PrintWriter getLogWriter(){return null;} public void setLogWriter(PrintWriter writer){}
            public void setLoginTimeout(int seconds){} public int getLoginTimeout(){return 0;}
            public Logger getParentLogger(){return Logger.getGlobal();}
            public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException();} public boolean isWrapperFor(Class<?> type){return false;}
        };
        PostgresPlatformRepository repository=new PostgresPlatformRepository(disconnected,new ObjectMapper());
        assertFalse(repository.dependencyReady());assertTrue(repository.metadata().durable());
        cn.mop.domain.DomainException exception=assertThrows(cn.mop.domain.DomainException.class,()->repository.list("tenant"));
        assertEquals(503,exception.status);assertEquals("UPSTREAM_UNAVAILABLE",exception.code);
        var dependencies=new PostgresAssetDependencyRepository(disconnected,repository);
        assertEquals(java.util.List.of("ASSET_DEPENDENCY_SCHEMA_OR_ACCESS_UNAVAILABLE"),dependencies.readinessProblems());
        assertEquals(503,assertThrows(cn.mop.domain.DomainException.class,()->dependencies.current("tenant","actor")).status);
        assertThrows(IllegalStateException.class,()->dependencies.append("tenant","actor",0,java.util.List.of(),"trace"));
    }
}
