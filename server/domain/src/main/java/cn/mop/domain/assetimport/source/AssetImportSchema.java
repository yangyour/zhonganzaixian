package cn.mop.domain.assetimport.source;
import cn.mop.domain.assetimport.AssetImportPrechecker.Column;
import java.util.*;
/** Contract labels only. Nine device values, collection methods and other required fields stay unconfirmed. */
public final class AssetImportSchema {
    private AssetImportSchema(){}
    public static final String VERSION="contract-m3-6-standard15-v1";
    public record ColumnDefinition(Column code,String label,boolean contractAlwaysRequired){}
    public static List<ColumnDefinition> columns(){return Arrays.stream(Column.values()).map(column->new ColumnDefinition(column,column.label(),column==Column.NOTE)).toList();}
}
