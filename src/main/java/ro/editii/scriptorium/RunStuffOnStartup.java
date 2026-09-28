package ro.editii.scriptorium;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;

@Configuration
public class RunStuffOnStartup {

    @Bean
    public CommandLineRunner printJdbcUrlCLR(DataSource dataSource) {
        return args -> {
            try {
                // DB_URL carries no credentials (see application.properties -
                // spring.datasource.username/password are separate
                // properties now), so the URL itself is safe to print as-is.
                final String url = dataSource.getConnection().getMetaData().getURL();
                System.out.println("jdbc url: " + url);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        };
    }

    public CommandLineRunner listBeans(ApplicationContext applicationContext) {
        return args -> {
            final String[] names = applicationContext.getBeanDefinitionNames();
            for (String name: names) {
                displayBean(applicationContext, name);
            }
        };

    }

    private void displayBean(ApplicationContext ctxt, String beanName) {
        System.out.println(String.format("* [%s] : [%s] ",
                beanName,
                ctxt.getBean(beanName).getClass()
        ));
    }


}
