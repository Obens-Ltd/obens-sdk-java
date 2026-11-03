package so.obens.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** What happened to one pushed record. {@code error} is set when {@code outcome} is FAILED. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncResult(String externalId, String id, Outcome outcome, String error) {
    public enum Outcome {CREATED, UPDATED, FAILED}
}
