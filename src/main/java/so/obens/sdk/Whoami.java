package so.obens.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Whoami(String keyId, String keyName, String accountId, String workspaceId) {
}
