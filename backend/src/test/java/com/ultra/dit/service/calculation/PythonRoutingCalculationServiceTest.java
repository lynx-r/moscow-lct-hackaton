package com.ultra.dit.service.calculation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.ultra.dit.config.JacksonConfig;
import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.FeatureCollection;
import com.ultra.dit.dto.geojson.properties.FeatureProperties;
import com.ultra.dit.dto.geojson.properties.HeatChamberProperties;
import com.ultra.dit.dto.geojson.properties.SourceProperties;
import com.ultra.dit.dto.geojson.properties.VariantSummaryProperties;
import com.ultra.dit.exception.SolverException;
import com.ultra.dit.service.RoutingFlowService;

class PythonRoutingCalculationServiceTest {
    private final ObjectMapper mapper = new JacksonConfig().configureObjectMapper().build();

    @Test
    void forwardsWgs84PreservesSummaryAndClosesInput() throws Exception {
        var received = new AtomicReference<FeatureCollection>();
        var summaryVariant = VariantSummaryProperties.builder().id("summary")
                .variantId("1").score(23.2).build();
        var summary = Feature.of(null, summaryVariant);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/solve", exchange -> {
            received.set(mapper.readValue(exchange.getRequestBody(), FeatureCollection.class));
            var collection = FeatureCollection.of(List.of(summary));
            byte[] payload = mapper.writeValueAsBytes(collection);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try {
            var service = new PythonRoutingCalculationService(mapper,
                    "http://127.0.0.1:" + server.getAddress().getPort(), 5);
            var pointG = new GeometryFactory().createPoint(new Coordinate(37.6344054041544, 55.6994810644531));
            var pointProps = SourceProperties.builder().id("opaque-id").build();
            var point = Feature.of(pointG, pointProps);
            var closed = new AtomicBoolean();
            Stream<? extends Feature<? extends FeatureProperties>> pointStream = Stream.of(point)
                    .onClose(() -> closed.set(true));
            var result = new RoutingFlowService(service).buildRoute(pointStream);
            assertTrue(closed.get());
            assertEquals(37.6344054041544, received.get().getFeatures().get(0).getGeometry().getCoordinate().x);
            assertEquals("opaque-id", received.get().getFeatures().get(0).getProperties().getId());
            assertNull(result.getFeatureCollection().getFeatures().get(0).getGeometry());
            VariantSummaryProperties properties = (VariantSummaryProperties) result.getFeatureCollection().getFeatures()
                    .get(0).getProperties();
            assertEquals(23.2, properties.getScore());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesValidationAndBusyStatuses() throws Exception {
        for (int code : new int[] { 422, 413, 503, 504, 500 }) {
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
                        () -> service.calculate(FeatureCollection.of(List.of())));
                assertEquals(code == 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.valueOf(code), error.getStatus());
                assertEquals("test diagnostic", error.getMessage());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void preservesOutputCoordinatePrecision() throws Exception {
        var pointG = new GeometryFactory().createPoint(new Coordinate(37.6334941974, 55.6986594949));
        var chamberProps = HeatChamberProperties.builder().id("new-chamber").build();
        var point = Feature.of(pointG, chamberProps);
        var serialized = mapper.readTree(mapper.writeValueAsBytes(point));
        assertEquals(37.6334941974, serialized.path("geometry").path("coordinates").get(0).doubleValue());
        assertEquals(55.6986594949, serialized.path("geometry").path("coordinates").get(1).doubleValue());
    }

    @Test
    void closesStreamOnFailure() {
        var closed = new AtomicBoolean();
        var flow = new RoutingFlowService(input -> {
            throw new SolverException(HttpStatus.BAD_GATEWAY, "failed");
        });
        assertThrows(SolverException.class,
                () -> flow.buildRoute(
                        Stream.<Feature<? extends FeatureProperties>>empty().onClose(() -> closed.set(true))));
        assertTrue(closed.get());
    }
}
