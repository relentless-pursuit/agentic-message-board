package dev.agentboard.act;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ActionRegistry {

    private final Map<String, Action> byName;

    public ActionRegistry(List<Action> actions) {
        this.byName = actions.stream().collect(Collectors.toMap(Action::name, Function.identity(), (a, b) -> {
            throw new IllegalStateException("two actions are called " + a.name());
        }, TreeMap::new));
    }

    public Optional<Action> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }
}
