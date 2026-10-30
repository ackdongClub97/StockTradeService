package stockOrder.stockTrade.member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomerDetailsSerializationTest {

    @Test
    @DisplayName("로그인 사용자(CustomerDetails)는 자바 직렬화가 가능해야 한다 - Spring Session JDBC가 세션을 DB에 저장할 때 직렬화함(실패하면 로그인 500)")
    void 로그인_사용자는_직렬화된다() throws Exception {
        Member member = new Member();
        member.setMemberId("m1");
        member.setSeed(10_000_000);
        CustomerDetails kakaoUser = new CustomerDetails(member, Map.of("id", 123L, "properties", Map.of("nickname", "n")));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(kakaoUser);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            CustomerDetails restored = (CustomerDetails) in.readObject();
            assertEquals("m1", restored.getMember().getMemberId());
        }
    }
}
