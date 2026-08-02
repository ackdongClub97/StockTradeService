package stockOrder.stockTrade.admin;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class AdminAlertService {

    private final Sinks.Many<AdminAlert> sink = Sinks.many().multicast().onBackpressureBuffer();

    public void publish(AdminAlert alert) {
        sink.tryEmitNext(alert);
    }

    public Flux<AdminAlert> stream() {
        return sink.asFlux();
    }
}
