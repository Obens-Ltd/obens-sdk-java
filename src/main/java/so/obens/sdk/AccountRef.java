package so.obens.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An account the key can act on: its own ({@code self}) or one delegated to it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountRef(String id, String name, boolean self) {
}
