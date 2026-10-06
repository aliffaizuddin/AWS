package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CompletedPartXml {

    @JacksonXmlProperty(localName = "PartNumber")
    private Integer partNumber;

    @JacksonXmlProperty(localName = "ETag")
    private String etag;

    public Integer getPartNumber() { return partNumber; }
    public void setPartNumber(Integer partNumber) { this.partNumber = partNumber; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
}
