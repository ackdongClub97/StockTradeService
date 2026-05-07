package stockOrder.stockTrade.stock.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import stockOrder.stockTrade.stock.domain.Stock;

@Repository
public class StockRepository {

    @PersistenceContext
    private EntityManager em;

    public void save(Stock stock) {
        if(stock.getId()==null){
            em.persist(stock);
        } else {
            em.merge(stock);
        }
    }

    public Stock findOne(Long id) {
        return em.find(Stock.class, id);
    }

}
