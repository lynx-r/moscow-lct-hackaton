package com.ultra.dit.stage1;

import java.io.IOException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class TransformServiceTest extends BaseGeometryTest {

    @InjectMocks
    private TransformService transformService;

    @Test
    void testTransform() throws IOException {
        for (var resource : datasetResources) {
            var featuresInputStream = resource.getInputStream();
            var testFeatures = PARSE_SERVICE.streamFeatures(featuresInputStream).collect(Collectors.toList());

            // 1. Прямой перевод 4326 -> 32637
            var transformed4326To32637Stream = transformService.transform4326To32637(testFeatures.stream());
            var transformed4326To32637 = transformed4326To32637Stream.collect(Collectors.toList());

            // 2. Обратный перевод 32637 -> 4326
            var transformed32637To4326Stream = transformService.transform32637To4326(transformed4326To32637.stream());
            var transformed32637To4326 = transformed32637To4326Stream.collect(Collectors.toList());

            double tolerance = 1E-10;
            assertFeatureGeometriesNotEqual(transformed4326To32637, transformed32637To4326, tolerance);
            assertFeatureGeometriesEqual(testFeatures, transformed32637To4326, tolerance);
        }
    }

    @Test
    void testBaseTransformFeatureCollection() {
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
    void testOrganizerTransformFeatureCollection() {
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
