package stockOrder.stockTrade.view;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;
import stockOrder.stockTrade.member.CustomerDetails;
import stockOrder.stockTrade.member.Member;
import stockOrder.stockTrade.member.MemberRepository;
import stockOrder.stockTrade.member.MemberService;

@Controller
public class PageController {

    private MemberRepository memberRepository;

    @Value("${admin.member-id:}")
    private String adminMemberId;

    public PageController(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    // main page
    @GetMapping("/stockHome")
    public Mono<String> rankingPage() {
        return Mono.just("stockHome");
    }

    // stock Detail page
    @GetMapping("/stock/{code}")
    public String stockDetail(@PathVariable String code, @RequestParam String name, Model model) {
        model.addAttribute("code", code);
        model.addAttribute("name", name);

        return "stockDetail";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/join")
    public String joinPage() {
        return "join";
    }

    @GetMapping("/myPage")
    public String myPage(Model model, @AuthenticationPrincipal CustomerDetails user) {
        String memberId = user.getMember().getMemberId();

        if (adminMemberId != null && !adminMemberId.isBlank() && adminMemberId.equals(memberId)) {
            return "redirect:/admin/orders";
        }

        Member member = memberRepository.findMemberInfo(memberId);
        model.addAttribute("member", member);
        return "myPage";
    }

}
