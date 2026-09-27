package com.ultra.dit.service.calculation;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;
import com.ultra.dit.exception.SolverException;
import com.ultra.dit.service.RoutingFlowService;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import com.ultra.dit.config.JacksonConfig;
import org.springframework.http.HttpStatus;

class PythonRoutingCalculationServiceTest {
    private final ObjectMapper mapper = new JacksonConfig().configureObjectMapper().build();

    @Test
    void forwardsWgs84PreservesSummaryAndClosesInput() throws Exception {
        var received = new AtomicReference<FeatureCollection>();
        var summary = new Feature(null, Map.of("id", "summary", "object_type", "variant_summary",
                "variant_id", "1", "score", 23.2), "Feature");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/solve", exchange -> {
            received.set(mapper.readValue(exchange.getRequestBody(), FeatureCollection.class));
            byte[] payload = mapper.writeValueAsBytes(new FeatureCollection(List.of(summary), "FeatureCollection"));
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try {
            var service = new PythonRoutingCalculationService(mapper,
                    "http://127.0.0.1:" + server.getAddress().getPort(), 5);
            var point = new Feature(new GeometryFactory().createPoint(new Coordinate(37.6344054041544, 55.6994810644531)),
                    Map.of("id", "opaque-id", "object_type", "source"), "Feature");
            var closed = new AtomicBoolean();
            var result = new RoutingFlowService(service).buildRoute(Stream.of(point).onClose(() -> closed.set(true)));
            assertTrue(closed.get());
            assertEquals(37.6344054041544, received.get().getFeatures().get(0).getGeometry().getCoordinate().x);
            assertEquals("opaque-id", received.get().getFeatures().get(0).getProperties().get("id"));
            assertNull(result.getFeatureCollection().getFeatures().get(0).getGeometry());
            assertEquals(23.2, result.getFeatureCollection().getFeatures().get(0).getProperties().get("score"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesValidationAndBusyStatuses() throws Exception {
        for (int code : new int[] {422, 413, 503, 504, 500}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/solve", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] payload = "{\"error\":\"test diagnostic\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            server.start();
            try {
                var service = new PythonRoutingCalculationService(mapper,
                        "http://127.0.0.1:" + server.getAddress().getPort(), 5);
                var error = assertThrows(SolverException.class,
                        () -> service.calculate(new FeatureCollection(List.of(), "FeatureCollection")));
                assertEquals(code == 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.valueOf(code), error.getStatus());
                assertEquals("test diagnostic", error.getMessage());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void preservesOutputCoordinatePrecision() throws Exception {
        var point = new Feature(new GeometryFactory().createPoint(
                new Coordinate(37.6334941974, 55.6986594949)), Map.of("id", "new-chamber"), "Feature");
        var serialized = mapper.readTree(mapper.writeValueAsBytes(point));
        assertEquals(37.6334941974, serialized.path("geometry").path("coordinates").get(0).doubleValue());
        assertEquals(55.6986594949, serialized.path("geometry").path("coordinates").get(1).doubleValue());
    }

    @Test
    void closesStreamOnFailure() {
        var closed = new AtomicBoolean();
        var flow = new RoutingFlowService(input -> { throw new SolverException(HttpStatus.BAD_GATEWAY, "failed"); });
        assertThrows(SolverException.class, () -> flow.buildRoute(Stream.<Feature>empty().onClose(() -> closed.set(true))));
        assertTrue(closed.get());
    }
}
