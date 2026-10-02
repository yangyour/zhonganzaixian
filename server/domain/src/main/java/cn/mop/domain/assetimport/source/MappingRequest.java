package cn.mop.domain.assetimport.source;

import cn.mop.domain.assetimport.AssetImportPrechecker.Column;
import java.util.*;

/** Null target = explicitly UNMAPPED, never an implicit ignore or a fabricated empty source value. */
public record MappingRequest(long expectedVersion,String sourceParseHash,Map<Column,Integer> targetSourceColumns,Set<Integer> ignoredSourceColumns){
    public MappingRequest{
        if(expectedVersion<1||sourceParseHash==null||!sourceParseHash.matches("[0-9a-f]{64}")||targetSourceColumns==null||!targetSourceColumns.keySet().equals(EnumSet.allOf(Column.class))||targetSourceColumns.get(Column.NOTE)==null)throw new IllegalArgumentException("Invalid 15-column mapping");
        EnumMap<Column,Integer> copy=new EnumMap<>(Column.class);copy.putAll(targetSourceColumns);Set<Integer> used=new HashSet<>();for(Integer index:copy.values())if(index!=null&&(index<0||index>=ImportLimits.MAX_COLUMNS||!used.add(index)))throw new IllegalArgumentException("Invalid or duplicate source mapping");
        ignoredSourceColumns=Set.copyOf(ignoredSourceColumns);for(int index:ignoredSourceColumns)if(index<0||index>=ImportLimits.MAX_COLUMNS||used.contains(index))throw new IllegalArgumentException("Invalid ignored source column");targetSourceColumns=Collections.unmodifiableMap(copy);
    }
    public void validateAgainst(ParsedArtifact artifact){
        if(artifact.state()!=ParsedArtifact.State.PARSED)throw new IllegalArgumentException("A parsed artifact is required");Set<Integer> available=new HashSet<>();for(var column:artifact.columns())available.add(column.index());
        Set<Integer> addressed=new HashSet<>(ignoredSourceColumns);for(Integer index:targetSourceColumns.values())if(index!=null)addressed.add(index);if(!addressed.equals(available))throw new IllegalArgumentException("Every source column requires an explicit mapping or ignore");
    }
}
