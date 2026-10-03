package cn.mop.contactworker;
import cn.mop.domain.contact.ContactImportRepository;
import org.slf4j.*;
import org.springframework.context.SmartLifecycle;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
/** Serial contact-only consumer. No local queue, fallback store, role provisioning, dispatch or automatic migration. */
final class ContactImportWorkerRuntime implements SmartLifecycle {
    private static final Logger LOG=LoggerFactory.getLogger(ContactImportWorkerRuntime.class);
    private static final long IDLE_INITIAL=250,IDLE_MAX=5000,FAILURE_INITIAL=1000,FAILURE_MAX=30000,STOP_TIMEOUT=35000;
    @FunctionalInterface interface Processor{boolean run(String owner,Duration lease);}
    private final BooleanSupplier repositoryReady;private final Processor processor;private final Duration lease;private final long stopTimeout;
    private final String owner="contact-import-worker-"+UUID.randomUUID();
    private volatile boolean running;private volatile Boolean dependencyReady;private Thread thread;
    ContactImportWorkerRuntime(ContactImportRepository repository,Duration lease){this(()->repository.readinessProblems().isEmpty(),(owner,duration)->{var claim=repository.claimNext(owner,duration);if(claim.isEmpty())return false;repository.process(claim.get());return true;},lease,STOP_TIMEOUT);}
    ContactImportWorkerRuntime(BooleanSupplier ready,Processor processor,Duration lease,long stopTimeout){this.repositoryReady=Objects.requireNonNull(ready);this.processor=Objects.requireNonNull(processor);this.lease=Objects.requireNonNull(lease);if(lease.toMillis()<1000||lease.toMillis()>3600000||lease.getNano()%1000000!=0||stopTimeout<1||stopTimeout>STOP_TIMEOUT)throw new IllegalArgumentException("Invalid contact worker timing boundary");this.stopTimeout=stopTimeout;}
    @Override public synchronized void start(){if(running)return;if(thread!=null&&thread.isAlive())throw new IllegalStateException("FAIL_CLOSED: previous contact worker has an in-flight fenced operation");boolean ready;try{ready=repositoryReady.getAsBoolean();}catch(RuntimeException failure){ready=false;}dependencyReady=ready;if(!ready)throw new IllegalStateException("FAIL_CLOSED: contact import worker requires reviewed V19 project schema and restricted PostgreSQL access");running=true;thread=new Thread(this::poll,"mop-contact-import-worker");thread.setDaemon(false);thread.start();LOG.info("Dedicated contact import worker started; only contact jobs are eligible");}
    private void poll(){long idle=IDLE_INITIAL,failure=FAILURE_INITIAL;try{while(running&&!Thread.currentThread().isInterrupted()){try{if(!repositoryReady.getAsBoolean()){dependencyReady=false;pause(failure);failure=Math.min(FAILURE_MAX,failure*2);continue;}dependencyReady=true;boolean attempted=processor.run(owner,lease);failure=FAILURE_INITIAL;if(attempted)idle=IDLE_INITIAL;else{pause(idle);idle=Math.min(IDLE_MAX,idle*2);}}catch(RuntimeException unavailable){dependencyReady=false;if(!running||Thread.currentThread().isInterrupted())break;LOG.warn("Contact import dependency unavailable; durable fenced jobs remain unconfirmed");pause(failure);failure=Math.min(FAILURE_MAX,failure*2);}}}finally{running=false;dependencyReady=null;}}
    private void pause(long millis){if(!running)return;try{Thread.sleep(millis);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
    @Override public void stop(){Thread current;synchronized(this){running=false;current=thread;if(current!=null)current.interrupt();}if(current!=null&&current!=Thread.currentThread()){try{current.join(stopTimeout);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}if(current.isAlive())LOG.warn("Contact import worker awaits its in-flight dependency; lease fencing remains required");}dependencyReady=null;}
    @Override public void stop(Runnable callback){try{stop();}finally{callback.run();}}
    @Override public boolean isRunning(){return running;}
    Boolean dependencyReady(){return dependencyReady;}
    @Override public boolean isAutoStartup(){return true;}
    @Override public int getPhase(){return Integer.MAX_VALUE;}
}
