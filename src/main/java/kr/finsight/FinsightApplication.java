package kr.finsight;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FinsightApplication {

	public static void main(String[] args) {
		var arguments = new ArrayList<>(List.of(args));
		if (arguments.remove("--recover-last-admin")) {
			arguments.addAll(List.of(
					"--spring.profiles.active=local",
					"--spring.main.web-application-type=none",
					"--finsight.admin.recovery.command=true",
					"--finsight.admin.bootstrap=false",
					"--finsight.discord.enabled=false",
					"--finsight.push.fcm-enabled=false"));
			SpringApplication.run(FinsightApplication.class, arguments.toArray(String[]::new)).close();
			return;
		}
		SpringApplication.run(FinsightApplication.class, args);
	}

}
