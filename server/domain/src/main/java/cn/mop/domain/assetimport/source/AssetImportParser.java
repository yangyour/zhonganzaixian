package cn.mop.domain.assetimport.source;
import java.nio.file.Path;
/** The input is an owned local copy of exact source bytes; the parser has no credentials or network dependency. */
public interface AssetImportParser { ParsedArtifact parse(Path input,ParseSelection selection); }
