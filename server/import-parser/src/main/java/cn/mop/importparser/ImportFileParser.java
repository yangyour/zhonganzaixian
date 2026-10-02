package cn.mop.importparser;

import cn.mop.domain.assetimport.source.*;
import java.nio.file.*;
import java.io.IOException;

public final class ImportFileParser implements AssetImportParser {
    public static final String VERSION="poi-5.5.1-csv-1.14.1-parser-v1";
    @Override public ParsedArtifact parse(Path input,ParseSelection selection){
        if(input==null||selection==null)throw new ParseFailure(ParseFailure.Code.INVALID_REQUEST);
        try{if(!Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS)||Files.size(input)<1||Files.size(input)>ImportLimits.MAX_SOURCE_BYTES)throw new ParseFailure(ParseFailure.Code.RESOURCE_LIMIT);}
        catch(IOException unavailable){throw new ParseFailure(ParseFailure.Code.INVALID_REQUEST);}
        ParseBudget budget=new ParseBudget();
        try{return selection.declaredFormat()==ImportFormat.CSV?CsvSourceParser.parse(input,selection,budget):XlsxSourceParser.parse(input,selection,budget);}
        catch(ParseFailure safe){throw safe;}
        catch(Exception unsafe){throw new ParseFailure(selection.declaredFormat()==ImportFormat.CSV?ParseFailure.Code.INVALID_CSV:ParseFailure.Code.INVALID_XLSX);}
    }
}
