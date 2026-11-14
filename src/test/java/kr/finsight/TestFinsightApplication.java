package kr.finsight;

import org.springframework.boot.SpringApplication;

public class TestFinsightApplication {

	public static void main(String[] args) {
		SpringApplication.from(FinsightApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
