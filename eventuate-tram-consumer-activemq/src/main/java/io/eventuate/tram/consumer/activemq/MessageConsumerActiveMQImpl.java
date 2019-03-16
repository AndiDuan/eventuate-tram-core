package io.eventuate.tram.consumer.activemq;

import io.eventuate.javaclient.commonimpl.JSonMapper;
import io.eventuate.tram.consumer.common.DuplicateMessageDetector;
import io.eventuate.tram.messaging.common.ChannelType;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.MessageImpl;
import io.eventuate.tram.messaging.consumer.MessageConsumer;
import io.eventuate.tram.messaging.consumer.MessageHandler;
import io.eventuate.tram.messaging.consumer.MessageSubscription;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import javax.jms.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class MessageConsumerActiveMQImpl implements MessageConsumer {

  private Logger logger = LoggerFactory.getLogger(getClass());

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private DuplicateMessageDetector duplicateMessageDetector;

  private ActiveMQConnectionFactory connectionFactory;

  private Connection connection;
  private Session session;
  private List<javax.jms.MessageConsumer> consumers = new ArrayList<>();
  private List<Future<Void>> processingFutures = new ArrayList<>();
  private Map<String, ChannelType> messageModes;

  private AtomicBoolean runFlag = new AtomicBoolean(true);

  public MessageConsumerActiveMQImpl(String url) {
    this(url, Collections.emptyMap());
  }

  public MessageConsumerActiveMQImpl(String url, Map<String, ChannelType> messageModes) {
    this.messageModes = messageModes;
    connectionFactory = new ActiveMQConnectionFactory(url);
    try {
      connection = connectionFactory.createConnection();
      connection.setExceptionListener(e -> logger.error(e.getMessage(), e));
      connection.start();
      session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
    } catch (JMSException e) {
      logger.error(e.getMessage(), e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public MessageSubscription subscribe(String subscriberId, Set<String> channels, MessageHandler handler) {
    try {
      List<javax.jms.MessageConsumer> subscriptionConsumers = new ArrayList<>();
      for (String channel : channels) {
        ChannelType mode = messageModes.getOrDefault(channel, ChannelType.TOPIC);

        String destinationName = mode == ChannelType.TOPIC ?
                String.format("Consumer.%s.VirtualTopic.%s", subscriberId, channel) :
                channel;

        Destination destination = session.createQueue(destinationName);

        javax.jms.MessageConsumer consumer = session.createConsumer(destination);
        consumers.add(consumer);
        subscriptionConsumers.add(consumer);

        processingFutures.add(CompletableFuture.supplyAsync(() -> process(subscriberId, consumer, handler)));
      }
      return () -> {
        subscriptionConsumers.forEach(consumer -> {
          try {
            consumer.close();
          } catch (JMSException e) {
            throw new RuntimeException(e);
          }
        });
      };
    } catch (JMSException e) {
      logger.error(e.getMessage(), e);
      throw new RuntimeException(e);
    }
  }

  private Void process(String subscriberId, javax.jms.MessageConsumer consumer, MessageHandler handler) {
    while (runFlag.get()) {
      try {
        javax.jms.Message message = consumer.receive(100);

        if (message == null) {
          continue;
        }

        TextMessage textMessage = (TextMessage) message;
        Message tramMessage = JSonMapper.fromJson(textMessage.getText(), MessageImpl.class);

        transactionTemplate.execute(ts -> {
          if (duplicateMessageDetector.isDuplicate(subscriberId, tramMessage.getId())) {
            logger.trace("Duplicate message {} {}", subscriberId, tramMessage.getId());
            acknowledge(textMessage);
            return null;
          }

          try {
            logger.trace("Invoking handler {} {}", subscriberId, tramMessage.getId());
            handler.accept(tramMessage);
            logger.trace("handled message {} {}", subscriberId, tramMessage.getId());
          } catch (Throwable t) {
            logger.trace("Got exception {} {}", subscriberId, tramMessage.getId());
            logger.trace("Got exception ", t);
          } finally {
            acknowledge(textMessage);
          }

          return null;
        });

      } catch (JMSException e) {
        logger.error(e.getMessage(), e);
      }
    }

    try {
      consumer.close();
    } catch (JMSException e) {
      logger.error(e.getMessage(), e);
    }

    return null;
  }

  private void acknowledge(TextMessage textMessage) {
    try {
      textMessage.acknowledge();
    } catch (JMSException e) {
      logger.error(e.getMessage(), e);
    }
  }

  public void close() {
    runFlag.set(false);

    processingFutures.forEach(f -> {
      try {
        f.get();
      } catch (InterruptedException | ExecutionException e) {
        logger.error(e.getMessage(), e);
      }
    });

    try {
      session.close();
      connection.close();
    } catch (JMSException e) {
      logger.error(e.getMessage(), e);
    }
  }
}