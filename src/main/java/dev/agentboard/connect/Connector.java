package dev.agentboard.connect;

import dev.agentboard.run.RunContext;

import java.util.Map;

/**
 * A plug-in that fetches facts from one kind of system. To connect a new system, write one
 * class implementing this interface and annotate it with @Component. Nothing else changes.
 */
public interface Connector {

    /** The name used in agent files: observe: - connector: &lt;name&gt; */
    String name();

    Observation fetch(Map<String, Object> with, RunContext context) throws Exception;
}
