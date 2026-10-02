package cn.mop.importworker;

import cn.mop.domain.assetimport.source.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Serial consumer of this project's asset-import jobs only, with PostgreSQL lease fencing. */
final class AssetImportWorkerRuntime implements SmartLifecycle {
    private static final Logger LOG=LoggerFactory.getLogger(AssetImportWorkerRuntime.class);
    private static final long IDLE_INITIAL=250,IDLE_MAX=5000,FAILURE_INITIAL=1000,FAILURE_MAX=30000,STOP_TIMEOUT=35000;
    @FunctionalInterface interface ImportProcessor {boolean process(String owner,AssetImportObjectStore store,AssetImportParser parser);}
    private final BooleanSupplier repositoryReady,parserAvailable;
    private final ImportProcessor processor;
    private final AssetImportObjectStore store;
    private final AssetImportParser parser;
    private final Runnable stopParser;
    private final long stopTimeoutMillis;
    private final String owner="asset-import-worker-"+UUID.randomUUID();
    private volatile boolean running;
    private Thread thread;
    AssetImportWorkerRuntime(AssetImportSourceRepository repository,AssetImportObjectStore store,AssetImportParserProcess parser){
        this(repository::ready,repository::processNext,store,parser,parser::available,parser::close);
    }
    AssetImportWorkerRuntime(BooleanSupplier repositoryReady,ImportProcessor processor,AssetImportObjectStore store,AssetImportParser parser,BooleanSupplier parserAvailable,Runnable stopParser){
        this(repositoryReady,processor,store,parser,parserAvailable,stopParser,STOP_TIMEOUT);
    }
    AssetImportWorkerRuntime(BooleanSupplier repositoryReady,ImportProcessor processor,AssetImportObjectStore store,AssetImportParser parser,BooleanSupplier parserAvailable,Runnable stopParser,long stopTimeoutMillis){
        this.repositoryReady=Objects.requireNonNull(repositoryReady);this.processor=Objects.requireNonNull(processor);this.store=Objects.requireNonNull(store);
        this.parser=Objects.requireNonNull(parser);this.parserAvailable=Objects.requireNonNull(parserAvailable);this.stopParser=Objects.requireNonNull(stopParser);
        if(stopTimeoutMillis<1||stopTimeoutMillis>STOP_TIMEOUT)throw new IllegalArgumentException("Invalid worker stop bound");this.stopTimeoutMillis=stopTimeoutMillis;
    }
    @Override public synchronized void start(){
        if(running)return;if(thread!=null&&thread.isAlive())throw new IllegalStateException("FAIL_CLOSED: the previous import worker has an in-flight fenced operation");
        boolean ready;try{ready=repositoryReady.getAsBoolean()&&store.ready()&&parserAvailable.getAsBoolean();}catch(RuntimeException unavailable){ready=false;}
        if(!ready)throw new IllegalStateException("FAIL_CLOSED: import worker requires the designated V6 PostgreSQL schema, project import storage and standalone parser to be ready");
        running=true;thread=new Thread(this::poll,"mop-asset-import-worker");thread.setDaemon(false);thread.start();LOG.info("Asset import worker started for project import jobs");
    }
    private void poll(){
        long idle=IDLE_INITIAL,failure=FAILURE_INITIAL;
        try{
            while(running&&!Thread.currentThread().isInterrupted()){
                try{
                    boolean processed=processor.process(owner,store,parser);failure=FAILURE_INITIAL;
                    if(processed)idle=IDLE_INITIAL;else{pause(idle);idle=Math.min(IDLE_MAX,idle*2);}
                }catch(RuntimeException unavailable){if(!running||Thread.currentThread().isInterrupted())break;LOG.warn("Asset import worker dependency unavailable; durable claims pause for retry");pause(failure);failure=Math.min(FAILURE_MAX,failure*2);}
            }
        }finally{running=false;}
    }
    private void pause(long millis){if(!running)return;try{Thread.sleep(millis);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
    @Override public void stop(){
        Thread current;synchronized(this){running=false;current=thread;if(current!=null)current.interrupt();}
        stopParser.run();
        if(current!=null&&current!=Thread.currentThread()){
            try{current.join(stopTimeoutMillis);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}
            if(current.isAlive())LOG.warn("Asset import worker is awaiting its in-flight dependency; its PostgreSQL lease remains fenced");
        }
    }
    @Override public void stop(Runnable callback){try{stop();}finally{callback.run();}}
    @Override public boolean isRunning(){return running;}
    @Override public boolean isAutoStartup(){return true;}
    @Override public int getPhase(){return Integer.MAX_VALUE;}
}
