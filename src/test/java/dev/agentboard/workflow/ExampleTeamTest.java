package dev.agentboard.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The example team is data (examples/): ten YAML agents, each naming a reusable skill, and one workflow file. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:example-team;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "board.agents-dir=examples/agents", "board.workflows-dir=examples/workflows",
        "board.data-dir=target/example-team-data", "board.benchmark.enabled=false", "board.scheduler.enabled=false"})
class ExampleTeamTest {

    @Autowired WorkflowService service;

    @Test
    void tenAgentsEachUseADifferentReusableSkill() {
        var agents = service.agents();
        assertThat(agents).hasSize(10);
        var skillIds = service.skills().stream().filter(s -> s.operation() == null).map(WorkflowService.Skill::id).collect(Collectors.toSet());
        assertThat(agents.stream().map(a -> (String) a.get("skill")).collect(Collectors.toSet())).isEqualTo(skillIds);
    }

    @Test
    void theTenAgentWorkflowIsLoadedFromItsFileAndIsValid() {
        var definition = service.definitions().stream().filter(d -> d.id().equals("demo-ten-agent-briefing")).findFirst().orElseThrow();
        assertThat(definition.nodes()).hasSize(10);
        assertThat(definition.edges()).hasSize(10);
        service.validate(definition);
        // Every agent in the workflow exists and is bound to the skill the node names.
        var skillByAgent = service.agents().stream().collect(Collectors.toMap(a -> ((dev.agentboard.registry.AgentView) a.get("agent")).handle(), a -> (String) a.get("skill")));
        assertThat(new HashSet<>(skillByAgent.keySet())).containsAll(definition.nodes().stream().map(WorkflowService.Node::agent).toList());
        definition.nodes().forEach(n -> assertThat(skillByAgent.get(n.agent())).isEqualTo(n.skill()));
    }
}
