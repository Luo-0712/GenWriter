package com.example.genwriter.agent;

import lombok.Getter;

/**
 * 单次 Agent 执行上下文
 * 使用状态机记录每一轮执行过程，避免在单例 Agent Bean 上保存共享状态。
 */
@Getter
public class AgentExecutionContext {

    private final AgentType agentType;
    private final String originalInput;
    private String currentInput;
    private String thought;
    private String observation;
    private int round;
    private AgentState state;
    private String kbId;
    private String sessionId;

    public AgentExecutionContext(AgentType agentType, String originalInput) {
        this.agentType = agentType;
        this.originalInput = originalInput;
        this.currentInput = originalInput;
        this.state = AgentState.IDLE;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public String getKbId() {
        return kbId;
    }

    public void nextRound() {
        this.round++;
    }

    public void updateCurrentInput(String currentInput) {
        this.currentInput = currentInput;
    }

    public void recordThought(String thought) {
        this.thought = thought;
    }

    public void recordObservation(String observation) {
        this.observation = observation;
    }

    public void transitionTo(AgentState nextState) {
        if (!isValidTransition(state, nextState)) {
            throw new IllegalStateException(
                    "非法 Agent 状态流转: " + state + " -> " + nextState + " (" + agentType + ")"
            );
        }
        this.state = nextState;
    }

    private boolean isValidTransition(AgentState currentState, AgentState nextState) {
        if (currentState == nextState) {
            return true;
        }

        return switch (currentState) {
            case IDLE -> nextState == AgentState.THINKING || nextState == AgentState.ERROR;
            case THINKING -> nextState == AgentState.PLANNING || nextState == AgentState.ERROR;
            case PLANNING -> nextState == AgentState.EXECUTING || nextState == AgentState.FINISHED || nextState == AgentState.ERROR;
            case EXECUTING -> nextState == AgentState.THINKING || nextState == AgentState.FINISHED || nextState == AgentState.ERROR;
            case FINISHED, ERROR -> false;
        };
    }
}
