package cn.mop.exportworker;

import cn.mop.persistence.*;
import cn.mop.domain.assetexport.AssetExportSnapshotRepository;
import cn.mop.domain.DomainException;
import com.zaxxer.hikari.*;
import java.time.OffsetDateTime;

final class ExportPreparationQueueApplication {
    static void run(ExportPreparationQueueSettings settings)throws Exception{
        var config=new HikariConfig();config.setJdbcUrl(settings.jdbcUrl());config.setUsername(settings.user());config.setPassword(settings.password());config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setConnectionTimeout(5000);config.setValidationTimeout(3000);config.setInitializationFailTimeout(5000);
        config.addDataSourceProperty("connectTimeout","5");config.addDataSourceProperty("socketTimeout","20");config.addDataSourceProperty("tcpKeepAlive","true");config.addDataSourceProperty("ApplicationName","mop-export-preparation-queue");
        try(var pool=cn.mop.persistence.RuntimeDataSources.openRestrictedTest(config)){
            try(var c=pool.getConnection();var statement=c.prepareStatement("SELECT clock_timestamp()")){c.setReadOnly(true);statement.setQueryTimeout(5);try(var r=statement.executeQuery()){if(!r.next()||settings.acceptedAfter().isAfter(r.getObject(1,OffsetDateTime.class).toInstant()))throw new IllegalStateException("Queue cutoff is after database time");}}
            var mapper=ExportPreparationWorkerApplication.mapper();var platform=new PostgresPlatformRepository(pool,mapper);var jobs=new PostgresAssetExportRepository(pool,platform);var source=new PostgresManualAssetMetadataRepository(pool,platform,mapper);
            AssetExportSnapshotRepository guarded=command->{if(!source.readinessProblems().isEmpty())throw new DomainException(503,"EXPORT_SOURCE_NOT_READY","Designated export source unavailable");return source.select(command);};
            var runtime=new ExportPreparationQueueRuntime(()->platform.dependencyReady()&&jobs.readinessProblems().isEmpty()&&source.readinessProblems().isEmpty(),()->ExportPreparationQueueRuntime.processOne(jobs,jobs,guarded,settings.workerId(),settings.lease(),settings.acceptedAfter()));
            Thread shutdown=new Thread(runtime::stop,"mop-export-queue-shutdown");Runtime.getRuntime().addShutdownHook(shutdown);
            try{runtime.start();System.out.println("{\"status\":\"EXPORT_PREPARATION_QUEUE_RUNNING\",\"published\":false,\"automatic_migrations\":false}");runtime.await();}
            finally{runtime.stop();try{Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException shuttingDown){}}
        }
    }
}
