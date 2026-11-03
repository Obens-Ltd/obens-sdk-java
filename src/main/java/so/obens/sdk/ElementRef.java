package so.obens.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A list farmers can be created in. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ElementRef(String id, String name, String path) {
}
