package com.stopbell.transit.client;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailResponse;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationResponse;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import tools.jackson.databind.json.JsonMapper;

/** Seoul's repeated itemList XML elements; JSON remains compatible with existing callers. */
final class SeoulBusResponseReader {
    private static final JsonMapper json = JsonMapper.builder().build();

    static SeoulBusVehicleLocationResponse location(byte[] bytes, MediaType type) {
        try {
            if (isJson(type)) return json.readValue(bytes, SeoulBusVehicleLocationResponse.class);
            Element root = root(bytes), header = child(root, "msgHeader"), body = child(root, "msgBody");
            return new SeoulBusVehicleLocationResponse(
                    header == null ? null : new SeoulBusVehicleLocationResponse.Header(
                            text(header, "headerCd"), text(header, "headerMsg")),
                    body == null ? null : new SeoulBusVehicleLocationResponse.Body(items(body).stream().map(item ->
                            new SeoulBusVehicleLocationItem(text(item, "vehId"), text(item, "plainNo"),
                                    integer(item, "sectOrd"), text(item, "sectionId"), integer(item, "stopFlag"),
                                    text(item, "dataTm"), decimal(item, "gpsX"), decimal(item, "gpsY"),
                                    text(item, "nextStId"), text(item, "isrunyn"), text(item, "congetion"))).toList()));
        } catch (Exception exception) {
            throw new RestClientException("Invalid Seoul roster response", exception);
        }
    }

    static SeoulBusVehicleDetailResponse detail(byte[] bytes, MediaType type) {
        try {
            if (isJson(type)) return json.readValue(bytes, SeoulBusVehicleDetailResponse.class);
            Element root = root(bytes), header = child(root, "msgHeader"), body = child(root, "msgBody");
            return new SeoulBusVehicleDetailResponse(
                    header == null ? null : new SeoulBusVehicleDetailResponse.Header(
                            text(header, "headerCd"), text(header, "headerMsg")),
                    body == null ? null : new SeoulBusVehicleDetailResponse.Body(items(body).stream().map(item ->
                            new SeoulBusVehicleDetailItem(text(item, "vehId"), text(item, "plainNo"),
                                    text(item, "stId"), integer(item, "stOrd"), integer(item, "stopFlag"),
                                    text(item, "dataTm"), decimal(item, "tmX"), decimal(item, "tmY"))).toList()));
        } catch (Exception exception) {
            throw new RestClientException("Invalid Seoul detail response", exception);
        }
    }

    private static boolean isJson(MediaType type) {
        return type != null && MediaType.APPLICATION_JSON.isCompatibleWith(type);
    }

    private static Element root(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Empty response");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        // Parser diagnostics can contain upstream data. Keep stderr and logs free of raw XML.
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException e) throws SAXParseException { throw e; }
            @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
        });
        Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        if (!"ServiceResult".equals(root.getTagName())) throw new IllegalArgumentException("Invalid envelope");
        return root;
    }

    private static List<Element> items(Element body) {
        var children = body.getChildNodes();
        return IntStream.range(0, children.getLength()).mapToObj(children::item)
                .filter(node -> node instanceof Element && "itemList".equals(node.getNodeName()))
                .map(node -> (Element) node).toList();
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) return element;
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        String value = element == null ? null : element.getTextContent().trim();
        return value == null || value.isEmpty() ? null : value;
    }

    private static Integer integer(Element item, String name) {
        String value = text(item, name);
        return value == null ? null : Integer.valueOf(value);
    }

    private static BigDecimal decimal(Element item, String name) {
        String value = text(item, name);
        return value == null ? null : new BigDecimal(value);
    }
}
