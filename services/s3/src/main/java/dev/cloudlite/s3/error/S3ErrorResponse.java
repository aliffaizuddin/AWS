package dev.cloudlite.s3.error;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "Error")
public class S3ErrorResponse {

    @JacksonXmlProperty(localName = "Code")
    private final String code;

    @JacksonXmlProperty(localName = "Message")
    private final String message;

    @JacksonXmlProperty(localName = "Resource")
    private final String resource;

    @JacksonXmlProperty(localName = "RequestId")
    private final String requestId;

    public S3ErrorResponse(String code, String message, String resource, String requestId) {
        this.code = code;
        this.message = message;
        this.resource = resource;
        this.requestId = requestId;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getResource() {
        return resource;
    }

    public String getRequestId() {
        return requestId;
    }
}
