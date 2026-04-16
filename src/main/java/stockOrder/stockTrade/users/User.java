package stockOrder.stockTrade.users;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter @Setter
@Table(name="USERS")
public class User {

    @Id
    @GeneratedValue
    String userId;
    String userName;
}
