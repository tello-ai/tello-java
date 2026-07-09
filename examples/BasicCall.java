// Illustrative example (not part of the Gradle build).
// Run against a locally-running turn-provider-gateway.

import ai.tello.EventType;
import ai.tello.TelloClient;
import ai.tello.events.TurnEvent;

public class BasicCall {

    public static void main(String[] args) {
        String apiKey = System.getenv().getOrDefault("TELLO_API_KEY", "tello_live_xxx");
        String url = System.getenv().getOrDefault("TELLO_URL", "ws://localhost:3000/sdk");

        try (TelloClient client = new TelloClient(apiKey, url).connectBlocking()) {

            client.on(EventType.USER_TURN, e -> {
                TurnEvent turn = (TurnEvent) e;
                System.out.println("[user #" + turn.turnIndex + "] " + turn.text);
                client.answer("확인했습니다. 계속 말씀해주세요.");
            });

            client.on(EventType.CALL_COMPLETED, e ->
                    System.out.println("[completed] " + ((ai.tello.events.Event) e).callId));

            client.on(EventType.ERROR, e -> {
                ai.tello.events.ErrorEvent err = (ai.tello.events.ErrorEvent) e;
                System.out.println("[error] " + err.code + ": " + err.message);
            });

            client.createCall("+821012345678", "agent-1", "예약 확인").join();
            client.waitClosed();
        }
    }
}
