package dev.cloudlite.s3.iamclient;

import dev.cloudlite.s3.http.QueryParams;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final IamClient iamClient;

    public AuthInterceptor(IamClient iamClient) {
        this.iamClient = iamClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            throw new IamAccessDeniedException("missing Authorization header");
        }
        if (!authorization.startsWith("Bearer ")) {
            throw new IamAccessDeniedException("malformed Authorization header");
        }

        @SuppressWarnings("unchecked")
        Map<String, String> pathVariables =
            (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String bucket = pathVariables != null ? pathVariables.get("bucket") : null;
        String key = pathVariables != null ? pathVariables.get("key") : null;
        String method = request.getMethod();
        boolean uploadsParam = QueryParams.has(request.getQueryString(), "uploads");
        boolean uploadIdParam = QueryParams.has(request.getQueryString(), "uploadId");

        String action;
        String resource;
        if (bucket == null) {
            if (!"GET".equals(method)) {
                throw new IamAccessDeniedException("unrecognized request: " + method + " " + request.getRequestURI());
            }
            action = "s3:ListAllMyBuckets";
            resource = "arn:cloudlite:s3:::*";
        } else if (key == null) {
            resource = "arn:cloudlite:s3:::" + bucket;
            action = switch (method) {
                case "PUT" -> "s3:CreateBucket";
                case "HEAD" -> "s3:ListBucket";
                case "DELETE" -> "s3:DeleteBucket";
                case "GET" -> {
                    if (uploadsParam) {
                        yield "s3:ListBucketMultipartUploads";
                    }
                    throw new IamAccessDeniedException();
                }
                default -> throw new IamAccessDeniedException();
            };
        } else {
            String strippedKey = key.startsWith("/") ? key.substring(1) : key;
            resource = "arn:cloudlite:s3:::" + bucket + "/" + strippedKey;
            action = switch (method) {
                case "PUT" -> "s3:PutObject";
                case "GET" -> uploadIdParam ? "s3:ListMultipartUploadParts" : "s3:GetObject";
                case "HEAD" -> "s3:GetObject";
                case "DELETE" -> uploadIdParam ? "s3:AbortMultipartUpload" : "s3:DeleteObject";
                case "POST" -> {
                    if (uploadsParam || uploadIdParam) {
                        yield "s3:PutObject";
                    }
                    throw new IamAccessDeniedException();
                }
                default -> throw new IamAccessDeniedException();
            };
        }

        iamClient.authorize(authorization, action, resource);
        return true;
    }
}
