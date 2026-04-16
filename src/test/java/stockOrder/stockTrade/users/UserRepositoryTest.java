package stockOrder.stockTrade.users;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;


@SpringBootTest
@ExtendWith(SpringExtension.class)
@Transactional
@Rollback(false)
public class UserRepositoryTest {
    @Autowired
    private UserRepository userRepository;
    
    @Test
    public void TestUser() throws Exception{
        //given
        User user = new User();
        user.setUserName("user001");

        //when
        String saveId = userRepository.save(user);
        User findUser = userRepository.find(saveId);

        //then
        Assertions.assertThat(findUser.getUserId()).isEqualTo(user.getUserId());
        Assertions.assertThat(findUser.getUserName()).isEqualTo(user.getUserName());
        Assertions.assertThat(findUser).isEqualTo(user);
     }
}