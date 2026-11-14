package kr.finsight.account;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
class PolicyController {
    private final PolicyDocuments policies;

    PolicyController(PolicyDocuments policies) {
        this.policies = policies;
    }

    @GetMapping
    ResponseEntity<PolicyDocuments.Catalog> catalog() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(policies.catalog());
    }

    @GetMapping("/{id}/{version}")
    ResponseEntity<PolicyDocuments.Detail> detail(@PathVariable String id, @PathVariable String version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(policies.detail(id, version));
    }
}
