package stockOrder.stockTrade.users;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    @PersistenceContext
    private EntityManager em;

    public String save (User user) {
        em.persist(user);
        return user.getUserId();
    }

    public User find (String userId) {
        return em.find(User.class, userId);
    }

}
