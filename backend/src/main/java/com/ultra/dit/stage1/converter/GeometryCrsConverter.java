package com.ultra.dit.stage1.converter;

import java.util.stream.Stream;

import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import com.ultra.dit.dto.geojson.Feature;
import com.ultra.dit.dto.geojson.FeatureProperties;

public class GeometryCrsConverter {

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
   * @param inputStream
   * @return
   * @throws Exception
   */
  public Stream<Feature<? extends FeatureProperties>> convert(
      Stream<Feature<? extends FeatureProperties>> inputStream) {
    return inputStream.parallel()
        .map(feature -> {
          if (feature == null || feature.getGeometry() == null) {
            return feature;
          }

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

          return Feature.of(geometry, feature.getProperties());
        })
        .onClose(() -> {
          // КРИТИЧЕСКИ ВАЖНО: Вешаем хук очистки ThreadLocal на закрытие самого Стрима.
          // Когда Оркестратор закончит выполнение (.forEachOrdered()), Java автоматически
          // вызовет этот коллбэк во ВСЕХ задействованных потоках пула.
          if (threadContext != null) {
            threadContext.remove();
          }
        });
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
