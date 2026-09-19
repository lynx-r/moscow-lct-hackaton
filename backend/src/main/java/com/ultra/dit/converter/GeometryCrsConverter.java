package com.ultra.dit.converter;

import java.util.stream.Collectors;

import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import com.ultra.dit.dto.Feature;
import com.ultra.dit.dto.FeatureCollection;

public class GeometryCrsConverter implements AutoCloseable {

  private final int SRID;

  // 1. Создаем ThreadLocal для потокобезопасного переиспользования тяжелых
  // объектов внутри потоков
  private final ThreadLocal<TransformContext> threadContext;

  public GeometryCrsConverter(String sourceCrsName, String targetCrsName) {
    this.SRID = Integer.valueOf(targetCrsName.split(":")[1].trim());

    var crsFactory = new CRSFactory();
    // Исходная система: WGS 84 (градусы)
    var sourceCrs = crsFactory.createFromName(sourceCrsName);
    // Целевая система: WGS 84 / UTM zone 37N (метры)
    var targetCrs = crsFactory.createFromName(targetCrsName);

    var transformFactory = new CoordinateTransformFactory();

    threadContext = ThreadLocal.withInitial(() -> {
      // Предполагается, что srcCrs и dstCrs объявлены на уровне класса и
      // потокобезопасны
      var localTransform = transformFactory.createTransform(sourceCrs, targetCrs);
      return new TransformContext(localTransform);
    });
  }

  /**
   * Сервис для преобразования геометрии из CoordinateReferenceSystem
   * sourceCrsName в targetCrsName
   * 
   * @param inputCollection
   * @return
   * @throws Exception
   */
  public FeatureCollection convert(FeatureCollection inputCollection) {

    final var transformedFeatures = inputCollection.getFeatures().parallelStream()
        .map(feature -> {
          var geometry = feature.getGeometry().copy();

          geometry.apply(new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence seq, int i) {
              // Извлекаем контекст, выделенный специально для текущего потока
              var ctx = threadContext.get();

              ctx.src.x = seq.getX(i);
              ctx.src.y = seq.getY(i);

              ctx.transform.transform(ctx.src, ctx.dst);

              seq.setOrdinate(i, CoordinateSequence.X, ctx.dst.x);
              seq.setOrdinate(i, CoordinateSequence.Y, ctx.dst.y);
            }

            @Override
            public boolean isDone() {
              return false;
            }

            @Override
            public boolean isGeometryChanged() {
              return true;
            }
          });

          geometry.geometryChanged();
          geometry.setSRID(SRID);

          return new Feature(geometry, feature.getProperties(), feature.getType());
        })
        .collect(Collectors.toList());

    return new FeatureCollection(transformedFeatures, inputCollection.getType());
  }

  // Метод для очистки ресурсов текущего потока (или при уничтожении бина)
  @Override
  public void close() {
    if (threadContext != null) {
      threadContext.remove(); // Удаляет данные из текущего потока и предотвращает утечки
    }
  }

  // Вспомогательный класс-контейнер для одного потока
  private static class TransformContext {
    final CoordinateTransform transform;
    final ProjCoordinate src = new ProjCoordinate();
    final ProjCoordinate dst = new ProjCoordinate();

    TransformContext(CoordinateTransform transform) {
      this.transform = transform;
    }
  }

}
