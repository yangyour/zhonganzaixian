package cn.mop.domain.assetimport.source;

/** Public messages contain no input text, path, library exception or credentials. */
public final class ParseFailure extends RuntimeException {
    public enum Code { INVALID_REQUEST,UNSUPPORTED_FORMAT,INVALID_ENCODING,INVALID_CSV,INVALID_XLSX,SELECTION_OUT_OF_RANGE,HEADER_MISSING,EMPTY_DATA,TOO_MANY_ROWS,RESOURCE_LIMIT,FORMULA_NOT_ALLOWED,EXTERNAL_LINK_NOT_ALLOWED,MACRO_NOT_ALLOWED,ENCRYPTED_FILE_NOT_ALLOWED,INTERNAL_PARSER_FAILURE }
    private final Code code;
    public ParseFailure(Code code){super("Asset import parsing failed: "+code.name());this.code=code;}
    public Code code(){return code;}
}
