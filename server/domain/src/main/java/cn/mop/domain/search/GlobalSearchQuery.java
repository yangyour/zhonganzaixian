package cn.mop.domain.search;
import cn.mop.domain.DomainException;
/** Contract M1-8 literal query. No wildcard expansion, cross-tenant fallback or implicit browse-all. */
public record GlobalSearchQuery(String text) {
    public static final int ASSET_LIMIT=5,EVENT_LIMIT=4,TICKET_LIMIT=3,AUTOMATION_LIMIT=3;
    public GlobalSearchQuery {if(text==null||text.isEmpty()||text.codePointCount(0,text.length())>100||text.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||text.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE))throw new DomainException(400,"VALIDATION_ERROR","搜索词必须为1至100个Unicode字符且非全空白；不支持控制字符、隐式全部查询或通配展开");}
}
