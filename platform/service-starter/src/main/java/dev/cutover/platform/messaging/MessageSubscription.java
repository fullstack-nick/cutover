package dev.cutover.platform.messaging;

import java.util.Set;

public record MessageSubscription(String queue, Set<String> sources, Set<String> sites) {
    public MessageSubscription { sources = Set.copyOf(sources); sites = Set.copyOf(sites); }
}
