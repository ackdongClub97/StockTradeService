import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../config.js';

// SecurityConfig의 formLogin은 usernameParameter/passwordParameter를 커스텀하지 않았으므로
// Spring Security 기본값인 username/password 파라미터명을 그대로 쓴다. memberId가 username이다.
//
// k6(v2.1.0)는 VU가 iteration을 새로 시작할 때마다 만료시간(Expires/Max-Age)이 없는 쿠키를
// 자동 쿠키 저장소에서 지운다. Spring이 내려주는 JSESSIONID가 정확히 그런 쿠키라서, k6의 자동
// 쿠키 저장소에 기대면 로그인 직후(iteration 0) 요청까지만 세션이 유지되고 그 다음 iteration부터는
// 매번 미인증 상태가 된다 - 2026-08-12 부하테스트 http_req_failed 18%대 원인 조사에서 VU 1개/
// 동시성 0 상태로도 재현 확인함(issue.md 참고). 그래서 로그인 응답에서 JSESSIONID 값을 직접 꺼내
// 리턴하고, 호출자가 이후 모든 요청에 `Cookie` 헤더로 수동으로 붙여야 한다(submitOrder 참고).
export function login(memberId, password) {
  const res = http.post(
    `${BASE_URL}/login`,
    { username: memberId, password },
    { redirects: 0 }
  );

  const ok = check(res, { [`로그인 성공 ${memberId}`]: (r) => r.status === 302 });
  if (!ok) {
    console.error(`로그인 실패 ${memberId}: status=${res.status} body=${res.body}`);
    return null;
  }

  const cookie = res.cookies['JSESSIONID'];
  return cookie && cookie[0] ? cookie[0].value : null;
}
