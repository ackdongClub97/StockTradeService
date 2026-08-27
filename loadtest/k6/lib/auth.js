import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../config.js';

// SecurityConfig의 formLogin은 usernameParameter/passwordParameter를 커스텀하지 않았으므로
// Spring Security 기본값인 username/password 파라미터명을 그대로 쓴다. memberId가 username이다.
//
// k6(v2.1.0)는 기본 설정상 VU가 iteration을 새로 시작할 때마다 쿠키 저장소를 통째로 초기화한다.
// Spring이 내려주는 JSESSIONID도 여기 걸려서, 로그인 직후(iteration 0) 요청까지만 세션이 유지되고
// 그 다음 iteration부터는 매번 미인증 상태가 되는 문제가 있었다(2026-08-12 부하테스트
// http_req_failed 18%대 원인 조사, issue.md 참고) - 처음엔 로그인 응답에서 JSESSIONID를 직접 꺼내
// 호출자가 매 요청마다 Cookie 헤더로 수동으로 붙이는 방식으로 우회했으나, k6 자체에 iteration 간
// 쿠키 초기화를 끄는 `options.noCookiesReset: true` 설정이 있다는 걸 뒤늦게(2026-08-25) 확인 -
// main.js에 그 설정만 켜두면 k6의 자동 쿠키 저장소가 계속 세션을 들고 있어서, 여기서 쿠키를
// 직접 다룰 필요가 없다.
export function login(memberId, password) {
  const res = http.post(
    `${BASE_URL}/login`,
    { username: memberId, password },
    { redirects: 0 }
  );

  const ok = check(res, { [`로그인 성공 ${memberId}`]: (r) => r.status === 302 });
  if (!ok) {
    console.error(`로그인 실패 ${memberId}: status=${res.status} body=${res.body}`);
  }
  return ok;
}
