// Illustrative example (not part of the Gradle build).
// Run against a locally-running turn-provider-gateway.

import io.telloai.EventType;
import io.telloai.TelloClient;
import io.telloai.events.TurnEvent;

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
                    System.out.println("[completed] " + ((io.telloai.events.Event) e).callId));

            client.on(EventType.ERROR, e -> {
                io.telloai.events.ErrorEvent err = (io.telloai.events.ErrorEvent) e;
                System.out.println("[error] " + err.code + ": " + err.message);
            });

            client.createCall("+821012345678", "예약 확인").join();
            client.waitClosed();
        }
    }
}
