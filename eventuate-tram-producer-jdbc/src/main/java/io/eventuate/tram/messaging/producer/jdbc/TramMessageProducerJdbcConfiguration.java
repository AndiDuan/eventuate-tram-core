package io.eventuate.tram.messaging.producer.jdbc;

import io.eventuate.javaclient.spring.jdbc.EventuateSchema;
import io.eventuate.javaclient.spring.jdbc.IdGenerator;
import io.eventuate.javaclient.spring.jdbc.IdGeneratorImpl;
import io.eventuate.sql.dialect.SqlDialectConfiguration;
import io.eventuate.sql.dialect.SqlDialectSelector;
import io.eventuate.tram.jdbc.CommonJdbcMessagingConfiguration;
import io.eventuate.tram.messaging.common.MessageInterceptor;
import io.eventuate.tram.messaging.producer.MessageProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({SqlDialectConfiguration.class, CommonJdbcMessagingConfiguration.class})
public class TramMessageProducerJdbcConfiguration {

  @Autowired(required = false)
  private MessageInterceptor[] messageInterceptors = new MessageInterceptor[0];

  @Value("${spring.datasource.driver-class-name}")
  private String driver;

  @Bean
  public MessageProducer messageProducer(EventuateSchema eventuateSchema,
                                         SqlDialectSelector sqlDialectSelector) {
    return new MessageProducerJdbcImpl(eventuateSchema,
            sqlDialectSelector.getDialect(driver).getCurrentTimeInMillisecondsExpression(), messageInterceptors);
  }

  @Bean
  public IdGenerator idGenerator() {
    return new IdGeneratorImpl();
  }
}
