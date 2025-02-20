package com.taxonic.carml.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Disabled("Causes hang in github actions build")
class ReactiveInputStreamsTest {

  @SuppressWarnings("java:S2925")
  @Test
  void blockHoundWorks() {
    try {
      FutureTask<?> task = new FutureTask<>(() -> {
        Thread.sleep(0);
        return "";
      });
      Schedulers.parallel()
          .schedule(task);

      task.get(10, TimeUnit.SECONDS);
      assertThat("should fail", false);
    } catch (ExecutionException | InterruptedException | TimeoutException e) {
      assertThat("detected", e.getCause() instanceof BlockingOperationError);
    }
  }

  @Test
  void inputStreamFromColdFluxTest() throws IOException, InterruptedException {
    // Given
    String inputString = "foo bar alice bob";
    InputStream inputStream = IOUtils.toInputStream(inputString, StandardCharsets.UTF_8);
    Flux<DataBuffer> dataBufferFlux = ReactiveInputStreams.fluxInputStream(inputStream);
    String out;

    // When
    InputStream input = ReactiveInputStreams.inputStreamFrom(dataBufferFlux);
    out = IOUtils.toString(input, StandardCharsets.UTF_8);

    // Then
    assertThat(out, is(inputString));
  }

  @Test
  void inputStreamFromHotFluxTest() throws IOException {
    // Given
    String inputString = "foo bar alice bob";
    InputStream inputStream = IOUtils.toInputStream(inputString, StandardCharsets.UTF_8);
    ConnectableFlux<DataBuffer> dataBufferFlux = ReactiveInputStreams.fluxInputStream(inputStream)
        .publish();

    // When
    InputStream input1 = ReactiveInputStreams.inputStreamFrom(dataBufferFlux);
    InputStream input2 = ReactiveInputStreams.inputStreamFrom(dataBufferFlux);
    InputStream input3 = ReactiveInputStreams.inputStreamFrom(dataBufferFlux);

    dataBufferFlux.connect();

    String out1 = IOUtils.toString(input1, StandardCharsets.UTF_8);
    String out2 = IOUtils.toString(input2, StandardCharsets.UTF_8);
    String out3 = IOUtils.toString(input3, StandardCharsets.UTF_8);

    // Then
    assertThat(out1, is(inputString));
    assertThat(out2, is(inputString));
    assertThat(out3, is(inputString));
  }

}
