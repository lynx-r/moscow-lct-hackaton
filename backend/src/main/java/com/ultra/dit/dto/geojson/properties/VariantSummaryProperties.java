package com.ultra.dit.dto.geojson.properties;

import java.math.BigDecimal;
import java.util.List;

import com.ultra.dit.dto.geojson.enums.ObjectType;
import com.ultra.dit.util.Id;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
public class VariantSummaryProperties extends FeatureProperties {
  /** 1 – лучший вариант */
  private Integer rank;

  /** Стоимость новых участков тепловой сети, руб. */
  private BigDecimal constructionCost;

  /** Стоимость строительства новых тепловых камер, руб. */
  private BigDecimal chamberConstructionCost;

  /** Суммарная стоимость врезок в существующую тепловую сеть, руб. */
  private BigDecimal tieInCost;

  /** Стоимость реконструкции существующих линейных участков, руб. */
  private BigDecimal reconstructionCost;

  /**
   * Стоимость реконструкции существующих тепловых камер, для которых требуется
   * реконструкция, руб.
   */
  private BigDecimal chamberReconstructionCost;

  /** Суммарный штраф, руб. */
  private BigDecimal unconnectedPenalty;

  /**
   * Стоимость новых линейных участков плюс стоимость новых камер плюс стоимость
   * врезок плюс стоимость реконструкции линейных участков плюс стоимость
   * реконструкции камер плюс штраф за неподключенные ОКС, руб.
   */
  private BigDecimal calculatedCost;

  /** Суммарная длина новых линейных участков, м */
  private Double newNetworkLength;

  /** Суммарная длина реконструируемых линейных частей, м */
  private Double reconstructionLength;

  /** Длина новой сети плюс длина реконструкции, м */
  private Double length;

  /** Значение S по разделу 9 */
  private Double score;

  /** ID ОКС, для которых маршрут не найден */
  private List<String> unconnectedOksIds;

  /**
   * Подключен ли вариант
   */
  @Builder.Default
  private boolean isConnected = true;

  public static VariantSummaryProperties emptyVariant(String id, String variantId, List<String> unconnectedOksIds) {
    return VariantSummaryProperties.builder()
        .id(id)
        .variantId(variantId)
        .unconnectedOksIds(unconnectedOksIds)
        .objectType(ObjectType.VARIANT_SUMMARY)
        .isConnected(false).build();
  }

  public static VariantSummaryProperties notConnected(int clusterId, List<String> unconnectedOksIds) {
    var id = Id.idForTypeVariantSummary(clusterId, 0);
    var variantId = Id.variantId(clusterId, 0);
    return emptyVariant(id, variantId, unconnectedOksIds);
  }

}
