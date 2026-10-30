package stockOrder.stockTrade.kis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import stockOrder.stockTrade.token.TokenService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/* KIS 웹소켓 연결 로그는 "기동 시 첫 시도 / 성공 / 끊김 / 재연결 성공"만 info·warn·error로 남고,
   끊긴 동안 10초마다 도는 재시도는 아무리 반복돼도 warn·error를 더 남기지 않는지 검증.
   KIS 대신 테스트 안에서 띄운 최소 웹소켓 서버를 켰다 껐다 해서 실제 끊김/재연결을 만든다. */
class KisWebSocketConnectionLogTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(KisWebSocketService.class);
    private TinyWebSocketServer server;

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        if (server != null) server.close();
    }

    @Test
    @DisplayName("연결 로그는 기동 시도·성공·끊김·재연결 성공 4줄뿐이고, 끊긴 동안의 반복 재시도는 warn/error를 남기지 않는다. 토큰도 안 남긴다")
    void 연결_수명주기_로그만_남는다() throws Exception {
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);

        server = new TinyWebSocketServer(0);
        int port = server.port();

        TokenService tokenService = mock(TokenService.class);
        when(tokenService.fetchApprovalKey()).thenReturn("secret-approval-key");
        MarketDataBus bus = mock(MarketDataBus.class);
        when(bus.isCollector()).thenReturn(true);
        KisWebSocketService ws = new KisWebSocketService(tokenService, mock(KisService.class), new ObjectMapper(), bus);
        ReflectionTestUtils.setField(ws, "wsUrl", "ws://127.0.0.1:" + port);

        ws.init();
        await(m -> m.contains("연결 성공(기동)"));

        server.close();                                    // KIS 쪽에서 연결이 끊김
        await(m -> m.contains("연결 끊김"));
        for (int i = 0; i < 3; i++) {                     // 끊긴 동안 healthCheck가 10초마다 재시도하는 상황
            ws.healthCheck();
            Thread.sleep(300);
        }

        server = new TinyWebSocketServer(port);            // KIS 복구
        ws.healthCheck();
        await(m -> m.contains("재연결 성공"));

        List<String> visible = logs.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(4, visible.size(), "info 이상 로그: " + visible);
        assertTrue(visible.get(0).contains("시도(기동)"));
        assertTrue(visible.get(1).contains("연결 성공(기동)"));
        assertTrue(visible.get(2).contains("연결 끊김"));
        assertTrue(visible.get(3).contains("재연결 성공") && visible.get(3).contains("재시도 4회"), visible.get(3));
        assertTrue(logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains("secret-approval-key")), "승인키가 로그에 남으면 안 된다");
    }

    private void await(Predicate<String> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (logs.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(condition)) return;
            Thread.sleep(50);
        }
        fail("로그가 안 나옴. 지금까지: " + logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    /* 웹소켓 핸드셰이크만 해주고 연결을 붙잡고 있는 최소 서버 - close()하면 소켓을 끊어 클라이언트 쪽에 "연결 끊김"이 생긴다 */
    static class TinyWebSocketServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final List<Socket> clients = new CopyOnWriteArrayList<>();

        TinyWebSocketServer(int port) throws IOException {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
            Thread acceptor = new Thread(this::acceptLoop);
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            try {
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    clients.add(socket);
                    handshake(socket);
                }
            } catch (Exception ignored) {
                // close() 시 정상 종료
            }
        }

        private void handshake(Socket socket) throws Exception {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String key = null;
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(18).trim();
            }
            byte[] sha1 = MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + Base64.getEncoder().encodeToString(sha1) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread drain = new Thread(() -> {   // 클라이언트가 보내는 구독 요청 프레임은 읽어서 버림
                try {
                    while (socket.getInputStream().read() != -1) { }
                } catch (IOException ignored) { }
            });
            drain.setDaemon(true);
            drain.start();
        }

        @Override
        public void close() {
            try {
                for (Socket c : clients) c.close();
                serverSocket.close();
            } catch (IOException ignored) { }
        }
    }
}
