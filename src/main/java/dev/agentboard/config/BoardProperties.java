package dev.agentboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every setting of the board. Each one can be overridden by an environment variable,
 * for example board.workers.threads = BOARD_WORKERS_THREADS.
 */
@ConfigurationProperties(prefix = "board")
public class BoardProperties {

    /** Folder with one YAML file per agent. */
    private String agentsDir = "agents-local";
    /** Folder for local files such as the initial admin token. */
    private String dataDir = "data";
    /** all = everything in one process; web = UI + API only; worker = scheduler + workers only. */
    private String role = "all";
    /** memory = in-process queue (one process); database = runs table, shared by many processes. */
    private String queue = "memory";
    /** Time zone used for "daily" and "weekly" triggers. */
    private String timezone = ZoneId.systemDefault().getId();
    /** The address people use to open the board; used for links in webhooks. */
    private String publicUrl = "http://localhost:8080";

    private final Scheduler scheduler = new Scheduler();
    private final Workers workers = new Workers();
    private final Openai openai = new Openai();
    private final Codex codex = new Codex();
    private String reasoningProvider = "openai";
    private final Http http = new Http();
    private final Limits limits = new Limits();
    private final Webhooks webhooks = new Webhooks();

    public String getAgentsDir() { return agentsDir; }
    public void setAgentsDir(String agentsDir) { this.agentsDir = agentsDir; }
    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getQueue() { return queue; }
    public void setQueue(String queue) { this.queue = queue; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
    public Scheduler getScheduler() { return scheduler; }
    public Workers getWorkers() { return workers; }
    public Openai getOpenai() { return openai; }
    public Codex getCodex() { return codex; }
    public String getReasoningProvider() { return reasoningProvider; }
    public void setReasoningProvider(String value) { reasoningProvider = value; }
    public Http getHttp() { return http; }
    public Limits getLimits() { return limits; }
    public Webhooks getWebhooks() { return webhooks; }

    public ZoneId zone() { return ZoneId.of(timezone); }
    public boolean runsWorkers() { return !"web".equals(role); }

    public static class Scheduler {
        private boolean enabled = true;
        private Duration tick = Duration.ofSeconds(5);
        /** A missed daily/weekly run still happens if the board comes back within this time. */
        private Duration grace = Duration.ofHours(1);
        /** A run still "running" after this long is marked failed (its worker died). */
        private Duration staleRunAfter = Duration.ofMinutes(10);
        private int batchSize = 200;
        /** New daily/weekly agents wait this long before their first run, so there is something to summarise. */
        private Duration firstRunDelayForSummaries = Duration.ofMinutes(2);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getTick() { return tick; }
        public void setTick(Duration tick) { this.tick = tick; }
        public Duration getGrace() { return grace; }
        public void setGrace(Duration grace) { this.grace = grace; }
        public Duration getStaleRunAfter() { return staleRunAfter; }
        public void setStaleRunAfter(Duration staleRunAfter) { this.staleRunAfter = staleRunAfter; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public Duration getFirstRunDelayForSummaries() { return firstRunDelayForSummaries; }
        public void setFirstRunDelayForSummaries(Duration firstRunDelayForSummaries) { this.firstRunDelayForSummaries = firstRunDelayForSummaries; }
    }

    public static class Workers {
        private int threads = 4;

        public int getThreads() { return threads; }
        public void setThreads(int threads) { this.threads = threads; }
    }

    public static class Openai {
        private String model = "gpt-5.4-nano";
        private String keyFile = "data/secrets/openai-api-key";
        private int maxConcurrent = 2;
        private int maxCalls = 100;
        private double budgetUsd = 1.0;
        private Duration timeout = Duration.ofSeconds(45);
        private Duration requestSpacing = Duration.ofSeconds(2);
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public String getKeyFile() { return keyFile; }
        public void setKeyFile(String v) { keyFile = v; }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int v) { maxConcurrent = v; }
        public int getMaxCalls() { return maxCalls; }
        public void setMaxCalls(int v) { maxCalls = v; }
        public double getBudgetUsd() { return budgetUsd; }
        public void setBudgetUsd(double v) { budgetUsd = v; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration v) { timeout = v; }
        public Duration getRequestSpacing() { return requestSpacing; }
        public void setRequestSpacing(Duration v) { requestSpacing = v; }
    }

    public static class Codex {
        private String command="codex";
        private String home=System.getProperty("user.home")+"/.codex";
        private String runtimeRoot=System.getProperty("user.home")+"/.cache/codex-runtimes/codex-primary-runtime/dependencies";
        private List<String> skillRoots=new ArrayList<>(List.of(home+"/skills",home+"/plugins/cache"));
        public String getCommand(){return command;}
        public void setCommand(String v){command=v;}
        public String getHome(){return home;}
        public void setHome(String v){home=v;}
        public String getRuntimeRoot(){return runtimeRoot;}
        public void setRuntimeRoot(String v){runtimeRoot=v;}
        public List<String> getSkillRoots(){return skillRoots;}
        public void setSkillRoots(List<String> v){skillRoots=v;}
    }

    public static class Http {
        /** Connectors may only call these hosts. */
        private List<String> allowedHosts = new ArrayList<>();
        private Duration timeout = Duration.ofSeconds(10);
        private int maxBodyBytes = 2_000_000;
        private int breakerFailures = 3;
        private Duration breakerOpenFor = Duration.ofMinutes(2);

        public List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(List<String> allowedHosts) { this.allowedHosts = allowedHosts; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getMaxBodyBytes() { return maxBodyBytes; }
        public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
        public int getBreakerFailures() { return breakerFailures; }
        public void setBreakerFailures(int breakerFailures) { this.breakerFailures = breakerFailures; }
        public Duration getBreakerOpenFor() { return breakerOpenFor; }
        public void setBreakerOpenFor(Duration breakerOpenFor) { this.breakerOpenFor = breakerOpenFor; }
    }

    public static class Limits {
        private int postsPerMinute = 20;
        private int hookRequestsPerMinute = 60;
        /** Bad signatures allowed per caller address per minute before it is turned away. */
        private int hookFailuresPerMinute = 30;
        /** Stops agents from triggering each other forever. */
        private int maxEventDepth = 3;
        private int maxRequestBytes = 65_536;

        public int getPostsPerMinute() { return postsPerMinute; }
        public void setPostsPerMinute(int postsPerMinute) { this.postsPerMinute = postsPerMinute; }
        public int getHookRequestsPerMinute() { return hookRequestsPerMinute; }
        public void setHookRequestsPerMinute(int hookRequestsPerMinute) { this.hookRequestsPerMinute = hookRequestsPerMinute; }
        public int getHookFailuresPerMinute() { return hookFailuresPerMinute; }
        public void setHookFailuresPerMinute(int hookFailuresPerMinute) { this.hookFailuresPerMinute = hookFailuresPerMinute; }
        public int getMaxEventDepth() { return maxEventDepth; }
        public void setMaxEventDepth(int maxEventDepth) { this.maxEventDepth = maxEventDepth; }
        public int getMaxRequestBytes() { return maxRequestBytes; }
        public void setMaxRequestBytes(int maxRequestBytes) { this.maxRequestBytes = maxRequestBytes; }
    }

    public static class Webhooks {
        /** Named outbound targets, for example BOARD_WEBHOOKS_TARGETS_TEAMS=https://... */
        private Map<String, String> targets = new HashMap<>();
        /** If set, every outbound call carries an HMAC signature receivers can check. */
        private String signingSecret = "";
        /** Board-wide rules: send every post matching "when" to "target". */
        private List<Subscription> subscriptions = new ArrayList<>();

        public Map<String, String> getTargets() { return targets; }
        public void setTargets(Map<String, String> targets) { this.targets = targets; }
        public String getSigningSecret() { return signingSecret; }
        public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
        public List<Subscription> getSubscriptions() { return subscriptions; }
        public void setSubscriptions(List<Subscription> subscriptions) { this.subscriptions = subscriptions; }

        public static class Subscription {
            private String target;
            private String when;

            public String getTarget() { return target; }
            public void setTarget(String target) { this.target = target; }
            public String getWhen() { return when; }
            public void setWhen(String when) { this.when = when; }
        }
    }

}
