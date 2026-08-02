function requestNotificationPermission() {
    if('Notification' in window) {
        Notification.requestPermission();
    }
}

function connectOrderStream() {
    if(!('Notification' in window)) return;

    const es = new EventSource('/api/order/stream');

    es.addEventListener('order-result', (e) => {
        const data = JSON.parse(e.data);
        sendNotification(data);
    });

    es.onerror = () => {
        es.close();
    };
}

function sendNotification(data) {
    if(Notification.permission !== 'granted') return;

    if (data.orderStatus === 'CANCELLED') {
        new Notification('⏰ 장 마감 주문 취소', {
            body: `${data.stockName} 미체결 주문이 장 마감으로 취소되었습니다.`,
        });
        return;
    }

    const isBuy = data.orderType == 'BUY';
    new Notification(`${isBuy ? '📈 매수' : '📉 매도'} 체결 완료`, {
        body: `${data.stockName} ${data.quantity}주 @ ${data.price.toLocaleString()}원`,
    });

}

requestNotificationPermission();

if (window.__authenticated) {
    connectOrderStream();
}