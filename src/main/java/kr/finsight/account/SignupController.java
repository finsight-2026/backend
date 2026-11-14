package kr.finsight.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
@RequestMapping("/api/v1/auth/signup")
class SignupController {
    record SendRequest(@NotBlank @Email @Size(max = 254) String email) {}

    private final SignupService signup;

    SignupController(SignupService signup) {
        this.signup = signup;
    }

    @PostMapping("/code")
    SignupService.Window send(@Valid @RequestBody SendRequest request, HttpServletRequest http) {
        return signup.send(request.email(), http.getRemoteAddr());
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.CREATED)
    void verify(@Valid @RequestBody SignupService.Registration request) {
        switch (signup.register(request)) {
            case CREATED -> { }
            case INVALID_CODE -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "인증번호를 확인해주세요.");
            case EXPIRED_CODE -> throw new ResponseStatusException(HttpStatus.GONE, "인증번호를 다시 요청해주세요.");
            case DUPLICATE -> throw new ResponseStatusException(HttpStatus.CONFLICT, "로그인 또는 비밀번호 찾기를 이용해주세요.");
            case INVALID_INPUT -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "가입 정보를 확인해주세요.");
            case POLICY_CHANGED -> throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "현재 약관을 확인해주세요.");
        }
    }
}
