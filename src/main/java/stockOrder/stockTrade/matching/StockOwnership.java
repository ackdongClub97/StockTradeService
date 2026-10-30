package stockOrder.stockTrade.matching;

import org.apache.kafka.common.utils.Utils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/* 이 서버가 체결을 맡은 종목 = stock-order 토픽에서 이 서버의 컨슈머에 할당된 파티션에 들어가는 종목.
   주문 메시지 키가 종목코드라 한 종목의 주문은 항상 같은 파티션으로 가고, 파티션 하나는 컨슈머 그룹 안에서 한 서버에만
   할당되므로 "한 종목은 한 서버만 체결한다(단일 writer)"가 Kafka 할당만으로 보장된다 - 서버 사이 분산락이 필요 없음.

   종목 -> 파티션 계산은 Kafka 기본 파티셔너(키가 있는 레코드)와 같은 식: toPositive(murmur2(키 바이트)) % 파티션 수.
   30초 배치·장마감 취소처럼 Kafka 메시지 없이 DB에서 대기 주문을 꺼내 도는 작업도 이 기준으로 자기 종목만 처리한다. */
@Component
public class StockOwnership {

    private final Set<Integer> ownedPartitions = ConcurrentHashMap.newKeySet();
    private volatile int partitionCount = 0;
    private volatile boolean ownsAll = false;

    /* 테스트용 - Kafka 할당 없이 모든 종목을 맡은 것으로 취급 */
    public static StockOwnership ownsAll() {
        StockOwnership ownership = new StockOwnership();
        ownership.ownsAll = true;
        return ownership;
    }

    public int partitionOf(String stockCode) {
        int count = partitionCount;
        if (count <= 0) return -1;
        return Utils.toPositive(Utils.murmur2(stockCode.getBytes(StandardCharsets.UTF_8))) % count;
    }

    public boolean owns(String stockCode) {
        if (ownsAll) return true;
        int partition = partitionOf(stockCode);
        return partition >= 0 && ownedPartitions.contains(partition);
    }

    void assign(Collection<Integer> partitions, int topicPartitionCount) {
        this.partitionCount = topicPartitionCount;
        ownedPartitions.addAll(partitions); // cooperative 리밸런스는 새로 받은 파티션만 알려주므로 교체가 아니라 추가
    }

    void revoke(Collection<Integer> partitions) {
        ownedPartitions.removeAll(partitions);
    }

    public Set<Integer> ownedPartitions() {
        return Set.copyOf(ownedPartitions);
    }
}
