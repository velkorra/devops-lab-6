package org.example;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Startup
@ApplicationScoped
@Path("/work")
@Tag(name = "Work", description = "API для нагрузочного тестирования с rate limiting")
@RegisterForReflection(registerFullHierarchy = true)
public class WorkResource {

  private final Semaphore semaphore;
  private final int apiLimit;
  private final int timeout;

  @Inject MeterRegistry registry;

  public WorkResource(
      @ConfigProperty(name = "app.api.limit") int apiLimit,
      @ConfigProperty(name = "app.api.timeout") int timeout) {
    this.apiLimit = apiLimit;
    this.timeout = timeout;
    this.semaphore = new Semaphore(apiLimit, true);
  }

  @PostConstruct
  void init() {
    Gauge.builder("work.active.requests", this, w -> w.apiLimit - w.semaphore.availablePermits())
        .description("Current number of active requests")
        .register(registry);
  }

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  @Operation(
      summary = "Выполнить работу",
      description =
          "Обрабатывает запрос с задержкой. При превышении лимита запросов возвращает 429")
  @APIResponse(responseCode = "429", description = "Превышен лимит запросов (Too Many Requests)")
  @APIResponse(responseCode = "200", description = "Успешное выполнение задачи")
  public Uni<Response> doWork() {
    if (!semaphore.tryAcquire()) {
      return Uni.createFrom()
          .item(Response.status(429).entity("Too many requests - rate limit exceeded\n").build());
    }
    return Uni.createFrom()
        .item("OK\n")
        .onItem()
        .delayIt()
        .by(Duration.ofMillis(timeout))
        .onItem()
        .transform(result -> Response.ok(result).build())
        .onFailure()
        .invoke(Throwable::printStackTrace)
        .eventually(() -> semaphore.release());
  }

  @GET
  @Path("/status")
  @Produces(MediaType.TEXT_PLAIN)
  @Operation(
      summary = "Получить статус",
      description = "Возвращает текущее количество активных запросов")
  public Uni<Integer> status() {
    return Uni.createFrom().item(apiLimit - semaphore.availablePermits());
  }
}
