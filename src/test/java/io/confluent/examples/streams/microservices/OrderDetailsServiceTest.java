package io.confluent.examples.streams.microservices;

import static io.confluent.examples.streams.avro.microservices.OrderState.CREATED;
import static io.confluent.examples.streams.avro.microservices.OrderValidationResult.FAIL;
import static io.confluent.examples.streams.avro.microservices.OrderValidationResult.PASS;
import static io.confluent.examples.streams.avro.microservices.OrderValidationType.ORDER_DETAILS_CHECK;
import static io.confluent.examples.streams.avro.microservices.Product.JUMPERS;
import static io.confluent.examples.streams.avro.microservices.Product.UNDERPANTS;
import static io.confluent.examples.streams.microservices.domain.Schemas.Topics;
import static io.confluent.examples.streams.microservices.domain.beans.OrderId.id;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

import io.confluent.examples.streams.avro.microservices.Order;
import io.confluent.examples.streams.avro.microservices.OrderValidation;
import io.confluent.examples.streams.microservices.domain.Schemas;
import io.confluent.examples.streams.microservices.util.MicroserviceTestUtils;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OrderDetailsServiceTest extends MicroserviceTestUtils {

  private List<Order> orders;
  private List<OrderValidation> expected;
  private OrderDetailsService orderValService;


  @Before
  public void startKafkaCluster() throws Exception {
    if (!CLUSTER.isRunning()) {
      CLUSTER.start();
    }
    CLUSTER.createTopic(Topics.ORDERS.name());
    CLUSTER.createTopic(Topics.ORDER_VALIDATIONS.name());
    Schemas.configureSerdesWithSchemaRegistryUrl(CLUSTER.schemaRegistryUrl());
  }

  @Test
  public void shouldPassAndFailOrders() throws Exception {
    //Given
    orderValService = new OrderDetailsService();

    orders = asList(
        new Order(id(0L), 0L, CREATED, UNDERPANTS, 3, 5.00d), //should pass
        new Order(id(1L), 0L, CREATED, JUMPERS, -1, 75.00d) //should fail
    );
    sendOrders(orders);

    //When
    orderValService.start(CLUSTER.bootstrapServers());

    //Then the second order for Jumpers should have been 'rejected' as it's out of stock
    expected = asList(
        new OrderValidation(id(0L), ORDER_DETAILS_CHECK, PASS),
        new OrderValidation(id(1L), ORDER_DETAILS_CHECK, FAIL)
    );
    assertThat(MicroserviceTestUtils.read(Topics.ORDER_VALIDATIONS, 2, CLUSTER.bootstrapServers()))
        .isEqualTo(expected);
  }

  @Test
  public void shouldCorrectlyProgressOffset() throws InterruptedException {

    //Given 1 initial order
    orderValService = new OrderDetailsService();
    orderValService.start(CLUSTER.bootstrapServers());
    sendOrders(asList(new Order(id(0L), 0L, CREATED, UNDERPANTS, 3, 5.00d)));
    MicroserviceTestUtils.read(Topics.ORDER_VALIDATIONS, 1, CLUSTER.bootstrapServers()); //block

    //When sending second order
    sendOrders(asList(new Order(id(1L), 0L, CREATED, UNDERPANTS, 3, 5.00d)));

    //Then two orders should be validated
    assertThat(MicroserviceTestUtils.read(Topics.ORDER_VALIDATIONS, 2, CLUSTER.bootstrapServers()))
        .isEqualTo(asList(
            new OrderValidation(id(0L), ORDER_DETAILS_CHECK, PASS),
            new OrderValidation(id(1L), ORDER_DETAILS_CHECK, PASS)
        ));

    //When sending third order
    sendOrders(asList(new Order(id(2L), 0L, CREATED, UNDERPANTS, 3, 5.00d)));

    //block until order is processed
    MicroserviceTestUtils.read(Topics.ORDER_VALIDATIONS, 3, CLUSTER.bootstrapServers());

    //And then restarting the order validation service
    orderValService.stop();
    orderValService = new OrderDetailsService();
    orderValService.start(CLUSTER.bootstrapServers());

    //Then three orders should now have been validated
    assertThat(MicroserviceTestUtils.read(Topics.ORDER_VALIDATIONS, 3, CLUSTER.bootstrapServers()))
        .isEqualTo(asList(
            new OrderValidation(id(0L), ORDER_DETAILS_CHECK, PASS),
            new OrderValidation(id(1L), ORDER_DETAILS_CHECK, PASS),
            new OrderValidation(id(2L), ORDER_DETAILS_CHECK, PASS)
        ));
  }

  @After
  public void tearDown() {
    orderValService.stop();
    CLUSTER.stop();
  }
}