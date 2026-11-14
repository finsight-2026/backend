package kr.finsight.diagnosis;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
class SurveyController {
    private final SurveyCatalog catalog;

    SurveyController(SurveyCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/v1/surveys/current")
    SurveyCatalog.Survey current() {
        return catalog.current();
    }
}
