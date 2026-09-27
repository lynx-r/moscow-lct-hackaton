package com.ultra.dit.service.calculation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultra.dit.dto.geojson.FeatureCollection;
import com.ultra.dit.dto.geojson.enums.GeoJsonType;
import com.ultra.dit.exception.SolverException;

@Primary
@Service
@ConditionalOnProperty(name = "app.routing.backend", havingValue = "solver", matchIfMissing = true)
public class PythonRoutingCalculationService implements RoutingCalculationService {
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient client;

    public PythonRoutingCalculationService(ObjectMapper mapper,
            @Value("${app.routing.solver-url:http://localhost:8090}") String baseUrl,
            @Value("${app.routing.timeout-seconds:900}") long timeoutSeconds) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/solve");
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public FeatureCollection calculate(FeatureCollection request) {
        try {
            var httpRequest = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build();
            var response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                var status = response.statusCode();
                HttpStatus mapped = status == 422 ? HttpStatus.UNPROCESSABLE_ENTITY
                        : status == 413 ? HttpStatus.PAYLOAD_TOO_LARGE
                                : status == 503 ? HttpStatus.SERVICE_UNAVAILABLE
                                        : status == 504 ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
                String message = "Routing solver returned HTTP " + status;
                try {
                    message = mapper.readTree(response.body()).path("error").asText(message);
                } catch (IOException ignored) {
                    // Preserve the status even if an upstream proxy returned non-JSON.
                }
                throw new SolverException(mapped, message);
            }
            var result = mapper.readValue(response.body(), FeatureCollection.class);
            if (result == null || (result.getType() != GeoJsonType.FEATURE_COLLECTION)
                    || result.getFeatures() == null) {
                throw new SolverException(HttpStatus.BAD_GATEWAY, "Invalid solver response");
            }
            return result;
        } catch (HttpTimeoutException e) {
            throw new SolverException(HttpStatus.GATEWAY_TIMEOUT, "Routing solver timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SolverException(HttpStatus.SERVICE_UNAVAILABLE, "Routing request interrupted");
        } catch (IOException e) {
            throw new SolverException(HttpStatus.BAD_GATEWAY, "Routing solver unavailable or returned invalid JSON");
        }
    }
}
