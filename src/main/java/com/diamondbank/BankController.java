package com.diamondbank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api")
class BankController {
    private final BankService bank;
    BankController(BankService bank) { this.bank = bank; }
    record Registration(@NotBlank @Size(max=60) String name, @NotBlank @Email @Size(max=100) String email,
                        @NotBlank @Size(min=12,max=64) String password) {}
    record Action(@NotBlank String type, String id, Map<String,String> fields) {}
    @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    void register(@Valid @RequestBody Registration request) { bank.register(request); }
    @GetMapping("/state") Map<String,Object> state(Principal principal) { return bank.state(principal.getName()); }
    @PostMapping("/actions") Map<String,Object> action(Principal principal,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Action action) {
        return bank.act(principal.getName(), key, action);
    }
}
