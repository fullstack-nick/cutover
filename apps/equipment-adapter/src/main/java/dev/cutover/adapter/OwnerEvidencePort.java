package dev.cutover.adapter;

import tools.jackson.databind.JsonNode;

/** Bounded authenticated reads from each owner; it never exposes another owner's database. */
@FunctionalInterface
public interface OwnerEvidencePort {
    JsonNode read(String service, String site, String zone, JsonNode request);
}
