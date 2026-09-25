package com.ultra.dit.stage1;

import java.io.IOException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import com.ultra.dit.aop.LoggingAspect;

public class TransformServiceTest extends BaseGeometryTest {

    private ParseService parseService;

    private TransformService transformService;

    @BeforeEach
    void setUp() {
        var parseServiceSpy = Mockito.spy(new ParseService(OBJECT_MAPPER));
        AspectJProxyFactory parseServiceFactory = new AspectJProxyFactory(parseServiceSpy);
        parseServiceFactory.addAspect(new LoggingAspect());
        parseServiceFactory.setProxyTargetClass(true);
        parseService = parseServiceFactory.getProxy();

        var transformServiceSpy = Mockito.spy(new TransformService());
        AspectJProxyFactory transformServiceFactory = new AspectJProxyFactory(transformServiceSpy);
        transformServiceFactory.addAspect(new LoggingAspect());
        transformServiceFactory.setProxyTargetClass(true);
        transformService = transformServiceFactory.getProxy();
    }

    @Test
    void testTransform() throws IOException {
        for (var resource : datasetResources) {
            try (var featuresInputStream = resource.getInputStream();
                    var parsed = parseService.streamFeatures(featuresInputStream)) {
                var originFeatures = parsed.collect(Collectors.toList());

                // 1. Прямой перевод 4326 -> 32637
                var transformed4326To32637Stream = transformService.transform4326To32637(originFeatures.stream());
                var transformed4326To32637 = transformed4326To32637Stream.collect(Collectors.toList());

                // 2. Обратный перевод 32637 -> 4326
                var transformed32637To4326Stream = transformService
                        .transform32637To4326(transformed4326To32637.stream());
                var transformed32637To4326 = transformed32637To4326Stream.collect(Collectors.toList());

                double tolerance = 1E-10;
                assertFeatureGeometriesNotEqual(transformed4326To32637, transformed32637To4326, tolerance);
                assertFeatureGeometriesEqual(originFeatures, transformed32637To4326, tolerance);
            }
        }
    }

    @Test
    void testBaseTransform() {
        // 1. Прямой перевод 4326 -> 32637
        var transformed4326To32637Stream = transformService.transform4326To32637(featuresBase.stream());
        var transformed4326To32637 = transformed4326To32637Stream.collect(Collectors.toList());

        // 2. Обратный перевод 32637 -> 4326
        var transformed32637To4326Stream = transformService.transform32637To4326(transformed4326To32637.stream());
        var transformed32637To4326 = transformed32637To4326Stream.collect(Collectors.toList());

        double tolerance = 1E-10;
        assertFeatureGeometriesNotEqual(transformed4326To32637, transformed32637To4326, tolerance);
        assertFeatureGeometriesEqual(featuresBase, transformed32637To4326, tolerance);
    }

    @Test
    void testOrganizerTransform() {
        // 1. Прямой перевод 4326 -> 32637
        var transformed4326To32637Stream = transformService.transform4326To32637(featuresBase.stream());
        var transformed4326To32637 = transformed4326To32637Stream.collect(Collectors.toList());

        // 2. Обратный перевод 32637 -> 4326
        var transformed32637To4326Stream = transformService.transform32637To4326(transformed4326To32637.stream());
        var transformed32637To4326 = transformed32637To4326Stream.collect(Collectors.toList());

        double tolerance = 1E-10;
        assertFeatureGeometriesNotEqual(transformed4326To32637, transformed32637To4326, tolerance);
        assertFeatureGeometriesEqual(featuresBase, transformed32637To4326, tolerance);
    }

}
