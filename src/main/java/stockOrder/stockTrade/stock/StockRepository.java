package stockOrder.stockTrade.stock;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

@Repository
public class StockRepository {

    @PersistenceContext
    private EntityManager em;

    public Long save(Stock stock) {
        em.persist(stock);

        return stock.getId();
    }

}
