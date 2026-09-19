package com.ultra.dit.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ErrorResponse {
  private String message;
  private int status;
  private long timestamp;

  public ErrorResponse(String message, int status) {
    this.message = message;
    this.status = status;
    this.timestamp = System.currentTimeMillis();
  }
}
