package cn.mop.importworker;

import cn.mop.domain.assetimport.source.*;
import cn.mop.domain.assetimport.source.ParsedArtifact.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.JarFile;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One owned run per parse. No shell, inherited credentials, raw logs, or in-process parser. */
final class AssetImportParserProcess implements AssetImportParser,AutoCloseable {
    static final int MAX_STDOUT_BYTES=16*1024*1024,MAX_STDERR_BYTES=64*1024,MAX_JSON_TOKENS=600_000;
    static final long TIMEOUT_MILLIS=30_000;
    private static final String PARSER_VERSION="poi-5.5.1-csv-1.14.1-parser-v1";
    private static final String MAIN_CLASS="cn.mop.importparser.ParserMain";
    private static final ObjectMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(ImportLimits.MAX_CELL_CHARS).maxNumberLength(32).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    @FunctionalInterface interface ProcessLauncher {Process start(ProcessBuilder builder)throws IOException;}
    private final Path jar,project,javaBinary;
    private final long timeoutMillis;
    private final Map<String,String> parentEnvironment;
    private final ProcessLauncher launcher;
    private final Object lifecycle=new Object();
    private volatile Process active;
    private volatile boolean closed;

    AssetImportParserProcess(Path jar){this(jar,TIMEOUT_MILLIS,System.getenv(),ProcessBuilder::start);}
    AssetImportParserProcess(Path jar,long timeoutMillis,Map<String,String> parentEnvironment,ProcessLauncher launcher){
        if(jar==null||!jar.isAbsolute()||timeoutMillis<1||timeoutMillis>TIMEOUT_MILLIS)throw configuration();
        this.jar=jar.normalize();this.project=projectFor(this.jar);this.timeoutMillis=timeoutMillis;
        this.parentEnvironment=Map.copyOf(parentEnvironment);this.launcher=Objects.requireNonNull(launcher);
        String executable=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")?"java.exe":"java";
        this.javaBinary=Path.of(System.getProperty("java.home"),"bin",executable).toAbsolutePath().normalize();
    }
    private static IllegalStateException configuration(){return new IllegalStateException("FAIL_CLOSED: designated standalone import parser configuration is unavailable");}
    private static Path projectFor(Path jar){
        Path target=jar.getParent(),module=target==null?null:target.getParent(),server=module==null?null:module.getParent();
        if(target==null||module==null||server==null||server.getParent()==null||!"target".equals(target.getFileName().toString())||!"import-parser".equals(module.getFileName().toString())||!"server".equals(server.getFileName().toString())||!jar.getFileName().toString().matches("[A-Za-z0-9._-]{1,200}\\.jar"))throw configuration();
        return server.getParent();
    }
    static boolean legalJar(Path jar){
        try{
            if(jar==null||!jar.isAbsolute())return false;Path normalized=jar.normalize(),project=projectFor(normalized);
            noLinks(project,normalized);if(!Files.isRegularFile(normalized,LinkOption.NOFOLLOW_LINKS)||Files.size(normalized)<1)return false;
            try(JarFile content=new JarFile(normalized.toFile())){return content.getManifest()!=null&&MAIN_CLASS.equals(content.getManifest().getMainAttributes().getValue("Main-Class"))&&content.getJarEntry("cn/mop/importparser/ParserMain.class")!=null;}
        }catch(Exception invalid){return false;}
    }
    private boolean configured(){return !closed&&legalJar(jar)&&Files.isRegularFile(javaBinary);}
    /** A real no-credential child must reject a harmless empty header using the public protocol. */
    boolean available(){
        if(!configured())return false;Path probe=null;
        try{
            probe=createRun();Path source=probe.resolve("probe");Files.writeString(source,"\n",StandardOpenOption.CREATE_NEW);
            parse(source,new ParseSelection(ImportFormat.CSV,"UTF-8",",",null,1));return false;
        }catch(ParseFailure failure){return failure.code()==ParseFailure.Code.HEADER_MISSING;}
        catch(Exception unavailable){return false;}
        finally{if(probe!=null)cleanup(probe);}
    }
    private static void noLinks(Path root,Path path)throws IOException{
        if(!path.startsWith(root)||linkOrSpecial(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException();
        Path current=root;for(Path segment:root.relativize(path)){current=current.resolve(segment);if(linkOrSpecial(current))throw new IOException();}
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!path.toRealPath().startsWith(root.toRealPath()))throw new IOException();
    }
    private static boolean linkOrSpecial(Path path)throws IOException{if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return false;BasicFileAttributes attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return attributes.isSymbolicLink()||attributes.isOther();}
    private Path createRun()throws IOException{
        Path local=project.resolve(".local"),runs=local.resolve("import-parser-runs");
        for(Path directory:List.of(local,runs)){
            noLinks(project,directory);
            if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))try{Files.createDirectory(directory);}catch(FileAlreadyExistsException raced){/* Recheck the exact path. */}
            if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException();noLinks(project,directory);
        }
        Path run=runs.resolve(UUID.randomUUID().toString());Files.createDirectory(run);
        try{noLinks(project,run);Files.createDirectory(run.resolve("tmp"));return run;}catch(IOException invalid){cleanup(run);throw invalid;}
    }
    private static Path copySource(Path input,Path run)throws IOException{
        if(input==null||!Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS)||Files.size(input)<1||Files.size(input)>ImportLimits.MAX_SOURCE_BYTES)throw new ParseFailure(ParseFailure.Code.INVALID_REQUEST);
        Path source=run.resolve("source");long bytes=0;byte[] buffer=new byte[8192];
        try(InputStream original=Files.newInputStream(input,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);OutputStream staged=Files.newOutputStream(source,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            for(int count;(count=original.read(buffer))!=-1;){bytes+=count;if(bytes>ImportLimits.MAX_SOURCE_BYTES)throw new ParseFailure(ParseFailure.Code.RESOURCE_LIMIT);staged.write(buffer,0,count);}
        }
        if(bytes<1)throw new ParseFailure(ParseFailure.Code.INVALID_REQUEST);return source;
    }
    @Override public synchronized ParsedArtifact parse(Path input,ParseSelection selection){
        if(selection==null)throw new ParseFailure(ParseFailure.Code.INVALID_REQUEST);
        if(!configured()||Thread.currentThread().isInterrupted())throw new ParseFailure(ParseFailure.Code.INTERNAL_PARSER_FAILURE);
        Path run=null;Process child=null;OutputCapture stdout=null;ErrorCapture stderr=null;boolean interrupted=false;
        try{
            run=createRun();Path source=copySource(input,run);
            ProcessBuilder builder=new ProcessBuilder(javaBinary.toString(),"-Xmx128m","-XX:MaxDirectMemorySize=32m","-Djava.io.tmpdir="+run.resolve("tmp"),"-jar",jar.toString());
            builder.directory(run.toFile());builder.environment().clear();
            String systemRoot=parentEnvironment.get("SystemRoot");if(systemRoot==null)systemRoot=parentEnvironment.get("SYSTEMROOT");if(systemRoot!=null&&!systemRoot.isBlank())builder.environment().put("SystemRoot",systemRoot);
            builder.environment().put("TEMP",run.resolve("tmp").toString());builder.environment().put("TMP",run.resolve("tmp").toString());
            // Publish a launched child atomically with respect to close(). A native process may
            // already exist while launcher.start has not returned; close must await that handoff.
            synchronized(lifecycle){
                if(closed)throw new ParseFailure(ParseFailure.Code.RESOURCE_LIMIT);
                child=launcher.start(builder);active=child;
            }
            if(closed){terminate(child);throw new ParseFailure(ParseFailure.Code.RESOURCE_LIMIT);}
            AtomicReference<ParseFailure.Code> overflow=new AtomicReference<>();
            stdout=new OutputCapture(child,overflow);stderr=new ErrorCapture(child,overflow);stdout.start();stderr.start();
            try(OutputStream request=child.getOutputStream()){JSON.writeValue(request,Map.of("path",source.toString(),"selection",selection));}
            boolean exited;
            try{exited=child.waitFor(timeoutMillis,TimeUnit.MILLISECONDS);}catch(InterruptedException stopped){interrupted=true;terminate(child);throw new ParseFailure(ParseFailure.Code.RESOURCE_LIMIT);}
            if(!exited){terminate(child);throw new ParseFailure(ParseFailure.Code.RESOURCE_LIMIT);}
            join(stdout,child);join(stderr,child);
            if(overflow.get()!=null)throw new ParseFailure(overflow.get());
            if(stdout.failure||stderr.failure||child.exitValue()!=0)throw new ParseFailure(ParseFailure.Code.INTERNAL_PARSER_FAILURE);
            if(closed||Thread.currentThread().isInterrupted())throw new ParseFailure(ParseFailure.Code.RESOURCE_LIMIT);
            return decode(stdout.bytes,selection);
        }catch(ParseFailure safe){throw safe;}
        catch(Exception unsafe){throw new ParseFailure(ParseFailure.Code.INTERNAL_PARSER_FAILURE);}
        finally{
            if(child!=null){if(child.isAlive())terminate(child);closeStreams(child);if(stdout!=null)joinUninterruptibly(stdout);if(stderr!=null)joinUninterruptibly(stderr);}
            active=null;
            if(interrupted)Thread.currentThread().interrupt();
            if(run!=null)cleanup(run);
        }
    }
    private static void join(Thread reader,Process child)throws InterruptedException{
        try{reader.join(5000);if(reader.isAlive()){closeStreams(child);reader.join(1000);if(reader.isAlive())throw new ParseFailure(ParseFailure.Code.INTERNAL_PARSER_FAILURE);}}
        catch(InterruptedException stopped){terminate(child);Thread.currentThread().interrupt();throw stopped;}
    }
    private static void joinUninterruptibly(Thread reader){boolean interrupted=false;while(reader.isAlive())try{reader.join();}catch(InterruptedException stopped){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
    /** Do not report timeout or shutdown completion while the native child is still alive. */
    private static void terminate(Process child){
        boolean interrupted=false;if(child.isAlive())child.destroyForcibly();
        while(child.isAlive())try{child.waitFor();}catch(InterruptedException stopped){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
    }
    private static void closeStreams(Process child){for(Closeable stream:List.of(child.getOutputStream(),child.getInputStream(),child.getErrorStream()))try{stream.close();}catch(IOException ignored){}}
    private void cleanup(Path run){
        try{
            Path runs=project.resolve(".local/import-parser-runs");
            if(!run.getParent().equals(runs)||!run.getFileName().toString().matches("[0-9a-f-]{36}"))throw new IOException();
            noLinks(project,runs);
            if(linkOrSpecial(run)){Files.deleteIfExists(run);return;}
            Files.walkFileTree(run,new SimpleFileVisitor<>(){
                @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attributes)throws IOException{if(attributes.isSymbolicLink()||attributes.isOther()){Files.delete(directory);return FileVisitResult.SKIP_SUBTREE;}return FileVisitResult.CONTINUE;}
                @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
                @Override public FileVisitResult postVisitDirectory(Path directory,IOException error)throws IOException{if(error!=null)throw error;Files.delete(directory);return FileVisitResult.CONTINUE;}
            });
        }catch(IOException invalid){throw new ParseFailure(ParseFailure.Code.INTERNAL_PARSER_FAILURE);}
    }
    @Override public void close(){Process child;synchronized(lifecycle){closed=true;child=active;}if(child!=null)terminate(child);}
    private static final class OutputCapture extends Thread {
        private final Process child;private final AtomicReference<ParseFailure.Code> overflow;byte[] bytes;boolean failure;
        OutputCapture(Process child,AtomicReference<ParseFailure.Code> overflow){super("mop-import-parser-stdout");this.child=child;this.overflow=overflow;}
        @Override public void run(){try{bytes=child.getInputStream().readNBytes(MAX_STDOUT_BYTES+1);if(bytes.length>MAX_STDOUT_BYTES){overflow.set(ParseFailure.Code.RESOURCE_LIMIT);child.destroyForcibly();}}catch(IOException unavailable){failure=true;}}
    }
    private static final class ErrorCapture extends Thread {
        private final Process child;private final AtomicReference<ParseFailure.Code> overflow;boolean failure;
        ErrorCapture(Process child,AtomicReference<ParseFailure.Code> overflow){super("mop-import-parser-stderr");this.child=child;this.overflow=overflow;}
        @Override public void run(){byte[] discard=new byte[1024];int total=0;try{for(int count;(count=child.getErrorStream().read(discard))!=-1;){if((total+=count)>MAX_STDERR_BYTES){overflow.set(ParseFailure.Code.RESOURCE_LIMIT);child.destroyForcibly();break;}}}catch(IOException unavailable){failure=true;}}
    }
    private static ParsedArtifact decode(byte[] bytes,ParseSelection selection){
        try{
            try(JsonParser tokens=JSON.createParser(bytes)){int count=0;for(JsonToken token;(token=tokens.nextToken())!=null;){if(++count>MAX_JSON_TOKENS||token==JsonToken.FIELD_NAME&&tokens.currentName().length()>64)throw invalidProtocol();if(token==JsonToken.VALUE_STRING)tokens.getTextLength();}}
            JsonNode response=JSON.readTree(bytes);if(response==null||!response.isObject()||!response.path("status").isTextual())throw invalidProtocol();
            if("FAILED".equals(response.path("status").asText())){
                exact(response,"status","code","message");
                if(!response.path("code").isTextual()||!"Asset import parser rejected the source safely".equals(response.path("message").asText()))throw invalidProtocol();
                ParseFailure.Code code;try{code=ParseFailure.Code.valueOf(response.path("code").asText());}catch(RuntimeException invalid){throw invalidProtocol();}throw new ParseFailure(code);
            }
            if(!"SUCCEEDED".equals(response.path("status").asText()))throw invalidProtocol();exact(response,"status","artifact");
            JsonNode tree=response.path("artifact");exact(tree,"state","detected_format","sheets","selected_sheet_index","header_row","date_epoch","columns","rows","parser_version","resource_policy_version");
            array(tree.path("sheets"),ImportLimits.MAX_SHEETS);array(tree.path("columns"),ImportLimits.MAX_COLUMNS);array(tree.path("rows"),ImportLimits.MAX_DATA_ROWS);
            for(JsonNode sheet:tree.path("sheets")){exact(sheet,"index","name","hidden");integer(sheet.path("index"));text(sheet.path("name"));if(!sheet.path("hidden").isBoolean())throw invalidProtocol();}
            for(JsonNode column:tree.path("columns")){exact(column,"index","header","target_suggestions");integer(column.path("index"));nullableText(column.path("header"));array(column.path("target_suggestions"),15);for(JsonNode suggestion:column.path("target_suggestions"))text(suggestion);}
            for(JsonNode row:tree.path("rows")){
                exact(row,"logical_record_number","source_row_number","physical_start_line","physical_end_line","cells");integer(row.path("logical_record_number"));integer(row.path("source_row_number"));nullableInteger(row.path("physical_start_line"));nullableInteger(row.path("physical_end_line"));array(row.path("cells"),ImportLimits.MAX_COLUMNS);
                for(JsonNode cell:row.path("cells")){exact(cell,"column_index","kind","lexical","display","style_index","format_code");integer(cell.path("column_index"));text(cell.path("kind"));text(cell.path("lexical"));text(cell.path("display"));nullableInteger(cell.path("style_index"));nullableText(cell.path("format_code"));}
            }
            text(tree.path("state"));text(tree.path("detected_format"));text(tree.path("date_epoch"));text(tree.path("parser_version"));text(tree.path("resource_policy_version"));nullableInteger(tree.path("selected_sheet_index"));nullableInteger(tree.path("header_row"));
            ParsedArtifact artifact=JSON.treeToValue(tree,ParsedArtifact.class);validate(artifact,selection);return artifact;
        }catch(ParseFailure safe){throw safe;}
        catch(Exception invalid){throw invalidProtocol();}
    }
    private static ParseFailure invalidProtocol(){return new ParseFailure(ParseFailure.Code.INTERNAL_PARSER_FAILURE);}
    private static void exact(JsonNode object,String... fields){if(!object.isObject()||object.size()!=fields.length)throw invalidProtocol();Set<String> actual=new HashSet<>();object.fieldNames().forEachRemaining(actual::add);if(!actual.equals(Set.of(fields)))throw invalidProtocol();}
    private static void array(JsonNode value,int limit){if(!value.isArray()||value.size()>limit)throw invalidProtocol();}
    private static void integer(JsonNode value){if(!value.isIntegralNumber()||!value.canConvertToLong())throw invalidProtocol();}
    private static void nullableInteger(JsonNode value){if(!value.isNull())integer(value);}
    private static void text(JsonNode value){if(!value.isTextual()||value.textValue().length()>ImportLimits.MAX_CELL_CHARS||value.textValue().codePoints().anyMatch(code->Character.isISOControl(code)&&code!='\t'&&code!='\r'&&code!='\n'))throw invalidProtocol();}
    private static void nullableText(JsonNode value){if(!value.isNull())text(value);}
    private static void validate(ParsedArtifact artifact,ParseSelection selection){
        if(artifact.detectedFormat()!=selection.declaredFormat()||!PARSER_VERSION.equals(artifact.parserVersion())||!ImportLimits.RESOURCE_POLICY_VERSION.equals(artifact.resourcePolicyVersion()))throw invalidProtocol();
        Set<String> names=new HashSet<>();for(int index=0;index<artifact.sheets().size();index++){var sheet=artifact.sheets().get(index);if(sheet.index()!=index||sheet.name().isEmpty()||!names.add(sheet.name().toLowerCase(Locale.ROOT)))throw invalidProtocol();}
        if(selection.declaredFormat()==ImportFormat.CSV){if(artifact.sheets().size()!=1||!"CSV".equals(artifact.sheets().get(0).name())||artifact.sheets().get(0).hidden()||artifact.dateEpoch()!=DateEpoch.NOT_APPLICABLE||artifact.state()!=State.PARSED||!Integer.valueOf(0).equals(artifact.selectedSheetIndex()))throw invalidProtocol();}
        else if(artifact.dateEpoch()==DateEpoch.NOT_APPLICABLE)throw invalidProtocol();
        if(selection.declaredFormat()==ImportFormat.XLSX&&(selection.sheetIndex()==null||selection.headerRow()==null)){
            if(artifact.state()!=State.SELECTION_REQUIRED||artifact.selectedSheetIndex()!=null||artifact.headerRow()!=null||!artifact.rows().isEmpty()||!artifact.columns().isEmpty())throw invalidProtocol();return;
        }
        if(artifact.state()!=State.PARSED||!Objects.equals(artifact.headerRow(),selection.headerRow())||artifact.selectedSheetIndex()==null||artifact.selectedSheetIndex()<0||artifact.selectedSheetIndex()>=artifact.sheets().size()||selection.declaredFormat()==ImportFormat.XLSX&&!Objects.equals(artifact.selectedSheetIndex(),selection.sheetIndex())||artifact.rows().isEmpty())throw invalidProtocol();
        if(artifact.columns().stream().allMatch(column->column.header()==null||column.header().isEmpty())||!artifact.columns().equals(ParsedArtifact.columns(artifact.columns().stream().map(SourceColumn::header).toList())))throw invalidProtocol();
        long previousLogical=0,previousPhysical=0;int previousRow=artifact.headerRow();
        for(int index=0;index<artifact.rows().size();index++){
            SourceRow row=artifact.rows().get(index);if(row.sourceRowNumber()<=previousRow||row.logicalRecordNumber()<=previousLogical||row.cells().isEmpty()||row.cells().stream().allMatch(cell->cell.display().isEmpty()))throw invalidProtocol();previousRow=row.sourceRowNumber();previousLogical=row.logicalRecordNumber();
            if(selection.declaredFormat()==ImportFormat.CSV){if(row.logicalRecordNumber()!=row.sourceRowNumber()||row.physicalStartLine()==null||row.physicalEndLine()==null||row.physicalStartLine()<=previousPhysical)throw invalidProtocol();previousPhysical=row.physicalEndLine();}
            else if(row.logicalRecordNumber()!=index+1L||row.physicalStartLine()!=null||row.physicalEndLine()!=null)throw invalidProtocol();
            int previousColumn=-1;for(SourceCell cell:row.cells()){if(cell.columnIndex()<=previousColumn||cell.columnIndex()>=artifact.columns().size())throw invalidProtocol();previousColumn=cell.columnIndex();if(selection.declaredFormat()==ImportFormat.CSV&&(cell.kind()!=CellKind.TEXT||cell.styleIndex()!=null||cell.formatCode()!=null||!cell.lexical().equals(cell.display())))throw invalidProtocol();}
        }
    }
}
