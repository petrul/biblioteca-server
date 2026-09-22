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
                // MYSQL_URL embeds the database credentials
                // (see application.properties) - this is the one place
                // they should stay visible at runtime (which DB textbase
                // is actually connected to), so redact just the password
                // rather than suppressing the whole line.
                final String url = dataSource.getConnection().getMetaData().getURL();
                final String redacted = url.replaceAll("(jdbc:\\w+://[^:/@]+:)[^@]*(@)", "$1***$2");
                System.out.println("jdbc url: " + redacted);
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
