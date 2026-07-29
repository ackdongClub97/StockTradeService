package stockOrder.stockTrade.member;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/member")
@RequiredArgsConstructor
@Slf4j
public class MemberController {

    private final MemberService memberService;
    private final MemberRepository memberRepository;

    @PostMapping("/join")
    public ResponseEntity<Map<String, String>> join(@RequestBody Member member){
        try{
            memberService.join(member);
            return ResponseEntity.ok(Map.of("message", "SUCCESS"));
        }catch (Exception e){
            log.info("info {}", member);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getMemberInfo(@AuthenticationPrincipal CustomerDetails customerDetails){
        if(customerDetails == null){
            return ResponseEntity.status(401).build();
        }

        String memberId = customerDetails.getMember().getMemberId();
        Member member = memberRepository.findMemberInfo(memberId);

        return ResponseEntity.ok(Map.of(
                "memberId", member.getMemberId(),
                "seed", member.getSeed(),
                "level", member.getLevel()
        ));
    }

    @PatchMapping("/profile")
    public ResponseEntity<Map<String, String>> updateProfile(@AuthenticationPrincipal CustomerDetails customerDetails,
                                                               @RequestBody Map<String, String> body) {
        if(customerDetails == null){
            return ResponseEntity.status(401).build();
        }

        try {
            String memberId = customerDetails.getMember().getMemberId();
            memberService.updateProfile(memberId, body.get("name"), body.get("phone"));
            return ResponseEntity.ok(Map.of("message", "SUCCESS"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/withdraw")
    public ResponseEntity<Map<String, String>> withdraw(@AuthenticationPrincipal CustomerDetails customerDetails) {
        if(customerDetails == null){
            return ResponseEntity.status(401).build();
        }

        try {
            memberService.withdraw(customerDetails.getMember().getMemberId());
            return ResponseEntity.ok(Map.of("message", "SUCCESS"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
