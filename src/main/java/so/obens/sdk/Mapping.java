package so.obens.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Your id and the platform id of one record. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Mapping(MappingType type, String externalId, String id) {
}
