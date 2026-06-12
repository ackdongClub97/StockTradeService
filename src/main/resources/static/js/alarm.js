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

    const isBuy = data.orderType == 'BUY';
    new Notification(`${isBuy ? '📈 매수' : '📉 매도'} 체결 완료`, {
        body: `${data.stockName} ${data.quantity}주 @ ${data.price.toLocaleString()}원`,
    });

}

requestNotificationPermission();

fetch('/api/member/info')
.then(r => {
    if(r.ok) connectOrderStream();
})
.catch(() => {});