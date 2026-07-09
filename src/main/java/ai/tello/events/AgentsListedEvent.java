package ai.tello.events;

import com.google.gson.JsonObject;
import java.util.List;

/** {@code agents.listed}, emitted in response to {@code listAgents}. */
public class AgentsListedEvent extends Event {

    public final String requestId;
    public final List<AgentInfo> agents;

    public AgentsListedEvent(String type, String version, String requestId, List<AgentInfo> agents,
                             JsonObject raw) {
        super(type, version, "", "", "", raw);
        this.requestId = requestId;
        this.agents = List.copyOf(agents);
    }
}
