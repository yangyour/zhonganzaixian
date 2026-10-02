package cn.mop.importworker;

import cn.mop.domain.assetimport.source.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class AssetImportParserProcessTest {
    @TempDir Path temporary;
    private Path jar()throws Exception{
        Path jar=temporary.resolve("server/import-parser/target/MOP-FAKE-parser.jar");Files.createDirectories(jar.getParent());Manifest manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class","cn.mop.importparser.ParserMain");
        try(JarOutputStream output=new JarOutputStream(Files.newOutputStream(jar),manifest);InputStream fixture=cn.mop.importparser.ParserMain.class.getResourceAsStream("/cn/mop/importparser/ParserMain.class")){output.putNextEntry(new JarEntry("cn/mop/importparser/ParserMain.class"));fixture.transferTo(output);output.closeEntry();}return jar;
    }
    private static ParseSelection selection(){return new ParseSelection(ImportFormat.CSV,"UTF-8",",",null,1);}
    private Path source(String mode)throws Exception{Path source=temporary.resolve("MOP-FAKE-source");Files.writeString(source,mode);return source;}
    private static Map<String,String> parent(){Map<String,String> environment=new HashMap<>();String root=System.getenv("SystemRoot");if(root!=null)environment.put("SystemRoot",root);environment.put("MOP_IMPORT_S3_SECRET_KEY","PRIVATE-ISOLATED-S3");environment.put("SPRING_DATASOURCE_PASSWORD","PRIVATE-ISOLATED-DB");environment.put("MOP_OIDC_CLIENT_SECRET","PRIVATE-ISOLATED-OIDC");environment.put("JAVA_TOOL_OPTIONS","PRIVATE-ISOLATED-OPTION");environment.put("OBJECT_STORAGE_ACCESS_KEY","PRIVATE-ISOLATED-OSS-ACCESS");environment.put("OBJECT_STORAGE_SECRET_KEY","PRIVATE-ISOLATED-OSS-SECRET");environment.put("OBJECT_STORAGE_SECURITY_TOKEN","PRIVATE-ISOLATED-OSS-TOKEN");return environment;}
    private void emptyRuns()throws Exception{Path runs=temporary.resolve(".local/import-parser-runs");if(Files.exists(runs))try(var children=Files.list(runs)){assertEquals(0,children.count());}}
    @Test void childHasOnlySystemAndOwnedTemporaryEnvironmentAndBoundedHeap()throws Exception{
        AtomicReference<Process> child=new AtomicReference<>();AtomicReference<List<String>> command=new AtomicReference<>();
        try(var parser=new AssetImportParserProcess(jar(),5000,parent(),builder->{command.set(builder.command());assertFalse(builder.environment().containsKey("MOP_IMPORT_S3_SECRET_KEY"));assertFalse(builder.environment().containsKey("SPRING_DATASOURCE_PASSWORD"));assertFalse(builder.environment().containsKey("JAVA_TOOL_OPTIONS"));assertFalse(builder.environment().containsKey("OBJECT_STORAGE_ACCESS_KEY"));assertFalse(builder.environment().containsKey("OBJECT_STORAGE_SECRET_KEY"));assertFalse(builder.environment().containsKey("OBJECT_STORAGE_SECURITY_TOKEN"));Process process=builder.start();child.set(process);return process;})){
            var artifact=parser.parse(source("ENV"),selection());assertEquals("MOP-FAKE-isolated",artifact.rows().get(0).cells().get(0).lexical());assertTrue(command.get().contains("-Xmx128m"));assertTrue(command.get().contains("-XX:MaxDirectMemorySize=32m"));assertEquals("-jar",command.get().get(4));assertFalse(child.get().isAlive());
        }emptyRuns();
    }
    @Test void actualStartupProbeRequiresTheChildProtocolAndDoesNotLeaveOwnedRuns()throws Exception{
        try(var parser=new AssetImportParserProcess(jar(),5000,parent(),ProcessBuilder::start)){assertTrue(parser.available());}emptyRuns();
        Path missing=temporary.resolve("server/import-parser/target/MOP-FAKE-missing.jar");try(var parser=new AssetImportParserProcess(missing)){assertFalse(parser.available());}
    }
    @Test void timeoutIncludingAChildThatAlreadyPrintedSuccessWaitsForNativeExit()throws Exception{
        for(String mode:List.of("TIMEOUT","HANG_AFTER_JSON")){
            AtomicReference<Process> child=new AtomicReference<>();try(var parser=new AssetImportParserProcess(jar(),500,parent(),builder->{Process process=builder.start();child.set(process);return process;})){
                assertEquals(ParseFailure.Code.RESOURCE_LIMIT,assertThrows(ParseFailure.class,()->parser.parse(source(mode),selection())).code());assertFalse(child.get().isAlive());assertFalse(ProcessHandle.of(child.get().pid()).map(ProcessHandle::isAlive).orElse(false));
            }emptyRuns();
        }
    }
    @Test void interruptedParseKillsAndWaitsPreservesInterruptAndCleansOnlyItsRun()throws Exception{
        CountDownLatch started=new CountDownLatch(1);AtomicReference<Process> child=new AtomicReference<>();AtomicBoolean interrupted=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();
        Path sibling=temporary.resolve(".local/import-parser-runs/00000000-0000-4000-8000-000000000099/MOP-FAKE-keep");Files.createDirectories(sibling.getParent());Files.writeString(sibling,"MOP-FAKE unrelated owned run");
        try(var parser=new AssetImportParserProcess(jar(),5000,parent(),builder->{Process process=builder.start();child.set(process);started.countDown();return process;})){
            Path input=source("TIMEOUT");Thread thread=new Thread(()->{try{parser.parse(input,selection());}catch(Throwable safe){failure.set(safe);}finally{interrupted.set(Thread.currentThread().isInterrupted());}});
            thread.start();assertTrue(started.await(3,TimeUnit.SECONDS));thread.interrupt();thread.join(5000);assertFalse(thread.isAlive());assertInstanceOf(ParseFailure.class,failure.get());assertTrue(interrupted.get());assertFalse(child.get().isAlive());assertTrue(Files.exists(sibling));
        }
        try(var runs=Files.list(sibling.getParent().getParent())){assertEquals(List.of(sibling.getParent()),runs.toList());}
    }
    @Test void rawStderrAndOversizedStdoutAreDiscardedAndTerminateTheNativeChild()throws Exception{
        for(String mode:List.of("STDERR","STDOUT")){
            AtomicReference<Process> child=new AtomicReference<>();try(var parser=new AssetImportParserProcess(jar(),5000,parent(),builder->{Process process=builder.start();child.set(process);return process;})){
                var failure=assertThrows(ParseFailure.class,()->parser.parse(source(mode),selection()));assertEquals(ParseFailure.Code.RESOURCE_LIMIT,failure.code());assertFalse(failure.getMessage().contains("PRIVATE"));assertNull(failure.getCause());assertFalse(child.get().isAlive());
            }emptyRuns();
        }
    }
    @Test void closeTerminatesAnActiveNativeChildAndDisablesFurtherClaims()throws Exception{
        CountDownLatch started=new CountDownLatch(1);AtomicReference<Process> child=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        try(var parser=new AssetImportParserProcess(jar(),5000,parent(),builder->{Process process=builder.start();child.set(process);started.countDown();return process;})){
            Path input=source("TIMEOUT");Thread thread=new Thread(()->{try{parser.parse(input,selection());}catch(Throwable safe){failure.set(safe);}});thread.start();assertTrue(started.await(3,TimeUnit.SECONDS));parser.close();assertFalse(child.get().isAlive());thread.join(5000);assertFalse(thread.isAlive());assertInstanceOf(ParseFailure.class,failure.get());assertFalse(parser.available());assertThrows(ParseFailure.class,()->parser.parse(input,selection()));
        }emptyRuns();
    }
    @Test void duplicateTrailingAndInconsistentSchemasCannotBecomeArtifacts()throws Exception{
        for(String mode:List.of("TWODOCS","DUPLICATE","WRONG_ROW","WRONG_SUGGESTION","BAD_ENUM","EXTRA","MANY_NODES"))try(var parser=new AssetImportParserProcess(jar(),5000,parent(),ProcessBuilder::start)){
            var failure=assertThrows(ParseFailure.class,()->parser.parse(source(mode),selection()));assertEquals(ParseFailure.Code.INTERNAL_PARSER_FAILURE,failure.code());assertFalse(failure.getMessage().contains("PRIVATE"));assertNull(failure.getCause());emptyRuns();
        }
    }
    @Test void jarDestinationsOutsideProjectTargetAndMissingExecutableMetadataFailClosed()throws Exception{
        assertThrows(IllegalStateException.class,()->new AssetImportParserProcess(Path.of("relative.jar")));assertThrows(IllegalStateException.class,()->new AssetImportParserProcess(temporary.resolve("other.jar")));
        Path malformed=jar();Files.writeString(malformed,"MOP-FAKE not a jar");assertFalse(AssetImportParserProcess.legalJar(malformed));
    }
}
