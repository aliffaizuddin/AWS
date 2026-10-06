package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

@JacksonXmlRootElement(localName = "CompleteMultipartUpload")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompleteMultipartUploadXml {

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "Part")
    private List<CompletedPartXml> parts;

    public List<CompletedPartXml> getParts() { return parts; }
    public void setParts(List<CompletedPartXml> parts) { this.parts = parts; }
}
