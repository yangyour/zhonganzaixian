package cn.mop.importparser;

import cn.mop.domain.assetimport.source.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;

final class SafeXml {
    private SafeXml(){}
    static final class Context {
        final ParseBudget budget;
        private long text;
        private int elements;
        Context(ParseBudget budget){this.budget=budget;}
        void text(int count){budget.check();if((text+=count)>ImportLimits.MAX_TOTAL_TEXT_CHARS)throw new ParseFailure(ParseFailure.Code.RESOURCE_LIMIT);}
        void element(){budget.check();if(++elements>ImportLimits.MAX_XML_ELEMENTS)throw new ParseFailure(ParseFailure.Code.RESOURCE_LIMIT);}
    }
    static void parse(InputStream stream,DefaultHandler handler,Context context)throws Exception{
        SAXParserFactory factory=SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        XMLReader xml=factory.newSAXParser().getXMLReader();
        xml.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,"");xml.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        DefaultHandler bounded=new DefaultHandler(){
            private int depth;
            @Override public void startElement(String uri,String local,String qualified,Attributes attributes)throws SAXException{
                context.element();if(++depth>ImportLimits.MAX_XML_DEPTH||attributes.getLength()>64)throw new ParseFailure(ParseFailure.Code.RESOURCE_LIMIT);
                for(int index=0;index<attributes.getLength();index++){String value=attributes.getValue(index);if(value.length()>ImportLimits.MAX_CELL_CHARS)throw new ParseFailure(ParseFailure.Code.RESOURCE_LIMIT);context.text(value.length());}
                handler.startElement(uri,local,qualified,attributes);
            }
            @Override public void endElement(String uri,String local,String qualified)throws SAXException{context.budget.check();handler.endElement(uri,local,qualified);depth--;}
            @Override public void characters(char[] chars,int start,int count)throws SAXException{context.text(count);handler.characters(chars,start,count);}
            @Override public void ignorableWhitespace(char[] chars,int start,int count)throws SAXException{context.text(count);handler.ignorableWhitespace(chars,start,count);}
        };
        xml.setEntityResolver((publicId,systemId)->{throw new ParseFailure(ParseFailure.Code.EXTERNAL_LINK_NOT_ALLOWED);});
        xml.setErrorHandler(new DefaultHandler(){@Override public void error(SAXParseException error)throws SAXException{throw error;}@Override public void fatalError(SAXParseException error)throws SAXException{throw error;}});
        xml.setContentHandler(bounded);xml.parse(new InputSource(stream));
    }
}
