package ai.tello.events;

/** One callable agent returned by {@code agents.listed}. */
public class AgentInfo {

    public final String agentId;
    public final String name;
    public final String role;
    public final boolean isDefault;
    public final String status;

    public AgentInfo(String agentId, String name, String role, boolean isDefault, String status) {
        this.agentId = agentId;
        this.name = name;
        this.role = role;
        this.isDefault = isDefault;
        this.status = status;
    }
}
