package dev.agentboard.connect;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Finds connectors by name. Spring hands it every Connector bean, so new plug-ins appear automatically. */
@Component
public class ConnectorRegistry {

    private final Map<String, Connector> byName;

    public ConnectorRegistry(List<Connector> connectors) {
        this.byName = connectors.stream().collect(Collectors.toMap(Connector::name, Function.identity(), (a, b) -> {
            throw new IllegalStateException("two connectors are called " + a.name());
        }, TreeMap::new));
    }

    public Optional<Connector> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }
}
