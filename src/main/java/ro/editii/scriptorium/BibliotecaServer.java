package ro.editii.scriptorium;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableCaching
@ImportRuntimeHints(NativeImageHints.class)
@PropertySource("classpath:version.properties")
@OpenAPIDefinition(servers = {
	@Server(url = "/", description = "Biblioteca Server")}
)

public class BibliotecaServer {

	public static void main(String[] args) {
		SpringApplication.run(BibliotecaServer.class, args);
	}

}
