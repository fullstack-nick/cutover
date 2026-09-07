package dev.cutover.platform.messaging;

import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

public record MessageSubscription(String queue, Set<String> sources, Set<String> sites, Map<String,String> exchanges) {
    public MessageSubscription(String queue,Set<String> sources,Set<String> sites) {
        this(queue,sources,sites,sources.stream().collect(Collectors.toMap(source->source,source->"cutover."+source+".v1")));
    }
    public MessageSubscription {
        sources=Set.copyOf(sources);sites=Set.copyOf(sites);exchanges=Map.copyOf(exchanges);
        if(!exchanges.keySet().equals(sources))throw new IllegalArgumentException("Every subscribed publisher requires one explicit authenticated exchange");
    }
}
